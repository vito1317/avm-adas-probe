#!/usr/bin/env python3
"""
用車道線校正四顆魚眼的角度（yaw / pitch / roll）。

想法：國道直線行駛時，同一條車道線不管從前、側、後哪顆鏡頭換算到地面，都應該落在同一條直線上，
而且所有線彼此平行（跟車身差一個共同的小角度 a_f）。以前的 selfcal.py 比對重疊區的紋理，
在國道單調的路面上找不到依據（實測前鏡頭被調了 7°、重疊區相似度只有 0.07 → 0.12，不可信）。

步驟：
  1. 挑直線、高速的影格；每顆鏡頭跑車道線模型，取「是漆」的像素（離鏡頭 4 m 內，投影準）。
  2. 用目前的校正把像素換到地面，依橫向位置把點分成一條一條線。
  3. 最佳化：每顆鏡頭的 Δyaw/Δpitch/Δroll（左鏡頭固定當基準，避免整體一起轉的自由度），
     讓每條線上的點 x_i ≈ a_f·z_i + b_k（同一影格所有線共用斜率 a_f），穩健損失＋小角度先驗。
  4. 輸出新的校正檔與校正前後每條線的殘差、各鏡頭之間的橫向落差。

用法：python tools/lane_label/linecal.py --train ~/avm-data/train --session train_20261111_073336 \
        --model ~/avm-data/lane_runs/v2/best.pt --calib START.json --out FITTED.json [--n 60]
"""
import argparse, copy, glob, json, math, os, sys
import numpy as np
import torch
from PIL import Image
from scipy.optimize import least_squares

HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, os.path.dirname(HERE)); sys.path.insert(0, HERE)
import avm_geometry as ag   # noqa: E402
import train as T           # noqa: E402

NAMES = ["front", "rear", "left", "right"]
REF = "left"                 # 基準鏡頭（不動）
NEAR_M = 4.0


