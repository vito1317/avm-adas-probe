#!/usr/bin/env python3
"""
車燈分類器：車框裁切 96x96 → 三個獨立的「亮不亮」：煞車燈、畫面左側方向燈、畫面右側方向燈（sigmoid，多標籤）。
不知道（-1）的不算損失。閃爍交給 360 端看連續幾幀（這裡只學「這一幀哪顆燈亮」）。

資料：TLD 裁切（tld_crops.py，labels.csv）＋ 自己標的魚眼車框（lamp_sheets.py 的標註，量少，當微調／驗證）。
驗證：TLD 依影片切 10% ＋ 自己的魚眼標註全部（看在真的魚眼上表現如何）。

用法：~/avm-data/.venv312/bin/python tools/det_label/lamp_train.py --tld ~/avm-data/lamp_ext/tld --own ~/avm-data/lamp \
        --out ~/avm-data/lamp_runs/v1 [--export]
輸出 TFLite：輸入 1x96x96x3（RGB 0–1），輸出 1x3 原始分數（brake, left, right）。
"""
import argparse, glob, json, os, random, shutil, subprocess, sys
import numpy as np
import torch, torch.nn as nn, torch.nn.functional as F
from PIL import Image

S = 96
MEAN = torch.tensor([0.485, 0.456, 0.406]).view(1, 3, 1, 1); STD = torch.tensor([0.229, 0.224, 0.225]).view(1, 3, 1, 1)


class Net(nn.Module):
    def __init__(self, pretrained=True):
        super().__init__()
        import torchvision
        m = torchvision.models.mobilenet_v3_small(weights="DEFAULT" if pretrained else None)
        self.f = m.features; self.head = nn.Sequential(nn.AdaptiveAvgPool2d(1), nn.Flatten(), nn.Dropout(0.2), nn.Linear(576, 3))

    def forward(self, x):
        return self.head(self.f((x - MEAN.to(x.device)) / STD.to(x.device)))


def load_tld(d, max_n):
    rows = []
    for line in open(os.path.join(d, "labels.csv")):
        p = line.strip().split(",")
        if len(p) < 4: continue
        rows.append((os.path.join(d, "img", p[0] + ".jpg"), [int(p[1]), int(p[2]), int(p[3])], p[0].split("_")[0]))
    random.Random(0).shuffle(rows)
    # 方向燈亮的很少：全部留下；其他的抽樣
    isp = [r[1][1] == 1 or r[1][2] == 1 or r[1][0] == 1 for r in rows]
    pos = [r for r, f in zip(rows, isp) if f]
    neg = [r for r, f in zip(rows, isp) if not f][:max(0, max_n - len(pos))]
    return pos + neg


def load_own(d):
    """自己標的魚眼車框：lamp_sheets 的 sheets.json ＋ results/*.jsonl → 從原圖重裁 96x96。"""
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from tld_crops import letterbox
    sheets = {s["sheet"]: s["cells"] for s in json.load(open(os.path.join(d, "sheets.json")))}
    out = []; os.makedirs(os.path.join(d, "crop96"), exist_ok=True)
    for f in glob.glob(os.path.join(d, "results", "*.jsonl")):
        for line in open(f):
            try: r = json.loads(line)
            except Exception: continue
            cells = sheets.get(r.get("sheet"))
            if not cells: continue
            for k, c in enumerate(cells):
                v = r.get(str(k + 1))
                if not isinstance(v, dict): continue
                lab = [1 if str(v.get(x)) == "1" else 0 if str(v.get(x)) == "0" else -1 for x in ("brake", "left", "right")]
                if lab == [-1, -1, -1]: continue
                p = os.path.join(d, "crop96", "%s_%d.jpg" % (r["sheet"], k))
                if not os.path.exists(p):
                    im = Image.open(c["img"]).convert("RGB"); W = im.size[0]
                    cx, cy, w, h = c["box"]
                    letterbox(im, ((cx - w / 2) * W, (cy - h / 2) * W, (cx + w / 2) * W, (cy + h / 2) * W)).save(p, quality=92)
                out.append((p, lab, "own"))
    return out


def tensors(rows):
    X = np.stack([np.asarray(Image.open(p).convert("RGB").resize((S, S)), np.float32) / 255.0 for p, _, _ in rows])
    Y = np.array([l for _, l, _ in rows], np.float32)
    return torch.from_numpy(X).permute(0, 3, 1, 2), torch.from_numpy(Y)


