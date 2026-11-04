#!/usr/bin/env python3
"""
四路魚眼重疊區 → 高於地面的障礙物（柱子、牆、路緣、別人的車身）。不需要任何偵測模型。

原理
----
俯視圖是「把畫面投影到 y = 0 的地面」。地面上的一個點，從兩顆不同鏡頭投影過去會落在
**同一個位置**；高於地面 h 的點，從裝在高度 cy 的鏡頭投影過去會沿著「遠離鏡頭」的方向偏

    偏移量 = 水平距離 × h / (cy − h)

cy 只有 0.30–0.84 m，所以 h = 0.4 m 的東西在 2 m 外就偏了 1.8 m。兩顆鏡頭的偏移方向不同，
同一個地面格子取到的內容就對不起來 ⇒ 那裡不是地面。

直接比「地面假設下兩顆鏡頭的差」行不通——量過實際資料：**平坦地面本身就有 0.2–0.4 m 的
殘餘錯位**（校正殘差 ＋ 路拱／路面起伏，柏油其實不是一個完美平面），強度跟 7–10 cm 高的
東西造成的視差同級。用樹影這種高對比紋理一比，平地的差值就衝到跟柱子一樣高，整張圖全紅。

所以改成**垂直方向的平面掃描（plane sweep）**：每個地面格子沿鉛直線取幾個高度 h，
把 (X, h, Z) 投到兩顆鏡頭上比對。

    地面格子：只有 h = 0 對得起來，h > 0 對不起來。
    柱子／牆：柱面在每個高度都有實體，所以 h > 0 也對得起來，甚至比 h = 0 更好。

    分數 = 代價(h=0) − min 代價(h>0)，再除以該處的紋理量。分數為正 ⇒ 這格上面站著東西。

比「只比 h = 0 對不對得起來」好的地方是：它要的是**正向證據**（某個高度上真的有一個面兩邊都看到），
而不是只看「對不起來」。對不起來的原因太多了——校正殘差、路面起伏、反光、曝光差——
全都會讓「只比 h = 0」的版本在平地上誤報。

兩個被實際資料打臉之後才加進去的細節：
- h > 0 的那幾層要**先過紋理門檻 TEX_FLOOR** 才能當候選。不然「兩邊都是一片死白」
  （天空、天花板、過曝白牆）會算出代價 0 被當成完美吻合，合成平地的遠處整片誤報。
- 掃描高度上限只到 1.0 m：前鏡頭只裝在 0.298 m，更高的點要抬頭看，超過魚眼 maxDeg 就沒了；
  而且 h → cy 時地面投影會發散。下限 0.15 m：再低的話視差跟平地本身的殘餘錯位同級。
- 平移容差（TOL）反而要關掉。本來想用 ±0.32 m 的容差吸收殘餘錯位，實測 ROC 變差：
  容差幫到障礙物比幫到平地還多（HP_R 拉大之後，剩下的訊號本來就對小位移不敏感）。

限制（量出來的，不是猜的）
------------------------
- 只有「≥2 顆鏡頭都看得到」的四個斜角楔形算得出來；正前／正後／正左／正右完全管不到。
  再扣掉 SKIRT / MAX_RANGE，實際可判定約 65 m²（整個 BEV 範圍是 157 m²）。
- 偵測到的是俯視圖上的「拖痕」，物體在拖痕靠車子的那一端，所以回報最近的一帶；
  合成方柱的定位誤差約 1.0–1.5 m（偏外側），延伸結構（牆、車身側面）準得多。
- 沒有紋理的面（過曝白牆、均勻天花板）完全沒有資訊，一定漏。

流程
----
1. Grid：每個地面格子被哪幾顆鏡頭看得到。除了 maxDeg / 象限邊界，還要算**自車遮擋**
   （俯視平面上射線穿過車身矩形就看不到；鏡頭本身在矩形內時不算自己擋自己）。
2. sample()：每個 (鏡頭, 高度) 取樣該 3D 點的灰階（每格 2x2 個 tap 平均，抵一點混疊）。
3. 高通：減掉 2.7 m 視窗的局部平均，吃掉四顆鏡頭的曝光／白平衡／暗角差（思路同
   tools/fit_calib.py 的 local_norm，但換成方框濾波，Kotlin 端才做得動）；
   成對鏡頭之間再用重疊區的穩健尺度（直方圖）對一次增益。
4. 每個高度算一次局部 SAD（1.44 m 視窗）→ 代價。
5. 分數 → 門檻 → 形態學 opening → 連通元件 → 障礙物（車身座標的位置與範圍）。

用法
----
    python3 tools/height_proto.py detect RAW.jpg -o OUT_DIR      # 偵測 + 疊圖
    python3 tools/height_proto.py tune RAW.jpg ... --flat=x0,z0,x1,z1 --obst=...
    python3 tools/height_proto.py synth -o OUT_DIR               # 合成柱子，量定位誤差
    python3 tools/height_proto.py fixtures -o DIR                # 產生 Kotlin 單元測試資料

需要 numpy、Pillow。
"""
import argparse
import json
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import avm_geometry as ag  # noqa: E402

REPO = ag.REPO
DEFAULT_CALIB = ag.DEFAULT_CALIB

# ──────────────────────────────────────────────────────── 參數（與 HeightDetector.kt 必須一致）

