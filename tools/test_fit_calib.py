#!/usr/bin/env python3
"""
fit_calib.py 的合成端到端驗證（一般 python 腳本，不需要 pytest）：

  1. 程序化地面紋理（柏油雜訊、停車格線、黃線、路緣、地磚、油漬、人孔蓋）
  2. 以「真值」參數（= 預設值 + 實際安裝會有的偏差）渲染 1920x1080 原始 2x2 畫面：
     2x2 超取樣、車身遮擋、天空、各鏡頭曝光/白平衡、暗角、鏡頭模糊、雜訊、JPEG
  3. 以真值參數渲染「全景360」俯視圖（原廠自己的對角接縫 + 漸變融合、亮度補償、色調曲線），
     放進 1280x480 假截圖的左半邊，加車子圖示、黑色無畫面角落、UI 雜物（可選倒車導引線）
  4. 從預設值開始跑 fit_calib.py（CLI），比對每個參數與地面重投影誤差（車身外 3 m 內）

用法：
    python3 tools/test_fit_calib.py [--seeds 1,2,3] [--out DIR] [--guides] [--keep-going]
目標：每個 seed 平均地面誤差 < 5 cm，最差鏡頭（平均）< 10 cm；全部通過時結束碼 0。
"""
import argparse
import json
import math
import os
import subprocess
import sys
import tempfile
import time

import numpy as np
from scipy import ndimage

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import avm_geometry as ag  # noqa: E402
import fit_calib as fc  # noqa: E402

DEG = math.pi / 180.0
FRAME_W, FRAME_H = 1920, 1080
SHOT_W, SHOT_H = 1280, 480
ROI = (0.1, 0.0, 0.4, 1.0)      # 截圖左半邊中間 384x480 px
WM = 8.0                         # → 8 m x 10 m，車身外左右 3.08 m、前後 2.58 m

# 真值偏差範圍（相對預設值，均勻分布）
PERTURB = {"pitch": 10.0, "roll": 5.0, "yaw": 5.0, "py": 0.15, "f": 0.10, "cx": 0.03, "cy": 0.03,
           "px": 0.05, "pz": 0.05}


# ---------------------------------------------------------------- 場景

def _smooth(t):
    return t * t * (3.0 - 2.0 * t)


