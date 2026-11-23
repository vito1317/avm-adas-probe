#!/usr/bin/env python3
"""
離線試 openpilot 的駕駛模型（driving_supercombo.onnx，MIT）在我們前鏡頭上認車道線的效果。只看辨識，不做任何控制。

做法：
  1. 視角轉換：openpilot 要的是擋風玻璃後、約 1.2 m 高、一般鏡頭的畫面（窄角 fl=910、寬角 fl=455，512x256）。
     我們的前鏡頭是保險桿高度的魚眼 → 對 openpilot 畫面的每個像素算它看到的「路面點」（假設是平的地面，
     虛擬鏡頭放在前鏡頭正上方 [CAM_H] 公尺），再到魚眼上取色；地平線以上用方向近似。路面會是對的，車子等立體物會被拉長。
  2. 轉成模型要的 YUV420 6 通道（4 個 Y 子取樣＋U＋V，128x256），窄角、寬角各一張。
  3. 我們的影格約 1 秒一張（模型要 20 Hz 連續影像）→ 同一張重複餵幾次讓內部狀態穩定，只看車道線、路邊。
  4. 把它認到的 4 條車道線、2 條路邊畫回 openpilot 畫面，另存圖。

用法：~/avm-data/.venv312/bin/python tools/openpilot/op_offline.py --frame ~/avm-data/train/<session>/f_01200.jpg --out out.jpg
模型檔：~/avm-data/openpilot/driving_supercombo.onnx（從 openpilot 的 LFS 下載，不進 repo）。
"""
import argparse, json, math, os, sys
import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, os.path.dirname(HERE)); sys.path.insert(0, os.path.join(os.path.dirname(HERE), "lane_label"))
import avm_geometry as ag                   # noqa: E402
from ufld_teacher import cam_to_fisheye     # noqa: E402
from candidates import load_calib           # noqa: E402

MODEL = os.path.expanduser("~/avm-data/openpilot/driving_supercombo.onnx")
CAM_H = 1.22
QW, QH = 960, 540
IDX_N = 33
X_IDXS = np.array([192.0 * (i / 32) ** 2 for i in range(IDX_N)])
MED = np.array([[910.0, 0, 256.0], [0, 910.0, 47.6], [0, 0, 1]])
SBIG = np.array([[455.0, 0, 256.0], [0, 455.0, 0.5 * (256 + 47.6)], [0, 0, 1]])


