#!/usr/bin/env python3
"""
車道線虛／實判斷的資料：審過、標了種類（w-solid / w-dashed / y-solid …）的線 → 沿線「有漆／沒漆／看不到」的剖面 → 特徵。

為什麼：車機判虛實的規則（最長一段 ≥ 5 m 才算實線、看到 ≥ 2 段就算虛線）實測白實線有 30% 被判成虛線
（模型的線被車子擋住、陰影、磨損、鏡頭接縫切斷，就變成好幾段）。

剖面跟車機一樣：v12 車道線模型跑四顆鏡頭 → 拼成俯視圖機率（300x350、每像素 0.04 m）→ 沿線每 0.04 m：
  有漆 = 線左右 ±0.12 m 內機率 ≥ 0.5；看得到 = 俯視圖有畫到、離最近鏡頭 ≤ [OBS_M] m。
輸出 CSV：特徵 + 標籤 dashed + 分組 + 車機當時的判斷（dev_d）。
用法：~/avm-data/.venv/bin/python tools/lane_label/line_dash_ds.py --avm ~/avm-data --ckpt ~/avm-data/lane_runs/v12/best.pt --out ~/avm-data/line_dash.csv
實車影像只放在本機，不要 commit。
"""
import argparse, csv, glob, json, math, os, sys
import numpy as np, torch
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE); sys.path.insert(0, os.path.dirname(HERE))
import avm_geometry as ag          # noqa: E402
import train as T                   # noqa: E402
from candidates import load_calib   # noqa: E402

BW, BH, MPP, OX, OY = 300, 350, 0.04, 150.0, 175.0
OBS_M = 5.0
STEP = 0.04
CAMS = [(0, 2.42), (0, -2.42), (-0.93, 0.9), (0.93, 0.9)]
FEATS = ["obs", "paint", "frac", "longest", "nruns", "ngaps", "longest_gap", "mean_run", "first_gap", "gap_ratio", "obs_span", "longest_frac"]


