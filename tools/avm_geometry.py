#!/usr/bin/env python3
"""
AVM 四路魚眼 → 地面幾何（與 app/src/main/java/com/vito/avmprobe/AvmGeometry.kt 同一個模型，
兩邊數值要一致到 1e-6；改公式時兩邊一起改，再重產 fixtures 跑 AvmGeometryTest）。

座標：車身座標 x 右、y 上、z 前（m），原點 = 車身投影中心的地面點。
相機座標：x 右、y 下、z 光軸。等距魚眼 rd = θ(1 + k1θ² + k2θ⁴)，fx/fy 都以象限寬度為單位。
Camera2 id "2" 的 1920x1080 畫面是 2x2 拼接：q0 左上=前、q1 右上=後、q2 左下=左、q3 右下=右。

用法（預設 --calib = app/src/main/assets/avm_calib.json）：
    python3 tools/avm_geometry.py bev RAW.png -o bev.png          # 用 LUT 拼出俯視圖
    python3 tools/avm_geometry.py fixtures -o app/src/test/resources/geometry_fixtures.json
    python3 tools/avm_geometry.py coverage -o coverage.png        # 每個 BEV 像素由哪顆鏡頭負責
    python3 tools/avm_geometry.py synth -o OUT_DIR                # 合成棋盤格端到端檢查

需要 numpy、Pillow。
"""
import argparse
import json
import math
import os
import sys

import numpy as np

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_CALIB = os.path.join(REPO, "app", "src", "main", "assets", "avm_calib.json")

DEG = math.pi / 180.0
HORIZON_EPS = 1e-6     # 射線 y 分量要 < -1e-6 才算打到地面
EGO_MARGIN = 0.05      # 車身外擴 5 cm 的範圍在 BEV 裡留黑
NEWTON_ITERS = 10

QUAD_NAMES = {0: "front", 1: "rear", 2: "left", 3: "right"}


def round_half_up(x):
    """跟 Kotlin 端 floor(x + 0.5) 一致（Python round() 是銀行家捨入）。"""
    return int(math.floor(x + 0.5))


def _mul3(a, b):
    return [[a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j] for j in range(3)]
            for i in range(3)]


def rotation(yaw_deg, pitch_deg, roll_deg):
    """相機 → 車身：Ryaw · Rpitch · Rroll · B（B 把相機 y 下翻成 y 上）。"""
    cy, sy = math.cos(yaw_deg * DEG), math.sin(yaw_deg * DEG)
    cp, sp = math.cos(pitch_deg * DEG), math.sin(pitch_deg * DEG)
    cr, sr = math.cos(roll_deg * DEG), math.sin(roll_deg * DEG)
    ryaw = [[cy, 0.0, sy], [0.0, 1.0, 0.0], [-sy, 0.0, cy]]
    rpitch = [[1.0, 0.0, 0.0], [0.0, cp, -sp], [0.0, sp, cp]]
    rroll = [[cr, -sr, 0.0], [sr, cr, 0.0], [0.0, 0.0, 1.0]]
    b = [[1.0, 0.0, 0.0], [0.0, -1.0, 0.0], [0.0, 0.0, 1.0]]
    return _mul3(_mul3(_mul3(ryaw, rpitch), rroll), b)


class Cam:
    def __init__(self, name, d):
        self.name = name
        self.quad = int(d["quad"])
        self.mirror = bool(d.get("mirror", False))
        self.cx = float(d.get("cx", 0.5))
        self.cy = float(d.get("cy", 0.5))
        self.fx = float(d.get("fx", 0.265))
        self.fy = float(d.get("fy", 0.265))
        self.k1 = float(d.get("k1", 0.0))
        self.k2 = float(d.get("k2", 0.0))
        self.max_deg = float(d.get("maxDeg", 95.0))
        self.px, self.py, self.pz = (float(t) for t in d["pos"])
        self.yaw = float(d.get("yaw", 0.0))
        self.pitch = float(d.get("pitch", 0.0))
        self.roll = float(d.get("roll", 0.0))
        self.m = rotation(self.yaw, self.pitch, self.roll)   # rv = m · rc
        self.max_rad = self.max_deg * DEG