def render(frame, geo, K, W=512, H=256):
    """openpilot 視角（內參 K）→ RGB 影像：每個像素的光線 → 地面點（或遠方方向）→ 前魚眼取色。"""
    cam = [c for c in geo.cams if c.quad == 0][0]
    Ki = np.linalg.inv(K)
    us, vs = np.meshgrid(np.arange(W) + 0.5, np.arange(H) + 0.5)
    pv = np.stack([us, vs, np.ones_like(us)], -1) @ Ki.T            # view 座標：x 右、y 下、z 前
    rx, ry, rz = pv[..., 2], pv[..., 0], pv[..., 1]                  # calib：x 前、y 右、z 下
    down = rz > 1e-3
    t = np.where(down, CAM_H / np.where(down, rz, 1), 1000.0)
    t = np.minimum(t, 1000.0)
    # 車身座標（X 右、Y 上、Z 前）：虛擬鏡頭在前鏡頭正上方 CAM_H
    X = cam.px + ry * t; Z = cam.pz + rx * t
    Y = np.where(down & (t < 999), 0.0, CAM_H - rz * t)
    u, v, ok = cam_to_fisheye(cam, X.ravel(), Y.ravel(), Z.ravel(), QW, QH)
    u = np.nan_to_num(u).reshape(H, W); v = np.nan_to_num(v).reshape(H, W); ok = ok.reshape(H, W)
    ox, oy = (cam.quad % 2) * QW, (cam.quad // 2) * QH
    ui = np.clip(u.astype(int), 0, QW - 1) + ox; vi = np.clip(v.astype(int), 0, QH - 1) + oy
    img = frame[vi, ui].copy(); img[~ok] = 0
    return img


def yuv6(rgb):
    """RGB(256x512) → I420 → 6x128x256（openpilot 的 frames_to_tensor 排法）。"""
    r, g, b = [rgb[..., i].astype(np.float32) for i in range(3)]
    Y = 0.257 * r + 0.504 * g + 0.098 * b + 16
    U = -0.148 * r - 0.291 * g + 0.439 * b + 128
    V = 0.439 * r - 0.368 * g - 0.071 * b + 128
    H, W = Y.shape
    Us = U.reshape(H // 2, 2, W // 2, 2).mean((1, 3)); Vs = V.reshape(H // 2, 2, W // 2, 2).mean((1, 3))
    out = np.zeros((6, H // 2, W // 2), np.float32)
    out[0] = Y[0::2, 0::2]; out[1] = Y[1::2, 0::2]; out[2] = Y[0::2, 1::2]; out[3] = Y[1::2, 1::2]
    out[4] = Us; out[5] = Vs
    return np.clip(out, 0, 255).astype(np.uint8)


def run(sess, med, big, n=12):
    feeds = {"desire": np.zeros(8, np.float32), "traffic_convention": np.array([[1, 0]], np.float32),   # 台灣：靠右行駛（左駕）
             "action_t": np.array([[0.25, 0.3]], np.float32),
             "state_img_q": np.zeros((2, 5, 6, 128, 256), np.uint8), "state_desire_q": np.zeros((132, 1, 8), np.float32),
             "state_feat_q": np.zeros((128, 1, 512), np.float32)}
    img = np.stack([yuv6(med), yuv6(big)])
    feeds["state_img_q"][:] = img[:, None]          # 過去 5 張也用同一張（我們沒有 20 Hz 連續影像）
    out = None
    for _ in range(n):
        feeds["new_img"] = img
        o = sess.run(None, feeds)
        out = o[0][0]
        feeds["state_img_q"], feeds["state_desire_q"], feeds["state_feat_q"] = o[1], o[2], o[3]
    ll = out[0:528][:264].reshape(4, IDX_N, 2); lp = 1 / (1 + np.exp(-out[528:536][1::2]))
    re = out[536:800][:132].reshape(2, IDX_N, 2)
    return ll, lp, re


def draw(img, K, lines, probs, edges):
    im = Image.fromarray(img); d = ImageDraw.Draw(im)
    def proj(x, y, z):
        if x < 1: return None
        p = K @ np.array([y, z, x]); return (p[0] / p[2], p[1] / p[2])
    cols = [(255, 80, 80), (80, 255, 80), (80, 160, 255), (255, 220, 60)]
    for i in range(4):
        pts = [proj(X_IDXS[k], lines[i, k, 0], lines[i, k, 1]) for k in range(IDX_N) if X_IDXS[k] <= 60]
        pts = [p for p in pts if p]
        if len(pts) > 1: d.line(pts, fill=cols[i] if probs[i] > 0.3 else (90, 90, 90), width=3)
        if pts: d.text(pts[0], f"{probs[i]:.2f}", fill=cols[i])
    for i in range(2):
        pts = [proj(X_IDXS[k], edges[i, k, 0], edges[i, k, 1]) for k in range(IDX_N) if X_IDXS[k] <= 60]
        pts = [p for p in pts if p]
        if len(pts) > 1: d.line(pts, fill=(255, 0, 255), width=2)
    return im


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--frame", required=True); ap.add_argument("--out", required=True)
    a = ap.parse_args()
    import onnxruntime as ort
    sd = os.path.dirname(a.frame)
    geo = ag.Geometry(load_calib(sd))
    frame = np.asarray(Image.open(a.frame).convert("RGB"))
    med = render(frame, geo, MED); big = render(frame, geo, SBIG)
    sess = ort.InferenceSession(MODEL, providers=["CPUExecutionProvider"])
    import time; t0 = time.time()
    ll, lp, re = run(sess, med, big)
    dt = (time.time() - t0) / 12
    a1 = draw(med, MED, ll, lp, re); a2 = draw(big, SBIG, ll, lp, re)
    sheet = Image.new("RGB", (512, 512)); sheet.paste(a1, (0, 0)); sheet.paste(a2, (0, 256)); sheet.save(a.out, quality=92)
    print(json.dumps({"lane_probs": [round(float(p), 2) for p in lp], "lane_y_at_0m": [round(float(ll[i, 0, 0]), 2) for i in range(4)],
                      "edge_y_at_0m": [round(float(re[i, 0, 0]), 2) for i in range(2)], "ms_per_run_mac_cpu": round(dt * 1000)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
