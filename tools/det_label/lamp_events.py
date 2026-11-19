#!/usr/bin/env python3
"""
車燈標註第二批：從車機「車燈事件」時多存的影格（evt = lead_brake / lead_turn / lead_hazard / side_blink）挑車。
第一批（lamp_sheets.py，隨機挑車）裡亮燈的車太少（煞車 23 台、方向燈 0 台），模型學不到魚眼裡的車燈長什麼樣。
事件影格是車機判斷「前車煞車」「旁邊車打方向燈」時存的，裡面亮燈的車多很多。

流程：事件影格 → 切成正方形（own_prelabel.crops_of）→ 魚眼物件模型找車（汽車、公車、卡車、機車）→
前車事件只看前鏡頭、旁車打燈看四顆鏡頭 → 依「很紅／很琥珀色」像素比例，一半挑最可能亮燈的、一半隨機 →
排成跟 lamp_sheets.py 一樣的 4x4 標註圖（同一份 LAMP_GUIDE.md，lamp_train.py 直接讀）。
同一段路相鄰的事件影格常常是同一台車：每 (session, 影格//6) 最多取 4 台。

用法：python tools/det_label/lamp_events.py --train ~/avm-data/train --model ~/avm-data/det_runs/mix6_n320/weights/best.pt \
        --out ~/avm-data/lamp2 --n 2400
實車影像只放在本機，不要 commit。
"""
import argparse, glob, json, os, random, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
from own_prelabel import crops_of          # noqa: E402
from lamp_sheets import lamp_score, CELL   # noqa: E402

VEH = {2, 3, 4, 5}       # 汽車、機車、公車、卡車（機車的方向燈也要學）
EVTS = {"lead_brake", "lead_turn", "lead_hazard", "side_blink"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--train", required=True); ap.add_argument("--model", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("--n", type=int, default=2400); ap.add_argument("--min_px", type=int, default=36); ap.add_argument("--batches", type=int, default=8)
    ap.add_argument("--conf", type=float, default=0.35)
    a = ap.parse_args()
    from ultralytics import YOLO
    import torch
    dev = "mps" if torch.backends.mps.is_available() else "cpu"
    det = YOLO(a.model)
    random.seed(9)
    frames = []
    for jf in sorted(glob.glob(os.path.join(a.train, "train_*", "f_*.json"))):
        try: m = json.load(open(jf))
        except Exception: continue
        if m.get("evt") in EVTS and os.path.exists(jf[:-5] + ".jpg"): frames.append((jf[:-5] + ".jpg", m["evt"]))
    print("事件影格", len(frames), flush=True)
    os.makedirs(os.path.join(a.out, "crops"), exist_ok=True)
    cand, per_group = [], {}
    for k, (jp, evt) in enumerate(frames):
        sd = os.path.basename(os.path.dirname(jp)); fr = int(os.path.basename(jp)[2:7])
        g = (sd, fr // 6)
        if per_group.get(g, 0) >= 4: continue
        try: frame = Image.open(jp).convert("RGB")
        except Exception: continue
        for tag, im in crops_of(frame):
            if evt.startswith("lead") and not tag.startswith("front"): continue
            r = det.predict(np.asarray(im)[:, :, ::-1], imgsz=320, conf=a.conf, device=dev, verbose=False)[0]
            S = im.size[0]; saved = None
            for b, c in zip(r.boxes.xywhn.cpu().numpy(), r.boxes.cls.cpu().numpy()):
                if int(c) not in VEH or b[2] * S < a.min_px or b[3] * S < a.min_px * 0.6: continue
                if saved is None:
                    saved = os.path.join(a.out, "crops", f"{sd}_{fr:05d}_{tag}.jpg"); im.save(saved, quality=92)
                cand.append(dict(img=saved, box=[float(v) for v in b], evt=evt, cls=int(c), score=lamp_score(im, b), group=g))
        per_group[g] = per_group.get(g, 0) + 1
        if k % 200 == 0: print(k, "/", len(frames), "車", len(cand), flush=True)
    # 每組最多 4 台；一半挑最紅／最琥珀色的，一半隨機
    random.shuffle(cand); cnt, pool = {}, []
    for c in cand:
        if cnt.get(c["group"], 0) >= 4: continue
        cnt[c["group"]] = cnt.get(c["group"], 0) + 1; pool.append(c)
    pool.sort(key=lambda c: -c["score"])
    hot = pool[:a.n // 2]; rest = pool[a.n // 2:]
    pick = hot + random.sample(rest, min(len(rest), a.n - len(hot))); random.shuffle(pick)
    os.makedirs(os.path.join(a.out, "sheets"), exist_ok=True)
    try: font = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial Bold.ttf", 34)
    except Exception: font = ImageFont.load_default()
    sheets = []
    for si in range(0, len(pick), 16):
        part = pick[si:si + 16]; name = "m%05d" % (si // 16)
        sh = Image.new("RGB", (CELL * 4, CELL * 4), (30, 30, 30)); d = ImageDraw.Draw(sh); cells = []
        for k, c in enumerate(part):
            im = Image.open(c["img"]).convert("RGB"); W = im.size[0]; cx, cy, w, h = c["box"]
            x1, y1, x2, y2 = (cx - w / 2) * W, (cy - h / 2) * W, (cx + w / 2) * W, (cy + h / 2) * W
            pw, ph = (x2 - x1) * 0.15, (y2 - y1) * 0.15
            crop = im.crop((max(0, x1 - pw), max(0, y1 - ph), min(W, x2 + pw), min(W, y2 + ph)))
            sc = min((CELL - 8) / crop.size[0], (CELL - 40) / crop.size[1])
            crop = crop.resize((max(1, int(crop.size[0] * sc)), max(1, int(crop.size[1] * sc))), Image.BICUBIC)
            ox, oy = (k % 4) * CELL, (k // 4) * CELL
            sh.paste(crop, (ox + (CELL - crop.size[0]) // 2, oy + 36 + (CELL - 40 - crop.size[1]) // 2))
            d.rectangle([ox, oy, ox + CELL - 1, oy + CELL - 1], outline=(200, 200, 0), width=2)
            d.text((ox + 6, oy + 2), str(k + 1), fill="yellow", font=font)
            cells.append(dict(split="train", img=c["img"], box=c["box"], evt=c["evt"], cls=c["cls"], session=c["group"][0]))
        sh.save(os.path.join(a.out, "sheets", name + ".jpg"), quality=90)
        sheets.append(dict(sheet=name, cells=cells))
    json.dump(sheets, open(os.path.join(a.out, "sheets.json"), "w"))
    per = (len(sheets) + a.batches - 1) // a.batches
    for b in range(a.batches):
        open(os.path.join(a.out, "batch_%d.txt" % b), "w").write("\n".join(s["sheet"] for s in sheets[b * per:(b + 1) * per]) + "\n")
    print("候選車", len(cand), "→ 挑", len(pick), "→", len(sheets), "張標註圖，每批", per)


if __name__ == "__main__":
    main()
