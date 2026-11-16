#!/usr/bin/env python3
"""
把 far_cands.py 的審查結果（review/out_*.jsonl）變成「只在審過的區塊計分」的訓練資料，並能評估模型在這些區塊上還誤判多少。

遮罩（480x272）：預設全部 255（不計分）；
  not_paint / other_paint → 該區塊（外擴 2 px）= 0（確定不是車道線）
  lane                    → 該區塊裡模型很有把握（機率 ≥ 0.7）的部分 = 1，其餘不計分（區塊通常比線粗）
  mixed / unsure          → 不計分
同一段路（session, frame//150）整組分到訓練或驗證（驗證約 20%），跟 train.py 的切法一樣不會互相洩漏。

用法：
  python tools/lane_label/hardneg_ds.py build --hn ~/avm-data/lane_hn
     → lane_hn/ds_train、lane_hn/ds_val（各有 index.json、img/、mask/；ds_train 給 train.py --extra）
  python tools/lane_label/hardneg_ds.py eval --hn ~/avm-data/lane_hn --ckpt best.pt --size 224x128
     → 驗證區塊：not_paint 還被畫成線的比例（像素、區塊）、lane 區塊還抓得到的比例
實車影像只放在本機，不要 commit。
"""
import argparse, glob, json, os, random, sys
import numpy as np
from PIL import Image
from scipy import ndimage

HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)

NEG = {"not_paint", "other_paint"}


def load_reviews(hn):
    lab = {}
    for f in sorted(glob.glob(os.path.join(hn, "review", "out_*.jsonl"))):
        for line in open(f):
            line = line.strip()
            if not line: continue
            try: o = json.loads(line)
            except Exception: continue
            for k, v in o.get("t", {}).items(): lab[(o["grid"], int(k))] = v
    return lab


def group_split(names, seed=3, val_frac=0.2):
    groups = sorted({(n.rsplit("_", 2)[0], int(n.rsplit("_", 2)[1]) // 150) for n in names})
    random.Random(seed).shuffle(groups)
    return set(groups[:max(1, int(len(groups) * val_frac))])


def per_image(hn, tiles_file="tiles.json"):
    tiles = json.load(open(os.path.join(hn, tiles_file))); rv = load_reviews(hn)
    by = {}
    for t in tiles:
        v = rv.get((t.get("grid"), t.get("tile")))
        if v is None: continue
        by.setdefault(t["name"], []).append((t["comp"], v))
    return by


def build(a):
    by = per_image(a.hn, a.tiles)
    vg = group_split(list(by))
    if a.holdout is not None:               # 整段留下來測試（不是隨機切）：holdout 那幾段當驗證，其他全部訓練
        hs = set(x for x in a.holdout.split(",") if x)
        vg = {(n.rsplit("_", 2)[0], int(n.rsplit("_", 2)[1]) // 150) for n in by if n.rsplit("_", 2)[0] in hs}
    cnt = {}; out = {"ds_train": [], "ds_val": []}
    for d in out:
        for sub in ("img", "mask"): os.makedirs(os.path.join(a.hn, d, sub), exist_ok=True)
    for name, comps in sorted(by.items()):
        z = np.load(os.path.join(a.hn, "comp", name + ".npz")); lab, prob = z["lab"], z["prob"]
        m = np.full(lab.shape, 255, np.uint8)
        for cid, v in comps:
            cnt[v] = cnt.get(v, 0) + 1
            reg = lab == cid
            if v in NEG: m[ndimage.binary_dilation(reg, iterations=2) & (m == 255)] = 0
        for cid, v in comps:                     # 線最後畫（蓋過旁邊外擴的背景）
            if v == "lane": m[(lab == cid) & (prob >= 178)] = 1
        if not ((m == 0) | (m == 1)).any(): continue
        s, fr, q = name.rsplit("_", 2)
        d = "ds_val" if (s, int(fr) // 150) in vg else "ds_train"
        dst = os.path.join(a.hn, d, "img", name + ".jpg")
        if not os.path.exists(dst): os.symlink(os.path.join(a.hn, "img", name + ".jpg"), dst)
        Image.fromarray(m).save(os.path.join(a.hn, d, "mask", name + ".png"))
        out[d].append(dict(name=name, session=s, frame=int(fr), quad=int(q[1:]), partial=False, line_px=int((m == 1).sum())))
    for d, items in out.items(): json.dump(items, open(os.path.join(a.hn, d, "index.json"), "w"))
    print("審查結果", cnt, "→ 訓練", len(out["ds_train"]), "張、驗證", len(out["ds_val"]), "張")


def evaluate(a):
    import torch, train as T
    dev = torch.device("mps" if torch.backends.mps.is_available() else "cpu")
    net = T.LaneNet(pretrained=False); net.load_state_dict(torch.load(a.ckpt, map_location="cpu")); net.to(dev).eval()
    sz = tuple(int(v) for v in a.size.split("x"))
    by = per_image(a.hn); vg = group_split(list(by))
    st = dict(neg_px=0, neg_fp=0, neg_n=0, neg_hit=0, lane_px=0, lane_tp=0, lane_n=0, lane_hit=0)
    for name, comps in sorted(by.items()):
        s, fr, _ = name.rsplit("_", 2)
        if (s, int(fr) // 150) not in vg: continue
        z = np.load(os.path.join(a.hn, "comp", name + ".npz")); lab, prob0 = z["lab"], z["prob"]
        im = Image.open(os.path.join(a.hn, "img", name + ".jpg")).convert("RGB")
        x = (torch.from_numpy(np.asarray(im.resize(sz, Image.BILINEAR), np.float32) / 255).permute(2, 0, 1) - T.MEAN) / T.STD
        with torch.no_grad():
            pr = torch.softmax(net(x.unsqueeze(0).to(dev)), 1)[:, 1:2]
            pr = torch.nn.functional.interpolate(pr, size=lab.shape, mode="bilinear", align_corners=False)[0, 0].cpu().numpy()
        pred = pr >= 0.5
        for cid, v in comps:
            reg = lab == cid
            if v in NEG:
                st["neg_px"] += reg.sum(); st["neg_fp"] += (pred & reg).sum(); st["neg_n"] += 1
                st["neg_hit"] += int((pred & reg).sum() >= 0.3 * reg.sum())
            elif v == "lane":
                core = reg & (prob0 >= 178)
                if core.sum() == 0: continue
                st["lane_px"] += core.sum(); st["lane_tp"] += (pred & core).sum(); st["lane_n"] += 1
                st["lane_hit"] += int((pred & core).sum() >= 0.3 * core.sum())
    r = dict(
        誤判像素還剩=round(st["neg_fp"] / max(1, st["neg_px"]), 3), 誤判區塊還剩=round(st["neg_hit"] / max(1, st["neg_n"]), 3), 誤判區塊數=st["neg_n"],
        真線像素抓到=round(st["lane_tp"] / max(1, st["lane_px"]), 3), 真線區塊抓到=round(st["lane_hit"] / max(1, st["lane_n"]), 3), 真線區塊數=st["lane_n"])
    print(json.dumps(r, ensure_ascii=False))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["build", "eval"]); ap.add_argument("--hn", required=True)
    ap.add_argument("--ckpt"); ap.add_argument("--size", default="224x128")
    ap.add_argument("--tiles", default="tiles.json", help="區塊清單檔（補審的那批是 tiles_only.json）")
    ap.add_argument("--holdout", default=None, help="整段留做驗證的 session（逗號分隔）；不給 = 隨機 20%%")
    a = ap.parse_args()
    build(a) if a.cmd == "build" else evaluate(a)


if __name__ == "__main__":
    main()
