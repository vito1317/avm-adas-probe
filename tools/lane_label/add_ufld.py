#!/usr/bin/env python3
"""
在已經產生好的候選（candidates.py＋add_regions.py）上，再加 UFLDv2（後鏡頭）找到的車道線當候選。
UFLDv2 在前鏡頭幾乎抓不到（鏡頭太低、晚上大燈），後鏡頭跟審查結果吻合約 84%（20 張實測）→ 只當候選，一樣要審查。

用法：python tools/lane_label/add_ufld.py --review ~/avm-data/label_review3 --train ~/avm-data/train \
        --ufld ~/avm-data/ext/ufld2 --weights ~/avm-data/ext/ufld2/weights/culane_res18.pth
"""
import argparse, glob, json, math, os, sys
import numpy as np
import torch
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, os.path.dirname(HERE)); sys.path.insert(0, HERE)
import avm_geometry as ag                       # noqa: E402
from candidates import font, dedupe, COLORS, W, H, MPP, CX, CY   # noqa: E402
import ufld_teacher as U                        # noqa: E402


def fit_line(pts):
    """地面點 (x, z) → 直線段（x = a·z + b），範圍取點的 z 範圍；點太少或太散回 None。"""
    if len(pts) < 5: return None
    P = np.array(pts); z = P[:, 1]; x = P[:, 0]
    A = np.vstack([z, np.ones_like(z)]).T
    (a, b), res, *_ = np.linalg.lstsq(A, x, rcond=None)
    rms = math.sqrt(float(np.mean((A @ np.array([a, b]) - x) ** 2)))
    if rms > 0.35 or abs(a) > 0.6: return None          # 不像一條直的車道線
    z0, z1 = float(z.min()), float(z.max())
    if z1 - z0 < 1.5: return None
    return dict(x1=a * z0 + b, z1=z0, x2=a * z1 + b, z2=z1)


def main():
    ap = argparse.ArgumentParser()
    for k in ["review", "train", "ufld", "weights"]: ap.add_argument("--" + k, required=True)
    a = ap.parse_args()
    dev = torch.device("mps" if torch.backends.mps.is_available() else "cpu")
    net = U.load_ufld(a.ufld, a.weights, dev)
    f_big, f_sm = font(18), font(14)
    geo_c = {}; n_add = 0; n_img = 0
    for cj in sorted(glob.glob(os.path.join(a.review, "*.cand.json"))):
        base = cj[:-10]; cand = json.load(open(cj))
        if any(c.get("src") == "ufld" for c in cand["cands"]): continue        # 已經加過
        sess, fr = cand["session"], cand["frame"]
        if sess not in geo_c: geo_c[sess] = ag.Geometry(json.load(open(os.path.join(a.train, sess, "session.json")))["calib"])
        geo = geo_c[sess]
        frame = np.asarray(Image.open(os.path.join(a.train, sess, "f_%05d.jpg" % fr)).convert("RGB"))
        cam = geo.cam_for_quad(1)
        view, rays = U.make_view(frame, cam, True)
        new = []
        for ln in U.ufld_lanes(net, view, dev):
            g = [U.pix_to_ground(cam, rays, x, y) for x, y in ln]
            # 只用車後 2.4～6.5 m（之前跟審查結果比對驗證過的範圍；更遠投影誤差大，線會往中間歪）
            g = [p for p in g if p is not None and -6.5 < p[1] < -2.4 and abs(p[0]) < 5.0]
            L = fit_line(g)
            if L: new.append(dict(k="w", d=False, src="ufld", **L))
        merged = dedupe([dict(c) for c in cand["cands"]] + new)
        added = [c for c in merged if c.get("src") == "ufld"]
        if not added: n_img += 1; json.dump(cand, open(cj, "w")); continue
        nid = max([c["id"] for c in cand["cands"]] + [0])
        for c in added: nid += 1; c["id"] = nid
        cand["cands"] += added
        json.dump(cand, open(cj, "w"))
        # 畫上去（同 candidates.py 的配色與編號規則）
        im = Image.open(base + ".png").convert("RGB"); d = ImageDraw.Draw(im)
        P = lambda x, z: ((CX + x / MPP) * 2, (CY - z / MPP) * 2 + 30)
        qw, qh = 960, 540; ox0 = W * 2 + 10
        for c in added:
            col = COLORS[(c["id"] - 1) % len(COLORS)]
            d.line([P(c["x1"], c["z1"]), P(c["x2"], c["z2"])], fill=col, width=3)
            px, py = P((c["x1"] + c["x2"]) / 2, (c["z1"] + c["z2"]) / 2)
            d.rectangle([px - 13, py - 11, px + 13, py + 11], fill=(0, 0, 0)); d.text((px - 10, py - 10), str(c["id"]), fill=col, font=f_big)
            for cm in geo.cams:
                ox, oy = ox0 + (cm.quad % 2) * (qw // 2), 30 + (cm.quad // 2) * (qh // 2)
                t = np.linspace(0, 1, 60)
                gx = c["x1"] + (c["x2"] - c["x1"]) * t; gz = c["z1"] + (c["z2"] - c["z1"]) * t
                u, v, th, ok, _ = ag.ground_to_pixel(cm, gx, gz, qw // 2, qh // 2)
                pts = [(ox + uu, oy + vv) for uu, vv, kk in zip(u, v, ok) if kk]
                if len(pts) >= 2:
                    d.line(pts, fill=col, width=2); mx, my = pts[len(pts) // 2]; d.text((mx + 3, my - 8), str(c["id"]), fill=col, font=f_sm)
        im.save(base + ".png"); n_add += len(added); n_img += 1
    print(f"{n_img} 張，加了 {n_add} 條 UFLDv2 候選")


if __name__ == "__main__":
    main()
