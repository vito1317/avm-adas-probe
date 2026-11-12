#!/usr/bin/env python3
"""
把審查留下的車道線「吸附」到真正的漆上，修正位置偏差。

候選線是在俯視圖上擬合出來的：俯視圖在車身附近拉長、模糊，擬合常偏到漆的邊緣或護欄底部（實測偏 0.3～0.5 m；
四顆鏡頭的對位其實只差約 7 公分，不是對位的問題）。審查只判斷「是不是這條線」，不改位置。

做法：每張影格每顆鏡頭跑車道線模型，取「是漆」的像素換到地面（離鏡頭 4 m 內）；
每條留下的線，收它 0.5 m 內的漆點重新擬合（x = a·z + b；線比較橫的改用 z = a·x + b），點夠才換。
結果寫進標註的 "geom"：{線 id: [x1, z1, x2, z2]}（build_masks.py 會優先用它），原始候選不動。

用法：python tools/lane_label/snap_labels.py --labels L1,L2,L3 --review R1,R2,R3 --train ~/avm-data/train --model ~/avm-data/lane_runs/v2/best.pt
"""
import argparse, glob, json, os, sys
import numpy as np
import torch
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, os.path.dirname(HERE)); sys.path.insert(0, HERE)
import avm_geometry as ag   # noqa: E402
import train as T           # noqa: E402
from linecal import paint_pixels, NEAR_M   # noqa: E402

SNAP_M = 0.5
MIN_PTS = 40


def paint_ground(geo, pix):
    pts = []
    for cam in geo.cams:
        u, v = pix[cam.quad]
        if len(u) == 0: continue
        gx, gz, ok, _ = ag.pixel_to_ground(cam, u, v, 960, 540)
        d = np.hypot(gx - cam.px, gz - cam.pz)
        body = (np.abs(gx) < geo.car_width / 2 + 0.1) & (np.abs(gz) < geo.car_length / 2 + 0.1)
        k = ok & (d < NEAR_M) & ~body
        pts.append(np.stack([gx[k], gz[k]], 1))
    return np.concatenate(pts, 0) if pts else np.zeros((0, 2))


def snap(c, pts):
    x1, z1, x2, z2 = c["x1"], c["z1"], c["x2"], c["z2"]
    dx, dz = x2 - x1, z2 - z1; L = np.hypot(dx, dz)
    if L < 0.3 or len(pts) == 0: return None
    ux, uz = dx / L, dz / L
    rel = pts - np.array([x1, z1])
    t = rel @ np.array([ux, uz]); n = rel @ np.array([-uz, ux])
    m = (np.abs(n) < SNAP_M) & (t > -1.5) & (t < L + 1.5)
    if m.sum() < MIN_PTS: return None
    P = pts[m]
    if abs(dz) >= abs(dx):        # 縱向線：x = a z + b
        A = np.vstack([P[:, 1], np.ones(len(P))]).T; a, b = np.linalg.lstsq(A, P[:, 0], rcond=None)[0]
        if abs(a) > 0.8: return None
        return [float(a * z1 + b), z1, float(a * z2 + b), z2], float(np.median(np.abs(n[m])))
    A = np.vstack([P[:, 0], np.ones(len(P))]).T; a, b = np.linalg.lstsq(A, P[:, 1], rcond=None)[0]
    if abs(a) > 0.8: return None
    return [x1, float(a * x1 + b), x2, float(a * x2 + b)], float(np.median(np.abs(n[m])))


def main():
    ap = argparse.ArgumentParser()
    for k in ["labels", "review", "train", "model"]: ap.add_argument("--" + k, required=True)
    a = ap.parse_args()
    net = T.LaneNet(pretrained=False); net.load_state_dict(torch.load(a.model, map_location="cpu")); net.eval()
    geo_c = {}; moved = []; n_lines = n_snapped = 0
    for lab_dir, rev_dir in zip(a.labels.split(","), a.review.split(",")):
        for f in sorted(glob.glob(os.path.join(lab_dir, "out_*.jsonl"))):
            recs = []
            for line in open(f):
                line = line.strip()
                if not line: continue
                try: L = json.loads(line)
                except Exception: continue
                recs.append(L)
            for L in recs:
                if not L.get("keep"): continue
                cand = json.load(open(os.path.join(rev_dir, L["img"][:-4] + ".cand.json")))
                sess, fr = cand["session"], cand["frame"]
                if sess not in geo_c: geo_c[sess] = ag.Geometry(json.load(open(os.path.join(a.train, sess, "session.json")))["calib"])
                geo = geo_c[sess]
                frame = np.asarray(Image.open(os.path.join(a.train, sess, "f_%05d.jpg" % fr)).convert("RGB"))
                pts = paint_ground(geo, paint_pixels(net, frame))
                byid = {str(c["id"]): c for c in cand["cands"]}
                geom = {}
                for cid in L["keep"]:
                    c = byid.get(str(cid))
                    if c is None: continue
                    n_lines += 1
                    r = snap(c, pts)
                    if r is None: continue
                    g, spread = r
                    # 移動量（線中點的距離）
                    mx0, mz0 = (c["x1"] + c["x2"]) / 2, (c["z1"] + c["z2"]) / 2
                    mx1, mz1 = (g[0] + g[2]) / 2, (g[1] + g[3]) / 2
                    d = float(np.hypot(mx1 - mx0, mz1 - mz0))
                    if d > SNAP_M: continue
                    geom[str(cid)] = [round(v, 3) for v in g]; n_snapped += 1; moved.append(d)
                L["geom"] = geom
            with open(f, "w") as fo:
                for L in recs: fo.write(json.dumps(L, ensure_ascii=False) + "\n")
    mv = np.array(moved) if moved else np.zeros(1)
    print(f"車道線 {n_lines} 條，吸附到漆上 {n_snapped} 條；移動量中位數 {np.median(mv):.3f} m、90% {np.percentile(mv, 90):.3f} m")


if __name__ == "__main__":
    main()
