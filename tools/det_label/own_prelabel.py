#!/usr/bin/env python3
"""
自己錄的影格 → 物件偵測的候選框（之後一定要逐張審查，審查過的才拿來訓練）。

跟車機一樣把每顆魚眼裁成正方形：左／右鏡頭各裁 a（左半）、b（右半），前／後鏡頭裁中間。
候選來源：
  - 大的 COCO 模型（yolo11x）：行人、腳踏車、汽車、機車、公車、卡車、紅綠燈；
  - YOLO-World（用文字找）：停車場柵欄桿（入口、出口長得一樣，同一類 barrier gate）。
每張裁切輸出：crop.jpg、cand.json（框＋編號）、review.png（畫上編號的框，給審查用）。

用法：~/avm-data/.venv312/bin/python tools/det_label/own_prelabel.py --train ~/avm-data/train --out ~/avm-data/det_review --every 8
"""
import argparse, glob, json, os
import numpy as np
from PIL import Image, ImageDraw, ImageFont

CLASSES = ["person", "bicycle", "car", "motorcycle", "bus", "truck", "traffic light", "barrier gate"]
COCO2OUR = {0: 0, 1: 1, 2: 2, 3: 3, 5: 4, 7: 5, 9: 6}
COLORS = ["#ff3030", "#30ff30", "#30d0ff", "#ffff30", "#ff30ff", "#ff9930", "#ffffff", "#ff80c0"]
GATE_PROMPTS = ["boom barrier", "parking gate arm", "barrier arm"]
QW, QH = 960, 540


def _iou(p, q):
    ix = max(0, min(p[2], q[2]) - max(p[0], q[0])); iy = max(0, min(p[3], q[3]) - max(p[1], q[1])); i = ix * iy
    return i / ((p[2] - p[0]) * (p[3] - p[1]) + (q[2] - q[0]) * (q[3] - q[1]) - i + 1e-9)


def crops_of(frame):
    """→ [(tag, PIL 正方形)]；象限順序 0 前、1 後、2 左、3 右（跟車機 quad 一樣）。"""
    out = []
    for q, name in ((0, "front"), (1, "rear"), (2, "left"), (3, "right")):
        ox, oy = (q % 2) * QW, (q // 2) * QH
        quad = frame.crop((ox, oy, ox + QW, oy + QH)); s = QH
        if name in ("left", "right"):
            out.append((name + "_a", quad.crop((0, 0, s, s)))); out.append((name + "_b", quad.crop((QW - s, 0, QW, s))))
        else:
            x0 = (QW - s) // 2; out.append((name + "_c", quad.crop((x0, 0, x0 + s, s))))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--train", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("--every", type=int, default=8)
    ap.add_argument("--extra_conf", type=float, default=0.35)
    ap.add_argument("--picks", default="", help="只處理這些影格（每行 session 路徑 影格），不用 --every 掃全部")
    ap.add_argument("--teach_conf", type=float, default=0.3, help="老師模型門檻（補漏框時調低，多框一些再由審查剔除）")
    ap.add_argument("--extra", default="", help="魚眼微調版（補老師漏掉的邊緣車輛；它的框一樣要審查）")
    a = ap.parse_args()
    from ultralytics import YOLO, YOLOWorld
    import torch
    dev = "mps" if torch.backends.mps.is_available() else "cpu"
    det = YOLO("yolo11x.pt")
    world = YOLOWorld("yolov8x-worldv2.pt"); world.set_classes(GATE_PROMPTS)
    extra = YOLO(a.extra) if a.extra else None
    try: font = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial Bold.ttf", 16)
    except Exception: font = ImageFont.load_default()
    os.makedirs(a.out, exist_ok=True)
    frames = []
    if a.picks:
        for line in open(a.picks):
            q = line.split()
            if len(q) >= 2: frames.append(os.path.join(q[0], "f_%05d.jpg" % int(q[1])))
    else:
        for sd in sorted(glob.glob(os.path.join(a.train, "train_*"))):
            fs = sorted(glob.glob(os.path.join(sd, "f_*.jpg")))
            frames += fs[::a.every]
    print("影格", len(frames), flush=True)
    n_box = n_gate = 0
    for k, fp in enumerate(frames):
        sess = os.path.basename(os.path.dirname(fp)); fr = os.path.basename(fp)[2:-4]
        try: frame = Image.open(fp).convert("RGB")
        except OSError: print("壞檔，跳過", fp, flush=True); continue
        for tag, im in crops_of(frame):
            base = os.path.join(a.out, f"{sess}_{fr}_{tag}")
            if os.path.exists(base + ".cand.json"): continue
            arr = np.asarray(im)[:, :, ::-1]
            boxes = []
            r = det.predict(arr, imgsz=640, conf=a.teach_conf, device=dev, verbose=False)[0]
            for b, c, s in zip(r.boxes.xyxy.cpu().tolist(), r.boxes.cls.cpu().tolist(), r.boxes.conf.cpu().tolist()):
                if int(c) in COCO2OUR: boxes.append(dict(c=COCO2OUR[int(c)], b=[round(v, 1) for v in b], s=round(s, 3)))
            if extra is not None:
                r = extra.predict(arr, imgsz=320, conf=a.extra_conf, device=dev, verbose=False)[0]
                for b, c, s in zip(r.boxes.xyxy.cpu().tolist(), r.boxes.cls.cpu().tolist(), r.boxes.conf.cpu().tolist()):
                    if int(c) == 7: continue
                    if (b[2] - b[0]) * (b[3] - b[1]) > 0.4 * im.size[0] ** 2: continue
                    if any(_iou(b, o["b"]) > 0.4 for o in boxes): continue
                    boxes.append(dict(c=int(c), b=[round(v, 1) for v in b], s=round(s, 3), src="fish"))
            r = world.predict(arr, imgsz=640, conf=0.12, device=dev, verbose=False)[0]
            for b, s in zip(r.boxes.xyxy.cpu().tolist(), r.boxes.conf.cpu().tolist()):
                w, h = b[2] - b[0], b[3] - b[1]
                if w < 1.5 * h: continue          # 柵欄桿是橫的細長條
                boxes.append(dict(c=7, b=[round(v, 1) for v in b], s=round(s, 3))); n_gate += 1
            for i, bx in enumerate(boxes): bx["id"] = i + 1
            n_box += len(boxes)
            im.save(base + ".jpg", quality=92)
            json.dump(dict(session=sess, frame=int(fr), crop=tag, size=im.size[0], boxes=boxes), open(base + ".cand.json", "w"))
            rv = im.copy().resize((im.size[0] * 2 // 1, im.size[1] * 2 // 1)) if im.size[0] < 600 else im.copy()
            sc = rv.size[0] / im.size[0]; d = ImageDraw.Draw(rv)
            for bx in boxes:
                x1, y1, x2, y2 = [v * sc for v in bx["b"]]; col = COLORS[bx["c"]]
                d.rectangle([x1, y1, x2, y2], outline=col, width=3)
                t = f'{bx["id"]} {CLASSES[bx["c"]]}'
                d.rectangle([x1, max(0, y1 - 20), x1 + 9 * len(t) + 6, max(20, y1)], fill="black"); d.text((x1 + 3, max(0, y1 - 19)), t, fill=col, font=font)
            rv.save(base + ".review.jpg", quality=88)
        if k % 50 == 0: print(k, len(frames), "框", n_box, "柵欄候選", n_gate, flush=True)
    print("完成：框", n_box, "柵欄候選", n_gate)


if __name__ == "__main__":
    main()