def evaluate(net, X, Y, dev, name):
    net.eval(); P = []
    with torch.no_grad():
        for i in range(0, len(X), 256): P.append(torch.sigmoid(net(X[i:i + 256].to(dev))).cpu())
    P = torch.cat(P) if P else torch.zeros(0, 3)
    res = {}
    for j, n in enumerate(["brake", "left", "right"]):
        m = Y[:, j] >= 0
        if m.sum() == 0: continue
        y = Y[m, j] > 0.5; p = P[m, j] > 0.5
        tp = (p & y).sum().item(); fp = (p & ~y).sum().item(); fn = (~p & y).sum().item()
        res[n] = dict(n=int(m.sum()), pos=int(y.sum()), prec=round(tp / max(1, tp + fp), 3), rec=round(tp / max(1, tp + fn), 3))
    print("  ", name, res, flush=True)
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tld", required=True); ap.add_argument("--own", default=""); ap.add_argument("--out", required=True)
    ap.add_argument("--epochs", type=int, default=12); ap.add_argument("--max_tld", type=int, default=60000); ap.add_argument("--export", action="store_true")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    dev = torch.device("mps" if torch.backends.mps.is_available() else "cpu")
    tld = load_tld(a.tld, a.max_tld)
    vids = sorted({v for *_, v in tld}); random.Random(1).shuffle(vids)
    val_v = set(vids[:max(1, len(vids) // 10)])
    tr = [r for r in tld if r[2] not in val_v]; va = [r for r in tld if r[2] in val_v]
    own = load_own(a.own) if a.own else []
    print("TLD 訓練", len(tr), "驗證", len(va), "自己的魚眼", len(own), flush=True)
    Xt, Yt = tensors(tr); Xv, Yv = tensors(va); Xo, Yo = tensors(own) if own else (torch.zeros(0, 3, S, S), torch.zeros(0, 3))
    # 方向燈少：正例加權
    pw = []
    for j in range(3):
        m = Yt[:, j] >= 0; pos = (Yt[m, j] > 0.5).sum().item(); neg = m.sum().item() - pos
        pw.append(min(20.0, neg / max(1, pos)))
    pw = torch.tensor(pw, device=dev); print("正例權重", pw.tolist(), flush=True)
    net = Net().to(dev); opt = torch.optim.AdamW(net.parameters(), lr=1e-3, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, a.epochs); best = -1
    for ep in range(a.epochs):
        net.train(); perm = torch.randperm(len(Xt))
        for i in range(0, len(Xt), 128):
            idx = perm[i:i + 128]; x = Xt[idx].to(dev); y = Yt[idx].to(dev)
            # 左右翻轉：左右方向燈對調（煞車不變）
            flip = torch.rand(len(x), device=dev) < 0.5
            x = torch.where(flip[:, None, None, None], x.flip(3), x)
            y = torch.where(flip[:, None], y[:, [0, 2, 1]], y)
            x = (x * (0.6 + 0.8 * torch.rand(len(x), 1, 1, 1, device=dev))).clamp(0, 1)
            m = (y >= 0).float()
            loss = (F.binary_cross_entropy_with_logits(net(x), y.clamp(min=0), pos_weight=pw, reduction="none") * m).sum() / m.sum().clamp(min=1)
            opt.zero_grad(); loss.backward(); opt.step()
        sched.step()
        print(f"ep {ep + 1} loss {loss.item():.3f}", flush=True)
        rv = evaluate(net, Xv, Yv, dev, "TLD 驗證")
        if len(Xo): evaluate(net, Xo, Yo, dev, "自己的魚眼")
        score = sum(v["prec"] + v["rec"] for v in rv.values())
        if score > best: best = score; torch.save(net.state_dict(), os.path.join(a.out, "best.pt"))
    if a.export:
        net = Net(pretrained=False); net.load_state_dict(torch.load(os.path.join(a.out, "best.pt"), map_location="cpu")); net.eval()
        onnx = os.path.join(a.out, "lamp.onnx")
        torch.onnx.export(net, torch.zeros(1, 3, S, S), onnx, input_names=["image"], output_names=["logits"], opset_version=17, dynamo=False)
        tf_dir = os.path.join(a.out, "tf"); shutil.rmtree(tf_dir, ignore_errors=True)
        cal = os.path.expanduser("~/avm-data/lane_runs/v7/tflite/calibration_image_sample_data_20x128x128x3_float32.npy")
        if os.path.exists(cal): shutil.copy(cal, ".")
        subprocess.check_call([sys.executable, "-m", "onnx2tf", "-i", onnx, "-o", tf_dir, "-osd"], stdout=subprocess.DEVNULL)
        print(glob.glob(os.path.join(tf_dir, "*.tflite")))


if __name__ == "__main__":
    main()
