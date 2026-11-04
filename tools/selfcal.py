#!/usr/bin/env python3
"""
自我校正：只用車機拍下來的原始四分割畫面，把四顆魚眼的外參擬合出來。
不需要原廠「全景360」截圖、不需要棋盤格靶、不需要任何校正檔（tools/fit_calib.py 需要截圖當真值）。

  RAW  Camera2 id "2" 的 1920x1080 原始 2x2 拼接畫面
       （q0 左上=前、q1 右上=後、q2 左下=左、q3 右下=右）

用法：
    python3 tools/selfcal.py raw1.jpg [raw2.jpg ...] -o out_calib.json [--debug-dir DIR]

想法
----
未校正時，地面上連續的直線（雙黃線、紅線、停車格線）在俯視圖的鏡頭接縫處會折斷：
前鏡頭那一段跟側鏡頭那一段角度對不起來。反過來說——

    真實地面上同一個點，從相鄰兩顆鏡頭投影到俯視圖應該落在同一個位置。

所以不需要任何外部真值：把「兩顆鏡頭都看得到的地面區域」拿出來，分別用兩顆鏡頭取樣，
要求取到的影像一致，就是校正的目標函數。接縫處的線接起來 = 殘差變小。

目標函數
--------
對每一對相鄰鏡頭（前-左、前-右、後-左、後-右）：
  1. 在地面網格上找兩顆鏡頭都看得到、又不在車身附近的點（= 重疊區，見 PairLevel）。
  2. 兩邊各自依該點的局部放大率挑原始畫面的模糊層（像 mipmap），讓兩邊在地面上的
     模糊程度一樣，否則殘差會被解析度差異主導。
  3. 兩邊各自做局部正規化（減局部平均、除局部標準差；重用 fit_calib.local_norm），
     吸收兩顆鏡頭的曝光／白平衡差異。
  4. 殘差 = 兩邊特徵相減，過 soft-L1 穩健損失（重用 fit_calib.robust）壓立體物、
     反光、路人等離群值。
  5. 兩個通道：luma（一般紋理）與 chroma = R−B（黃線、紅線這種細長彩色結構）。
     chroma 的正規化下限拉高，平坦柏油幾乎不出力，等於一個「線結構」通道。

自由度
------
只擬合「每顆鏡頭的 yaw / pitch / roll」+「四顆共用的焦距倍率」= 13 個參數；
pos（安裝位置）固定在起始值。加上 ±15° / 0.8–1.25x 的界限與對起始值的弱先驗，
再加覆蓋率懲罰（避免最佳化靠「把重疊區轉掉」來降殘差）。多張圖一起擬合（殘差串接）。

需要 numpy、scipy、Pillow。四張圖、三層，在 Apple M4 上約 2–4 分鐘。
"""
import argparse
import datetime
import json
import math
import os
import sys
import time

import numpy as np
from scipy import ndimage, optimize

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import avm_geometry as ag  # noqa: E402
import fit_calib as fc  # noqa: E402

DEG = math.pi / 180.0

# 相鄰鏡頭配對（前後鏡頭彼此沒有重疊區，左右也沒有）
PAIRS = (("front", "left"), ("front", "right"), ("rear", "left"), ("rear", "right"))
ANGLES = ("yaw", "pitch", "roll")

# 參數正規化：z 乘上步長 = 自然單位（度 / ln 倍率）
ANG_STEP = 1.0          # 1 單位 = 1 度
ANG_BOUND = 15.0        # 角度上下界（相對起始值，度）
ANG_PRIOR = 6.0         # 角度弱先驗 σ（度）
F_STEP = 0.02           # 1 單位 = ln 焦距倍率 0.02
F_LO, F_HI = 0.80, 1.25  # 焦距倍率界限
F_PRIOR = 0.08          # ln 焦距倍率先驗 σ

RAW_SIGMAS = (0.0, 1.0, 2.0, 4.0, 8.0, 16.0, 32.0)   # 原始畫面模糊層（像素）
DARK_SUM = 150          # R+G+B 低於此值視為魚眼圈外的暗角
MASK_ERODE = 9          # 有效遮罩內縮（像素）
EDGE_PX = 12.0          # 取樣點離象限邊緣的軟門檻（像素）
TH_SOFT_DEG = 5.0       # 離 maxDeg 的軟門檻（度）
EGO_MARGIN = 0.35       # 車身矩形外擴多少才開始取樣（m）；車身鈑金不在地面上
MAX_RANGE = 4.5         # 離車身矩形多遠以內才取樣（m）；太遠解析度太差
MIN_SAMPLES = 200       # 一個鏡頭對在一層至少要有幾個可比對的點
MIN_CONTRAST = 2.5      # 重疊區的局部標準差中位數低於此值 = 地面沒紋理
LUMA_EPS_FLOOR = 2.0    # 局部正規化的穩定項下限（灰階）
CHROMA_EPS_FLOOR = 7.0  # chroma 的下限拉高：平坦處幾乎不出力，只剩彩色細線
CHANNEL_W = {"luma": 1.0, "chroma": 0.6}
DATA_WEIGHT = 1500.0    # 每個（鏡頭對 × 畫面 × 層）的資料項總權重
COVER_WEIGHT = 400.0    # 重疊區縮水的懲罰權重
ROBUST_C = 1.0