class Geometry:
    def __init__(self, calib):
        car = calib.get("car", {})
        self.car_length = float(car.get("length", 4.84))
        self.car_width = float(car.get("width", 1.85))
        self.cams = sorted((Cam(n, d) for n, d in calib["cams"].items()), key=lambda c: c.quad)
        b = calib.get("bev", {})
        self.x_min = float(b.get("xMin", -6.0))
        self.x_max = float(b.get("xMax", 6.0))
        self.z_min = float(b.get("zMin", -7.0))
        self.z_max = float(b.get("zMax", 7.0))
        self.mpp = float(b.get("mpp", 0.04))
        self.bev_w = round_half_up((self.x_max - self.x_min) / self.mpp)
        self.bev_h = round_half_up((self.z_max - self.z_min) / self.mpp)
        self.origin_x = -self.x_min / self.mpp
        self.origin_y = self.z_max / self.mpp

    @classmethod
    def load(cls, path):
        with open(path, encoding="utf-8") as f:
            return cls(json.load(f))

    def cam(self, name):
        return next((c for c in self.cams if c.name == name), None)

    def cam_for_quad(self, q):
        return next((c for c in self.cams if c.quad == q), None)

    def bev_ground(self):
        """BEV 每個像素中心的地面座標 (gx, gz)，形狀 (bev_h, bev_w)。"""
        i = np.arange(self.bev_w, dtype=np.float64)
        j = np.arange(self.bev_h, dtype=np.float64)
        gx = self.x_min + (i + 0.5) * self.mpp
        gz = self.z_max - (j + 0.5) * self.mpp
        return np.meshgrid(gx, gz)

    def ego_mask(self, gx, gz):
        hw = self.car_width / 2 + EGO_MARGIN
        hl = self.car_length / 2 + EGO_MARGIN
        return (np.abs(gx) <= hw) & (np.abs(gz) <= hl)


# ---------------------------------------------------------------- 投影（全部向量化）

def solve_theta(rd, k1, k2):
    """解 rd = θ(1 + k1θ² + k2θ⁴)：從 θ = rd 起做 10 次 Newton，θ ≥ 0。"""
    rd = np.asarray(rd, dtype=np.float64)
    if k1 == 0.0 and k2 == 0.0:
        return rd.copy()
    t = rd.copy()
    for _ in range(NEWTON_ITERS):
        t2 = t * t
        f = t * (1.0 + k1 * t2 + k2 * t2 * t2) - rd
        fp = 1.0 + 3.0 * k1 * t2 + 5.0 * k2 * t2 * t2
        ok = np.abs(fp) >= 1e-12
        t = np.where(ok, t - f / np.where(ok, fp, 1.0), t)
        t = np.where(t < 0.0, 0.0, t)
    return t


def _unit_dir(a, b, r):
    """(cos, sin) = (a/r, b/r)；r = 0 時給 (1, 0)（此時 θ = 0，方向不影響結果）。"""
    pos = r > 0.0
    safe = np.where(pos, r, 1.0)
    return np.where(pos, a / safe, 1.0), np.where(pos, b / safe, 0.0)


def pixel_to_ray(cam, u, v, qw, qh):
    """象限內像素 (u, v)（連續座標，整數像素 k 的中心是 k + 0.5）→ 車身座標單位射線 (rx, ry, rz)。"""
    u = np.asarray(u, dtype=np.float64)
    v = np.asarray(v, dtype=np.float64)
    if cam.mirror:
        u = qw - u
    dx = (u - cam.cx * qw) / (cam.fx * qw)
    dy = (v - cam.cy * qh) / (cam.fy * qw)
    rd = np.sqrt(dx * dx + dy * dy)
    th = solve_theta(rd, cam.k1, cam.k2)
    st = np.sin(th)
    ct = np.cos(th)
    ca, sa = _unit_dir(dx, dy, rd)
    x = st * ca
    y = st * sa
    z = ct
    m = cam.m
    rx = m[0][0] * x + m[0][1] * y + m[0][2] * z
    ry = m[1][0] * x + m[1][1] * y + m[1][2] * z
    rz = m[2][0] * x + m[2][1] * y + m[2][2] * z
    return rx, ry, rz


def pixel_to_ground(cam, u, v, qw, qh):
    """→ (gx, gz, ok, margin)。ok=False 的位置 gx/gz 為 NaN；margin = 射線 y 離門檻多遠。"""
    rx, ry, rz = pixel_to_ray(cam, u, v, qw, qh)
    ok = ry < -HORIZON_EPS
    t = -cam.py / np.where(ok, ry, -1.0)
    gx = np.where(ok, cam.px + t * rx, np.nan)
    gz = np.where(ok, cam.pz + t * rz, np.nan)
    return gx, gz, ok, np.abs(ry + HORIZON_EPS)


