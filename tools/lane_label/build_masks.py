#!/usr/bin/env python3
"""
把審過的標註（out_*.jsonl）投影回四顆魚眼畫面，產生分割訓練資料。

每張影格 → 4 張 480x272 的鏡頭影像（前/後/左/右）＋同尺寸遮罩：
  0 = 背景、1 = 白線、2 = 黃線、255 = 不計分（不知道答案的地方，模型猜什麼都不扣分）。
不計分的地方：
  - 魚眼外圈的黑邊以外都算「看得到」，但地面離鏡頭超過 [FAR_M]、或射線打不到地面（天空、建築）→ 不計分
    （候選線只在近處找，遠處有線也沒被標，不能當成「沒有線」來教）。
  - 審查說這張有漏標（missing 不是空的）或看不清楚（ok=false）→ 只教「線在哪裡」，背景全部不計分。
用法：
  python tools/lane_label/build_masks.py --labels ~/avm-data/labels --review ~/avm-data/label_review \
      --train ~/avm-data/train --out ~/avm-data/lane_ds
實車影像只放在本機，不要 commit。
"""
import argparse, glob, json, os, sys
import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import avm_geometry as ag   # noqa: E402

OW, OH = 480, 272            # 每顆鏡頭輸出大小（960x540 縮一半，下面補 2 列）
FAR_M = 3.0                  # 離鏡頭多遠以內的地面才當成「有標」（規則在 3 m 內最可靠；更遠有線也可能沒被標）
LINE_W = 4                   # 線寬（輸出像素）
CLS = {"w": 1, "y": 2}


def known_region(geo):
    """每顆鏡頭（quad）在輸出解析度下，哪些像素的地面點在 FAR_M 以內（有標註的範圍）。"""
    out = {}
    qw, qh = 960, 540
    us = (np.arange(OW) + 0.5) * (qw / OW)
    vs = (np.arange(OH) + 0.5) * 2.0          # 輸出第 j 列 = 原象限 v ≈ 2j（最後 2 列是補的，v ≥ 540 → 無效）
    U, V = np.meshgrid(us, vs)
    for cam in geo.cams:
        gx, gz, ok, _ = ag.pixel_to_ground(cam, U.ravel(), V.ravel(), qw, qh)
        gx = gx.reshape(OH, OW); gz = gz.reshape(OH, OW); ok = ok.reshape(OH, OW) & (V < qh)
        d = np.hypot(gx - cam.px, gz - cam.pz)
        out[cam.quad] = ok & (d <= FAR_M)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--labels", required=True); ap.add_argument("--review", required=True)
    ap.add_argument("--train", required=True); ap.add_argument("--out", required=True)
    a = ap.parse_args()
    os.makedirs(os.path.join(a.out, "img"), exist_ok=True); os.makedirs(os.path.join(a.out, "mask"), exist_ok=True)
    labels = []
    for f in sorted(glob.glob(os.path.join(a.labels, "out_*.jsonl"))):
        for line in open(f):
            line = line.strip()
            if line:
                try: labels.append(json.loads(line))
                except Exception: pass
    geo_cache, known_cache = {}, {}
    index = []
    for L in labels:
        base = L["img"][:-4]
        cand = json.load(open(os.path.join(a.review, base + ".cand.json")))
        sess, fr = cand["session"], cand["frame"]
        sd = os.path.join(a.train, sess)
        if sess not in geo_cache:
            geo_cache[sess] = ag.Geometry(json.load(open(os.path.join(sd, "session.json")))["calib"])
            known_cache[sess] = known_region(geo_cache[sess])
        geo, known = geo_cache[sess], known_cache[sess]
        frame = Image.open(os.path.join(sd, "f_%05d.jpg" % fr)).convert("RGB")
        byid = {str(c["id"]): c for c in cand["cands"]}
        partial = (not L.get("ok", True)) or bool(L.get("missing"))
        for cam in geo.cams:
            q = cam.quad
            ox, oy = (q % 2) * 960, (q // 2) * 540
            img = frame.crop((ox, oy, ox + 960, oy + 540)).resize((OW, 270), Image.BILINEAR)
            canvas = Image.new("RGB", (OW, OH)); canvas.paste(img, (0, 0))
            m = np.where(known[q], 0, 255).astype(np.uint8)
            if partial:
                m[:] = 255
            mi = Image.fromarray(m); d = ImageDraw.Draw(mi)
            for cid, kind in L.get("keep", {}).items():
                c = byid.get(str(cid))
                if c is None: continue
                cls = CLS.get(kind[0], 1)
                t = np.linspace(0, 1, 200)
                gx = c["x1"] + (c["x2"] - c["x1"]) * t; gz = c["z1"] + (c["z2"] - c["z1"]) * t
                u, v, th, ok, _ = ag.ground_to_pixel(cam, gx, gz, 960, 540)
                near = np.hypot(gx - cam.px, gz - cam.pz) <= FAR_M + 1.0
                pts = [(uu / 2.0, vv / 2.0) for uu, vv, kk, nn in zip(u, v, ok, near) if kk and nn]
                if len(pts) >= 2:
                    d.line(pts, fill=cls, width=LINE_W)
            name = f"{sess}_{fr:05d}_q{q}"
            canvas.save(os.path.join(a.out, "img", name + ".jpg"), quality=92)
            mi.save(os.path.join(a.out, "mask", name + ".png"))
            arr = np.asarray(mi)
            index.append(dict(name=name, session=sess, frame=fr, quad=q, partial=partial,
                              line_px=int(((arr == 1) | (arr == 2)).sum())))
    json.dump(index, open(os.path.join(a.out, "index.json"), "w"))
    n_line = sum(1 for i in index if i["line_px"] > 0)
    print(f"{len(labels)} 張影格 → {len(index)} 張鏡頭影像，{n_line} 張有線，{sum(i['partial'] for i in index)} 張只教線（背景不計分）")


if __name__ == "__main__":
    main()
