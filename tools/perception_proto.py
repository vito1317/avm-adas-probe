#!/usr/bin/env python3
"""
v0.3 地面感知原型（道路標線 / 路緣 / 三角錐 / 紅綠燈燈色）。

先在這裡用合成資料調參，再一比一移植到 Kotlin（RoadExtractor.kt / ConeDetector.kt /
TrafficLightState.kt）。演算法區塊的整數運算、門檻、掃描順序都刻意跟 Kotlin 一致，
fixture 會同時寫入 ground truth 與本檔的輸出，JVM 測試兩者都比。

合成 BEV 模擬「我們自己用四顆魚眼原始畫面拼出來的俯視圖」：
  每顆鏡頭各自的增益/白平衡/暗角、沿車身對角線的接縫、離鏡頭越遠越糊、
  車身投影區與無覆蓋角落全黑、高物（車、三角錐、牆、樹叢）沿鏡頭射線往外拉長。
另有「螢幕模式」變體：256 px 寬、約 0.03 m/px、中間畫 全景360 的白色車子圖示。

SPEC = 規格書 §3 的參考演算法（調參前）；TUNED / CONE / TL = 調參後，也就是 Kotlin 版。

用法：
  python3 tools/perception_proto.py score [--n 8] [--variant both|spec|tuned] [--out DIR]   # 合成資料評分
  python3 tools/perception_proto.py tl [--n 800]                                            # 紅綠燈燈色
  python3 tools/perception_proto.py fixtures --out app/src/test/resources/perception        # JVM 測試資料
  python3 tools/perception_proto.py sample --kinds urban:7 [--variant screen] --out a.png   # 存一張合成 BEV
  python3 tools/perception_proto.py real --img 13.jpg --corners x0,y0,x1,y1,x2,y2,x3,y3 \\
      --view W,H,mpp,cx,cy --out DIR      # 車機螢幕照：四角（左上、右上、右下、左下）校正後跑一遍
需要 numpy / scipy / Pillow（不用 OpenCV）。
"""
import argparse
import json
import math
import os
import sys
import time

import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage

# ───────────────────────────── 共用 ─────────────────────────────

EGO_HX, EGO_HZ = 0.925, 2.42          # 車身半寬 / 半長（m）
K8 = np.ones((3, 3), bool)            # 8 連通


def f32(v):
    """Kotlin 端參數是 Float：先捨入成 float32 再用 double 算，兩邊才會逐位元一致。"""
    return float(np.float32(v))


def unpack(argb, w, h):
    a = np.asarray(argb, dtype=np.int64).reshape(h, w) & 0xFFFFFFFF
    return ((a >> 16) & 255), ((a >> 8) & 255), (a & 255)


def pack(rgb):
    rgb = np.asarray(rgb, dtype=np.int64)
    v = (0xFF << 24) | (rgb[..., 0] << 16) | (rgb[..., 1] << 8) | rgb[..., 2]
    return v.astype(np.int64)


def to_int32(v):
    v = np.asarray(v, dtype=np.int64) & 0xFFFFFFFF
    return np.where(v >= 2 ** 31, v - 2 ** 32, v).astype(np.int32)


def pix_coords(w, h, mpp, cx, cy):
    xs = (np.arange(w) + 0.5 - cx) * mpp
    zs = (cy - (np.arange(h) + 0.5)) * mpp
    return xs, zs


def exclude_mask(w, h, mpp, cx, cy, exclude):
    xs, zs = pix_coords(w, h, mpp, cx, cy)
    m = np.zeros((h, w), bool)
    for r in exclude:
        x0, z0, x1, z1 = (f32(v) for v in r)
        m |= ((zs >= z0) & (zs <= z1))[:, None] & ((xs >= x0) & (xs <= x1))[None, :]
    return m


def floor_half(v):
    return int(math.floor(v + 0.5))