def ground_to_pixel(cam, gx, gz, qw, qh):
    """地面點 → (u, v, θ, ok, margin)。u/v/θ 不論 ok 都會算；margin = 離任一有效邊界的最小距離。"""
    gx = np.asarray(gx, dtype=np.float64)
    gz = np.asarray(gz, dtype=np.float64)
    m = cam.m
    dX = gx - cam.px
    dY = -cam.py
    dZ = gz - cam.pz
    x = m[0][0] * dX + m[1][0] * dY + m[2][0] * dZ
    y = m[0][1] * dX + m[1][1] * dY + m[2][1] * dZ
    z = m[0][2] * dX + m[1][2] * dY + m[2][2] * dZ
    r = np.sqrt(x * x + y * y)
    th = np.arctan2(r, z)
    t2 = th * th
    rd = th * (1.0 + cam.k1 * t2 + cam.k2 * t2 * t2)
    ca, sa = _unit_dir(x, y, r)
    u = cam.cx * qw + cam.fx * qw * rd * ca
    v = cam.cy * qh + cam.fy * qw * rd * sa
    if cam.mirror:
        u = qw - u
    ok = (th <= cam.max_rad) & (u >= 0.0) & (u < qw) & (v >= 0.0) & (v < qh)
    margin = np.minimum.reduce([np.abs(th - cam.max_rad), np.abs(u), np.abs(qw - u),
                                np.abs(v), np.abs(qh - v)])
    return u, v, th, ok, margin


