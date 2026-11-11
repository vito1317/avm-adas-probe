#!/usr/bin/env python3
"""
把 WoodScape（Valeo）語意分割轉成跟 build_masks.py 一樣的格式，給車道線模型預訓練用。

**授權**：WoodScape 資料只能依 Valeo 書面同意的範圍使用（Vito 個人、非商業、只顯示提示，不得散布）。
轉出來的資料與用它訓練的權重只放本機，不要 commit、不要放公開 repo。

類別：lanemarks（2）→ 1，其他都是 0（void 也當 0：魚眼外圈黑邊、天空都確定不是線）。
影像 1280x966 → 等比縮成 360x272，左右補黑到 480x272（跟我們的魚眼象限一樣是黑邊夾著圓）。
用法：python tools/lane_label/woodscape_ds.py --zips ~/avm-data/ext/woodscape_dl --out ~/avm-data/ws_ds
"""
import argparse, io, json, os, zipfile
from multiprocessing import Pool
import numpy as np
from PIL import Image

OW, OH, IW = 480, 272, 360
A = None


def init(zdir):
    global A
    A = (zipfile.ZipFile(os.path.join(zdir, "rgb_images.zip")), zipfile.ZipFile(os.path.join(zdir, "semantic_annotations.zip")))


def conv(args):
    i, name, out = args
    rz, sz = A
    try:
        img = Image.open(io.BytesIO(rz.read("rgb_images/" + name))).convert("RGB").resize((IW, OH), Image.BILINEAR)
        lab = np.asarray(Image.open(io.BytesIO(sz.read("semantic_annotations/gtLabels/" + name))))
    except KeyError:
        return None
    m = (lab == 2).astype(np.uint8)
    m = np.asarray(Image.fromarray(m * 255).resize((IW, OH), Image.BILINEAR)) >= 96
    ci = Image.new("RGB", (OW, OH)); ci.paste(img, ((OW - IW) // 2, 0))
    cm = np.zeros((OH, OW), np.uint8); cm[:, (OW - IW) // 2:(OW - IW) // 2 + IW] = m
    base = "ws_" + name[:-4]
    ci.save(os.path.join(out, "img", base + ".jpg"), quality=90)
    Image.fromarray(cm).save(os.path.join(out, "mask", base + ".png"))
    return dict(name=base, session="ws_" + name[-6:-4], frame=i, quad=-1, partial=False, line_px=int(cm.sum()))


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--zips", required=True); ap.add_argument("--out", required=True)
    a = ap.parse_args()
    for d in ["img", "mask"]:
        os.makedirs(os.path.join(a.out, d), exist_ok=True)
    names = sorted(n.split("/")[-1] for n in zipfile.ZipFile(os.path.join(a.zips, "rgb_images.zip")).namelist() if n.endswith(".png"))
    with Pool(8, initializer=init, initargs=(a.zips,)) as p:
        idx = [r for r in p.imap(conv, [(i, n, a.out) for i, n in enumerate(names)], chunksize=16) if r]
    json.dump(idx, open(os.path.join(a.out, "index.json"), "w"))
    print(len(idx), "張；有車道標線的", sum(1 for r in idx if r["line_px"] > 0), "張")


if __name__ == "__main__":
    main()
