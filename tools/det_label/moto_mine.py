#!/usr/bin/env python3
"""
找「有機車」但還沒標過的影格：用目前的魚眼物件模型（低門檻）掃還沒審過的影格裁切，數機車框，
機車多的影格優先，輸出成 picks（每行 session 路徑 影格），給 own_prelabel.py --picks 產生候選。

用法：python tools/det_label/moto_mine.py --train ~/avm-data/train --review ~/avm-data/det_review \
        --model ~/avm-data/det_runs/mix3_n320/weights/best.pt --out ~/avm-data/moto_picks.txt --n 600
"""
import argparse, glob, os, sys
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
from own_prelabel import crops_of   # noqa: E402

MOTO = 3


def main():
    ap = argparse.ArgumentParser()
    for k in ("train", "review", "model", "out"): ap.add_argument("--" + k, required=True)
    ap.add_argument("--n", type=int, default=600); ap.add_argument("--every", type=int, default=2)
    ap.add_argument("--conf", type=float, default=0.25)
    a = ap.parse_args()
    from ultralytics import YOLO
    import torch
    dev = "mps" if torch.backends.mps.is_available() else "cpu"
    m = YOLO(a.model)
    done = {os.path.basename(f)[:-10].rsplit("_", 2)[0] for f in glob.glob(os.path.join(a.review, "*.cand.json"))}
    scored = []; k = 0
    for sd in sorted(glob.glob(os.path.join(a.train, "train_*"))):
        sess = os.path.basename(sd)
        for jp in sorted(glob.glob(os.path.join(sd, "f_*.jpg")))[::a.every]:
            fr = int(os.path.basename(jp)[2:7])
            if f"{sess}_{fr:05d}" in done: continue
            try: frame = Image.open(jp).convert("RGB")
            except Exception: continue
            n = 0
            for tag, im in crops_of(frame):
                r = m.predict(np.asarray(im)[:, :, ::-1], imgsz=320, conf=a.conf, device=dev, verbose=False)[0]
                n += int((r.boxes.cls.cpu().numpy() == MOTO).sum())
            if n: scored.append((n, sd, fr))
            k += 1
            if k % 500 == 0: print(k, "張，有機車", len(scored), flush=True)
    scored.sort(key=lambda t: -t[0])
    # 同一段路相鄰影格很像：每 3 張內只取一張（機車多的優先）
    picked, used = [], set()
    for n, sd, fr in scored:
        if any((sd, fr + d) in used for d in range(-3, 4)): continue
        used.add((sd, fr)); picked.append((sd, fr, n))
        if len(picked) >= a.n: break
    with open(a.out, "w") as f:
        for sd, fr, n in picked: f.write(f"{sd} {fr} {n}\n")
    print("掃了", k, "張；有機車", len(scored), "；挑", len(picked), "；機車框合計", sum(p[2] for p in picked))


if __name__ == "__main__":
    main()
