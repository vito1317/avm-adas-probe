#!/usr/bin/env python3
"""
車頭朝向分類器（8 個方向，相對於鏡頭；見 orient_sheets.py）：車框裁切 → 64x64 → MobileNetV3-Small → 8 類。

輸入的做法要跟 360 端（OrientNet.kt）一樣：車框四周各加 15%，等比縮到放得進 64x64，置中，四周補灰（114）。
左右翻轉當資料增強：L↔R、FL↔FR、BL↔BR 對調（F、B 不變），等於資料多一倍。
驗證：sheets.json 裡 split=val 的車（自己的影格裡早上那一段），不參與訓練。

用法：~/avm-data/.venv312/bin/python tools/det_label/orient_train.py --orient ~/avm-data/orient --out ~/avm-data/orient_runs/v1
      加 --export 轉成 TFLite（orient.tflite，輸入 1x64x64x3 RGB 0–1，輸出 1x8 原始分數）
"""
import argparse, glob, json, os, random, shutil, subprocess, sys
import numpy as np
import torch, torch.nn as nn, torch.nn.functional as F
from PIL import Image

BINS = ["F", "FR", "R", "BR", "B", "BL", "L", "FL"]          # 每 45°：0 = 車頭正對鏡頭，順時針（往畫面右）
FLIP = {"F": "F", "B": "B", "L": "R", "R": "L", "FL": "FR", "FR": "FL", "BL": "BR", "BR": "BL"}
S = 64
MEAN = torch.tensor([0.485, 0.456, 0.406]).view(1, 3, 1, 1); STD = torch.tensor([0.229, 0.224, 0.225]).view(1, 3, 1, 1)


def crop64(img_path, box):
    im = Image.open(img_path).convert("RGB"); W = im.size[0]
    cx, cy, w, h = box
    x1, y1, x2, y2 = (cx - w / 2) * W, (cy - h / 2) * W, (cx + w / 2) * W, (cy + h / 2) * W
    pw, ph = (x2 - x1) * 0.15, (y2 - y1) * 0.15
    c = im.crop((max(0, x1 - pw), max(0, y1 - ph), min(W, x2 + pw), min(W, y2 + ph)))
    sc = min(S / c.size[0], S / c.size[1])
    c = c.resize((max(1, round(c.size[0] * sc)), max(1, round(c.size[1] * sc))), Image.BILINEAR)
    out = Image.new("RGB", (S, S), (114, 114, 114)); out.paste(c, ((S - c.size[0]) // 2, (S - c.size[1]) // 2))
    return np.asarray(out, np.float32) / 255.0


def load(orient_dir):
    sheets = {s["sheet"]: s["cells"] for s in json.load(open(os.path.join(orient_dir, "sheets.json")))}
    tr, va = [], []
    for f in glob.glob(os.path.join(orient_dir, "results", "*.jsonl")):
        for line in open(f):
            try: r = json.loads(line)
            except Exception: continue
            cells = sheets.get(r.get("sheet"))
            if not cells: continue
            for k, c in enumerate(cells):
                lab = str(r.get(str(k + 1), "x")).upper()
                if lab not in BINS: continue
                (va if c["split"] == "val" else tr).append((c["img"], c["box"], BINS.index(lab)))
    return tr, va


class Net(nn.Module):
    def __init__(self, pretrained=True):
        super().__init__()
        import torchvision
        m = torchvision.models.mobilenet_v3_small(weights="DEFAULT" if pretrained else None)
        self.f = m.features; self.head = nn.Sequential(nn.AdaptiveAvgPool2d(1), nn.Flatten(), nn.Dropout(0.2), nn.Linear(576, len(BINS)))

    def forward(self, x):            # x: NCHW 0–1
        return self.head(self.f((x - MEAN.to(x.device)) / STD.to(x.device)))


def tensors(items, flip=False):
    X = np.stack([crop64(p, b) for p, b, _ in items]); Y = np.array([y for _, _, y in items])
    if flip:
        X = np.concatenate([X, X[:, :, ::-1]]); Y = np.concatenate([Y, [BINS.index(FLIP[BINS[y]]) for y in Y]])
    return torch.from_numpy(np.ascontiguousarray(X)).permute(0, 3, 1, 2), torch.from_numpy(Y).long()


def evaluate(net, X, Y, dev):
    net.eval(); P = []
    with torch.no_grad():
        for i in range(0, len(X), 256): P.append(net(X[i:i + 256].to(dev)).argmax(1).cpu())
    P = torch.cat(P); d = (P - Y).abs(); d = torch.minimum(d, 8 - d)
    return dict(exact=float((d == 0).float().mean()), within45=float((d <= 1).float().mean()), n=len(Y))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--orient", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("--epochs", type=int, default=30); ap.add_argument("--export", action="store_true")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    dev = torch.device("mps" if torch.backends.mps.is_available() else "cpu")
    tr, va = load(a.orient)
    print("訓練", len(tr), "驗證", len(va), "分布", np.bincount([y for *_, y in tr], minlength=8).tolist(), flush=True)
    Xt, Yt = tensors(tr, flip=True); Xv, Yv = tensors(va)
    net = Net().to(dev); opt = torch.optim.AdamW(net.parameters(), lr=1e-3, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, a.epochs)
    best = -1
    for ep in range(a.epochs):
        net.train(); perm = torch.randperm(len(Xt))
        for i in range(0, len(Xt), 64):
            idx = perm[i:i + 64]; x = Xt[idx].to(dev); y = Yt[idx].to(dev)
            # 亮度／對比抖動（晚上、逆光）
            x = (x * (0.6 + 0.8 * torch.rand(len(x), 1, 1, 1, device=dev)) + 0.1 * (torch.rand(len(x), 1, 1, 1, device=dev) - 0.5)).clamp(0, 1)
            loss = F.cross_entropy(net(x), y, label_smoothing=0.1)
            opt.zero_grad(); loss.backward(); opt.step()
        sched.step()
        m = evaluate(net, Xv, Yv, dev)
        print(f"ep {ep + 1:2d} loss {loss.item():.3f}  驗證 完全對 {m['exact']:.3f}  差 45° 內 {m['within45']:.3f}", flush=True)
        if m["exact"] + m["within45"] > best:
            best = m["exact"] + m["within45"]; torch.save(net.state_dict(), os.path.join(a.out, "best.pt"))
    if a.export:
        net = Net(pretrained=False); net.load_state_dict(torch.load(os.path.join(a.out, "best.pt"), map_location="cpu")); net.eval()
        onnx = os.path.join(a.out, "orient.onnx")
        # NCHW 匯出；onnx2tf 會自己轉成 NHWC（1x64x64x3），跟車道線模型一樣
        torch.onnx.export(net, torch.zeros(1, 3, S, S), onnx, input_names=["image"], output_names=["logits"], opset_version=17, dynamo=False)
        tf_dir = os.path.join(a.out, "tf"); shutil.rmtree(tf_dir, ignore_errors=True)
        cal = os.path.expanduser("~/avm-data/lane_runs/v7/tflite/calibration_image_sample_data_20x128x128x3_float32.npy")
        if os.path.exists(cal): shutil.copy(cal, ".")
        subprocess.check_call([sys.executable, "-m", "onnx2tf", "-i", onnx, "-o", tf_dir, "-osd"], stdout=subprocess.DEVNULL)
        print(glob.glob(os.path.join(tf_dir, "*.tflite")))


if __name__ == "__main__":
    main()