def pick_frames(sd, n):
    out = []
    for jf in sorted(glob.glob(os.path.join(sd, "f_*.json"))):
        try: m = json.load(open(jf))
        except Exception: continue
        if (m.get("speed") or 0) < 60 or abs(m.get("steer") or 0) > 8 or m.get("turn"): continue
        jp = jf[:-5] + ".jpg"
        if os.path.exists(jp): out.append(jp)
    step = max(1, len(out) // n)
    return out[::step][:n]


def paint_pixels(net, frame):
    """每顆鏡頭：模型說是漆的像素（象限 960x540 座標）。"""
    res = {}
    for q in range(4):
        ox, oy = (q % 2) * 960, (q // 2) * 540
        im = Image.fromarray(frame[oy:oy + 540, ox:ox + 960]).resize((480, 270), Image.BILINEAR)
        x = np.zeros((272, 480, 3), np.float32); x[:270] = np.asarray(im, np.float32) / 255.0
        t = (torch.from_numpy(x).permute(2, 0, 1) - T.MEAN) / T.STD
        with torch.no_grad():
            p = torch.softmax(net(t[None]), 1)[0, 1].numpy()
        vv, uu = np.nonzero(p[:270] > 0.6)
        res[q] = (uu * 2.0 + 1.0, vv * 2.0 + 1.0)
    return res


def geo_with(calib, delta):
    c = copy.deepcopy(calib)
    for i, n in enumerate(NAMES):
        if n == REF: continue
        d = delta[i * 3:(i + 1) * 3]
        c["cams"][n]["yaw"] += float(d[0]); c["cams"][n]["pitch"] += float(d[1]); c["cams"][n]["roll"] += float(d[2])
    return ag.Geometry(c)


def ground_points(geo, pix):
    """→ 每顆鏡頭 (x, z) 陣列（離鏡頭 NEAR_M 內、車身外）。"""
    out = {}
    for cam in geo.cams:
        u, v = pix[cam.quad]
        if len(u) == 0: out[cam.quad] = np.zeros((0, 2)); continue
        gx, gz, ok, _ = ag.pixel_to_ground(cam, u, v, 960, 540)
        d = np.hypot(gx - cam.px, gz - cam.pz)
        body = (np.abs(gx) < geo.car_width / 2 + 0.1) & (np.abs(gz) < geo.car_length / 2 + 0.1)
        keep = ok & (d < NEAR_M) & ~body
        out[cam.quad] = np.stack([gx[keep], gz[keep]], 1)
    return out


def cluster(pts_all):
    """所有鏡頭的點依 x 分成一條一條線（直線行駛，線大致沿 z）。回傳每個點的線編號（-1 = 不用）。"""
    x = pts_all[:, 0]
    if len(x) == 0: return np.zeros(0, int)
    bins = np.round(x / 0.15).astype(int)
    vals, counts = np.unique(bins, return_counts=True)
    peaks = []
    for b, c in sorted(zip(vals, counts), key=lambda t: -t[1]):
        if c < 30: break
        if all(abs(b - p) > 4 for p in peaks): peaks.append(b)     # 線與線至少隔 0.6 m
    lab = np.full(len(x), -1)
    for k, p in enumerate(peaks):
        lab[np.abs(x - p * 0.15) < 0.4] = k
    return lab


def ground_fixed(geo, pix):
    """同一批像素（不再篩選）→ 地面 (x, z)；投不到地面的給 NaN。殘差向量長度才固定（最佳化要求）。"""
    out = []
    for cam in geo.cams:
        u, v = pix[cam.quad]
        if len(u) == 0: continue
        gx, gz, ok, _ = ag.pixel_to_ground(cam, u, v, 960, 540)
        gx = np.where(ok, gx, np.nan); gz = np.where(ok, gz, np.nan)
        out.append(np.stack([gx, gz], 1))
    return np.concatenate(out, 0) if out else np.zeros((0, 2))


def residuals(delta, calib, frames_pix, labels, n_lines, prior=0.02):
    geo = geo_with(calib, delta[:12])
    res = []
    off = 12
    for fi, pix in enumerate(frames_pix):
        pts = ground_fixed(geo, pix)
        lab = labels[fi]
        a = delta[off]; bs = delta[off + 1: off + 1 + n_lines[fi]]
        off += 1 + n_lines[fi]
        m = lab >= 0
        r = pts[m, 0] - (a * pts[m, 1] + bs[lab[m]])
        r = np.where(np.isfinite(r), r, 0.0)
        res.append(r[::3])                 # 抽樣減量
    res.append(np.asarray(delta[:12]) * prior * 10)   # 小角度先驗（度）
    return np.concatenate(res)


def main():
    ap = argparse.ArgumentParser()
    for k in ["train", "session", "model", "calib", "out"]: ap.add_argument("--" + k, required=True)
    ap.add_argument("--n", type=int, default=60)
    a = ap.parse_args()
    calib = json.load(open(a.calib)); calib = calib.get("calib", calib)
    net = T.LaneNet(pretrained=False); net.load_state_dict(torch.load(a.model, map_location="cpu")); net.eval()
    frames = pick_frames(os.path.join(a.train, a.session), a.n)
    print("直線高速影格", len(frames))
    geo0 = ag.Geometry(calib)
    frames_pix, labels, n_lines = [], [], []
    for fp in frames:
        pix = paint_pixels(net, np.asarray(Image.open(fp).convert("RGB")))
        g = ground_points(geo0, pix)
        # 固定每顆鏡頭的像素順序：只保留在初始校正下可見的像素（之後的評估用同一批）
        for cam in geo0.cams:
            u, v = pix[cam.quad]
            gx, gz, ok, _ = ag.pixel_to_ground(cam, u, v, 960, 540)
            d = np.hypot(gx - cam.px, gz - cam.pz)
            body = (np.abs(gx) < geo0.car_width / 2 + 0.1) & (np.abs(gz) < geo0.car_length / 2 + 0.1)
            keep = ok & (d < NEAR_M) & ~body
            pix[cam.quad] = (u[keep], v[keep])
        pts = ground_fixed(geo0, pix)
        lab = cluster(pts)
        # 至少要有一條線同時被兩顆以上鏡頭看到，才有校正資訊
        cams_of = np.concatenate([np.full(len(pix[c.quad][0]), c.quad) for c in geo0.cams])
        multi = [k for k in range(lab.max() + 1) if len(set(cams_of[lab == k])) >= 2] if len(lab) else []
        if not multi: continue
        lab = np.where(np.isin(lab, multi), lab, -1)
        remap = {k: i for i, k in enumerate(multi)}
        lab = np.array([remap.get(int(v), -1) for v in lab])
        frames_pix.append(pix); labels.append(lab); n_lines.append(len(multi))
    print("可用影格", len(frames_pix), "線數", sum(n_lines))
    x0 = np.zeros(12 + sum(1 + n for n in n_lines))
    # 線的初值：各自 x 中位數
    off = 12
    for fi, pix in enumerate(frames_pix):
        pts = ground_fixed(geo0, pix)
        for k in range(n_lines[fi]):
            x0[off + 1 + k] = np.median(pts[labels[fi] == k, 0])
        off += 1 + n_lines[fi]
    r0 = residuals(x0, calib, frames_pix, labels, n_lines)
    sol = least_squares(residuals, x0, args=(calib, frames_pix, labels, n_lines), loss="soft_l1", f_scale=0.1, max_nfev=60, verbose=0)
    r1 = residuals(sol.x, calib, frames_pix, labels, n_lines)
    k = len(r0) - 12
    print("線上點的橫向殘差（中位數絕對值，m）：校正前 %.3f → 校正後 %.3f" % (np.median(np.abs(r0[:k])), np.median(np.abs(r1[:k]))))
    out = copy.deepcopy(calib)
    for i, n in enumerate(NAMES):
        if n == REF: continue
        d = sol.x[i * 3:(i + 1) * 3]
        print("  %-5s Δyaw %+.2f°  Δpitch %+.2f°  Δroll %+.2f°" % (n, d[0], d[1], d[2]))
        out["cams"][n]["yaw"] = round(out["cams"][n]["yaw"] + d[0], 3)
        out["cams"][n]["pitch"] = round(out["cams"][n]["pitch"] + d[1], 3)
        out["cams"][n]["roll"] = round(out["cams"][n]["roll"] + d[2], 3)
    out["note"] = (out.get("note", "") + "；linecal：國道直線車道線校正").strip("；")
    json.dump(out, open(a.out, "w"), indent=1, ensure_ascii=False)
    print("→", a.out)


if __name__ == "__main__":
    main()