class Scene:
    """地面紋理：輸入地面座標 (gx, gz)（m），輸出 RGB float32（0..255）。"""

    def __init__(self, seed, plain=False):
        """plain=True：只有很淡的柏油雜訊、沒有油漬/碎石，主要靠標線、路緣、地磚對位（較接近真實停車場）。"""
        self.plain = plain
        rng = np.random.default_rng(1000 + seed)
        self.tabs = [rng.random((256, 256)).astype(np.float32) for _ in range(7)]
        self.rot = rng.uniform(-6.0, 6.0) * DEG
        self.off = rng.uniform(-0.4, 0.4, 2)
        self.stall_w = rng.uniform(2.5, 2.9)
        self.stall_off = rng.uniform(-0.3, 0.3)
        self.head_b = rng.uniform(2.6, 3.1)
        self.curb_b = -rng.uniform(3.0, 3.6)
        self.red_curb = bool(rng.random() < 0.5)
        self.tile = rng.uniform(0.22, 0.4)
        self.tile_tab = rng.random((64, 64)).astype(np.float32)
        self.lane_b = rng.uniform(4.3, 5.0)
        ang = rng.uniform(0, 2 * math.pi)
        rad = rng.uniform(2.2, 3.2)
        self.manhole = (rad * math.cos(ang), rad * math.sin(ang), rng.uniform(0.28, 0.36))
        self.base = rng.uniform(85, 110)

    def _noise(self, a, b, k, cell):
        t = self.tabs[k]
        x = a / cell
        y = b / cell
        i0 = np.floor(x)
        j0 = np.floor(y)
        fx = _smooth(x - i0)
        fy = _smooth(y - j0)
        i0 = i0.astype(np.int64) & 255
        j0 = j0.astype(np.int64) & 255
        i1 = (i0 + 1) & 255
        j1 = (j0 + 1) & 255
        top = t[j0, i0] + (t[j0, i1] - t[j0, i0]) * fx
        bot = t[j1, i0] + (t[j1, i1] - t[j1, i0]) * fx
        return top + (bot - top) * fy - 0.5

    def color(self, gx, gz):
        gx = np.asarray(gx, dtype=np.float64)
        gz = np.asarray(gz, dtype=np.float64)
        c, s = math.cos(self.rot), math.sin(self.rot)
        x0 = gx - self.off[0]
        z0 = gz - self.off[1]
        a = c * x0 + s * z0          # 場景座標：a 橫向、b 縱向
        b = -s * x0 + c * z0
        amp = 0.25 if self.plain else 1.0
        Y = (self.base + amp * (40 * self._noise(a, b, 0, 2.3) + 26 * self._noise(a, b, 1, 0.7)
                                + 18 * self._noise(a, b, 2, 0.21) + 12 * self._noise(a, b, 3, 0.06)))
        if not self.plain:
            oil = self._noise(a + 31.0, b - 17.0, 4, 0.45)
            Y = np.where(oil > 0.28, Y * 0.62, Y)
            grav = self._noise(a - 7.0, b + 3.0, 5, 0.09)
            Y = np.where(grav > 0.38, Y + 55, Y)
        rgb = np.stack((Y * 0.97, Y, Y * 1.05), axis=-1)

        def paint(mask, col):
            rgb[mask] = col

        # 停車格線（白）、格子前端橫線
        p = (a - self.stall_off) / self.stall_w - 0.5
        on_stall = (np.abs(p - np.round(p)) * self.stall_w < 0.06) & (b > self.curb_b + 0.25) & (b < self.head_b)
        paint(on_stall, (232, 232, 226))
        paint((np.abs(b - self.head_b) < 0.06) & (np.abs(a) < 9), (232, 232, 226))
        # 走道雙黃線、更遠的白虛線
        paint((np.abs(b - self.lane_b) < 0.05) | (np.abs(b - self.lane_b - 0.2) < 0.05), (226, 186, 40))
        paint((np.abs(b - self.lane_b - 2.2) < 0.06) & (np.mod(a, 4.0) < 2.0), (232, 232, 226))
        # 路緣（紅或水泥）與人行道地磚
        curb = (b <= self.curb_b) & (b > self.curb_b - 0.18)
        cn = 160 + 30 * self._noise(a, b, 6, 0.05)
        if self.red_curb:
            rgb[curb] = np.stack((cn * 1.15, cn * 0.25, cn * 0.25), axis=-1)[curb]
        else:
            rgb[curb] = np.stack((cn, cn, cn * 0.97), axis=-1)[curb]
        side = b <= self.curb_b - 0.18
        tb = (self.curb_b - 0.18 - b) / self.tile
        row = np.floor(tb)
        ta = a / (2 * self.tile) + 0.5 * (row.astype(np.int64) & 1)
        col = np.floor(ta)
        tv = self.tile_tab[row.astype(np.int64) & 63, col.astype(np.int64) & 63]
        grout = (np.minimum(tb - row, 1 - (tb - row)) * self.tile < 0.012) | \
                (np.minimum(ta - col, 1 - (ta - col)) * 2 * self.tile < 0.012)
        tY = np.where(grout, 55.0, 115.0 + 70.0 * tv) + 10 * self._noise(a, b, 3, 0.03)
        rgb[side] = np.stack((tY * 1.04, tY * 0.98, tY * 0.92), axis=-1)[side]
        # 人孔蓋
        mx, mz, mr = self.manhole
        d = np.hypot(gx - mx, gz - mz)
        lid = d < mr
        grid = (np.mod((gx - mx) * 12.0, 1.0) < 0.35) ^ (np.mod((gz - mz) * 12.0, 1.0) < 0.35)
        rgb[lid] = np.where(grid[lid, None], 60.0, 95.0)
        paint((d >= mr) & (d < mr + 0.04), (150, 150, 150))
        # 遠處淡出（避免地平線附近嚴重鋸齒）
        far = np.clip((np.hypot(gx, gz) - 14.0) / 10.0, 0.0, 1.0)[..., None]
        rgb = rgb * (1 - far) + np.array([118.0, 120.0, 124.0]) * far
        return rgb.astype(np.float32)


# ---------------------------------------------------------------- 真值參數

def perturbed_calib(start, seed, lens_mismatch=False):
    rng = np.random.default_rng(seed)
    cal = json.loads(json.dumps(start))
    for name in sorted(cal["cams"]):
        d = cal["cams"][name]
        u = {k: rng.uniform(-1, 1) for k in sorted(PERTURB)}
        d["pitch"] = d["pitch"] + PERTURB["pitch"] * u["pitch"]
        d["roll"] = d["roll"] + PERTURB["roll"] * u["roll"]
        d["yaw"] = d["yaw"] + PERTURB["yaw"] * u["yaw"]
        d["pos"] = [d["pos"][0] + PERTURB["px"] * u["px"], d["pos"][1] + PERTURB["py"] * u["py"],
                    d["pos"][2] + PERTURB["pz"] * u["pz"]]
        f = 1.0 + PERTURB["f"] * u["f"]
        d["fx"] = d["fx"] * f
        d["fy"] = d["fy"] * f
        d["cx"] = d["cx"] + PERTURB["cx"] * u["cx"]
        d["cy"] = d["cy"] + PERTURB["cy"] * u["cy"]
    if lens_mismatch:
        # 真實鏡頭不是純等距投影：真值加 k1/k2（擬合模型預設 k1=k2=0）
        rng2 = np.random.default_rng(seed + 50)
        for name in sorted(cal["cams"]):
            cal["cams"][name]["k1"] = float(rng2.uniform(-0.04, 0.04))
            cal["cams"][name]["k2"] = float(rng2.uniform(-0.004, 0.004))
    return cal