STRIDE = 4             # 工作網格 = BEV 的 1/4 解析度（0.04 → 0.16 m/格），300x350 → 75x87
TAP = 2                # 每格每鏡頭取 TAP x TAP 個 tap 平均
# 掃描高度（m）：h=0 是地面假設。上限只到 1.0 m——
#   (a) 前鏡頭只裝在 0.298 m，比它高的點要抬頭看，魚眼 maxDeg 之外就看不到了；
#   (b) 高於鏡頭的點在地面上的投影會發散（h → cy 時趨近無限遠），資訊量很低。
# 下限 0.15 m：再低的話那點視差（≈ 0.3 m）跟平地本身的殘餘錯位同級，平地就會被誤報。
# 5 層就夠：量過 6 層（多一層 1.0 m）的 ROC 沒有比較好，但每幀的方框濾波要多做 20%。
HEIGHTS = (0.0, 0.15, 0.30, 0.50, 0.80)
TOL = 0                # 平移容差（格）。量過：給容差反而變差（見模組說明），所以預設 0
DARK_SUM = 30          # R+G+B 低於此值 = 魚眼成像圈外的暗角，該 tap 不算數
EGO_MARGIN = 0.05      # 車身矩形外擴（跟 avm_geometry.EGO_MARGIN 一致）
OCC_MARGIN = 0.02      # 自車遮擋判斷用的矩形外擴
SKIRT = 0.25           # 車身再往外推這麼多才開始找：後照鏡、保桿在畫面裡不是地面
MAX_RANGE = 4.2        # 離車身矩形多遠以內才找（m）；更遠的地面解析度與視差都不可靠

HP_R = 8               # 高通（減局部平均）方框半徑（格）：17 格視窗 = 2.72 m
MATCH_R = 4            # 比對視窗半徑（格）：9 格 = 1.44 m，要比殘餘錯位大
TEX_FLOOR = 3.0        # 紋理下限（灰階）：低於它的地方沒有資訊，分數自動趨近 0
MIN_DEN = 0.4          # 比對視窗內至少要有幾成格子有效

SCORE_T = 0.45         # 分數門檻（見模組說明的實測 ROC）
OPEN_R = 1             # 形態學 opening 半徑（格）
MIN_AREA = 0.20        # 元件最小面積（m²）：至少 8 格，單格雜訊過不了
NEAR_BAND = 0.60       # 拖痕離車最近的這一帶當作物體本體（m）
MIN_CONF = 0.30

QUAD_NAMES = ag.QUAD_NAMES


# ──────────────────────────────────────────────────────── 幾何

def project_h(cam, gx, gy, gz, qw, qh):
    """任意高度的點 (gx, gy, gz) → 象限內像素 (u, v, ok)。

    avm_geometry.ground_to_pixel 把高度寫死成 0（dY = −cam.py），這裡放開成 dY = gy − cam.py，
    其它完全一樣（等距魚眼 rd = θ(1 + k1θ² + k2θ⁴)）。HeightDetector.kt 有同一份。
    """
    m = cam.m
    dX = np.asarray(gx, dtype=np.float64) - cam.px
    dY = gy - cam.py
    dZ = np.asarray(gz, dtype=np.float64) - cam.pz
    x = m[0][0] * dX + m[1][0] * dY + m[2][0] * dZ
    y = m[0][1] * dX + m[1][1] * dY + m[2][1] * dZ
    z = m[0][2] * dX + m[1][2] * dY + m[2][2] * dZ
    r = np.sqrt(x * x + y * y)
    th = np.arctan2(r, z)
    t2 = th * th
    rd = th * (1.0 + cam.k1 * t2 + cam.k2 * t2 * t2)
    ca, sa = ag._unit_dir(x, y, r)
    u = cam.cx * qw + cam.fx * qw * rd * ca
    v = cam.cy * qh + cam.fy * qw * rd * sa
    if cam.mirror:
        u = qw - u
    return u, v, (th <= cam.max_rad) & (u >= 0.0) & (u < qw) & (v >= 0.0) & (v < qh)


def ego_occluded(geo, cam, gx, gz, margin=OCC_MARGIN):
    """鏡頭 → 地面點的視線（俯視平面上的線段）是否穿過自車矩形。

    鏡頭本身落在矩形內（例如後鏡頭 z = −2.3825，而半長 2.42）時不算自己擋自己，直接回 False。
    slab method：線段參數 t 落在 x / z 兩條帶子裡的交集非空 ⇒ 被擋。
    """
    hw = geo.car_width / 2.0 + margin
    hl = geo.car_length / 2.0 + margin
    if abs(cam.px) <= hw and abs(cam.pz) <= hl:
        return np.zeros(np.shape(gx), dtype=bool)
    t0 = np.zeros(np.shape(gx))
    t1 = np.ones(np.shape(gx))
    for c, d, h in ((cam.px, gx - cam.px, hw), (cam.pz, gz - cam.pz, hl)):
        par = np.abs(d) < 1e-12
        safe = np.where(par, 1.0, d)
        a = (-h - c) / safe
        b = (h - c) / safe
        lo = np.where(par, -np.inf if abs(c) <= h else np.inf, np.minimum(a, b))
        hi = np.where(par, np.inf if abs(c) <= h else -np.inf, np.maximum(a, b))
        t0 = np.maximum(t0, lo)
        t1 = np.minimum(t1, hi)
    return t0 < t1 - 1e-9


