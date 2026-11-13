#!/usr/bin/env python3
"""
紅綠燈燈號分類器（LISA Traffic Light，CC BY-NC-SA 4.0）：紅綠燈偵測框裁切 48x48 → 燈色（紅／黃／綠）＋ 箭頭（無／左轉／直行）。

LISA 類別：go、goForward、goLeft、stop、stopLeft、warning、warningLeft（YOLO 格式，每個框是一個燈頭）。
LISA 是美國的直式燈（紅上綠下）；台灣多是橫式（紅左綠右）→ 訓練時一半轉 90°（逆時針：上 → 左），兩種都學。
「熄燈」不在這裡學：360 端沿用原本的暗燈規則。
驗證：依序列（dayClip／nightSequence…）切。

用法：~/avm-data/.venv312/bin/python tools/det_label/tl_train.py --lisa ~/avm-data/ext/lisa/data --out ~/avm-data/tl_runs/v1 [--export]
輸出 TFLite：輸入 1x48x48x3（RGB 0–1），輸出 1x6 原始分數（前 3 = 紅黃綠，後 3 = 無箭頭／左轉／直行）。
"""
import argparse, glob, os, random, shutil, subprocess, sys
import numpy as np
import torch, torch.nn as nn, torch.nn.functional as F
from PIL import Image

S = 48
# LISA 類別 → (燈色 0 紅 1 黃 2 綠, 箭頭 0 無 1 左 2 直行)
MAP = {0: (2, 0), 1: (2, 2), 2: (2, 1), 3: (0, 0), 4: (0, 1), 5: (1, 0), 6: (1, 1)}
MEAN = torch.tensor([0.485, 0.456, 0.406]).view(1, 3, 1, 1); STD = torch.tensor([0.229, 0.224, 0.225]).view(1, 3, 1, 1)


