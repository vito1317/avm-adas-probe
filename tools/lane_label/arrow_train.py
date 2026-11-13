#!/usr/bin/env python3
"""
路面箭頭分類器：路面小圖（ground_patch.py）切成的單一車道條（寬 3.6 m × 前方 8 m）→ 8 類：
none、S 直行、L 左轉、R 右轉、SL、SR、U 迴轉、LR。
輸入縮成 64x128（寬x高）。左右翻轉當增強：L↔R、SL↔SR（S、U、LR、none 不變）。
驗證：依影格批次（session）切，避免同一個路口的影格同時出現在訓練和驗證。

用法：~/avm-data/.venv312/bin/python tools/lane_label/arrow_train.py --ds ~/avm-data/arrow_ds --out ~/avm-data/arrow_runs/v1 [--export]
"""
import argparse, glob, json, os, random, shutil, subprocess, sys
import numpy as np
import torch, torch.nn as nn, torch.nn.functional as F
from PIL import Image

K = ["none", "S", "L", "R", "SL", "SR", "U", "LR"]
FLIP = [0, 1, 3, 2, 5, 4, 6, 7]
IW, IH = 64, 128
MEAN = torch.tensor([0.485, 0.456, 0.406]).view(1, 3, 1, 1); STD = torch.tensor([0.229, 0.224, 0.225]).view(1, 3, 1, 1)


class Net(nn.Module):
    def __init__(self, pretrained=True):
        super().__init__()
        import torchvision
        m = torchvision.models.mobilenet_v3_small(weights="DEFAULT" if pretrained else None)
        self.f = m.features; self.head = nn.Sequential(nn.AdaptiveAvgPool2d(1), nn.Flatten(), nn.Dropout(0.3), nn.Linear(576, len(K)))

    def forward(self, x):
        return self.head(self.f((x - MEAN.to(x.device)) / STD.to(x.device)))


def load(ds, rows):
    X = np.stack([np.asarray(Image.open(os.path.join(ds, "img", r["img"])).convert("RGB").resize((IW, IH), Image.BILINEAR), np.float32) / 255 for r in rows])
    return torch.from_numpy(X).permute(0, 3, 1, 2), torch.tensor([r["label"] for r in rows])


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--ds", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("--epochs", type=int, default=40); ap.add_argument("--export", action="store_true")
    a = ap.parse_args(); os.makedirs(a.out, exist_ok=True)
    rows = json.load(open(os.path.join(a.ds, "index.json")))
    sess = sorted({r["sess"] for r in rows}); random.Random(2).shuffle(sess)
    # 驗證：挑幾個有箭頭的批次（不然驗證集裡沒有箭頭）
    pos_sess = [s for s in sess if any(r["sess"] == s and r["label"] > 0 for r in rows)]
    val_s = set(pos_sess[:max(1, len(pos_sess) // 4)])
    tr = [r for r in rows if r["sess"] not in val_s]; va = [r for r in rows if r["sess"] in val_s]
    print("訓練", len(tr), "（箭頭", sum(r["label"] > 0 for r in tr), "）驗證", len(va), "（箭頭", sum(r["label"] > 0 for r in va), "）", sorted(val_s), flush=True)
    dev = torch.device("mps" if torch.backends.mps.is_available() else "cpu")
    Xt, Yt = load(a.ds, tr); Xv, Yv = load(a.ds, va)
    cnt = torch.bincount(Yt, minlength=len(K)).float() + torch.bincount(torch.tensor(FLIP)[Yt], minlength=len(K)).float()
    w = (cnt.sum() / (cnt + 1)).clamp(max=50); w = (w / w.mean()).to(dev)
    net = Net().to(dev); opt = torch.optim.AdamW(net.parameters(), lr=1e-3, weight_decay=1e-3)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, a.epochs); best = -1
    # 箭頭少：每個 epoch 把有箭頭的重複幾次
    pos_idx = torch.nonzero(Yt > 0).flatten()
    for ep in range(a.epochs):
        net.train()
        idx = torch.cat([torch.randperm(len(Xt)), pos_idx.repeat(6)]); idx = idx[torch.randperm(len(idx))]
        for i in range(0, len(idx), 64):
            b = idx[i:i + 64]; x = Xt[b].to(dev); y = Yt[b].to(dev)
            fl = torch.rand(len(x), device=dev) < 0.5
            x = torch.where(fl[:, None, None, None], x.flip(3), x); y = torch.where(fl, torch.tensor(FLIP, device=dev)[y], y)
            x = (x * (0.6 + 0.8 * torch.rand(len(x), 1, 1, 1, device=dev))).clamp(0, 1)
            loss = F.cross_entropy(net(x), y, weight=w, label_smoothing=0.05)
            opt.zero_grad(); loss.backward(); opt.step()
        sched.step()
        net.eval()
        with torch.no_grad(): P = torch.cat([net(Xv[i:i + 256].to(dev)).argmax(1).cpu() for i in range(0, len(Xv), 256)])
        isA = Yv > 0; pA = P > 0
        det_p = (pA & isA).sum().item() / max(1, pA.sum().item()); det_r = (pA & isA).sum().item() / max(1, isA.sum().item())
        typ = ((P == Yv) & isA).sum().item() / max(1, isA.sum().item())
        print(f"ep {ep + 1} loss {loss.item():.3f}  有無箭頭 準確率 {det_p:.2f} 抓到率 {det_r:.2f}  種類全對 {typ:.2f}", flush=True)
        score = det_p + det_r + typ
        if score > best: best = score; torch.save(net.state_dict(), os.path.join(a.out, "best.pt"))
    print("最佳", round(best, 3))
    if a.export:
        net = Net(pretrained=False); net.load_state_dict(torch.load(os.path.join(a.out, "best.pt"), map_location="cpu")); net.eval()
        onnx = os.path.join(a.out, "arrow.onnx")
        torch.onnx.export(net, torch.zeros(1, 3, IH, IW), onnx, input_names=["image"], output_names=["logits"], opset_version=17, dynamo=False)
        tf_dir = os.path.join(a.out, "tf"); shutil.rmtree(tf_dir, ignore_errors=True)
        cal = os.path.expanduser("~/avm-data/lane_runs/v7/tflite/calibration_image_sample_data_20x128x128x3_float32.npy")
        if os.path.exists(cal): shutil.copy(cal, ".")
        subprocess.check_call([sys.executable, "-m", "onnx2tf", "-i", onnx, "-o", tf_dir, "-osd"], stdout=subprocess.DEVNULL)
        print(glob.glob(os.path.join(tf_dir, "*.tflite")))


if __name__ == "__main__":
    main()