class Grid:
    """工作網格 ＋ 每個 (鏡頭, 高度, tap) 的取樣查表。只跟校正／畫面大小有關，要快取重用。"""

    def __init__(self, geo, frame_w, frame_h, stride=STRIDE, tap=TAP,
                 heights=HEIGHTS, max_range=MAX_RANGE, skirt=SKIRT):
        self.geo = geo
        self.frame_w, self.frame_h = frame_w, frame_h
        self.stride, self.tap = stride, tap
        self.heights = tuple(heights)
        self.cell = geo.mpp * stride
        self.nx = geo.bev_w // stride
        self.nz = geo.bev_h // stride
        self.ncam = len(geo.cams)
        self.nh = len(self.heights)
        i = np.arange(self.nx, dtype=np.float64)
        j = np.arange(self.nz, dtype=np.float64)
        self.gx, self.gz = np.meshgrid(geo.x_min + (i + 0.5) * self.cell,
                                       geo.z_max - (j + 0.5) * self.cell)
        self.ego = geo.ego_mask(self.gx, self.gz)
        dx = np.maximum(np.abs(self.gx) - geo.car_width / 2.0, 0.0)
        dz = np.maximum(np.abs(self.gz) - geo.car_length / 2.0, 0.0)
        self.dist = np.hypot(dx, dz)                      # 離車身矩形的距離
        self.inrange = (self.dist >= skirt) & (self.dist <= max_range)

        qw, qh = frame_w // 2, frame_h // 2
        ntap = tap * tap
        offs = [(-0.5 + (k + 0.5) / tap) * self.cell for k in range(tap)]
        self.idx = np.full((self.ncam, self.nh, ntap, self.nz, self.nx), -1, dtype=np.int64)
        self.vis = np.zeros((self.ncam, self.nh, self.nz, self.nx), dtype=bool)
        for c, cam in enumerate(geo.cams):
            qx0 = (cam.quad % 2) * qw
            qy0 = (cam.quad // 2) * qh
            base = self.inrange & ~ego_occluded(geo, cam, self.gx, self.gz)
            for hi, hv in enumerate(self.heights):
                for t, (ox, oz) in enumerate((a, b) for a in offs for b in offs):
                    u, v, ok = project_h(cam, self.gx + ox, hv, self.gz + oz, qw, qh)
                    good = ok & base
                    ii = (qy0 + np.floor(np.where(good, v, 0.0)).astype(np.int64)) * frame_w \
                        + qx0 + np.floor(np.where(good, u, 0.0)).astype(np.int64)
                    self.idx[c, hi, t] = np.where(good, ii, -1)
                self.vis[c, hi] = np.all(self.idx[c, hi] >= 0, axis=0)
        self.ncov = self.vis[:, 0].sum(axis=0)
        self.pairs = [(a, b) for a in range(self.ncam) for b in range(a + 1, self.ncam)
                      if int((self.vis[a, 0] & self.vis[b, 0]).sum()) >= 50]

    def coverage(self):
        """可用性：車身外有幾成的格子有 ≥2 顆鏡頭看到（= 這個方法能管到的範圍）。"""
        out = ~self.ego
        tot = int(out.sum())
        area = self.cell * self.cell
        s = {"cells": tot, "area_m2": round(tot * area, 1)}
        for k in (1, 2):
            s[">=%d" % k] = round(100.0 * int(((self.ncov >= k) & out).sum()) / max(tot, 1), 1)
        s["overlap_m2"] = round(int(((self.ncov >= 2) & out).sum()) * area, 1)
        hw = self.geo.car_width / 2.0 + EGO_MARGIN
        hl = self.geo.car_length / 2.0 + EGO_MARGIN
        for side, m in (("front", self.gz > hl), ("rear", self.gz < -hl),
                        ("left", self.gx < -hw), ("right", self.gx > hw)):
            sel = m & out
            s[side] = round(100.0 * int(((self.ncov >= 2) & sel).sum()) / max(int(sel.sum()), 1), 1)
        return s


# ──────────────────────────────────────────────────────── 取樣 ＋ 方框濾波

def luma_frame(rgb):
    """整數灰階（跟 ConeDetector / RoadExtractor 同一條式子）。"""
    r = rgb[..., 0].astype(np.int32)
    g = rgb[..., 1].astype(np.int32)
    b = rgb[..., 2].astype(np.int32)
    return (77 * r + 150 * g + 29 * b) >> 8


def sample(grid, rgb):
    """每個 (鏡頭, 高度, 格子) 取到的灰階與有效旗標，形狀 (ncam, nh, nz, nx)。"""
    h, w = rgb.shape[:2]
    y = luma_frame(rgb).reshape(h * w)
    s = rgb.astype(np.int32).sum(axis=2).reshape(h * w)
    ntap = grid.tap * grid.tap
    val = np.zeros((grid.ncam, grid.nh, grid.nz, grid.nx), dtype=np.float64)
    ok = grid.vis.copy()
    for c in range(grid.ncam):
        for hi in range(grid.nh):
            acc = np.zeros((grid.nz, grid.nx), dtype=np.float64)
            for t in range(ntap):
                ii = grid.idx[c, hi, t]
                g = ii >= 0
                f = np.where(g, ii, 0)
                acc += np.where(g, y[f], 0.0)
                ok[c, hi] &= np.where(g, s[f] >= DARK_SUM, False)
            val[c, hi] = acc / ntap
    return val, ok


def _box(a, r):
    """方框和（邊界截斷，不補零）。用前綴和，Kotlin 端是同一條式子、同一個加總順序。"""
    out = a
    for axis in (0, 1):
        n = out.shape[axis]
        zeros = np.zeros((1, out.shape[1]) if axis == 0 else (out.shape[0], 1))
        cs = np.concatenate([zeros, np.cumsum(out, axis=axis)], axis=axis)
        lo = np.maximum(np.arange(n) - r, 0)
        hi = np.minimum(np.arange(n) + r + 1, n)
        out = np.take(cs, hi, axis=axis) - np.take(cs, lo, axis=axis)
    return out


def box_mean(val, msk, r):
    """遮罩內的方框平均，回傳 (平均, 有效格數)。"""
    den = _box(msk.astype(np.float64), r)
    return _box(np.where(msk, val, 0.0), r) / np.maximum(den, 1e-9), den


def shift(a, di, dj, fill=0.0):
    """把 a 往 (+di, +dj) 平移（右、下為正），空出來的填 fill。"""
    out = np.full_like(a, fill)
    h, w = a.shape
    out[max(0, -dj):h - max(0, dj), max(0, -di):w - max(0, di)] = \
        a[max(0, dj):h - max(0, -dj), max(0, di):w - max(0, -di)]
    return out


def robust_sd(val, msk, lo=0.0, hi=255.0, bins=256):
    """直方圖中位數為中心、涵蓋 68% 樣本的半寬（≈ 1σ）。不排序，Kotlin 端照抄。"""
    n = int(msk.sum())
    if n == 0:
        return 1.0
    q = np.clip(((val[msk] - lo) / (hi - lo) * bins).astype(np.int64), 0, bins - 1)
    h = np.bincount(q, minlength=bins)
    med = int(np.searchsorted(np.cumsum(h), (n + 1) // 2))
    need = 0.68 * n
    acc = int(h[med])
    k = 0
    while acc < need and k < bins:
        k += 1
        if med - k >= 0:
            acc += int(h[med - k])
        if med + k < bins:
            acc += int(h[med + k])
    return max((k + 0.5) * (hi - lo) / bins, 1.0)


# ──────────────────────────────────────────────────────── 平面掃描分數

def score(grid, val, ok, cfg=None):
    """回傳 (score, valid, cost, tex)。score > 0 ⇒ 這格上面有高於地面的面。"""
    cfg = cfg or {}
    hp_r = cfg.get("hp_r", HP_R)
    mr = cfg.get("match_r", MATCH_R)
    tol = cfg.get("tol", TOL)
    texf = cfg.get("tex_floor", TEX_FLOOR)
    win = (2 * mr + 1) ** 2 * cfg.get("min_den", MIN_DEN)

    # 高通：減掉局部平均，吃掉曝光 / 白平衡 / 暗角（偏移項在相減時自動消掉，只剩增益要對）
    hp = np.zeros_like(val)
    for c in range(grid.ncam):
        for hi in range(grid.nh):
            mu, _ = box_mean(val[c, hi], ok[c, hi], hp_r)
            hp[c, hi] = np.where(ok[c, hi], val[c, hi] - mu, 0.0)

    offs = [(di, dj) for di in range(-tol, tol + 1) for dj in range(-tol, tol + 1)
            if di * di + dj * dj <= tol * tol]
    # 每個高度算一次局部 SAD（cost）與局部紋理量（tex）。
    # h > 0 的那幾層要**先過紋理門檻**才能當候選：不然「兩邊都是一片死白」（天空、天花板、
    # 過曝白牆）會算出 cost = 0 被當成完美吻合——合成平地的遠處曾經因此整片誤報。
    cost = np.full((grid.nh,) + grid.gx.shape, np.inf)
    tex = np.zeros(grid.gx.shape)
    for a, b in grid.pairs:
        m0 = ok[a, 0] & ok[b, 0]
        if not m0.any():
            continue
        gn = float(np.clip(robust_sd(val[b, 0], m0) / robust_sd(val[a, 0], m0), 0.4, 2.5))
        for hi in range(grid.nh):
            m = ok[a, hi] & ok[b, hi]
            if not m.any():
                continue
            th, _ = box_mean(np.where(m, 0.5 * (gn * np.abs(hp[a, hi]) + np.abs(hp[b, hi])), 0.0), m, mr)
            if hi == 0:
                tex = np.where(m, np.maximum(tex, th), tex)
            else:
                m = m & (th >= texf)
            c1 = np.full(grid.gx.shape, np.inf)
            for di, dj in offs:
                ms = shift(ok[a, hi].astype(np.float64), di, dj) > 0.5
                mm = ms & m
                d = np.where(mm, np.abs(shift(gn * hp[a, hi], di, dj) - hp[b, hi]), 0.0)
                cc, den = box_mean(d, mm, mr)
                c1 = np.minimum(c1, np.where(mm & (den > win), cc, np.inf))
            cost[hi] = np.where(m0, np.minimum(cost[hi], c1), cost[hi])

    # 沒有任何 h > 0 過得了紋理門檻 ⇒ 沒有「上面站著東西」的正向證據 ⇒ 分數 0
    up = np.min(cost[1:], axis=0)
    valid = np.isfinite(cost[0])
    up = np.where(np.isfinite(up), up, cost[0])
    sc = np.where(valid, (np.where(valid, cost[0], 0.0) - np.where(valid, up, 0.0))
                  / np.maximum(tex, texf), 0.0)
    return sc, valid, cost, tex


# ──────────────────────────────────────────────────────── 二值化 → 元件 → 障礙物

def morph(mask, r, grow):
    """方框結構元的 erosion（grow=False）/ dilation（grow=True）。"""
    if r <= 0:
        return mask
    s = _box(mask.astype(np.float64), r)
    return s > 0.5 if grow else s >= _box(np.ones(mask.shape), r) - 0.5


def components(mask):
    """8 連通元件標記 → (label, 元件數)。"""
    nz, nx = mask.shape
    lab = np.zeros(mask.shape, dtype=np.int32)
    flat = mask.ravel()
    lf = lab.ravel()
    stack = np.empty(nz * nx, dtype=np.int64)
    n = 0
    for p0 in range(nz * nx):
        if not flat[p0] or lf[p0]:
            continue
        n += 1
        sp = 0
        stack[sp] = p0
        sp += 1
        lf[p0] = n
        while sp:
            sp -= 1
            p = int(stack[sp])
            i, j = p % nx, p // nx
            for jj in range(max(j - 1, 0), min(j + 2, nz)):
                for ii in range(max(i - 1, 0), min(i + 2, nx)):
                    q = jj * nx + ii
                    if flat[q] and not lf[q]:
                        lf[q] = n
                        stack[sp] = q
                        sp += 1
    return lab, n


class Obstacle:
    __slots__ = ("x", "z", "w", "l", "conf", "cells", "score", "full")

    def __init__(self, x, z, w, l, conf, cells, score, full):
        self.x, self.z, self.w, self.l = x, z, w, l
        self.conf, self.cells, self.score, self.full = conf, cells, score, full

    def as_dict(self):
        # conf 要留夠位數：Kotlin 端是逐位元對的，3 位小數會對不起來
        return {"x": round(self.x, 6), "z": round(self.z, 6), "w": round(self.w, 6),
                "l": round(self.l, 6), "conf": round(self.conf, 7),
                "cells": self.cells, "score": round(self.score, 6),
                "full": [round(v, 3) for v in self.full]}

    def __repr__(self):
        return "Obstacle(x=%.2f z=%.2f w=%.2f l=%.2f conf=%.2f)" % (
            self.x, self.z, self.w, self.l, self.conf)


def extract(grid, sc, valid, cfg=None):
    """分數圖 → 障礙物列表。

    元件在俯視圖裡是「拖痕」：物體被兩顆鏡頭各往外拉一條，所以元件整體會比實體往外偏。
    （實測：合成的 0.5 m 方柱，元件中心比真值外偏 1.5–2 m；牆這種延伸結構偏得少。）
    物體一定在拖痕與車子之間，所以回報離車最近的那一帶當作物體本體，完整拖痕另外留給除錯圖。
    """
    cfg = cfg or {}
    thr = cfg.get("score_t", SCORE_T)
    r = cfg.get("open_r", OPEN_R)
    band = cfg.get("near_band", NEAR_BAND)
    mask = valid & (sc > thr)
    mask = morph(morph(mask, r, False), r, True) & valid
    lab, n = components(mask)
    area1 = grid.cell * grid.cell
    out = []
    for k in range(1, n + 1):
        sel = lab == k
        cnt = int(sel.sum())
        if cnt * area1 < cfg.get("min_area", MIN_AREA):
            continue
        xs, zs, vs, ds = grid.gx[sel], grid.gz[sel], sc[sel], grid.dist[sel]
        mean = float(vs.mean())
        conf = float(min(1.0, max(0.0, (mean / thr - 1.0) * 0.9 + 0.35)))
        if conf < cfg.get("min_conf", MIN_CONF):
            continue
        full = [float(xs.min()), float(zs.min()), float(xs.max()), float(zs.max())]
        near = ds <= ds.min() + band
        nx_, nz_ = xs[near], zs[near]
        out.append(Obstacle(
            float((nx_.min() + nx_.max()) / 2), float((nz_.min() + nz_.max()) / 2),
            float(nx_.max() - nx_.min() + grid.cell), float(nz_.max() - nz_.min() + grid.cell),
            conf, cnt, mean, full))
    out.sort(key=lambda o: -o.conf)
    return out, mask


def detect(grid, rgb, cfg=None):
    val, ok = sample(grid, rgb)
    sc, valid, cost, tex = score(grid, val, ok, cfg)
    obs, mask = extract(grid, sc, valid, cfg)
    return obs, sc, valid, mask


# ──────────────────────────────────────────────────────── 疊圖

def overlay(geo, grid, frame, sc, valid, mask, obs, truth=None, scale=2):
    """俯視圖 + 偵測結果。橘紅陰影 = 分數，紅 = 超過門檻，黃框 = 完整拖痕，青框 = 回報的障礙物，白框 = 真值。"""
    lut = ag.build_bev_lut(geo, frame.shape[1], frame.shape[0])
    img = ag.stitch(frame, lut).astype(np.float64)
    s = grid.stride

    def up(a):
        """格子圖 → BEV 解析度（nz*stride 可能比 BEV 少幾列，尾端補 0）。"""
        b = np.repeat(np.repeat(np.asarray(a), s, axis=0), s, axis=1)
        out = np.zeros(img.shape[:2], dtype=b.dtype)
        n0 = min(b.shape[0], out.shape[0])
        n1 = min(b.shape[1], out.shape[1])
        out[:n0, :n1] = b[:n0, :n1]
        return out

    lv = up(np.where(valid, np.clip(sc / max(SCORE_T, 1e-6), 0, 2.0) / 2.0, 0.0))
    img[..., 0] = np.minimum(255, img[..., 0] + 150 * lv)
    img[..., 1] *= 1.0 - 0.5 * lv
    img[..., 2] *= 1.0 - 0.5 * lv
    hard = up(mask)
    img[hard] = img[hard] * 0.4 + np.array([255, 40, 40]) * 0.6
    # 沒有 ≥2 顆鏡頭的地方壓暗，一眼看得出這個方法管不到哪裡
    dim = ~up(valid)
    img[dim] *= 0.55
    img = img.astype(np.uint8)

    def rect(x0, z0, x1, z1, col):
        i0 = int(round((min(x0, x1) - geo.x_min) / geo.mpp))
        i1 = int(round((max(x0, x1) - geo.x_min) / geo.mpp))
        j0 = int(round((geo.z_max - max(z0, z1)) / geo.mpp))
        j1 = int(round((geo.z_max - min(z0, z1)) / geo.mpp))
        i0, i1 = max(0, i0), min(img.shape[1] - 1, i1)
        j0, j1 = max(0, j0), min(img.shape[0] - 1, j1)
        if i1 < i0 or j1 < j0:
            return
        img[j0:j1 + 1, i0:i0 + 2] = col
        img[j0:j1 + 1, max(i0, i1 - 1):i1 + 1] = col
        img[j0:j0 + 2, i0:i1 + 1] = col
        img[max(j0, j1 - 1):j1 + 1, i0:i1 + 1] = col

    for t in truth or []:
        rect(t[0] - t[2], t[1] - t[3], t[0] + t[2], t[1] + t[3], (255, 255, 255))
    for o in obs:
        rect(o.full[0], o.full[1], o.full[2], o.full[3], (235, 205, 40))
    for o in obs:
        rect(o.x - o.w / 2, o.z - o.l / 2, o.x + o.w / 2, o.z + o.l / 2, (40, 240, 240))
    return np.repeat(np.repeat(img, scale, axis=0), scale, axis=1) if scale != 1 else img


def coverage_image(grid, scale=4):
    col = np.array([(25, 25, 30), (70, 70, 80), (50, 170, 90), (90, 215, 130), (170, 235, 160)])
    img = col[np.clip(grid.ncov, 0, 4)].astype(np.uint8)
    img[grid.ego] = (90, 60, 60)
    img[~grid.inrange & ~grid.ego] = (45, 45, 55)
    return np.repeat(np.repeat(img, scale, axis=0), scale, axis=1)


# ──────────────────────────────────────────────────────── 合成場景

def _slab_hit(cam, rx, ry, rz, px, pz, hw, hl, h):
    """射線打到軸對齊方柱（x ∈ px±hw、z ∈ pz±hl、y ∈ [0, h]）的最近 t；沒打到 = inf。"""
    t0 = np.zeros(rx.shape)
    t1 = np.full(rx.shape, np.inf)
    for c, d, lo, hi in ((cam.px, rx, px - hw, px + hw), (cam.pz, rz, pz - hl, pz + hl),
                         (cam.py, ry, 0.0, h)):
        par = np.abs(d) < 1e-12
        safe = np.where(par, 1.0, d)
        a = (lo - c) / safe
        b = (hi - c) / safe
        inside = (c >= lo) & (c <= hi)
        t0 = np.maximum(t0, np.where(par, -np.inf if inside else np.inf, np.minimum(a, b)))
        t1 = np.minimum(t1, np.where(par, np.inf if inside else -np.inf, np.maximum(a, b)))
    return np.where((t0 < t1) & (t0 > 1e-6), t0, np.inf)


def _ground_tex(geo, seed, cell=0.02, smooth=5):
    """合成地面貼圖：帶限的隨機灰（相關長度約 0.1 m，接近真實鏡頭 MTF 之後的柏油）＋ 幾條白線。

    一開始用 0.5 m 棋盤格 / 純白雜訊，結果合成平地的誤報率遠高於真實資料——那是渲染的鋸齒，
    不是演算法的問題：真實鏡頭會把高於奈奎斯特的細節糊掉，而最近鄰渲染不會。
    """
    rng = np.random.default_rng(seed)
    tw = int((geo.x_max - geo.x_min) / cell) + 4
    th = int((geo.z_max - geo.z_min) / cell) + 4
    tex = rng.normal(118.0, 40.0, (th, tw))
    for _ in range(smooth):                       # 反覆 3x3 平滑 → 帶限
        tex = (tex + np.roll(tex, 1, 0) + np.roll(tex, -1, 0)
               + np.roll(tex, 1, 1) + np.roll(tex, -1, 1)) / 5.0
    tex = 118.0 + (tex - tex.mean()) * (26.0 / max(tex.std(), 1e-6))
    gx = geo.x_min - 2 * cell + np.arange(tw) * cell
    gz = geo.z_max + 2 * cell - np.arange(th) * cell
    GX, GZ = np.meshgrid(gx, gz)
    tex = np.where(np.abs(np.abs(GX) - 2.6) < 0.07, 215.0, tex)      # 兩條縱向白線
    tex = np.where(np.abs(GZ - 5.0) < 0.07, 215.0, tex)              # 一條橫向白線
    return np.clip(tex, 30, 235), cell


def synth_frame(geo, frame_w, frame_h, pillars, seed=7, ss=2):
    """反向渲染四路魚眼：帶限雜訊地面 ＋ 幾根軸對齊方柱（柱面有水平條紋才有東西可比對）。
    每個畫面像素用 ss x ss 條射線平均，模擬真實鏡頭的模糊，不然渲染鋸齒會假扮成視差。
    回傳 (RGB 畫面, 每根柱子的可見像素數)。"""
    qw, qh = frame_w // 2, frame_h // 2
    tex, cell = _ground_tex(geo, seed)
    th_, tw = tex.shape
    off = [(k + 0.5) / ss for k in range(ss)]
    uu, vv = np.meshgrid(np.arange(qw), np.arange(qh))
    rgb = np.zeros((frame_h, frame_w, 3), dtype=np.uint8)
    seen = [0] * len(pillars)
    for cam in geo.cams:
        acc = np.zeros((qh, qw))
        for du in off:
            for dv in off:
                rx, ry, rz = ag.pixel_to_ray(cam, uu + du, vv + dv, qw, qh)
                down = ry < -ag.HORIZON_EPS
                tg = np.where(down, -cam.py / np.where(down, ry, -1.0), np.inf)
                tf = np.where(np.isfinite(tg), tg, 0.0)
                ti = np.clip(((geo.z_max + 2 * cell - (cam.pz + tf * rz)) / cell).astype(np.int64), 0, th_ - 1)
                tj = np.clip((((cam.px + tf * rx) - geo.x_min + 2 * cell) / cell).astype(np.int64), 0, tw - 1)
                col = np.where(down, tex[ti, tj], 20.0)
                best = np.where(down, tg, np.inf)
                for pi, (px, pz, hw, hl, h, base) in enumerate(pillars):
                    t = _slab_hit(cam, rx, ry, rz, px, pz, hw, hl, h)
                    hit = np.isfinite(t) & (t < best)
                    best = np.where(hit, t, best)
                    yy = cam.py + np.where(hit, t, 0.0) * ry
                    stripe = (np.floor(yy / 0.18).astype(np.int64) % 2) == 0
                    col = np.where(hit, base * np.where(stripe, 1.0, 0.72), col)
                    seen[pi] += int(hit.sum())
                acc += col
        qx0 = (cam.quad % 2) * qw
        qy0 = (cam.quad // 2) * qh
        rgb[qy0:qy0 + qh, qx0:qx0 + qw] = \
            np.clip(acc / (ss * ss), 0, 255).astype(np.uint8)[..., None]
    return rgb, [s // (ss * ss) for s in seen]


# 合成場景：(x, z, 半寬, 半長, 高, 柱面灰階)
# 位置都挑在「四個斜角重疊楔形」裡面——正左／正右／正前／正後只有一顆鏡頭看得到，
# 放在那邊等於在測一個這個方法本來就管不到的地方。
SYNTH_PILLARS = [
    (-2.60, 3.70, 0.26, 0.26, 2.2, 200.0),    # 左前方柱子（0.52 m 見方）
    (2.70, -3.60, 0.24, 0.24, 2.2, 175.0),    # 右後方柱子
    (-3.40, -3.60, 0.12, 1.20, 1.5, 190.0),   # 左後方一段牆（沿 z 向 2.4 m）
]


# ──────────────────────────────────────────────────────── CLI

def _load_rgb(path):
    from PIL import Image
    return np.asarray(Image.open(path).convert("RGB"))


def _save(path, arr):
    ag._save_rgb(path, arr)


def _cfg(a):
    return {"score_t": a.score_t, "hp_r": a.hp_r, "match_r": a.match_r, "tol": a.tol,
            "tex_floor": a.tex_floor, "open_r": a.open_r, "min_area": a.min_area,
            "min_conf": a.min_conf, "near_band": a.near_band}


def _grid(geo, rgb, a):
    return Grid(geo, rgb.shape[1], rgb.shape[0], a.stride, a.tap,
                [float(t) for t in a.heights.split(",")], a.max_range, a.skirt)


def cmd_detect(a):
    geo = ag.Geometry.load(a.calib)
    os.makedirs(a.output, exist_ok=True)
    grid = None
    for path in a.raw:
        rgb = _load_rgb(path)
        grid = _grid(geo, rgb, a)
        obs, sc, valid, mask = detect(grid, rgb, _cfg(a))
        tag = os.path.splitext(os.path.basename(path))[0]
        _save(os.path.join(a.output, tag + "_height.png"),
              overlay(geo, grid, rgb, sc, valid, mask, obs))
        print("%s：%s" % (tag, json.dumps(grid.coverage(), ensure_ascii=False)))
        print("  可判定格 %d（%.1f m²），超過門檻 %d，障礙物 %d 個"
              % (valid.sum(), valid.sum() * grid.cell ** 2, mask.sum(), len(obs)))
        for o in obs:
            print("    %s  面積 %.2f m²  分數 %.2f  完整拖痕 x[%.1f,%.1f] z[%.1f,%.1f]"
                  % (o, o.cells * grid.cell ** 2, o.score, o.full[0], o.full[2], o.full[1], o.full[3]))
    if grid is not None:
        _save(os.path.join(a.output, "coverage.png"), coverage_image(grid))
    return 0


def _pct(v):
    if v.size == 0:
        return "       (空)"
    q = np.percentile(v, [50, 90, 95, 99])
    return "%6.2f %6.2f %6.2f %6.2f %6.2f" % (q[0], q[1], q[2], q[3], v.max())


def _rect_mask(grid, rects):
    m = np.zeros(grid.gx.shape, dtype=bool)
    for r in rects or []:
        x0, z0, x1, z1 = (float(t) for t in r.split(","))
        m |= (grid.gx >= x0) & (grid.gx <= x1) & (grid.gz >= z0) & (grid.gz <= z1)
    return m


def cmd_tune(a):
    """量分數分佈：--flat 標起來的平地 vs --obst 標起來的障礙物，門檻才有數字依據。"""
    geo = ag.Geometry.load(a.calib)
    print("%-26s %-5s %6s %s" % ("檔案", "區域", "格數", " p50    p90    p95    p99    max"))
    allf = []
    allo = []
    for path in a.raw:
        rgb = _load_rgb(path)
        grid = _grid(geo, rgb, a)
        sc, valid, _, _ = score(grid, *sample(grid, rgb), cfg=_cfg(a))
        tag = os.path.basename(path)[:26]
        for nm, rs, bag in (("平地", a.flat, allf), ("障礙", a.obst, allo)):
            m = valid & _rect_mask(grid, rs)
            if not m.any():
                continue
            bag.append(sc[m])
            print("%-26s %-5s %6d %s" % (tag, nm, m.sum(), _pct(sc[m])))
        m = valid & ~_rect_mask(grid, a.flat) & ~_rect_mask(grid, a.obst)
        print("%-26s %-5s %6d %s" % (tag, "其它", m.sum(), _pct(sc[m])))
    if allf:
        f = np.concatenate(allf)
        print("\n全部平地 %d 格：p95 %.3f  p99 %.3f  p99.9 %.3f  max %.3f"
              % (f.size, *np.percentile(f, [95, 99, 99.9]), f.max()))
        if allo:
            o = np.concatenate(allo)
            print("全部障礙 %d 格：p10 %.3f  p25 %.3f  p50 %.3f" % (o.size, *np.percentile(o, [10, 25, 50])))
            for t in (0.3, 0.35, 0.4, 0.45, 0.5, 0.6, 0.7):
                print("  門檻 %.2f → 平地誤判 %.2f%%，障礙命中 %.1f%%"
                      % (t, 100.0 * (f > t).mean(), 100.0 * (o > t).mean()))
    return 0


def cmd_synth(a):
    geo = ag.Geometry.load(a.calib)
    os.makedirs(a.output, exist_ok=True)
    rgb, seen = synth_frame(geo, a.width, a.height, SYNTH_PILLARS)
    grid = _grid(geo, rgb, a)
    obs, sc, valid, mask = detect(grid, rgb, _cfg(a))
    truth = [(p[0], p[1], p[2], p[3]) for p in SYNTH_PILLARS]
    _save(os.path.join(a.output, "synth_quad.png"), rgb)
    _save(os.path.join(a.output, "synth_height.png"),
          overlay(geo, grid, rgb, sc, valid, mask, obs, truth))
    print("合成畫面 %dx%d，柱子可見像素 %s；%s" % (a.width, a.height, seen,
                                                  json.dumps(grid.coverage(), ensure_ascii=False)))
    for px, pz, hw, hl, h, _ in SYNTH_PILLARS:
        hit = [(math.hypot(o.x - px, o.z - pz), o) for o in obs]
        hit.sort(key=lambda t: t[0])
        print("  真值 (%+.2f, %+.2f) 半徑 %.2f/%.2f 高 %.1f → %s 誤差 %.2f m"
              % (px, pz, hw, hl, h, hit[0][1] if hit else None, hit[0][0] if hit else float("nan")))
    flat, _ = synth_frame(geo, a.width, a.height, [])
    fg = _grid(geo, flat, a)
    fo, fsc, fv, fm = detect(fg, flat, _cfg(a))
    _save(os.path.join(a.output, "synth_flat.png"), overlay(geo, fg, flat, fsc, fv, fm, fo))
    print("  純平地：誤報 %d 個；分數 p99 = %.3f，max = %.3f，超門檻格 %d"
          % (len(fo), float(np.percentile(fsc[fv], 99)), float(fsc[fv].max()), int(fm.sum())))
    return 0


def cmd_fixtures(a):
    """給 HeightDetectorTest.kt 的資料：原始四分割畫面 PNG ＋ 參數 ＋ Python 端輸出。"""
    geo = ag.Geometry.load(a.calib)
    os.makedirs(a.output, exist_ok=True)
    with open(a.calib, encoding="utf-8") as f:
        calib = json.load(f)
    cases = [("pillars", SYNTH_PILLARS), ("flat", []), ("black", "black"), ("white", "white")]
    for p in a.raw:
        cases.append(("real_" + os.path.splitext(os.path.basename(p))[0], p))
    index = []
    for name, spec in cases:
        gt = []
        if spec == "black":
            rgb = np.zeros((a.height, a.width, 3), np.uint8)
        elif spec == "white":
            rgb = np.full((a.height, a.width, 3), 255, np.uint8)
        elif isinstance(spec, str):
            rgb = _load_rgb(spec)
        else:
            rgb, _ = synth_frame(geo, a.width, a.height, spec)
            gt = [{"x": p[0], "z": p[1], "hw": p[2], "hl": p[3], "h": p[4]} for p in spec]
        grid = _grid(geo, rgb, a)
        obs, sc, valid, mask = detect(grid, rgb, _cfg(a))
        _save(os.path.join(a.output, name + ".png"), rgb)
        doc = {
            "note": "由 tools/height_proto.py fixtures 產生，勿手改。",
            "frameW": int(rgb.shape[1]), "frameH": int(rgb.shape[0]),
            "stride": a.stride, "tap": a.tap, "heights": grid.heights,
            "maxRange": a.max_range, "skirt": a.skirt, "scoreT": a.score_t,
            "calib": calib, "gt": gt, "coverage": grid.coverage(),
            "py": [o.as_dict() for o in obs],
            "stats": {"valid": int(valid.sum()), "over": int(mask.sum()),
                      "scoreSum": round(float(sc[valid].sum()), 3),
                      "scoreMax": round(float(sc[valid].max()) if valid.any() else 0.0, 4)},
        }
        with open(os.path.join(a.output, name + ".json"), "w", encoding="utf-8") as f:
            json.dump(doc, f, ensure_ascii=False, indent=1)
        index.append(name)
        print("%-30s 可判定格 %5d  超門檻 %4d  障礙物 %d" % (name, valid.sum(), mask.sum(), len(obs)))
    with open(os.path.join(a.output, "index.json"), "w", encoding="utf-8") as f:
        json.dump({"cases": index}, f, ensure_ascii=False)
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description="重疊區垂直平面掃描 → 高於地面的障礙物")
    ap.add_argument("--calib", default=DEFAULT_CALIB)
    sub = ap.add_subparsers(dest="cmd", required=True)

    def common(p):
        p.add_argument("--stride", type=int, default=STRIDE)
        p.add_argument("--tap", type=int, default=TAP)
        p.add_argument("--heights", default=",".join("%g" % h for h in HEIGHTS))
        p.add_argument("--tol", type=int, default=TOL)
        p.add_argument("--hp-r", type=int, default=HP_R, dest="hp_r")
        p.add_argument("--match-r", type=int, default=MATCH_R, dest="match_r")
        p.add_argument("--tex-floor", type=float, default=TEX_FLOOR, dest="tex_floor")
        p.add_argument("--max-range", type=float, default=MAX_RANGE, dest="max_range")
        p.add_argument("--skirt", type=float, default=SKIRT)
        p.add_argument("--score-t", type=float, default=SCORE_T, dest="score_t")
        p.add_argument("--open-r", type=int, default=OPEN_R, dest="open_r")
        p.add_argument("--min-area", type=float, default=MIN_AREA, dest="min_area")
        p.add_argument("--min-conf", type=float, default=MIN_CONF, dest="min_conf")
        p.add_argument("--near-band", type=float, default=NEAR_BAND, dest="near_band")
        p.add_argument("--calib", default=argparse.SUPPRESS, help=argparse.SUPPRESS)

    p = sub.add_parser("detect", help="原始 2x2 畫面 → 障礙物 ＋ 疊圖")
    p.add_argument("raw", nargs="+")
    p.add_argument("-o", "--output", required=True)
    common(p)
    p.set_defaults(fn=cmd_detect)

    p = sub.add_parser("tune", help="量分數分佈，決定門檻")
    p.add_argument("raw", nargs="+")
    p.add_argument("--flat", action="append", default=[], help="平地矩形 x0,z0,x1,z1（可多個）")
    p.add_argument("--obst", action="append", default=[], help="障礙物矩形 x0,z0,x1,z1（可多個）")
    common(p)
    p.set_defaults(fn=cmd_tune)

    p = sub.add_parser("synth", help="合成柱子：定位誤差 ＋ 平地誤報")
    p.add_argument("-o", "--output", required=True)
    p.add_argument("--width", type=int, default=1920)
    p.add_argument("--height", type=int, default=1080)
    common(p)
    p.set_defaults(fn=cmd_synth)

    p = sub.add_parser("fixtures", help="產生 Kotlin 單元測試資料")
    p.add_argument("-o", "--output", required=True)
    p.add_argument("--raw", nargs="*", default=[])
    p.add_argument("--width", type=int, default=1280)
    p.add_argument("--height", type=int, default=720)
    common(p)
    p.set_defaults(fn=cmd_fixtures)

    a = ap.parse_args(argv)
    return a.fn(a)


if __name__ == "__main__":
    sys.exit(main())
