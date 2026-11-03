#!/usr/bin/env python3
"""
用停車時拍的兩張圖，擬合 avm_calib.json 四顆鏡頭的參數（模型與 tools/avm_geometry.py、AvmGeometry.kt 相同）。

  RAW    Camera2 id "2" 的 1920x1080 原始 2x2 拼接畫面（q0 左上=前、q1 右上=後、q2 左下=左、q3 右下=右）
  SCREEN 原廠「全景360」的螢幕截圖；它的俯視圖（BEV）由安裝師傅校正過，當作公制真值

用法：
    python3 tools/fit_calib.py RAW.jpg SCREEN.png --roi 0.1,0,0.4,1 --wm 8 [--flip] \\
        [--calib start.json] -o fitted.json [--debug-dir DIR]

  --roi l,t,r,b  截圖裡俯視圖的範圍（螢幕比例，跟 App 的 ROI 設定同一套換算）
  --wm M         這個範圍橫向代表幾公尺（直向依像素比例推得，像素是正方形）
  --flip         俯視圖車頭朝下（整張轉 180°）
  --calib        起始值（預設 app/src/main/assets/avm_calib.json）；只改 cams 的
                 pitch/roll/yaw/pos/fx/fy/cx/cy（選配 k1），其餘欄位（car、bev、quad、mirror…）照抄
  --debug-dir    輸出對位前後的俯視圖、棋盤交錯比對、邊緣疊圖與 fit_report.json

截圖的假設：俯視圖以車身中心為原點、上方 = 車頭；中間的車子圖示用 --car-margin 外擴遮掉；
黑色（R+G+B < 24）視為無畫面。顏色、亮度跟原始畫面不同沒關係（比的是局部正規化後的灰階紋理）。

拍攝建議：P 檔停好（不要在倒車檔，截圖才不會有倒車導引線；原始畫面本來就沒有疊圖），
兩張圖同一時間拍、車與光線不動；周圍 3 m 內地面要有紋理（停車格線、地磚、路緣），
不要有其他車或人（立體物在俯視圖裡會被拉長，各鏡頭看起來不一樣）。

方法（每顆鏡頭獨立擬合）：
  1. 地面網格上，用鏡頭參數把原始畫面投到地面（ground→pixel），依該點的局部放大率挑原始畫面的
     模糊層（像 mipmap），讓兩邊在地面上的模糊程度一樣；截圖俯視圖以同樣的地面網格取樣。
  2. 兩邊都做局部正規化（減局部平均、除局部標準差），殘差 = 兩者相減；粗層 soft-L1、
     細層 Cauchy（UI 疊圖、導引線、立體物等離群值的影響較小）。
  3. 粗到細 5 層（見 BASE_LEVELS）：最粗層地面模糊 0.24 m，暴力搜 pitch/yaw/roll × 焦距 3 檔，
     前 8 名用 scipy least_squares（TRF、有界）調角度；接著兩層保留 4 個不同的候選、全部參數一起調，
     在 0.08 m 層選出最好的一個，再到 0.04 m、~0.025 m 用中央差分 Jacobian 收斂。
  4. 每個參數對起始值有弱先驗（相機高度、焦距、pitch、cy 會互相抵換，先驗只在資料分不出來的方向起作用；
     這些方向對地面投影的影響本來就小，所以地面誤差仍然準）。
需要 numpy、scipy、Pillow。在 Apple M4 上約 30–60 秒。
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

DEG = math.pi / 180.0

# 參數：(單位步長, 相對起始值的上下界, 先驗 σ)，自然單位（度、公尺、ln 比例、象限比例）
PARAM_SPEC = {
    "pitch": (2.0, 20.0, 15.0),
    "roll": (2.0, 12.0, 8.0),
    "yaw": (2.0, 12.0, 8.0),
    "px": (0.05, 0.30, 0.15),
    "py": (0.05, 0.40, 0.20),
    "pz": (0.05, 0.30, 0.15),
    "f": (0.02, 0.25, 0.12),      # ln(fx / fx0)，fy 同步
    "cx": (0.01, 0.08, 0.04),
    "cy": (0.01, 0.08, 0.04),
    "k1": (0.01, 0.12, 0.04),
    "fyr": (0.005, 0.06, 0.03),   # ln(fy/fx) 相對起始值（--separate-fy）
}
# 最粗層只調角度（焦距維持格點值）；高度、焦距一起放會沿退化谷滑走，候選全擠到同一處
COARSE_FREE = ("pitch", "roll", "yaw")

RAW_SIGMAS = (0.0, 0.7, 1.4, 2.8, 5.6, 11.2, 22.4, 44.8)   # 原始畫面模糊層（像素）
EDGE_PX = 6.0          # 取樣點離象限邊緣至少幾像素才選進來
THETA_MARGIN = 3.0     # 離 maxDeg 至少幾度
SECTOR_DEG = 12.0      # θ 比其他鏡頭大不超過這麼多度才算這顆鏡頭的區域
ROBUST_C = 1.0         # robust 轉折點（特徵約為單位變異）
DATA_WEIGHT = 2000.0   # 資料項總權重（相對先驗）
MIN_SAMPLES = 150


def luma(rgb):
    rgb = np.asarray(rgb, dtype=np.float32)
    return (77.0 * rgb[..., 0] + 150.0 * rgb[..., 1] + 29.0 * rgb[..., 2]) / 256.0


def load_rgb(path):
    from PIL import Image
    return np.asarray(Image.open(path).convert("RGB"))


def save_rgb(path, arr):
    from PIL import Image
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    Image.fromarray(np.ascontiguousarray(np.clip(arr, 0, 255).astype(np.uint8))).save(path)


def project(cam, gx, gz, qw, qh):
    """地面 → 象限像素 (u, v, θ)；與 ag.ground_to_pixel 同公式，少算 margin 比較快。"""
    m = cam.m
    dx = gx - cam.px
    dy = -cam.py
    dz = gz - cam.pz
    x = m[0][0] * dx + m[1][0] * dy + m[2][0] * dz
    y = m[0][1] * dx + m[1][1] * dy + m[2][1] * dz
    z = m[0][2] * dx + m[1][2] * dy + m[2][2] * dz
    r = np.sqrt(x * x + y * y)
    th = np.arctan2(r, z)
    if cam.k1 != 0.0 or cam.k2 != 0.0:
        t2 = th * th
        rd = th * (1.0 + cam.k1 * t2 + cam.k2 * t2 * t2)
    else:
        rd = th
    pos = r > 0.0
    k = np.where(pos, rd / np.where(pos, r, 1.0), 0.0)
    u = cam.cx * qw + cam.fx * qw * k * x
    v = cam.cy * qh + cam.fy * qw * k * y
    if cam.mirror:
        u = qw - u
    return u, v, th


def valid_proj(cam, u, v, th, qw, qh, edge=0.0, th_margin_deg=0.0):
    return ((th <= cam.max_rad - th_margin_deg * DEG) & (u >= edge) & (u < qw - edge)
            & (v >= edge) & (v < qh - edge))


# ---------------------------------------------------------------- 參數向量

def apply_params(d0, z):
    """z（dict：名稱 → 正規化值，相對 d0）→ 新的鏡頭 dict。"""
    d = dict(d0)
    d["pos"] = list(d0["pos"])
    for n, zi in z.items():
        v = zi * PARAM_SPEC[n][0]
        if n in ("pitch", "roll", "yaw", "cx", "cy", "k1"):
            d[n] = float(d0.get(n, 0.0)) + v
        elif n == "px":
            d["pos"][0] = float(d0["pos"][0]) + v
        elif n == "py":
            d["pos"][1] = float(d0["pos"][1]) + v
        elif n == "pz":
            d["pos"][2] = float(d0["pos"][2]) + v
    ef = math.exp(z.get("f", 0.0) * PARAM_SPEC["f"][0])
    d["fx"] = float(d0.get("fx", 0.265)) * ef
    d["fy"] = float(d0.get("fy", 0.265)) * ef * math.exp(z.get("fyr", 0.0) * PARAM_SPEC["fyr"][0])
    return d


# ---------------------------------------------------------------- 截圖俯視圖

class ScreenBev:
    def __init__(self, rgb, roi, wm, flip, car_len, car_wid, car_margin):
        H, W = rgb.shape[:2]
        L = min(max(int(roi[0] * W), 0), W - 2)
        T = min(max(int(roi[1] * H), 0), H - 2)
        R = min(max(int(roi[2] * W), 2), W)
        B = min(max(int(roi[3] * H), 2), H)
        if R - L <= 8 or B - T <= 8:
            raise ValueError("ROI 太小：%r → 像素 %d,%d,%d,%d" % (roi, L, T, R, B))
        crop = np.asarray(rgb[T:B, L:R, :3])
        if flip:
            crop = crop[::-1, ::-1]
        self.roi_px = (L, T, R, B)
        self.rgb = np.ascontiguousarray(crop)
        self.h, self.w = self.rgb.shape[:2]
        self.wm = float(wm)
        self.mpp = self.wm / self.w
        self.hm = self.h * self.mpp
        valid = self.rgb.astype(np.int32).sum(axis=2) >= 24
        self.valid = ndimage.binary_erosion(valid, iterations=2, border_value=0)
        self.gray = luma(self.rgb)
        self.car_hw = car_wid / 2.0 + car_margin
        self.car_hl = car_len / 2.0 + car_margin
        self._blur = {}

    def to_rc(self, gx, gz):
        """地面 → 截圖俯視圖的 (row, col)，整數 = 像素中心（map_coordinates 慣例）。"""
        return self.h / 2.0 - 0.5 - gz / self.mpp, gx / self.mpp + self.w / 2.0 - 0.5

    def pixel_ground(self):
        gx = (np.arange(self.w) + 0.5 - self.w / 2.0) * self.mpp
        gz = (self.h / 2.0 - np.arange(self.h) - 0.5) * self.mpp
        return np.meshgrid(gx, gz)

    def car_mask(self, gx, gz):
        return (np.abs(gx) <= self.car_hw) & (np.abs(gz) <= self.car_hl)

    def blurred(self, blur_m):
        """以地面 σ = blur_m（m）模糊（只用有效像素），回傳 (灰階, 有效比例)。"""
        if blur_m not in self._blur:
            sig = blur_m / self.mpp
            vf = self.valid.astype(np.float32)
            if sig < 0.35:
                self._blur[blur_m] = (self.gray, vf)
            else:
                num = ndimage.gaussian_filter(self.gray * vf, sig, mode="constant", truncate=3.0)
                den = ndimage.gaussian_filter(vf, sig, mode="constant", truncate=3.0)
                self._blur[blur_m] = (num / np.maximum(den, 1e-6), den)
        return self._blur[blur_m]

    def level_grid(self, s, blur_m):
        nx = int(math.floor(self.wm / s))
        nz = int(math.floor(self.hm / s))
        gx = (np.arange(nx) + 0.5 - nx / 2.0) * s
        gz = (nz / 2.0 - np.arange(nz) - 0.5) * s
        GX, GZ = np.meshgrid(gx, gz)
        img, den = self.blurred(blur_m)
        rc = self.to_rc(GX, GZ)
        val = ndimage.map_coordinates(img, rc, order=1, mode="nearest")
        ok = ndimage.map_coordinates(den, rc, order=1, mode="constant", cval=0.0) >= 0.97
        ok &= ~self.car_mask(GX, GZ)
        return GX, GZ, val.astype(np.float32), ok


# ---------------------------------------------------------------- 特徵

def local_norm(X, M, sig, eps=None):
    """遮罩內局部正規化：(X − 局部平均) / sqrt(局部變異 + eps²)。eps=None 時回傳 (特徵, 建議 eps)。"""
    Mf = M.astype(np.float32)
    kw = dict(mode="constant", truncate=3.0)
    den = ndimage.gaussian_filter(Mf, sig, **kw) + 1e-6
    mu = ndimage.gaussian_filter(X * Mf, sig, **kw) / den
    D = (X - mu) * Mf
    var = ndimage.gaussian_filter(D * D, sig, **kw) / den
    if eps is None:
        sd = np.sqrt(var[M])
        eps = max(1.0, 0.25 * float(np.median(sd))) if sd.size else 1.0
        return D / np.sqrt(var + eps * eps), eps
    return D / np.sqrt(var + eps * eps)


def robust(e, c=ROBUST_C, kind="soft_l1"):
    """回傳 r，使 r² = 2ρ(e)。soft-L1：大誤差 ≈ sqrt(2c|e|)；cauchy：大誤差只長 log，UI 疊圖等離群值影響更小。"""
    if kind == "cauchy":
        return np.sign(e) * c * np.sqrt(np.log1p((e / c) ** 2))
    return np.sign(e) * c * np.sqrt(2.0 * (np.sqrt(1.0 + (e / c) ** 2) - 1.0))


# ---------------------------------------------------------------- 單顆鏡頭擬合

class CamFitter:
    def __init__(self, name, d0, raw_gray_quad, opts):
        self.name = name
        self.d0 = d0
        self.qh, self.qw = raw_gray_quad.shape
        self.stack = [raw_gray_quad if s == 0 else
                      ndimage.gaussian_filter(raw_gray_quad, s, mode="nearest", truncate=3.0)
                      for s in RAW_SIGMAS]
        names = ["pitch", "roll", "yaw", "px", "py", "pz", "f", "cx", "cy"]
        if opts.get("fit_k1"):
            names.append("k1")
        if opts.get("separate_fy"):
            names.append("fyr")
        self.names = names
        self.z = {n: 0.0 for n in names}
        self.opts = opts
        self.loss = "soft_l1"
        self.log = []
        self.lv = None

    def cam(self, z=None):
        return ag.Cam(self.name, apply_params(self.d0, self.z if z is None else z))

    # ---- 每層的取樣設定
    def setup_level(self, s, blur_m, GX, GZ, valB, region, sig_l_m):
        rows = np.where(region.any(axis=1))[0]
        cols = np.where(region.any(axis=0))[0]
        if rows.size == 0 or region.sum() < MIN_SAMPLES:
            self.lv = None
            return False
        sig = sig_l_m / s
        pad = int(math.ceil(2.0 * sig))
        r0, r1 = max(rows[0] - pad, 0), min(rows[-1] + pad + 1, GX.shape[0])
        c0, c1 = max(cols[0] - pad, 0), min(cols[-1] + pad + 1, GX.shape[1])
        M = region[r0:r1, c0:c1]
        gx = GX[r0:r1, c0:c1][M]
        gz = GZ[r0:r1, c0:c1][M]
        cam = self.cam()
        # 局部放大率（像素/公尺）→ 原始畫面要先模糊多少，地面上的模糊才跟截圖一樣是 blur_m
        h = 0.5 * s
        ua, va, _ = project(cam, gx + h, gz, self.qw, self.qh)
        ub, vb, _ = project(cam, gx - h, gz, self.qw, self.qh)
        uc, vc, _ = project(cam, gx, gz + h, self.qw, self.qh)
        ud, vd, _ = project(cam, gx, gz - h, self.qw, self.qh)
        det = np.abs((ua - ub) * (vc - vd) - (uc - ud) * (va - vb)) / (2 * h) ** 2
        need = blur_m * np.sqrt(det)
        idx = np.interp(need, RAW_SIGMAS, np.arange(len(RAW_SIGMAS), dtype=np.float64))
        lo = np.minimum(np.floor(idx).astype(np.int64), len(RAW_SIGMAS) - 2)
        groups = [(k, np.where(lo == k)[0]) for k in range(len(RAW_SIGMAS) - 1)]
        groups = [(k, g) for k, g in groups if g.size]
        lv = {"s": s, "M": M, "gx": gx, "gz": gz, "sig": sig, "groups": groups,
              "w": (idx - lo).astype(np.float32), "box": (r0, r1, c0, c1), "n": int(M.sum()),
              "buf": np.zeros(M.shape, dtype=np.float32)}
        XB = np.zeros(M.shape, dtype=np.float32)
        XB[M] = valB[r0:r1, c0:c1][M]
        FB, epsB = local_norm(XB, M, sig)
        lv["FB"] = FB[M]
        lv["epsB"] = epsB
        self.lv = lv
        _, epsA = local_norm(self._sample_grid(cam)[0], M, sig)
        lv["epsA"] = epsA
        lv["wdata"] = math.sqrt(DATA_WEIGHT / lv["n"])
        return True

    def _sample_grid(self, cam):
        lv = self.lv
        u, v, th = project(cam, lv["gx"], lv["gz"], self.qw, self.qh)
        rr = v - 0.5
        cc = u - 0.5
        vals = np.empty(lv["n"], dtype=np.float32)
        w = lv["w"]
        for k, g in lv["groups"]:
            c = np.vstack((rr[g], cc[g]))
            a = ndimage.map_coordinates(self.stack[k], c, order=1, mode="nearest")
            b = ndimage.map_coordinates(self.stack[k + 1], c, order=1, mode="nearest")
            vals[g] = a + (b - a) * w[g]
        X = lv["buf"]
        X[lv["M"]] = vals
        ok = valid_proj(cam, u, v, th, self.qw, self.qh)
        return X, ok

    def features(self, z):
        cam = self.cam(z)
        X, ok = self._sample_grid(cam)
        lv = self.lv
        FA = local_norm(X, lv["M"], lv["sig"], lv["epsA"])[lv["M"]]
        return FA, ok

    def ncc(self, z):
        FA, ok = self.features(z)
        FB = self.lv["FB"]
        den = math.sqrt(float(np.dot(FA, FA)) * float(np.dot(FB, FB))) + 1e-9
        return float(np.dot(FA, FB)) / den, float(ok.mean())

    def residuals(self, z):
        FA, _ = self.features(z)
        lv = self.lv
        r_data = robust(FA - lv["FB"], ROBUST_C, self.loss) * lv["wdata"]
        r_prior = np.array([z[n] * PARAM_SPEC[n][0] / PARAM_SPEC[n][2] for n in self.names])
        return np.concatenate((r_data.astype(np.float64), r_prior))

    def cost(self, z):
        r = self.residuals(z)
        return 0.5 * float(np.dot(r, r))

    # ---- 最粗層：暴力搜 pitch / yaw / roll / 焦距
    def coarse_search(self, top_k=None):
        t0 = time.time()
        top_k = top_k or self.opts.get("top_k", 8)
        fs = self.opts.get("grid_f", (-0.09, 0.0, 0.09))
        cands = []
        for df in fs:
            for dp in np.arange(-16.0, 16.01, 2.0):
                for dy in np.arange(-10.0, 10.01, 2.5):
                    for dr in np.arange(-9.0, 9.01, 3.0):
                        z = dict(self.z)
                        z["pitch"] = self.z["pitch"] + dp / PARAM_SPEC["pitch"][0]
                        z["yaw"] = self.z["yaw"] + dy / PARAM_SPEC["yaw"][0]
                        z["roll"] = self.z["roll"] + dr / PARAM_SPEC["roll"][0]
                        z["f"] = self.z["f"] + df / PARAM_SPEC["f"][0]
                        c, frac = self.ncc(z)
                        score = c - 2.0 * max(0.0, 0.85 - frac)
                        cands.append((score, dp, dy, dr, df, z))
        cands.sort(key=lambda t: -t[0])
        picked = []
        for c in cands:
            if all(abs(c[1] - p[1]) > 3.0 or abs(c[2] - p[2]) > 3.0 or abs(c[3] - p[3]) > 4.0
                   or abs(c[4] - p[4]) > 0.05 for p in picked):
                picked.append(c)
            if len(picked) >= top_k:
                break
        self.log.append({"stage": "grid", "evals": len(cands), "sec": round(time.time() - t0, 2),
                         "best": [[round(float(v), 3) for v in p[:5]] for p in picked]})
        return [p[5] for p in picked]

    def refine(self, z0, free, max_nfev, diff_step, jac="2-point"):
        base = dict(z0)
        names = [n for n in self.names if n in free]
        lo = np.array([-PARAM_SPEC[n][1] / PARAM_SPEC[n][0] - base[n] for n in names])
        hi = np.array([PARAM_SPEC[n][1] / PARAM_SPEC[n][0] - base[n] for n in names])
        lo = np.minimum(lo, -1e-6)
        hi = np.maximum(hi, 1e-6)

        def unpack(dv):
            z = dict(base)
            for n, d in zip(names, dv):
                z[n] = base[n] + float(d)
            return z

        res = optimize.least_squares(lambda dv: self.residuals(unpack(dv)), np.zeros(len(names)),
                                     jac=jac, diff_step=diff_step, bounds=(lo, hi),
                                     method="trf", x_scale=1.0, max_nfev=max_nfev,
                                     ftol=1e-7, xtol=1e-6, gtol=1e-10)
        z = unpack(res.x)
        at_bound = [n for n, d, a, b in zip(names, res.x, lo, hi) if d <= a + 1e-3 or d >= b - 1e-3]
        return z, float(res.cost), int(res.nfev), at_bound


def compute_regions(scr, s, blur_m, cams, qw, qh):
    """每顆鏡頭在這一層要比對的地面網格點（截圖有效、不在車子圖示、這顆鏡頭看得到且接近它的扇區）。"""
    GX, GZ, valB, okB = scr.level_grid(s, blur_m)
    ths = {}
    oks = {}
    for name, cam in cams.items():
        u, v, th = project(cam, GX, GZ, qw, qh)
        ok = valid_proj(cam, u, v, th, qw, qh, edge=EDGE_PX, th_margin_deg=THETA_MARGIN)
        oks[name] = ok
        ths[name] = np.where(ok, th, np.inf)
    regions = {}
    for name in cams:
        others = [ths[n] for n in cams if n != name]
        best_other = np.minimum.reduce(others) if others else np.full(GX.shape, np.inf)
        regions[name] = okB & oks[name] & (ths[name] <= best_other + SECTOR_DEG * DEG)
    return GX, GZ, valB, regions


# 層級：(取樣間距 m, 兩邊的地面模糊 σ m, 局部正規化 σ m, 階段)
#   grid  暴力搜 + 多起點 LS（只調 COARSE_FREE）；模糊很大，盆地夠寬，低紋理（只有幾條線）也接得住
#   multi 每個候選全部參數微調，保留 cost 最好且彼此不同的 KEEP 個；連續 multi 的最後一層只留一個
#   one   單一解微調
BASE_LEVELS = (
    (0.16, 0.24, 0.90, "grid"),
    (0.16, 0.08, 0.64, "multi"),
    (0.08, 0.04, 0.32, "multi"),
    (0.04, 0.02, 0.25, "one"),
)
KEEP = 4


def level_schedule(scr_mpp):
    lv = list(BASE_LEVELS)
    fin = max(0.025, 1.2 * scr_mpp)
    if fin < 0.035:
        lv.append((round(fin, 4), round(fin / 2, 5), 0.25, "one"))
    return lv


def _same_solution(za, zb):
    S = PARAM_SPEC
    for n in ("pitch", "roll", "yaw"):
        if abs(za[n] - zb[n]) * S[n][0] > 0.5:
            return False
    if abs(za["f"] - zb["f"]) * S["f"][0] > 0.01:
        return False
    return all(abs(za[n] - zb[n]) * S[n][0] <= 0.02 for n in ("px", "py", "pz"))


def _distinct(results, keep):
    out = []
    for r in results:
        if all(not _same_solution(r[1], o[1]) for o in out):
            out.append(r)
        if len(out) >= keep:
            break
    return out


def fit(raw_rgb, screen_rgb, roi, wm, flip, start_calib, opts=None, verbose=True):
    """回傳 (fitted_calib, report)。opts: fit_k1, separate_fy, car_margin, cams(名稱清單), levels。"""
    opts = dict(opts or {})
    t_all = time.time()
    say = (lambda *a: print(*a, flush=True)) if verbose else (lambda *a: None)
    geo0 = ag.Geometry(start_calib)
    H, W = raw_rgb.shape[:2]
    qw, qh = W // 2, H // 2
    gray = luma(raw_rgb)
    scr = ScreenBev(screen_rgb, roi, wm, flip, geo0.car_length, geo0.car_width,
                    opts.get("car_margin", 0.3))
    say("截圖俯視圖 %dx%d px（ROI 像素 %s），%.2f x %.2f m，%.4f m/px，有效 %.0f%%"
        % (scr.w, scr.h, scr.roi_px, scr.wm, scr.hm, scr.mpp, 100.0 * scr.valid.mean()))
    want = opts.get("cams") or [c.name for c in geo0.cams]
    fitters = {}
    for c in geo0.cams:
        if c.name not in want:
            continue
        qx0, qy0 = (c.quad % 2) * qw, (c.quad // 2) * qh
        d0 = start_calib["cams"][c.name]
        fitters[c.name] = CamFitter(c.name, d0, np.ascontiguousarray(gray[qy0:qy0 + qh, qx0:qx0 + qw]),
                                    opts)
    levels = opts.get("levels") or level_schedule(scr.mpp)
    say("層級（間距 m, 模糊 m, 正規化 m, 階段）：%s" % (levels,))
    report = {"levels": levels, "screen": {"w": scr.w, "h": scr.h, "mpp": scr.mpp, "roi_px": scr.roi_px,
                                           "wm": scr.wm, "hm": scr.hm, "flip": bool(flip)},
              "cams": {}}
    cands = {name: [dict(ft.z)] for name, ft in fitters.items()}
    for li, (s, blur, sig_l, stage) in enumerate(levels):
        cams_now = {}
        for c in geo0.cams:
            cams_now[c.name] = fitters[c.name].cam(cands[c.name][0]) if c.name in fitters else c
        GX, GZ, valB, regions = compute_regions(scr, s, blur, cams_now, qw, qh)
        nxt = levels[li + 1][3] if li + 1 < len(levels) else None
        keep = opts.get("keep", KEEP) if (stage == "grid" or (stage == "multi" and nxt == "multi")) else 1
        for name, ft in fitters.items():
            t0 = time.time()
            ft.z = cands[name][0]
            # 粗層 soft-L1（盆地寬）；細層 Cauchy（UI 疊圖、導引線等離群值的影響只長 log）
            ft.loss = opts.get("loss_fine", "cauchy") if s < 0.16 else opts.get("loss", "soft_l1")
            if not ft.setup_level(s, blur, GX, GZ, valB, regions[name], sig_l):
                say("  [%s] s=%.3f 可比對的點太少（%d），略過" % (name, s, int(regions[name].sum())))
                continue
            ncc0, _ = ft.ncc(ft.z)
            results = []
            if stage == "grid":
                for z0 in ft.coarse_search():
                    z, cst, nfev, ab = ft.refine(z0, opts.get("coarse_free", COARSE_FREE), max_nfev=40,
                                                 diff_step=0.05)
                    results.append((cst, z, nfev, ab))
            else:
                ds = 0.04 if s >= 0.08 else 0.02
                # 細層的谷很平（焦距/高度/pitch/cy 互相抵換）：中央差分的 Jacobian 才走得動，前向差分會提早停
                fine = s < 0.08
                for z0 in cands[name]:
                    z, cst, nfev, ab = ft.refine(z0, ft.names, max_nfev=100 if fine else 60, diff_step=ds,
                                                 jac="3-point" if fine else "2-point")
                    results.append((cst, z, nfev, ab))
            results.sort(key=lambda r: r[0])
            kept = _distinct(results, keep)
            cands[name] = [r[1] for r in kept]
            cst, ft.z, nfev, ab = kept[0]
            ncc1, frac = ft.ncc(ft.z)
            ent = {"s": s, "blur": blur, "stage": stage, "n": ft.lv["n"], "ncc_before": round(ncc0, 4),
                   "ncc": round(ncc1, 4), "cost": round(cst, 3), "nfev": nfev, "at_bound": ab,
                   "valid_frac": round(frac, 3), "sec": round(time.time() - t0, 2),
                   "cands": [round(r[0], 2) for r in results]}
            ft.log.append(ent)
            say("  [%-5s] s=%.3f σ=%.3f n=%6d  NCC %.3f → %.3f  候選 %d→%d  %.1fs%s"
                % (name, s, blur, ft.lv["n"], ncc0, ncc1, len(results), len(kept), time.time() - t0,
                   ("  觸界:" + ",".join(ab)) if ab else ""))

    out = json.loads(json.dumps(start_calib))
    for name, ft in fitters.items():
        d = apply_params(ft.d0, ft.z)
        dst = out["cams"][name]
        for k in ("pitch", "roll", "yaw", "cx", "cy", "fx", "fy"):
            dst[k] = d[k]
        if "k1" in ft.names:
            dst["k1"] = d["k1"]
        dst["pos"] = list(d["pos"])
        fin = [e for e in ft.log if "ncc" in e]
        last = fin[-1] if fin else {}
        report["cams"][name] = {
            "ncc": last.get("ncc"), "valid_frac": last.get("valid_frac"),
            "at_bound": last.get("at_bound", []),
            "delta": {n: round(ft.z[n] * PARAM_SPEC[n][0], 5) for n in ft.names},
            "log": ft.log,
        }
    report["sec"] = round(time.time() - t_all, 1)
    scores = " / ".join("%s %.2f" % (n, report["cams"][n]["ncc"] or 0.0) for n in fitters)
    out["note"] = ("由 tools/fit_calib.py 擬合（%s；roi=%s，wm=%g，flip=%s；相似度 %s）。"
                   "fx/fy 以象限寬度為單位；角度為度。"
                   % (datetime.date.today().isoformat(), ",".join("%g" % v for v in roi), wm,
                      "是" if flip else "否", scores))
    return out, report


# ---------------------------------------------------------------- 輸出

def _num(v, nd):
    v = round(float(v), nd)
    if v == 0:
        v = 0.0
    if float(v).is_integer() and abs(v) < 1e6:
        return "%d" % int(v) if nd == 0 else ("%.1f" % v)
    return repr(v)


def format_calib(calib):
    """排版跟 assets/avm_calib.json 一樣（每顆鏡頭三行）。"""
    nd = {"cx": 6, "cy": 6, "fx": 6, "fy": 6, "k1": 6, "k2": 6, "maxDeg": 3,
          "yaw": 3, "pitch": 3, "roll": 3}

    def val(k, v):
        if isinstance(v, bool) or v is None or isinstance(v, str):
            return json.dumps(v, ensure_ascii=False)
        if k == "pos":
            return "[" + ", ".join(_num(t, 4) for t in v) + "]"
        if isinstance(v, int):
            return str(v)
        if isinstance(v, (int, float)):
            return _num(v, nd.get(k, 6))
        return json.dumps(v, ensure_ascii=False)

    groups = (("quad", "mirror", "cx", "cy", "fx", "fy"), ("k1", "k2", "maxDeg"),
              ("pos", "yaw", "pitch", "roll"))
    lines = ["{"]
    keys = list(calib.keys())
    for ki, key in enumerate(keys):
        comma = "," if ki < len(keys) - 1 else ""
        v = calib[key]
        if key == "cams":
            lines.append('  "cams": {')
            names = list(v.keys())
            for ci, name in enumerate(names):
                d = v[name]
                rest = [k for k in d if not any(k in g for g in groups)]
                rows = [[k for k in g if k in d] for g in groups]
                rows[-1] += rest
                rows = [r for r in rows if r]
                head = '    "%s": { ' % name
                pad = " " * len(head)
                for ri, r in enumerate(rows):
                    body = ", ".join('"%s": %s' % (k, val(k, d[k])) for k in r)
                    end = " }" + ("," if ci < len(names) - 1 else "") if ri == len(rows) - 1 else ","
                    lines.append((head if ri == 0 else pad) + body + end)
            lines.append("  }" + comma)
        elif isinstance(v, dict):
            body = ", ".join('"%s": %s' % (k, val(k, t)) for k, t in v.items())
            lines.append('  "%s": { %s }%s' % (key, body, comma))
        else:
            lines.append('  "%s": %s%s' % (key, json.dumps(v, ensure_ascii=False), comma))
    lines.append("}")
    return "\n".join(lines) + "\n"


def render_ours(raw_rgb, calib, scr):
    """用校正檔把原始畫面拼成跟截圖俯視圖同範圍、同解析度的 BEV（每點取 θ 最小的鏡頭）。"""
    geo = ag.Geometry(calib)
    H, W = raw_rgb.shape[:2]
    qw, qh = W // 2, H // 2
    gx, gz = scr.pixel_ground()
    out = np.zeros(gx.shape + (3,), dtype=np.float32)
    best = np.full(gx.shape, np.inf)
    for cam in geo.cams:
        u, v, th, ok, _ = ag.ground_to_pixel(cam, gx, gz, qw, qh)
        take = ok & (th < best)
        if not take.any():
            continue
        qx0, qy0 = (cam.quad % 2) * qw, (cam.quad // 2) * qh
        rc = np.vstack((v[take] - 0.5 + qy0, u[take] - 0.5 + qx0))
        for ch in range(3):
            out[..., ch][take] = ndimage.map_coordinates(raw_rgb[..., ch].astype(np.float32), rc,
                                                         order=1, mode="nearest")
        best[take] = th[take]
    out[geo.ego_mask(gx, gz)] = 0
    return out


def _edges(gray, valid):
    g = ndimage.gaussian_filter(gray, 1.0)
    e = np.hypot(ndimage.sobel(g, 0), ndimage.sobel(g, 1)) * valid
    p = np.percentile(e[valid], 97) if valid.any() else 1.0
    return np.clip(e / max(p, 1e-6), 0, 1)


def write_debug(dirpath, raw_rgb, scr, start_calib, fitted, report):
    os.makedirs(dirpath, exist_ok=True)
    gx, gz = scr.pixel_ground()
    shot = scr.rgb.astype(np.float32).copy()
    inval = ~scr.valid | scr.car_mask(gx, gz)
    shot[inval] = shot[inval] * 0.35 + np.array([90, 0, 90], np.float32) * 0.65
    save_rgb(os.path.join(dirpath, "screen_bev_masked.png"), shot)
    for tag, calib in (("start", start_calib), ("fit", fitted)):
        ours = render_ours(raw_rgb, calib, scr)
        save_rgb(os.path.join(dirpath, "ours_%s.png" % tag), ours)
        # 0.5 m 棋盤交錯：偶數格=截圖、奇數格=我們的 BEV，對準時線條在格子邊界連續
        cell = (np.floor(gx / 0.5).astype(int) + np.floor(gz / 0.5).astype(int)) % 2 == 1
        chk = np.where(cell[..., None], ours, scr.rgb.astype(np.float32))
        save_rgb(os.path.join(dirpath, "checker_%s.png" % tag), chk)
        vo = ours.sum(axis=2) > 0
        e1 = _edges(scr.gray, scr.valid & vo)
        e2 = _edges(luma(ours), scr.valid & vo)
        ov = np.stack((e1 * 255, e2 * 255, np.zeros_like(e1)), axis=2)   # 紅=截圖 綠=我們 黃=重合
        save_rgb(os.path.join(dirpath, "edges_%s.png" % tag), ov)
    with open(os.path.join(dirpath, "fit_report.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=1)


# ---------------------------------------------------------------- CLI

def parse_roi(text):
    v = [float(t) for t in text.split(",")]
    if len(v) != 4 or not (0 <= v[0] < v[2] <= 1 and 0 <= v[1] < v[3] <= 1):
        raise argparse.ArgumentTypeError("--roi 要 l,t,r,b 四個 0..1 的比例，且 l<r、t<b")
    return v


def main(argv=None):
    ap = argparse.ArgumentParser(description="用原始四路畫面 + 全景360 截圖擬合 avm_calib.json")
    ap.add_argument("raw", help="Camera2 1920x1080 2x2 原始畫面（JPEG/PNG）")
    ap.add_argument("screen", help="全景360 螢幕截圖（PNG）")
    ap.add_argument("--roi", type=parse_roi, required=True, help="截圖中俯視圖範圍 l,t,r,b（比例）")
    ap.add_argument("--wm", type=float, required=True, help="ROI 橫向代表的公尺數")
    ap.add_argument("--flip", action="store_true", help="俯視圖車頭朝下（轉 180°）")
    ap.add_argument("--calib", default=ag.DEFAULT_CALIB, help="起始校正檔（預設 assets/avm_calib.json）")
    ap.add_argument("-o", "--output", required=True, help="輸出的校正檔")
    ap.add_argument("--debug-dir", help="輸出比對圖與 fit_report.json 的資料夾")
    ap.add_argument("--car-margin", type=float, default=0.3, help="車子圖示遮罩外擴（m，預設 0.3）")
    ap.add_argument("--cams", help="只擬合這些鏡頭，例如 front,rear（其他照抄起始值）")
    ap.add_argument("--fit-k1", action="store_true", help="一併擬合魚眼畸變 k1")
    ap.add_argument("--separate-fy", action="store_true", help="fx、fy 分開擬合（預設 fx=fy 同比例）")
    ap.add_argument("-q", "--quiet", action="store_true")
    a = ap.parse_args(argv)

    with open(a.calib, encoding="utf-8") as f:
        start = json.load(f)
    raw = load_rgb(a.raw)
    shot = load_rgb(a.screen)
    if raw.shape[:2] != (1080, 1920):
        print("注意：原始畫面是 %dx%d，不是 1920x1080；照實際大小切四象限" % (raw.shape[1], raw.shape[0]),
              file=sys.stderr)
    opts = {"fit_k1": a.fit_k1, "separate_fy": a.separate_fy, "car_margin": a.car_margin}
    if a.cams:
        opts["cams"] = [t.strip() for t in a.cams.split(",") if t.strip()]
    fitted, report = fit(raw, shot, a.roi, a.wm, a.flip, start, opts, verbose=not a.quiet)
    report["args"] = {"raw": a.raw, "screen": a.screen, "roi": a.roi, "wm": a.wm, "flip": a.flip,
                      "calib": a.calib}
    os.makedirs(os.path.dirname(os.path.abspath(a.output)), exist_ok=True)
    with open(a.output, "w", encoding="utf-8") as f:
        f.write(format_calib(fitted))
    ag.Geometry(json.loads(open(a.output, encoding="utf-8").read()))   # 確認讀得回來
    if a.debug_dir:
        scr = ScreenBev(shot, a.roi, a.wm, a.flip, fitted["car"]["length"], fitted["car"]["width"],
                        a.car_margin)
        write_debug(a.debug_dir, raw, scr, start, fitted, report)
    print("→ %s（%.1f s）" % (a.output, report["sec"]))
    warn = []
    for name, r in report["cams"].items():
        d = r["delta"]
        print("  %-5s NCC %.3f  Δpitch %+6.2f° Δroll %+6.2f° Δyaw %+6.2f°  Δpos %+.3f %+.3f %+.3f m"
              "  Δf %+5.1f%%  Δcx %+.4f Δcy %+.4f"
              % (name, r["ncc"] or 0.0, d["pitch"], d["roll"], d["yaw"], d["px"], d["py"], d["pz"],
                 100.0 * (math.exp(d["f"]) - 1.0), d["cx"], d["cy"]))
        if (r["ncc"] or 0.0) < 0.3:
            warn.append("%s 相似度只有 %.2f，可能沒對上（看 --debug-dir 的 checker/edges 圖）" % (name, r["ncc"] or 0.0))
        if r["at_bound"]:
            warn.append("%s 參數碰到上下界：%s（起始值差太多或截圖範圍/比例有誤）" % (name, ",".join(r["at_bound"])))
    for w in warn:
        print("警告：" + w, file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
