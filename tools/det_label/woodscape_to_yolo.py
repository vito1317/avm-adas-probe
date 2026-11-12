#!/usr/bin/env python3
"""
WoodScape 2D 框 → YOLO 訓練資料（給 360 的物件偵測微調用）。

車機上偵測的輸入是魚眼畫面裁成正方形（左、中、右），所以這裡也把 1280x966 的 WoodScape 影像裁成左、右兩塊 966x966。

WoodScape 公開的框只有 5 類，而且：
  - 「vehicles」沒分汽車／卡車／公車；
  - 沒有機車（機車只在語意標註裡）；
  - 被遮住的物件被濾掉了（直接拿來訓練會教模型「被遮住的車不是車」）。
所以另外用一個大的 COCO 模型（老師）補：
  - GT 的 vehicles 框 → 跟老師的框重疊最大的類別（car/truck/bus/motorcycle），沒有重疊就當 car；
  - 老師抓到、GT 沒有的物件（信心 ≥ TEACH_CONF）→ 補上（大多是被遮住的車、機車）。
老師不是用 WoodScape 訓練的，補上的框只當訓練用，不輸出到任何地方。

注意：WoodScape 只能私人使用、不得散布（Valeo 條件），產生的資料與權重都放在 ~/avm-data，不進 repo。

用法：~/avm-data/.venv312/bin/python tools/det_label/woodscape_to_yolo.py --ws ~/avm-data/ext/woodscape --out ~/avm-data/det_ws
"""
import argparse, glob, os, random
import numpy as np
from PIL import Image

CLASSES = ["person", "bicycle", "car", "motorcycle", "bus", "truck", "traffic light"]
COCO2OUR = {0: 0, 1: 1, 2: 2, 3: 3, 5: 4, 7: 5, 9: 6}
TEACH_CONF = 0.45
MIN_SIDE = 8          # 裁切後太小的框不要（px，原圖尺度）


def iou(a, b):
    x1, y1 = max(a[0], b[0]), max(a[1], b[1]); x2, y2 = min(a[2], b[2]), min(a[3], b[3])
    i = max(0, x2 - x1) * max(0, y2 - y1)
    u = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - i
    return i / u if u > 0 else 0.0


def read_gt(path):
    out = []
    for line in open(path):
        p = line.strip().split(",")
        if len(p) < 6: continue
        out.append((p[0], [float(v) for v in p[2:6]]))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ws", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("--teacher", default="yolo11x.pt"); ap.add_argument("--limit", type=int, default=0)
    a = ap.parse_args()
    from ultralytics import YOLO
    import torch
    dev = "mps" if torch.backends.mps.is_available() else "cpu"
    teacher = YOLO(a.teacher)
    imgs = sorted(glob.glob(os.path.join(a.ws, "rgb_images", "*.png")))
    if a.limit: imgs = imgs[:a.limit]
    random.seed(0)
    stats = dict(gt=0, added=0, veh_cls={}, crops=0)
    for split in ("train", "val"):
        for sub in ("images", "labels"): os.makedirs(os.path.join(a.out, sub, split), exist_ok=True)
    for k, ip in enumerate(imgs):
        name = os.path.basename(ip)[:-4]
        gp = os.path.join(a.ws, "box_2d_annotations", name + ".txt")
        if not os.path.exists(gp): continue
        im = Image.open(ip).convert("RGB"); W, H = im.size
        gt = read_gt(gp)
        r = teacher.predict(np.asarray(im)[:, :, ::-1], imgsz=1280, conf=0.25, device=dev, verbose=False)[0]
        tb = [(COCO2OUR.get(int(c)), b.tolist(), float(s)) for b, c, s in zip(r.boxes.xyxy.cpu(), r.boxes.cls.cpu(), r.boxes.conf.cpu())]
        tb = [t for t in tb if t[0] is not None]
        boxes = []   # (cls, [x1,y1,x2,y2])
        for nm, b in gt:
            if nm == "traffic_sign": continue
            if nm == "vehicles":
                best = max(((iou(b, t[1]), t[0]) for t in tb if t[0] in (2, 3, 4, 5)), default=(0, 2))
                c = best[1] if best[0] >= 0.3 else 2
                stats["veh_cls"][CLASSES[c]] = stats["veh_cls"].get(CLASSES[c], 0) + 1
            else:
                c = {"person": 0, "bicycle": 1, "traffic_light": 6}[nm]
            boxes.append((c, b)); stats["gt"] += 1
        for c, b, s in tb:
            if s < TEACH_CONF: continue
            if any(iou(b, g[1]) > 0.3 for g in boxes): continue
            boxes.append((c, b)); stats["added"] += 1
        split = "val" if random.random() < 0.08 else "train"
        side = min(W, H)
        for tag, x0 in (("L", 0), ("R", W - side)):
            crop = im.crop((x0, 0, x0 + side, side))
            lines = []
            for c, (x1, y1, x2, y2) in boxes:
                cx1, cx2 = max(x1, x0), min(x2, x0 + side); cy1, cy2 = max(y1, 0), min(y2, side)
                if cx2 - cx1 < MIN_SIDE or cy2 - cy1 < MIN_SIDE: continue
                if (cx2 - cx1) < 0.4 * (x2 - x1): continue      # 只剩一小條的不要
                lines.append("%d %.6f %.6f %.6f %.6f" % (c, ((cx1 + cx2) / 2 - x0) / side, (cy1 + cy2) / 2 / side, (cx2 - cx1) / side, (cy2 - cy1) / side))
            crop.resize((640, 640), Image.BILINEAR).save(os.path.join(a.out, "images", split, f"ws_{name}_{tag}.jpg"), quality=92)
            open(os.path.join(a.out, "labels", split, f"ws_{name}_{tag}.txt"), "w").write("\n".join(lines))
            stats["crops"] += 1
        if k % 200 == 0: print(k, len(imgs), stats, flush=True)
    print("完成", stats)


if __name__ == "__main__":
    main()
