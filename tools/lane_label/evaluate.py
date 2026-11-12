#!/usr/bin/env python3
"""
評估車道線模型：驗證集（跟 train.py 同一套切分），容許偏差 [TOL] 像素。
  精確度：模型畫成線的像素裡，有多少在真的線 TOL 像素內（不計分的地方不算）。
  召回率：真的線的像素裡，有多少在模型畫的線 TOL 像素內。
另存幾張檢查圖（綠＝模型、紅＝標註）。
用法：python tools/lane_label/evaluate.py --data ~/avm-data/lane_ds --ckpt ~/avm-data/lane_runs/v1/best.pt --out ~/avm-data/lane_runs/v1/eval
"""
import argparse, json, os, sys
import numpy as np, torch
from PIL import Image
from scipy import ndimage

HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
import train as T   # noqa: E402

TOL = 3


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True); ap.add_argument("--ckpt", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("--size", default=None, help="模型的輸入尺寸 寬x高（跟訓練一樣）")
    ap.add_argument("--all", action="store_true", help="index.json 裡全部都當驗證（已經自己切好驗證集時用）")
    ap.add_argument("--thr", type=float, default=0.5); ap.add_argument("--show", type=int, default=8)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    dev = torch.device("mps" if torch.backends.mps.is_available() else "cpu")
    net = T.LaneNet(pretrained=False); net.load_state_dict(torch.load(a.ckpt, map_location="cpu")); net.to(dev).eval()
    items = json.load(open(os.path.join(a.data, "index.json")))
    _, va = (None, items) if a.all else T.split(items)
    sz = tuple(int(v) for v in a.size.split("x")) if a.size else None
    ds = T.DS(a.data, va, False, sz)
    full = T.DS(a.data, va, False, None)      # 評分一律在原尺寸（480x272）比，縮小版放大回去，容許偏差才一樣
    st = np.zeros(4)          # tp_p, n_p, tp_r, n_r
    shown = 0
    rng = np.random.RandomState(0); show_idx = set(rng.choice(len(va), min(a.show, len(va)), replace=False).tolist())
    for i in range(len(va)):
        x, _ = ds[i]; _, m = full[i]
        with torch.no_grad():
            pr = torch.softmax(net(x.unsqueeze(0).to(dev)), 1)[:, 1:2]
            pr = torch.nn.functional.interpolate(pr, size=tuple(m.shape), mode="bilinear", align_corners=False)[0, 0].cpu().numpy()
        m = m.numpy(); pred = pr >= a.thr
        gt = m == 1; valid = m != 255
        near_gt = ndimage.binary_dilation(gt, iterations=TOL); near_pr = ndimage.binary_dilation(pred, iterations=TOL)
        pv = pred & valid
        st += [(pv & near_gt).sum(), pv.sum(), (gt & near_pr).sum(), gt.sum()]
        if i in show_idx:
            img = np.asarray(Image.open(os.path.join(a.data, "img", va[i]["name"] + ".jpg")).convert("RGB").resize((m.shape[1], m.shape[0]))).copy()
            img[gt] = [255, 40, 40]; img[pred] = [40, 255, 40]; img[pred & gt] = [255, 255, 0]
            img[~valid] = (img[~valid] * 0.45).astype(np.uint8)
            Image.fromarray(img).save(os.path.join(a.out, va[i]["name"] + ".jpg")); shown += 1
    p = st[0] / max(1, st[1]); r = st[2] / max(1, st[3]); f = 2 * p * r / max(1e-9, p + r)
    res = dict(n=len(va), tol_px=TOL, precision=round(p, 3), recall=round(r, 3), f1=round(f, 3))
    json.dump(res, open(os.path.join(a.out, "metrics.json"), "w"))
    print(res)


if __name__ == "__main__":
    main()
