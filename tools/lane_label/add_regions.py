#!/usr/bin/env python3
"""
第二輪標註：把 Kotlin 偵測（LocalRegionProbeTest）找到的斑馬線（C#）、網狀線（B#）、停車格（S#）候選
畫進 candidates.py 產生的檢查圖，並寫進 .cand.json 的 "regions"。

用法：python tools/lane_label/add_regions.py --review ~/avm-data/label_review2 --train ~/avm-data/train
實車影像只放本機。
"""
import argparse, glob, json, os, sys
import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, os.path.dirname(HERE)); sys.path.insert(0, HERE)
import avm_geometry as ag   # noqa: E402
from candidates import font, W, H, MPP, CX, CY   # noqa: E402

STYLE = {"cw": ("C", (240, 240, 255)), "bj": ("B", (255, 200, 40)), "slots": ("S", (60, 230, 120))}


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--review", required=True); ap.add_argument("--train", required=True)
    a = ap.parse_args()
    f_sm, f_big = font(14), font(18)
    geo_c = {}
    n = 0
    for cj in sorted(glob.glob(os.path.join(a.review, "*.cand.json"))):
        base = cj[:-10]
        rj = base + "_regions.json"
        if not os.path.exists(rj):
            continue
        cand = json.load(open(cj)); reg = json.load(open(rj))
        sess = cand["session"]
        if sess not in geo_c:
            geo_c[sess] = ag.Geometry(json.load(open(os.path.join(a.train, sess, "session.json")))["calib"])
        geo = geo_c[sess]
        regions = []
        for key, (pre, col) in STYLE.items():
            for k, r in enumerate(reg.get(key, [])):
                regions.append(dict(id=f"{pre}{k + 1}", type=key, p=r["p"], col=col, occ=r.get("o")))
        cand["regions"] = [{k: v for k, v in r.items() if k != "col"} for r in regions]
        json.dump(cand, open(cj, "w"))
        if not regions:
            n += 1; continue
        im = Image.open(base + ".png").convert("RGB"); d = ImageDraw.Draw(im)
        P = lambda x, z: ((CX + x / MPP) * 2, (CY - z / MPP) * 2 + 30)
        for r in regions:
            p = r["p"]; pts = [P(p[i * 2], p[i * 2 + 1]) for i in range(4)]
            d.line(pts + [pts[0]], fill=r["col"], width=3)
            cx = sum(q[0] for q in pts) / 4; cy = sum(q[1] for q in pts) / 4
            d.rectangle([cx - 15, cy - 11, cx + 15, cy + 11], fill=(0, 0, 0)); d.text((cx - 12, cy - 10), r["id"], fill=r["col"], font=f_big)
        # 魚眼：多邊形四邊細分後投影
        qw, qh = 960, 540; ox0 = W * 2 + 10
        for cam in geo.cams:
            ox, oy = ox0 + (cam.quad % 2) * (qw // 2), 30 + (cam.quad // 2) * (qh // 2)
            for r in regions:
                p = r["p"]; xs, zs = [], []
                for i in range(4):
                    x0, z0, x1, z1 = p[i * 2], p[i * 2 + 1], p[(i + 1) % 4 * 2], p[(i + 1) % 4 * 2 + 1]
                    t = np.linspace(0, 1, 15); xs += list(x0 + (x1 - x0) * t); zs += list(z0 + (z1 - z0) * t)
                u, v, th, ok, _ = ag.ground_to_pixel(cam, np.array(xs), np.array(zs), qw // 2, qh // 2)
                pts = [(ox + uu, oy + vv) for uu, vv, kk in zip(u, v, ok) if kk]
                if len(pts) >= 2:
                    d.line(pts, fill=r["col"], width=2)
                    mx, my = pts[len(pts) // 2]; d.text((mx + 3, my - 8), r["id"], fill=r["col"], font=f_sm)
        im.save(base + ".png"); n += 1
    print(n, "張加上區域候選")


if __name__ == "__main__":
    main()
