#!/usr/bin/env python3
"""
路面箭頭候選：在每張影格的俯視圖（candidates.py 產生的 *_bev.png，300x350、每像素 0.04 m、車頭朝上）找「一塊白漆」，
不是細長的線（車道線寬約 0.15 m）而是寬 0.3～2 m、長 1.2～6.5 m、不是實心長方形的白色區域 → 裁出來給人分類。

輸出：out/crops/*.png（每個候選放大裁切）、out/cands.jsonl（影格、位置 x/z 公尺、大小）、out/sheets/*.jpg（3x2 拼圖，左上編號）。
用法：python tools/lane_label/arrow_cands.py --review ~/avm-data/label_review,~/avm-data/label_review2,~/avm-data/label_review3,~/avm-data/label_review4 --out ~/avm-data/arrows
"""
import argparse, glob, json, os
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy import ndimage

W, H, MPP, CX, CY = 300, 350, 0.04, 150.0, 175.0
EGO_HW, EGO_HL = 1.2, 2.7


def find(bev):
    a = np.asarray(bev.convert("RGB"), np.float32)
    g = a.mean(2); sat = a.max(2) - a.min(2)
    loc = ndimage.uniform_filter(g, 31)
    m = (g > loc + 28) & (g > 110) & (sat < 60)
    # 自車與太遠（投影糊掉）的地方不要
    ys, xs = np.mgrid[0:H, 0:W]
    x = (xs + 0.5 - CX) * MPP; z = (CY - ys - 0.5) * MPP
    m &= ~((np.abs(x) < EGO_HW) & (np.abs(z) < EGO_HL)) & (np.abs(z) < 5.0) & (np.abs(x) < 4.5)
    m = ndimage.binary_opening(m, iterations=1)
    lab, n = ndimage.label(ndimage.binary_closing(m, iterations=2))
    out = []
    for i, sl in enumerate(ndimage.find_objects(lab)):
        if sl is None: continue
        comp = lab[sl] == i + 1
        h = (sl[0].stop - sl[0].start) * MPP; w = (sl[1].stop - sl[1].start) * MPP
        area = comp.sum() * MPP * MPP; fill = comp.mean()
        if not (0.3 <= w <= 2.0 and 1.2 <= h <= 6.5 and 0.35 <= area <= 4.5 and 0.12 <= fill <= 0.75): continue
        # 形狀：箭頭有一段明顯比較寬的「頭」，車道線從頭到尾一樣寬
        rw = comp.sum(1) * MPP; rw = rw[rw > 0]
        if len(rw) < 10: continue
        med = float(np.median(rw)); mx = float(np.percentile(rw, 95))
        if med > 0.6 or mx < 1.8 * med or mx < 0.45: continue
        cy = (sl[0].start + sl[0].stop) / 2; cx = (sl[1].start + sl[1].stop) / 2
        out.append(dict(x=round((cx - CX) * MPP, 2), z=round((CY - cy) * MPP, 2), w=round(w, 2), h=round(h, 2),
                        box=[sl[1].start, sl[0].start, sl[1].stop, sl[0].stop]))
    return out


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--review", required=True); ap.add_argument("--out", required=True)
    a = ap.parse_args()
    os.makedirs(os.path.join(a.out, "crops"), exist_ok=True); os.makedirs(os.path.join(a.out, "sheets"), exist_ok=True)
    seen = set(); cands = []
    for rd in a.review.split(","):
        for bp in sorted(glob.glob(os.path.join(os.path.expanduser(rd), "*_bev.png"))):
            key = os.path.basename(bp)[:-8]
            if key in seen: continue
            seen.add(key)
            bev = Image.open(bp).convert("RGB")
            for k, c in enumerate(find(bev)):
                x1, y1, x2, y2 = c["box"]; pad = 12
                crop = bev.crop((max(0, x1 - pad), max(0, y1 - pad), min(W, x2 + pad), min(H, y2 + pad)))
                sc = min(180 / crop.size[0], 260 / crop.size[1])
                crop = crop.resize((max(1, int(crop.size[0] * sc)), max(1, int(crop.size[1] * sc))), Image.NEAREST)
                name = f"{key}_{k}"
                crop.save(os.path.join(a.out, "crops", name + ".png"))
                cands.append(dict(name=name, frame=key, **{kk: c[kk] for kk in ("x", "z", "w", "h")}))
    try: font = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial Bold.ttf", 26)
    except Exception: font = ImageFont.load_default()
    CW, CH = 200, 290
    with open(os.path.join(a.out, "cands.jsonl"), "w") as f:
        for c in cands: f.write(json.dumps(c) + "\n")
    sheets = []
    for si in range(0, len(cands), 6):
        part = cands[si:si + 6]; sh = Image.new("RGB", (CW * 3, CH * 2), (25, 25, 25)); d = ImageDraw.Draw(sh)
        for k, c in enumerate(part):
            im = Image.open(os.path.join(a.out, "crops", c["name"] + ".png"))
            ox, oy = (k % 3) * CW, (k // 3) * CH
            sh.paste(im, (ox + (CW - im.size[0]) // 2, oy + 28 + (CH - 28 - im.size[1]) // 2))
            d.rectangle([ox, oy, ox + CW - 1, oy + CH - 1], outline=(200, 200, 0), width=2)
            d.text((ox + 6, oy + 2), str(k + 1), fill="yellow", font=font)
        name = "a%05d" % (si // 6); sh.save(os.path.join(a.out, "sheets", name + ".jpg"), quality=90)
        sheets.append(dict(sheet=name, cells=[c["name"] for c in part]))
    json.dump(sheets, open(os.path.join(a.out, "sheets.json"), "w"))
    print(len(seen), "張俯視圖，箭頭候選", len(cands), "→", len(sheets), "張分類圖")


if __name__ == "__main__":
    main()
