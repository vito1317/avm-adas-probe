#!/usr/bin/env python3
"""挑標註用的影格：依批次、車速、明暗、轉向、停車場分層，盡量多樣；相鄰影格（同一段路）不要挑太多。
用法：python tools/lane_label/pick_frames.py ~/avm-data/train --n 250 > picks.txt（每行 session frame）"""
import argparse, glob, json, os, random
from PIL import Image

ap = argparse.ArgumentParser(); ap.add_argument("root"); ap.add_argument("--n", type=int, default=250); ap.add_argument("--seed", type=int, default=7)
ap.add_argument("--exclude", default=None, help="已經標過的清單（同格式：session 路徑 影格），不要再挑")
a = ap.parse_args()
excl = set()
if a.exclude:
    for line in open(a.exclude):
        q = line.split()
        if len(q) >= 2: excl.add((os.path.basename(q[0].rstrip("/")), int(q[1])))
random.seed(a.seed)
items = []
for sd in sorted(glob.glob(os.path.join(a.root, "train_*"))):
    for jf in sorted(glob.glob(os.path.join(sd, "f_*.json"))):
        jp = jf[:-5] + ".jpg"
        if not os.path.exists(jp): continue
        try: m = json.load(open(jf))
        except Exception: continue
        fr = int(os.path.basename(jf)[2:7])
        if (os.path.basename(sd), fr) in excl: continue
        items.append((sd, fr, m))
def bright(sd, fr):
    try:
        im = Image.open(os.path.join(sd, "f_%05d.jpg" % fr)).convert("L").resize((96, 54))
    except Exception:
        return None                      # 還在下載、檔案不完整
    return sum(im.tobytes()) / (96 * 54)
buckets = {}
for sd, fr, m in items:
    s = m.get("speed") or 0
    sb = 0 if s < 5 else 1 if s < 30 else 2 if s < 70 else 3
    key = (os.path.basename(sd), sb, bool(m.get("turn")), bool(m.get("lot")), (m.get("t", 0) // 1000 // 3600 + 8) % 24 >= 18 or (m.get("t", 0) // 1000 // 3600 + 8) % 24 < 6)
    buckets.setdefault(key, []).append((sd, fr))
picks = []
keys = list(buckets)
while len(picks) < a.n and any(buckets[k] for k in keys):
    for k in keys:
        if not buckets[k] or len(picks) >= a.n: continue
        sd, fr = buckets[k].pop(random.randrange(len(buckets[k])))
        if any(p[0] == sd and abs(p[1] - fr) < 8 for p in picks): continue   # 同一段路不重複
        picks.append((sd, fr))
# 補明暗資訊（夜間比例太低就多抽）
for sd, fr in sorted(picks):
    b = bright(sd, fr)
    if b is not None:
        print(sd, fr, round(b))