def stock_calib(truth, seed):
    """原廠校正也不是完美的：角度 σ 0.25°、位置 σ 1 cm、焦距 σ 0.5%。"""
    rng = np.random.default_rng(seed + 300)
    cal = json.loads(json.dumps(truth))
    for name in sorted(cal["cams"]):
        d = cal["cams"][name]
        for k in ("pitch", "roll", "yaw"):
            d[k] += float(rng.normal(0, 0.25))
        d["pos"] = [p + float(rng.normal(0, 0.01)) for p in d["pos"]]
        f = 1.0 + float(rng.normal(0, 0.005))
        d["fx"] *= f
        d["fy"] *= f
    return cal


# ---------------------------------------------------------------- 原始畫面渲染

BODY = (0.88, 0.25, 1.40, 2.25)   # 車身遮擋盒：|x|≤0.88、0.25≤y≤1.40、|z|≤2.25（鏡頭裝在盒外）


def _ray_box(px, py, pz, rx, ry, rz):
    hx, y0, y1, hz = BODY
    tmin = np.full(rx.shape, -np.inf)
    tmax = np.full(rx.shape, np.inf)
    for o, d, lo, hi in ((px, rx, -hx, hx), (py, ry, y0, y1), (pz, rz, -hz, hz)):
        with np.errstate(divide="ignore", invalid="ignore"):
            t1 = (lo - o) / d
            t2 = (hi - o) / d
        a = np.minimum(t1, t2)
        b = np.maximum(t1, t2)
        par = np.abs(d) < 1e-12
        inside = (o >= lo) & (o <= hi)
        a = np.where(par, np.where(inside, -np.inf, np.inf), a)
        b = np.where(par, np.where(inside, np.inf, -np.inf), b)
        tmin = np.maximum(tmin, a)
        tmax = np.minimum(tmax, b)
    hit = (tmax >= tmin) & (tmax > 0)
    return np.where(hit, np.maximum(tmin, 0.0), np.inf)


