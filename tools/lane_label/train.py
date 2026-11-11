#!/usr/bin/env python3
"""
魚眼道路線分割模型（第一版）：MobileNetV3-Small 編碼器（ImageNet 預訓練）＋ 簡單 U-Net 解碼器。
輸入一顆鏡頭 480x272 RGB，輸出 3 類（背景／白線／黃線）同尺寸機率圖。遮罩 255 = 不計分。

切分：同一段路不能同時出現在訓練和驗證（以影格號每 150 張一段分組，整段分到同一邊）。
用法：
  python tools/lane_label/train.py --data ~/avm-data/lane_ds --out ~/avm-data/lane_runs/v1 --epochs 40
裝置：Apple 晶片用 MPS，沒有就 CPU。
"""
import argparse, json, os, random, time
import numpy as np
import torch, torch.nn as nn, torch.nn.functional as F
from PIL import Image
from torchvision.models import mobilenet_v3_small, MobileNet_V3_Small_Weights

MEAN = torch.tensor([0.485, 0.456, 0.406]).view(3, 1, 1)
STD = torch.tensor([0.229, 0.224, 0.225]).view(3, 1, 1)


class LaneNet(nn.Module):
    def __init__(self, n_cls=3, pretrained=True):
        super().__init__()
        f = mobilenet_v3_small(weights=MobileNet_V3_Small_Weights.DEFAULT if pretrained else None).features
        # 取 1/2、1/4、1/8、1/16 四層輸出當 skip
        self.s1, self.s2, self.s3, self.s4 = f[:1], f[1:2], f[2:4], f[4:9]   # 16@/2, 16@/4, 24@/8, 48@/16
        def blk(i, o): return nn.Sequential(nn.Conv2d(i, o, 3, padding=1, bias=False), nn.BatchNorm2d(o), nn.ReLU(inplace=True))
        self.d3 = blk(48 + 24, 48); self.d2 = blk(48 + 16, 32); self.d1 = blk(32 + 16, 24)
        self.head = nn.Sequential(blk(24, 16), nn.Conv2d(16, n_cls, 1))

    def forward(self, x):
        h, w = x.shape[-2:]
        a = self.s1(x); b = self.s2(a); c = self.s3(b); d = self.s4(c)
        y = F.interpolate(d, size=c.shape[-2:], mode="bilinear", align_corners=False); y = self.d3(torch.cat([y, c], 1))
        y = F.interpolate(y, size=b.shape[-2:], mode="bilinear", align_corners=False); y = self.d2(torch.cat([y, b], 1))
        y = F.interpolate(y, size=a.shape[-2:], mode="bilinear", align_corners=False); y = self.d1(torch.cat([y, a], 1))
        return F.interpolate(self.head(y), size=(h, w), mode="bilinear", align_corners=False)


class DS(torch.utils.data.Dataset):
    def __init__(self, root, items, aug):
        self.root, self.items, self.aug = root, items, aug

    def __len__(self): return len(self.items)

    def __getitem__(self, i):
        it = self.items[i]
        img = np.asarray(Image.open(os.path.join(self.root, "img", it["name"] + ".jpg")).convert("RGB"), np.float32) / 255.0
        m = np.asarray(Image.open(os.path.join(self.root, "mask", it["name"] + ".png")), np.int64)
        if self.aug:
            # 亮度／對比／色偏（夜間、路燈），小量雜訊；不做翻轉（左右鏡頭幾何不對稱，翻了線的位置就不對）
            img = img * random.uniform(0.6, 1.4) + random.uniform(-0.1, 0.1)
            img = img * np.array([random.uniform(0.9, 1.1) for _ in range(3)], np.float32)
            img = img + np.random.normal(0, 0.02, img.shape).astype(np.float32)
            img = np.clip(img, 0, 1)
        t = (torch.from_numpy(img).permute(2, 0, 1) - MEAN) / STD
        return t, torch.from_numpy(m)


def split(items, val_frac=0.2, seed=1):
    groups = {}
    for it in items:
        groups.setdefault((it["session"], it["frame"] // 150), []).append(it)
    keys = sorted(groups); random.Random(seed).shuffle(keys)
    val, tr = [], []
    for k in keys:
        (val if len(val) < val_frac * len(items) else tr).extend(groups[k])
    return tr, val


def metrics(logits, m):
    p = logits.argmax(1)
    valid = m != 255
    out = {}
    for c, n in [(1, "white"), (2, "yellow")]:
        tp = ((p == c) & (m == c) & valid).sum().item(); fp = ((p == c) & (m != c) & valid).sum().item(); fn = ((p != c) & (m == c)).sum().item()
        out[n] = (tp, fp, fn)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("--epochs", type=int, default=40); ap.add_argument("--bs", type=int, default=16); ap.add_argument("--lr", type=float, default=2e-3)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    dev = torch.device("mps" if torch.backends.mps.is_available() else "cpu")
    items = json.load(open(os.path.join(a.data, "index.json")))
    tr, va = split(items)
    print(f"訓練 {len(tr)}、驗證 {len(va)} 張（裝置 {dev}）")
    dl = torch.utils.data.DataLoader(DS(a.data, tr, True), batch_size=a.bs, shuffle=True, num_workers=2, drop_last=True)
    dv = torch.utils.data.DataLoader(DS(a.data, va, False), batch_size=a.bs, num_workers=2)
    net = LaneNet().to(dev)
    opt = torch.optim.AdamW(net.parameters(), lr=a.lr, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=a.lr, total_steps=a.epochs * max(1, len(dl)))
    wts = torch.tensor([1.0, 8.0, 8.0], device=dev)       # 線很細、很少：加權
    best, log = -1.0, []
    for ep in range(a.epochs):
        net.train(); t0 = time.time(); tl = 0.0
        for x, m in dl:
            x, m = x.to(dev), m.to(dev)
            lo = net(x)
            loss = F.cross_entropy(lo, m, weight=wts, ignore_index=255)
            opt.zero_grad(); loss.backward(); opt.step(); sched.step(); tl += loss.item()
        net.eval(); agg = {"white": [0, 0, 0], "yellow": [0, 0, 0]}
        with torch.no_grad():
            for x, m in dv:
                r = metrics(net(x.to(dev)).cpu(), m)
                for k in agg: agg[k] = [u + v for u, v in zip(agg[k], r[k])]
        f1 = {}
        for k, (tp, fp, fn) in agg.items():
            pr = tp / max(1, tp + fp); rc = tp / max(1, tp + fn); f1[k] = (2 * pr * rc / max(1e-9, pr + rc), pr, rc)
        score = f1["white"][0] * 0.7 + f1["yellow"][0] * 0.3
        log.append(dict(epoch=ep + 1, loss=tl / max(1, len(dl)), f1=f1))
        print(f"ep {ep + 1:3d} loss {tl / max(1, len(dl)):.3f}  白 F1 {f1['white'][0]:.3f}（P {f1['white'][1]:.2f} R {f1['white'][2]:.2f}）"
              f"  黃 F1 {f1['yellow'][0]:.3f}  {time.time() - t0:.0f}s", flush=True)
        if score > best:
            best = score; torch.save(net.state_dict(), os.path.join(a.out, "best.pt"))
    json.dump(log, open(os.path.join(a.out, "log.json"), "w"), ensure_ascii=False, indent=1)
    print("最佳分數", round(best, 3))


if __name__ == "__main__":
    main()
