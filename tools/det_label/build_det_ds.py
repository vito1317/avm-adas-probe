#!/usr/bin/env python3
"""
審查結果＋WoodScape → 物件偵測訓練資料（YOLO 格式）。

審查輸出（每行一張審查圖）：
  {"grid": "g00012", "A": {"reject": [2], "relabel": {"3": "truck"}, "missing": false}, "B": {...}, ...}
  reject = 不對的框（不是物體、框歪太多、重複）；relabel = 類別改掉；missing = 有明顯的物體沒被框到。
missing 的裁切整張不用（框不齊會教模型「那不是車」）；沒有審查結果的裁切也不用。

自己的影格：train_20261111_073336（早上）整段當驗證，其餘當訓練 —— 驗證跟訓練不會是同一段路的相鄰影格。

用法：python tools/det_label/build_det_ds.py --review ~/avm-data/det_review --grids ~/avm-data/det_grids \
        --results ~/avm-data/det_grids/results --ws ~/avm-data/det_ws --out ~/avm-data/det_ds
"""
import argparse, glob, json, os, shutil

CLASSES = ["person", "bicycle", "car", "motorcycle", "bus", "truck", "traffic light", "barrier gate"]
VAL_SESS = "train_20261111_073336"


def main():
    ap = argparse.ArgumentParser()
    for k in ["review", "grids", "results", "ws", "out"]: ap.add_argument("--" + k, required=True)
    ap.add_argument("--ws_frac", type=float, default=1.0, help="WoodScape 用多少比例")
    a = ap.parse_args()
    # 可以給好幾輪（逗號分隔，--grids 與 --results 一一對應）；後面的輪次覆蓋前面（重審過的裁切以新的為準）
    rev = {}
    for gdir, rdir in zip(a.grids.split(","), a.results.split(",")):
        _load(gdir, rdir, rev)
    _build(a, rev)


def _load(gdir, rdir, rev):
    grids = {g["grid"]: g["cells"] for g in json.load(open(os.path.join(gdir, "grids.json")))}
    for f in sorted(glob.glob(os.path.join(rdir, "*.jsonl"))):
        for line in open(f):
            line = line.strip()
            if not line: continue
            try: r = json.loads(line)
            except Exception: continue
            cells = grids.get(r.get("grid"))
            if not cells: continue
            for k, n in enumerate(cells):
                if "ABCD"[k] in r: rev[n] = r["ABCD"[k]]


def _build(a, rev):
    for s in ("train", "val"):
        for sub in ("images", "labels"):
            d = os.path.join(a.out, sub, s); shutil.rmtree(d, ignore_errors=True); os.makedirs(d)
    st = dict(own_train=0, own_val=0, missing=0, rejected=0, relabeled=0, boxes={c: 0 for c in CLASSES})
    for n, r in rev.items():
        if r.get("missing"): st["missing"] += 1; continue
        cand = json.load(open(os.path.join(a.review, n + ".cand.json")))
        rej = {int(i) for i in r.get("reject", [])}; rel = {int(k): v for k, v in (r.get("relabel") or {}).items()}
        S = cand["size"]; lines = []
        # 老師的 NMS 分類別做：同一個物體可能有兩個幾乎一樣的框、類別不同 → 只留審查沒剔除、分數高的那個
        def _iou(p, q):
            ix = max(0, min(p[2], q[2]) - max(p[0], q[0])); iy = max(0, min(p[3], q[3]) - max(p[1], q[1])); i = ix * iy
            return i / ((p[2] - p[0]) * (p[3] - p[1]) + (q[2] - q[0]) * (q[3] - q[1]) - i + 1e-9)
        alive = sorted([b for b in cand["boxes"] if b["id"] not in rej], key=lambda b: -b["s"])
        dup = set()
        for i, p in enumerate(alive):
            if p["id"] in dup: continue
            for q in alive[i + 1:]:
                if q["id"] not in dup and _iou(p["b"], q["b"]) > 0.9: dup.add(q["id"])
        rej = rej | dup
        for b in cand["boxes"]:
            if b["id"] in rej: st["rejected"] += 1; continue
            c = b["c"]
            if b["id"] in rel:
                if rel[b["id"]] not in CLASSES: st["rejected"] += 1; continue
                c = CLASSES.index(rel[b["id"]]); st["relabeled"] += 1
            x1, y1, x2, y2 = b["b"]
            # 老師常把自己的車身框成一個大物體（審查會剔除，這裡再保險一次）：蓋住超過 40% 畫面的框不要
            if (x2 - x1) * (y2 - y1) > 0.4 * S * S: st["rejected"] += 1; continue
            lines.append("%d %.6f %.6f %.6f %.6f" % (c, (x1 + x2) / 2 / S, (y1 + y2) / 2 / S, (x2 - x1) / S, (y2 - y1) / S))
            st["boxes"][CLASSES[c]] += 1
        split = "val" if cand["session"] == VAL_SESS else "train"
        st["own_" + split] += 1
        shutil.copy(os.path.join(a.review, n + ".jpg"), os.path.join(a.out, "images", split, "own_" + n + ".jpg"))
        open(os.path.join(a.out, "labels", split, "own_" + n + ".txt"), "w").write("\n".join(lines))
    # WoodScape：只進訓練（驗證只看自己的車）
    ws = sorted(glob.glob(os.path.join(a.ws, "images", "*", "*.jpg")))
    ws = ws[:int(len(ws) * a.ws_frac)]
    for f in ws:
        b = os.path.basename(f)[:-4]; lab = f.replace("/images/", "/labels/")[:-4] + ".txt"
        os.symlink(f, os.path.join(a.out, "images", "train", b + ".jpg"))
        shutil.copy(lab, os.path.join(a.out, "labels", "train", b + ".txt"))
    st["ws"] = len(ws)
    yaml = "path: %s\ntrain: images/train\nval: images/val\nnames:\n" % a.out + "".join("  %d: %s\n" % (i, c) for i, c in enumerate(CLASSES))
    open(os.path.join(a.out, "data.yaml"), "w").write(yaml)
    print(st)


if __name__ == "__main__":
    main()