def bev_prob(net, dev, frame, lut):
    """四顆鏡頭跑模型 → 1920x1080 機率 → 俯視圖。"""
    full = np.zeros((1080, 1920), np.float32)
    for q in range(4):
        ox, oy = (q % 2) * 960, (q // 2) * 540
        cv = Image.new("RGB", (480, 272)); cv.paste(Image.fromarray(frame[oy:oy + 540, ox:ox + 960]).resize((480, 270), Image.BILINEAR), (0, 0))
        x = (torch.from_numpy(np.asarray(cv.resize((224, 128), Image.BILINEAR), np.float32) / 255).permute(2, 0, 1) - T.MEAN) / T.STD
        with torch.no_grad():
            p = torch.softmax(net(x.unsqueeze(0).to(dev)), 1)[:, 1:2]
            p = torch.nn.functional.interpolate(p, size=(544, 960), mode="bilinear", align_corners=False)[0, 0, :540].cpu().numpy()
        full[oy:oy + 540, ox:ox + 960] = p
    out = np.full(lut.shape, -1.0, np.float32); m = lut >= 0
    out[m] = full.reshape(-1)[lut[m]]
    return out                     # -1 = 俯視圖沒畫到


def profile(bp, x1, z1, x2, z2):
    L = math.hypot(x2 - x1, z2 - z1)
    if L < 0.5: return None
    s, c = (x2 - x1) / L, (z2 - z1) / L; off = x1 * c - z1 * s
    u0, u1 = -7.5, 7.5
    st = []                        # 每步：1 有漆、0 沒漆、-1 看不到
    u = u0
    while u <= u1:
        x, z = off * c + u * s, -off * s + u * c; u += STEP
        if not any(math.hypot(x - a, z - b) <= OBS_M for a, b in CAMS): st.append(-1); continue
        best = -1.0; seen = False
        for o in np.arange(-0.12, 0.121, 0.04):
            xx, zz = x + o * c, z - o * s
            i = math.floor(xx / MPP + OX); j = math.floor(OY - zz / MPP)
            if 0 <= i < BW and 0 <= j < BH and bp[j, i] >= 0: seen = True; best = max(best, bp[j, i])
        st.append(-1 if not seen else (1 if best >= 0.5 else 0))
    return np.array(st)


def profile_rgb(lum, bp, x1, z1, x2, z2):
    """沿線每步：1 = 線比旁邊路面明顯亮（漆）、0 = 不亮、-1 = 看不到。門檻：亮 max(14, 0.18×路面亮度)。"""
    L = math.hypot(x2 - x1, z2 - z1)
    if L < 0.5: return None
    s, c = (x2 - x1) / L, (z2 - z1) / L; off = x1 * c - z1 * s
    st = []; u = -7.5
    def at(xx, zz):
        i = math.floor(xx / MPP + OX); j = math.floor(OY - zz / MPP)
        return lum[j, i] if (0 <= i < BW and 0 <= j < BH and bp[j, i] >= 0) else None
    while u <= 7.5:
        x, z = off * c + u * s, -off * s + u * c; u += STEP
        if not any(math.hypot(x - a, z - b) <= OBS_M for a, b in CAMS): st.append(-1); continue
        on = [v for v in (at(x + o * c, z - o * s) for o in (-0.08, -0.04, 0.0, 0.04, 0.08)) if v is not None]
        bg = [v for v in (at(x + o * c, z - o * s) for o in (0.30, 0.45, -0.30, -0.45)) if v is not None]
        if not on or not bg: st.append(-1); continue
        b = float(np.median(bg)); st.append(1 if max(on) - b > max(14.0, 0.18 * b) else 0)
    return np.array(st)


def feats(st):
    obs = (st >= 0); n_obs = obs.sum()
    if n_obs * STEP < 1.0 or (st == 1).sum() * STEP < 0.3: return None
    idx = np.nonzero(obs)[0]; span = (idx[-1] - idx[0] + 1) * STEP
    # 看得到的範圍內，連續有漆的段（看不到的地方不切斷段，也不算空隙）
    runs, gaps = [], []; cur = 0.0; gap = 0.0; started = False
    for v in st[idx[0]:idx[-1] + 1]:
        if v == 1:
            if gap > 0 and started: gaps.append(gap)
            gap = 0.0; cur += STEP; started = True
        elif v == 0:
            if cur > 0: runs.append(cur); cur = 0.0
            if started: gap += STEP
    if cur > 0: runs.append(cur)
    runs = [r for r in runs if r >= 0.2]; gaps = [g for g in gaps if g >= 0.2]
    paint = (st == 1).sum() * STEP
    longest = max(runs) if runs else 0.0
    return dict(obs=n_obs * STEP, paint=paint, frac=paint / (n_obs * STEP), longest=longest, nruns=len(runs),
                ngaps=sum(1 for g in gaps if g >= 0.8), longest_gap=max(gaps) if gaps else 0.0,
                mean_run=float(np.mean(runs)) if runs else 0.0, first_gap=gaps[0] if gaps else 0.0,
                gap_ratio=(sum(gaps) / max(1e-6, sum(gaps) + paint)), obs_span=span, longest_frac=longest / max(0.2, span))


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--avm", required=True); ap.add_argument("--ckpt", required=True); ap.add_argument("--out", required=True)
    a = ap.parse_args()
    dev = torch.device("mps" if torch.backends.mps.is_available() else "cpu")
    net = T.LaneNet(pretrained=False); net.load_state_dict(torch.load(a.ckpt, map_location="cpu")); net.to(dev).eval()
    rows = []; luts = {}
    for r in ("", "2", "3", "4"):
        for f in sorted(glob.glob(os.path.join(a.avm, f"labels{r}", "out_*.jsonl"))):
            for line in open(f):
                try: L = json.loads(line)
                except Exception: continue
                base = L["img"][:-4]; cj = os.path.join(a.avm, f"label_review{r}", base + ".cand.json")
                if not os.path.exists(cj) or not L.get("keep"): continue
                sess, fr = base.rsplit("_", 1); sd = os.path.join(a.avm, "train", sess); jp = os.path.join(sd, "f_%05d.jpg" % int(fr))
                if not os.path.exists(jp): continue
                if sess not in luts:
                    try: luts[sess] = ag.build_bev_lut(ag.Geometry(load_calib(sd)), 1920, 1080)
                    except SystemExit: luts[sess] = None
                if luts[sess] is None: continue
                c = json.load(open(cj)); byid = {str(x["id"]): x for x in c["cands"]}
                kept = [(t, (L.get("geom") or {}).get(str(i)) or [byid[str(i)][k] for k in ("x1", "z1", "x2", "z2")], byid[str(i)])
                        for i, t in L["keep"].items() if str(i) in byid and t in ("w-solid", "w-dashed", "y-solid", "y-dashed")]
                if not kept: continue
                frame = np.asarray(Image.open(jp).convert("RGB"))
                bp = bev_prob(net, dev, frame, luts[sess])
                rgb = ag.stitch(frame, luts[sess]).astype(np.float32); lum = 0.299 * rgb[..., 0] + 0.587 * rgb[..., 1] + 0.114 * rgb[..., 2]
                for t, g, x in kept:
                    st = profile(bp, *g)
                    if st is None: continue
                    ft = feats(st)
                    if ft is None: continue
                    sr = profile_rgb(lum, bp, *g); fr2 = feats(sr) if sr is not None else None
                    for k in FEATS: ft["c_" + k] = (fr2 or {}).get(k, 0.0)
                    ft["c_ok"] = 1 if fr2 else 0
                    rows.append(dict(ft, dashed=1 if "dashed" in t else 0, color=t[0], group=f"{sess}/{int(fr) // 150}",
                                     night=1 if int(sess.split("_")[2][:2]) >= 18 or int(sess.split("_")[2][:2]) < 6 else 0,
                                     dev_d=int(bool(x.get("d"))) if x.get("src") == "dev" else -1))
    keys = FEATS + ["c_" + k for k in FEATS] + ["c_ok", "dashed", "color", "group", "night", "dev_d"]
    with open(a.out, "w", newline="") as fo:
        w = csv.DictWriter(fo, fieldnames=keys); w.writeheader()
        for r in rows: w.writerow({k: r.get(k) for k in keys})
    print("線", len(rows), "虛", sum(r["dashed"] for r in rows), "實", sum(1 - r["dashed"] for r in rows))


if __name__ == "__main__":
    main()
