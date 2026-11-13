#!/usr/bin/env python3
"""
車頭朝向標註：從審查過的車框（det_ds 的 own_* 標註）挑車，裁出來拼成 4x4 的標註圖（每格編號 1～16）。

標的是「在畫面上看到的車頭朝向」（跟鏡頭的相對角度，8 個方向），360 端再加上鏡頭射線的方位換成真正的方向：
  F  車頭正對鏡頭        B  車尾正對鏡頭（車頭朝遠方）
  L  車頭朝畫面左邊（看到車的側面）  R  車頭朝畫面右邊
  FL 車頭朝鏡頭、偏畫面左  FR 車頭朝鏡頭、偏畫面右
  BL 車頭朝遠方、偏畫面左  BR 車頭朝遠方、偏畫面右
  x  看不出來（太小、太糊、只剩一角）

輸出：sheets/o00000.jpg 與 sheets.json（每張圖每格對應的影像、框）。
用法：python tools/det_label/orient_sheets.py --ds ~/avm-data/det_ds3 --out ~/avm-data/orient --n 2400
"""
import argparse, glob, json, os, random
from PIL import Image, ImageDraw, ImageFont

VEH = {2, 4, 5}          # car / bus / truck
CELL = 300


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ds", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("--n", type=int, default=2400); ap.add_argument("--min_px", type=int, default=36)
    ap.add_argument("--batches", type=int, default=6)
    a = ap.parse_args()
    random.seed(3)
    items = []
    for split in ("train", "val"):
        for lf in sorted(glob.glob(os.path.join(a.ds, "labels", split, "own_*.txt"))):
            img = lf.replace("/labels/", "/images/")[:-4] + ".jpg"
            if not os.path.exists(img): continue
            for line in open(lf):
                p = line.split()
                if len(p) != 5 or int(p[0]) not in VEH: continue
                items.append((split, img, [float(v) for v in p[1:]]))
    # 依影像大小過濾、每張裁切最多取 2 台（多樣）
    S = 540
    ok = [it for it in items if it[2][2] * S >= a.min_px and it[2][3] * S >= a.min_px * 0.6]
    random.shuffle(ok)
    per_img = {}; pick = []
    for it in ok:
        if per_img.get(it[1], 0) >= 2: continue
        per_img[it[1]] = per_img.get(it[1], 0) + 1; pick.append(it)
        if len(pick) >= a.n: break
    os.makedirs(os.path.join(a.out, "sheets"), exist_ok=True)
    try: font = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial Bold.ttf", 34)
    except Exception: font = ImageFont.load_default()
    sheets = []
    for si in range(0, len(pick), 16):
        part = pick[si:si + 16]; name = "o%05d" % (si // 16)
        sh = Image.new("RGB", (CELL * 4, CELL * 4), (30, 30, 30)); d = ImageDraw.Draw(sh)
        cells = []
        for k, (split, img, (cx, cy, w, h)) in enumerate(part):
            im = Image.open(img).convert("RGB"); W = im.size[0]
            x1, y1, x2, y2 = (cx - w / 2) * W, (cy - h / 2) * W, (cx + w / 2) * W, (cy + h / 2) * W
            pw, ph = (x2 - x1) * 0.15, (y2 - y1) * 0.15
            crop = im.crop((max(0, x1 - pw), max(0, y1 - ph), min(W, x2 + pw), min(W, y2 + ph)))
            sc = min((CELL - 8) / crop.size[0], (CELL - 40) / crop.size[1])     # 小車也放大到填滿格子
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
    print(len(items), "個車框，挑", len(pick), "→", len(sheets), "張標註圖，每批", per)


if __name__ == "__main__":
    main()
