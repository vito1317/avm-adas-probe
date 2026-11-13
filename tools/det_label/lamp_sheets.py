#!/usr/bin/env python3
"""
車燈狀態標註：從審查過的車框挑車，拼成 4x4 標註圖（同 orient_sheets.py 的格式）。

亮著的燈很少見，隨便挑幾乎都是「沒亮」→ 依車框裡「很紅」「很琥珀色」的像素比例加權抽樣：
一半挑紅／琥珀色多的（可能有燈亮），一半隨機（教模型「沒亮」長什麼樣，包括紅色的車、琥珀色路燈照到的車）。

每格標三件事（見 LAMP_GUIDE.md）：brake（煞車燈亮）、left（畫面左側的方向燈亮）、right（畫面右側的方向燈亮），
各為 1／0／x（看不到那顆燈或看不出來）。

用法：python tools/det_label/lamp_sheets.py --ds ~/avm-data/det_ds3 --out ~/avm-data/lamp --n 2400
"""
import argparse, glob, json, os, random
import numpy as np
from PIL import Image, ImageDraw, ImageFont

VEH = {2, 4, 5}
CELL = 300


def lamp_score(im, box):
    W = im.size[0]; cx, cy, w, h = box
    c = np.asarray(im.crop((int((cx - w / 2) * W), int((cy - h / 2) * W), int((cx + w / 2) * W), int((cy + h / 2) * W))).resize((48, 32)), np.float32)
    r, g, b = c[..., 0], c[..., 1], c[..., 2]
    red = (r > 150) & (r > g * 1.6) & (r > b * 1.6)
    amber = (r > 170) & (g > 90) & (g < r * 0.85) & (b < g * 0.6)
    return float(red.mean() + 2 * amber.mean())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ds", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("--n", type=int, default=2400); ap.add_argument("--min_px", type=int, default=40)
    ap.add_argument("--batches", type=int, default=8)
    a = ap.parse_args()
    random.seed(5)
    items = []
    for split in ("train", "val"):
        for lf in sorted(glob.glob(os.path.join(a.ds, "labels", split, "own_*.txt"))):
            img = lf.replace("/labels/", "/images/")[:-4] + ".jpg"
            if not os.path.exists(img): continue
            for line in open(lf):
                p = line.split()
                if len(p) != 5 or int(p[0]) not in VEH: continue
                box = [float(v) for v in p[1:]]
                if box[2] * 540 >= a.min_px and box[3] * 540 >= a.min_px * 0.6: items.append((split, img, box))
    random.shuffle(items)
    pool = items[:12000]
    scored = []
    for k, it in enumerate(pool):
        scored.append((lamp_score(Image.open(it[1]).convert("RGB"), it[2]), it))
    scored.sort(key=lambda t: -t[0])
    hot = [it for s, it in scored[:a.n // 2]]
    rest = [it for s, it in scored[a.n // 2:]]
    pick = hot + random.sample(rest, min(len(rest), a.n - len(hot)))
    random.shuffle(pick)
    os.makedirs(os.path.join(a.out, "sheets"), exist_ok=True)
    try: font = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial Bold.ttf", 34)
    except Exception: font = ImageFont.load_default()
    sheets = []
    for si in range(0, len(pick), 16):
        part = pick[si:si + 16]; name = "l%05d" % (si // 16)
        sh = Image.new("RGB", (CELL * 4, CELL * 4), (30, 30, 30)); d = ImageDraw.Draw(sh)
        cells = []
        for k, (split, img, (cx, cy, w, h)) in enumerate(part):
            im = Image.open(img).convert("RGB"); W = im.size[0]
            x1, y1, x2, y2 = (cx - w / 2) * W, (cy - h / 2) * W, (cx + w / 2) * W, (cy + h / 2) * W
            pw, ph = (x2 - x1) * 0.15, (y2 - y1) * 0.15
            crop = im.crop((max(0, x1 - pw), max(0, y1 - ph), min(W, x2 + pw), min(W, y2 + ph)))
            sc = min((CELL - 8) / crop.size[0], (CELL - 40) / crop.size[1])
            crop = crop.resize((max(1, int(crop.size[0] * sc)), max(1, int(crop.size[1] * sc))), Image.BICUBIC)
            ox, oy = (k % 4) * CELL, (k // 4) * CELL
            sh.paste(crop, (ox + (CELL - crop.size[0]) // 2, oy + 36 + (CELL - 40 - crop.size[1]) // 2))
            d.rectangle([ox, oy, ox + CELL - 1, oy + CELL - 1], outline=(200, 200, 0), width=2)
            d.text((ox + 6, oy + 2), str(k + 1), fill="yellow", font=font)
            cells.append(dict(split=split, img=img, box=[cx, cy, w, h]))
        sh.save(os.path.join(a.out, "sheets", name + ".jpg"), quality=90)
        sheets.append(dict(sheet=name, cells=cells))
    json.dump(sheets, open(os.path.join(a.out, "sheets.json"), "w"))
    per = (len(sheets) + a.batches - 1) // a.batches
    for b in range(a.batches):
        open(os.path.join(a.out, "batch_%d.txt" % b), "w").write("\n".join(s["sheet"] for s in sheets[b * per:(b + 1) * per]) + "\n")
    print(len(items), "個車框，挑", len(pick), "（紅／琥珀色多的", len(hot), "）→", len(sheets), "張標註圖，每批", per)


if __name__ == "__main__":
    main()