def crops(lisa, splits, cache):
    os.makedirs(cache, exist_ok=True)
    rows = []
    for sp in splits:
        for lf in glob.glob(os.path.join(lisa, "labels", sp, "**", "*.txt"), recursive=True):
            ip = lf.replace("/labels/", "/images/")[:-4] + ".jpg"
            if not os.path.exists(ip): continue
            lines = [l.split() for l in open(lf) if len(l.split()) == 5]
            if not lines: continue
            im = None
            seq = os.path.basename(ip).split("--")[0]
            for k, (c, cx, cy, w, h) in enumerate(lines):
                c = int(c)
                if c not in MAP: continue
                name = os.path.basename(ip)[:-4] + f"_{k}.png"; out = os.path.join(cache, name)
                if not os.path.exists(out):
                    if im is None: im = Image.open(ip).convert("RGB")
                    W, H = im.size; cx, cy, w, h = float(cx) * W, float(cy) * H, float(w) * W, float(h) * H
                    if w < 4 or h < 8: continue
                    pw, ph = w * 0.2, h * 0.1
                    cr = im.crop((max(0, cx - w / 2 - pw), max(0, cy - h / 2 - ph), min(W, cx + w / 2 + pw), min(H, cy + h / 2 + ph)))
                    sc = min(S / cr.size[0], S / cr.size[1])
                    cr = cr.resize((max(1, round(cr.size[0] * sc)), max(1, round(cr.size[1] * sc))), Image.BILINEAR)
                    bg = Image.new("RGB", (S, S), (0, 0, 0)); bg.paste(cr, ((S - cr.size[0]) // 2, (S - cr.size[1]) // 2)); bg.save(out)
                col, arr = MAP[c]
                rows.append((out, col, arr, seq))
    return rows


class Net(nn.Module):
    def __init__(self, pretrained=True):
        super().__init__()
        import torchvision
        m = torchvision.models.mobilenet_v3_small(weights="DEFAULT" if pretrained else None)
        self.f = m.features; self.head = nn.Sequential(nn.AdaptiveAvgPool2d(1), nn.Flatten(), nn.Dropout(0.2), nn.Linear(576, 6))

    def forward(self, x):
        return self.head(self.f((x - MEAN.to(x.device)) / STD.to(x.device)))


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--lisa", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("--epochs", type=int, default=10); ap.add_argument("--export", action="store_true")
    a = ap.parse_args(); os.makedirs(a.out, exist_ok=True)
    splits = [s for s in ("train", "val", "test") if glob.glob(os.path.join(a.lisa, "labels", s, "**", "*.txt"), recursive=True)]
    rows = crops(a.lisa, splits, os.path.join(a.out, "crops"))
    seqs = sorted({r[3] for r in rows}); random.Random(4).shuffle(seqs)
    val_s = set(seqs[:max(1, len(seqs) // 6)])
    tr = [r for r in rows if r[3] not in val_s]; va = [r for r in rows if r[3] in val_s]
    print("裁切", len(rows), "訓練", len(tr), "驗證", len(va), "燈色分布", np.bincount([r[1] for r in tr]).tolist(), "箭頭", np.bincount([r[2] for r in tr], minlength=3).tolist(), flush=True)
    def T(rs):
        X = np.stack([np.asarray(Image.open(p).convert("RGB"), np.float32) / 255 for p, *_ in rs])
        return torch.from_numpy(X).permute(0, 3, 1, 2), torch.tensor([r[1] for r in rs]), torch.tensor([r[2] for r in rs])
    Xt, Ct, At = T(tr); Xv, Cv, Av = T(va)
    dev = torch.device("mps" if torch.backends.mps.is_available() else "cpu")
    net = Net().to(dev); opt = torch.optim.AdamW(net.parameters(), lr=1e-3, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, a.epochs); best = -1
    wc = torch.bincount(Ct, minlength=3).float(); wc = (wc.sum() / (wc + 1)); wc = (wc / wc.mean()).to(dev)
    wa = torch.bincount(At, minlength=3).float(); wa = (wa.sum() / (wa + 1)).clamp(max=20); wa = (wa / wa.mean()).to(dev)
    for ep in range(a.epochs):
        net.train(); perm = torch.randperm(len(Xt))
        for i in range(0, len(Xt), 256):
            b = perm[i:i + 256]; x = Xt[b].to(dev); c = Ct[b].to(dev); ar = At[b].to(dev)
            rot = torch.rand(len(x), device=dev) < 0.5
            x = torch.where(rot[:, None, None, None], torch.rot90(x, 1, (2, 3)), x)    # 逆時針 90°：直式 → 橫式（紅在左）
            x = (x * (0.6 + 0.8 * torch.rand(len(x), 1, 1, 1, device=dev))).clamp(0, 1)
            o = net(x)
            loss = F.cross_entropy(o[:, :3], c, weight=wc) + F.cross_entropy(o[:, 3:], ar, weight=wa)
            opt.zero_grad(); loss.backward(); opt.step()
        sched.step(); net.eval()
        res = []
        for rotv in (False, True):
            with torch.no_grad():
                P = torch.cat([net((torch.rot90(Xv[i:i + 512], 1, (2, 3)) if rotv else Xv[i:i + 512]).to(dev)).cpu() for i in range(0, len(Xv), 512)])
            res.append(((P[:, :3].argmax(1) == Cv).float().mean().item(), ((P[:, 3:].argmax(1) == Av).float().mean().item())))
        print(f"ep {ep + 1} loss {loss.item():.3f}  直式 燈色 {res[0][0]:.3f} 箭頭 {res[0][1]:.3f}  橫式 燈色 {res[1][0]:.3f} 箭頭 {res[1][1]:.3f}", flush=True)
        sc = sum(res[0]) + sum(res[1])
        if sc > best: best = sc; torch.save(net.state_dict(), os.path.join(a.out, "best.pt"))
    if a.export:
        net = Net(pretrained=False); net.load_state_dict(torch.load(os.path.join(a.out, "best.pt"), map_location="cpu")); net.eval()
        onnx = os.path.join(a.out, "tl.onnx")
        torch.onnx.export(net, torch.zeros(1, 3, S, S), onnx, input_names=["image"], output_names=["logits"], opset_version=17, dynamo=False)
        tf_dir = os.path.join(a.out, "tf"); shutil.rmtree(tf_dir, ignore_errors=True)
        cal = os.path.expanduser("~/avm-data/lane_runs/v7/tflite/calibration_image_sample_data_20x128x128x3_float32.npy")
        if os.path.exists(cal): shutil.copy(cal, ".")
        subprocess.check_call([sys.executable, "-m", "onnx2tf", "-i", onnx, "-o", tf_dir, "-osd"], stdout=subprocess.DEVNULL)
        print(glob.glob(os.path.join(tf_dir, "*.tflite")))


if __name__ == "__main__":
    main()