# 層級：(取樣間距 m, 地面模糊 σ m, 局部正規化 σ m)
LEVELS = ((0.14, 0.20, 0.70), (0.09, 0.10, 0.45), (0.06, 0.05, 0.30))


def smoothstep(t):
    t = np.clip(t, 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


# ---------------------------------------------------------------- 一個象限的影像

class QuadView:
    """原始畫面裡某一顆鏡頭的象限：通道金字塔（lazy）+ 魚眼圈有效遮罩。"""

    def __init__(self, rgb_quad, name):
        self.name = name
        rgb = np.asarray(rgb_quad)
        self.qh, self.qw = rgb.shape[:2]
        f = rgb.astype(np.float32)
        self.base = {
            "luma": fc.luma(rgb).astype(np.float32),
            # R−B：黃線、紅線為正，柏油接近 0，天空／藍色為負
            "chroma": np.ascontiguousarray(f[..., 0] - f[..., 2]),
        }
        self.valid = self._circle_mask(rgb)
        self._cache = {}

    @staticmethod
    def _circle_mask(rgb):
        """魚眼成像圈：亮度夠的最大連通區域 → 補洞 → 內縮。比單純門檻穩，暗柏油不會被誤判。"""
        s = rgb.astype(np.int32).sum(axis=2)
        m = s >= DARK_SUM
        m = ndimage.binary_opening(m, np.ones((5, 5)))
        lab, n = ndimage.label(m)
        if n > 1:
            sz = ndimage.sum(m, lab, range(1, n + 1))
            m = lab == (1 + int(np.argmax(sz)))
        elif n == 0:
            m = np.ones(s.shape, bool)
        m = ndimage.binary_fill_holes(m)
        m = ndimage.binary_erosion(m, np.ones((MASK_ERODE, MASK_ERODE)), border_value=0)
        return m.astype(np.float32)

    def level(self, ch, k):
        """第 k 層模糊的通道影像（含 valid 權重的歸一化模糊，暗角不會滲進來）。"""
        key = (ch, k)
        if key not in self._cache:
            sig = RAW_SIGMAS[k]
            src = self.base[ch] if ch != "valid" else self.valid
            if sig <= 0.0:
                self._cache[key] = src
            elif ch == "valid":
                self._cache[key] = ndimage.gaussian_filter(src, sig, mode="constant", truncate=3.0)
            else:
                v = self.valid
                num = ndimage.gaussian_filter(src * v, sig, mode="constant", truncate=3.0)
                den = ndimage.gaussian_filter(v, sig, mode="constant", truncate=3.0)
                self._cache[key] = (num / np.maximum(den, 1e-6)).astype(np.float32)
        return self._cache[key]


class Frame:
    """一張原始 2x2 畫面。"""

    def __init__(self, rgb, geo0, tag=""):
        self.tag = tag
        self.rgb = np.asarray(rgb)
        h, w = self.rgb.shape[:2]
        self.w, self.h = w, h
        self.qw, self.qh = w // 2, h // 2
        self.views = {}
        for cam in geo0.cams:
            x0, y0 = (cam.quad % 2) * self.qw, (cam.quad // 2) * self.qh
            self.views[cam.name] = QuadView(
                self.rgb[y0:y0 + self.qh, x0:x0 + self.qw], cam.name)


# ---------------------------------------------------------------- 取樣

def mip_groups(cam, gx, gz, qw, qh, blur_m, s):
    """依地面點的局部放大率（px²/m²）決定要用哪一層模糊，使地面上的模糊都是 blur_m。"""
    h = 0.5 * s
    ua, va, _ = fc.project(cam, gx + h, gz, qw, qh)
    ub, vb, _ = fc.project(cam, gx - h, gz, qw, qh)
    uc, vc, _ = fc.project(cam, gx, gz + h, qw, qh)
    ud, vd, _ = fc.project(cam, gx, gz - h, qw, qh)
    det = np.abs((ua - ub) * (vc - vd) - (uc - ud) * (va - vb)) / (2 * h) ** 2
    need = blur_m * np.sqrt(np.maximum(det, 0.0))
    idx = np.interp(need, RAW_SIGMAS, np.arange(len(RAW_SIGMAS), dtype=np.float64))
    lo = np.minimum(np.floor(idx).astype(np.int64), len(RAW_SIGMAS) - 2)
    groups = [(k, np.where(lo == k)[0]) for k in range(len(RAW_SIGMAS) - 1)]
    return [(k, g) for k, g in groups if g.size], (idx - lo).astype(np.float32)


def sample_mip(view, ch, u, v, groups, w):
    vals = np.empty(u.size, dtype=np.float32)
    rr = v - 0.5
    cc = u - 0.5
    for k, g in groups:
        c = np.vstack((rr[g], cc[g]))
        a = ndimage.map_coordinates(view.level(ch, k), c, order=1, mode="nearest")
        b = ndimage.map_coordinates(view.level(ch, k + 1), c, order=1, mode="nearest")
        vals[g] = a + (b - a) * w[g]
    return vals


def local_sd(X, M, sig):
    """遮罩內局部標準差的中位數（當作 local_norm 穩定項的尺度，也是「有沒有紋理」的指標）。"""
    Mf = M.astype(np.float32)
    kw = dict(mode="constant", truncate=3.0)
    den = ndimage.gaussian_filter(Mf, sig, **kw) + 1e-6
    mu = ndimage.gaussian_filter(X * Mf, sig, **kw) / den
    D = (X - mu) * Mf
    var = ndimage.gaussian_filter(D * D, sig, **kw) / den
    sd = np.sqrt(var[M])
    return float(np.median(sd)) if sd.size else 0.0


# ---------------------------------------------------------------- 鏡頭對 × 一層

class PairLevel:
    """一張畫面、一對相鄰鏡頭、一個尺度的重疊區比對。

    重疊區怎麼找：在 BEV 範圍內鋪一張間距 s 的地面網格，留下
      (a) 在車身矩形外擴 EGO_MARGIN 之外、離車身 MAX_RANGE 之內（鈑金不是地面、太遠沒解析度）
      (b) 兩顆鏡頭用「目前參數」投影都落在象限內（離邊緣 EDGE_PX）、θ 離 maxDeg 還有餘裕
      (c) 兩顆鏡頭投到的位置都在魚眼成像圈裡（暗角之外）
    的點。這組點在整層固定不變（殘差長度要固定），參數變動造成的進出用軟權重處理。
    """

    def __init__(self, frame, a, b, cams, geo, s, blur_m, sig_m, opts):
        self.frame = frame
        self.a, self.b = a, b
        self.s, self.blur_m = s, blur_m
        self.ok = False
        qw, qh = frame.qw, frame.qh
        self.qw, self.qh = qw, qh

        nx = max(int(round((geo.x_max - geo.x_min) / s)), 2)
        nz = max(int(round((geo.z_max - geo.z_min) / s)), 2)
        gx1 = geo.x_min + (np.arange(nx) + 0.5) * s
        gz1 = geo.z_max - (np.arange(nz) + 0.5) * s
        GX, GZ = np.meshgrid(gx1, gz1)
        hw = geo.car_width / 2.0 + opts["ego_margin"]
        hl = geo.car_length / 2.0 + opts["ego_margin"]
        dx = np.maximum(np.abs(GX) - hw, 0.0)
        dz = np.maximum(np.abs(GZ) - hl, 0.0)
        ground = ((dx > 0) | (dz > 0)) & (np.hypot(dx, dz) <= opts["max_range"])

        M = ground.copy()
        for n in (a, b):
            u, v, th = fc.project(cams[n], GX, GZ, qw, qh)
            vis = ((th <= cams[n].max_rad - TH_SOFT_DEG * DEG) & (u >= EDGE_PX)
                   & (u < qw - EDGE_PX) & (v >= EDGE_PX) & (v < qh - EDGE_PX))
            sv = np.zeros(M.shape, np.float32)
            sel = vis & M
            if sel.any():
                rc = np.vstack((v[sel] - 0.5, u[sel] - 0.5))
                sv[sel] = ndimage.map_coordinates(frame.views[n].valid, rc, order=1,
                                                  mode="constant", cval=0.0)
            M &= vis & (sv > 0.9)
        self.n = int(M.sum())
        if self.n < MIN_SAMPLES:
            return

        sig = sig_m / s
        pad = int(math.ceil(2.5 * sig))
        rows = np.where(M.any(axis=1))[0]
        cols = np.where(M.any(axis=0))[0]
        r0, r1 = max(int(rows[0]) - pad, 0), min(int(rows[-1]) + pad + 1, nz)
        c0, c1 = max(int(cols[0]) - pad, 0), min(int(cols[-1]) + pad + 1, nx)
        self.M = np.ascontiguousarray(M[r0:r1, c0:c1])
        self.gx = np.ascontiguousarray(GX[r0:r1, c0:c1][self.M])
        self.gz = np.ascontiguousarray(GZ[r0:r1, c0:c1][self.M])
        self.sig = sig
        self.buf = np.zeros(self.M.shape, dtype=np.float32)
        self.groups = {}
        for n in (a, b):
            self.groups[n] = mip_groups(cams[n], self.gx, self.gz, qw, qh, blur_m, s)

        # 兩邊共用一組 eps，正規化後的對比尺度才可比
        self.eps = {}
        self.sd = {}
        for ch, floor in (("luma", LUMA_EPS_FLOOR), ("chroma", CHROMA_EPS_FLOOR)):
            sds = []
            for n in (a, b):
                X = self._fill(frame.views[n], ch, cams[n])[0]
                sds.append(local_sd(X, self.M, sig))
            self.sd[ch] = float(np.mean(sds))
            self.eps[ch] = max(floor, 0.3 * self.sd[ch])
        self.ok = True
        self.w0 = float(self._weights(cams[a], cams[b]).sum())
        if self.w0 < MIN_SAMPLES * 0.5:
            self.ok = False

    # ---- 內部
    def _fill(self, view, ch, cam):
        u, v, th = fc.project(cam, self.gx, self.gz, self.qw, self.qh)
        vals = sample_mip(view, ch, u, v, *self.groups[view.name])
        X = self.buf
        X[self.M] = vals
        return X, (u, v, th)

    def _soft(self, cam, view, u, v, th):
        """軟有效權重：離象限邊緣、離 maxDeg、離魚眼暗角都有平滑過渡，參數動的時候不會跳。"""
        edge = np.minimum.reduce([u, self.qw - u, v, self.qh - v])
        w = smoothstep(edge / EDGE_PX) * smoothstep((cam.max_rad - th) / (TH_SOFT_DEG * DEG))
        rc = np.vstack((np.clip(v - 0.5, -1, self.qh), np.clip(u - 0.5, -1, self.qw)))
        sv = ndimage.map_coordinates(view.valid, rc, order=1, mode="constant", cval=0.0)
        return w * smoothstep((sv - 0.5) / 0.4)

    def _weights(self, camA, camB):
        uA, vA, thA = fc.project(camA, self.gx, self.gz, self.qw, self.qh)
        uB, vB, thB = fc.project(camB, self.gx, self.gz, self.qw, self.qh)
        return (self._soft(camA, self.frame.views[self.a], uA, vA, thA)
                * self._soft(camB, self.frame.views[self.b], uB, vB, thB))

    def features(self, cams):
        """回傳 (每通道的 (FA, FB), 權重 w)。"""
        out = {}
        w = None
        for n, key in ((self.a, "A"), (self.b, "B")):
            cam = cams[n]
            view = self.frame.views[n]
            u = v = th = None
            for ch in CHANNEL_W:
                X, (u, v, th) = self._fill(view, ch, cam)
                out[(ch, key)] = fc.local_norm(X, self.M, self.sig, self.eps[ch])[self.M]
            sw = self._soft(cam, view, u, v, th)
            w = sw if w is None else w * sw
        return out, w

    def residuals(self, cams, loss="soft_l1"):
        f, w = self.features(cams)
        W = float(w.sum())
        scale = math.sqrt(DATA_WEIGHT / max(W, 1e-3))
        sw = np.sqrt(w) * scale
        parts = []
        for ch, cw in CHANNEL_W.items():
            e = f[(ch, "A")] - f[(ch, "B")]
            parts.append(fc.robust(e, ROBUST_C, loss) * sw * math.sqrt(cw))
        # 重疊區縮水的懲罰：不准靠「把重疊區轉掉」降殘差
        parts.append(np.array([math.sqrt(COVER_WEIGHT) * max(0.0, 1.0 - W / max(self.w0, 1e-6))]))
        return np.concatenate([p.astype(np.float64) for p in parts])

    def metrics(self, cams):
        """人看的數字：加權 RMS 差、加權 NCC、有效權重比例。"""
        f, w = self.features(cams)
        W = max(float(w.sum()), 1e-6)
        out = {"n": int(self.n), "w_frac": round(W / max(self.w0, 1e-6), 4)}
        for ch in CHANNEL_W:
            A, B = f[(ch, "A")], f[(ch, "B")]
            out["rms_" + ch] = round(float(math.sqrt(np.sum(w * (A - B) ** 2) / W)), 4)
            na = math.sqrt(float(np.sum(w * A * A)))
            nb = math.sqrt(float(np.sum(w * B * B)))
            out["ncc_" + ch] = round(float(np.sum(w * A * B) / max(na * nb, 1e-9)), 4)
        return out


# ---------------------------------------------------------------- 參數模型

class Model:
    """參數向量 z：最後一個永遠是「四顆共用的 ln 焦距倍率 / F_STEP」。
    fit_angles=True 時前面再接每顆鏡頭的 (yaw, pitch, roll) 差（度）。

    mode="scale"（預設）是給原廠 calibinfo.lua 轉出來的校正檔用的：外參、主點、畸變都已經精確，
    只剩畸變表換算到像素時的那個全域縮放沒定，所以只擬合 1 個參數。
    mode="angles" 是完全沒有原廠檔時的備案（13 個參數，很難解，見模組說明）。
    """

    def __init__(self, start_calib, names, fit_angles=False):
        self.names = list(names)
        self.base = {n: start_calib["cams"][n] for n in self.names}
        self.fit_angles = bool(fit_angles)

    @property
    def n(self):
        return 3 * len(self.names) + 1 if self.fit_angles else 1

    def bounds(self):
        lo = np.empty(self.n)
        hi = np.empty(self.n)
        lo[:-1] = -ANG_BOUND / ANG_STEP
        hi[:-1] = ANG_BOUND / ANG_STEP
        lo[-1] = math.log(F_LO) / F_STEP
        hi[-1] = math.log(F_HI) / F_STEP
        return lo, hi

    def cams(self, z):
        s = math.exp(float(z[-1]) * F_STEP)
        out = {}
        for i, n in enumerate(self.names):
            d0 = self.base[n]
            d = dict(d0)
            d["pos"] = list(d0["pos"])
            if self.fit_angles:
                for k, ang in enumerate(ANGLES):
                    d[ang] = float(d0.get(ang, 0.0)) + float(z[3 * i + k]) * ANG_STEP
            d["fx"] = float(d0.get("fx", 0.265)) * s
            d["fy"] = float(d0.get("fy", 0.265)) * s
            out[n] = ag.Cam(n, d)
        return out

    def deltas(self, z):
        out = {"f_scale": round(math.exp(float(z[-1]) * F_STEP), 5)}
        if self.fit_angles:
            for i, n in enumerate(self.names):
                out[n] = {ang: round(float(z[3 * i + k]) * ANG_STEP, 4)
                          for k, ang in enumerate(ANGLES)}
        return out


def prior_residual(z):
    r = np.empty(z.size)
    r[:-1] = z[:-1] * ANG_STEP / ANG_PRIOR
    r[-1] = z[-1] * F_STEP / F_PRIOR
    return r


def total_residual(z, model, pls, loss):
    cams = model.cams(z)
    parts = [pl.residuals(cams, loss) for pl in pls]
    parts.append(prior_residual(np.asarray(z, dtype=np.float64)))
    return np.concatenate(parts)


def cost_of(z, model, pls, loss):
    r = total_residual(z, model, pls, loss)
    return 0.5 * float(np.dot(r, r))


class Objective:
    """殘差 + 自己算的 Jacobian。

    為什麼不用 least_squares 的 diff_step：那是「相對」步長，而我們的起始值剛好是 0
    （參數都是相對起始校正檔的差），scipy 會退回 sqrt(eps)≈1.5e-8 的機器步長。影像殘差
    在這種步長下量到的全是 float32 捨入雜訊，Jacobian 會被高估一到兩個數量級，
    Gauss-Newton 步長縮到 1e-3，整個最佳化原地踏步（實測 cost 完全不降）。
    這裡改用固定的絕對步長做前向差分，並快取上一次的殘差省掉一次評估。
    """

    def __init__(self, model, pls, loss, step, lo, hi):
        self.model, self.pls, self.loss = model, pls, loss
        self.step = float(step)
        self.lo, self.hi = lo, hi
        self._key = None
        self._val = None
        self.nfev = 0

    def res(self, x):
        x = np.asarray(x, dtype=np.float64)
        key = x.tobytes()
        if key != self._key:
            self._key = key
            self._val = total_residual(x, self.model, self.pls, self.loss)
            self.nfev += 1
        return self._val

    def jac(self, x):
        x = np.asarray(x, dtype=np.float64)
        f0 = self.res(x).copy()
        J = np.empty((f0.size, x.size))
        for i in range(x.size):
            h = self.step if x[i] + self.step <= self.hi[i] else -self.step
            xp = x.copy()
            xp[i] += h
            J[:, i] = (self.res(xp) - f0) / h
        self.res(x)   # 讓快取回到 x，下一次 fun(x) 不用重算
        return J


# ---------------------------------------------------------------- 粗搜（逐角度線搜尋）

def scan_scale(z, model, pls, loss, lo, hi, n_grid, say=print, refine=14):
    """1 維：焦距倍率。網格掃過整個範圍找全域最小，再用黃金分割收斂。

    只有一個自由度，不需要（也不該用）梯度法——網格掃保證不會卡在局部極小，
    而且可以把整條曲線印出來給人看。回傳 (z, 曲線)。
    """
    z = np.asarray(z, dtype=np.float64).copy()
    grid = np.linspace(lo, hi, n_grid)
    curve = []
    for g in grid:
        zz = z.copy()
        zz[-1] = g
        curve.append((float(math.exp(g * F_STEP)), cost_of(zz, model, pls, loss)))
    k = int(np.argmin([c for _, c in curve]))
    a = grid[max(k - 1, 0)]
    b = grid[min(k + 1, n_grid - 1)]
    phi = (math.sqrt(5.0) - 1.0) / 2.0
    c, d = b - phi * (b - a), a + phi * (b - a)
    zc, zd = z.copy(), z.copy()
    zc[-1], zd[-1] = c, d
    fc_, fd = cost_of(zc, model, pls, loss), cost_of(zd, model, pls, loss)
    for _ in range(refine):
        if fc_ < fd:
            b, d, fd = d, c, fc_
            c = b - phi * (b - a)
            zc[-1] = c
            fc_ = cost_of(zc, model, pls, loss)
        else:
            a, c, fc_ = c, d, fd
            d = a + phi * (b - a)
            zd[-1] = d
            fd = cost_of(zd, model, pls, loss)
    z[-1] = 0.5 * (a + b)
    say("    掃描 %d 點 + 黃金分割 %d 次 → 倍率 %.4f" % (n_grid, refine, math.exp(z[-1] * F_STEP)))
    return z, curve


def coarse_search(z, model, pls, loss, passes=((6.0, 1.5), (2.0, 0.5)), say=print):
    """每顆鏡頭的三個角度輪流做一維線搜尋，只用牽涉到該鏡頭的鏡頭對。
    殘差把四顆鏡頭綁在一起，沒辦法像 fit_calib 那樣各自暴力搜；這是便宜的替代品。"""
    z = np.asarray(z, dtype=np.float64).copy()
    t0 = time.time()
    evals = 0
    for span, step in passes:
        for i, name in enumerate(model.names):
            sub = [pl for pl in pls if name in (pl.a, pl.b)]
            if not sub:
                continue
            for k in range(3):
                best_c = cost_of(z, model, sub, loss)
                evals += 1
                best_d = 0.0
                for d in np.arange(-span, span + 1e-9, step):
                    if abs(d) < 1e-9:
                        continue
                    zz = z.copy()
                    zz[3 * i + k] = float(np.clip(z[3 * i + k] + d, -ANG_BOUND, ANG_BOUND))
                    c = cost_of(zz, model, sub, loss)
                    evals += 1
                    if c < best_c:
                        best_c, best_d = c, d
                z[3 * i + k] = float(np.clip(z[3 * i + k] + best_d, -ANG_BOUND, ANG_BOUND))
    say("  粗搜：%d 次評估，%.1f s" % (evals, time.time() - t0))
    return z


# ---------------------------------------------------------------- 主流程

def selfcal(frames_rgb, start_calib, opts=None, verbose=True):
    """回傳 (calib, report)。report["ok"] 為 False 時 calib = 起始值原封不動。"""
    opts = dict(opts or {})
    opts.setdefault("ego_margin", EGO_MARGIN)
    opts.setdefault("max_range", MAX_RANGE)
    opts.setdefault("levels", LEVELS)
    opts.setdefault("coarse", True)
    opts.setdefault("loss", "soft_l1")
    opts.setdefault("max_nfev", 35)
    opts.setdefault("mode", "scale")
    say = (lambda *a: print(*a, flush=True)) if verbose else (lambda *a: None)
    t_all = time.time()

    geo0 = ag.Geometry(start_calib)
    names = [c.name for c in geo0.cams]
    pairs = [(a, b) for a, b in PAIRS if a in names and b in names]
    report = {"ok": False, "reason": None, "frames": [], "levels": [], "warnings": []}
    if not pairs:
        report["reason"] = "資料不足：校正檔裡沒有可配對的相鄰鏡頭（需要 front/rear + left/right）"
        return json.loads(json.dumps(start_calib)), report

    frames = []
    for i, rgb in enumerate(frames_rgb):
        tag = opts.get("tags", [None] * len(frames_rgb))[i] or ("frame%d" % i)
        f = Frame(rgb, geo0, tag)
        frames.append(f)
        report["frames"].append({"tag": tag, "size": [f.w, f.h],
                                 "valid_frac": {n: round(float(v.valid.mean()), 3)
                                                for n, v in f.views.items()}})
    if len(frames) == 1:
        report["warnings"].append("只有一張圖：容易過擬合到這個場景，建議至少兩張不同地點／角度的畫面")
    say("畫面 %d 張，鏡頭對 %s" % (len(frames), ", ".join("%s-%s" % p for p in pairs)))

    model = Model(start_calib, names, fit_angles=(opts["mode"] == "angles"))
    report["mode"] = opts["mode"]
    z = np.zeros(model.n)
    lo, hi = model.bounds()
    before_metrics = None

    for li, (s, blur_m, sig_m) in enumerate(opts["levels"]):
        cams = model.cams(z)
        pls = []
        skipped = []
        for f in frames:
            for a, b in pairs:
                pl = PairLevel(f, a, b, cams, geo0, s, blur_m, sig_m, opts)
                if pl.ok:
                    pls.append(pl)
                else:
                    skipped.append("%s/%s-%s(n=%d)" % (f.tag, a, b, pl.n))
        lv = {"s": s, "blur_m": blur_m, "sig_m": sig_m, "pairs": len(pls),
              "samples": int(sum(p.n for p in pls)), "skipped": skipped}
        if not pls:
            lv["note"] = "沒有可用的重疊區"
            report["levels"].append(lv)
            if li == 0:
                report["reason"] = ("資料不足：找不到任何兩顆鏡頭都看得到的地面區域"
                                    "（重疊區太小或畫面全被暗角／車身遮住）%s"
                                    % (("；" + ", ".join(skipped)) if skipped else ""))
                return json.loads(json.dumps(start_calib)), report
            say("  第 %d 層沒有可用重疊區，略過" % li)
            continue

        contrast = max(max(p.sd["luma"] for p in pls), 0.0)
        lv["contrast"] = round(contrast, 3)
        if li == 0:
            if contrast < MIN_CONTRAST:
                report["levels"].append(lv)
                report["reason"] = ("資料不足：重疊區地面幾乎沒有紋理（局部對比中位數 %.2f < %.2f），"
                                    "光度一致性無法分辨角度" % (contrast, MIN_CONTRAST))
                return json.loads(json.dumps(start_calib)), report
            before_metrics = {("%s/%s-%s" % (p.frame.tag, p.a, p.b)): p.metrics(cams) for p in pls}

        say("  層 %d：s=%.3f σ_g=%.3f σ_n=%.2f，%d 組重疊區共 %d 點（對比 %.1f）"
            % (li, s, blur_m, sig_m, len(pls), lv["samples"], contrast))
        c0 = cost_of(z, model, pls, opts["loss"])
        t0 = time.time()
        nfev = None
        if model.n == 1:
            # 只校縮放：第一層掃全範圍，之後在上一層結果附近收斂
            if li == 0:
                z, curve = scan_scale(z, model, pls, opts["loss"], lo[-1], hi[-1], 25, say)
                lv["curve"] = [[round(s, 4), round(c, 2)] for s, c in curve]
            else:
                half = 2.0 / F_STEP * 0.02      # ±2% 倍率
                z, _ = scan_scale(z, model, pls, opts["loss"],
                                  max(z[-1] - half, lo[-1]), min(z[-1] + half, hi[-1]), 9, say)
            cost_after = cost_of(z, model, pls, opts["loss"])
        else:
            if li == 0 and opts["coarse"]:
                z = coarse_search(z, model, pls, opts["loss"], say=say)
            obj = Objective(model, pls, opts["loss"], 0.30 if li == 0 else 0.12, lo, hi)
            res = optimize.least_squares(
                obj.res, z, jac=obj.jac, bounds=(lo, hi), method="trf",
                x_scale=1.0, max_nfev=opts["max_nfev"], ftol=1e-6, xtol=1e-7, gtol=1e-10)
            z = np.asarray(res.x, dtype=np.float64)
            nfev = int(obj.nfev)
            cost_after = float(res.cost)
        lv.update({"cost_before": round(c0, 2), "cost_after": round(cost_after, 2),
                   "nfev": nfev, "sec": round(time.time() - t0, 1)})
        at_bound = [("f_scale" if i == model.n - 1 else
                     "%s.%s" % (model.names[i // 3], ANGLES[i % 3]))
                    for i in range(model.n)
                    if z[i] <= lo[i] + 1e-3 or z[i] >= hi[i] - 1e-3]
        lv["at_bound"] = at_bound
        report["levels"].append(lv)
        say("    cost %.1f → %.1f（%.1f s）%s"
            % (c0, cost_after, time.time() - t0,
               ("  觸界: " + ",".join(at_bound)) if at_bound else ""))
        if li == len(opts["levels"]) - 1:
            report["after"] = {("%s/%s-%s" % (p.frame.tag, p.a, p.b)): p.metrics(model.cams(z))
                               for p in pls}
            report["final_pls"] = pls      # 給 debug 用，寫檔前會拿掉

    # 用同一組最細層的重疊區，量校正前 / 校正後的殘差（可直接對比）
    final_pls = report.pop("final_pls", None)
    if final_pls:
        report["residual"] = {
            "before": {("%s/%s-%s" % (p.frame.tag, p.a, p.b)): p.metrics(model.cams(np.zeros(model.n)))
                       for p in final_pls},
            "after": report.pop("after", {}),
        }
        for tag in ("before", "after"):
            d = report["residual"][tag]
            if d:
                report["residual"]["mean_rms_luma_" + tag] = round(
                    float(np.mean([v["rms_luma"] for v in d.values()])), 4)
                report["residual"]["mean_ncc_luma_" + tag] = round(
                    float(np.mean([v["ncc_luma"] for v in d.values()])), 4)
    report.pop("after", None)
    report["coarse_metrics"] = before_metrics
    report["delta"] = model.deltas(z)
    report["z"] = [round(float(t), 4) for t in z]
    report["sec"] = round(time.time() - t_all, 1)
    report["ok"] = True
    return build_output(start_calib, model, z, report), report


def build_output(start_calib, model, z, report):
    out = json.loads(json.dumps(start_calib))
    cams = model.cams(z)
    for n, cam in cams.items():
        d = out["cams"][n]
        d["yaw"] = cam.yaw
        d["pitch"] = cam.pitch
        d["roll"] = cam.roll
        d["fx"] = cam.fx
        d["fy"] = cam.fy
    r = report.get("residual", {})
    out["note"] = ("由 tools/selfcal.py 自我校正（%s；跨鏡頭光度一致性，%d 張畫面，mode=%s，"
                   "焦距倍率 %.4f）。重疊區 NCC %s → %s、RMS %s → %s。"
                   "fx/fy 以象限寬度為單位；角度為度。"
                   % (datetime.date.today().isoformat(), len(report.get("frames", [])),
                      report.get("mode", "scale"), report["delta"]["f_scale"],
                      r.get("mean_ncc_luma_before"), r.get("mean_ncc_luma_after"),
                      r.get("mean_rms_luma_before"), r.get("mean_rms_luma_after")))
    ordered = {}
    for k, v in out.items():
        ordered[k] = v
        if k == "version":
            ordered["calibrated"] = True
    if "calibrated" not in ordered:
        ordered = {"calibrated": True, **ordered}
    return ordered


# ---------------------------------------------------------------- debug 輸出

def seam_overlay(geo, bev, owner):
    """在拼接俯視圖上把鏡頭接縫畫成洋紅線——地面上的線有沒有接起來，看這條線兩側就知道。"""
    img = np.ascontiguousarray(bev.astype(np.uint8)).copy()
    o = owner.astype(np.int16)
    e = np.zeros(o.shape, bool)
    e[:, :-1] |= (o[:, :-1] != o[:, 1:]) & (o[:, :-1] >= 0) & (o[:, 1:] >= 0)
    e[:-1, :] |= (o[:-1, :] != o[1:, :]) & (o[:-1, :] >= 0) & (o[1:, :] >= 0)
    e = ndimage.binary_dilation(e, np.ones((3, 3)))
    img[e] = (img[e] * 0.35 + np.array([255, 0, 255], np.float32) * 0.65).astype(np.uint8)
    return img


def write_debug(dirpath, frames, start_calib, fitted, report):
    os.makedirs(dirpath, exist_ok=True)
    for tag, calib in (("before", start_calib), ("after", fitted)):
        geo = ag.Geometry(calib)
        for f in frames:
            lut, owner = ag.build_bev_lut(geo, f.w, f.h, with_owner=True)
            bev = ag.stitch(f.rgb, lut)
            suffix = ("_" + f.tag) if len(frames) > 1 else ""
            fc.save_rgb(os.path.join(dirpath, "bev_%s%s.png" % (tag, suffix)), bev)
            fc.save_rgb(os.path.join(dirpath, "seam_%s%s.png" % (tag, suffix)),
                        seam_overlay(geo, bev, owner))
    with open(os.path.join(dirpath, "selfcal_report.json"), "w", encoding="utf-8") as fh:
        json.dump(report, fh, ensure_ascii=False, indent=1)


# ---------------------------------------------------------------- CLI

def sidecar_calib(path):
    """車機存圖時旁邊那個 .json 裡的 calib（= 拍攝當下車上用的值），當起始值最準。"""
    j = os.path.splitext(path)[0] + ".json"
    if os.path.exists(j):
        try:
            with open(j, encoding="utf-8") as f:
                d = json.load(f)
            if isinstance(d.get("calib"), dict) and "cams" in d["calib"]:
                return d["calib"], j
        except (ValueError, OSError):
            pass
    return None, None


def main(argv=None):
    ap = argparse.ArgumentParser(
        description="自我校正：只用原始四分割畫面擬合魚眼外參（跨鏡頭光度一致性）")
    ap.add_argument("raw", nargs="+", help="Camera2 1920x1080 2x2 原始畫面（JPEG/PNG），可給多張")
    ap.add_argument("-o", "--output", required=True, help="輸出的校正檔")
    ap.add_argument("--calib", help="起始校正檔（預設：第一張圖旁的 .json，再退回 assets/avm_calib.json）")
    ap.add_argument("--debug-dir", help="輸出校正前/後的拼接俯視圖、接縫疊圖與 selfcal_report.json")
    ap.add_argument("--ego-margin", type=float, default=EGO_MARGIN,
                    help="車身矩形外擴多少才取樣（m，預設 %.2f）" % EGO_MARGIN)
    ap.add_argument("--max-range", type=float, default=MAX_RANGE,
                    help="離車身多遠以內才取樣（m，預設 %.1f）" % MAX_RANGE)
    ap.add_argument("--mode", choices=("scale", "angles"), default="scale",
                    help="scale=只校四顆共用的焦距倍率（搭配 calib_from_lua.py 的原廠外參，預設）；"
                         "angles=連 12 個角度一起擬合（沒有原廠檔時的備案，很難解）")
    ap.add_argument("--no-coarse", action="store_true", help="跳過粗搜（起始值已經很接近時可以省時間）")
    ap.add_argument("--max-nfev", type=int, default=35, help="每層最多幾次殘差評估（預設 35）")
    ap.add_argument("-q", "--quiet", action="store_true")
    a = ap.parse_args(argv)

    if a.calib:
        with open(a.calib, encoding="utf-8") as f:
            start = json.load(f)
        src = a.calib
    else:
        start, src = sidecar_calib(a.raw[0])
        if start is None:
            with open(ag.DEFAULT_CALIB, encoding="utf-8") as f:
                start = json.load(f)
            src = ag.DEFAULT_CALIB
    if not a.quiet:
        print("起始校正檔：%s" % src)

    rgbs, tags = [], []
    for p in a.raw:
        rgb = fc.load_rgb(p)
        if rgb.shape[:2] != (1080, 1920):
            print("注意：%s 是 %dx%d，不是 1920x1080；照實際大小切四象限"
                  % (p, rgb.shape[1], rgb.shape[0]), file=sys.stderr)
        rgbs.append(rgb)
        tags.append(os.path.splitext(os.path.basename(p))[0])

    opts = {"ego_margin": a.ego_margin, "max_range": a.max_range, "mode": a.mode,
            "coarse": not a.no_coarse, "max_nfev": a.max_nfev, "tags": tags}
    fitted, report = selfcal(rgbs, start, opts, verbose=not a.quiet)
    report["args"] = {"raw": list(a.raw), "calib": src, "ego_margin": a.ego_margin,
                      "max_range": a.max_range}

    if not report["ok"]:
        print("校正失敗：%s" % report["reason"], file=sys.stderr)
        fitted = json.loads(json.dumps(start))
        ordered = {}
        for k, v in fitted.items():
            ordered[k] = v
            if k == "version":
                ordered["calibrated"] = False
        fitted = ordered if "calibrated" in ordered else {"calibrated": False, **fitted}

    os.makedirs(os.path.dirname(os.path.abspath(a.output)) or ".", exist_ok=True)
    with open(a.output, "w", encoding="utf-8") as f:
        f.write(fc.format_calib(fitted))
    with open(a.output, encoding="utf-8") as f:
        ag.Geometry(json.load(f))      # 確認讀得回來

    if a.debug_dir:
        geo0 = ag.Geometry(start)
        frames = [Frame(r, geo0, t) for r, t in zip(rgbs, tags)]
        write_debug(a.debug_dir, frames, start, fitted, report)

    if report["ok"]:
        d = report["delta"]
        print("→ %s（%.1f s，mode=%s）" % (a.output, report["sec"], a.mode))
        for n in d:
            if n == "f_scale":
                continue
            print("  %-5s Δyaw %+6.2f°  Δpitch %+6.2f°  Δroll %+6.2f°"
                  % (n, d[n]["yaw"], d[n]["pitch"], d[n]["roll"]))
        print("  焦距倍率 %.4f（fx 全部乘這個數）" % d["f_scale"])
        r = report.get("residual", {})
        print("  重疊區 luma RMS %.3f → %.3f，NCC %.3f → %.3f"
              % (r.get("mean_rms_luma_before", float("nan")),
                 r.get("mean_rms_luma_after", float("nan")),
                 r.get("mean_ncc_luma_before", float("nan")),
                 r.get("mean_ncc_luma_after", float("nan"))))
        for w in report["warnings"]:
            print("警告：" + w, file=sys.stderr)
        return 0
    print("→ %s（原封不動照抄起始值，calibrated=false）" % a.output, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