def upper_median(vals):
    s = sorted(vals)
    return s[len(s) // 2]


# ─────────────────────── 道路標線 / 路緣：參數 ───────────────────────

# 規格書 §3.1 的參考演算法（「調參前」）
SPEC = dict(
    name='spec', bg='mean', bgHalfM=0.25, tW=28, yW=110, tY=10, neutral=50,
    redHue=False, bgChroma=0,
    minArea=0.03, maxWidth=0.35, minAspect=3.0, minLen=0.0, complexVote=False,
    hough='spec', dBin=0.05, nmsDeg=6, nmsD=0.25, supportM=0.8, band=0.08, maxLines=8,
    gapM=0.6, minCovered=0.8, dashed='spec', edge='spec',
)

# 調參後（Kotlin 版就是這組）
TUNED = dict(
    name='tuned', bg='open', bgHalfM=0.20, tW=24, yW=100, tY=14, neutral=50,
    redHue=True, bgChroma=36, chromaHalfM=0.30, yChroma=20, egoMargin=0.15,
    minArea=0.03, maxWidth=0.50, minAspect=3.0, minLen=0.40, complexVote=True, thinM=0.50, thinFrac=0.85,
    hough='tuned', dBin=0.05, voteWin=8, supportM=0.8, band1=0.10, band=0.08, maxLines=8, maxIter=40,
    gapM=0.6, minSeg=0.5, minCovered=0.8, dashed='tuned', solidM=5.0, dashGapM=1.5, thickM=0.25, dupM=0.17, dupDeg=4.0,
    edge='tuned', madC=3, edgeMinRows=4, edgePrune=0.0, clutterMax=0.25, minFill=0.25, wideM=0.30, coneNearM=0.25,
)


# ─────────────────────── 道路標線 / 路緣：演算法 ───────────────────────

def _running_min_max(Y, valid, half):
    """有效像素上的灰階 opening（先 min 再 max，方形 (2h+1)²）；無效像素不參與 min。"""
    big = np.where(valid, Y, 255).astype(np.int32)
    s = 2 * half + 1
    e = ndimage.minimum_filter1d(big, s, axis=1, mode='constant', cval=255)
    e = ndimage.minimum_filter1d(e, s, axis=0, mode='constant', cval=255)
    d = ndimage.maximum_filter1d(e, s, axis=1, mode='constant', cval=0)
    d = ndimage.maximum_filter1d(d, s, axis=0, mode='constant', cval=0)
    return d


def _box_sum(V, half):
    """(2h+1)² 視窗和（積分影像，視窗超出影像就截掉）。"""
    h, w = V.shape
    iv = np.zeros((h + 1, w + 1), np.int64)
    iv[1:, 1:] = V.astype(np.int64).cumsum(0).cumsum(1)
    j0 = np.clip(np.arange(h) - half, 0, h)
    j1 = np.clip(np.arange(h) + half + 1, 0, h)
    i0 = np.clip(np.arange(w) - half, 0, w)
    i1 = np.clip(np.arange(w) + half + 1, 0, w)
    return iv[j1][:, i1] - iv[j0][:, i1] - iv[j1][:, i0] + iv[j0][:, i0]


def _box_mean(Y, valid, half):
    """有效像素的 K×K 平均（積分影像）。"""
    S = _box_sum(np.where(valid, Y, 0), half)
    C = _box_sum(valid, half)
    return np.where(C > 0, S / np.maximum(C, 1), Y.astype(np.float64))


def _run_lengths(m):
    """每個 True 像素所在的水平 / 垂直連續段長度。"""
    def runs(a):
        out = np.zeros(a.shape, np.int32)
        for r in range(a.shape[0]):
            row = a[r]
            if not row.any():
                continue
            d = np.diff(np.concatenate([[0], row.astype(np.int8), [0]]))
            st = np.nonzero(d == 1)[0]
            en = np.nonzero(d == -1)[0]
            for s0, e0 in zip(st, en):
                out[r, s0:e0] = e0 - s0
        return out
    return runs(m), runs(m.T).T


def _comp_stats(lab, n):
    """每個連通元件的像素數與 PCA（像素座標，整數和 → 兩種語言完全一致）。"""
    h, w = lab.shape
    jj, ii = np.indices((h, w))
    L = lab.ravel()
    cnt = np.bincount(L, minlength=n + 1).astype(np.float64)
    si = np.bincount(L, ii.ravel().astype(np.float64), n + 1)
    sj = np.bincount(L, jj.ravel().astype(np.float64), n + 1)
    sii = np.bincount(L, (ii * ii).ravel().astype(np.float64), n + 1)
    sij = np.bincount(L, (ii * jj).ravel().astype(np.float64), n + 1)
    sjj = np.bincount(L, (jj * jj).ravel().astype(np.float64), n + 1)
    out = []
    for k in range(1, n + 1):
        c = cnt[k]
        mi = si[k] / c
        mj = sj[k] / c
        cii = sii[k] / c - mi * mi
        cij = sij[k] / c - mi * mj
        cjj = sjj[k] / c - mj * mj
        hd = (cii - cjj) / 2.0
        disc = math.sqrt(hd * hd + cij * cij)
        l1 = (cii + cjj) / 2.0 + disc
        l2 = max((cii + cjj) / 2.0 - disc, 0.0)
        al = 0.5 * math.atan2(2.0 * cij, cii - cjj)        # 主軸方向（像素座標，+i 起算）
        phi = math.degrees(math.atan2(math.cos(al), -math.sin(al)))  # 轉成 +z 往 +x 的角度
        while phi >= 90.0:
            phi -= 180.0
        while phi < -90.0:
            phi += 180.0
        out.append((int(c), math.sqrt(12.0 * l1 + 1.0), math.sqrt(12.0 * l2 + 1.0), phi))
    return out


def _pca_line(xs, zs):
    n = len(xs)
    mx = float(np.sum(xs)) / n
    mz = float(np.sum(zs)) / n
    dx = xs - mx
    dz = zs - mz
    cxx = float(np.sum(dx * dx)) / n
    cxz = float(np.sum(dx * dz)) / n
    czz = float(np.sum(dz * dz)) / n
    al = 0.5 * math.atan2(2.0 * cxz, cxx - czz)
    phi = math.atan2(math.cos(al), math.sin(al))
    if phi >= math.pi / 2:
        phi -= math.pi
    if phi < -math.pi / 2:
        phi += math.pi
    d = mx * math.cos(phi) - mz * math.sin(phi)
    return phi, d


def road_extract(argb, w, h, mpp, cx, cy, exclude, P=TUNED, dbg=None):
    """回傳 dict(lines=[dict(k,d,x1,z1,x2,z2,c)], edgeL, edgeR)。"""
    mpp, cx, cy = f32(mpp), f32(cx), f32(cy)
    R, G, B = unpack(argb, w, h)
    Y = (77 * R + 150 * G + 29 * B) >> 8
    valid = (R + G + B) >= 24
    em = P.get('egoMargin', 0.0)
    use0 = valid & ~exclude_mask(w, h, mpp, cx, cy, exclude)
    # 標線另外把自車外擴一圈也排除（螢幕模式的車子圖示、後照鏡、車身邊緣的亮條）
    use = use0 & ~exclude_mask(w, h, mpp, cx, cy, [[-EGO_HX - em, -EGO_HZ - em, EGO_HX + em, EGO_HZ + em]]) if em > 0 else use0
    half = floor_half(P['bgHalfM'] / mpp)
    if P['bg'] == 'mean':
        T = Y - _box_mean(Y, valid, half)
    else:
        T = Y - _running_min_max(Y, valid, half)
    mx = np.maximum(np.maximum(R, G), B)
    mn = np.minimum(np.minimum(R, G), B)
    neutral = (mx - mn) <= P['neutral']
    yel = (R >= 90) & (G >= 70) & (R - B >= 45) & (G - B >= 30) & (np.abs(R - G) <= 80)
    red = (R >= 90) & (R - G >= 55) & (R - B >= 45)
    if P['redHue']:
        # 紅色路緣漆色相約 0–5°；三角錐、磚牆偏橘（G 明顯高於 B）
        red &= 12 * np.abs(G - B) <= (R - np.minimum(G, B))
    white = use & neutral & (T >= P['tW']) & (Y >= P['yW'])
    if P.get('coneNearM', 0) > 0:
        # 三角錐的白色反光帶夾在橘色中間，不是白線
        on = _box_sum(orange_mask(R, G, B, CONE), floor_half(P['coneNearM'] / mpp))
        white &= on < 3
    yc = use & yel
    if P['bgChroma'] > 0:
        # 白線周圍應該是灰色路面：磚牆灰縫、紅磚人行道、草地裡的亮細紋都不算
        hc = floor_half(P['chromaHalfM'] / mpp)
        cnt = _box_sum(valid, hc)
        sr = _box_sum(np.where(valid, R, 0), hc)
        sg = _box_sum(np.where(valid, G, 0), hc)
        sb = _box_sum(np.where(valid, B, 0), hc)
        smax = np.maximum(np.maximum(sr, sg), sb)
        smin = np.minimum(np.minimum(sr, sg), sb)
        white &= (smax - smin) <= P['bgChroma'] * cnt
        # 黃線看「比周圍黃多少」（(R+G)/2−B 的局部對比），不看亮度：淺色水泥路上黃漆反而比較暗
        yc &= (R + G - 2 * B) * cnt - (sr + sg - 2 * sb) >= 2 * P['yChroma'] * cnt
    else:
        yc &= T >= P['tY']
    cls = np.zeros((h, w), np.int8)
    cls[white] = 1
    cls[use & red] = 3
    cls[yc] = 2
    cand = cls > 0

    lab, n = ndimage.label(cand, structure=K8)
    stats = _comp_stats(lab, n)
    mode = np.zeros(n + 1, np.int8)          # 0 丟掉, 1 細長（±win 投票）, 2 複雜細線（全角度投票）
    cphi = np.zeros(n + 1)
    a1 = mpp * mpp
    thin = None
    if P['complexVote']:
        hr, vr = _run_lengths(cand)
        thinpx = cand & (np.minimum(hr, vr) <= int(math.floor(P['thinM'] / mpp + 1e-6)))
        thin = np.bincount(lab.ravel(), thinpx.ravel(), n + 1)
    for k, (c, lpx, wpx, phi) in enumerate(stats, start=1):
        area = c * a1
        L = lpx * mpp
        W = wpx * mpp
        cphi[k] = phi
        if area < P['minArea'] or L < P['minLen']:
            continue
        if W <= P['maxWidth'] and L >= P['minAspect'] * W:
            mode[k] = 1
        elif P['complexVote'] and area >= 0.06 and thin[k] >= P['thinFrac'] * c:
            mode[k] = 2
    keep = mode[lab] > 0
    keep[lab == 0] = False
    jj, ii = np.nonzero(keep)
    X = (ii + 0.5 - cx) * mpp
    Z = (cy - (jj + 0.5)) * mpp
    C = cls[jj, ii]
    lk = lab[jj, ii]
    if dbg is not None:
        dbg.update(T=T, cls=cls, keep=keep, lab=lab, mode=mode, use=use)

    if P['hough'] == 'spec':
        lines = _lines_spec(X, Z, C, w, h, mpp, cx, cy, use, P)
    else:
        lines = _lines_tuned(X, Z, C, lk, mode, cphi, w, h, mpp, cx, cy, use, P, _cand_xz(cand, mpp, cx, cy))

    eL, eR = _edges(R, G, B, Y, cand, valid, use0, w, h, mpp, cx, cy, P)
    pm = P.get('edgePrune', 0.0)
    if pm > 0:
        # 路邊以外（人行道、牆面、植栽）的「線」不要：只看大致縱向的線在 z=0 的位置
        keep_l = []
        for L in lines:
            ph = math.radians(L['phi'])
            if abs(L['phi']) <= 30.0:
                x0 = L['off'] / math.cos(ph)
                if (eR is not None and x0 > eR + pm) or (eL is not None and x0 < eL - pm):
                    continue
            keep_l.append(L)
        lines = keep_l
    return dict(lines=lines, edgeL=eL, edgeR=eR)


def _cand_xz(cand, mpp, cx, cy):
    jj, ii = np.nonzero(cand)
    return (ii + 0.5 - cx) * mpp, (cy - (jj + 0.5)) * mpp


def _hough_geom(w, h, mpp, cx, cy, dBin):
    dmax = math.sqrt(max(cx, w - cx) ** 2 + max(cy, h - cy) ** 2) * mpp + 0.1
    half = int(math.ceil(dmax / dBin)) + 1
    return half, 2 * half


COS_T = [math.cos(math.radians(k - 90)) for k in range(180)]
SIN_T = [math.sin(math.radians(k - 90)) for k in range(180)]


def _segments(sp, mpp, P):
    """投影到線軸上的覆蓋段：以 mpp 為格，空格連續超過 gapM 就切段；太短的段丟掉。"""
    smin = float(sp.min())
    b = np.floor((sp - smin) / mpp).astype(np.int64)
    nb = int(b.max()) + 1
    occ = np.zeros(nb, bool)
    occ[b] = True
    maxGap = int(math.floor(P['gapM'] / mpp + 1e-6))
    minSeg = int(math.floor(P.get('minSeg', 0.0) / mpp + 1e-6))
    segs = []
    start = 0
    last = 0
    for q in range(1, nb):
        if not occ[q]:
            continue
        if q - last - 1 > maxGap:
            segs.append((start, last))
            start = q
        last = q
    segs.append((start, last))
    segs = [(a, e) for a, e in segs if e - a + 1 >= minSeg]
    return smin, [(smin + a * mpp, smin + (e + 1) * mpp) for a, e in segs]


def _observable(use, w, h, mpp, cx, cy, c, s, d, s0, s1):
    """沿線 [s0,s1) 每 mpp 取樣，回傳 (樣本數, 可觀測樣本數)。"""
    n = 0
    ok = 0
    t = s0
    while t < s1:
        x = d * c + t * s
        z = -d * s + t * c
        i = int(math.floor(x / mpp + cx))
        j = int(math.floor(cy - z / mpp))
        n += 1
        if 0 <= i < w and 0 <= j < h and use[j, i]:
            ok += 1
        t += mpp
    return n, ok


def _line_out(phi, d, s0, s1, kind, dashed, conf):
    c, s = math.cos(phi), math.sin(phi)
    return dict(k='wyr'[kind - 1], d=bool(dashed),
                x1=d * c + s0 * s, z1=-d * s + s0 * c,
                x2=d * c + s1 * s, z2=-d * s + s1 * c, c=min(1.0, conf),
                phi=math.degrees(phi), off=d)


def _lines_tuned(X, Z, C, lk, mode, cphi, w, h, mpp, cx, cy, use, P, CA=None):
    nk = len(X)
    if nk == 0:
        return []
    dBin = P['dBin']
    half, nR = _hough_geom(w, h, mpp, cx, cy, dBin)
    cosT = np.array(COS_T)
    sinT = np.array(SIN_T)
    win = P['voteWin']
    # 每個像素要投的角度格：細長元件 ±win，複雜元件全部
    pm = mode[lk]
    k0 = (np.floor(cphi[lk] + 0.5).astype(np.int64) + 90)
    pix_list = []
    k_list = []
    one = np.nonzero(pm == 1)[0]
    if len(one):
        offs = np.arange(-win, win + 1)
        pix_list.append(np.repeat(one, len(offs)))
        k_list.append(((k0[one][:, None] + offs[None, :]) % 180).ravel())
    two = np.nonzero(pm == 2)[0]
    if len(two):
        pix_list.append(np.repeat(two, 180))
        k_list.append(np.tile(np.arange(180), len(two)))
    vp = np.concatenate(pix_list)
    vk = np.concatenate(k_list)
    order = np.argsort(vp, kind='stable')
    vp, vk = vp[order], vk[order]
    vr = (np.floor((X[vp] * cosT[vk] - Z[vp] * sinT[vk]) * (1.0 / dBin)).astype(np.int64) + half)
    acc = np.zeros((180, nR), np.int64)
    np.add.at(acc, (vk, vr), 1)
    vstart = np.searchsorted(vp, np.arange(nk + 1))   # 像素 p 的票在 [vstart[p], vstart[p+1])

    minVotes = floor_half(P['supportM'] / mpp * 2.0)
    claimed = np.zeros(nk, bool)
    lines = []

    def unvote(sel):
        idx = np.nonzero(sel)[0]
        if len(idx) == 0:
            return
        parts = [np.arange(vstart[p], vstart[p + 1]) for p in idx]
        q = np.concatenate(parts)
        np.subtract.at(acc, (vk[q], vr[q]), 1)

    for _ in range(P['maxIter']):
        if len(lines) >= P['maxLines'] + 6:
            break
        S3 = acc[:, :-2] + acc[:, 1:-1] + acc[:, 2:]
        flat = int(np.argmax(S3))
        k, rr = divmod(flat, nR - 2)
        r = rr + 1
        best = int(S3[k, rr])
        if best < minVotes:
            break
        a0, a1, a2 = acc[k, r - 1], acc[k, r], acc[k, r + 1]
        dc = ((r - 1 - half + 0.5) * a0 + (r - half + 0.5) * a1 + (r + 1 - half + 0.5) * a2) * dBin / best
        c, s = COS_T[k], SIN_T[k]
        inl = ~claimed & (np.abs(X * c - Z * s - dc) <= P['band1'])
        ok = int(inl.sum()) >= 2
        if ok:
            phi, d = _pca_line(X[inl], Z[inl])
            c, s = math.cos(phi), math.sin(phi)
            inl = ~claimed & (np.abs(X * c - Z * s - d) <= P['band'])
            ok = int(inl.sum()) >= 2
        if ok:
            phi, d = _pca_line(X[inl], Z[inl])
            c, s = math.cos(phi), math.sin(phi)
            sp = X[inl] * s + Z[inl] * c
            _, segs = _segments(sp, mpp, P)
            covered = sum(e - a for a, e in segs)
            ok = covered >= P['minCovered']
        if not ok:
            unvote(inl)
            acc[k, r - 1:r + 2] = 0
            continue
        # 這條線實際的漆寬：±0.30 m 內未認領像素的橫向分佈，從中心往外找連續區（雙黃線中間有縫會停）
        rr_ = X * c - Z * s - d
        near = ~claimed & (np.abs(rr_) < 0.30)
        hist = np.zeros(30, np.int64)
        np.add.at(hist, np.floor((rr_[near] + 0.30) / 0.02).astype(np.int64).clip(0, 29), 1)
        pk = max(hist[14], hist[15])
        lo, hi = 14, 15
        while pk > 0 and lo > 0 and hist[lo - 1] * 4 >= pk:
            lo -= 1
        while pk > 0 and hi < 29 and hist[hi + 1] * 4 >= pk:
            hi += 1
        wide = near & (rr_ >= lo * 0.02 - 0.30) & (rr_ < (hi + 1) * 0.02 - 0.30)
        grab = inl | wide
        # 跟已找到的同色線幾乎重合 → 重複偵測（寬線、模糊線的另一半），丟掉
        mx_ = d * c + (segs[0][0] + segs[-1][1]) / 2 * s
        mz_ = -d * s + (segs[0][0] + segs[-1][1]) / 2 * c
        cnts = [int(np.sum(C[inl] == q)) for q in (1, 2, 3)]
        kind = 1 + int(np.argmax(cnts))
        dup = False
        for L in lines:
            if L['k'] != 'wyr'[kind - 1] or abs(wrap90(L['phi'] - math.degrees(phi))) > P['dupDeg']:
                continue
            pr = math.radians(L['phi'])
            if abs(mx_ * math.cos(pr) - mz_ * math.sin(pr) - L['off']) < P['dupM']:
                dup = True
                break
        claimed |= grab
        unvote(grab)
        if dup:
            continue
        # 不可觀測（無覆蓋 / 被排除）的空隙不算真的斷開
        # 虛線的空檔是 6 m；太短（磨損、陰影）或看不到（無覆蓋、被排除）的空隙都不算斷開
        spans = [list(segs[0])]
        for a, e in segs[1:]:
            n_, ok_ = _observable(use, w, h, mpp, cx, cy, c, s, d, spans[-1][1], a)
            if a - spans[-1][1] < P['dashGapM'] or (n_ > 0 and ok_ * 2 < n_):
                spans[-1][1] = e
            else:
                spans.append([a, e])
        longest = max(e - a for a, e in spans)
        wd = (hi - lo + 1) * 0.02
        if longest >= P['solidM']:
            dashed = False
        elif len(spans) >= 2:
            dashed = True
        elif wd >= P['thickM']:
            dashed = False     # 停止線之類的粗線
        else:
            ext = int(math.floor(1.5 / mpp + 1e-6))
            need = int(math.floor(1.0 / mpp + 1e-6))
            s0, s1 = spans[0]
            _, okA = _observable(use, w, h, mpp, cx, cy, c, s, d, s0 - ext * mpp, s0)
            _, okB = _observable(use, w, h, mpp, cx, cy, c, s, d, s1, s1 + ext * mpp)
            dashed = okA >= need or okB >= need or longest < 2.5
        # 覆蓋率太低（零散小片湊成一條長線）→ 不要；不可觀測的部分不算進分母
        nobs, okobs = _observable(use, w, h, mpp, cx, cy, c, s, d, segs[0][0], segs[-1][1])
        if covered < P['minFill'] * okobs * mpp:
            continue
        if P.get('clutterMax', 0) > 0 and CA is not None:
            # 線兩側 0.35–0.8 m 的帶子裡還有一堆候選像素 → 在牆面 / 樹叢 / 車身這種紋理裡，不是標線
            r2 = CA[0] * c - CA[1] * s - d
            s2 = CA[0] * s + CA[1] * c
            nb_ = int(np.sum((np.abs(r2) >= 0.35) & (np.abs(r2) <= 0.80) & (s2 >= segs[0][0]) & (s2 <= segs[-1][1])))
            if nb_ * mpp * mpp > P['clutterMax'] * 0.9 * (segs[-1][1] - segs[0][0]):
                continue
        ln = _line_out(phi, d, segs[0][0], segs[-1][1], kind, dashed, covered / 4.0)
        ln['wd'] = wd
        lines.append(ln)
    # 斑馬紋：比一般標線粗、又跟某條細標線平行 → 不是車道線
    out = []
    for L in lines:
        if L['wd'] > P['wideM'] and any(M['wd'] <= P['wideM'] and abs(wrap90(M['phi'] - L['phi'])) < 10.0
                                        for M in lines):
            continue
        out.append(L)
    return out[:P['maxLines']]


def _lines_spec(X, Z, C, w, h, mpp, cx, cy, use, P):
    """規格書原文：全角度 Hough + 幾何 NMS，無重新擬合。"""
    nk = len(X)
    if nk == 0:
        return []
    dBin = P['dBin']
    half, nR = _hough_geom(w, h, mpp, cx, cy, dBin)
    cosT = np.array(COS_T)
    sinT = np.array(SIN_T)
    acc = np.zeros((180, nR), np.int64)
    for k in range(180):
        r = np.floor((X * cosT[k] - Z * sinT[k]) / dBin).astype(np.int64) + half
        np.add.at(acc[k], r, 1)
    # 「0.8 m 支撐」：一條 1 px 寬的線 0.8 m 約 0.8/mpp 票
    minVotes = int(P['supportM'] / mpp)
    peaks = []
    A = acc.copy()
    while len(peaks) < 40:
        flat = int(np.argmax(A))
        k, r = divmod(flat, nR)
        if A[k, r] < minVotes:
            break
        peaks.append((k, r))
        nd = int(round(P['nmsD'] / dBin))
        for dk in range(-P['nmsDeg'], P['nmsDeg'] + 1):
            kk = k + dk
            rr = r
            if kk < 0:
                kk += 180
                rr = 2 * half - 1 - r
            elif kk >= 180:
                kk -= 180
                rr = 2 * half - 1 - r
            A[kk, max(0, rr - nd):rr + nd + 1] = 0
    lines = []
    for k, r in peaks:
        if len(lines) >= P['maxLines']:
            break
        phi = math.radians(k - 90)
        d = (r - half + 0.5) * dBin
        c, s = math.cos(phi), math.sin(phi)
        inl = np.abs(X * c - Z * s - d) <= P['band']
        if inl.sum() < 2:
            continue
        sp = X[inl] * s + Z[inl] * c
        _, segs = _segments(sp, mpp, P)
        covered = sum(e - a for a, e in segs)
        if covered < P['minCovered']:
            continue
        longest = max(e - a for a, e in segs)
        dashed = len(segs) >= 2 or longest < 2.5
        cnts = [int(np.sum(C[inl] == q)) for q in (1, 2, 3)]
        kind = 1 + int(np.argmax(cnts))
        lines.append(_line_out(phi, d, float(sp.min()), float(sp.max()), kind, dashed, covered / 4.0))
    return lines


def _edges(R, G, B, Y, cand, valid, use, w, h, mpp, cx, cy, P):
    xs, zs = pix_coords(w, h, mpp, cx, cy)
    ax = np.abs(xs)
    az = np.abs(zs)
    band = ((az <= 2.4)[:, None] & ((ax >= 1.2) & (ax <= 1.95))[None, :]) & use & ~cand
    if int(band.sum()) < 50:
        return None, None
    U = B - Y
    V = R - Y
    model = []
    for q, ch in enumerate((Y, U, V)):
        vals = ch[band].tolist()
        med = upper_median(vals)
        mad = upper_median([abs(v - med) for v in vals])
        model.append((med, max(mad, 6 if q == 0 else P.get('madC', 6))))
    (mY, sY), (mU, sU), (mV, sV) = model
    roadc = ((np.abs(Y - mY) <= 3 * sY + 12) & (np.abs(U - mU) <= 3 * sU + 10)
             & (np.abs(V - mV) <= 3 * sV + 10))
    need = int(math.ceil(0.3 / mpp - 1e-6))
    nRows = 33
    tuned = P['edge'] != 'spec'
    out = []
    for side in (-1, 1):
        vals = []
        scanned = 0       # 調參版：被排除區（車框）擋住的列不算「有掃到」
        for q in range(nRows):
            z = -4.0 + 0.25 * q
            j = int(math.floor(cy - z / mpp))
            if j < 0 or j >= h:
                continue
            i = int(math.floor(cx + side * 1.0 / mpp))
            run = 0
            first = -1
            blocked = False
            while 0 <= i < w:
                if not use[j, i]:
                    blocked = bool(valid[j, i])
                    break
                c_ = cand[j, i]
                if roadc[j, i] and not c_:
                    run = 0
                    first = -1
                elif c_:
                    # 規格版：標線算路面；調參版：標線「透明」，不打斷也不計數，
                    # 緊貼在非路面前的標線（路緣漆、水泥路緣）算進邊界
                    if not tuned:
                        run = 0
                        first = -1
                    elif first < 0:
                        first = i
                else:
                    if first < 0:
                        first = i
                    run += 1
                    if run >= need:
                        vals.append((first - cx) * mpp if side > 0 else (first + 1 - cx) * mpp)
                        break
                i += side
            if not (blocked and run < need):
                scanned += 1
        e = None
        if P['edge'] == 'spec':
            if len(vals) * 10 >= nRows * 3:
                e = upper_median(vals)
        else:
            # 取最密集的 0.4 m 窗（接縫/路邊車只會在少數列造成假邊）
            sv = sorted(vals)
            bestN, bestA = 0, 0
            b = 0
            for a in range(len(sv)):
                while b < len(sv) and sv[b] - sv[a] <= 0.4 + 1e-9:
                    b += 1
                if b - a > bestN:
                    bestN, bestA = b - a, a
            if bestN >= P['edgeMinRows'] and bestN * 10 >= scanned * 3:
                e = sv[bestA + bestN // 2]
        out.append(e)
    return out[0], out[1]


# ─────────────────────────── 三角錐 ───────────────────────────

# 規格書 §3.2 原文（「調參前」）：色相 8–32°、S ≥ 0.5、R ≥ 140，回報最近點、只用 0.4 m 合併
CONE_SPEC = dict(name='spec', minR=140, satPct=50, hueLo=8, hueHi=32, relax=False, minArea=0.03, maxArea=1.5,
                 stripL=1.2, stripW=0.2, mergeM=0.4, baseTol=0.0, bridge=False, centerOff=0.0)
# 調參後：雙門檻（強橘當種子、淡橘長出去，抓得到被拉長變糊的錐身又不會把紅磚當錐），
# 反光帶隔開的上半截沿鏡頭射線併回同一個錐，回報點往外推半個錐底
CONE = dict(name='tuned', minR=110, satPct=70, hueLo=5, hueHi=32, relax=True, relaxR=90, relaxSat=45,
            seedMin=6, minArea=0.03, maxArea=3.0, stripL=1.2, stripW=0.2, mergeM=0.4, baseTol=0.06,
            bridge=True, bridgeDeg=15.0, bridgeM=3.5, bridgeFrac=0.7, centerOff=0.12, sideCamZ=0.85)


def orange_mask(R, G, B, P):
    """三角錐橘：R 最大、B 最小，色相 hueLo–hueHi 度，飽和度 ≥ satPct%，R ≥ minR（全整數）。"""
    return ((R >= P['minR']) & (R >= G) & (G >= B)
            & (60 * (G - B) >= P['hueLo'] * (R - B)) & (60 * (G - B) <= P['hueHi'] * (R - B))
            & (100 * (R - B) >= P['satPct'] * R))


def _rect_dist(x, z):
    dx = np.maximum(np.abs(x) - EGO_HX, 0.0)
    dz = np.maximum(np.abs(z) - EGO_HZ, 0.0)
    return np.sqrt(dx * dx + dz * dz)


def cone_detect(argb, w, h, mpp, cx, cy, exclude, P=CONE, dbg=None):
    mpp, cx, cy = f32(mpp), f32(cx), f32(cy)
    R, G, B = unpack(argb, w, h)
    valid = (R + G + B) >= 24
    use = valid & ~exclude_mask(w, h, mpp, cx, cy, exclude)
    strong = use & orange_mask(R, G, B, P)
    if P['relax']:
        weak = use & orange_mask(R, G, B, dict(P, minR=P['relaxR'], satPct=P['relaxSat']))
    else:
        weak = strong
    Y = (77 * R + 150 * G + 29 * B) >> 8
    mx = np.maximum(np.maximum(R, G), B)
    mn = np.minimum(np.minimum(R, G), B)
    # 橋接像素：淡橘/粉（橘與白色反光帶混色）或亮白
    bridge = weak | (use & (((R >= 100) & (R >= G) & (G >= B) & (60 * (G - B) <= 40 * (R - B))
                             & (10 * (R - B) >= 3 * R)) | ((Y >= 140) & (mx - mn <= 60))))
    lab, n = ndimage.label(weak, structure=K8)
    if dbg is not None:
        dbg.update(orange=weak, strong=strong, lab=lab)
    if n == 0:
        return []
    stats = _comp_stats(lab, n)
    seeds = np.bincount(lab.ravel(), strong.ravel(), n + 1)
    xs, zs = pix_coords(w, h, mpp, cx, cy)
    D = _rect_dist(xs[None, :], zs[:, None])
    comps = []
    a1 = mpp * mpp
    objs = ndimage.find_objects(lab)
    for k in range(1, n + 1):
        c, lpx, wpx, phi = stats[k - 1]
        area = c * a1
        L, W = lpx * mpp, wpx * mpp
        if area < P['minArea'] or area > P['maxArea']:
            continue
        if L >= P['stripL'] and W <= P['stripW']:
            continue
        if P['relax'] and seeds[k] < P['seedMin']:
            continue
        sl = objs[k - 1]
        m = lab[sl] == k
        dd = D[sl][m]
        dmin = float(dd.min())
        near = dd <= dmin + P['baseTol']
        jj, ii = np.nonzero(m)
        bx = float(np.sum(xs[ii[near] + sl[1].start])) / int(near.sum())
        bz = float(np.sum(zs[jj[near] + sl[0].start])) / int(near.sum())
        sat = float(np.sum(((R[sl] - B[sl]) / np.maximum(R[sl], 1))[m])) / c
        comps.append(dict(dmin=dmin, x=bx, z=bz, area=area, sat=sat, lab=k, phi=phi, elong=L >= 1.5 * W))
    comps.sort(key=lambda q: (q['dmin'], q['lab']))
    cones = []
    for q in comps:
        hit = None
        for cn in cones:
            if math.hypot(q['x'] - cn['x'], q['z'] - cn['z']) < P['mergeM']:
                hit = cn
                break
            if P['bridge'] and _bridged(cn, q, bridge, w, h, mpp, cx, cy, P):
                hit = cn
                break
        if hit is not None:
            hit['area'] += q['area']
            continue
        cones.append(dict(q))
    out = []
    for cn in cones:
        # 底點是元件裡離車身最近的點；往外推一點估計錐心
        nx = cn['x'] - max(-EGO_HX, min(EGO_HX, cn['x']))
        nz = cn['z'] - max(-EGO_HZ, min(EGO_HZ, cn['z']))
        nn = math.hypot(nx, nz)
        x, z = cn['x'], cn['z']
        if nn > 1e-9:
            x += nx / nn * P['centerOff']
            z += nz / nn * P['centerOff']
        conf = min(1.0, 0.5 * min(1.0, cn['area'] / 0.08) + 0.5 * min(1.0, max(0.0, (cn['sat'] - 0.5) / 0.3)))
        out.append(dict(x=x, z=z, c=conf))
    return out


def _bridged(a, b, bridge, w, h, mpp, cx, cy, P):
    """b 在 a 的射線後方、且兩者之間幾乎都是橘/白（三角錐的反光帶）→ 同一個錐被拉長。"""
    da = math.hypot(b['x'] - a['x'], b['z'] - a['z'])
    if da > P['bridgeM'] or b['dmin'] <= a['dmin']:
        return False
    vang = math.degrees(math.atan2(b['x'] - a['x'], b['z'] - a['z']))
    # 拉長方向 = 鏡頭射線方向：a 本身夠長就用它的長軸，否則用最近的標稱鏡頭位置（前/後保桿中央、左右後照鏡）
    cams = ((0.0, EGO_HZ), (0.0, -EGO_HZ), (-EGO_HX, P['sideCamZ']), (EGO_HX, P['sideCamZ']))
    best = min(cams, key=lambda q: (a['x'] - q[0]) ** 2 + (a['z'] - q[1]) ** 2)
    ray = math.degrees(math.atan2(a['x'] - best[0], a['z'] - best[1]))
    ang = abs(vang - ray) % 360.0
    ang = min(ang, 360.0 - ang)
    if a['elong']:
        ang = min(ang, abs(wrap90(vang - a['phi'])))
    if ang > P['bridgeDeg']:
        return False
    n = max(1, int(da / mpp))
    good = 0
    for t in range(n + 1):
        x = a['x'] + (b['x'] - a['x']) * t / n
        z = a['z'] + (b['z'] - a['z']) * t / n
        i = int(math.floor(x / mpp + cx))
        j = int(math.floor(cy - z / mpp))
        if 0 <= i < w and 0 <= j < h and bridge[j, i]:
            good += 1
    return good >= P['bridgeFrac'] * (n + 1)


# ─────────────────────────── 紅綠燈燈色 ───────────────────────────

# 全整數：vMin / darkV 是 V(=max(R,G,B)) 門檻、vRelPct 相對 98 百分位亮度、sMinPct 是飽和度 %
TL = dict(vMin=140, vRelPct=60, sMinPct=35, darkV=90, minPermil=12, tiePct=60, sidePct=15, endPct=5)


def tl_bands(R, G, B):
    """燈色色相帶（整數判斷）：紅 <15° 或 >340°、黃 25–65°、綠 90–200°（LED 綠常偏青）。"""
    mx = np.maximum(np.maximum(R, G), B)
    C = mx - np.minimum(np.minimum(R, G), B)
    rmax = (R >= G) & (R >= B)
    gmax = ~rmax & (G >= B)
    bmax = ~rmax & ~gmax
    red = rmax & (((G >= B) & (4 * (G - B) < C)) | ((B > G) & (3 * (B - G) < C)))
    yel = (rmax & (G >= B) & (12 * (G - B) >= 5 * C)) | (gmax & (R >= B) & (12 * (R - B) >= 11 * C))
    grn = (gmax & ((B >= R) | (2 * (R - B) <= C))) | (bmax & (G > R) & (3 * (G - R) >= 2 * C))
    return mx, C, (red, yel, grn)


def tl_classify(argb, w, h, P=TL):
    R, G, B = unpack(argb, w, h)
    mx, C, bands = tl_bands(R, G, B)
    # 只看中間：短軸去掉兩側 sidePct%、長軸兩端去掉 endPct%（框邊多半是天空 / 樹）
    vert = h >= w
    a0 = (w if vert else h) * P['sidePct'] // 100
    b0 = (h if vert else w) * P['endPct'] // 100
    roi = np.zeros((h, w), bool)
    if vert:
        roi[b0:h - b0, a0:w - a0] = True
    else:
        roi[a0:h - a0, b0:w - b0] = True
    vals = np.sort(mx[roi])
    if len(vals) == 0:
        return 'off', 0.5
    vtop = int(vals[(len(vals) * 98) // 100])
    if vtop < P['darkV']:
        return 'off', 0.9
    vthr = max(P['vMin'], vtop * P['vRelPct'] // 100)
    lamp = roi & (mx >= vthr) & (100 * C >= P['sMinPct'] * mx)
    wgt = (mx * C).astype(np.int64)
    score = [int(np.sum(wgt[lamp & b])) for b in bands]
    npx = [int(np.sum(lamp & b)) for b in bands]
    tot = sum(score)
    if tot <= 0 or max(npx) < max(2, int(roi.sum()) * P['minPermil'] // 1000):
        return 'off', 0.6
    order = sorted(range(3), key=lambda q: (-score[q], q))
    best = order[0]
    if 100 * score[order[1]] > P['tiePct'] * score[best]:
        # 平手：看亮點落在長軸的哪一段（紅上/左、綠下/右）
        jj, ii = np.indices((h, w))
        pos = (jj if vert else ii).astype(np.float64) + 0.5
        L = float(h if vert else w)
        m = lamp & (bands[order[0]] | bands[order[1]])
        p = float(np.sum(pos[m])) / max(1, int(m.sum())) / L
        exp = [1 / 6, 0.5, 5 / 6]
        best = min(order[:2], key=lambda q: (abs(p - exp[q]), q))
    return ['red', 'yellow', 'green'][best], score[best] / tot


# ─────────────────────────── 合成 BEV：鏡頭模型 ───────────────────────────

QW, QH = 960, 540
CAMS = {   # avm_calib.json 預設值
    'front': dict(cx=.5, cy=.5, fx=.265, fy=.265, maxDeg=95, pos=(0.0, 0.55, 2.35), yaw=0, pitch=25, roll=0),
    'rear': dict(cx=.5, cy=.5, fx=.265, fy=.265, maxDeg=95, pos=(0.0, 0.95, -2.40), yaw=180, pitch=35, roll=0),
    'left': dict(cx=.5, cy=.5, fx=.265, fy=.265, maxDeg=95, pos=(-1.0, 1.0, 0.85), yaw=-90, pitch=55, roll=0),
    'right': dict(cx=.5, cy=.5, fx=.265, fy=.265, maxDeg=95, pos=(1.0, 1.0, 0.85), yaw=90, pitch=55, roll=0),
}


def cam_matrix(c):
    y, p, r = (math.radians(c[k]) for k in ('yaw', 'pitch', 'roll'))
    Ry = np.array([[math.cos(y), 0, math.sin(y)], [0, 1, 0], [-math.sin(y), 0, math.cos(y)]])
    Rp = np.array([[1, 0, 0], [0, math.cos(p), -math.sin(p)], [0, math.sin(p), math.cos(p)]])
    Rr = np.array([[math.cos(r), -math.sin(r), 0], [math.sin(r), math.cos(r), 0], [0, 0, 1]])
    return Ry @ Rp @ Rr @ np.diag([1.0, -1.0, 1.0])   # 鏡頭 → 車體


def ground_to_pix(c, gx, gz):
    M = cam_matrix(c).T
    px, py, pz = c['pos']
    d = np.stack([gx - px, np.full_like(gx, -py), gz - pz])
    rc = np.tensordot(M, d, axes=1)
    rc /= np.linalg.norm(rc, axis=0)
    th = np.arccos(np.clip(rc[2], -1, 1))
    a = np.arctan2(rc[1], rc[0])
    u = c['cx'] * QW + c['fx'] * QW * th * np.cos(a)
    v = c['cy'] * QH + c['fy'] * QW * th * np.sin(a)
    ok = (th <= math.radians(c['maxDeg'])) & (u >= 0) & (u < QW) & (v >= 0) & (v < QH)
    return u, v, th, ok


def ground_gsd(c, gx, gz, eps=0.01):
    """一個鏡頭像素在地面上的最大尺寸（m）。"""
    u0, v0, _, _ = ground_to_pix(c, gx, gz)
    u1, v1, _, _ = ground_to_pix(c, gx + eps, gz)
    u2, v2, _, _ = ground_to_pix(c, gx, gz + eps)
    a, b, cc, d = (u1 - u0) / eps, (u2 - u0) / eps, (v1 - v0) / eps, (v2 - v0) / eps
    s1 = a * a + b * b + cc * cc + d * d
    det = np.abs(a * d - b * cc)
    smin = np.sqrt(np.maximum((s1 - np.sqrt(np.maximum(s1 * s1 - 4 * det * det, 0))) / 2, 1e-12))
    return 1.0 / smin


def smooth_noise(shape, sigma, rng):
    n = ndimage.gaussian_filter(rng.standard_normal(shape), sigma, mode='wrap')
    return n / (n.std() + 1e-9)


# ─────────────────────────── 合成 BEV：場景 ───────────────────────────

WHITE = (228, 228, 222)
YELLOW = (232, 182, 40)
REDP = (196, 44, 46)
CONE_COLS = [(232, 82, 36), (240, 108, 30), (222, 60, 38), (246, 126, 40)]
CAR_COLS = {'white': (236, 236, 233), 'silver': (178, 181, 186), 'black': (32, 33, 36), 'blue': (40, 60, 120)}


def U(rng, a, b):
    return float(rng.uniform(a, b))


def _road_base(rng):
    g = U(rng, 78, 128)
    tint = U(rng, -4, 6)
    return dict(psi=math.radians(U(rng, -5, 5)), asphalt=(g + tint, g, g - tint * 0.5),
                paint=[], zones=[], cross=None, tall=[], patches=[], shadows=[],
                edgeL=None, edgeR=None, ignore=[], wear=U(rng, 0.0, 0.35))


def _add_line(Wd, kind, a, width, dashed, rng, b0=-99, b1=99):
    Wd['paint'].append(dict(kind=kind, a=a, w=width, dashed=dashed, dash=4.0, gap=6.0,
                            phase=U(rng, 0, 10), b0=b0, b1=b1))


def _add_double_yellow(Wd, a, rng, b0=-99, b1=99):
    lw = U(rng, 0.10, 0.15)
    gap = U(rng, 0.10, 0.15)
    off = (lw + gap) / 2
    _add_line(Wd, 'y', a - off, lw, False, rng, b0, b1)
    _add_line(Wd, 'y', a + off, lw, False, rng, b0, b1)


def _add_curb(Wd, side, a, rng, paint=None, beyond='tiles'):
    """a = 路面側的路緣位置（側向座標）；side=+1 右、-1 左。"""
    cw = 0.18
    a0, a1 = (a, a + cw) if side > 0 else (a - cw, a)
    col = {'r': REDP, 'y': YELLOW}.get(paint, (168, 166, 158))
    Wd['zones'].append(dict(type='curb', a0=a0, a1=a1, col=col))
    if paint:
        Wd['paint'].append(dict(kind=paint, a=(a0 + a1) / 2, w=cw, dashed=False, dash=4, gap=6,
                                phase=0, b0=-99, b1=99, zone=True))
    else:   # 沒漆的水泥路緣：偵測成白線不算錯也不算對
        Wd['ignore'].append(dict(phi=math.degrees(Wd['psi']), d=(a0 + a1) / 2))
    s0, s1 = (a1, a1 + 6) if side > 0 else (a0 - 6, a0)
    if beyond == 'tiles':
        red = rng.random() < 0.4
        base = (150, 92, 78) if red else (152, 150, 144)
        Wd['zones'].append(dict(type='tiles', a0=s0, a1=s1, col=base, ts=U(rng, 0.2, 0.4)))
    elif beyond == 'grass':
        Wd['zones'].append(dict(type='grass', a0=s0, a1=s1))
    else:   # 植栽帶 + 人行道
        pw = U(rng, 0.8, 1.5)
        g0, g1 = (s0, s0 + pw) if side > 0 else (s1 - pw, s1)
        t0, t1 = (g1, s1) if side > 0 else (s0, g0)
        Wd['zones'].append(dict(type='grass', a0=g0, a1=g1))
        Wd['zones'].append(dict(type='tiles', a0=t0, a1=t1, col=(152, 150, 144), ts=0.3))
        for _ in range(int(rng.integers(1, 4))):
            Wd['tall'].append(dict(type='bush', a=(g0 + g1) / 2, b=U(rng, -7, 7), r=pw / 2 * 0.9,
                                   hgt=U(rng, 0.4, 0.9)))
    if side > 0:
        Wd['edgeR'] = a
    else:
        Wd['edgeL'] = a


def _add_cars(Wd, a, rng, n, b_rng=(-9, 9), cols=None):
    bs = sorted(U(rng, *b_rng) for _ in range(n))
    last = -99
    for b in bs:
        if b - last < 5.0:
            continue
        last = b
        col = str(rng.choice(cols or ['white', 'silver', 'black', 'white', 'blue']))
        Wd['tall'].append(dict(type='car', a=a + U(rng, -0.15, 0.15), b=b, yaw=U(rng, -3, 3),
                               L=U(rng, 4.2, 4.8), W=U(rng, 1.7, 1.85), col=col))


def _decorate(Wd, rng):
    for _ in range(int(rng.integers(0, 3))):
        Wd['patches'].append(dict(kind=str(rng.choice(['light', 'dark', 'crack'])), x=U(rng, -5, 5),
                                  z=U(rng, -6, 6), sx=U(rng, 0.6, 2.5), sz=U(rng, 0.6, 2.5),
                                  ang=U(rng, 0, 180)))
    r = rng.random()
    if r < 0.35:
        for _ in range(int(rng.integers(1, 4))):
            Wd['shadows'].append(dict(kind='blob', x=U(rng, -6, 6), z=U(rng, -7, 7),
                                      r=U(rng, 0.8, 2.5), k=U(rng, 0.45, 0.7)))
    elif r < 0.55:
        Wd['shadows'].append(dict(kind='half', ang=U(rng, 0, 360), d=U(rng, 1.5, 5), k=U(rng, 0.45, 0.7)))
    if rng.random() < 0.6:   # 自車影子
        Wd['shadows'].append(dict(kind='ego', dx=U(rng, -0.6, 0.6), dz=U(rng, -0.6, 0.6), k=U(rng, 0.5, 0.75)))


def _cone(rng, a, b):
    return dict(type='cone', a=a, b=b, col=CONE_COLS[int(rng.integers(len(CONE_COLS)))])


def scene_urban(rng, cones=False):
    Wd = _road_base(rng)
    Wl = U(rng, 3.0, 3.5)
    e = U(rng, -0.35, 0.35)
    aL, aR = -e - Wl / 2, -e + Wl / 2
    lw = U(rng, 0.10, 0.15)
    if rng.random() < 0.5:     # 右邊還有車道
        _add_line(Wd, 'w', aR, lw, True, rng)
        a2 = aR + Wl
        _add_line(Wd, 'w', a2, lw, False, rng)
        _add_curb(Wd, 1, a2 + U(rng, 0.3, 1.0), rng, paint=rng.choice(['r', 'y', None]),
                  beyond=str(rng.choice(['tiles', 'mixed'])))
    else:
        _add_line(Wd, 'w', aR, lw, False, rng)
        sh = U(rng, 0.4, 2.6)
        _add_curb(Wd, 1, aR + sh, rng, paint=rng.choice(['r', 'r', 'y', None]),
                  beyond=str(rng.choice(['tiles', 'grass', 'mixed'])))
        if sh > 2.1:
            _add_cars(Wd, aR + sh / 2 + 0.05, rng, int(rng.integers(1, 3)))
    if rng.random() < 0.5:
        _add_line(Wd, 'w', aL, lw, True, rng)
        _add_double_yellow(Wd, aL - Wl, rng)
    else:
        _add_double_yellow(Wd, aL, rng)
        _add_line(Wd, 'w', aL - Wl, lw, True, rng)
    if cones:
        ac = aR + U(rng, -0.4, 0.6)
        n = int(rng.integers(3, 6))
        b0 = U(rng, -6, -2)
        step = U(rng, 1.8, 3.2)
        drift = U(rng, 0, 0.25) if rng.random() < 0.3 else 0.0
        for q in range(n):
            Wd['tall'].append(_cone(rng, ac + U(rng, -0.1, 0.1) + q * drift, b0 + q * step))
        if rng.random() < 0.5:
            Wd['tall'].append(_cone(rng, aL - U(rng, 0.3, 1.5), U(rng, -5, 5)))
    _decorate(Wd, rng)
    return Wd


def scene_two_lane(rng):
    Wd = _road_base(rng)
    Wl = U(rng, 3.0, 3.4)
    e = U(rng, -0.3, 0.3)
    aL, aR = -e - Wl / 2, -e + Wl / 2
    _add_double_yellow(Wd, aL, rng)
    _add_line(Wd, 'w', aR, U(rng, 0.10, 0.15), False, rng)
    sh = U(rng, 2.2, 2.7)
    _add_cars(Wd, aR + sh / 2 + 0.1, rng, int(rng.integers(1, 4)), cols=['white', 'silver', 'white', 'black'])
    _add_curb(Wd, 1, aR + sh, rng, paint=rng.choice(['r', 'r', 'y']), beyond=str(rng.choice(['tiles', 'mixed'])))
    _add_line(Wd, 'w', aL - Wl, U(rng, 0.10, 0.15), False, rng)
    _decorate(Wd, rng)
    return Wd


def scene_intersection(rng):
    Wd = _road_base(rng)
    Wl = U(rng, 3.0, 3.4)
    e = U(rng, -0.3, 0.3)
    aL, aR = -e - Wl / 2, -e + Wl / 2
    bs = U(rng, 3.2, 4.6)
    sw = U(rng, 0.30, 0.40)
    _add_line(Wd, 'w', aR, U(rng, 0.10, 0.15), False, rng, b1=bs)
    _add_double_yellow(Wd, aL, rng, b1=bs)
    _add_line(Wd, 'w', aL - Wl, U(rng, 0.10, 0.15), False, rng, b1=bs)
    _add_curb(Wd, 1, aR + U(rng, 0.4, 1.2), rng, paint=rng.choice(['r', None]), beyond='tiles')
    Wd['paint'].append(dict(kind='w', trans=True, b=bs + sw / 2, w=sw, a0=aL - 0.2, a1=aR + 0.3))
    c0 = bs + sw + U(rng, 0.8, 1.5)
    Wd['cross'] = dict(b0=c0, b1=c0 + U(rng, 3.0, 4.0), a0=aL - Wl - 1, a1=aR + 1.5,
                       sw=U(rng, 0.40, 0.45), gap=U(rng, 0.45, 0.7))
    _decorate(Wd, rng)
    return Wd


def scene_alley(rng):
    Wd = _road_base(rng)
    aW = U(rng, 2.0, 3.0)          # 右側磚牆
    Wd['zones'].append(dict(type='curb', a0=aW - 0.35, a1=aW, col=(150, 148, 140)))
    Wd['ignore'].append(dict(phi=math.degrees(Wd['psi']), d=aW - 0.175))
    _add_line(Wd, str(rng.choice(['y', 'w'])), aW - 0.5, U(rng, 0.10, 0.15), False, rng)
    Wd['tall'].append(dict(type='wall', a0=aW, a1=aW + 0.25, b0=-30, b1=30, hgt=2.6))
    if rng.random() < 0.6:
        Wd['tall'].append(dict(type='bush', a=aW - 0.3, b=U(rng, -4, 6), r=0.5, hgt=0.9))
    Wd['edgeR'] = aW - 0.35
    aL = -U(rng, 2.6, 3.8)
    _add_cars(Wd, aL + 1.0, rng, 1, b_rng=(-4, 4), cols=['white', 'silver'])
    _add_curb(Wd, -1, aL, rng, paint=rng.choice(['r', None]), beyond='grass')
    _decorate(Wd, rng)
    return Wd


def scene_parking(rng):
    Wd = _road_base(rng)
    e = U(rng, -0.3, 0.3)
    _add_line(Wd, 'y', -e - U(rng, 1.6, 1.9), U(rng, 0.10, 0.15), True, rng)
    gR = U(rng, 0.6, 1.4)
    _add_cars(Wd, EGO_HX + gR + 0.9, rng, 3, cols=['white', 'white', 'silver', 'black'])
    _add_curb(Wd, 1, EGO_HX + gR + 2.0, rng, paint='r', beyond='tiles')
    _add_cars(Wd, -e - 1.8 - 2.2, rng, 2, cols=['white', 'silver'])
    _decorate(Wd, rng)
    return Wd


SCENES = dict(urban=scene_urban, two_lane=scene_two_lane, intersection=scene_intersection,
              cones=lambda r: scene_urban(r, cones=True), alley=scene_alley, parking=scene_parking)


# ─────────────────────────── 合成 BEV：繪製 ───────────────────────────

def _soft(dist, px):
    """dist ≤ 0 在裡面；回傳邊緣抗鋸齒的覆蓋率。"""
    return np.clip(0.5 - dist / px, 0.0, 1.0)


def _road_frame(Wd, X, Z):
    c, s = math.cos(Wd['psi']), math.sin(Wd['psi'])
    return X * c - Z * s, X * s + Z * c


def _paint_cov(p, A, Bl, px):
    if p.get('trans'):
        cov = _soft(np.abs(Bl - p['b']) - p['w'] / 2, px)
        return cov * _soft(np.maximum(p['a0'] - A, A - p['a1']), px)
    cov = _soft(np.abs(A - p['a']) - p['w'] / 2, px)
    cov *= _soft(np.maximum(p['b0'] - Bl, Bl - p['b1']), px)
    if p['dashed']:
        per = p['dash'] + p['gap']
        t = np.mod(Bl - p['phase'], per)
        din = np.where(t <= p['dash'], -np.minimum(t, p['dash'] - t), np.minimum(t - p['dash'], per - t))
        cov *= _soft(din, px)
    return cov


def render_ground(Wd, X, Z, px, rng):
    h, w = X.shape
    A, Bl = _road_frame(Wd, X, Z)
    base = np.array(Wd['asphalt'], np.float64)
    tex = (smooth_noise((h, w), 18, rng) * U(rng, 4, 9) + smooth_noise((h, w), 4, rng) * U(rng, 2, 5)
           + rng.standard_normal((h, w)) * U(rng, 7, 12))
    img = base[None, None, :] + tex[..., None]
    spk = rng.random((h, w)) < 0.004          # 骨材亮點
    img[spk] += U(rng, 25, 50)
    for z in Wd['zones']:
        cov = _soft(np.maximum(z['a0'] - A, A - z['a1']), px)
        if z['type'] == 'curb':
            col = np.array(z['col'], np.float64)[None, None, :] + rng.standard_normal((h, w, 1)) * 6
        elif z['type'] == 'tiles':
            ts = z['ts']
            ta = np.floor(A / ts).astype(np.int64)
            tb = np.floor(Bl / ts).astype(np.int64)
            lut = rng.normal(0, 12, (64, 64))
            col = np.array(z['col'], np.float64)[None, None, :] + lut[ta % 64, tb % 64][..., None]
            fa = np.minimum(np.mod(A, ts), ts - np.mod(A, ts))
            fb = np.minimum(np.mod(Bl, ts), ts - np.mod(Bl, ts))
            joint = np.minimum(fa, fb) < 0.008
            col = np.where(joint[..., None], col * 0.7, col)
            col = col + rng.standard_normal((h, w, 1)) * 5
        else:   # 植栽帶
            g = smooth_noise((h, w), 2.5, rng)
            col = np.stack([60 + 18 * g, 112 + 30 * g, 48 + 14 * g], -1)
            soil = smooth_noise((h, w), 8, rng) > 1.0
            col[soil] = (95, 78, 58)
        img = img * (1 - cov[..., None]) + col * cov[..., None]
    wear = np.clip(1.0 - Wd['wear'] * np.clip(smooth_noise((h, w), 3, rng) * 0.5 + 0.5, 0, 1), 0, 1)
    for p in Wd['paint']:
        cov = _paint_cov(p, A, Bl, px) * wear
        col = np.array({'w': WHITE, 'y': YELLOW, 'r': REDP}[p['kind']], np.float64) * U(rng, 0.9, 1.03)
        img = img * (1 - cov[..., None]) + (col[None, None, :] + rng.standard_normal((h, w, 1)) * 5) * cov[..., None]
    if Wd['cross']:
        cr = Wd['cross']
        per = cr['sw'] + cr['gap']
        t = np.mod(A - cr['a0'], per)
        din = np.where(t <= cr['sw'], -np.minimum(t, cr['sw'] - t), np.minimum(t - cr['sw'], per - t))
        cov = _soft(din, px) * _soft(np.maximum(cr['b0'] - Bl, Bl - cr['b1']), px)
        cov *= _soft(np.maximum(cr['a0'] - A, A - cr['a1']), px) * wear
        img = img * (1 - cov[..., None]) + np.array(WHITE, np.float64)[None, None, :] * cov[..., None]
    for pt in Wd['patches']:
        ca, sa = math.cos(math.radians(pt['ang'])), math.sin(math.radians(pt['ang']))
        u = (X - pt['x']) * ca - (Z - pt['z']) * sa
        v = (X - pt['x']) * sa + (Z - pt['z']) * ca
        if pt['kind'] == 'crack':
            cov = _soft(np.abs(u + 0.15 * np.sin(v * 3)) - 0.012, px) * _soft(np.abs(v) - pt['sz'], px)
            img = img * (1 - 0.6 * cov[..., None])
        else:
            cov = _soft(np.maximum(np.abs(u) - pt['sx'] / 2, np.abs(v) - pt['sz'] / 2), px * 3)
            k = 1.35 if pt['kind'] == 'light' else 0.7
            img = img * (1 + (k - 1) * cov[..., None])
    sh = np.ones((h, w))
    for s in Wd['shadows']:
        if s['kind'] == 'blob':
            m = np.hypot(X - s['x'], Z - s['z']) + smooth_noise((h, w), 10, rng) * 0.3 < s['r']
        elif s['kind'] == 'half':
            a = math.radians(s['ang'])
            m = X * math.cos(a) + Z * math.sin(a) > s['d']
        else:
            m = (np.abs(X - s['dx']) < EGO_HX + 0.25) & (np.abs(Z - s['dz']) < EGO_HZ + 0.25)
        sh = np.where(m, np.minimum(sh, s['k']), sh)
    sh = ndimage.gaussian_filter(sh, 3)
    tint = np.array([0.94, 1.0, 1.10])[None, None, :] ** (1 - sh[..., None])   # 陰影偏藍
    return img * sh[..., None] * tint


def paint_masks(Wd, X, Z, px):
    """每條 GT 標線的覆蓋率（不含磨損），給 GT 可見度用。"""
    A, Bl = _road_frame(Wd, X, Z)
    return [_paint_cov(p, A, Bl, px) for p in Wd['paint']]


def _rect_poly(cxv, czv, L, W, yaw):
    c, s = math.cos(yaw), math.sin(yaw)
    return [(cxv + u * c + v * s, czv - u * s + v * c)
            for (u, v) in ((-W / 2, -L / 2), (W / 2, -L / 2), (W / 2, L / 2), (-W / 2, L / 2))]


def _circle_poly(cxv, czv, r, n=16):
    return [(cxv + r * math.cos(2 * math.pi * q / n), czv + r * math.sin(2 * math.pi * q / n)) for q in range(n)]


def _to_vehicle(Wd, a, b):
    cs, sn = math.cos(Wd['psi']), math.sin(Wd['psi'])
    return a * cs + b * sn, -a * sn + b * cs


def tall_slices(Wd):
    """所有高物切成水平薄片：(高度, 多邊形(車體座標), 物件編號, 標籤)。"""
    psi = Wd['psi']
    out = []
    for oi, o in enumerate(Wd['tall']):
        if o['type'] == 'car':
            x, z = _to_vehicle(Wd, o['a'], o['b'])
            yaw = psi + math.radians(o['yaw'])
            for h in np.arange(0.0, 0.80, 0.04):
                out.append((h, _rect_poly(x, z, o['L'], o['W'], yaw), oi, 'under' if h < 0.3 else 'body'))
            off = -0.08 * o['L']
            xc, zc = x + off * math.sin(yaw), z + off * math.cos(yaw)
            for h in np.arange(0.80, 1.44, 0.04):
                Lc = o['L'] * (0.55 if h < 1.2 else 0.42)
                Wc = o['W'] * (0.86 if h < 1.2 else 0.78)
                out.append((h, _rect_poly(xc, zc, Lc, Wc, yaw), oi, 'glass' if h < 1.24 else 'roof'))
        elif o['type'] == 'cone':
            x, z = _to_vehicle(Wd, o['a'], o['b'])
            out.append((0.0, _rect_poly(x, z, 0.38, 0.38, psi), oi, 'cbase'))
            for h in np.arange(0.03, 0.71, 0.02):
                r = 0.16 * (1 - h / 0.74) + 0.012
                band = 0.30 <= h <= 0.40 or 0.48 <= h <= 0.55
                out.append((h, _circle_poly(x, z, r), oi, 'cwhite' if band else 'corange'))
        elif o['type'] == 'wall':
            pts = [_to_vehicle(Wd, o['a0'], o['b0']), _to_vehicle(Wd, o['a1'], o['b0']),
                   _to_vehicle(Wd, o['a1'], o['b1']), _to_vehicle(Wd, o['a0'], o['b1'])]
            for h in np.arange(0.0, o['hgt'], 0.05):
                out.append((h, pts, oi, 'wall'))
        elif o['type'] == 'bush':
            x, z = _to_vehicle(Wd, o['a'], o['b'])
            hc = o['hgt'] / 2
            for h in np.arange(0.0, o['hgt'], 0.05):
                r = o['r'] * math.sqrt(max(0.05, 1 - ((h - hc) / hc) ** 2))
                out.append((h, _circle_poly(x, z, r, 20), oi, 'bush'))
    out.sort(key=lambda t: t[0])
    return out


def _proj_slice(pts, h, cam):
    gx, hc, gz = cam['pos']
    s = hc / (hc - h) if h < hc - 0.03 else hc / 0.03
    s = min(s, 30.0)
    return [(gx + (x - gx) * s, gz + (z - gz) * s) for x, z in pts]


def render_layer(Wd, ground, slices, cam, view, ss, rng):
    """某顆鏡頭看到的地面（高物沿該鏡頭射線拉長）。回傳 (rgb, 高物遮罩)。"""
    mpp, w, h, cx, cy = view
    Hs, Ws = ground.shape[:2]
    idimg = Image.new('I', (Ws, Hs), 0)
    dr = ImageDraw.Draw(idimg)
    for q, (hh, pts, oi, tag) in enumerate(slices):
        pp = _proj_slice(pts, hh, cam)
        dr.polygon([((x / mpp + cx) * ss, (cy - z / mpp) * ss) for x, z in pp], fill=q + 1)
    ids = np.array(idimg, np.int64)
    img = ground.copy()
    m = ids > 0
    if not m.any():
        return img, m
    jj, ii = np.nonzero(m)
    q = ids[jj, ii] - 1
    tags = np.array([t[3] for t in slices])[q]
    hs = np.array([t[0] for t in slices])[q]
    obj = np.array([t[2] for t in slices])[q]
    col = np.zeros((len(q), 3))
    nz = rng.standard_normal(len(q))
    for tag in set(tags.tolist()):
        sel = tags == tag
        if tag in ('under', 'cbase'):
            col[sel] = (24, 24, 26)
        elif tag in ('body', 'roof'):
            for oi in set(obj[sel].tolist()):
                s2 = sel & (obj == oi)
                base = np.array(CAR_COLS[Wd['tall'][oi]['col']], np.float64)
                col[s2] = base * (0.86 if tag == 'body' else 1.0) + nz[s2, None] * 4
        elif tag == 'glass':
            col[sel] = np.array((38, 44, 52)) + nz[sel, None] * 8
        elif tag == 'corange':
            for oi in set(obj[sel].tolist()):
                s2 = sel & (obj == oi)
                col[s2] = np.array(Wd['tall'][oi]['col'], np.float64) + nz[s2, None] * 5
        elif tag == 'cwhite':
            col[sel] = np.array((236, 236, 232)) + nz[sel, None] * 4
        elif tag == 'wall':
            # 由地面點反推牆上的高度與沿牆位置 → 磚紋
            gx, hc, gz = cam['pos']
            xg = ((ii[sel] + 0.5) / ss - cx) * mpp
            zg = (cy - (jj[sel] + 0.5) / ss) * mpp
            hh = hs[sel]
            f = (hc - hh) / hc
            xp = gx + (xg - gx) * f
            zp = gz + (zg - gz) * f
            bl = xp * math.sin(Wd['psi']) + zp * math.cos(Wd['psi'])
            row = np.floor(hh / 0.075).astype(np.int64)
            t = bl / 0.23 + 0.5 * (row % 2)
            cc = np.floor(t).astype(np.int64)
            lut = rng.normal(0, 14, 97)
            v = lut[(row * 7 + cc) % 97]
            br = np.stack([150 + v, 82 + v * 0.6, 66 + v * 0.4], -1)
            mort = (np.abs(t - np.round(t)) < 0.06) | (np.mod(hh, 0.075) < 0.012)
            br[mort] = (172, 166, 156)
            col[sel] = br
        elif tag == 'bush':
            g = rng.standard_normal(int(sel.sum()))
            col[sel] = np.stack([45 + 15 * g, 105 + 35 * g, 40 + 12 * g], -1)
    img[jj, ii] = col
    return img, m


def render_bev(Wd, view, rng, screen=False):
    """回傳 (rgb uint8 HxWx3, 高物遮罩, 有效遮罩, 各 GT 標線覆蓋率)。"""
    mpp, w, h, cx, cy = view
    ss = 2
    xs = ((np.arange(w * ss) + 0.5) / ss - cx) * mpp
    zs = (cy - (np.arange(h * ss) + 0.5) / ss) * mpp
    X, Z = np.meshgrid(xs, zs)
    ground = render_ground(Wd, X, Z, mpp / ss, rng)
    pm = [m.reshape(h, ss, w, ss).mean((1, 3)) for m in paint_masks(Wd, X, Z, mpp / ss)]
    slices = tall_slices(Wd)
    names = list(CAMS)
    th = np.full((len(names), h * ss, w * ss), 9.0)
    gsd = np.zeros_like(th)
    rmax = U(rng, 6.8, 9.0)
    for q, nm in enumerate(names):
        c = CAMS[nm]
        _, _, t, ok = ground_to_pix(c, X, Z)
        if not screen:
            ok &= np.hypot(X - c['pos'][0], Z - c['pos'][2]) <= rmax
        th[q] = np.where(ok, t, 9.0)
        gsd[q] = ground_gsd(c, X, Z)
    best = np.argmin(th, 0)
    covered = np.min(th, 0) < 9.0
    if screen:
        wts = np.exp(-(th - th.min(0)[None]) / 0.06)
        wts /= wts.sum(0)[None]
    out = np.zeros((h * ss, w * ss, 3))
    tallm = np.zeros((h * ss, w * ss), bool)
    levels = [0, 1, 2, 3, 4.5, 6.5, 10]
    for q, nm in enumerate(names):
        layer, tm = render_layer(Wd, ground, slices, CAMS[nm], view, ss, rng)
        gain = U(rng, 0.84, 1.16)
        wb = np.array([U(rng, 0.92, 1.08), 1.0, U(rng, 0.92, 1.08)])
        vig = 1.0 - U(rng, 0.15, 0.35) * (np.minimum(th[q], 2.0) / math.radians(95)) ** 2
        layer = layer * gain * wb[None, None, :] * np.clip(vig, 0.5, 1)[..., None]
        # 離鏡頭越遠越糊：σ ≈ 0.6 × 一個鏡頭像素在地面上的尺寸
        sig = np.clip(0.6 * gsd[q] / (mpp / ss) + (0.8 if screen else 0.0), 0, 10)
        li = np.interp(sig, levels, np.arange(len(levels)))
        lo = np.floor(li).astype(int)
        fr = li - lo
        blur = np.zeros_like(layer)
        for k in range(len(levels)):
            wk = np.where(lo == k, 1 - fr, 0.0) + np.where(lo + 1 == k, fr, 0.0)
            if (wk > 1e-6).any():
                img_k = layer if k == 0 else ndimage.gaussian_filter(layer, (levels[k], levels[k], 0))
                blur += img_k * wk[..., None]
        blur += rng.standard_normal(blur.shape[:2])[..., None] * U(rng, 2.0, 4.5)
        if screen:
            out += blur * wts[q][..., None]
            tallm |= tm & (wts[q] > 0.3)
        else:
            sel = best == q
            out[sel] = blur[sel]
            tallm |= tm & sel
    if screen:
        _draw_icon(out, view, ss)
    small = out.reshape(h, ss, w, ss, 3).mean((1, 3))
    tsmall = tallm.reshape(h, ss, w, ss).mean((1, 3)) > 0.4
    cov_s = covered.reshape(h, ss, w, ss).mean((1, 3)) > 0.5
    # YUV420：色度只有一半解析度
    Yc = small @ np.array([0.299, 0.587, 0.114])
    Uc = ndimage.gaussian_filter(small[..., 2] - Yc, 0.8)
    Vc = ndimage.gaussian_filter(small[..., 0] - Yc, 0.8)
    small = np.stack([Yc + Vc, Yc - (0.299 * Vc + 0.114 * Uc) / 0.587, Yc + Uc], -1)
    small += rng.standard_normal(small.shape[:2])[..., None] * U(rng, 1.0, 2.5)
    xsb, zsb = pix_coords(w, h, mpp, cx, cy)
    foot = (np.abs(zsb)[:, None] <= EGO_HZ + 0.05) & (np.abs(xsb)[None, :] <= EGO_HX + 0.05)
    validm = (cov_s & ~foot) if not screen else np.ones((h, w), bool)
    rgb = np.clip(np.round(small), 0, 255).astype(np.uint8)
    rgb[~validm] = 0
    if not screen:   # 黑的就是黑的：無覆蓋區保證 R+G+B < 24
        validm &= rgb.astype(np.int32).sum(-1) >= 24
    return rgb, tsmall, validm, pm


def _draw_icon(out, view, ss):
    """全景360 中間那台白色車子圖示（比車身略大，含後照鏡）。"""
    mpp, w, h, cx, cy = view
    im = Image.fromarray(np.clip(out, 0, 255).astype(np.uint8))
    d = ImageDraw.Draw(im)

    def P(x, z):
        return ((x / mpp + cx) * ss, (cy - z / mpp) * ss)
    hw, hl = 0.98, 2.46
    x0, y0 = P(-hw, hl)
    x1, y1 = P(hw, -hl)
    d.rounded_rectangle([x0, y0, x1, y1], radius=int(0.35 / mpp * ss), fill=(232, 232, 230), outline=(120, 120, 120))
    d.polygon([P(-0.78, 0.95), P(0.78, 0.95), P(0.68, 0.35), P(-0.68, 0.35)], fill=(70, 74, 80))
    d.polygon([P(-0.70, -1.15), P(0.70, -1.15), P(0.62, -1.6), P(-0.62, -1.6)], fill=(70, 74, 80))
    a0, b0 = P(-0.62, 0.3)
    a1, b1 = P(0.62, -1.1)
    d.rectangle([a0, b0, a1, b1], fill=(246, 246, 244))
    for sx in (-1, 1):
        d.polygon([P(sx * 0.98, 0.75), P(sx * 1.15, 0.7), P(sx * 1.12, 0.55), P(sx * 0.98, 0.55)], fill=(210, 210, 210))
        a0, b0 = P(sx * 0.74 - 0.04, 0.2)
        a1, b1 = P(sx * 0.74 + 0.04, -1.0)
        d.rectangle([min(a0, a1), b0, max(a0, a1), b1], fill=(90, 92, 96))
    out[:] = np.array(im, np.float64)


# ─────────────────────────── 樣本 + GT ───────────────────────────

VIEW_MAIN = (0.04, 300, 350, 150.0, 175.0)     # x∈[-6,6], z∈[-7,7]
VIEW_SCREEN = (0.03, 256, 340, 128.0, 170.0)   # 全景360 螢幕模式
EGO_RECT = [-EGO_HX, -EGO_HZ, EGO_HX, EGO_HZ]


def wrap90(a):
    return (a + 90.0) % 180.0 - 90.0


def veh_rects(Wd, grow=0.3):
    """路邊車的外接矩形（模擬呼叫端傳進來的 YOLO 車框）。"""
    out = []
    for o in Wd['tall']:
        if o['type'] != 'car':
            continue
        x, z = _to_vehicle(Wd, o['a'], o['b'])
        pts = _rect_poly(x, z, o['L'], o['W'], Wd['psi'] + math.radians(o['yaw']))
        xs = [p[0] for p in pts]
        zs = [p[1] for p in pts]
        out.append([min(xs) - grow, min(zs) - grow, max(xs) + grow, max(zs) + grow])
    return out


def build_gt(S, exclude):
    Wd, (mpp, w, h, cx, cy) = S['Wd'], S['view']
    ex = exclude_mask(w, h, mpp, cx, cy, exclude)
    vis = S['valid'] & ~S['tall'] & ~ex
    xs, zs = pix_coords(w, h, mpp, cx, cy)
    psi = math.degrees(Wd['psi'])
    lines = []
    for p, cov in zip(Wd['paint'], S['pm']):
        m = (cov >= 0.5) & vis
        jj, ii = np.nonzero(m)
        width = p['w']
        if len(jj) * mpp * mpp / width < 1.0:
            continue
        if p.get('trans'):
            phi = wrap90(psi - 90.0)
            d = p['b'] if abs(phi - (psi - 90.0)) < 1e-6 else -p['b']
        else:
            phi, d = psi, p['a']
        c, s = math.cos(math.radians(phi)), math.sin(math.radians(phi))
        sp = xs[ii] * s + zs[jj] * c
        lines.append(dict(k=p['kind'], d=bool(p.get('dashed', False)), phi=phi, off=d,
                          s0=float(sp.min()), s1=float(sp.max())))
    ign = [dict(phi=q['phi'], off=q['d']) for q in Wd['ignore']]
    if Wd['cross']:
        cr = Wd['cross']
        a = cr['a0'] + cr['sw'] / 2
        while a < cr['a1']:
            ign.append(dict(phi=psi, off=a, b0=cr['b0'], b1=cr['b1']))
            a += cr['sw'] + cr['gap']
    xmax = (w - cx) * mpp - 0.3
    edges = []
    for a in (Wd['edgeL'], Wd['edgeR']):
        e = None if a is None else a / math.cos(Wd['psi'])
        if e is not None and (abs(e) > xmax or abs(e) < 1.3):
            e = None
        edges.append(e)
    cones = []
    for o in Wd['tall']:
        if o['type'] != 'cone':
            continue
        x, z = _to_vehicle(Wd, o['a'], o['b'])
        i = int(math.floor(x / mpp + cx))
        j = int(math.floor(cy - z / mpp))
        if 0 <= i < w and 0 <= j < h and S['valid'][j, i] and not ex[j, i]:
            cones.append(dict(x=x, z=z))
    return dict(lines=lines, ignore=ign, edgeL=edges[0], edgeR=edges[1], cones=cones)


def make_sample(kind, seed, screen=False):
    rng = np.random.default_rng(seed)
    Wd = SCENES[kind](rng)
    view = VIEW_SCREEN if screen else VIEW_MAIN
    rgb, tallm, validm, pm = render_bev(Wd, view, rng, screen)
    return dict(kind=kind, seed=seed, screen=screen, view=view, rgb=rgb, Wd=Wd, tall=tallm, valid=validm, pm=pm)


def sample_argb(S):
    return to_int32(pack(S['rgb'])).ravel()


# ─────────────────────────── 評估 ───────────────────────────

def _line_err(det, g):
    """det（端點）對 GT 線：角度差、det 中點到 GT 線的距離、軸向是否重疊。"""
    phd = math.degrees(math.atan2(det['x2'] - det['x1'], det['z2'] - det['z1']))
    da = abs(wrap90(phd - g['phi']))
    c, s = math.cos(math.radians(g['phi'])), math.sin(math.radians(g['phi']))
    xm, zm = (det['x1'] + det['x2']) / 2, (det['z1'] + det['z2']) / 2
    dd = abs(xm * c - zm * s - g['off'])
    sa = det['x1'] * s + det['z1'] * c
    sb = det['x2'] * s + det['z2'] * c
    lo, hi = min(sa, sb), max(sa, sb)
    if 's0' in g:
        ov = min(hi, g['s1']) - max(lo, g['s0'])
    elif 'b0' in g:
        ov = min(hi, g['b1']) - max(lo, g['b0'])
    else:
        ov = 1.0
    return da, dd, ov


def match_lines(dets, gt, tolA=3.0, tolD=0.10):
    pairs = []
    for i, dt in enumerate(dets):
        for j, g in enumerate(gt['lines']):
            da, dd, ov = _line_err(dt, g)
            if da <= tolA and dd <= tolD and ov > 0:
                pairs.append((dd / tolD + da / tolA, i, j, da, dd))
    pairs.sort()
    used_d, used_g, m = set(), set(), []
    for _, i, j, da, dd in pairs:
        if i in used_d or j in used_g:
            continue
        used_d.add(i)
        used_g.add(j)
        m.append((i, j, da, dd))
    ign = set()
    for i, dt in enumerate(dets):
        if i in used_d:
            continue
        for g in gt['ignore']:
            da, dd, ov = _line_err(dt, g)
            if da <= 5 and dd <= 0.3 and ov > -0.5:
                ign.add(i)
                break
    return m, ign


def match_cones(dets, gts, tol=0.5):
    pairs = sorted((math.hypot(d['x'] - g['x'], d['z'] - g['z']), i, j)
                   for i, d in enumerate(dets) for j, g in enumerate(gts))
    ud, ug, m = set(), set(), []
    for e, i, j in pairs:
        if e > tol or i in ud or j in ug:
            continue
        ud.add(i)
        ug.add(j)
        m.append((i, j, e))
    return m


class Agg:
    def __init__(self):
        self.tp = self.fp = self.fn = 0
        self.da, self.dd = [], []
        self.kind_ok = self.dash_ok = self.n_m = 0
        self.e_err, self.e_miss, self.e_false, self.e_n, self.e_null = [], 0, 0, 0, 0
        self.c_tp = self.c_fp = self.c_fn = 0
        self.c_err = []

    def lines(self, dets, gt):
        m, ign = match_lines(dets, gt)
        self.tp += len(m)
        self.fn += len(gt['lines']) - len(m)
        self.fp += len(dets) - len(m) - len(ign)
        for i, j, da, dd in m:
            self.da.append(da)
            self.dd.append(dd)
            self.n_m += 1
            self.kind_ok += dets[i]['k'] == gt['lines'][j]['k']
            self.dash_ok += dets[i]['d'] == gt['lines'][j]['d']
        return m, ign

    def edges(self, res, gt):
        for e, g in ((res['edgeL'], gt['edgeL']), (res['edgeR'], gt['edgeR'])):
            if g is None:
                self.e_null += 1
                self.e_false += e is not None
            else:
                self.e_n += 1
                if e is None:
                    self.e_miss += 1
                else:
                    self.e_err.append(abs(e - g))

    def cones(self, dets, gts):
        m = match_cones(dets, gts)
        self.c_tp += len(m)
        self.c_fp += len(dets) - len(m)
        self.c_fn += len(gts) - len(m)
        self.c_err += [e for _, _, e in m]
        return m

    def report(self, title):
        def pct(a, b):
            return f'{100.0 * a / b:5.1f}%' if b else '  n/a'

        def st(v):
            return f'mean {np.mean(v):.3f} p90 {np.percentile(v, 90):.3f}' if v else 'n/a'
        L = [f'== {title}']
        L.append(f'  lines : recall {pct(self.tp, self.tp + self.fn)} precision {pct(self.tp, self.tp + self.fp)}'
                 f'  (TP {self.tp} FP {self.fp} FN {self.fn})')
        L.append(f'          offset err m {st(self.dd)} | angle err deg {st(self.da)}')
        L.append(f'          kind acc {pct(self.kind_ok, self.n_m)} dashed acc {pct(self.dash_ok, self.n_m)}')
        ok = sum(1 for e in self.e_err if e <= 0.25)
        L.append(f'  edges : within 0.25 m {pct(ok, self.e_n)} of {self.e_n} GT edges, missing {self.e_miss},'
                 f' err {st(self.e_err)}, false edge {self.e_false}/{self.e_null}')
        if self.c_tp + self.c_fn + self.c_fp:
            ok3 = sum(1 for e in self.c_err if e <= 0.3)
            L.append(f'  cones : recall {pct(self.c_tp, self.c_tp + self.c_fn)} precision'
                     f' {pct(self.c_tp, self.c_tp + self.c_fp)} (TP {self.c_tp} FP {self.c_fp} FN {self.c_fn})'
                     f' pos err {st(self.c_err)} within 0.3 m {pct(ok3, self.c_tp)}')
        return '\n'.join(L)


def annotate(S, res, cones, gt, path):
    mpp, w, h, cx, cy = S['view']
    k = 2
    im = Image.fromarray(S['rgb']).resize((w * k, h * k), Image.NEAREST)
    d = ImageDraw.Draw(im)

    def P(x, z):
        return ((x / mpp + cx) * k, (cy - z / mpp) * k)
    for g in (gt['lines'] if gt else []):
        c, s = math.cos(math.radians(g['phi'])), math.sin(math.radians(g['phi']))
        a = (g['off'] * c + g['s0'] * s, -g['off'] * s + g['s0'] * c)
        b = (g['off'] * c + g['s1'] * s, -g['off'] * s + g['s1'] * c)
        d.line([P(*a), P(*b)], fill=(0, 255, 0), width=1)
    colors = {'w': (0, 220, 255), 'y': (255, 230, 0), 'r': (255, 0, 200)}
    for ln in (res['lines'] if res else []):
        a, b = P(ln['x1'], ln['z1']), P(ln['x2'], ln['z2'])
        if ln['d']:
            n = 12
            for q in range(0, n, 2):
                d.line([(a[0] + (b[0] - a[0]) * q / n, a[1] + (b[1] - a[1]) * q / n),
                        (a[0] + (b[0] - a[0]) * (q + 1) / n, a[1] + (b[1] - a[1]) * (q + 1) / n)],
                       fill=colors[ln['k']], width=3)
        else:
            d.line([a, b], fill=colors[ln['k']], width=3)
    for key, col in (('edgeL', (255, 80, 80)), ('edgeR', (255, 80, 80))):
        if res and res[key] is not None:
            d.line([P(res[key], -4), P(res[key], 4)], fill=col, width=2)
        if gt and gt[key] is not None:
            d.line([P(gt[key], -4.5), P(gt[key], -4.0)], fill=(0, 255, 0), width=3)
    for c in (cones or []):
        x, y = P(c['x'], c['z'])
        d.ellipse([x - 7, y - 7, x + 7, y + 7], outline=(255, 0, 0), width=3)
    for c in (gt['cones'] if gt else []):
        x, y = P(c['x'], c['z'])
        d.ellipse([x - 3, y - 3, x + 3, y + 3], fill=(0, 255, 0))
    im.save(path)


def run_eval(n, variants, out=None, kinds=None, exmodes=('ego', 'veh')):
    kinds = kinds or list(SCENES)
    samples = []
    t0 = time.time()
    for kind in kinds:
        for q in range(n):
            samples.append(make_sample(kind, 1000 * (list(SCENES).index(kind) + 1) + q))
        for q in range(max(1, n // 2)):
            if kind in ('urban', 'two_lane', 'cones', 'intersection'):
                samples.append(make_sample(kind, 50000 + 1000 * (list(SCENES).index(kind) + 1) + q, screen=True))
    print(f'generated {len(samples)} samples in {time.time() - t0:.1f}s', flush=True)
    for P in variants:
        for exm in exmodes:
            tot = Agg()
            per = {}
            tms = []
            for S in samples:
                mpp, w, h, cx, cy = S['view']
                ex = [EGO_RECT] + (veh_rects(S['Wd']) if exm == 'veh' else [])
                gt = build_gt(S, ex)
                argb = sample_argb(S)
                t1 = time.time()
                res = road_extract(argb, w, h, mpp, cx, cy, ex, P)
                tms.append(time.time() - t1)
                cones = cone_detect(argb, w, h, mpp, cx, cy, ex, CONE if P['name'] == 'tuned' else CONE_SPEC)
                key = S['kind'] + ('/screen' if S['screen'] else '')
                a = per.setdefault(key, Agg())
                for A in (tot, a):
                    A.lines(res['lines'], gt)
                    A.edges(res, gt)
                    A.cones(cones, gt['cones'])
                if out and exm == 'ego':
                    os.makedirs(out, exist_ok=True)
                    annotate(S, res, cones, gt, os.path.join(out, f"{P['name']}_{key.replace('/', '_')}_{S['seed']}.png"))
            print(tot.report(f"{P['name']} exclude={exm}  ALL ({len(samples)} images, py {1000 * np.median(tms):.0f} ms)"))
            for key, a in per.items():
                print(a.report(f"{P['name']} exclude={exm}  {key}"))
            print(flush=True)
    return samples


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('cmd', choices=['score', 'tl', 'fixtures', 'real', 'sample'])
    ap.add_argument('--n', type=int, default=8)
    ap.add_argument('--variant', default='both')
    ap.add_argument('--kinds', default=None)
    ap.add_argument('--out', default=None)
    ap.add_argument('--img', default=None)
    ap.add_argument('--corners', default=None)
    ap.add_argument('--view', default=None, help='W,H,mpp,cx,cy（校正後影像）')
    a = ap.parse_args()
    if a.cmd == 'score':
        vs = {'both': [SPEC, TUNED], 'spec': [SPEC], 'tuned': [TUNED]}[a.variant]
        run_eval(a.n, vs, a.out, a.kinds.split(',') if a.kinds else None)
    elif a.cmd == 'sample':
        kind, seed = a.kinds.split(':')
        S = make_sample(kind, int(seed), screen=a.variant == 'screen')
        Image.fromarray(S['rgb']).save(a.out)
    elif a.cmd == 'tl':
        tl_eval(a.n)
    elif a.cmd == 'fixtures':
        write_fixtures(a.out)
    elif a.cmd == 'real':
        run_real(a.img, a.corners, a.out, a.view)


TL_STATES = ['red', 'yellow', 'green', 'off']


def tl_make(state, vertical, rng, L=None):
    """合成一張紅綠燈偵測框裁切（燈箱 + 一點背景）。回傳 uint8 HxWx3。"""
    L = L or int(rng.integers(14, 72))
    S = max(6, int(round(L * U(rng, 0.32, 0.40))))
    mg = U(rng, 0.0, 0.18)
    W_, H_ = (S, L) if vertical else (L, S)
    cw = int(round(W_ * (1 + 2 * mg))) + 1
    ch = int(round(H_ * (1 + 2 * mg))) + 1
    ss = 4
    yy, xx = np.mgrid[0:ch * ss, 0:cw * ss]
    xx = (xx + 0.5) / ss
    yy = (yy + 0.5) / ss
    bg = rng.integers(3)
    if bg == 0:      # 藍天
        top, bot = np.array([120, 170, 225]), np.array([190, 215, 240])
    elif bg == 1:    # 陰天
        top, bot = np.array([205, 210, 215]), np.array([225, 228, 230])
    else:            # 樓房 / 樹
        top, bot = rng.uniform(40, 130, 3), rng.uniform(40, 130, 3)
    t = (yy / ch)[..., None]
    img = top * (1 - t) + bot * t
    x0 = (cw - W_) / 2 + U(rng, -1, 1) * mg * W_
    y0 = (ch - H_) / 2 + U(rng, -1, 1) * mg * H_
    hous = (xx >= x0) & (xx < x0 + W_) & (yy >= y0) & (yy < y0 + H_)
    hc = U(rng, 18, 48)
    img[hous] = (hc, hc, hc + U(rng, -3, 6))
    r = 0.36 * S
    lit = {'red': 0, 'yellow': 1, 'green': 2}.get(state, -1)
    unlit = [np.array([55, 18, 18]), np.array([55, 44, 14]), np.array([14, 48, 42])]
    phantom = U(rng, 0, 45) if rng.random() < 0.35 else 0.0
    glow = np.zeros(img.shape[:2])
    gcol = None
    for q in range(3):
        f = (q + 0.5) / 3
        pcx, pcy = (x0 + W_ / 2, y0 + f * H_) if vertical else (x0 + f * W_, y0 + H_ / 2)
        d = np.hypot(xx - pcx, yy - pcy)
        m = d <= r
        if q == lit:
            if q == 0:
                col = np.array([255, U(rng, 25, 75), U(rng, 15, 60)])
            elif q == 1:
                col = np.array([255, U(rng, 140, 200), U(rng, 0, 45)])
            else:
                col = np.array([U(rng, 0, 90), 255, U(rng, 130, 230)])
            core = np.clip(1 - d / (r * U(rng, 0.3, 0.75)), 0, 1)[..., None] * U(rng, 0.3, 1.0)
            pix = col * (1 - core) + np.array([255, 250, 240]) * core
            img[m] = pix[m]
            glow = np.maximum(glow, np.exp(-np.maximum(d - r, 0) ** 2 / (2 * (r * U(rng, 0.3, 1.0)) ** 2)) * (~m))
            gcol = col
        else:
            img[m] = unlit[q] + phantom
    if gcol is not None:
        g = glow[..., None] * U(rng, 0.15, 0.6)
        img = img * (1 - g) + gcol * g
    img *= U(rng, 0.75, 1.15)
    small = img.reshape(ch, ss, cw, ss, 3).mean((1, 3))
    small = ndimage.gaussian_filter(small, (U(rng, 0, 1.2),) * 2 + (0,))
    small += rng.standard_normal(small.shape) * U(rng, 2, 6)
    return np.clip(np.round(small), 0, 255).astype(np.uint8)


def tl_eval(n, P=TL, verbose=True):
    rng = np.random.default_rng(777)
    conf = {s: {t: 0 for t in TL_STATES} for s in TL_STATES}
    for k in range(n):
        st = TL_STATES[k % 4]
        img = tl_make(st, bool(rng.random() < 0.5), rng)
        h, w = img.shape[:2]
        got, _ = tl_classify(to_int32(pack(img)).ravel(), w, h, P)
        conf[st][got] += 1
    ok = sum(conf[s][s] for s in TL_STATES)
    if verbose:
        print(f'traffic light: accuracy {100.0 * ok / n:.1f}% ({ok}/{n})')
        for s in TL_STATES:
            print(f'  {s:6s} -> ' + ' '.join(f'{t}:{conf[s][t]}' for t in TL_STATES))
    return ok / n


FIXTURE_PLAN = [('urban', False, 2), ('two_lane', False, 1), ('intersection', False, 1), ('cones', False, 2),
                ('parking', False, 1), ('alley', False, 1), ('urban', True, 1), ('cones', True, 1)]


def _perfect(S, res, cones, gt):
    """這張樣本的 Python 輸出是否完全對上 GT（線全中、種類/虛實對、沒有多的線、路緣 ±0.25、錐 ±0.3）。"""
    m, ign = match_lines(res['lines'], gt)
    if len(m) != len(gt['lines']) or len(m) + len(ign) != len(res['lines']):
        return False
    for i, j, _, _ in m:
        if res['lines'][i]['k'] != gt['lines'][j]['k'] or res['lines'][i]['d'] != gt['lines'][j]['d']:
            return False
    for k in ('edgeL', 'edgeR'):
        g, e = gt[k], res[k]
        if g is None and e is not None:
            return False
        if g is not None and e is not None and abs(e - g) > 0.25:
            return False
    cm = match_cones(cones, gt['cones'], 0.3)
    return len(cm) == len(gt['cones']) == len(cones)


def write_fixtures(out):
    os.makedirs(out, exist_ok=True)
    index = []
    for kind, screen, k in FIXTURE_PLAN:
        got = 0
        seed = 70000 + 1000 * list(SCENES).index(kind) + (500 if screen else 0)
        while got < k:
            S = make_sample(kind, seed, screen)
            seed += 1
            mpp, w, h, cx, cy = S['view']
            ex = [EGO_RECT] + veh_rects(S['Wd'])
            gt = build_gt(S, ex)
            argb = sample_argb(S)
            res = road_extract(argb, w, h, mpp, cx, cy, ex)
            cones = cone_detect(argb, w, h, mpp, cx, cy, ex)
            if kind == 'cones' and len(gt['cones']) < 3:
                continue
            if not _perfect(S, res, cones, gt):
                continue
            name = f"road_{kind}{'_screen' if screen else ''}_{got}"
            Image.fromarray(S['rgb']).save(os.path.join(out, name + '.png'), optimize=True)
            doc = dict(
                name=name, seed=seed - 1, w=w, h=h, mpp=mpp, cx=cx, cy=cy, exclude=ex,
                gt=dict(lines=gt['lines'], ignore=gt['ignore'], edgeL=gt['edgeL'], edgeR=gt['edgeR'], cones=gt['cones']),
                py=dict(lines=[{q: L[q] for q in ('k', 'd', 'x1', 'z1', 'x2', 'z2', 'c', 'phi', 'off')} for L in res['lines']],
                        edgeL=res['edgeL'], edgeR=res['edgeR'], cones=cones))
            with open(os.path.join(out, name + '.json'), 'w') as f:
                json.dump(doc, f, indent=1)
            index.append(name)
            got += 1
            print('fixture', name, 'seed', seed - 1, len(res['lines']), 'lines', len(cones), 'cones', flush=True)
    # 紅綠燈：一張拼圖 + 每格的框、真值、Python 結果
    rng = np.random.default_rng(4242)
    crops = []
    for st in TL_STATES:
        for vert in (True, False):
            for L in (16, 28, 44, 64):
                crops.append((st, vert, tl_make(st, vert, rng, L)))
    atlas_w = 420
    x = y = rowh = 0
    rects = []
    for st, vert, im in crops:
        hh, ww = im.shape[:2]
        if x + ww > atlas_w:
            x = 0
            y += rowh + 2
            rowh = 0
        rects.append((x, y, ww, hh))
        x += ww + 2
        rowh = max(rowh, hh)
    atlas = np.zeros((y + rowh, atlas_w, 3), np.uint8)
    items = []
    for (st, vert, im), (x, y, ww, hh) in zip(crops, rects):
        atlas[y:y + hh, x:x + ww] = im
        s, cf = tl_classify(to_int32(pack(im)).ravel(), ww, hh)
        items.append(dict(x=x, y=y, w=ww, h=hh, state=st, vertical=vert, py=s, pyConf=cf))
    Image.fromarray(atlas).save(os.path.join(out, 'tl_atlas.png'), optimize=True)
    with open(os.path.join(out, 'tl_atlas.json'), 'w') as f:
        json.dump(dict(items=items), f, indent=1)
    with open(os.path.join(out, 'index.json'), 'w') as f:
        json.dump(dict(road=index), f, indent=1)
    print('tl crops', len(items), 'py correct', sum(it['py'] == it['state'] for it in items))


def homography(src, dst):
    A, b = [], []
    for (x, y), (u, v) in zip(src, dst):
        A.append([x, y, 1, 0, 0, 0, -u * x, -u * y])
        b.append(u)
        A.append([0, 0, 0, x, y, 1, -v * x, -v * y])
        b.append(v)
    hv = np.linalg.solve(np.array(A, float), np.array(b, float))
    return np.append(hv, 1).reshape(3, 3)


def rectify(photo, corners, W, H):
    """照片裡螢幕上的俯視區（平面）→ 正視矩形；corners = 左上、右上、右下、左下。"""
    img = np.asarray(photo.convert('RGB')).astype(np.float64)
    Hm = homography([(0, 0), (W, 0), (W, H), (0, H)], corners)
    jj, ii = np.mgrid[0:H, 0:W]
    p = np.stack([ii + 0.5, jj + 0.5, np.ones_like(ii)], 0).reshape(3, -1).astype(float)
    q = Hm @ p
    x = q[0] / q[2] - 0.5
    y = q[1] / q[2] - 0.5
    x0 = np.floor(x).astype(int)
    y0 = np.floor(y).astype(int)
    fx = (x - x0)[:, None]
    fy = (y - y0)[:, None]

    def g(yy, xx):
        return img[np.clip(yy, 0, img.shape[0] - 1), np.clip(xx, 0, img.shape[1] - 1)]
    o = (g(y0, x0) * (1 - fx) * (1 - fy) + g(y0, x0 + 1) * fx * (1 - fy)
         + g(y0 + 1, x0) * (1 - fx) * fy + g(y0 + 1, x0 + 1) * fx * fy)
    return np.clip(np.round(o.reshape(H, W, 3)), 0, 255).astype(np.uint8)


def run_real(img, corners, out, spec):
    """實拍螢幕照：4 角校正 → 跑標線 / 路緣 / 三角錐 → 存標註圖。spec = 'W,H,mpp,cx,cy'。"""
    c = [float(v) for v in corners.split(',')]
    W, H, mpp, cx, cy = (float(v) for v in spec.split(','))
    W, H = int(W), int(H)
    rgb = rectify(Image.open(img), [(c[0], c[1]), (c[2], c[3]), (c[4], c[5]), (c[6], c[7])], W, H)
    argb = to_int32(pack(rgb)).ravel()
    ex = [EGO_RECT]
    dbg = {}
    res = road_extract(argb, W, H, mpp, cx, cy, ex, TUNED, dbg)
    cones = cone_detect(argb, W, H, mpp, cx, cy, ex)
    os.makedirs(out, exist_ok=True)
    base = os.path.join(out, os.path.splitext(os.path.basename(img))[0])
    Image.fromarray(rgb).save(base + '_rect.png')
    S = dict(view=(mpp, W, H, cx, cy), rgb=rgb)
    annotate(S, res, cones, None, base + '_annot.png')
    vis = np.zeros((H, W, 3), np.uint8)
    cls = dbg['cls']
    vis[cls == 1] = (110, 110, 110)
    vis[cls == 2] = (110, 110, 0)
    vis[cls == 3] = (110, 0, 70)
    vis[dbg['keep'] & (cls == 1)] = (255, 255, 255)
    vis[dbg['keep'] & (cls == 2)] = (255, 255, 0)
    vis[dbg['keep'] & (cls == 3)] = (255, 0, 160)
    Image.fromarray(vis).save(base + '_cand.png')
    print(f'{img}: {W}x{H} mpp {mpp}')
    for L in res['lines']:
        print(f"  line {L['k']} {'dashed' if L['d'] else 'solid '} ({L['x1']:.2f},{L['z1']:.2f})-({L['x2']:.2f},{L['z2']:.2f})"
              f" phi {L['phi']:.1f} off {L['off']:.2f} conf {L['c']:.2f}")
    print(f"  edgeL {res['edgeL']} edgeR {res['edgeR']}")
    for q in cones:
        print(f"  cone ({q['x']:.2f},{q['z']:.2f}) conf {q['c']:.2f}")



if __name__ == '__main__':
    main()
