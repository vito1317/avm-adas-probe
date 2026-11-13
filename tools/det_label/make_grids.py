#!/usr/bin/env python3
"""
把 own_prelabel.py 產生的 review.jpg 每 4 張拼成一張 2x2 審查圖（格子左上角標 A/B/C/D），
並寫一份 grids.json 記每張圖的 4 個裁切名稱，方便分批給審查。

用法：python tools/det_label/make_grids.py --review ~/avm-data/det_review --out ~/avm-data/det_grids --batches 8
"""
import argparse, glob, json, os
from PIL import Image, ImageDraw, ImageFont

CELL = 720


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--review", required=True); ap.add_argument("--out", required=True); ap.add_argument("--batches", type=int, default=8)
    ap.add_argument("--exclude", default="", help="上一輪的 grids.json（審過的裁切不再審；上一輪標 missing 而重產的除外）")
    ap.add_argument("--prefix", default="g", help="審查圖名稱字首（每輪不同，避免撞名）")
    ap.add_argument("--redo", default="", help="要重審的裁切名稱清單（一行一個）")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    try: font = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial Bold.ttf", 40)
    except Exception: font = ImageFont.load_default()
    names = sorted(os.path.basename(f)[:-11] for f in glob.glob(os.path.join(a.review, "*.review.jpg")))
    if a.exclude:
        done = {n for ex in a.exclude.split(",") for g in json.load(open(ex)) for n in g["cells"]}
        redo = set(open(a.redo).read().split()) if a.redo else set()
        names = [n for n in names if n not in done or n in redo]
    grids = []
    for gi in range(0, len(names), 4):
        part = names[gi:gi + 4]; gname = a.prefix + "%05d" % (gi // 4)
        im = Image.new("RGB", (CELL * 2, CELL * 2), (40, 40, 40)); d = ImageDraw.Draw(im)
        for k, n in enumerate(part):
            x, y = (k % 2) * CELL, (k // 2) * CELL
            im.paste(Image.open(os.path.join(a.review, n + ".review.jpg")).resize((CELL, CELL)), (x, y))
            d.rectangle([x, y, x + 50, y + 50], fill="black"); d.text((x + 10, y + 3), "ABCD"[k], fill="yellow", font=font)
            d.rectangle([x, y, x + CELL - 1, y + CELL - 1], outline="yellow", width=2)
        im.save(os.path.join(a.out, gname + ".jpg"), quality=88)
        grids.append(dict(grid=gname, cells=part))
    json.dump(grids, open(os.path.join(a.out, "grids.json"), "w"))
    per = (len(grids) + a.batches - 1) // a.batches
    for b in range(a.batches):
        open(os.path.join(a.out, "batch_%d.txt" % b), "w").write("\n".join(g["grid"] for g in grids[b * per:(b + 1) * per]))
    print(len(names), "張裁切 →", len(grids), "張審查圖，分", a.batches, "批，每批約", per)


if __name__ == "__main__":
    main()