def build_bev_lut(geo, frame_w, frame_h, with_owner=False):
    """BEV LUT：每個 BEV 像素 → 原始 2x2 畫面像素索引（row-major），沒有鏡頭看到或在車身內 = -1。
    多顆鏡頭都看得到時取 θ 最小（最靠光軸）的那顆；θ 完全相同時 quad 編號小的優先。"""
    qw, qh = frame_w // 2, frame_h // 2
    gx, gz = geo.bev_ground()
    ego = geo.ego_mask(gx, gz)
    lut = np.full(gx.shape, -1, dtype=np.int64)
    owner = np.full(gx.shape, -1, dtype=np.int8)
    best = np.full(gx.shape, np.inf)
    for cam in geo.cams:
        u, v, th, ok, _ = ground_to_pixel(cam, gx, gz, qw, qh)
        take = ok & ~ego & (th < best)
        qx0 = (cam.quad % 2) * qw
        qy0 = (cam.quad // 2) * qh
        fu = np.floor(np.where(take, u, 0.0)).astype(np.int64)
        fv = np.floor(np.where(take, v, 0.0)).astype(np.int64)
        idx = (qy0 + fv) * frame_w + (qx0 + fu)
        lut[take] = idx[take]
        owner[take] = cam.quad
        best[take] = th[take]
    return (lut, owner) if with_owner else lut


def stitch(frame, lut):
    """frame (H, W[, C]) + LUT → BEV 影像；沒有覆蓋的像素填 0。"""
    h, w = frame.shape[:2]
    flat = frame.reshape(h * w, -1)
    out = np.zeros(lut.shape + (flat.shape[1],), dtype=frame.dtype)
    m = lut >= 0
    out[m] = flat[lut[m]]
    return out if frame.ndim == 3 else out[..., 0]


# ---------------------------------------------------------------- 影像小工具

def _load_rgb(path):
    from PIL import Image
    return np.asarray(Image.open(path).convert("RGB"))


def _save_rgb(path, arr, scale=1):
    from PIL import Image
    img = Image.fromarray(np.ascontiguousarray(arr.astype(np.uint8)))
    if scale != 1:
        img = img.resize((img.width * scale, img.height * scale), Image.NEAREST)
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    img.save(path)


CAM_COLORS = {0: (220, 60, 50), 1: (60, 110, 230), 2: (60, 190, 80), 3: (235, 190, 40)}


def coverage_image(geo, owner):
    gx, gz = geo.bev_ground()
    img = np.zeros(owner.shape + (3,), dtype=np.uint8)
    for q, col in CAM_COLORS.items():
        img[owner == q] = col
    img[geo.ego_mask(gx, gz)] = (70, 70, 70)
    # 每 1 m 一條淡格線，方便目測距離
    on_x = np.abs(gx - np.round(gx)) < geo.mpp / 2
    on_z = np.abs(gz - np.round(gz)) < geo.mpp / 2
    grid = (on_x | on_z) & (owner >= 0)
    img[grid] = (img[grid] * 0.6).astype(np.uint8)
    return img


# ---------------------------------------------------------------- 合成端到端檢查

def ground_texture(gx, gz):
    """0.5 m 棋盤格 + 紅色 x 軸（z=0）+ 綠色 z 軸（x=0）+ 藍色 x=±3 m 線 + 前方黃色三角（z 4~5.5 m）。"""
    gx = np.asarray(gx)
    gz = np.asarray(gz)
    out = np.zeros(gx.shape + (3,), dtype=np.uint8)
    good = np.isfinite(gx) & np.isfinite(gz)
    gxs = np.where(good, gx, 0.0)
    gzs = np.where(good, gz, 0.0)
    cell = (np.floor(gxs / 0.5).astype(np.int64) + np.floor(gzs / 0.5).astype(np.int64)) % 2
    out[...] = np.where(cell[..., None] == 0, 200, 70).astype(np.uint8)
    out[np.abs(np.abs(gxs) - 3.0) < 0.06] = (40, 80, 230)
    out[np.abs(gxs) < 0.06] = (30, 200, 30)
    out[np.abs(gzs) < 0.06] = (220, 30, 30)
    tri = (gzs >= 4.0) & (gzs <= 5.5) & (np.abs(gxs) <= (5.5 - gzs) * 0.6)
    out[tri] = (240, 210, 20)
    out[~good] = (20, 30, 60)    # 天空 / 打不到地面
    return out


def render_quad_frame(geo, frame_w, frame_h):
    """用模型反向渲染：每個象限像素中心 → 射線 → 地面 → 貼圖。回傳 (RGB 畫面, 地面座標畫面 (H,W,2))。"""
    qw, qh = frame_w // 2, frame_h // 2
    uu, vv = np.meshgrid(np.arange(qw) + 0.5, np.arange(qh) + 0.5)
    rgb = np.zeros((frame_h, frame_w, 3), dtype=np.uint8)
    coord = np.full((frame_h, frame_w, 2), np.nan)
    for cam in geo.cams:
        gx, gz, ok, _ = pixel_to_ground(cam, uu, vv, qw, qh)
        qx0 = (cam.quad % 2) * qw
        qy0 = (cam.quad // 2) * qh
        rgb[qy0:qy0 + qh, qx0:qx0 + qw] = ground_texture(gx, gz)
        coord[qy0:qy0 + qh, qx0:qx0 + qw, 0] = gx
        coord[qy0:qy0 + qh, qx0:qx0 + qw, 1] = gz
    return rgb, coord


def synth_check(geo, frame_w, frame_h, out_dir):
    rgb, coord = render_quad_frame(geo, frame_w, frame_h)
    lut, owner = build_bev_lut(geo, frame_w, frame_h, with_owner=True)
    bev = stitch(rgb, lut)
    gx, gz = geo.bev_ground()
    truth = ground_texture(gx, gz)
    truth[lut < 0] = 0
    m = lut >= 0

    # 1) 幾何誤差：BEV 像素取到的原始像素中心，反投影回地面，跟 BEV 像素中心差多遠（m）
    rec = stitch(coord, lut)
    err = np.hypot(rec[..., 0] - gx, rec[..., 1] - gz)
    # 2) 像素域：BEV 點投影 (u,v) 與實際取樣像素中心的差，理論上 |du|,|dv| <= 0.5
    qw, qh = frame_w // 2, frame_h // 2
    du_max = dv_max = 0.0
    per_cam = {}
    for cam in geo.cams:
        sel = m & (owner == cam.quad)
        if not sel.any():
            continue
        u, v, _, _, _ = ground_to_pixel(cam, gx[sel], gz[sel], qw, qh)
        idx = lut[sel]
        fu = (idx % frame_w) - (cam.quad % 2) * qw
        fv = (idx // frame_w) - (cam.quad // 2) * qh
        du_max = max(du_max, float(np.max(np.abs(u - (fu + 0.5)))))
        dv_max = max(dv_max, float(np.max(np.abs(v - (fv + 0.5)))))
        e = err[sel]
        per_cam[cam.name] = {"pixels": int(sel.sum()), "mean_m": float(e.mean()),
                             "p95_m": float(np.percentile(e, 95)), "max_m": float(e.max())}
    # 3) 顏色：拼回來的 BEV 與直接畫在 BEV 上的貼圖比對
    diff = np.abs(bev.astype(np.int16) - truth.astype(np.int16)).max(axis=2)
    mism = (diff > 40) & m
    # 管線自洽：BEV 顏色必須剛好等於取樣像素中心那個地面點的貼圖顏色
    exact = ground_texture(rec[..., 0], rec[..., 1])
    pipe_bad = int(np.count_nonzero(np.any(exact != bev, axis=2) & m))

    dist = np.maximum(np.abs(gx) - geo.car_width / 2, 0) + np.maximum(np.abs(gz) - geo.car_length / 2, 0)
    near = m & (dist <= 2.0)
    far = m & (dist > 2.0)
    metrics = {
        "frame": [frame_w, frame_h], "bev": [geo.bev_w, geo.bev_h], "mpp": geo.mpp,
        "covered_pixels": int(m.sum()),
        "geom_err_m": {"mean": float(err[m].mean()), "median": float(np.median(err[m])),
                       "p95": float(np.percentile(err[m], 95)), "max": float(err[m].max()),
                       "near_2m_mean": float(err[near].mean()), "near_2m_max": float(err[near].max()),
                       "far_mean": float(err[far].mean()), "far_max": float(err[far].max())},
        "per_cam": per_cam,
        "pixel_offset_max": {"du": du_max, "dv": dv_max},
        "color_mismatch_frac": float(mism.sum() / m.sum()),
        "color_mean_abs": float(diff[m].mean()),
        "pipeline_color_inconsistent": pipe_bad,
    }
    os.makedirs(out_dir, exist_ok=True)
    _save_rgb(os.path.join(out_dir, "synth_quad.png"), rgb)
    _save_rgb(os.path.join(out_dir, "synth_bev.png"), bev, scale=2)
    _save_rgb(os.path.join(out_dir, "synth_truth.png"), truth, scale=2)
    heat = np.zeros(err.shape + (3,), dtype=np.uint8)
    lvl = np.clip(err / 0.10, 0, 1)            # 0 → 黑、≥ 10 cm → 全紅
    heat[..., 0] = np.where(m, 40 + 215 * lvl, 0).astype(np.uint8)
    heat[..., 1] = np.where(m, 40 * (1 - lvl), 0).astype(np.uint8)
    _save_rgb(os.path.join(out_dir, "synth_err.png"), heat, scale=2)
    with open(os.path.join(out_dir, "synth_metrics.json"), "w", encoding="utf-8") as f:
        json.dump(metrics, f, indent=2)
    return metrics


# ---------------------------------------------------------------- fixtures（給 AvmGeometryTest）

# 涵蓋 mirror / 畸變 / roll / 非對稱主點 / fx≠fy 的測試用參數（不是真實校正值）
VARIANT_CALIB = {
    "version": 1,
    "note": "測試用：涵蓋 mirror、k1/k2、roll、主點偏移、fx≠fy",
    "car": {"length": 4.84, "width": 1.85},
    "cams": {
        "front": {"quad": 0, "mirror": True, "cx": 0.52, "cy": 0.47, "fx": 0.27, "fy": 0.262,
                  "k1": -0.035, "k2": 0.0021, "maxDeg": 92,
                  "pos": [0.05, 0.6, 2.3], "yaw": 3.5, "pitch": 28, "roll": -2.0},
        "rear": {"quad": 1, "mirror": True, "cx": 0.49, "cy": 0.53, "fx": 0.26, "fy": 0.268,
                 "k1": 0.02, "k2": -0.003, "maxDeg": 97,
                 "pos": [-0.04, 0.92, -2.38], "yaw": 178, "pitch": 33, "roll": 1.5},
        "left": {"quad": 2, "mirror": False, "cx": 0.505, "cy": 0.495, "fx": 0.255, "fy": 0.259,
                 "k1": -0.05, "k2": 0.004, "maxDeg": 94,
                 "pos": [-0.98, 1.03, 0.8], "yaw": -92, "pitch": 57, "roll": 4.0},
        "right": {"quad": 3, "mirror": True, "cx": 0.48, "cy": 0.51, "fx": 0.272, "fy": 0.266,
                  "k1": -0.02, "k2": 0.0, "maxDeg": 96,
                  "pos": [1.01, 0.98, 0.9], "yaw": 88, "pitch": 52, "roll": -3.0},
    },
    "bev": {"xMin": -6.0, "xMax": 6.0, "zMin": -7.0, "zMax": 7.0, "mpp": 0.04},
}


def _g(x, digits=12):
    """參考值留 12 位有效數字（夠 1e-6 比對，檔案小一半）。"""
    return None if not math.isfinite(x) else float("%.*g" % (digits, x))


def fixture_set(name, calib, qw=960, qh=540):
    geo = Geometry(calib)
    us = [0.0] + [round(0.37 + 59.9 * k, 6) for k in range(17)] + [959.99]
    vs = [0.0] + [round(0.21 + 44.9 * k, 6) for k in range(13)] + [539.99]
    uu, vv = np.meshgrid(np.array(us), np.array(vs))
    uu, vv = uu.ravel(), vv.ravel()
    gxs = [round(-8.4 + 0.8 * k, 6) for k in range(22)]
    gzs = [round(-9.6 + 0.8 * k, 6) for k in range(25)]
    GX, GZ = np.meshgrid(np.array(gxs), np.array(gzs))
    GX, GZ = GX.ravel(), GZ.ravel()
    cams = []
    for cam in geo.cams:
        rx, ry, rz = pixel_to_ray(cam, uu, vv, qw, qh)
        gx, gz, ok, mg = pixel_to_ground(cam, uu, vv, qw, qh)
        pix = []
        for k in range(len(uu)):
            pix.append([float(uu[k]), float(vv[k]), _g(rx[k]), _g(ry[k]), _g(rz[k]),
                        _g(gx[k]) if ok[k] else None, _g(gz[k]) if ok[k] else None, _g(mg[k], 3)])
        u, v, th, ok2, mg2 = ground_to_pixel(cam, GX, GZ, qw, qh)
        gnd = []
        for k in range(len(GX)):
            gnd.append([float(GX[k]), float(GZ[k]), _g(u[k]) if ok2[k] else None,
                        _g(v[k]) if ok2[k] else None, _g(th[k]), _g(mg2[k], 3)])
        cams.append({"name": cam.name, "pix": pix, "gnd": gnd})
    return {"name": name, "calib": calib, "qw": qw, "qh": qh,
            "bev": {"width": geo.bev_w, "height": geo.bev_h,
                    "originX": geo.origin_x, "originY": geo.origin_y},
            "cams": cams}


def make_fixtures(calib, frame_w=1920, frame_h=1080, step=7):
    geo = Geometry(calib)
    lut, owner = build_bev_lut(geo, frame_w, frame_h, with_owner=True)
    flat = lut.ravel()
    samples = [int(x) for x in flat[::step]]
    return {
        "note": "由 tools/avm_geometry.py fixtures 產生，勿手改。"
                "pix 列: [u, v, rx, ry, rz, gx|null, gz|null, margin]；"
                "gnd 列: [gx, gz, u|null, v|null, theta, margin]；margin = 離有效邊界的距離。",
        "sets": [fixture_set("default", calib), fixture_set("variant", VARIANT_CALIB)],
        "lut": {
            "set": "default", "frameW": frame_w, "frameH": frame_h,
            "width": geo.bev_w, "height": geo.bev_h,
            "valid": int(np.count_nonzero(flat >= 0)),
            "ownerCounts": {QUAD_NAMES.get(q, str(q)): int(np.count_nonzero(owner == q)) for q in range(4)},
            "step": step,
            "samples": [samples[k:k + 25] for k in range(0, len(samples), 25)],
        },
    }


def _dump_rows(obj):
    """壓縮 JSON，但每一列換行，diff 比較好看。"""
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":")).replace("],[", "],\n[") + "\n"


# ---------------------------------------------------------------- CLI

def _load_calib(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def cmd_bev(a):
    geo = Geometry(_load_calib(a.calib))
    frame = _load_rgb(a.raw)
    h, w = frame.shape[:2]
    if (w, h) != (1920, 1080):
        print("注意：畫面是 %dx%d，不是 1920x1080；照實際大小切四象限" % (w, h), file=sys.stderr)
    lut = build_bev_lut(geo, w, h)
    _save_rgb(a.output, stitch(frame, lut), scale=a.scale)
    print("BEV %dx%d（mpp=%.3f，車身原點在像素 %.1f,%.1f）→ %s"
          % (geo.bev_w, geo.bev_h, geo.mpp, geo.origin_x, geo.origin_y, a.output))
    return 0


def cmd_fixtures(a):
    fx = make_fixtures(_load_calib(a.calib))
    os.makedirs(os.path.dirname(os.path.abspath(a.output)), exist_ok=True)
    with open(a.output, "w", encoding="utf-8") as f:
        f.write(_dump_rows(fx))
    lut = fx["lut"]
    print("fixtures → %s（%d bytes）；LUT %dx%d 有效 %d，取樣 %d 筆"
          % (a.output, os.path.getsize(a.output), lut["width"], lut["height"], lut["valid"],
             sum(len(r) for r in lut["samples"])))
    return 0


def coverage_stats(geo, owner):
    gx, gz = geo.bev_ground()
    ego = geo.ego_mask(gx, gz)
    total = owner.size
    outside = int(np.count_nonzero(~ego))
    stats = {"total": total, "ego": int(ego.sum()), "none": int(np.count_nonzero((owner < 0) & ~ego))}
    for cam in geo.cams:
        stats[cam.name] = int(np.count_nonzero(owner == cam.quad))
    return stats, outside


def cmd_coverage(a):
    geo = Geometry(_load_calib(a.calib))
    _, owner = build_bev_lut(geo, a.width, a.height, with_owner=True)
    _save_rgb(a.output, coverage_image(geo, owner), scale=a.scale)
    stats, outside = coverage_stats(geo, owner)
    print("BEV %dx%d = %d 像素（車身外 %d）" % (geo.bev_w, geo.bev_h, stats["total"], outside))
    for k in [c.name for c in geo.cams] + ["none", "ego"]:
        n = stats[k]
        print("  %-6s %6d  佔全部 %6.2f%%  佔車身外 %6.2f%%"
              % (k, n, 100.0 * n / stats["total"], 100.0 * n / outside if k != "ego" else float("nan")))
    print("→ %s（紅=前 藍=後 綠=左 黃=右 灰=車身 黑=無覆蓋）" % a.output)
    return 0


def cmd_synth(a):
    geo = Geometry(_load_calib(a.calib))
    mt = synth_check(geo, a.width, a.height, a.output)
    print(json.dumps(mt, indent=2, ensure_ascii=False))
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description="AVM 魚眼 → 地面幾何工具（與 AvmGeometry.kt 同模型）")
    ap.add_argument("--calib", default=DEFAULT_CALIB, help="校正檔（預設 app/src/main/assets/avm_calib.json）")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("bev", help="1920x1080 2x2 原始畫面 → BEV 俯視圖")
    p.add_argument("raw")
    p.add_argument("-o", "--output", required=True)
    p.add_argument("--scale", type=int, default=1, help="輸出放大倍數（最近鄰）")
    p.set_defaults(fn=cmd_bev)

    p = sub.add_parser("fixtures", help="產生 Kotlin 單元測試用的參考值")
    p.add_argument("-o", "--output", required=True)
    p.set_defaults(fn=cmd_fixtures)

    p = sub.add_parser("coverage", help="每個 BEV 像素由哪顆鏡頭負責（顏色標示）")
    p.add_argument("-o", "--output", required=True)
    p.add_argument("--width", type=int, default=1920)
    p.add_argument("--height", type=int, default=1080)
    p.add_argument("--scale", type=int, default=2)
    p.set_defaults(fn=cmd_coverage)

    p = sub.add_parser("synth", help="合成棋盤格：渲染四路魚眼畫面再拼回 BEV，量重建誤差")
    p.add_argument("-o", "--output", required=True, help="輸出資料夾")
    p.add_argument("--width", type=int, default=1920)
    p.add_argument("--height", type=int, default=1080)
    p.set_defaults(fn=cmd_synth)

    # --calib 放在子命令後面也接受
    for sp in sub.choices.values():
        sp.add_argument("--calib", default=argparse.SUPPRESS, help=argparse.SUPPRESS)
    a = ap.parse_args(argv)
    return a.fn(a)


if __name__ == "__main__":
    sys.exit(main())