def render_raw(geo, scene, seed, ss=2, size=(FRAME_W, FRAME_H)):
    """seed 決定曝光/白平衡/雜訊；size 可以跟 1920x1080 不同（fx/fy 以象限寬為單位，幾何不變）。"""
    rng = np.random.default_rng(seed)
    fw, fh = size
    qw, qh = fw // 2, fh // 2
    frame = np.zeros((fh, fw, 3), dtype=np.float32)
    phot = {}
    for cam in geo.cams:
        acc = np.zeros((qh, qw, 3), dtype=np.float32)
        for oy in range(ss):
            for ox in range(ss):
                uu, vv = np.meshgrid(np.arange(qw) + (ox + 0.5) / ss, np.arange(qh) + (oy + 0.5) / ss)
                rx, ry, rz = ag.pixel_to_ray(cam, uu, vv, qw, qh)
                hit = ry < -1e-6
                t = np.where(hit, -cam.py / np.where(hit, ry, -1.0), np.inf)
                gx = np.where(hit, cam.px + t * rx, 0.0)
                gz = np.where(hit, cam.pz + t * rz, 0.0)
                col = scene.color(gx, gz)
                sky = np.stack((150 + 60 * ry, 175 + 50 * ry, 215 + 20 * ry), axis=-1).astype(np.float32)
                col = np.where(hit[..., None], col, sky)
                tb = _ray_box(cam.px, cam.py, cam.pz, rx, ry, rz)
                body = tb < t
                shade = (40 + 25 * ry + 10 * rz).astype(np.float32)
                bcol = np.stack((shade * 0.8, shade * 0.85, shade * 1.0), axis=-1)
                col = np.where(body[..., None], bcol, col)
                acc += col
        acc /= ss * ss
        gain = rng.uniform(0.8, 1.2)
        wb = rng.uniform(0.92, 1.08, 3)
        uu, vv = np.meshgrid(np.arange(qw) + 0.5, np.arange(qh) + 0.5)
        dx = (uu - cam.cx * qw) / (cam.fx * qw)
        dy = (vv - cam.cy * qh) / (cam.fy * qw)
        th = np.sqrt(dx * dx + dy * dy)
        vig = (1.0 - 0.32 * (th / (95 * DEG)) ** 2).clip(0.4, 1.0)[..., None]
        img = acc * gain * wb * vig
        img = 255.0 * (np.clip(img, 0, 400) / 255.0) ** 0.92
        img = ndimage.gaussian_filter(img, (0.7, 0.7, 0))
        img += rng.normal(0.0, 2.5, img.shape)
        qx0, qy0 = (cam.quad % 2) * qw, (cam.quad // 2) * qh
        frame[qy0:qy0 + qh, qx0:qx0 + qw] = img
        phot[cam.name] = gain
    return np.clip(frame, 0, 255).astype(np.uint8), phot


def jpeg_roundtrip(rgb, path, quality=90):
    from PIL import Image
    Image.fromarray(rgb).save(path, quality=quality)
    return np.asarray(Image.open(path).convert("RGB"))


# ---------------------------------------------------------------- 原廠俯視圖 + 假截圖

def render_stock_bev(raw, geo, w, h, wm, phot, seed):
    """原廠的做法：對角接縫（從車角 45°）+ 0.6 m 漸變融合，各鏡頭亮度補償，再套色調曲線。
    raw 是原廠自己拿到的畫面（解析度、雜訊、曝光可以跟我們的 RAW 不同）。"""
    rng = np.random.default_rng(700 + seed)
    qw, qh = raw.shape[1] // 2, raw.shape[0] // 2
    mpp = wm / w
    gx = (np.arange(w) + 0.5 - w / 2.0) * mpp
    gz = (h / 2.0 - np.arange(h) - 0.5) * mpp
    GX, GZ = np.meshgrid(gx, gz)
    hw, hl = geo.car_width / 2, geo.car_length / 2
    dx = np.abs(GX) - hw
    dz = np.abs(GZ) - hl
    w_long = np.clip(0.5 + (dz - dx) / 0.6, 0.0, 1.0)
    want = {"front": w_long * (GZ > 0), "rear": w_long * (GZ <= 0),
            "left": (1 - w_long) * (GX < 0), "right": (1 - w_long) * (GX >= 0)}
    acc = np.zeros(GX.shape + (3,))
    wsum = np.zeros(GX.shape)
    for cam in geo.cams:
        u, v, th, ok, _ = ag.ground_to_pixel(cam, GX, GZ, qw, qh)
        wt = want[cam.name] * ok
        if not wt.any():
            continue
        qx0, qy0 = (cam.quad % 2) * qw, (cam.quad // 2) * qh
        rc = np.stack((np.where(ok, v, 0) - 0.5 + qy0, np.where(ok, u, 0) - 0.5 + qx0))
        comp = rng.uniform(0.88, 1.12) / phot[cam.name]
        for ch in range(3):
            s = ndimage.map_coordinates(raw[..., ch].astype(np.float32), rc, order=1, mode="nearest")
            acc[..., ch] += wt * s * comp
        wsum += wt
    # 想要的鏡頭看不到時，退回任何看得到的鏡頭
    miss = wsum < 1e-6
    if miss.any():
        for cam in geo.cams:
            u, v, th, ok, _ = ag.ground_to_pixel(cam, GX, GZ, qw, qh)
            sel = miss & ok & (wsum < 1e-6)
            if not sel.any():
                continue
            qx0, qy0 = (cam.quad % 2) * qw, (cam.quad // 2) * qh
            rc = np.stack((v[sel] - 0.5 + qy0, u[sel] - 0.5 + qx0))
            comp = 1.0 / phot[cam.name]
            for ch in range(3):
                acc[..., ch][sel] = ndimage.map_coordinates(raw[..., ch].astype(np.float32), rc,
                                                            order=1, mode="nearest") * comp
            wsum[sel] = 1.0
    img = acc / np.maximum(wsum, 1e-6)[..., None]
    img = 255.0 * (np.clip(img, 0, 255) / 255.0) ** 0.85
    m = img.mean(axis=2, keepdims=True)
    img = m + 1.2 * (img - m)                      # 飽和度
    img = (img - 128) * 1.08 + 136                  # 對比、亮度
    img[wsum < 1e-6] = 0
    # 黑色無畫面角落
    corner = np.abs(GX) / (wm / 2) + np.abs(GZ) / (h * mpp / 2) > 1.72
    img[corner] = 0
    return np.clip(img, 0, 255), GX, GZ


def draw_car_icon(img, GX, GZ, hw, hl):
    m = 0.12
    body = (np.abs(GX) <= hw + m - 0.02) & (np.abs(GZ) <= hl + m)
    rnd = ((np.abs(GX) - (hw + m - 0.3)) ** 2 + (np.abs(GZ) - (hl + m - 0.3)) ** 2 > 0.3 ** 2) & \
          (np.abs(GX) > hw + m - 0.3) & (np.abs(GZ) > hl + m - 0.3)
    body &= ~rnd
    img[body] = (45, 75, 150)
    img[body & (np.abs(GX) < hw - 0.15) & (np.abs(GZ) < 1.0)] = (80, 110, 185)
    img[body & (np.abs(GX) < hw - 0.12) & (GZ > 0.55) & (GZ < 1.3)] = (25, 30, 45)
    img[body & (np.abs(GX) < hw - 0.12) & (GZ < -1.1) & (GZ > -1.6)] = (25, 30, 45)
    img[body & (np.abs(GX) > hw - 0.25) & (GZ > hl + m - 0.12)] = (240, 230, 160)
    wheel = (np.abs(GX) > hw - 0.05) & (np.abs(GX) <= hw + m) & (np.abs(np.abs(GZ) - 1.45) < 0.33)
    img[wheel] = (10, 10, 10)


def draw_guides(img, GX, GZ):
    """倒車導引線（只在測 --guides 時畫；原始畫面不會有）。"""
    zz = -GZ - 2.5
    for sgn in (-1, 1):
        xg = sgn * (0.95 + 0.03 * np.clip(zz, 0, None) ** 2)
        line = (np.abs(GX - xg) < 0.04) & (zz > 0) & (zz < 2.8)
        img[line & (zz < 0.8)] = (230, 40, 30)
        img[line & (zz >= 0.8) & (zz < 1.8)] = (240, 210, 30)
        img[line & (zz >= 1.8)] = (40, 200, 60)
    for zt, col in ((0.5, (230, 40, 30)), (1.5, (240, 210, 30))):
        img[(np.abs(zz - zt) < 0.03) & (np.abs(GX) < 1.0)] = col


def make_screenshot(raw, geo, flip, phot, seed, guides=False):
    rng = np.random.default_rng(900 + seed)
    L, T = int(ROI[0] * SHOT_W), int(ROI[1] * SHOT_H)
    R, B = int(ROI[2] * SHOT_W), int(ROI[3] * SHOT_H)
    w, h = R - L, B - T
    bev, GX, GZ = render_stock_bev(raw, geo, w, h, WM, phot, seed)
    draw_car_icon(bev, GX, GZ, geo.car_width / 2, geo.car_length / 2)
    if guides:
        draw_guides(bev, GX, GZ)
    # 俯視圖內的半透明按鈕
    bev[h - 70:h - 22, w - 70:w - 22] = bev[h - 70:h - 22, w - 70:w - 22] * 0.4 + 255 * 0.6
    bev[h - 52:h - 40, w - 60:w - 32] = (40, 40, 40)
    if flip:
        bev = bev[::-1, ::-1]
    shot = np.zeros((SHOT_H, SHOT_W, 3), dtype=np.float32)
    shot[:] = (22, 24, 30)
    shot[T:B, L:R] = bev
    # 左右兩側按鈕列、右半邊單鏡頭畫面與標題列
    for k in range(5):
        y0 = 30 + 90 * k
        shot[y0:y0 + 60, 24:104] = (60, 64, 75)
        shot[y0 + 22:y0 + 38, 36:36 + int(rng.integers(30, 60))] = (220, 220, 220)
        shot[y0:y0 + 60, 536:616] = (60, 64, 75)
    step = max(1, raw.shape[1] // 640)
    cam_view = raw[:raw.shape[0] // 2, :raw.shape[1] // 2][::step, ::step][:, :, :3]
    ch, cw = min(cam_view.shape[0], 340), min(cam_view.shape[1], 600)
    shot[100:100 + ch, 660:660 + cw] = cam_view[:ch, :cw]
    shot[20:70, 640:1280] = (35, 38, 48)
    for k in range(6):
        x0 = 660 + 95 * k
        shot[35:55, x0:x0 + int(rng.integers(40, 80))] = (200, 200, 205)
    return np.clip(shot, 0, 255).astype(np.uint8)


# ---------------------------------------------------------------- 評估

def ground_error(true_calib, fit_calib, radius=3.0, step=0.04):
    """車身外 radius 內的地面點：真值鏡頭 ground→pixel，再用擬合參數 pixel→ground，量距離。
    主要統計以真值 LUT 的歸屬（θ 最小的鏡頭）分；另給「看得到就算」的版本。"""
    gt = ag.Geometry(true_calib)
    gf = ag.Geometry(fit_calib)
    qw, qh = FRAME_W // 2, FRAME_H // 2
    hw, hl = gt.car_width / 2, gt.car_length / 2
    xs = np.arange(-hw - radius, hw + radius + 1e-9, step)
    zs = np.arange(-hl - radius, hl + radius + 1e-9, step)
    GX, GZ = np.meshgrid(xs, zs)
    dist = np.hypot(np.maximum(np.abs(GX) - hw, 0), np.maximum(np.abs(GZ) - hl, 0))
    region = (dist <= radius) & ~gt.ego_mask(GX, GZ)
    best = np.full(GX.shape, np.inf)
    owner = np.full(GX.shape, -1)
    proj = {}
    for cam in gt.cams:
        u, v, th, ok, _ = ag.ground_to_pixel(cam, GX, GZ, qw, qh)
        proj[cam.name] = (u, v, ok)
        take = ok & region & (th < best)
        best[take] = th[take]
        owner[take] = cam.quad
    out = {"owner": {}, "visible": {}}
    all_own = []
    for cam in gt.cams:
        fcam = gf.cam(cam.name)
        u, v, ok = proj[cam.name]
        for key, sel in (("owner", region & (owner == cam.quad)), ("visible", region & ok)):
            if not sel.any():
                continue
            gx2, gz2, ok2, _ = ag.pixel_to_ground(fcam, u[sel], v[sel], qw, qh)
            e = np.hypot(np.where(ok2, gx2, 1e3) - GX[sel], np.where(ok2, gz2, 1e3) - GZ[sel])
            e = np.minimum(e, 10.0)
            # 反方向：同一地面點用擬合參數投到畫面，跟真值像素差多少
            uf, vf, _, okf, _ = ag.ground_to_pixel(fcam, GX[sel], GZ[sel], qw, qh)
            pe = np.hypot(uf - u[sel], vf - v[sel])
            out[key][cam.name] = {"n": int(sel.sum()), "mean_cm": 100 * float(e.mean()),
                                  "p95_cm": 100 * float(np.percentile(e, 95)),
                                  "max_cm": 100 * float(e.max()), "fail": int((~ok2).sum()),
                                  "px_mean": float(pe.mean()), "px_p95": float(np.percentile(pe, 95))}
            if key == "owner":
                all_own.append(e)
    allv = np.concatenate(all_own)
    out["mean_cm"] = 100 * float(allv.mean())
    out["p95_cm"] = 100 * float(np.percentile(allv, 95))
    out["worst_cam_mean_cm"] = max(r["mean_cm"] for r in out["owner"].values())
    return out


def param_errors(true_calib, fit_calib):
    rows = {}
    for name, t in true_calib["cams"].items():
        f = fit_calib["cams"][name]
        rows[name] = {"pitch": f["pitch"] - t["pitch"], "roll": f["roll"] - t["roll"],
                      "yaw": f["yaw"] - t["yaw"],
                      "px": f["pos"][0] - t["pos"][0], "py": f["pos"][1] - t["pos"][1],
                      "pz": f["pos"][2] - t["pos"][2],
                      "f%": 100 * (f["fx"] / t["fx"] - 1), "cx": f["cx"] - t["cx"], "cy": f["cy"] - t["cy"],
                      "k1": f.get("k1", 0.0) - t.get("k1", 0.0)}
    return rows


def fmt_params(rows):
    hdr = "        %8s %7s %7s %7s %7s %7s %7s %7s %8s" % ("pitch°", "roll°", "yaw°", "px m", "py m",
                                                         "pz m", "f %", "cx", "cy")
    lines = [hdr]
    for n, r in rows.items():
        lines.append("  %-5s %8.2f %7.2f %7.2f %7.3f %7.3f %7.3f %7.2f %7.4f %8.4f"
                     % (n, r["pitch"], r["roll"], r["yaw"], r["px"], r["py"], r["pz"], r["f%"], r["cx"], r["cy"]))
    return "\n".join(lines)


def fmt_ground(ev):
    lines = ["        %8s %8s %8s %8s %8s  (車身外 3 m 內，依真值 LUT 歸屬)" % ("mean cm", "p95 cm", "max cm",
                                                                       "px mean", "px p95")]
    for n, r in ev["owner"].items():
        lines.append("  %-5s %8.2f %8.2f %8.2f %8.2f %8.2f" % (n, r["mean_cm"], r["p95_cm"], r["max_cm"],
                                                           r["px_mean"], r["px_p95"]))
    lines.append("  全部  %8.2f %8.2f   最差鏡頭平均 %.2f cm" % (ev["mean_cm"], ev["p95_cm"], ev["worst_cam_mean_cm"]))
    vis = " ".join("%s %.2f" % (n, r["mean_cm"]) for n, r in ev["visible"].items())
    lines.append("  （每顆鏡頭看得到的全部點，平均 cm：%s）" % vis)
    return "\n".join(lines)


# ---------------------------------------------------------------- 主程式

def check_projection(start):
    """fit_calib.project 必須跟 avm_geometry.ground_to_pixel 一致。"""
    geo = ag.Geometry(ag.VARIANT_CALIB)
    rng = np.random.default_rng(0)
    gx = rng.uniform(-8, 8, 5000)
    gz = rng.uniform(-9, 9, 5000)
    worst = 0.0
    for cam in geo.cams:
        u1, v1, t1 = fc.project(cam, gx, gz, 960, 540)
        u2, v2, t2, _, _ = ag.ground_to_pixel(cam, gx, gz, 960, 540)
        m = t2 < math.pi - 1e-3
        worst = max(worst, float(np.max(np.abs(u1 - u2)[m])), float(np.max(np.abs(v1 - v2)[m])),
                    float(np.max(np.abs(t1 - t2))))
    return worst


def run_seed(seed, start_path, out_dir, guides=False, flip=None, lens_mismatch=False, stock_err=False,
             fit_args=(), same_frame=False, tag="", plain=False):
    start = json.load(open(start_path, encoding="utf-8"))
    d = os.path.join(out_dir, "seed%d%s" % (seed, tag))
    os.makedirs(d, exist_ok=True)
    truth = perturbed_calib(start, seed, lens_mismatch=lens_mismatch)
    stock = stock_calib(truth, seed) if stock_err else truth
    with open(os.path.join(d, "true_calib.json"), "w", encoding="utf-8") as f:
        f.write(fc.format_calib(truth))
    with open(os.path.join(d, "stock_calib.json"), "w", encoding="utf-8") as f:
        f.write(fc.format_calib(stock))
    geo = ag.Geometry(truth)
    flip = (seed % 2 == 0) if flip is None else flip
    t0 = time.time()
    scene = Scene(seed, plain=plain)
    raw, phot = render_raw(geo, scene, 500 + seed)
    raw_path = os.path.join(d, "raw.jpg")
    raw_dec = jpeg_roundtrip(raw, raw_path)
    if same_frame:
        stock_raw, stock_phot = raw_dec, phot
    else:
        # 原廠主機自己的擷取：另一個時間點（雜訊、曝光不同）、每路 1280x720、沒有 JPEG
        stock_raw, stock_phot = render_raw(geo, scene, 600 + seed, size=(2560, 1440))
    shot = make_screenshot(stock_raw, ag.Geometry(stock), flip, stock_phot, seed, guides=guides)
    shot_path = os.path.join(d, "screen.png")
    fc.save_rgb(shot_path, shot)
    t_render = time.time() - t0
    out_path = os.path.join(d, "fitted.json")
    cmd = [sys.executable, os.path.join(HERE, "fit_calib.py"), raw_path, shot_path,
           "--roi", ",".join("%g" % v for v in ROI), "--wm", "%g" % WM, "--calib", start_path,
           "-o", out_path, "--debug-dir", os.path.join(d, "debug")] + (["--flip"] if flip else []) \
        + list(fit_args)
    t1 = time.time()
    pr = subprocess.run(cmd, capture_output=True, text=True)
    t_fit = time.time() - t1
    log = pr.stdout + pr.stderr
    with open(os.path.join(d, "fit_log.txt"), "w", encoding="utf-8") as f:
        f.write(" ".join(cmd) + "\n" + log)
    if pr.returncode != 0:
        print(log)
        raise RuntimeError("fit_calib.py 失敗（seed %d）" % seed)
    fitted = json.load(open(out_path, encoding="utf-8"))
    ev0 = ground_error(truth, start)
    ev = ground_error(truth, fitted)
    ev_stock = ground_error(truth, stock) if stock_err else None
    ev_vs_stock = ground_error(stock, fitted) if stock_err else None
    rep = json.load(open(os.path.join(d, "debug", "fit_report.json"), encoding="utf-8"))
    return {"seed": seed, "flip": flip, "guides": guides, "lens": lens_mismatch, "stock_err": stock_err,
            "plain": plain, "render_s": t_render, "fit_s": t_fit,
            "start": ev0, "fit": ev, "stock": ev_stock, "vs_stock": ev_vs_stock,
            "params_start": param_errors(truth, start),
            "params_fit": param_errors(truth, fitted), "ncc": {n: r["ncc"] for n, r in rep["cams"].items()},
            "log": log, "dir": d}


def main(argv=None):
    ap = argparse.ArgumentParser(description="fit_calib.py 合成端到端驗證")
    ap.add_argument("--seeds", default="1,2,3")
    ap.add_argument("--out", help="輸出資料夾（預設暫存資料夾）")
    ap.add_argument("--calib", default=ag.DEFAULT_CALIB)
    ap.add_argument("--guides", action="store_true", help="截圖加倒車導引線（測干擾）")
    ap.add_argument("--flip", choices=["auto", "on", "off"], default="auto", help="auto = 偶數 seed 翻轉")
    ap.add_argument("--lens-mismatch", action="store_true", help="真值鏡頭加 k1/k2（模型不完全吻合）")
    ap.add_argument("--stock-error", action="store_true", help="原廠俯視圖本身帶小誤差（0.25°、1 cm）")
    ap.add_argument("--same-frame", action="store_true",
                    help="原廠俯視圖直接用同一張 RAW 渲染（較理想化；預設用另一次 1280x720/路 的擷取）")
    ap.add_argument("--plain", action="store_true", help="低紋理地面：淡柏油、沒有油漬碎石，只靠標線/路緣/地磚")
    ap.add_argument("--fit-k1", action="store_true", help="傳給 fit_calib.py --fit-k1")
    ap.add_argument("--tag", default="", help="輸出子資料夾後綴")
    ap.add_argument("-v", "--verbose", action="store_true", help="印出 fit_calib 的過程")
    a = ap.parse_args(argv)
    out = a.out or tempfile.mkdtemp(prefix="fit_calib_test_")
    seeds = [int(t) for t in a.seeds.split(",") if t.strip()]
    worst = check_projection(None)
    print("project() 與 avm_geometry.ground_to_pixel 最大差 %.2e（需 < 1e-9）" % worst)
    ok_all = worst < 1e-9
    summary = []
    fit_args = ["--fit-k1"] if a.fit_k1 else []
    for seed in seeds:
        flip = None if a.flip == "auto" else (a.flip == "on")
        r = run_seed(seed, a.calib, out, guides=a.guides, flip=flip, lens_mismatch=a.lens_mismatch,
                     stock_err=a.stock_error, fit_args=fit_args, same_frame=a.same_frame, tag=a.tag,
                     plain=a.plain)
        if a.verbose:
            print(r["log"])
        ev = r["fit"]
        passed = ev["mean_cm"] < 5.0 and ev["worst_cam_mean_cm"] < 10.0
        ok_all &= passed
        print("\n=== seed %d（flip=%s guides=%s lens_mismatch=%s stock_error=%s plain=%s fit_k1=%s）"
              "渲染 %.1f s，擬合 %.1f s → %s ==="
              % (seed, r["flip"], r["guides"], r["lens"], r["stock_err"], r["plain"], a.fit_k1, r["render_s"],
                 r["fit_s"], "通過" if passed else "未通過"))
        print("起始值（預設）的參數誤差（擬合值 − 真值）：")
        print(fmt_params(r["params_start"]))
        print("擬合後的參數誤差：")
        print(fmt_params(r["params_fit"]))
        print("起始值地面誤差：")
        print(fmt_ground(r["start"]))
        print("擬合後地面誤差：")
        print(fmt_ground(ev))
        if r["stock"] is not None:
            print("（對照）原廠校正本身相對真值的地面誤差：平均 %.2f cm，最差鏡頭 %.2f cm"
                  % (r["stock"]["mean_cm"], r["stock"]["worst_cam_mean_cm"]))
            print("（對照）擬合結果相對原廠參數的地面誤差：平均 %.2f cm，最差鏡頭 %.2f cm"
                  % (r["vs_stock"]["mean_cm"], r["vs_stock"]["worst_cam_mean_cm"]))
        print("NCC：%s   輸出：%s" % (" ".join("%s %.3f" % kv for kv in r["ncc"].items()), r["dir"]))
        summary.append((seed, ev["mean_cm"], ev["worst_cam_mean_cm"], ev["p95_cm"], r["fit_s"], passed))
    print("\n總結：")
    for s, m, w, p, t, ps in summary:
        print("  seed %-3d 平均 %5.2f cm  最差鏡頭 %5.2f cm  p95 %5.2f cm  擬合 %5.1f s  %s"
              % (s, m, w, p, t, "通過" if ps else "未通過"))
    print("全部通過" if ok_all else "有未通過的項目")
    return 0 if ok_all else 1


if __name__ == "__main__":
    sys.exit(main())
