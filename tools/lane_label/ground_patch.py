#!/usr/bin/env python3
"""
從前（或後）魚眼直接投影出「車前一段路面」的高解析俯視小圖（比拼接俯視圖清楚很多），給路面箭頭標註與模型用。

範圍：x ∈ [-X, X]（橫向，m）、z ∈ [Z0, Z1]（前方距離，m），每像素 MPP。後鏡頭用 z ∈ [-Z1, -Z0] 並把畫面轉 180°（一樣是行進方向朝上）。
360 端要用一樣的方式產生（同樣的範圍與解析度），模型才對得起來。

用法（拼標註圖）：python tools/lane_label/ground_patch.py --train ~/avm-data/train --picks ~/avm-data/picks4.txt,... --out ~/avm-data/arrow_patches
"""
import argparse, json, os, sys
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, os.path.dirname(HERE))
import avm_geometry as ag   # noqa: E402

X, Z0, Z1, MPP = 5.4, 2.6, 10.6, 0.03     # 3 個車道寬 × 8 m，每像素 3 cm → 360 x 267


def patch(frame, geo, rear=False):
    cam = geo.cam_for_quad(1 if rear else 0)
    nx = int(round(2 * X / MPP)); nz = int(round((Z1 - Z0) / MPP))
    xs = -X + (np.arange(nx) + 0.5) * MPP
    zs = Z1 - (np.arange(nz) + 0.5) * MPP          # 上 = 遠
    gx, gz = np.meshgrid(xs, zs)
    if rear:
        # 後鏡頭：還是「行進方向（往前）朝上」——上 = 離車近（z = -Z0）、下 = 車後遠處（z = -Z1），左右不翻
        # （以前寫成 gx, gz = -gx, -gz：上下顛倒、左右也反了，標註員發現後鏡頭的箭頭都朝下）
        gz = -(Z0 + Z1) + gz
    u, v, _, ok, _ = ag.ground_to_pixel(cam, gx.ravel(), gz.ravel(), 960, 540)
    ox, oy = (cam.quad % 2) * 960, (cam.quad // 2) * 540
    ui = np.clip(u.astype(int), 0, 959) + ox; vi = np.clip(v.astype(int), 0, 539) + oy
    out = frame[vi, ui].reshape(nz, nx, 3).copy()
    out[~ok.reshape(nz, nx)] = 0
    return out


def arrowish(img):
    """路面小圖裡有沒有「像箭頭」的白漆（有一段比較寬的頭）：用來從大量影格裡先挑出值得標的。"""
    from scipy import ndimage
    g = img.astype(np.float32).mean(2); sat = img.max(2).astype(np.float32) - img.min(2)
    m = (g > ndimage.uniform_filter(g, 41) + 25) & (g > 120) & (sat < 60)
    lab, n = ndimage.label(ndimage.binary_closing(ndimage.binary_opening(m), iterations=2))
    for i, sl in enumerate(ndimage.find_objects(lab)):
        if sl is None: continue
        comp = lab[sl] == i + 1
        h = (sl[0].stop - sl[0].start) * MPP; w = (sl[1].stop - sl[1].start) * MPP
        if not (0.25 <= w <= 2.2 and 1.0 <= h <= 6.5): continue
        rw = comp.sum(1) * MPP; rw = rw[rw > 0]
        if len(rw) < 15: continue
        med = float(np.median(rw)); mx = float(np.percentile(rw, 95))
        if med <= 0.45 and mx >= 1.8 * med and mx >= 0.4: return True
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--train", required=True); ap.add_argument("--picks", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("--min_kmh", type=float, default=3.0, help="停著的影格不要（看到的都是同一塊地）")
    ap.add_argument("--scan", type=int, default=0, help="不用 picks：每個 session 每 N 張掃一張，只留像有箭頭的")
    a = ap.parse_args()
    os.makedirs(os.path.join(a.out, "img"), exist_ok=True)
    geo_c = {}; n = 0; seen = set()
    import glob
    if a.scan:
        items = []
        for sd in sorted(glob.glob(os.path.join(a.train, "train_*"))):
            fs = sorted(glob.glob(os.path.join(sd, "f_*.jpg")))
            items += [(sd, int(os.path.basename(f)[2:7])) for f in fs[::a.scan]]
    else:
        items = []
        for pf in a.picks.split(","):
            for line in open(os.path.expanduser(pf)):
                q = line.split()
                if len(q) >= 2: items.append((q[0], int(q[1])))
    for sd, fr in items:
            sess = os.path.basename(sd.rstrip("/"))
            if (sess, fr) in seen: continue
            seen.add((sess, fr))
            jp = os.path.join(a.train, sess, "f_%05d.json" % fr); ip = jp[:-5] + ".jpg"
            if not os.path.exists(ip): continue
            try: meta = json.load(open(jp))
            except Exception: meta = {}
            if (meta.get("speed") or 0) < a.min_kmh or meta.get("gear") in ("P", "R"): continue
            if sess not in geo_c:
                sj = os.path.join(a.train, sess, "session.json")
                if not os.path.exists(sj): continue          # 沒有校正檔的批次（還沒拉完）
                geo_c[sess] = ag.Geometry(json.load(open(sj))["calib"])
            try: frame = np.asarray(Image.open(ip).convert("RGB"))
            except Exception: continue
            for rear in (False, True):
                pimg = patch(frame, geo_c[sess], rear)
                if a.scan and not arrowish(pimg): continue
                Image.fromarray(pimg).save(os.path.join(a.out, "img", f"{sess}_{fr:05d}_{'r' if rear else 'f'}.jpg"), quality=92)
                n += 1
    print("路面小圖", n)


if __name__ == "__main__":
    main()
