#!/usr/bin/env python3
"""
車道線顏色（白／黃）分類的資料：審查過、標了種類（w-solid / y-solid …）的線 → 在魚眼原圖上取「線上」與「旁邊路面」的顏色。

為什麼：車機用固定 RGB 門檻判黃線（r≥90、g≥70、r−b≥45…），實測黃實線只認對 9%（701 條有 606 條被當白線）。
晚上車燈／路燈把漆和柏油一起染色，黃漆看起來偏白、白漆被鈉燈照成黃；但「漆比旁邊的柏油黃多少」比較穩。

每條線：沿線每 0.1 m 取一點（離鏡頭 ≤ 3.5 m、在那顆鏡頭看得到的），線上取 3x3 平均，
旁邊路面取線兩側垂直 0.30 m、0.45 m 處；彙總成特徵（中位數）。
輸出 CSV：一行一條線（特徵 + 標籤 y/w + 分組）。
用法：python tools/lane_label/line_color_ds.py --avm ~/avm-data --out ~/avm-data/line_color.csv
實車影像只放在本機，不要 commit。
"""
import argparse, glob, json, math, os, sys
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE); sys.path.insert(0, os.path.dirname(HERE))
import avm_geometry as ag            # noqa: E402
from ufld_teacher import cam_to_fisheye   # noqa: E402
from candidates import load_calib    # noqa: E402

QW, QH = 960, 540
FEATS = ["lr", "lg", "lb", "br", "bg", "bb", "ly", "by", "dy", "dyn", "dl", "dch", "lum", "x0", "left", "leftmost", "rightmost", "night", "n"]


def sample(img, cam, X, Z):
    u, v, ok = cam_to_fisheye(cam, np.array([X]), np.array([0.0]), np.array([Z]), QW, QH)
    if not ok[0] or not np.isfinite(u[0]): return None
    ox, oy = (cam.quad % 2) * QW, (cam.quad // 2) * QH
    x, y = int(u[0]) + ox, int(v[0]) + oy
    if x < ox + 1 or x > ox + QW - 2 or y < oy + 1 or y > oy + QH - 2: return None
    return img[y - 1:y + 2, x - 1:x + 2].reshape(-1, 3).mean(0)


def line_feats(img, geo, x1, z1, x2, z2):
    L = math.hypot(x2 - x1, z2 - z1)
    if L < 0.5: return None
    ux, uz = (x2 - x1) / L, (z2 - z1) / L; nx, nz = uz, -ux     # 垂直方向
    on, bgs = [], []
    for k in range(int(L / 0.1) + 1):
        X, Z = x1 + ux * k * 0.1, z1 + uz * k * 0.1
        if abs(Z) > 6.5: continue
        best = None
        for c in geo.cams:
            d = math.hypot(X - c.px, Z - c.pz)
            if d <= 3.5 and (best is None or d < best[0]): best = (d, c)
        if best is None: continue
        c = best[1]
        p = sample(img, c, X, Z)
        if p is None: continue
        bb = [sample(img, c, X + nx * o, Z + nz * o) for o in (0.30, 0.45, -0.30, -0.45)]
        bb = [b for b in bb if b is not None]
        if not bb: continue
        on.append(p); bgs.append(np.median(np.array(bb), 0))
    if len(on) < 5: return None
    on = np.array(on); bgs = np.array(bgs)
    l = np.median(on, 0); b = np.median(bgs, 0)
    ls, bs = l.sum() + 1e-6, b.sum() + 1e-6
    ly = (l[0] + l[1]) / 2 - l[2]; by = (b[0] + b[1]) / 2 - b[2]
    # 每點的「比旁邊黃多少」（除以旁邊亮度，抵銷整體照明）再取中位數
    dyn = np.median((((on[:, 0] + on[:, 1]) / 2 - on[:, 2]) - ((bgs[:, 0] + bgs[:, 1]) / 2 - bgs[:, 2])) / (bgs.sum(1) / 3 + 10))
    return dict(lr=l[0] / ls, lg=l[1] / ls, lb=l[2] / ls, br=b[0] / bs, bg=b[1] / bs, bb=b[2] / bs,
                ly=ly, by=by, dy=ly - by, dyn=float(dyn), dl=(ls - bs) / 3, dch=(l[0] / ls - l[2] / ls) - (b[0] / bs - b[2] / bs),
                lum=ls / 3, n=len(on))


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--avm", required=True); ap.add_argument("--out", required=True)
    a = ap.parse_args()
    rows = []; geos = {}
    for r in ("", "2", "3", "4"):
        for f in sorted(glob.glob(os.path.join(a.avm, f"labels{r}", "out_*.jsonl"))):
            for line in open(f):
                try: L = json.loads(line)
                except Exception: continue
                base = L["img"][:-4]; cj = os.path.join(a.avm, f"label_review{r}", base + ".cand.json")
                if not os.path.exists(cj) or not L.get("keep"): continue
                sess, fr = base.rsplit("_", 1); sd = os.path.join(a.avm, "train", sess)
                jp = os.path.join(sd, "f_%05d.jpg" % int(fr))
                if not os.path.exists(jp): continue
                if sess not in geos:
                    try: geos[sess] = ag.Geometry(load_calib(sd))
                    except SystemExit: geos[sess] = None
                geo = geos[sess]
                if geo is None: continue
                c = json.load(open(cj)); byid = {str(x["id"]): x for x in c["cands"]}
                kept = []
                for i, t in L["keep"].items():
                    x = byid.get(str(i))
                    if not x or t not in ("w-solid", "w-dashed", "y-solid", "y-dashed"): continue
                    g = (L.get("geom") or {}).get(str(i)) or [x["x1"], x["z1"], x["x2"], x["z2"]]
                    kept.append((i, t, g, x))
                if not kept: continue
                img = np.asarray(Image.open(jp).convert("RGB"), np.float32)
                xs0 = []
                for i, t, g, x in kept:
                    x1, z1, x2, z2 = g; xs0.append(x1 + (x2 - x1) * (0 - z1) / (z2 - z1) if abs(z2 - z1) > 0.1 else (x1 + x2) / 2)
                hh = int(sess.split("_")[2][:2])
                for (i, t, g, x), x0 in zip(kept, xs0):
                    ft = line_feats(img, geo, *g)
                    if ft is None: continue
                    ft.update(x0=x0, left=1 if x0 < 0 else 0, leftmost=1 if x0 == min(xs0) else 0, rightmost=1 if x0 == max(xs0) else 0,
                              night=1 if hh >= 18 or hh < 6 else 0)
                    rows.append(dict(ft, label=t[0], dashed=1 if "dashed" in t else 0, group=f"{sess}/{int(fr) // 150}",
                                     src=x.get("src", ""), dev_k=x.get("k", "") if x.get("src") == "dev" else "", dev_d=int(bool(x.get("d"))) if x.get("src") == "dev" else -1))
    import csv
    keys = FEATS + ["label", "dashed", "group", "src", "dev_k", "dev_d"]
    with open(a.out, "w", newline="") as fo:
        w = csv.DictWriter(fo, fieldnames=keys); w.writeheader()
        for r in rows: w.writerow({k: r.get(k) for k in keys})
    print("線", len(rows), "黃", sum(r["label"] == "y" for r in rows), "白", sum(r["label"] == "w" for r in rows))


if __name__ == "__main__":
    main()
