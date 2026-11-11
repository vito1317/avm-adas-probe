#!/usr/bin/env python3
"""
把 woodscape_ds.py 轉出的 WoodScape 標線遮罩改成跟我們一樣：只留細長的線條、細化成中心線再加粗到 4 像素。
WoodScape 的 lanemarks 是整塊漆（又是 1280 寬縮下來的），比我們的標註粗很多，還包含箭頭、停車格、斜紋；
直接拿來預訓練，模型學會「線很粗」，微調後精確度反而下降（實測 F1 0.53 → 0.44）。
授權：同 woodscape_ds.py（只放本機）。
用法：python tools/lane_label/woodscape_thin.py --src ~/avm-data/ws_ds --out ~/avm-data/ws_thin
"""
import argparse, json, os, shutil
from multiprocessing import Pool
import numpy as np
from PIL import Image
from scipy import ndimage
from skimage.morphology import skeletonize

LINE_W = 4          # 跟 build_masks.py 一樣
MIN_LEN = 30        # 骨架至少這麼多像素（約 30 px ≈ 魚眼裡一段夠長的線）
MAX_W = 14          # 原本那塊漆平均寬度超過這個（px）→ 大塊標記（斑馬線、箭頭、斜紋），丟掉


def conv(a):
    src, out, it = a
    m = np.asarray(Image.open(os.path.join(src, "mask", it["name"] + ".png"))) == 1
    lab, n = ndimage.label(m, structure=np.ones((3, 3)))
    keep = np.zeros_like(m)
    for k in range(1, n + 1):
        comp = lab == k
        sk = skeletonize(comp)
        L = int(sk.sum())
        if L < MIN_LEN:
            continue
        if comp.sum() / max(1, L) > MAX_W:   # 面積／長度 ≈ 平均寬度
            continue
        keep |= sk
    thick = ndimage.binary_dilation(keep, iterations=LINE_W // 2)
    Image.fromarray(thick.astype(np.uint8)).save(os.path.join(out, "mask", it["name"] + ".png"))
    os.link(os.path.join(src, "img", it["name"] + ".jpg"), os.path.join(out, "img", it["name"] + ".jpg"))
    return dict(it, line_px=int(thick.sum()))


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--src", required=True); ap.add_argument("--out", required=True)
    a = ap.parse_args()
    shutil.rmtree(a.out, ignore_errors=True)
    for d in ["img", "mask"]: os.makedirs(os.path.join(a.out, d))
    idx = json.load(open(os.path.join(a.src, "index.json")))
    with Pool(8) as p:
        res = p.map(conv, [(a.src, a.out, it) for it in idx], chunksize=32)
    json.dump(res, open(os.path.join(a.out, "index.json"), "w"))
    print(len(res), "張；細化後還有線的", sum(1 for r in res if r["line_px"] > 0), "張")


if __name__ == "__main__":
    main()
