#!/usr/bin/env python3
"""
在自己的驗證裁切上比較偵測模型（現在車上的 COCO YOLO11n vs 微調版）。

分組比（跟車機 NMS 分群一樣）：veh = car/bus/truck、two = motorcycle/bicycle、ped = person、light = traffic light。
IoU ≥ 0.5 算對；每組算準確率（偵測到的有幾成是真的）、抓到率（真的有幾成被抓到）。
用 320 輸入、信心 0.35（跟車上的設定一樣）。

用法：python tools/det_label/eval_det.py --ds ~/avm-data/det_ds --models yolo11n.pt:coco,~/avm-data/det_runs/own_n320/weights/best.pt:ours
"""
import argparse, glob, os
import numpy as np

OURS = ["person", "bicycle", "car", "motorcycle", "bus", "truck", "traffic light", "barrier gate"]
GROUP_OURS = {0: "ped", 1: "two", 2: "veh", 3: "two", 4: "veh", 5: "veh", 6: "light", 7: "gate"}
GROUP_COCO = {0: "ped", 1: "two", 2: "veh", 3: "two", 5: "veh", 7: "veh", 9: "light"}
GROUPS = ["veh", "two", "ped", "light"]


def iou(a, b):
    x1, y1 = np.maximum(a[0], b[:, 0]), np.maximum(a[1], b[:, 1]); x2, y2 = np.minimum(a[2], b[:, 2]), np.minimum(a[3], b[:, 3])
    i = np.clip(x2 - x1, 0, None) * np.clip(y2 - y1, 0, None)
    return i / ((a[2] - a[0]) * (a[3] - a[1]) + (b[:, 2] - b[:, 0]) * (b[:, 3] - b[:, 1]) - i + 1e-9)


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--ds", required=True); ap.add_argument("--models", required=True)
    ap.add_argument("--conf", type=float, default=0.35); ap.add_argument("--imgsz", type=int, default=320)
    a = ap.parse_args()
    from ultralytics import YOLO
    import torch
    dev = "mps" if torch.backends.mps.is_available() else "cpu"
    imgs = sorted(glob.glob(os.path.join(a.ds, "images", "val", "*.jpg")))
    gts = {}
    for f in imgs:
        from PIL import Image
        S = Image.open(f).size[0]; g = []
        lf = f.replace("/images/", "/labels/")[:-4] + ".txt"
        for line in open(lf):
            p = line.split()
            if len(p) != 5: continue
            c = int(p[0]); x, y, w, h = [float(v) * S for v in p[1:]]
            g.append((GROUP_OURS[c], [x - w / 2, y - h / 2, x + w / 2, y + h / 2]))
        gts[f] = g
    print("驗證裁切", len(imgs), "框", sum(len(v) for v in gts.values()))
    for spec in a.models.split(","):
        path, kind = spec.rsplit(":", 1); path = os.path.expanduser(path)
        gm = GROUP_COCO if kind == "coco" else GROUP_OURS
        m = YOLO(path); tp = {g: 0 for g in GROUPS}; fp = dict(tp); fn = dict(tp)
        for f in imgs:
            r = m.predict(f, imgsz=a.imgsz, conf=a.conf, device=dev, verbose=False)[0]
            pred = [(gm.get(int(c)), b) for b, c in zip(r.boxes.xyxy.cpu().numpy(), r.boxes.cls.cpu().numpy())]
            for g in GROUPS:
                P = np.array([b for k, b in pred if k == g]).reshape(-1, 4); G = np.array([b for k, b in gts[f] if k == g]).reshape(-1, 4)
                used = np.zeros(len(G), bool)
                for p in P:
                    if len(G):
                        o = iou(p, G); o[used] = 0; j = int(o.argmax())
                        if o[j] >= 0.5: used[j] = True; tp[g] += 1; continue
                    fp[g] += 1
                fn[g] += int((~used).sum())
        print(f"== {os.path.basename(os.path.dirname(os.path.dirname(path))) or path} ({kind})")
        for g in GROUPS:
            P = tp[g] / max(1, tp[g] + fp[g]); R = tp[g] / max(1, tp[g] + fn[g]); F = 2 * P * R / max(1e-9, P + R)
            print(f"  {g:5s} 準確率 {P:.3f}  抓到率 {R:.3f}  F1 {F:.3f}  (真 {tp[g] + fn[g]}，誤判 {fp[g]}，漏 {fn[g]})")


if __name__ == "__main__":
    main()
