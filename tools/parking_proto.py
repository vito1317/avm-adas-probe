#!/usr/bin/env python3
"""停車格偵測原型：ParkingSlotDetector.kt 的 Python 對照實作 + 合成 BEV 產生器 + 評估 + 單元測試 fixture。

用法（/opt/homebrew/bin/python3，需要 numpy / scipy / Pillow）：
  parking_proto.py stats [N] [300|256] [-v]   合成資料評估（每種場景 N 張），印出各類型 P/R 與角點誤差
  parking_proto.py preview [N] [300|256]      存合成場景 + 偵測結果疊圖到 scratch 目錄
  parking_proto.py adv [N] [300|256] [類型,...] [-v] [-p]
                                              對抗場景評估（重度模糊、15 % 接縫 + 校正誤差、停歪的車 / 漏框、行人穿越道、
                                              雙線、路緣紅黃線 + 停止線 + 機車停等區、車頭轉 0–90°、空地陰影、機車格變體、
                                              輪擋、格內文字、網狀線 / 斜紋、地下室柱子、植草磚）；-p 存疊圖
  parking_proto.py real                       車機螢幕翻拍照（8、13）校正成俯視圖後跑偵測，存疊圖
  parking_proto.py fixtures                   產生 Kotlin 單元測試用的 PNG + 期望 JSON（app/src/test/resources/parking/）
  parking_proto.py dump N 300|256 目錄        合成 + 對抗場景與原型輸出寫成 PNG + JSON（Kotlin 大量一致性比對用）
  parking_proto.py parity 目錄                比對目錄裡 Kotlin 輸出（*.kt.json）與原型
調參：環境變數 PP_SET="RIDGE_T=10,MIN_VOTES_M=0.3" 可暫時覆寫參數。

座標：車身座標 x 右、z 前（公尺），BEV 影像第 0 列 = 前方、第 0 行 = 左方，
像素 (i, j) 中心 ↔ x = (i + 0.5 - cx) * mpp, z = (cy - (j + 0.5)) * mpp。

演算法（Kotlin 版逐步照抄；整數運算部分完全一致，浮點部分同樣的運算順序）：
 1. 標線遮罩：亮度 + 黃色加成的「漆」通道 P，3x3 盒狀加總 S，四個方向的對稱脊線測試
    r = min(S(p) - S(p+d), S(p) - S(p-d))，d ≈ 0.16 m（寬於 ~0.3 m 的斑馬線、停止線幾乎都過不了），
    法線角由四個方向的二階差分當 cos 2θ 取樣算出（連續角度），沿法線非極大值抑制得 1 px 中心線。
    白線要中性色、黃線過色度測試；離無覆蓋區 d+1 px 內「看不到線」（取樣時當未知）。
 2. 線段：中心線像素做 Hough（每像素只投票到自己法線角 ±10°），峰值依序收集像素、沿線切群（> 2 m 的空隙
    分成不同的線，例如走道兩側的分隔線）、兩次 PCA 擬合、依 0.3 m 的間隙切段；最後把真正共線的
    （被自車、車輛擋成兩段的同一條線）合併，線頭再沿遮罩延伸。
 3. 停車格假設：一對平行線（間距 1.8–3.5 m）= 長邊對；與之相交（≥ 25°）的線 = 橫邊（入口線、底線、
    平行格的分隔線），或兩條線同時真的結束的「開口端」。沿長邊對依序排列橫邊，相鄰兩個橫邊圍成一格；
    只看到入口側（T/L 標記、另一端出畫面）的格子用標準深度 5.5 m（平行格 6 m）補遠端。
    橫邊與長邊的線寬（漆通道剖面半高寬，每條線每 0.25 m 取樣一次）差 1.6 倍以上且寬的那邊 ≥ 0.24 m
    → 停止線、機車停等區、斑馬線，不當格子的邊。單側斜角格兩條分隔線都要伸進格內 ≥ 1 m。
 4. 驗證：長邊與四角的遮罩支持度；格內不得有平行或橫過的線（機車格、兩格併成一格），格內平行線看得到
    ≥ 1.2 m 也算（斜紋禁停區）；從真的橫邊伸進格內的細短線頭（機車格 T 字，左右 0.2 m 沒有）→ 不是汽車格；
    格內雜線比例不得太高（草叢、樹影），格子外圍 1 m 一圈也不得太高（植草磚、連鎖磚）；
    單側格長邊 > 7 m 視為車道線；只有分隔線的格子要成排或明顯斜於車身，鄰格入口方向要一致（柱子擋住
    分隔線頭會湊出鋸齒狀的斜格）。整張圖地面 ≥ 20 % 是標線遮罩（整片紋理）直接結束。
    車輛框內：命中照算、沒命中當作被車擋住（未知）。
 5. 類型：垂直 / 平行由「與鄰格共用長邊（垂直）或短邊（平行）」、面向自車的邊、尺寸投票；
    斜角格由橫邊與長邊夾角（25–75°）判定。入口 = 面向自車的那一邊（在自車左右 → 較平行車身的邊，
    在車頭 / 車尾 → 較垂直車身的邊）；和自車並排的垂直格取車頭那端（多半倒車入庫）。
 6. 與車輛框或自車（含原點的排除區；導引線等其他排除區不算）重疊 > 30 % 面積 → occupied；多邊形 IoU > 0.3 做 NMS。

合成評估（stats 40，每種場景 40 張；300x350 @ 0.04 m/px 自家拼接 BEV：接縫、遠處模糊、黑角、磨損、陰影、
停著的車、車道線 / 斑馬線 / 停止線 / 機車格干擾）：
  垂直 P 1.00 R 0.72、平行 P 1.00 R 0.70、斜角 P 1.00 R 0.71；入口角點誤差中位數約 0.01 m、≤ 0.25 m 約 97 %。
  256 寬 @ 0.03 m/px（視野只有 ±3.8 m，旁邊的格子多半只露出一角）：垂直 P 0.97 R 0.62、平行 P 1.00 R 0.57。
對抗場景（adv 30，14 種 × 30 張，300x350）：誤報 2 格（行人穿越道斑馬紋剛好和平行格長邊共線、機車停等區寬度
  剛好等於標準深度），召回 0.56（被停歪的車整個蓋住、地下室柱子擋住分隔線頭、重度模糊的格子找不到）。
"""
import json
import math
import os
import sys

import numpy as np

# 疊圖輸出目錄、車機螢幕翻拍照目錄（可用環境變數改）
SCRATCH = os.environ.get('PARKING_SCRATCH', '/private/tmp/claude-501/-Users-vito-development/'
                         '7b2a2a6f-c0ce-40ca-9bfc-33d8d3ea9c70/scratchpad/v03-parking')
REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
FIXDIR = os.path.join(REPO, 'app', 'src', 'test', 'resources', 'parking')

# ---------------------------------------------------------------- 參數（公尺，除非註明）
PAINT_MIN = 50          # 漆通道最低亮度
NEUTRAL_MAX = 60        # 白線：max-min ≤ 此值
RIDGE_D_M = 0.16        # 脊線測試偏移
RIDGE_T = 14            # 脊線對比門檻（灰階，S 是 9 倍）
SECTOR = 10             # Hough 每像素投票的 θ 半寬（度）
MIN_VOTES_M = 0.35      # Hough 峰值最低支持長度
MAX_PEAKS = 120
MAX_LINES = 48
GATHER_TOL0 = 1.5       # 由峰值收集像素的距離（px）
GATHER_TOLF_M = 0.05    # 擬合後收集距離（m，最少 1.2 px）
GAP_M = 0.3             # 線段切斷的間隙
LINE_GAP_M = 2.0        # 共線但間隔超過此距離 → 先當成不同的線
MERGE_DEG = 1.5         # 之後再把真正共線（角度、距離都很近）的合併
MERGE_TOL_M = 0.06
MIN_PIECE_M = 0.3       # 最短線段
PAR_DEG = 4.0           # 平行判定
PAIR_WMIN, PAIR_WMAX = 1.8, 3.5
PAIR_OVL = -2.5
CROSS_MIN_DEG = 25.0    # 橫邊與長邊最小夾角
PERP_TOL_DEG = 15.0     # 垂直判定容差
DEPTH_DEF = 5.5         # 垂直 / 斜角格預設深度（沿長邊）
DEPTH_PAR = 6.0         # 平行格預設長度
LANE_LEN = 7.0          # 單側格長邊超過此長度 → 車道線
CROSS_REACH = 1.2       # 橫線自身線段可以離交點多遠（m）：超過就不是真的橫過這個格子
GLYPH_M = 0.60          # 遮罩連通元件的外接框兩個方向都小於這個值 → 字形（車位號碼、箭頭、污漬），不是標線
RUN_GAP = 1.0           # 沿線走時可跨過的間隙（磨損、被車擋）
END_GAP = 0.8           # 端點後至少這麼長沒有線才算「真的結束」
EXT_MISS = 2            # 線頭延伸時可容許連續沒命中的取樣點數
CLUTTER_MAX = 0.08      # 格內雜線比例上限
CORRIDOR = 1.2          # 自車左右 ±此寬度內算「車頭 / 車尾方向」
OCC_FRAC = 0.30
NMS_IOU = 0.30
MAX_SLOTS = 16
WIDE_MIN = 0.24         # 橫邊與長邊半高寬差 WIDE_RATIO 倍以上、寬的那邊 ≥ 此值 → 停止線 / 停等區 / 斑馬線，不當格子的邊
WIDE_RATIO = 1.6
WSTEP = 0.25            # 線寬取樣間距（每條線算一次，之後查表）
STUB_IN = 0.45          # 格內短分隔線頭（機車格 T 字）探針長度：從橫邊外約 0.1 m 起往格內這麼長
INNER_PAR = 1.2         # 格內平行線看得到的長度 ≥ 此值 → 格內有線（斜紋禁停區、兩格併一格）
SEP_MIN_A = 1.0         # 單側斜角格：兩條分隔線從橫邊伸進格內至少這麼長
RING = 1.0              # 格子外圍一圈的寬度（雜線比例用）
RING_MAX = 0.25
TEXTURE_MAX = 0.2       # 整張圖看得到的地面中標線遮罩比例上限（一般停車場 ≤ 0.13）

# 調參用：PP_SET="RIDGE_T=10,MIN_VOTES_M=0.3"
for _kv in filter(None, os.environ.get('PP_SET', '').split(',')):
    _k, _v = _kv.split('=')
    globals()[_k] = type(globals()[_k])(float(_v)) if isinstance(globals()[_k], float) else int(_v)

SIN_PAR = math.sin(math.radians(PAR_DEG))
COS_OBLIQUE = math.cos(math.radians(20.0))
OPEN_WMAX = 2.75        # 只有分隔線（沒有入口 / 底線）的格子：寬度上限（車道寬多半 ≥ 2.8 m）
COS_CROSS = math.cos(math.radians(CROSS_MIN_DEG))
SIN_PERP = math.sin(math.radians(PERP_TOL_DEG))
SIN_12 = math.sin(math.radians(12.0))
NEG = -(1 << 28)


def seqsum(a):
    """由左到右逐項相加（與 Kotlin 迴圈一致，numpy.sum 是成對相加）。"""
    return float(np.cumsum(a)[-1]) if len(a) else 0.0


def f32(v):
    """Kotlin 端參數是 Float：先四捨五入成 float32 再用 double 計算，兩邊結果才會一致。"""
    return float(np.float32(v))


# ---------------------------------------------------------------- 影像層（整數運算，與 Kotlin 完全一致）
def _shift(a, di, dj, fill):
    """out[j, i] = a[j + dj, i + di]，超出範圍填 fill。"""
    h, w = a.shape
    out = np.full_like(a, fill)
    y0, y1 = max(0, -dj), min(h, h - dj)
    x0, x1 = max(0, -di), min(w, w - di)
    if y1 > y0 and x1 > x0:
        out[y0:y1, x0:x1] = a[y0 + dj:y1 + dj, x0 + di:x1 + di]
    return out


def rect_px(r, w, h, mpp, cx, cy):
    """公尺矩形 → 像素中心落在矩形內的閉區間 (i0, i1, j0, j1)（可能為空）。"""
    x0, z0, x1, z1 = (f32(v) for v in r)
    i0 = math.ceil(x0 / mpp + cx - 0.5)
    i1 = math.floor(x1 / mpp + cx - 0.5)
    j0 = math.ceil(cy - z1 / mpp - 0.5)
    j1 = math.floor(cy - z0 / mpp - 0.5)
    return max(0, i0), min(w - 1, i1), max(0, j0), min(h - 1, j1)


class Line:
    __slots__ = ('cx', 'cz', 'ux', 'uz', 'pieces', 'n')

    def __init__(self, cx, cz, ux, uz, pieces, n):
        self.cx, self.cz, self.ux, self.uz, self.pieces, self.n = cx, cz, ux, uz, pieces, n


def _glyph_mask(M, mpp):
    """脊線遮罩裡「兩個方向都小於 GLYPH_M」的連通元件 → 字形。

    停車格裡常常直接漆著車位號碼（實車地下室擷取就是「565」），箭頭、補土、油漬也一樣。
    這些東西會讓「格內有沒有東西」的檢查誤判成機車格的 T 字或格內橫線，把好好的格子刷掉。
    實測那張擷取：干擾物最大 0.12 x 0.36 m，而最小的真標線元件是 1.00 m —— 中間空得很。
    8 連通，和 Kotlin 端同一條規則（元件集合與走訪順序無關，所以兩邊結果一致）。
    """
    h, w = M.shape
    flat = M.reshape(-1)
    lab = np.full(h * w, -1, np.int32)
    G = np.zeros(h * w, bool)
    lim = GLYPH_M / mpp
    stack = np.empty(h * w, np.int32)
    cells = np.empty(h * w, np.int32)
    nlab = 0
    for p0 in range(h * w):
        if not flat[p0] or lab[p0] >= 0:
            continue
        lab[p0] = nlab
        stack[0] = p0
        sp = 1
        nc = 0
        i0 = i1 = p0 % w
        j0 = j1 = p0 // w
        while sp > 0:
            sp -= 1
            p = stack[sp]
            cells[nc] = p
            nc += 1
            i = p % w
            j = p // w
            if i < i0: i0 = i
            if i > i1: i1 = i
            if j < j0: j0 = j
            if j > j1: j1 = j
            for dj in (-1, 0, 1):
                nj = j + dj
                if nj < 0 or nj >= h:
                    continue
                for di in (-1, 0, 1):
                    ni = i + di
                    if ni < 0 or ni >= w:
                        continue
                    q = nj * w + ni
                    if flat[q] and lab[q] < 0:
                        lab[q] = nlab
                        stack[sp] = q
                        sp += 1
        nlab += 1
        if (i1 - i0 + 1) < lim and (j1 - j0 + 1) < lim:
            G[cells[:nc]] = True
    return G.reshape(h, w)



class Detector:
    def __init__(self):
        self.dbg = {}

    # ------------------------------------------------------------ 遮罩
    def _mask(self, rgb, mpp, cx, cy, exclude, vehicles):
        h, w = rgb.shape[:2]
        R = rgb[:, :, 0].astype(np.int32)
        G = rgb[:, :, 1].astype(np.int32)
        B = rgb[:, :, 2].astype(np.int32)
        valid = (R + G + B) >= 24
        # 狀態：0 = 看不到（無覆蓋 / 排除區），1 = 看得到，2 = 在車輛框內（標線可能被車擋住）
        blocked = np.zeros((h, w), bool)
        for r in exclude:
            i0, i1, j0, j1 = rect_px(r, w, h, mpp, cx, cy)
            if i1 >= i0 and j1 >= j0:
                blocked[j0:j1 + 1, i0:i1 + 1] = True
        veh = np.zeros((h, w), bool)
        for r in vehicles:
            i0, i1, j0, j1 = rect_px(r, w, h, mpp, cx, cy)
            if i1 >= i0 and j1 >= j0:
                veh[j0:j1 + 1, i0:i1 + 1] = True
        vis = valid & ~blocked
        # 脊線測試要 p±d 都有效：離無覆蓋區 / 影像邊 ≤ d+1 px 的地方「看不到線」，取樣時當未知
        dR = max(2, int(round(RIDGE_D_M / mpp))) + 1
        inv = np.pad((~valid).astype(np.int32), dR + 1, constant_values=1)
        ii = inv.cumsum(0).cumsum(1)
        k = 2 * dR + 1
        win = ii[k:, k:] - ii[:-k, k:] - ii[k:, :-k] + ii[:-k, :-k]
        validE = win[:h, :w] == 0
        self.state = np.where(vis & validE, np.where(veh, 2, 1), 0).astype(np.int8)
        Y = (77 * R + 150 * G + 29 * B) >> 8
        yb = np.minimum(R, G) - B
        boost = np.where((yb > 40) & (R >= 90) & (G >= 70), (yb - 40) >> 1, 0)
        P = np.where(valid, Y + boost, 0).astype(np.int32)
        self.P = P
        vcnt = np.zeros((h, w), np.int32)
        S = np.zeros((h, w), np.int32)
        for dj in (-1, 0, 1):
            for di in (-1, 0, 1):
                vcnt += _shift(valid.astype(np.int32), di, dj, 0)
                S += _shift(P, di, dj, 0)
        valid9 = vcnt == 9
        S = np.where(valid9, S, 0)
        mx = np.maximum(np.maximum(R, G), B)
        mn = np.minimum(np.minimum(R, G), B)
        neutral = (mx - mn) <= NEUTRAL_MAX
        yellow = (R >= 90) & (G >= 70) & (R - B >= 40) & (G - B >= 25) & (np.abs(R - G) <= 80)
        cand = vis & valid9 & (neutral | yellow) & (P >= PAINT_MIN)
        d = max(2, int(round(RIDGE_D_M / mpp)))
        dd = max(2, int(round(RIDGE_D_M / mpp / math.sqrt(2.0))))
        offs = [(d, 0), (0, d), (dd, dd), (dd, -dd)]
        best = np.full((h, w), NEG, np.int32)
        allv = valid9.copy()
        e = []
        for k, (di, dj) in enumerate(offs):
            Sp = _shift(S, di, dj, 0)
            Sm = _shift(S, -di, -dj, 0)
            allv &= _shift(valid9, di, dj, False) & _shift(valid9, -di, -dj, False)
            best = np.maximum(best, np.minimum(S - Sp, S - Sm))
            e.append(2 * S - Sp - Sm)
        best = np.where(cand & allv, best, NEG)
        # 法線方向：四個方向的二階差分當成 cos 2θ 取樣（可調濾波器），連續角度
        th = np.arctan2((e[2] - e[3]).astype(np.float64), (e[0] - e[1]).astype(np.float64)) * (90.0 / math.pi)
        th = np.where(th < 0, th + 180.0, th)
        TH = np.floor(th + 0.5).astype(np.int32) % 180
        Kn = np.array([0, 2, 1, 3])[np.floor(th / 45.0 + 0.5).astype(np.int32) % 4]
        M = best >= 9 * RIDGE_T
        units = [(1, 0), (0, 1), (1, 1), (1, -1)]
        C = np.zeros((h, w), bool)
        for k, (ui, uj) in enumerate(units):
            Rm = _shift(best, -ui, -uj, NEG)
            Rp = _shift(best, ui, uj, NEG)
            C |= M & (Kn == k) & (best > Rm) & (best >= Rp)
        K = TH
        D = np.zeros((h, w), bool)
        for dj in (-1, 0, 1):
            for di in (-1, 0, 1):
                D |= _shift(M, di, dj, False)
        # 字形遮罩要在「沒膨脹的脊線」上算：膨脹會把號碼的筆畫和旁邊的標線串成同一個元件。
        # 算完再各自膨脹，只有「附近完全沒有結構標線」的像素才算字形。
        Gm = _glyph_mask(M, mpp)
        self.Gm = Gm
        GD = np.zeros((h, w), bool)
        SD = np.zeros((h, w), bool)
        Ms = M & ~Gm
        for dj in (-1, 0, 1):
            for di in (-1, 0, 1):
                GD |= _shift(Gm, di, dj, False)
                SD |= _shift(Ms, di, dj, False)
        self.G = GD & ~SD
        return vis, M, C, K, D

# ------------------------------------------------------------ Hough + 貪婪線段
    def _lines(self, C, K, w, h, mpp, cx, cy):
        js, iis = np.nonzero(C)              # row-major 順序
        kk = K[js, iis]
        xc = iis + 0.5 - w / 2.0
        yc = js + 0.5 - h / 2.0
        nC = len(xc)
        base = kk
        rmax = int(math.ceil(math.hypot(w, h) / 2.0)) + 2
        NR = 2 * rmax + 1
        cosT = np.array([math.cos(t * math.pi / 180.0) for t in range(180)])
        sinT = np.array([math.sin(t * math.pi / 180.0) for t in range(180)])
        H = np.zeros((180, NR), np.int32)
        for dt in range(-SECTOR, SECTOR + 1):
            t = (base + dt) % 180
            rho = xc * cosT[t] + yc * sinT[t]
            rb = np.floor(rho + 0.5).astype(np.int64) + rmax
            np.add.at(H, (t, rb), 1)
        H3 = H.copy()
        H3[:, 1:] += H[:, :-1]
        H3[:, :-1] += H[:, 1:]
        minVotes = max(6, int(round(MIN_VOTES_M / mpp)))
        peak = H3 >= minVotes
        for dt in range(-2, 3):
            for dr in range(-3, 4):
                if dt == 0 and dr == 0:
                    continue
                nb = _shift(H3, dr, dt, -1)
                if dt < 0 or (dt == 0 and dr < 0):
                    peak &= H3 > nb
                else:
                    peak &= H3 >= nb
        pt, pr = np.nonzero(peak)
        order = sorted(range(len(pt)), key=lambda q: (-int(H3[pt[q], pr[q]]), int(pt[q]) * NR + int(pr[q])))
        order = order[:MAX_PEAKS]
        self.dbg['peaks'] = [(int(pt[q]), int(pr[q]) - rmax, int(H3[pt[q], pr[q]])) for q in order]
        used = np.zeros(nC, bool)
        tolF = max(1.2, GATHER_TOLF_M / mpp)
        gapPx = GAP_M / mpp
        minPiecePx = MIN_PIECE_M / mpp
        lines = []

        def sec_ok(theta):
            dlt = np.abs(base - theta) % 180.0
            dlt = np.minimum(dlt, 180.0 - dlt)
            return dlt <= SECTOR + 1

        def fit(idx):
            x = xc[idx]
            y = yc[idx]
            n = len(idx)
            mx = seqsum(x) / n
            my = seqsum(y) / n
            dx = x - mx
            dy = y - my
            sxx = seqsum(dx * dx)
            syy = seqsum(dy * dy)
            sxy = seqsum(dx * dy)
            phi = 0.5 * math.atan2(2.0 * sxy, sxx - syy)
            return mx, my, phi

        def gather_fit(mx, my, phi, tol):
            c, s = math.cos(phi), math.sin(phi)
            nrm = (math.degrees(phi) + 90.0) % 180.0
            dist = np.abs((xc - mx) * (-s) + (yc - my) * c)
            return np.nonzero((~used) & sec_ok(nrm) & (dist <= tol))[0]

        lineGap = LINE_GAP_M / mpp

        def biggest_cluster(idx, dx, dy):
            """沿方向 (dx, dy) 排序後以 > LINE_GAP 的間隙切群，取像素最多的一群（同數取前者）。"""
            sv = xc[idx] * dx + yc[idx] * dy
            o = np.argsort(sv, kind='stable')
            idx, sv = idx[o], sv[o]
            best, bs, be = -1, 0, 0
            st = 0
            for e in range(1, len(idx) + 1):
                if e == len(idx) or sv[e] - sv[e - 1] > lineGap:
                    if e - st > best:
                        best, bs, be = e - st, st, e
                    st = e
            return idx[bs:be], sv[bs], sv[be - 1]

        def split_pieces(idx):
            """依擬合方向排序、以 > GAP 的間隙切段；太短或太稀疏的段丟掉。回傳 (保留像素, 各段像素)。"""
            mx, my, phi = fit(idx)
            c, s = math.cos(phi), math.sin(phi)
            sv = (xc[idx] - mx) * c + (yc[idx] - my) * s
            o = np.argsort(sv, kind='stable')
            idx = idx[o]
            sv = sv[o]
            kept = []
            pieces_idx = []
            st = 0
            for e in range(1, len(idx) + 1):
                if e == len(idx) or sv[e] - sv[e - 1] > gapPx:
                    span = sv[e - 1] - sv[st] + 1.0
                    if span >= minPiecePx and e - st >= 0.4 * span:
                        pieces_idx.append(idx[st:e])
                        kept.extend(idx[st:e].tolist())
                    st = e
            return np.sort(np.array(kept, dtype=np.int64)), pieces_idx

        raw = []
        for q in order:
            if len(raw) >= MAX_LINES:
                break
            t, r = int(pt[q]), int(pr[q])
            rho0 = r - rmax
            for _rep in range(4):              # 同一峰值上可能有好幾段共線但分開的線（例如走道兩側的分隔線）
                if len(raw) >= MAX_LINES:
                    break
                dist = np.abs(xc * cosT[t] + yc * sinT[t] - rho0)
                idx = np.nonzero((~used) & sec_ok(float(t)) & (dist <= GATHER_TOL0))[0]
                if len(idx) < minVotes:
                    break
                d0x, d0y = -sinT[t], cosT[t]
                idx, s0, s1 = biggest_cluster(idx, d0x, d0y)
                if len(idx) < minVotes:
                    break
                ok = True
                for _it in range(2):
                    mx, my, phi = fit(idx)
                    idx = gather_fit(mx, my, phi, tolF)
                    sp = xc[idx] * d0x + yc[idx] * d0y
                    idx = idx[(sp >= s0 - 0.5 * lineGap) & (sp <= s1 + 0.5 * lineGap)]
                    if len(idx) < minVotes:
                        ok = False
                        break
                if not ok:
                    break
                kept, _ = split_pieces(idx)
                if len(kept) < minVotes:
                    break
                used[kept] = True
                raw.append(kept)
        # 共線的線段合併（被自車、車輛擋成兩段的同一條線），合併後殘差要小才算
        mergeTol = max(1.5, MERGE_TOL_M / mpp)
        sinMerge = math.sin(math.radians(MERGE_DEG))
        fits = [fit(r) for r in raw]
        merged = True
        while merged:
            merged = False
            for i in range(len(raw)):
                for j in range(i + 1, len(raw)):
                    mi, ni_, pi = fits[i]
                    mj, nj_, pj = fits[j]
                    if abs(math.sin(pi - pj)) > sinMerge:
                        continue
                    ci, si = math.cos(pi), math.sin(pi)
                    cj, sj = math.cos(pj), math.sin(pj)
                    if abs((mj - mi) * (-si) + (nj_ - ni_) * ci) > mergeTol or abs((mi - mj) * (-sj) + (ni_ - nj_) * cj) > mergeTol:
                        continue
                    uni = np.sort(np.concatenate([raw[i], raw[j]]))
                    fu = fit(uni)
                    cu, su = math.cos(fu[2]), math.sin(fu[2])
                    res = np.abs((xc[uni] - fu[0]) * (-su) + (yc[uni] - fu[1]) * cu)
                    if float(np.max(res)) > 1.5 * tolF:
                        continue
                    raw[i] = uni
                    fits[i] = fu
                    del raw[j]
                    del fits[j]
                    merged = True
                    break
                if merged:
                    break
        for r in raw:
            kept, pieces_idx = split_pieces(r)
            if len(kept) < minVotes:
                continue
            mx, my, phi = fit(kept)
            mxm = (mx + w / 2.0 - cx) * mpp              # 轉成公尺
            mzm = (cy - (my + h / 2.0)) * mpp
            ux, uz = math.cos(phi), -math.sin(phi)
            if ux < -1e-12 or (abs(ux) <= 1e-12 and uz < 0):
                ux, uz = -ux, -uz
            pcs = []
            for pidx in pieces_idx:
                px = (xc[pidx] + w / 2.0 - cx) * mpp
                pz = (cy - (yc[pidx] + h / 2.0)) * mpp
                tv = (px - mxm) * ux + (pz - mzm) * uz
                pcs.append((float(np.min(tv)) - 0.5 * mpp, float(np.max(tv)) + 0.5 * mpp))
            pcs.sort()
            lines.append(Line(mxm, mzm, ux, uz, pcs, len(kept)))
        self.dbg['nC'] = nC
        self.dbg['npeaks'] = len(order)
        return lines

    def _extend(self, L):
        """沿擬合直線把每段的兩端往外延伸到遮罩（D）斷掉為止：中心線像素常在線頭、交會處斷開。
        只走看得到且不在車框內的像素；連續 EXT_MISS 點沒命中就停。延伸後重疊 / 間隙 ≤ GAP 的段合併。"""
        st, D = self.state, self.D
        step = self.mpp
        out = []
        for (t0, t1) in L.pieces:
            ends = []
            for sg, te in ((-1.0, t0), (1.0, t1)):
                last = te - sg * 0.5 * step
                t = last
                miss = 0
                while True:
                    t += sg * step
                    x = L.cx + t * L.ux
                    z = L.cz + t * L.uz
                    i = math.floor(x / self.mpp + self.cx)
                    j = math.floor(self.cy - z / self.mpp)
                    if i < 0 or i >= self.w or j < 0 or j >= self.h or st[j, i] != 1:
                        break
                    if D[j, i]:
                        last = t
                        miss = 0
                    else:
                        miss += 1
                        if miss > EXT_MISS:
                            break
                ends.append(last + sg * 0.5 * step)
            out.append((min(t0, ends[0]), max(t1, ends[1])))
        out.sort()
        merged = []
        for (a, b) in out:
            if merged and a - merged[-1][1] <= GAP_M:
                merged[-1] = (merged[-1][0], max(merged[-1][1], b))
            else:
                merged.append((a, b))
        L.pieces = merged

    # ------------------------------------------------------------ 取樣支持度
    def _seg(self, x0, z0, x1, z1, strict=False):
        """沿線段取樣（每 mpp 一點）。車輛框內：命中照算、沒命中當作看不到（被車擋）；
        strict（格內檢查用）時車輛框內一律不算，避免車身紋路被當成格內的線。"""
        L = math.hypot(x1 - x0, z1 - z0)
        n = max(2, int(math.ceil(L / self.mpp)))
        hit = vis = 0
        st, D = self.state, self.D
        for k in range(n):
            f = (k + 0.5) / n
            x = x0 + (x1 - x0) * f
            z = z0 + (z1 - z0) * f
            i = math.floor(x / self.mpp + self.cx)
            j = math.floor(self.cy - z / self.mpp)
            if i < 0 or i >= self.w or j < 0 or j >= self.h:
                continue
            v = st[j, i]
            if v == 0 or (v == 2 and (strict or not D[j, i])):
                continue
            vis += 1
            if D[j, i] and not (strict and self.G[j, i]):
                hit += 1
        return hit, vis, n

    def _ratio(self, x0, z0, x1, z1, strict=False):
        """遮罩命中率（只算看得到的取樣點）；可見點太少回傳 None（未知）。"""
        hit, vis, n = self._seg(x0, z0, x1, z1, strict)
        if strict:
            # 格內檢查：只要看得到 0.3 m 以上就算數（寧可錯殺）
            if vis < 3 or vis * self.mpp < 0.25:
                return None
        elif vis < 3 or (vis < 0.3 * n and vis * self.mpp < 1.0):
            return None
        return hit / vis

    # ------------------------------------------------------------ 線寬（停止線、斑馬線邊線比停車格線寬很多）
    def _width_at(self, x0, z0, nx, nz):
        """(x0, z0) 沿法線 (nx, nz) 的漆通道剖面半高寬（m）；看不到、對比太低回傳 None。步長 mpp/2，±0.45 m。"""
        h = 0.5 * self.mpp
        K = int(math.ceil(0.45 / h))
        ko = int(math.ceil(0.3 / h))
        kc = int(0.12 / h)
        st, P = self.state, self.P
        prof = []
        for k in range(-K, K + 1):
            o = k * h
            i = math.floor((x0 + o * nx) / self.mpp + self.cx)
            j = math.floor(self.cy - (z0 + o * nz) / self.mpp)
            if i < 0 or i >= self.w or j < 0 or j >= self.h or st[j, i] != 1:
                return None
            prof.append(int(P[j, i]))
        c = K
        pk = c - kc
        for k in range(c - kc, c + kc + 1):
            if prof[k] > prof[pk]:
                pk = k
        left = sorted(prof[:K - ko + 1])
        right = sorted(prof[K + ko:])
        bg = max(left[len(left) // 2], right[len(right) // 2])
        if prof[pk] - bg < 20:
            return None
        thr2 = prof[pk] + bg          # 半高門檻 ×2（整數比較）
        a = pk
        while a > 0 and 2 * prof[a - 1] >= thr2:
            a -= 1
        b = pk
        while b < 2 * K and 2 * prof[b + 1] >= thr2:
            b += 1
        return (b - a + 1) * h

    def _width_samples(self, X):
        """線 X 沿各線段每 WSTEP 一點（只取遮罩上的點；離段端 < 0.3 m 的不取，交會處量到的是別條線）的
        (參數, 半高寬)，每條線算一次。"""
        key = id(X)
        if key in self._wcache:
            return self._wcache[key]
        nx, nz = -X.uz, X.ux
        ts, ws = [], []
        for a, b in X.pieces:
            q = 0
            while True:
                t = a + 0.3 + WSTEP * q
                if t > b - 0.3:
                    break
                q += 1
                x, z = X.cx + t * X.ux, X.cz + t * X.uz
                i = math.floor(x / self.mpp + self.cx)
                j = math.floor(self.cy - z / self.mpp)
                if i < 0 or i >= self.w or j < 0 or j >= self.h or not self.D[j, i]:
                    continue
                v = self._width_at(x, z, nx, nz)
                if v is not None:
                    ts.append(t)
                    ws.append(v)
        self._wcache[key] = (ts, ws)
        return ts, ws

    def _width_med(self, X, lo, hi, gap=-1.0, t0=0.0, nmin=2):
        """線 X 在參數 [lo, hi] 內（且 |t - t0| ≥ gap）的半高寬（下）中位數；不到 nmin 點回傳 None。"""
        ts, ws = self._width_samples(X)
        v = sorted(w for t, w in zip(ts, ws) if lo <= t <= hi and abs(t - t0) >= gap)
        return v[(len(v) - 1) // 2] if len(v) >= nmin else None

    def _wide_cross(self, A, B, Cl, PA, PB):
        """橫邊與長邊的線寬差很多（停止線、機車停等區、斑馬線邊線 0.3 m 以上，停車格線 0.10–0.15 m）→ True。"""
        def tpar(X, p):
            return (p[0] - X.cx) * X.ux + (p[1] - X.cz) * X.uz
        ta, tb = tpar(Cl, PA), tpar(Cl, PB)
        if abs(tb - ta) < 0.9:
            return False
        # 橫邊寬度：兩條長邊之間的局部值與整條線的值取大（磨損的停止線局部可能只剩細細一條；被自車擋住時量不到）
        wl = self._width_med(Cl, min(ta, tb) + 0.25, max(ta, tb) - 0.25)
        wa = self._width_med(Cl, -1e9, 1e9, nmin=3)
        if wl is None and wa is None:
            return False
        wc = max(-1.0 if wl is None else wl, -1.0 if wa is None else wa)
        ws = -1.0
        for X, P0 in ((A, PA), (B, PB)):           # 長邊：交點前後 0.3–1.5 m
            t0 = tpar(X, P0)
            v = self._width_med(X, t0 - 1.5, t0 + 1.5, 0.3, t0)
            if v is not None:
                ws = max(ws, v)
        if ws < 0:
            for X in (A, B):
                v = self._width_med(X, -1e9, 1e9, nmin=3)
                if v is not None:
                    ws = max(ws, v)
        if ws < 0:
            return False
        return (wc >= WIDE_MIN and wc >= WIDE_RATIO * ws) or (ws >= WIDE_MIN and ws >= WIDE_RATIO * wc)

    # ------------------------------------------------------------ 主流程
    def detect(self, rgb, mpp, cx, cy, exclude, vehicles):
        mpp, cx, cy = f32(mpp), f32(cx), f32(cy)
        h, w = rgb.shape[:2]
        self.mpp, self.cx, self.cy, self.w, self.h = mpp, cx, cy, w, h
        vis, M, C, K, D = self._mask(rgb, mpp, cx, cy, exclude, vehicles)
        self.vis, self.D = vis, D
        self.dbg = {'M': M, 'C': C}
        self.M = M
        # 整片紋理（植草磚、連鎖磚）：看得到的地面 ≥ TEXTURE_MAX 是標線遮罩 → 格子外圍雜線幾乎一定過高，直接結束（省下最耗時的情況）
        nvis = int((self.state == 1).sum())
        if nvis >= 1000 and int(M[self.state == 1].sum()) >= TEXTURE_MAX * nvis:
            self.lines = []
            self.dbg['lines'] = []
            self.dbg['cells'] = []
            return []
        lines = self._lines(C, K, w, h, mpp, cx, cy)
        for L in lines:
            self._extend(L)
        self.lines = lines
        self.dbg['lines'] = lines
        self._wcache = {}
        cells = []
        nl = len(lines)
        for a in range(nl):
            for b in range(a + 1, nl):
                self._pair(lines, a, b, cells)
        self.dbg['cells'] = cells
        cands = []
        for c in cells:
            cd = self._eval_cell(lines, c)
            if cd is not None:
                cands.append(cd)
        # 只有分隔線、入口沒畫線的單側格：必須和另一格共用分隔線（成排），排除兩條車道線
        cands = [c for c in cands if not c['row'] or self._row_ok(c, cands)]
        self._decide_kinds(cands)
        cands.sort(key=lambda c: (-c['score'], c['order']))
        keep = []
        for c in cands:
            if any(poly_iou(c['poly'], k['poly']) > NMS_IOU for k in keep):
                continue
            keep.append(c)
            if len(keep) >= MAX_SLOTS:
                break
        occ = [list(map(f32, r)) for r in vehicles]
        for r in exclude:
            r = list(map(f32, r))
            if r[0] <= 0.0 <= r[2] and r[1] <= 0.0 <= r[3]:      # 只有自車（含原點的排除區）算佔用物
                occ.append(r)
        out = []
        for c in keep:
            poly = c['poly']
            area = poly_area(poly)
            o = False
            for r in occ:
                if area > 1e-6 and clip_rect_area(poly, r) > OCC_FRAC * area:
                    o = True
                    break
            p = []
            for (x, z) in poly:
                p.extend([x, z])
            out.append({'p': p, 'kind': c['kind'], 'occupied': o, 'conf': min(1.0, max(0.0, c['score']))})
        return out

    # ------------------------------------------------------------ 長邊對 → 橫邊 → 格子
    def _pair(self, lines, a, b, cells):
        A, B = lines[a], lines[b]
        dot = A.ux * B.ux + A.uz * B.uz
        if abs(A.ux * B.uz - A.uz * B.ux) > SIN_PAR:
            return
        sg = 1.0 if dot >= 0 else -1.0
        ux, uz = A.ux + sg * B.ux, A.uz + sg * B.uz
        nn = math.hypot(ux, uz)
        ux, uz = ux / nn, uz / nn
        F = Frame(A.cx, A.cz, ux, uz)
        pa = F.pieces(A)
        pb = F.pieces(B)
        minA, maxA = pa[0][0], max(p[1] for p in pa)
        minB, maxB = pb[0][0], max(p[1] for p in pb)
        lo, hi = max(minA, minB), min(maxA, maxB)
        if hi - lo < PAIR_OVL:
            return
        tm = 0.5 * (lo + hi)
        xa, za = F.on(A, tm)
        xb, zb = F.on(B, tm)
        side = (xb - xa) * (-uz) + (zb - za) * ux
        wdt = abs(side)
        if wdt < PAIR_WMIN or wdt > PAIR_WMAX:
            return
        crs = []
        for ci, Cl in enumerate(lines):
            if ci == a or ci == b:
                continue
            if abs(Cl.ux * ux + Cl.uz * uz) > COS_CROSS:
                continue
            PA = intersect(A, Cl)
            PB = intersect(B, Cl)
            if PA is None or PB is None:
                continue
            # intersect() 算的是無限長直線的交點：兩公尺外一條 1 m 的斜線延伸過來照樣有交點，
            # 會把格子從中間切成兩半（實車地下室擷取就是這樣掉的）。要求橫線自己的線段
            # 真的伸到交點附近；被停著的車擋掉一段還是過得了（pieces 的首尾涵蓋整段）。
            tc0 = Cl.pieces[0][0]
            tc1 = max(q[1] for q in Cl.pieces)
            reach = 0.0
            for P in (PA, PB):
                tc = (P[0] - Cl.cx) * Cl.ux + (P[1] - Cl.cz) * Cl.uz
                reach = max(reach, tc0 - tc, tc - tc1)
            if reach > CROSS_REACH:
                continue
            tA = (PA[0] - F.ox) * ux + (PA[1] - F.oz) * uz
            tB = (PB[0] - F.ox) * ux + (PB[1] - F.oz) * uz
            inA = minA - 1.0 <= tA <= maxA + 1.0
            inB = minB - 1.0 <= tB <= maxB + 1.0
            # 至少一條長邊延伸到橫邊附近；另一條可能被車擋住
            if not (inA or inB) or tA < minA - LANE_LEN or tA > maxA + LANE_LEN or tB < minB - LANE_LEN or tB > maxB + LANE_LEN:
                continue
            cr = self._crossing(A, B, F, PA, PB, tA, tB, ci)
            if cr is not None:
                crs.append(cr)
        crs.sort(key=lambda c: (c['t'], c['ci']))
        ded = []
        for c in crs:
            dup = -1
            for q, d in enumerate(ded):
                if abs(d['tA'] - c['tA']) < 0.35 and abs(d['tB'] - c['tB']) < 0.35:
                    dup = q
                    break
            if dup < 0:
                ded.append(c)
            elif c['ev'] > ded[dup]['ev']:
                ded[dup] = c
        crs = ded
        # 開口端：兩條線在同一側真的結束（後面看得到地面但沒有線），例如沒畫入口線的「ㄇ」或「||||」
        nline = len(crs)
        endsB = self._ends(B, F, pb)
        for (eA, sA) in self._ends(A, F, pa):
            for (eB, sB) in endsB:
                if sA != sB:
                    continue
                PA = F.on(A, eA)
                PB = F.on(B, eB)
                vx, vz = PB[0] - PA[0], PB[1] - PA[1]
                dv = math.hypot(vx, vz)
                if dv < 1e-6:
                    continue
                vx, vz = vx / dv, vz / dv
                cosang = abs(vx * ux + vz * uz)
                if cosang > COS_CROSS:
                    continue
                dup = False
                for q, c in enumerate(crs):
                    if abs(c['tA'] - eA) < 0.5 and abs(c['tB'] - eB) < 0.5 and (q < nline or c['sense'] == sA):
                        dup = True
                        break
                if dup:
                    continue
                crs.append({'t': 0.5 * (eA + eB), 'tA': eA, 'tB': eB, 'PA': PA, 'PB': PB, 'vx': vx, 'vz': vz,
                            'perp': cosang <= SIN_PERP, 'open': True, 'sense': sA, 'ev': 0.0,
                            'cA': None, 'cB': None, 'side': (None, None, None, None), 'ci': -1 - (sA > 0)})
        crs.sort(key=lambda c: (c['t'], c['ci']))
        base = {'a': a, 'b': b, 'F': F, 'w': wdt, 'minA': minA, 'maxA': maxA, 'minB': minB, 'maxB': maxB}
        n = len(crs)
        for k in range(n - 1):
            c0, c1 = crs[k], crs[k + 1]
            if c0['open'] and c0['sense'] > 0:
                continue
            if c1['open'] and c1['sense'] < 0:
                continue
            LA = c1['tA'] - c0['tA']
            LB = c1['tB'] - c0['tB']
            if LA < 0.5 or LB < 0.5:
                continue
            if abs(c0['vx'] * c1['vz'] - c0['vz'] * c1['vx']) > math.sin(math.radians(12.0)):
                continue
            cells.append(dict(base, c0=c0, c1=c1, s=1, L=0.5 * (LA + LB), one=False))
        # 單側格：入口有 T/L 標記（或兩條分隔線在入口同時結束），另一端出畫面或沒畫
        for k, c in enumerate(crs):
            if c['open']:
                s = -c['sense']
                near = False
                for q, d in enumerate(crs):
                    dt = (d['t'] - c['t']) * s
                    if q != k and 0.4 <= dt <= LANE_LEN:
                        near = True
                        break
                if near:
                    continue
                eA = _run(pa, c['tA'], s)
                eB = _run(pb, c['tB'], s)
                lA, lB = abs(eA - c['tA']), abs(eB - c['tB'])
                if min(lA, lB) < 1.2 or max(lA, lB) > LANE_LEN:
                    continue
                if _dash_after(pa, eA, s) or _dash_after(pb, eB, s):
                    continue          # 同一直線上隔一段又有線 → 虛線車道線
                if not c['perp'] and abs(uz) > COS_OBLIQUE:
                    continue          # 斜角格的分隔線不會和車身平行（那多半是車道線端點沒對齊）
                cells.append(dict(base, c0=c, c1=None, s=s, L=DEPTH_DEF, one=True, okinds=['p' if c['perp'] else 'a']))
                continue
            for s in (1, -1):
                near = False
                for q, d in enumerate(crs):
                    if q == k or d['open']:
                        continue
                    dt = (d['t'] - c['t']) * s
                    if 0.4 <= dt <= LANE_LEN:
                        near = True
                        break
                if near:
                    continue
                rAp, rAm, rBp, rBm = c['side']
                fa = rAp if s > 0 else rAm
                fb = rBp if s > 0 else rBm
                ga = rAm if s > 0 else rAp
                gb = rBm if s > 0 else rBp
                if not ((fa is not None and fa >= 0.4 and (fb is None or fb >= 0.4)) or
                        (fb is not None and fb >= 0.4 and fa is None)):
                    continue
                eA = _run(pa, c['tA'], s)
                eB = _run(pb, c['tB'], s)
                lA, lB = abs(eA - c['tA']), abs(eB - c['tB'])
                okinds = []
                # 垂直 / 斜角格：橫邊是長邊對的終點（另一側沒有線），長邊不能太長（車道線）
                term = not ((ga is not None and ga > 0.3) or (gb is not None and gb > 0.3))
                # 斜角格兩條分隔線都要真的伸進格內（≥ SEP_MIN_A）：斜紋、網狀線、字的筆畫湊出的斜格常常只有一條
                if term and max(lA, lB) <= LANE_LEN and (c['perp'] or min(lA, lB) >= SEP_MIN_A) and not (
                        1.4 <= max(lA, lB) <= 4.2 and self._real_end(A, F, eA, s) and self._real_end(B, F, eB, s)):
                    okinds.append('p' if c['perp'] else 'a')
                # 平行格：長邊對是入口線與路緣側的線，往 s 側延伸到畫面外（另一個橫邊看不到）
                if (c['perp'] and PAIR_WMIN <= wdt <= 3.0 and min(lA, lB) >= 2.5 and max(lA, lB) <= 7.5
                        and not self._real_end(A, F, eA, s) and not self._real_end(B, F, eB, s)):
                    okinds.append('l')
                if okinds:
                    cells.append(dict(base, c0=c, c1=None, s=s, L=DEPTH_DEF, one=True, okinds=okinds))

    def _ends(self, X, F, pcs):
        """線段在共同座標下的「真的結束」端點：與下一段間隙 ≥ END_GAP，且端點外看得到地面但沒有線。"""
        out = []
        n = len(pcs)
        for q, (p0, p1) in enumerate(pcs):
            if (q == 0 or p0 - pcs[q - 1][1] >= END_GAP) and self._real_end(X, F, p0, -1):
                out.append((p0, -1))
            if (q == n - 1 or pcs[q + 1][0] - p1 >= END_GAP) and self._real_end(X, F, p1, 1):
                out.append((p1, 1))
        return out

    def _real_end(self, X, F, e, sense):
        x0, z0 = F.on(X, e + sense * 0.15)
        x1, z1 = F.on(X, e + sense * 0.8)
        r = self._ratio(x0, z0, x1, z1)
        return r is not None and r <= 0.2

    def _crossing(self, A, B, F, PA, PB, tA, tB, ci):
        vx, vz = PB[0] - PA[0], PB[1] - PA[1]
        dv = math.hypot(vx, vz)
        if dv < 0.5:
            return None
        vx, vz = vx / dv, vz / dv
        e0, e1 = 0.12, 0.45
        rc = self._ratio(PA[0] + e0 * vx, PA[1] + e0 * vz, PB[0] - e0 * vx, PB[1] - e0 * vz)
        cA = self._ratio(PA[0] + e0 * vx, PA[1] + e0 * vz, PA[0] + e1 * vx, PA[1] + e1 * vz)
        cB = self._ratio(PB[0] - e1 * vx, PB[1] - e1 * vz, PB[0] - e0 * vx, PB[1] - e0 * vz)
        strong = (rc is not None and rc >= 0.55) or (cA is not None and cB is not None and cA >= 0.55 and cB >= 0.55)
        partial = (cA is not None and cA >= 0.55 and cB is None) or (cB is not None and cB >= 0.55 and cA is None)
        if not (strong or partial):
            return None
        side = []
        for X, t in ((A, tA), (B, tB)):
            for sg in (1, -1):
                x0, z0 = F.on(X, t + sg * 0.15)
                x1, z1 = F.on(X, t + sg * 1.2)
                side.append(self._ratio(x0, z0, x1, z1))
        rAp, rAm, rBp, rBm = side
        okA = (rAp is not None and rAp >= 0.4) or (rAm is not None and rAm >= 0.4) or (rAp is None and rAm is None)
        okB = (rBp is not None and rBp >= 0.4) or (rBm is not None and rBm >= 0.4) or (rBp is None and rBm is None)
        if not (okA and okB):
            return None
        if self._wide_cross(A, B, self.lines[ci], PA, PB):
            return None
        if cA is not None and cB is not None:
            km = (cA + cB) / 2.0
        elif cA is not None or cB is not None:
            km = cA if cA is not None else cB
        else:
            km = 0.0
        ev = max(rc if rc is not None else 0.0, km)
        cosang = abs(vx * F.ux + vz * F.uz)
        return {'t': 0.5 * (tA + tB), 'tA': tA, 'tB': tB, 'PA': PA, 'PB': PB, 'vx': vx, 'vz': vz,
                'perp': cosang <= SIN_PERP, 'open': False, 'sense': 0, 'ev': min(1.0, ev),
                'cA': cA, 'cB': cB, 'side': (rAp, rAm, rBp, rBm), 'ci': ci}

    # ------------------------------------------------------------ 格子驗證
    def _eval_cell(self, lines, c):
        A, B, F = lines[c['a']], lines[c['b']], c['F']
        w = c['w']
        c0, c1, s = c['c0'], c['c1'], c['s']
        ux, uz = F.ux, F.uz
        tA0, tB0 = c0['tA'], c0['tB']
        if c1 is not None:
            tA1, tB1 = c1['tA'], c1['tB']
        else:
            tA1, tB1 = tA0 + s * DEPTH_DEF, tB0 + s * DEPTH_DEF
        L = 0.5 * (abs(tA1 - tA0) + abs(tB1 - tB0))
        perp = c0['perp'] and (c1 is None or c1['perp'])
        ang = (not c0['perp']) and (c1 is None or not c1['perp'])
        nOpen = int(c0['open']) + int(c1 is not None and c1['open'])
        kinds = []
        if c['one']:
            kinds = [k for k in c['okinds'] if k == 'l' or 2.0 <= w <= 3.4]
        elif perp:
            if 2.0 <= w <= 3.4 and 4.2 <= L <= 7.0 and (nOpen == 0 or (L >= 4.5 and w <= 3.2)):
                kinds.append('p')
            if 1.8 <= w <= 3.0 and 4.8 <= L <= 7.5 and nOpen == 0:
                kinds.append('l')
        elif ang:
            if 2.0 <= w <= 3.4 and 4.0 <= L <= 7.0:
                kinds.append('a')
        if not kinds:
            return self._why('kind')
        Ak = F.on(A, tA0)
        Bk = F.on(B, tB0)
        Am = F.on(A, tA1)
        Bm = F.on(B, tB1)
        if c1 is None:
            Ak, Bk = c0['PA'], c0['PB']
        else:
            Ak, Bk, Am, Bm = c0['PA'], c0['PB'], c1['PA'], c1['PB']
        # 長邊支持度
        lo_a, hi_a = min(tA0, tA1), max(tA0, tA1)
        lo_b, hi_b = min(tB0, tB1), max(tB0, tB1)
        rA = self._ratio(*F.on(A, lo_a + 0.2), *F.on(A, hi_a - 0.2))
        rB = self._ratio(*F.on(B, lo_b + 0.2), *F.on(B, hi_b - 0.2))
        # 四角：長邊靠角 0.12–0.6 m 與橫邊靠角
        corners_ok = 0
        corners_n = 0
        for cr, sg in ((c0, s), (c1, -s)):
            if cr is None or cr['open']:
                continue
            for X, t, cc in ((A, cr['tA'], cr['cA']), (B, cr['tB'], cr['cB'])):
                corners_n += 1
                pr = self._ratio(*F.on(X, t + sg * 0.12), *F.on(X, t + sg * 0.6))
                if pr is not None and pr >= 0.5 and cc is not None and cc >= 0.55:
                    corners_ok += 1
        # 規則
        row = False
        if c['one'] and c0['open']:
            if rA is None or rB is None or rA < 0.6 or rB < 0.6 or w > OPEN_WMAX:
                return self._why('open-sep')
            row = True
        elif c['one']:
            if corners_ok < 1 or (corners_ok < 2 and corners_n == 2 and not self._corner_hidden(c0)):
                return self._why('corners')
        elif nOpen == 0:
            okA = rA is not None and rA >= 0.35
            okB = rB is not None and rB >= 0.35
            sep_ok = (okA and okB) or (okA and rB is None) or (okB and rA is None)
            if not sep_ok and corners_ok < 3:
                return self._why('sep')
        elif nOpen == 1:
            if rA is None or rB is None or rA < 0.5 or rB < 0.5 or corners_ok < 1:
                return self._why('open1')
        else:
            if rA is None or rB is None or rA < 0.6 or rB < 0.6 or w > OPEN_WMAX:
                return self._why('open2')
        # 格內不得有平行線 / 橫線
        if self._interior(F, c['a'], c['b'], tA0, tA1, tB0, tB1, c0, c1):
            return self._why('interior')
        if rA is not None and rB is not None:
            rsep = (rA + rB) / 2.0
        elif rA is not None or rB is not None:
            rsep = rA if rA is not None else rB
        else:
            rsep = 0.3
        cfrac = corners_ok / corners_n if corners_n else 0.5
        ends = 0.0
        for cr in (c0, c1):
            ends += 0.3 if cr is None else (0.6 if cr['open'] else cr['ev'])
        score = 0.35 * min(1.0, rsep / 0.8) + 0.15 * ends + 0.25 * cfrac + 0.1 * (1.0 if c1 is not None else 0.0)
        if score < 0.40:
            return self._why('score')
        if self._clutter([Ak, Bk, Bm, Am]) > CLUTTER_MAX:
            return self._why('clutter')
        ring = self._ring(Ak, Bk, Am)
        if ring > RING_MAX:
            return self._why('ring')
        return {'cell': c, 'kinds': kinds, 'Ak': Ak, 'Bk': Bk, 'Am': Am, 'Bm': Bm, 'score': score, 'row': row,
                'ringv': ring}

    def _ring(self, o, pa, pb):
        """格子外圍 RING 寬的一圈（以 o→pa、o→pb 張成的平行四邊形往外擴）看得到的像素中，標線遮罩 M 的比例。
        植草磚、連鎖磚、斜紋區這類整片紋理會很高；停車場裡格子旁邊多半是空的地面。每 2 px 取樣。"""
        ax, az = pa[0] - o[0], pa[1] - o[1]
        bx, bz = pb[0] - o[0], pb[1] - o[1]
        la, lb = math.hypot(ax, az), math.hypot(bx, bz)
        if la < 1e-6 or lb < 1e-6:
            return 0.0
        ea, eb = RING / la, RING / lb
        na = int(math.ceil((la + 2 * RING) / (2 * self.mpp)))
        nb = int(math.ceil((lb + 2 * RING) / (2 * self.mpp)))
        st, M = self.state, self.M
        n = m = 0
        for q in range(nb + 1):
            fb = -eb + (1 + 2 * eb) * q / nb
            inb = 0.0 <= fb <= 1.0
            for k in range(na + 1):
                fa = -ea + (1 + 2 * ea) * k / na
                if inb and 0.0 <= fa <= 1.0:
                    continue
                x = o[0] + fa * ax + fb * bx
                z = o[1] + fa * az + fb * bz
                i = math.floor(x / self.mpp + self.cx)
                j = math.floor(self.cy - z / self.mpp)
                if i < 0 or i >= self.w or j < 0 or j >= self.h or st[j, i] != 1:
                    continue
                n += 1
                if M[j, i]:
                    m += 1
        return m / n if n >= 30 else 0.0

    def _clutter(self, quad):
        """格內（往中心縮 15 %）看得到的像素中，標線遮罩 M 的比例；草叢、樹影拖曳的雜線會很高。每 2 px 取樣。

        字形（車位號碼、箭頭、污漬）不算雜線：自己停在格子裡的時候，格內九成以上是自車排除區，
        剩下那一小條如果剛好有車位號碼，比例就會爆掉 —— 實測 15.1 m² 的格子只看得到 4 %，
        6 個遮罩點全是漆上去的號碼。斜紋禁停區的斜線是細長的，不會被當成字形，照樣擋得下來。"""
        gx = 0.25 * (quad[0][0] + quad[1][0] + quad[2][0] + quad[3][0])
        gz = 0.25 * (quad[0][1] + quad[1][1] + quad[2][1] + quad[3][1])
        q = [(gx + (x - gx) * 0.85, gz + (z - gz) * 0.85) for (x, z) in quad]
        if poly_signed_area(q) < 0:
            q = q[::-1]
        xs = [v[0] for v in q]
        zs = [v[1] for v in q]
        i0 = max(0, math.floor(min(xs) / self.mpp + self.cx))
        i1 = min(self.w - 1, math.floor(max(xs) / self.mpp + self.cx))
        j0 = max(0, math.floor(self.cy - max(zs) / self.mpp))
        j1 = min(self.h - 1, math.floor(self.cy - min(zs) / self.mpp))
        st, M, G = self.state, self.M, self.Gm
        n = m = 0
        for j in range(j0, j1 + 1, 2):
            z = (self.cy - (j + 0.5)) * self.mpp
            for i in range(i0, i1 + 1, 2):
                x = (i + 0.5 - self.cx) * self.mpp
                inside = True
                for k in range(4):
                    ax, az = q[k]
                    bx, bz = q[(k + 1) % 4]
                    if (bx - ax) * (z - az) - (bz - az) * (x - ax) < 0:
                        inside = False
                        break
                if inside:
                    if st[j, i] != 1:
                        continue
                    n += 1
                    if M[j, i] and not G[j, i]:
                        m += 1
        if n < 20:
            return 0.0
        return m / n

    def _why(self, tag):
        """除錯用：記下格子被拒的原因。"""
        self.dbg['why'] = tag
        return None

    def _corner_hidden(self, cr):
        return cr['cA'] is None or cr['cB'] is None

    def _interior(self, F, ia, ib, tA0, tA1, tB0, tB1, c0=None, c1=None):
        """格內有與長邊平行的線（機車格、兩格合成一格）或橫過的線 → True。車框內不算。"""
        A, B = self.lines[ia], self.lines[ib]
        sA = 1.0 if tA1 >= tA0 else -1.0
        sB = 1.0 if tB1 >= tB0 else -1.0
        # 從真的橫邊伸進格內的短分隔線頭（機車格的 T 字、每 1 m 一格）→ 不是汽車格。
        # 沿橫邊在寬度 20–80 % 處逐點往格內探 STUB_IN（直接看遮罩，短線頭常常抽不成 Hough 線）；
        # 線頭是細的：該點命中、左右各 0.2 m 的探針都沒命中（橫向的東西會讓三支都命中）。
        for cr, sg in ((c0, sA), (c1, -sA)):
            if cr is None or cr['open']:
                continue
            ax, az = cr['PA']
            bx, bz = cr['PB']
            wd = math.hypot(bx - ax, bz - az)
            sn = abs(F.ux * (bz - az) - F.uz * (bx - ax)) / max(wd, 1e-9)
            e0 = 0.1 / max(sn, 0.5)
            m = int(math.ceil(0.6 * wd / self.mpp))
            dl = int(round(0.2 / (0.6 * wd / m)))
            rs = []
            for k in range(-dl, m + dl + 1):
                f = 0.2 + 0.6 * k / m
                px_, pz_ = ax + (bx - ax) * f, az + (bz - az) * f
                hit, vis, n = self._seg(px_ + sg * e0 * F.ux, pz_ + sg * e0 * F.uz,
                                        px_ + sg * (e0 + STUB_IN) * F.ux, pz_ + sg * (e0 + STUB_IN) * F.uz, True)
                rs.append(hit / vis if vis >= 0.8 * n else -1.0)       # 被擋住 / 看不到 → 不判斷
            for k in range(m + 1):
                if rs[k + dl] >= 0.7 and 0.0 <= rs[k] < 0.4 and 0.0 <= rs[k + 2 * dl] < 0.4:
                    return True
        a0 = F.on(A, tA0 + sA * 0.5)
        a1 = F.on(A, tA1 - sA * 0.5)
        b0 = F.on(B, tB0 + sB * 0.5)
        b1 = F.on(B, tB1 - sB * 0.5)
        for f in (0.3, 0.5, 0.7):
            p0 = (a0[0] + (b0[0] - a0[0]) * f, a0[1] + (b0[1] - a0[1]) * f)
            p1 = (a1[0] + (b1[0] - a1[0]) * f, a1[1] + (b1[1] - a1[1]) * f)
            r = self._ratio(p0[0], p0[1], p1[0], p1[1], True)
            if r is not None and r >= 0.45:
                return True
        for g in (0.3, 0.5, 0.7):
            pa = F.on(A, tA0 + (tA1 - tA0) * g)
            pb = F.on(B, tB0 + (tB1 - tB0) * g)
            vx, vz = pb[0] - pa[0], pb[1] - pa[1]
            dv = math.hypot(vx, vz)
            vx, vz = vx / dv, vz / dv
            r = self._ratio(pa[0] + 0.3 * vx, pa[1] + 0.3 * vz, pb[0] - 0.3 * vx, pb[1] - 0.3 * vz, True)
            if r is not None and r >= 0.45:
                return True
        # 偵測到的線：在格內的平行線、或從兩長邊中間橫過的線
        ux, uz = F.ux, F.uz
        nx, nz = -uz, ux
        pA = F.on(A, 0.5 * (tA0 + tA1))
        pB = F.on(B, 0.5 * (tB0 + tB1))
        wB = (pB[0] - pA[0]) * nx + (pB[1] - pA[1]) * nz
        lo = max(min(tA0, tA1), min(tB0, tB1)) + 0.15
        hi = min(max(tA0, tA1), max(tB0, tB1)) - 0.15
        for k, X in enumerate(self.lines):
            if k == ia or k == ib:
                continue
            if abs(X.ux * uz - X.uz * ux) <= SIN_PAR:
                off = ((X.cx - pA[0]) * nx + (X.cz - pA[1]) * nz) / wB
                if off <= 0.15 or off >= 0.85 or hi - lo < 0.6:
                    continue
                p0 = F.on(X, lo)
                p1 = F.on(X, hi)
                # 命中比例高，或看得到的線夠長（斜紋禁停區的斜線、網狀線常常只有一段在格內）
                hit, vis, n = self._seg(p0[0], p0[1], p1[0], p1[1], True)
                if vis >= 3 and vis * self.mpp >= 0.25 and (hit / vis >= 0.45 or hit * (hi - lo) / n >= INNER_PAR):
                    return True
            elif abs(X.ux * ux + X.uz * uz) <= COS_CROSS:
                PA = intersect(A, X)
                PB = intersect(B, X)
                if PA is None or PB is None:
                    continue
                tA = (PA[0] - F.ox) * ux + (PA[1] - F.oz) * uz
                tB = (PB[0] - F.ox) * ux + (PB[1] - F.oz) * uz
                fa = (tA - tA0) / (tA1 - tA0)
                fb = (tB - tB0) / (tB1 - tB0)
                if fa <= 0.1 or fa >= 0.9 or fb <= 0.1 or fb >= 0.9:
                    continue
                vx, vz = PB[0] - PA[0], PB[1] - PA[1]
                dv = math.hypot(vx, vz)
                if dv < 0.8:
                    continue
                vx, vz = vx / dv, vz / dv
                r = self._ratio(PA[0] + 0.3 * vx, PA[1] + 0.3 * vz, PB[0] - 0.3 * vx, PB[1] - 0.3 * vz, True)
                if r is not None and r >= 0.45:
                    return True
        return False

    def _row_ok(self, c, cands):
        """只有分隔線的單側格：成排（與別格共用分隔線），或分隔線明顯斜於車頭方向且不寬（車道線多半與車身平行）。
        成排時鄰格的入口邊（開口端或入口線）方向要一致；不一致是被柱子 / 磨損截短的分隔線湊出的鋸齒，不算。"""
        a = c['cell']['c0']
        zig = False
        for d in cands:
            if d is c or not self._long_neighbors(c['cell'], d['cell']):
                continue
            b = d['cell']['c0']
            if abs(a['vx'] * b['vz'] - a['vz'] * b['vx']) > SIN_12:
                zig = True
                continue
            return True
        F = c['cell']['F']
        return not zig and abs(F.uz) < COS_CROSS

    def _span_on(self, cell, which):
        """格子在某條長邊上的區段，用該線自身參數表示（不同長邊對之間可比）。"""
        F = cell['F']
        X = self.lines[cell['a'] if which == 0 else cell['b']]
        t0, t1 = _cell_range(cell, which)
        p0 = F.on(X, t0)
        p1 = F.on(X, t1)
        s0 = (p0[0] - X.cx) * X.ux + (p0[1] - X.cz) * X.uz
        s1 = (p1[0] - X.cx) * X.ux + (p1[1] - X.cz) * X.uz
        return min(s0, s1), max(s0, s1)

    def _long_neighbors(self, c, d):
        """兩格共用一條長邊（同一條線、範圍重疊過半、另一條長邊不同）。"""
        for wc, lc in ((0, c['a']), (1, c['b'])):
            for wd, ld in ((0, d['a']), (1, d['b'])):
                if lc != ld:
                    continue
                oc = c['b'] if wc == 0 else c['a']
                od = d['b'] if wd == 0 else d['a']
                if oc == od:
                    continue
                X, Yc, Yd = self.lines[lc], self.lines[oc], self.lines[od]
                sc_ = (Yc.cx - X.cx) * (-X.uz) + (Yc.cz - X.cz) * X.ux
                sd_ = (Yd.cx - X.cx) * (-X.uz) + (Yd.cz - X.cz) * X.ux
                if sc_ * sd_ >= 0:
                    continue
                ra = self._span_on(c, wc)
                rb = self._span_on(d, wd)
                ov = min(ra[1], rb[1]) - max(ra[0], rb[0])
                if ov >= 0.5 * min(ra[1] - ra[0], rb[1] - rb[0]):
                    return True
        return False

    # ------------------------------------------------------------ 類型與入口
    def _decide_kinds(self, cands):
        for i, c in enumerate(cands):
            c['order'] = i
        for c in cands:
            cell = c['cell']
            quad = [c['Ak'], c['Bk'], c['Bm'], c['Am']]
            if len(c['kinds']) == 1:
                kind = c['kinds'][0]
            else:
                vp = vl = 0.0
                L, w = cell['L'], cell['w']
                if 'l' not in c['kinds']:
                    vp += 10
                if 'p' not in c['kinds']:
                    vl += 10
                fe = facing_edge2(quad)
                if fe is not None:
                    if fe in (0, 2):
                        vp += 1.0
                    else:
                        vl += 1.0
                if cell['one']:
                    pass
                elif L >= 5.9:
                    vl += 0.6
                elif L <= 5.6:
                    vp += 0.6
                if w <= 2.3:
                    vl += 0.4
                elif w >= 2.45:
                    vp += 0.4
                for d in cands:
                    if d is c:
                        continue
                    dc = d['cell']
                    wgt = 1.0 if (dc['one'] or cell['one']) else 1.5
                    if self._long_neighbors(cell, dc):
                        vp += wgt
                    elif short_neighbors(cell, dc):
                        vl += wgt
                kind = 'p' if vp >= vl else 'l'
            c['kind'] = kind
            if kind == 'l' and cell['one']:
                # 平行格只看到一個橫邊：遠端用標準長度 6 m
                F, A, B = cell['F'], self.lines[cell['a']], self.lines[cell['b']]
                quad[2] = F.on(B, cell['c0']['tB'] + cell['s'] * DEPTH_PAR)
                quad[3] = F.on(A, cell['c0']['tA'] + cell['s'] * DEPTH_PAR)
            # 入口邊（quad 的邊 0 = c0 橫邊, 1 = B 長邊, 2 = c1 橫邊, 3 = A 長邊）
            if kind in ('p', 'a'):
                zs_ = [q[1] for q in quad]
                F_ = cell['F']
                if kind == 'p' and min(zs_) < -0.5 and max(zs_) > 0.5 and abs(F_.uz) > COS_CROSS:
                    # 和自車並排（分隔線與車身平行、z 範圍涵蓋自車）：入口取車頭那端（台灣多倒車入庫）
                    m0 = 0.5 * (quad[0][1] + quad[1][1])
                    m2 = 0.5 * (quad[2][1] + quad[3][1])
                    ent = 0 if m0 >= m2 else 2
                elif cell['one']:
                    ent = 0
                else:
                    fe = facing_edge2(quad, only=(0, 2))
                    if fe is None:
                        m0 = 0.5 * (quad[0][1] + quad[1][1])
                        m2 = 0.5 * (quad[2][1] + quad[3][1])
                        fe = 0 if m0 >= m2 else 2
                    ent = fe
            else:
                fe = facing_edge2(quad, only=(1, 3))
                if fe is None:
                    m1 = 0.5 * (quad[1][0] + quad[2][0])
                    m3 = 0.5 * (quad[3][0] + quad[0][0])
                    fe = 1 if m1 <= m3 else 3
                ent = fe
            poly = [quad[ent], quad[(ent + 1) % 4], quad[(ent + 2) % 4], quad[(ent + 3) % 4]]
            if poly_signed_area(poly) < 0:
                poly = [poly[1], poly[0], poly[3], poly[2]]
            c['poly'] = poly


class Frame:
    """長邊對的共同座標：原點 o，方向 u。"""

    def __init__(self, ox, oz, ux, uz):
        self.ox, self.oz, self.ux, self.uz = ox, oz, ux, uz

    def on(self, X, t):
        """X 線上共同參數 t 的點。"""
        du = X.ux * self.ux + X.uz * self.uz
        t0 = (X.cx - self.ox) * self.ux + (X.cz - self.oz) * self.uz
        tx = (t - t0) / du
        return (X.cx + tx * X.ux, X.cz + tx * X.uz)

    def pieces(self, X):
        du = X.ux * self.ux + X.uz * self.uz
        t0 = (X.cx - self.ox) * self.ux + (X.cz - self.oz) * self.uz
        out = []
        for p0, p1 in X.pieces:
            a, b = t0 + p0 * du, t0 + p1 * du
            out.append((min(a, b), max(a, b)))
        out.sort()
        return out


def _run(pcs, t, s, gmax=None):
    """從 t 沿線往 s 方向走過間隙 ≤ gmax 的線段，回傳最遠到達的參數。"""
    gmax = RUN_GAP if gmax is None else gmax
    far = t
    if s > 0:
        for (p0, p1) in pcs:
            if p1 <= far:
                continue
            if p0 - far > gmax:
                break
            far = p1
    else:
        for (p0, p1) in reversed(pcs):
            if p0 >= far:
                continue
            if far - p1 > gmax:
                break
            far = p0
    return far


def _dash_after(pcs, e, s):
    """端點 e 之後（往 s 方向）1–8 m 內同一條線上還有別段 → 像虛線。"""
    for (p0, p1) in pcs:
        d = (p0 - e) if s > 0 else (e - p1)
        if 1.0 <= d <= 8.0:
            return True
    return False


def intersect(L1, L2):
    den = L1.ux * L2.uz - L1.uz * L2.ux
    if abs(den) < 1e-9:
        return None
    dx, dz = L2.cx - L1.cx, L2.cz - L1.cz
    a = (dx * L2.uz - dz * L2.ux) / den
    return (L1.cx + a * L1.ux, L1.cz + a * L1.uz)


def facing_edge(quad, only=(0, 1, 2, 3)):
    """面向原點（自車）的邊：原點在其外側且距離最近者；原點在格內回傳 None。"""
    gx = (quad[0][0] + quad[1][0] + quad[2][0] + quad[3][0]) / 4.0
    gz = (quad[0][1] + quad[1][1] + quad[2][1] + quad[3][1]) / 4.0
    best, bf = None, 1e18
    for e in only:
        q1, q2 = quad[e], quad[(e + 1) % 4]
        ex, ez = q2[0] - q1[0], q2[1] - q1[1]
        nx, nz = -ez, ex
        if (gx - q1[0]) * nx + (gz - q1[1]) * nz > 0:
            nx, nz = -nx, -nz
        nl = math.hypot(nx, nz)
        if nl < 1e-9:
            continue
        f = ((0.0 - q1[0]) * nx + (0.0 - q1[1]) * nz) / nl
        if f > 0 and f < bf:
            best, bf = e, f
    return best


def facing_edge2(quad, only=(0, 1, 2, 3)):
    """入口面向自車：格子在自車左右（x 方向不跟車寬重疊）→ 取朝向自車、較平行車身的邊；
    格子在車頭 / 車尾方向 → 取朝向自車、較垂直車身的邊。多個候選取中點離原點最近者。"""
    xs = [q[0] for q in quad]
    corr = min(xs) < CORRIDOR and max(xs) > -CORRIDOR
    gx = (quad[0][0] + quad[1][0] + quad[2][0] + quad[3][0]) / 4.0
    gz = (quad[0][1] + quad[1][1] + quad[2][1] + quad[3][1]) / 4.0
    best, bd = None, 1e18
    for e in only:
        q1, q2 = quad[e], quad[(e + 1) % 4]
        mx, mz = 0.5 * (q1[0] + q2[0]), 0.5 * (q1[1] + q2[1])
        nx, nz = -(q2[1] - q1[1]), q2[0] - q1[0]
        if (gx - q1[0]) * nx + (gz - q1[1]) * nz > 0:
            nx, nz = -nx, -nz
        if corr:
            ok = abs(nz) >= abs(nx) and nz * (0.0 - mz) > 0
        else:
            ok = abs(nx) >= abs(nz) and nx * (0.0 - mx) > 0
        if not ok:
            continue
        d = mx * mx + mz * mz
        if d < bd:
            best, bd = e, d
    return best if best is not None else facing_edge(quad, only)


def _cell_range(cell, which):
    """格子在長邊 A（which=0）或 B（1）上的共同參數範圍。"""
    key = 'tA' if which == 0 else 'tB'
    t0 = cell['c0'][key]
    t1 = cell['c1'][key] if cell['c1'] is not None else t0 + cell['s'] * DEPTH_DEF
    return min(t0, t1), max(t0, t1)


def short_neighbors(c, d):
    """同一長邊對、共用一個橫邊、分處兩側。"""
    if c['a'] != d['a'] or c['b'] != d['b']:
        return False
    for x in (c['c0'], c['c1']):
        for y in (d['c0'], d['c1']):
            if x is not None and x is y:
                return True
    return False


# ---------------------------------------------------------------- 多邊形工具
def poly_signed_area(p):
    s = 0.0
    n = len(p)
    for k in range(n):
        x0, z0 = p[k]
        x1, z1 = p[(k + 1) % n]
        s += x0 * z1 - x1 * z0
    return 0.5 * s


def poly_area(p):
    return abs(poly_signed_area(p))


def _clip(poly, inside, cut):
    out = []
    n = len(poly)
    for k in range(n):
        cur, prv = poly[k], poly[k - 1]
        ic, ip = inside(cur), inside(prv)
        if ic:
            if not ip:
                out.append(cut(prv, cur))
            out.append(cur)
        elif ip:
            out.append(cut(prv, cur))
    return out


def clip_rect_area(poly, r):
    x0, z0, x1, z1 = r

    def cx(a, b, x):
        t = (x - a[0]) / (b[0] - a[0])
        return (x, a[1] + (b[1] - a[1]) * t)

    def cz(a, b, z):
        t = (z - a[1]) / (b[1] - a[1])
        return (a[0] + (b[0] - a[0]) * t, z)

    p = list(poly)
    for inside, cut in ((lambda q: q[0] >= x0, lambda a, b: cx(a, b, x0)),
                        (lambda q: q[0] <= x1, lambda a, b: cx(a, b, x1)),
                        (lambda q: q[1] >= z0, lambda a, b: cz(a, b, z0)),
                        (lambda q: q[1] <= z1, lambda a, b: cz(a, b, z1))):
        if not p:
            return 0.0
        p = _clip(p, inside, cut)
    return poly_area(p) if len(p) >= 3 else 0.0


def clip_convex(subject, clipper):
    """凸多邊形交集（Sutherland–Hodgman；clipper 須為逆時針）。"""
    p = list(subject)
    n = len(clipper)
    for k in range(n):
        if not p:
            break
        a, b = clipper[k], clipper[(k + 1) % n]
        ex, ez = b[0] - a[0], b[1] - a[1]

        def inside(q, a=a, ex=ex, ez=ez):
            return ex * (q[1] - a[1]) - ez * (q[0] - a[0]) >= 0

        def cut(s, e, a=a, ex=ex, ez=ez):
            ds = ex * (s[1] - a[1]) - ez * (s[0] - a[0])
            de = ex * (e[1] - a[1]) - ez * (e[0] - a[0])
            t = ds / (ds - de)
            return (s[0] + (e[0] - s[0]) * t, s[1] + (e[1] - s[1]) * t)

        p = _clip(p, inside, cut)
    return p


def poly_iou(p, q):
    if poly_signed_area(p) < 0:
        p = p[::-1]
    if poly_signed_area(q) < 0:
        q = q[::-1]
    inter = clip_convex(p, q)
    ia = poly_area(inter) if len(inter) >= 3 else 0.0
    u = poly_area(p) + poly_area(q) - ia
    return ia / u if u > 1e-9 else 0.0


# ================================================================ 合成 BEV 場景
EGO_HX, EGO_HZ = 0.925, 2.42
EGO_EXCL = [-0.975, -2.47, 0.975, 2.47]


def _rot(x, z, c, s):
    return (x * c - z * s, x * s + z * c)


class Scene:
    """場景在「版面座標」建立，最後整體旋轉 yaw（自車與走道不完全平行）。"""

    def __init__(self, rng, w=300, h=350, mpp=0.04, cx=150.0, cy=175.0, style='own'):
        self.rng = rng
        self.w, self.h, self.mpp, self.cx, self.cy, self.style = w, h, mpp, cx, cy, style
        yaw = math.radians(rng.uniform(-8, 8))
        self.c, self.s = math.cos(yaw), math.sin(yaw)
        self.strokes = []     # (x0, z0, x1, z1, width, rgb, opacity)
        self.cars = []        # (corners[4], rgb, smear)
        self.areas = []       # (poly, rgb) 人行道等色塊
        self.slots = []       # {'p': [(x,z)]*4（入口兩點在前，逆時針）, 'kind'}
        self.tags = []
        self.worn = rng.random() < 0.35
        self.lw, self.col = 0.12, (240, 240, 240)
        # 對抗場景用的選項（預設值不消耗亂數，原有場景的影像不變）
        self.occ_p = None     # 停車機率（None = 各版面原本的值）
        self.crook = False    # 車停歪（橫移 ±0.45 m、轉 ±0.15 rad）
        self.yolo_miss = 0.1  # 車輛框漏掉的機率
        self.objs = []        # 沒有車輛框的立體物（行人、推車、柱子）：(corners, rgb, smear)
        self.shade = []       # 額外陰影：f(X, Z) → 0..1 的遮光比例
        self.base_rgb = None  # 指定地面顏色
        self.dim = 1.0        # 整體亮度（地下室）
        self.blur_mul = 1.0   # 遠處模糊倍數
        self.base_blur = 0.0  # 全圖模糊（px）
        self.seam_gain = 0.0  # 側鏡頭與前後鏡頭的亮度差（例如 0.15）
        self.seam_shift = None  # 各鏡頭的校正誤差 [(dx, dz, 旋轉 rad)] * 4（前、後、左、右）

    def T(self, x, z):
        return _rot(x, z, self.c, self.s)

    def line(self, x0, z0, x1, z1, lw=None, rgb=None, op=1.0):
        a = self.T(x0, z0)
        b = self.T(x1, z1)
        lw = lw if lw is not None else self.lw
        self.strokes.append((a[0], a[1], b[0], b[1], lw, rgb if rgb is not None else self.col, op))

    def slot(self, pts, kind):
        p = [self.T(*q) for q in pts]
        if poly_signed_area(p) < 0:
            p = [p[1], p[0], p[3], p[2]]
        self.slots.append({'p': p, 'kind': kind, 'occupied': False})

    def car(self, xc, zc, ang, L=None, W=None):
        rng = self.rng
        L = L or rng.uniform(4.3, 4.9)
        W = W or rng.uniform(1.72, 1.9)
        if self.crook:
            e = rng.uniform(-0.45, 0.45)
            xc, zc = xc + e * math.cos(ang), zc - e * math.sin(ang)
            ang += rng.uniform(-0.15, 0.15)
        ca, sa = math.cos(ang), math.sin(ang)
        pts = []
        for (a, b) in ((-W / 2, -L / 2), (W / 2, -L / 2), (W / 2, L / 2), (-W / 2, L / 2)):
            pts.append(self.T(xc + a * ca + b * sa, zc - a * sa + b * ca))
        pal = [(228, 228, 226), (175, 177, 182), (32, 32, 36), (150, 28, 30), (40, 62, 120), (110, 110, 112)]
        rgb = pal[rng.integers(len(pal))]
        self.cars.append((pts, rgb, rng.uniform(0.3, 1.2)))


def _occ(sc, p):
    return p if sc.occ_p is None else sc.occ_p


def _style_line(sc):
    rng = sc.rng
    sc.lw = rng.uniform(0.10, 0.15)
    if rng.random() < 0.2:
        sc.col = (rng.uniform(205, 235), rng.uniform(165, 195), rng.uniform(30, 70))
    else:
        v = rng.uniform(215, 250)
        sc.col = (v, v, v * rng.uniform(0.96, 1.0))


def lay_perp_aisle(sc, side):
    rng = sc.rng
    xe = side * rng.uniform(2.3, 3.6)
    wd = rng.uniform(2.35, 2.7)
    dp = rng.uniform(5.0, 5.6)
    st = ['box', 'u', 'T', 'sep', 'sepE'][rng.integers(5)]
    z0 = rng.uniform(-9.5, -7.0)
    zs = [z0 + k * wd for k in range(int(17 / wd) + 1)]
    xb = xe + side * dp
    if st in ('box', 'sepE'):
        sc.line(xe, zs[0], xe, zs[-1])
    if st in ('box', 'u'):
        sc.line(xb, zs[0], xb, zs[-1])
    for z in zs:
        if st == 'T':
            hb = rng.uniform(0.35, 0.6)
            sc.line(xe, z - hb, xe, z + hb)
            sc.line(xe, z, xe + side * rng.uniform(0.5, 1.0), z)
        else:
            sc.line(xe, z, xb, z)
    for k in range(len(zs) - 1):
        sc.slot([(xe, zs[k]), (xe, zs[k + 1]), (xb, zs[k + 1]), (xb, zs[k])], 'p')
        if rng.random() < _occ(sc, 0.35):
            sc.car(xe + side * (dp / 2 + rng.uniform(-0.2, 0.4)), 0.5 * (zs[k] + zs[k + 1]) + rng.uniform(-0.15, 0.15),
                   math.pi / 2 + rng.uniform(-0.05, 0.05))
    sc.tags.append('aisle-' + st)


def lay_ego_perp(sc):
    rng = sc.rng
    wd = rng.uniform(2.4, 2.7)
    dp = rng.uniform(5.0, 5.6)
    dl = rng.uniform(-0.2, 0.2)
    flip = rng.random() < 0.3
    ze = dp / 2 + rng.uniform(-0.3, 0.3)
    if flip:
        ze = -ze
    zb = ze - dp if not flip else ze + dp
    st = ['box', 'u', 'sepE'][rng.integers(3)]
    xs = [dl - wd / 2 + k * wd for k in range(-3, 4)]
    if st in ('box', 'sepE'):
        sc.line(xs[0], ze, xs[-1], ze)
    if st in ('box', 'u'):
        sc.line(xs[0], zb, xs[-1], zb)
    for x in xs:
        sc.line(x, ze, x, zb)
    for k in range(len(xs) - 1):
        sc.slot([(xs[k], ze), (xs[k + 1], ze), (xs[k + 1], zb), (xs[k], zb)], 'p')
        mid = 0.5 * (xs[k] + xs[k + 1])
        if abs(mid - dl) > 0.1 and rng.random() < _occ(sc, 0.4):
            sc.car(mid + rng.uniform(-0.15, 0.15), 0.5 * (ze + zb) + rng.uniform(-0.3, 0.3), rng.uniform(-0.05, 0.05))
    sc.tags.append('egoperp-' + st)


def _sidewalk(sc, xc_, side):
    rng = sc.rng
    walk = (rng.uniform(120, 175),) * 3
    far = xc_ + side * 10
    sc.areas.append(([sc.T(xc_, -12), sc.T(far, -12), sc.T(far, 12), sc.T(xc_, 12)], walk))
    sc.line(xc_, -12, xc_, 12, lw=0.18, rgb=(min(255, walk[0] + 25),) * 3)


def lay_parallel(sc, side):
    rng = sc.rng
    xi = side * rng.uniform(1.4, 2.6)
    wd = rng.uniform(2.0, 2.5)
    ln = rng.uniform(5.6, 6.6)
    xo = xi + side * wd
    st = ['box', 'boxes', 'L', 'box'][rng.integers(4)]
    gap = rng.uniform(0.6, 1.2) if st == 'boxes' else 0.0
    z = rng.uniform(-11, -7)
    zs = []
    while z < 9:
        zs.append((z, z + ln))
        z += ln + gap
    _sidewalk(sc, xo + side * rng.uniform(0.05, 0.4), side)
    if st == 'box':
        sc.line(xi, zs[0][0], xi, zs[-1][1])
        sc.line(xo, zs[0][0], xo, zs[-1][1])
    for (za, zb) in zs:
        if st == 'box':
            sc.line(xi, za, xo, za)
            sc.line(xi, zb, xo, zb)
        elif st == 'boxes':
            for (a, b, c, d) in ((xi, za, xo, za), (xi, zb, xo, zb), (xi, za, xi, zb), (xo, za, xo, zb)):
                sc.line(a, b, c, d)
        else:
            arm = rng.uniform(0.45, 0.8)
            for (x, z0, dz) in ((xi, za, 1), (xo, za, 1), (xi, zb, -1), (xo, zb, -1)):
                sc.line(x, z0, x, z0 + dz * arm)
                dx = side if x == xi else -side
                sc.line(x, z0, x + dx * arm, z0)
        sc.slot([(xi, za), (xi, zb), (xo, zb), (xo, za)], 'l')
        if rng.random() < _occ(sc, 0.4):
            sc.car(0.5 * (xi + xo) + rng.uniform(-0.1, 0.1), 0.5 * (za + zb) + rng.uniform(-0.5, 0.5),
                   rng.uniform(-0.04, 0.04))
    sc.tags.append('parallel-' + st)


def lay_ego_parallel(sc):
    rng = sc.rng
    wd = rng.uniform(2.1, 2.5)
    ln = rng.uniform(5.8, 6.6)
    dl = rng.uniform(-0.15, 0.15)
    xi, xo = dl - wd / 2, dl + wd / 2
    e = rng.uniform(-0.4, 0.4)
    zs = [(-ln / 2 + e + k * ln, ln / 2 + e + k * ln) for k in (-2, -1, 0, 1, 2)]
    sc.line(xi, zs[0][0], xi, zs[-1][1])
    sc.line(xo, zs[0][0], xo, zs[-1][1])
    for (za, zb) in zs:
        sc.line(xi, za, xo, za)
        sc.slot([(xi, za), (xi, zb), (xo, zb), (xo, za)], 'l')
        if abs(0.5 * (za + zb)) > 1 and rng.random() < _occ(sc, 0.4):
            sc.car(dl + rng.uniform(-0.1, 0.1), 0.5 * (za + zb) + rng.uniform(-0.4, 0.4), rng.uniform(-0.04, 0.04))
    sc.line(xi, zs[-1][1], xo, zs[-1][1])
    _sidewalk(sc, xo + rng.uniform(0.1, 0.4), 1)
    sc.tags.append('egopar')


def lay_angled(sc, side):
    rng = sc.rng
    al = math.radians(rng.uniform(30, 60))
    xe = side * rng.uniform(2.5, 3.5)
    wp = rng.uniform(2.4, 2.7)
    dp = rng.uniform(5.0, 5.5)
    lean = 1 if rng.random() < 0.5 else -1
    sp = wp / math.sin(al)
    dx, dz = side * math.sin(al), lean * math.cos(al)
    st = ['box', 'u', 'sep', 'sepE'][rng.integers(4)]
    z0 = rng.uniform(-12, -9)
    zs = [z0 + k * sp for k in range(int(22 / sp) + 1)]
    if st in ('box', 'sepE'):
        sc.line(xe, zs[0], xe, zs[-1])
    if st in ('box', 'u'):
        sc.line(xe + dp * dx, zs[0] + dp * dz, xe + dp * dx, zs[-1] + dp * dz)
    for z in zs:
        sc.line(xe, z, xe + dp * dx, z + dp * dz)
    for k in range(len(zs) - 1):
        a, b = zs[k], zs[k + 1]
        sc.slot([(xe, a), (xe, b), (xe + dp * dx, b + dp * dz), (xe + dp * dx, a + dp * dz)], 'a')
        if rng.random() < _occ(sc, 0.3):
            m = 0.5 * (a + b)
            sc.car(xe + dx * dp * 0.55, m + dz * dp * 0.55, math.atan2(dx, dz))
    sc.tags.append('angled-' + st)


def lay_perp_ahead(sc):
    rng = sc.rng
    ze = rng.uniform(3.4, 5.0) * (1 if rng.random() < 0.7 else -1)
    sg = 1 if ze > 0 else -1
    wd = rng.uniform(2.4, 2.7)
    dp = rng.uniform(5.0, 5.6)
    zb = ze + sg * dp
    st = ['box', 'u', 'T', 'sepE'][rng.integers(4)]
    x0 = rng.uniform(-7.5, -6)
    xs = [x0 + k * wd for k in range(int(14 / wd) + 1)]
    if st in ('box', 'sepE'):
        sc.line(xs[0], ze, xs[-1], ze)
    if st in ('box', 'u'):
        sc.line(xs[0], zb, xs[-1], zb)
    for x in xs:
        if st == 'T':
            hb = rng.uniform(0.35, 0.6)
            sc.line(x - hb, ze, x + hb, ze)
            sc.line(x, ze, x, ze + sg * rng.uniform(0.5, 1.0))
        else:
            sc.line(x, ze, x, zb)
    for k in range(len(xs) - 1):
        sc.slot([(xs[k], ze), (xs[k + 1], ze), (xs[k + 1], zb), (xs[k], zb)], 'p')
        if rng.random() < _occ(sc, 0.3):
            sc.car(0.5 * (xs[k] + xs[k + 1]), ze + sg * (dp / 2 + 0.3), 0.0)
    sc.tags.append('ahead-' + st)


def lay_road(sc, stop=True, zebra=True, xmax=99.0):
    """負樣本：車道線、雙黃線、停止線、斑馬線。xmax：只畫 x < xmax 的車道線（右側留給路邊停車格）。"""
    rng = sc.rng
    lw_lane = rng.uniform(0.1, 0.15)
    white = (rng.uniform(220, 250),) * 3
    yel = (225, 185, 50)
    lane = rng.uniform(2.8, 3.5)
    x0 = rng.uniform(-lane / 2 - 0.3, -lane / 2 + 0.3)
    zstop = rng.uniform(3.0, 5.0)
    zend = zstop if stop else 12
    for k in range(-2, 3):
        x = x0 + k * lane
        if x > xmax:
            continue
        kind = rng.integers(3)
        if k == -1 and rng.random() < 0.5:
            sc.line(x - 0.1, -12, x - 0.1, zend, lw=lw_lane, rgb=yel)
            sc.line(x + 0.1, -12, x + 0.1, zend, lw=lw_lane, rgb=yel)
        elif kind == 0:
            sc.line(x, -12, x, zend, lw=lw_lane, rgb=white)
        else:
            z = -12 + rng.uniform(-10, 0)
            while z < zend:
                a, b = max(z, -12), min(z + 4.0, zend)
                if b > a:
                    sc.line(x, a, x, b, lw=lw_lane, rgb=white)
                z += 10.0
    if stop:
        sc.line(x0 - 2 * lane, zstop, min(xmax, x0 + 2 * lane), zstop, lw=rng.uniform(0.3, 0.45), rgb=white)
    if zebra:
        zz = zstop + rng.uniform(1.0, 2.0)
        x = -7.0
        bw = rng.uniform(0.4, 0.5)
        while x < min(7.0, xmax):
            sc.line(x, zz, x, zz + 3.0, lw=bw, rgb=white)
            x += bw + rng.uniform(0.4, 0.6)
    sc.tags.append('road')


def lay_scooter(sc, side):
    """機車格：約 1 x 2 m 的窄格，不能報成汽車格。"""
    rng = sc.rng
    xi = side * rng.uniform(1.5, 3.0)
    dp = rng.uniform(1.8, 2.2)
    wd = rng.uniform(0.9, 1.1)
    xo = xi + side * dp
    z0 = rng.uniform(-8, -4)
    zs = [z0 + k * wd for k in range(int(rng.uniform(5, 12)))]
    sc.line(xi, zs[0], xi, zs[-1])
    sc.line(xo, zs[0], xo, zs[-1])
    for z in zs:
        sc.line(xi, z, xo, z)
    sc.tags.append('scooter')


SCENE_KINDS = ['aisle', 'egoperp', 'parallel', 'egopar', 'angled', 'ahead', 'road', 'scooter']


def make_scene(rng, kind, w=300, h=350, mpp=0.04, cx=150.0, cy=175.0, style='own'):
    sc = Scene(rng, w, h, mpp, cx, cy, style)
    _style_line(sc)
    if kind == 'aisle':
        lay_perp_aisle(sc, 1)
        if rng.random() < 0.5:
            _style_line(sc)
            lay_perp_aisle(sc, -1)
    elif kind == 'egoperp':
        lay_ego_perp(sc)
    elif kind == 'parallel':
        side = 1 if rng.random() < 0.8 else -1
        lay_parallel(sc, side)
        if side > 0 and rng.random() < 0.5:
            lay_road(sc, stop=False, zebra=False, xmax=0.0)
    elif kind == 'egopar':
        lay_ego_parallel(sc)
    elif kind == 'angled':
        lay_angled(sc, 1 if rng.random() < 0.5 else -1)
    elif kind == 'ahead':
        lay_perp_ahead(sc)
    elif kind == 'road':
        lay_road(sc, stop=rng.random() < 0.7, zebra=rng.random() < 0.7)
        if rng.random() < 0.5:
            lay_scooter(sc, 1)
    elif kind == 'scooter':
        lay_scooter(sc, 1)
        if rng.random() < 0.5:
            lay_scooter(sc, -1)
    return sc


# ================================================================ 對抗場景（審查用，比原本的合成資料更刁難）
ADV_KINDS = ['blur', 'seam', 'occl', 'xwalk', 'double', 'curb', 'rot', 'shadow', 'scoot2', 'stopper', 'text',
             'hatch', 'garage', 'pavers']
YEL = (225, 185, 50)


def _clip_seg_rect(x0, z0, x1, z1, r):
    """線段裁到矩形 r = (xmin, zmin, xmax, zmax) 內（Liang–Barsky）；完全在外回傳 None。"""
    dx, dz = x1 - x0, z1 - z0
    t0, t1 = 0.0, 1.0
    for p, q in ((-dx, x0 - r[0]), (dx, r[2] - x0), (-dz, z0 - r[1]), (dz, r[3] - z0)):
        if abs(p) < 1e-12:
            if q < 0:
                return None
            continue
        t = q / p
        if p < 0:
            t0 = max(t0, t)
        else:
            t1 = min(t1, t)
    if t1 <= t0:
        return None
    return (x0 + t0 * dx, z0 + t0 * dz, x0 + t1 * dx, z0 + t1 * dz)


def _hatch(sc, r, pitch, ang, lw, rgb, border=True, blw=None):
    """矩形 r 內畫斜線（角度 ang，間距 pitch），border 時加外框。"""
    ca, sa = math.cos(ang), math.sin(ang)
    cxr, czr = 0.5 * (r[0] + r[2]), 0.5 * (r[1] + r[3])
    R = math.hypot(r[2] - r[0], r[3] - r[1])
    k = -R
    while k <= R:
        ox, oz = cxr - sa * k, czr + ca * k
        s = _clip_seg_rect(ox - ca * R, oz - sa * R, ox + ca * R, oz + sa * R, r)
        if s is not None:
            sc.line(*s, lw=lw, rgb=rgb)
        k += pitch
    if border:
        b = blw or lw
        for (a, c, d, e) in ((r[0], r[1], r[2], r[1]), (r[2], r[1], r[2], r[3]), (r[2], r[3], r[0], r[3]),
                             (r[0], r[3], r[0], r[1])):
            sc.line(a, c, d, e, lw=b, rgb=rgb)


_SEG7 = {'0': 'abcdef', '1': 'bc', '2': 'abged', '3': 'abgcd', '4': 'fgbc', '5': 'afgcd', '6': 'afgedc',
         '7': 'abc', '8': 'abcdefg', '9': 'abcdfg'}
_SEGP = {'a': (0, 1, .6, 1), 'b': (.6, 1, .6, .5), 'c': (.6, .5, .6, 0), 'd': (0, 0, .6, 0), 'e': (0, 0, 0, .5),
         'f': (0, .5, 0, 1), 'g': (0, .5, .6, .5)}


def _glyph(sc, x, z, size, ang, rgb=None, lw=0.1, text=None):
    """在 (x, z) 畫一個字：數字用 7 段，否則畫個方塊字（橫豎筆畫）。ang = 字頂朝向（0 = +z）。"""
    rng = sc.rng
    ux, uz = math.cos(ang), -math.sin(ang)       # 字的右方
    vx, vz = math.sin(ang), math.cos(ang)        # 字的上方

    def P(u, v):
        return (x + (u - 0.3) * size * ux + (v - 0.5) * size * vx, z + (u - 0.3) * size * uz + (v - 0.5) * size * vz)
    segs = []
    if text is not None and text in _SEG7:
        for s in _SEG7[text]:
            segs.append(_SEGP[s])
    else:
        for _ in range(rng.integers(2, 5)):
            v = rng.uniform(0.05, 0.95)
            a = rng.uniform(-0.1, 0.2)
            segs.append((a, v, rng.uniform(0.5, 0.9), v))
        for _ in range(rng.integers(1, 4)):
            u = rng.uniform(0.0, 0.7)
            segs.append((u, rng.uniform(0.0, 0.3), u, rng.uniform(0.6, 1.0)))
    for (u0, v0, u1, v1) in segs:
        a, b = P(u0, v0), P(u1, v1)
        sc.line(a[0], a[1], b[0], b[1], lw=lw, rgb=rgb)


def _arc(sc, x, z, r, a0, a1, lw, rgb, n=10):
    for k in range(n):
        t0 = a0 + (a1 - a0) * k / n
        t1 = a0 + (a1 - a0) * (k + 1) / n
        sc.line(x + r * math.cos(t0), z + r * math.sin(t0), x + r * math.cos(t1), z + r * math.sin(t1), lw=lw, rgb=rgb)


def _scooter_sym(sc, x, z, size, ang, rgb=(240, 240, 240)):
    """機車圖示（兩個輪子 + 車身）。"""
    ca, sa = math.sin(ang), math.cos(ang)
    for f in (-0.35, 0.35):
        _arc(sc, x + f * size * ca, z + f * size * sa, 0.15 * size, 0, 2 * math.pi, 0.08, rgb, n=8)
    sc.line(x - 0.3 * size * ca, z - 0.3 * size * sa, x + 0.3 * size * ca, z + 0.3 * size * sa, lw=0.12, rgb=rgb)
    sc.line(x, z, x + 0.15 * size * sa, z - 0.15 * size * ca, lw=0.1, rgb=rgb)


def _obj(sc, x, z, wd, ln, ang, rgb, smear):
    ca, sa = math.cos(ang), math.sin(ang)
    pts = [sc.T(x + a * ca + b * sa, z - a * sa + b * ca)
           for (a, b) in ((-wd / 2, -ln / 2), (wd / 2, -ln / 2), (wd / 2, ln / 2), (-wd / 2, ln / 2))]
    sc.objs.append((pts, rgb, smear))


def _seg_points(sc, kinds=('p', 'a', 'l')):
    """真值格的邊（版面座標換回來太麻煩，直接用車身座標的角點）上的隨機點，給遮擋物用。"""
    out = []
    for g in sc.slots:
        if g['kind'] not in kinds:
            continue
        p = g['p']
        for k in range(4):
            a, b = p[k], p[(k + 1) % 4]
            out.append((a, b))
    return out


def _shadow_fns(sc, n):
    """額外陰影：建物硬邊、百葉 / 欄杆條紋、電線桿影子、兩片陰影間的亮縫、樹影斑。"""
    rng = sc.rng
    for _ in range(n):
        t = rng.integers(5)
        ang = rng.uniform(0, math.pi)
        ca, sa = math.cos(ang), math.sin(ang)
        off = rng.uniform(-5, 5)
        k = rng.uniform(0.35, 0.6)
        if t == 0:
            sc.shade.append(lambda X, Z, ca=ca, sa=sa, off=off, k=k:
                            k / (1 + np.exp(-np.clip((X * ca + Z * sa - off) / 0.05, -50, 50))))
        elif t == 1:
            pitch = rng.uniform(0.3, 2.8)
            duty = rng.uniform(0.5, 0.85)
            ext = rng.uniform(3, 8)

            def f(X, Z, ca=ca, sa=sa, off=off, k=k, pitch=pitch, duty=duty, ext=ext):
                d = X * ca + Z * sa - off
                e = -X * sa + Z * ca
                ph = np.mod(e / pitch, 1.0)
                bar = np.clip((duty - np.abs(ph - 0.5) * 2) * pitch / 0.04, 0, 1)   # 暗條，亮縫寬 (1-duty)*pitch
                return k * bar * (np.abs(d) < ext)
            sc.shade.append(f)
        elif t == 2:
            wid = rng.uniform(0.08, 0.2)
            L = rng.uniform(4, 10)
            sep = rng.uniform(0.25, 0.5) if rng.random() < 0.5 else None

            def f(X, Z, ca=ca, sa=sa, off=off, k=k, wid=wid, L=L, sep=sep):
                d = X * ca + Z * sa - off
                e = -X * sa + Z * ca
                m = (np.abs(d) < wid / 2).astype(float)
                if sep is not None:
                    m = np.maximum(m, (np.abs(d - sep) < wid / 2).astype(float))
                return k * m * (np.abs(e) < L / 2)
            sc.shade.append(f)
        elif t == 3:
            gap = rng.uniform(0.1, 0.25)
            L = rng.uniform(3, 9)

            def f(X, Z, ca=ca, sa=sa, off=off, k=k, gap=gap, L=L):
                d = X * ca + Z * sa - off
                e = -X * sa + Z * ca
                return k * ((np.abs(d) > gap / 2) | (np.abs(e) > L / 2)) * (np.abs(d) < 3)
            sc.shade.append(f)
        else:
            from scipy.ndimage import gaussian_filter
            m = gaussian_filter((_smooth_noise(rng, sc.h, sc.w, rng.uniform(3, 8)) > rng.uniform(0.2, 0.7)).astype(float),
                                1.0)
            sc.shade.append(lambda X, Z, m=m, k=k: k * m)


def lay_xwalk(sc):
    """道路 + 行人穿越道（枕木紋或有邊線的斑馬紋）+ 停止線，旁邊有路邊平行格或停車場走道。"""
    rng = sc.rng
    zz = rng.uniform(-1.5, 3.5)
    W = rng.uniform(3.0, 4.0)
    zebra_t = rng.integers(2)
    if zebra_t == 0:          # 枕木紋
        bw = rng.uniform(0.4, 0.5)
        x = -8.0
        while x < 8.0:
            sc.line(x, zz, x, zz + W, lw=bw, rgb=(235, 235, 235))
            x += bw + rng.uniform(0.4, 0.6)
    else:                     # 斑馬紋：邊線 + 45° 斜紋
        blw = rng.uniform(0.1, 0.2)
        _hatch(sc, (-8.0, zz, 8.0, zz + W), rng.uniform(0.8, 1.0), math.radians(45 if rng.random() < 0.5 else 135),
               rng.uniform(0.35, 0.45), (235, 235, 235), border=True, blw=blw)
    zstop = zz - rng.uniform(1.0, 2.0)
    lane = rng.uniform(2.8, 3.4)
    x0 = rng.uniform(-1.8, -1.2)
    for k in range(-2, 1):
        x = x0 + k * lane
        if rng.random() < 0.5:
            z = rng.uniform(-14, -10)
            while z < zstop:
                sc.line(x, z, x, min(z + 4.0, zstop), lw=0.12, rgb=(235, 235, 235))
                z += 10.0
        else:
            sc.line(x, -12, x, zstop, lw=0.12, rgb=(235, 235, 235))
        sc.line(x, zz + W + 1.5, x, 12, lw=0.12, rgb=(235, 235, 235))
    sc.line(x0 - 2 * lane, zstop, x0, zstop, lw=rng.uniform(0.3, 0.45), rgb=(240, 240, 240))
    # 右側：路邊平行格，被穿越道截斷（穿越道前後留 0.5–1 m）
    xi = rng.uniform(1.4, 2.4)
    wd = rng.uniform(2.1, 2.5)
    ln = rng.uniform(5.8, 6.4)
    xo = xi + wd
    _sidewalk(sc, xo + rng.uniform(0.1, 0.3), 1)
    for zs_, ze_, sg in ((zz - rng.uniform(0.5, 1.0), -12.0, -1), (zz + W + rng.uniform(0.5, 1.0), 12.0, 1)):
        z = zs_
        while (z + sg * ln - ze_) * sg <= 0:
            za, zb = sorted((z, z + sg * ln))
            for (a, b, c, d) in ((xi, za, xo, za), (xi, zb, xo, zb), (xi, za, xi, zb), (xo, za, xo, zb)):
                sc.line(a, b, c, d)
            sc.slot([(xi, za), (xi, zb), (xo, zb), (xo, za)], 'l')
            if rng.random() < 0.3:
                sc.car(0.5 * (xi + xo), 0.5 * (za + zb) + rng.uniform(-0.4, 0.4), rng.uniform(-0.04, 0.04))
            z += sg * ln
    sc.tags.append('xwalk-' + ('sleeper' if zebra_t == 0 else 'zebra'))


def lay_double(sc):
    """雙線分隔的垂直格（線心距 0.25–0.45 m）；另一側是雙黃線 + 車道線（負樣本）。"""
    rng = sc.rng
    side = 1 if rng.random() < 0.5 else -1
    xe = side * rng.uniform(2.3, 3.4)
    wd = rng.uniform(2.4, 2.7)
    dp = rng.uniform(5.0, 5.5)
    gap = rng.uniform(0.25, 0.45)
    ent = rng.random() < 0.5
    back = rng.random() < 0.5
    z0 = rng.uniform(-9.5, -7.0)
    zs = [z0 + k * wd for k in range(int(17 / wd) + 1)]
    xb = xe + side * dp
    if ent:
        sc.line(xe, zs[0] - gap / 2, xe, zs[-1] + gap / 2)
    if back:
        sc.line(xb, zs[0] - gap / 2, xb, zs[-1] + gap / 2)
    for z in zs:
        for o in (-gap / 2, gap / 2):
            sc.line(xe, z + o, xb, z + o)
        if not ent:                      # 「U」型：每對分隔線在入口端相連
            sc.line(xe, z - gap / 2, xe, z + gap / 2)
    for k in range(len(zs) - 1):
        sc.slot([(xe, zs[k]), (xe, zs[k + 1]), (xb, zs[k + 1]), (xb, zs[k])], 'p')
        if rng.random() < 0.3:
            sc.car(xe + side * (dp / 2 + rng.uniform(-0.2, 0.4)), 0.5 * (zs[k] + zs[k + 1]) + rng.uniform(-0.15, 0.15),
                   math.pi / 2 + rng.uniform(-0.05, 0.05))
    # 對側：雙黃線 + 白色車道線（間距 2.6–3.4 m），止於停止線
    xd = -side * rng.uniform(1.6, 2.4)
    zst = rng.uniform(3.0, 6.0)
    for o in (-0.1, 0.1):
        sc.line(xd + o * side, -12, xd + o * side, zst, lw=0.12, rgb=YEL)
    xl = xd - side * rng.uniform(2.6, 3.4)
    sc.line(xl, -12, xl, zst, lw=0.12, rgb=(235, 235, 235))
    if rng.random() < 0.7:
        sc.line(min(xd, xl) - 1.0, zst, max(xd, xl) + 1.0, zst, lw=rng.uniform(0.3, 0.45), rgb=(240, 240, 240))
    sc.tags.append('double-' + ('E' if ent else '') + ('B' if back else ''))


def lay_curb(sc):
    """路邊（負樣本）：人行道、紅 / 黃線、路面邊線、機慢車道、車道線、停止線、機車停等區、機車待轉區。"""
    rng = sc.rng
    xc = rng.uniform(2.6, 5.0)
    _sidewalk(sc, xc, 1)
    cp = rng.integers(3)
    if cp < 2:                 # 路緣紅線 / 黃線（車道邊緣的實線，有缺口 = 車道出入口）
        rgb = (190, 45, 45) if cp == 0 else YEL
        zgap = rng.uniform(-6, 6)
        for (a, b) in ((-12, zgap - 1.5), (zgap + 1.5, 12)):
            sc.line(xc - 0.1, a, xc - 0.1, b, lw=rng.uniform(0.1, 0.15), rgb=rgb)
    xe = xc - rng.uniform(0.3, 1.0)
    zst = rng.uniform(1.0, 5.0)
    sc.line(xe, -12, xe, 12, lw=rng.uniform(0.1, 0.15), rgb=(235, 235, 235))    # 路面邊線
    xl = xe - rng.uniform(2.0, 3.0)                                               # 機慢車優先道
    xl2 = xl - rng.uniform(3.0, 3.4)
    for x in (xl, xl2):
        if rng.random() < 0.5:
            z = rng.uniform(-16, -12)
            while z < zst:
                sc.line(x, z, x, min(z + 4.0, zst), lw=0.12, rgb=(235, 235, 235))
                z += 10.0
        else:
            sc.line(x, -12, x, zst, lw=0.12, rgb=(235, 235, 235))
    sc.line(xl2 - 0.2, -12, xl2 - 0.2, zst, lw=0.12, rgb=YEL)
    sc.line(xl2 - 0.4, -12, xl2 - 0.4, zst, lw=0.12, rgb=YEL)
    slw = rng.uniform(0.3, 0.45)
    sc.line(xl2, zst, xe, zst, lw=slw, rgb=(240, 240, 240))                          # 汽車停止線
    if rng.random() < 0.7:                                                          # 機車停等區
        d = rng.uniform(3.0, 5.0)
        blw = rng.uniform(0.12, 0.4)
        sc.line(xl2, zst + d, xe, zst + d, lw=blw, rgb=(240, 240, 240))
        if rng.random() < 0.5:
            sc.line(xl2, zst, xl2, zst + d, lw=0.12, rgb=(240, 240, 240))
            sc.line(xe, zst, xe, zst + d, lw=0.12, rgb=(240, 240, 240))
        _scooter_sym(sc, 0.5 * (xl + xe), zst + d / 2, rng.uniform(1.2, 1.8), math.pi / 2)
    if rng.random() < 0.5:                                                          # 機車待轉區
        bx = xe - rng.uniform(0.3, 0.6)
        bz = zst + rng.uniform(6.0, 8.0)
        bd = rng.uniform(1.8, 2.5)
        bl = rng.uniform(3.0, 6.0)
        r = (bx - bl, bz, bx, bz + bd)
        for (a, b, c, e) in ((r[0], r[1], r[2], r[1]), (r[2], r[1], r[2], r[3]), (r[2], r[3], r[0], r[3]),
                             (r[0], r[3], r[0], r[1])):
            sc.line(a, b, c, e, lw=0.12, rgb=(240, 240, 240))
        _scooter_sym(sc, bx - bl / 2, bz + bd / 2, min(1.4, bl * 0.4), math.pi / 2)
    sc.tags.append('curb')


def lay_shadow(sc):
    """空地（負樣本）：強烈陰影、亮縫、輪胎痕、補土裂縫、伸縮縫、補丁。"""
    rng = sc.rng
    _shadow_fns(sc, rng.integers(2, 6))
    for _ in range(rng.integers(0, 4)):             # 輪胎痕 / 補土裂縫（暗線）
        a = rng.uniform(0, math.pi)
        x, z = rng.uniform(-5, 5), rng.uniform(-6, 6)
        L = rng.uniform(2, 8)
        sc.line(x - L / 2 * math.cos(a), z - L / 2 * math.sin(a), x + L / 2 * math.cos(a), z + L / 2 * math.sin(a),
                lw=rng.uniform(0.05, 0.25), rgb=(25, 25, 25), op=rng.uniform(0.3, 0.7))
    if rng.random() < 0.4:                          # 水泥伸縮縫網格
        p = rng.uniform(3.0, 5.0)
        for k in range(-4, 5):
            sc.line(k * p, -12, k * p, 12, lw=0.02, rgb=(40, 40, 40), op=0.8)
            sc.line(-12, k * p, 12, k * p, lw=0.02, rgb=(40, 40, 40), op=0.8)
    for _ in range(rng.integers(0, 3)):             # 補丁（亮 / 暗色塊）
        x, z = rng.uniform(-5, 5), rng.uniform(-6, 6)
        a, b = rng.uniform(0.5, 3), rng.uniform(0.5, 3)
        v = rng.uniform(60, 190)
        sc.areas.append(([sc.T(x, z), sc.T(x + a, z), sc.T(x + a, z + b), sc.T(x, z + b)], (v, v, v)))
    sc.tags.append('shadow')


def lay_scoot2(sc):
    """機車格變體（負樣本）：長框無分隔（8–12 m）、T 字分隔、背對背兩排、只有框加字。"""
    rng = sc.rng
    for side in ((1, -1) if rng.random() < 0.5 else (1,)):
        t = rng.integers(4)
        xi = side * rng.uniform(1.4, 3.0)
        dp = rng.uniform(1.8, 2.4)
        xo = xi + side * dp
        z0 = rng.uniform(-9, -4)
        L = rng.uniform(8.0, 12.0)
        if t == 0:                                   # 長框 + 字
            for (a, b, c, d) in ((xi, z0, xo, z0), (xi, z0 + L, xo, z0 + L), (xi, z0, xi, z0 + L), (xo, z0, xo, z0 + L)):
                sc.line(a, b, c, d)
            for k in range(2):
                _glyph(sc, 0.5 * (xi + xo), z0 + L * (0.35 + 0.3 * k), 0.9, -side * math.pi / 2)
        elif t == 1:                                 # T 字分隔（每 1 m）
            sc.line(xi, z0, xi, z0 + L)
            z = z0
            while z <= z0 + L:
                sc.line(xi, z, xi + side * rng.uniform(0.4, 0.7), z)
                z += rng.uniform(0.9, 1.1)
        elif t == 2:                                 # 背對背兩排，每排 2 m 深、1 m 寬
            xm = xo + side * dp
            for x in (xi, xo, xm):
                sc.line(x, z0, x, z0 + L)
            z = z0
            while z <= z0 + L:
                sc.line(xi, z, xm, z)
                z += rng.uniform(0.9, 1.1)
        else:                                        # 框 + 1 m 分隔，部分磨掉
            for x in (xi, xo):
                sc.line(x, z0, x, z0 + L)
            z = z0
            while z <= z0 + L:
                if rng.random() < 0.6:
                    sc.line(xi, z, xo, z)
                z += rng.uniform(0.9, 1.1)
            _scooter_sym(sc, 0.5 * (xi + xo), z0 + L / 2, 1.2, 0.0)
    sc.tags.append('scoot2')


def lay_stopper(sc):
    """垂直格 + 輪擋（黃色 / 水泥 / 黑黃條，一根 1.5–1.8 m 或兩塊 0.5 m），離底線 0.6–1.0 m。"""
    rng = sc.rng
    if rng.random() < 0.6:
        lay_perp_aisle(sc, 1 if rng.random() < 0.5 else -1)
    else:
        lay_perp_ahead(sc)
    for g in sc.slots:
        p = g['p']                               # 車身座標：p0,p1 入口，p2,p3 底
        bx, bz = 0.5 * (p[2][0] + p[3][0]), 0.5 * (p[2][1] + p[3][1])
        ex, ez = 0.5 * (p[0][0] + p[1][0]), 0.5 * (p[0][1] + p[1][1])
        D = math.hypot(ex - bx, ez - bz)
        dx, dz = (ex - bx) / D, (ez - bz) / D
        d = rng.uniform(0.6, 1.0)
        cxs, czs = bx + dx * d, bz + dz * d
        ux, uz = dz, -dx
        col = [YEL, (200, 200, 195), (230, 200, 40)][rng.integers(3)]
        lw = rng.uniform(0.12, 0.2)
        # sc.line 會再轉一次 yaw，所以先轉回版面座標
        inv = lambda x, z: _rot(x, z, sc.c, -sc.s)
        if rng.random() < 0.6:
            hl = rng.uniform(0.75, 0.9)
            a = inv(cxs - ux * hl, czs - uz * hl)
            b = inv(cxs + ux * hl, czs + uz * hl)
            sc.line(a[0], a[1], b[0], b[1], lw=lw, rgb=col)
        else:
            for o in (-0.55, 0.55):
                a = inv(cxs + ux * (o - 0.25), czs + uz * (o - 0.25))
                b = inv(cxs + ux * (o + 0.25), czs + uz * (o + 0.25))
                sc.line(a[0], a[1], b[0], b[1], lw=lw, rgb=col)
    sc.tags.append('stopper')


def lay_text(sc):
    """格內有編號 / 字 / 身障或充電格塗色 + 圖示。"""
    rng = sc.rng
    if rng.random() < 0.6:
        lay_perp_aisle(sc, 1 if rng.random() < 0.5 else -1)
    else:
        lay_perp_ahead(sc)
    inv = lambda x, z: _rot(x, z, sc.c, -sc.s)
    for g in sc.slots:
        p = g['p']
        ex, ez = 0.5 * (p[0][0] + p[1][0]), 0.5 * (p[0][1] + p[1][1])
        bx, bz = 0.5 * (p[2][0] + p[3][0]), 0.5 * (p[2][1] + p[3][1])
        ang = math.atan2(ex - bx, ez - bz) + math.atan2(sc.s, sc.c)   # 字頂朝入口（版面座標）
        t = rng.integers(4)
        if t == 0:                                   # 編號（兩三位數字）
            f = rng.uniform(0.15, 0.3)
            x, z = inv(ex + (bx - ex) * f, ez + (bz - ez) * f)
            n = rng.integers(2, 4)
            sz = rng.uniform(0.5, 0.8)
            ca, sa = math.cos(ang), -math.sin(ang)
            for k in range(n):
                o = (k - (n - 1) / 2) * sz * 0.8
                _glyph(sc, x + o * ca, z + o * sa, sz, ang, text=str(rng.integers(10)), lw=rng.uniform(0.08, 0.12))
        elif t == 1:                                 # 文字（卸貨、孕婦…）
            x, z = inv(ex + (bx - ex) * 0.5, ez + (bz - ez) * 0.5)
            ca, sa = math.cos(ang), -math.sin(ang)
            for k in range(2):
                o = (k - 0.5) * 0.9
                _glyph(sc, x + o * ca, z + o * sa, 0.8, ang)
        elif t == 2:                                 # 身障 / 充電：塗色 + 白色圖示
            col = (40, 70, 160) if rng.random() < 0.5 else (50, 140, 70)
            q = [inv(*v) for v in p]
            gx = sum(v[0] for v in q) / 4
            gz = sum(v[1] for v in q) / 4
            q = [(gx + (v[0] - gx) * 0.93, gz + (v[1] - gz) * 0.93) for v in q]
            sc.areas.append(([sc.T(*v) for v in q], col))
            x, z = inv(ex + (bx - ex) * 0.5, ez + (bz - ez) * 0.5)
            _arc(sc, x, z, 0.4, 0, 2 * math.pi, 0.1, (240, 240, 240), n=10)
            _glyph(sc, x, z + 0.5, 0.5, ang)
    sc.tags.append('text')


def lay_hatch(sc):
    """網狀線（黃色交叉斜線框）與白色斜紋禁停區（夾在格子之間）。"""
    rng = sc.rng
    side = 1 if rng.random() < 0.5 else -1
    lay_perp_aisle(sc, side)
    # 把其中一格改成斜紋禁停區：刪掉真值、在格內畫斜線
    if len(sc.slots) > 3:
        k = rng.integers(1, len(sc.slots) - 1)
        g = sc.slots.pop(k)
        inv = lambda x, z: _rot(x, z, sc.c, -sc.s)
        q = [inv(*v) for v in g['p']]
        xs = [v[0] for v in q]
        zs = [v[1] for v in q]
        _hatch(sc, (min(xs), min(zs), max(xs), max(zs)), rng.uniform(0.5, 0.9), math.radians(rng.uniform(30, 60)),
               rng.uniform(0.1, 0.15), sc.col, border=False)
    # 對側：黃色網狀線框（2 方向斜線）
    x0 = -side * rng.uniform(1.5, 2.5)
    x1 = x0 - side * rng.uniform(3.0, 6.0)
    z0 = rng.uniform(-6, 0)
    r = (min(x0, x1), z0, max(x0, x1), z0 + rng.uniform(4, 8))
    p = rng.uniform(1.0, 2.6)
    _hatch(sc, r, p, math.radians(45), 0.12, YEL, border=True)
    _hatch(sc, r, p, math.radians(135), 0.12, YEL, border=False)
    sc.tags.append('hatch')


def lay_garage(sc):
    """地下室：環氧樹脂地坪、較暗、柱子擋住入口角、燈管倒影（亮條）、格內編號。"""
    rng = sc.rng
    sc.base_rgb = (rng.uniform(70, 100), rng.uniform(110, 140), rng.uniform(90, 110)) if rng.random() < 0.6 else \
        (rng.uniform(110, 140),) * 3
    sc.dim = rng.uniform(0.55, 0.85)
    if rng.random() < 0.5:
        sc.col = YEL
    side = 1 if rng.random() < 0.5 else -1
    lay_perp_aisle(sc, side)
    if rng.random() < 0.5:
        lay_perp_aisle(sc, -side)
    # 柱子：每 3 格一根，站在入口角
    for k, g in enumerate(sc.slots):
        if k % 3 == 0 and rng.random() < 0.8:
            inv = _rot(g['p'][0][0], g['p'][0][1], sc.c, -sc.s)
            _obj(sc, inv[0], inv[1], rng.uniform(0.6, 0.9), rng.uniform(0.6, 0.9), 0.0, (150, 150, 150),
                 rng.uniform(0.5, 1.5))
    # 燈管倒影：沿走道方向一排排的亮條
    for kx in (-1.0, 1.0):
        z = rng.uniform(-10, -7)
        while z < 10:
            x = kx * rng.uniform(0.5, 1.6)
            sc.line(x, z, x, z + 1.2, lw=rng.uniform(0.12, 0.25), rgb=(245, 245, 240), op=rng.uniform(0.5, 0.9))
            z += rng.uniform(2.4, 3.2)
    sc.tags.append('garage')


def lay_pavers(sc):
    """植草磚 / 連鎖磚（負樣本，也是最耗時的輸入）：整片 0.3–0.6 m 間距的亮色格柵，草地或磚縫。"""
    rng = sc.rng
    if rng.random() < 0.6:
        sc.base_rgb = (rng.uniform(50, 80), rng.uniform(80, 115), rng.uniform(40, 65))     # 草
        rib = (rng.uniform(165, 205),) * 3
    else:
        sc.base_rgb = (rng.uniform(60, 90),) * 3                                           # 磚縫
        rib = (rng.uniform(150, 190), rng.uniform(120, 150), rng.uniform(100, 130))
    p1, p2 = rng.uniform(0.3, 0.6), rng.uniform(0.3, 0.6)
    lw = rng.uniform(0.08, 0.14)
    x0, x1 = (-9, 9) if rng.random() < 0.6 else (rng.uniform(0.8, 2.5), 9)
    k = x0
    while k <= x1:
        sc.line(k, -9, k, 9, lw=lw, rgb=rib)
        k += p1
    k = -9
    while k <= 9:
        sc.line(x0, k, x1, k, lw=lw, rgb=rib)
        k += p2
    sc.tags.append('pavers')


def make_adv_scene(rng, kind, w=300, h=350, mpp=0.04, cx=150.0, cy=175.0):
    sc = Scene(rng, w, h, mpp, cx, cy, 'own')
    _style_line(sc)
    base_pick = lambda opts: opts[rng.integers(len(opts))]
    if kind == 'blur':
        sc.blur_mul = rng.uniform(2.0, 3.0)
        sc.base_blur = rng.uniform(0.6, 1.2)
        b = base_pick(['aisle', 'parallel', 'angled', 'ahead', 'egoperp'])
    elif kind == 'seam':
        sc.seam_gain = 0.15
        sc.seam_shift = [(rng.uniform(-0.1, 0.1), rng.uniform(-0.1, 0.1), math.radians(rng.uniform(-1.2, 1.2)))
                         for _ in range(4)]
        b = base_pick(['ahead', 'aisle', 'egoperp', 'angled'])
    elif kind == 'occl':
        sc.occ_p = 0.75
        sc.crook = True
        sc.yolo_miss = 0.3
        b = base_pick(['aisle', 'ahead', 'angled'])
    elif kind == 'rot':
        yaw = math.radians(rng.uniform(0, 90)) * (1 if rng.random() < 0.5 else -1)
        sc.c, sc.s = math.cos(yaw), math.sin(yaw)
        b = base_pick(['aisle', 'parallel', 'angled', 'ahead'])
    else:
        b = None
    if b == 'aisle':
        lay_perp_aisle(sc, 1 if rng.random() < 0.5 else -1)
    elif b == 'parallel':
        lay_parallel(sc, 1 if rng.random() < 0.5 else -1)
    elif b == 'angled':
        lay_angled(sc, 1 if rng.random() < 0.5 else -1)
    elif b == 'ahead':
        lay_perp_ahead(sc)
    elif b == 'egoperp':
        lay_ego_perp(sc)
    elif kind == 'xwalk':
        lay_xwalk(sc)
    elif kind == 'double':
        lay_double(sc)
    elif kind == 'curb':
        lay_curb(sc)
    elif kind == 'shadow':
        lay_shadow(sc)
    elif kind == 'scoot2':
        lay_scoot2(sc)
    elif kind == 'stopper':
        lay_stopper(sc)
    elif kind == 'text':
        lay_text(sc)
    elif kind == 'hatch':
        lay_hatch(sc)
    elif kind == 'garage':
        lay_garage(sc)
    elif kind == 'pavers':
        lay_pavers(sc)
    if kind == 'occl':                               # 沒有車輛框的遮擋物（行人、推車）壓在格線上
        segs = _seg_points(sc)
        for _ in range(rng.integers(2, 6)):
            if not segs:
                break
            a, bb = segs[rng.integers(len(segs))]
            f = rng.uniform(0.1, 0.9)
            x, z = _rot(a[0] + (bb[0] - a[0]) * f, a[1] + (bb[1] - a[1]) * f, sc.c, -sc.s)
            _obj(sc, x, z, rng.uniform(0.4, 0.8), rng.uniform(0.4, 0.9), rng.uniform(0, 3),
                 tuple(rng.uniform(30, 200, 3)), rng.uniform(0.3, 1.2))
    if kind in ('shadow', 'curb'):
        _shadow_fns(sc, rng.integers(0, 3))
    if b is not None:
        sc.tags.insert(0, kind)
    return sc


# ---------------------------------------------------------------- 繪製
def _smooth_noise(rng, h, w, cells):
    from scipy.ndimage import zoom
    gh, gw = max(2, int(h / cells)), max(2, int(w / cells))
    g = rng.normal(size=(gh + 3, gw + 3))
    z = zoom(g, (h / gh, w / gw), order=3)
    return z[:h, :w]


def _seg_dist(X, Z, x0, z0, x1, z1):
    vx, vz = x1 - x0, z1 - z0
    L2 = vx * vx + vz * vz
    t = np.clip(((X - x0) * vx + (Z - z0) * vz) / max(L2, 1e-12), 0, 1)
    return np.hypot(X - (x0 + t * vx), Z - (z0 + t * vz))


def _poly_cov(X, Z, poly):
    inside = np.ones(np.shape(X), bool)
    n = len(poly)
    sa = poly_signed_area(poly)
    for k in range(n):
        a, b = poly[k], poly[(k + 1) % n]
        cr = (b[0] - a[0]) * (Z - a[1]) - (b[1] - a[1]) * (X - a[0])
        inside &= (cr >= 0) if sa > 0 else (cr <= 0)
    return inside


def _ccw(p):
    return list(p) if poly_signed_area(p) > 0 else list(p)[::-1]


def _convex_hull(pts):
    pts = sorted(set(pts))
    if len(pts) <= 2:
        return pts

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
    lo, up = [], []
    for p in pts:
        while len(lo) >= 2 and cross(lo[-2], lo[-1], p) <= 0:
            lo.pop()
        lo.append(p)
    for p in reversed(pts):
        while len(up) >= 2 and cross(up[-2], up[-1], p) <= 0:
            up.pop()
        up.append(p)
    return lo[:-1] + up[:-1]


def _grow(poly, d):
    gx = sum(p[0] for p in poly) / len(poly)
    gz = sum(p[1] for p in poly) / len(poly)
    out = []
    for p in poly:
        vx, vz = p[0] - gx, p[1] - gz
        r = math.hypot(vx, vz) + 1e-9
        out.append((p[0] + vx / r * d * 1.41, p[1] + vz / r * d * 1.41))
    return out


def _car_hull(pts, sm):
    gx = sum(p[0] for p in pts) / 4
    gz = sum(p[1] for p in pts) / 4
    r = math.hypot(gx, gz) + 1e-6
    ext = list(pts) + [(p[0] + gx / r * sm, p[1] + gz / r * sm) for p in pts]
    return _convex_hull(ext)


def render(sc):
    from scipy.ndimage import gaussian_filter
    rng = sc.rng
    h, w, mpp, cx, cy = sc.h, sc.w, sc.mpp, sc.cx, sc.cy
    SS = 3
    offs = (np.arange(SS) + 0.5) / SS - 0.5
    X1 = (np.arange(w) + 0.5 - cx) * mpp
    Z1 = (cy - (np.arange(h) + 0.5)) * mpp
    X, Z = np.meshgrid(X1, Z1)
    gt = rng.integers(4)
    if gt == 0:
        base = np.array([rng.uniform(70, 110)] * 3)                 # 柏油
    elif gt == 1:
        base = np.array([rng.uniform(130, 165)] * 3)                # 水泥
    elif gt == 2:
        base = np.array([rng.uniform(80, 110), rng.uniform(115, 140), rng.uniform(95, 115)])  # 地下室環氧樹脂
    else:
        base = np.array([rng.uniform(95, 125)] * 3) * np.array([1.0, 0.98, 0.94])
    if sc.base_rgb is not None:
        base = np.array(sc.base_rgb, float)
    img = np.zeros((h, w, 3)) + base
    lf = _smooth_noise(rng, h, w, 40) * rng.uniform(5, 14) + _smooth_noise(rng, h, w, 8) * rng.uniform(2, 6)
    img += lf[:, :, None]
    img += rng.normal(0, rng.uniform(3, 8), (h, w))[:, :, None]
    for _ in range(rng.integers(0, 5)):   # 汙漬
        ex, ez = rng.uniform(-6, 6), rng.uniform(-7, 7)
        r = rng.uniform(0.3, 1.5)
        m = np.exp(-((X - ex) ** 2 + (Z - ez) ** 2) / (r * r))
        img *= (1 - rng.uniform(0.1, 0.35) * m)[:, :, None]
    for poly, rgb in sc.areas:
        cov = _poly_cov(X, Z, _ccw(poly))
        img[cov] = np.array(rgb) + rng.normal(0, 6, (int(cov.sum()), 3))
    wear = _smooth_noise(rng, h, w, 6)
    wear = (wear - wear.min()) / (np.ptp(wear) + 1e-9)
    wear_k = rng.uniform(0.55, 0.9)
    for (x0, z0, x1, z1, lw, rgb, op) in sc.strokes:
        pad = lw + 2 * mpp
        i0 = int(max(0, math.floor((min(x0, x1) - pad) / mpp + cx)))
        i1 = int(min(w, math.ceil((max(x0, x1) + pad) / mpp + cx) + 1))
        j0 = int(max(0, math.floor(cy - (max(z0, z1) + pad) / mpp)))
        j1 = int(min(h, math.ceil(cy - (min(z0, z1) - pad) / mpp) + 1))
        if i1 <= i0 or j1 <= j0:
            continue
        Xs, Zs = X[j0:j1, i0:i1], Z[j0:j1, i0:i1]
        cov = np.zeros(Xs.shape)
        for oy in offs:
            for ox in offs:
                cov += _seg_dist(Xs + ox * mpp, Zs - oy * mpp, x0, z0, x1, z1) <= lw / 2
        cov /= SS * SS
        alpha = cov * op
        if sc.worn:
            alpha = alpha * np.clip((wear[j0:j1, i0:i1] - 0.2) / 0.2, 0, 1) * wear_k
        sub = img[j0:j1, i0:i1]
        img[j0:j1, i0:i1] = sub * (1 - alpha[:, :, None]) + np.array(rgb)[None, None, :] * alpha[:, :, None]
    for _ in range(rng.integers(0, 3)):   # 建物 / 樹影
        if rng.random() < 0.5:
            ang = rng.uniform(0, 2 * math.pi)
            d = X * math.cos(ang) + Z * math.sin(ang) - rng.uniform(-5, 5)
            sh = 1 / (1 + np.exp(-np.clip(d / 0.15, -50, 50)))
        else:
            sh = gaussian_filter((_smooth_noise(rng, h, w, 25) > 0.6).astype(float), 2)
        img *= (1 - rng.uniform(0.3, 0.55) * sh)[:, :, None]
    for f in sc.shade:
        img *= (1 - f(X, Z))[:, :, None]
    for pts, rgb, sm in sc.objs:          # 沒有車輛框的立體物
        cov = _poly_cov(X, Z, _ccw(_car_hull(pts, sm)))
        img[cov] = np.array(rgb, float) * 0.85 + rng.normal(0, 8, (int(cov.sum()), 3))
        cov2 = _poly_cov(X, Z, _ccw(pts))
        img[cov2] = np.array(rgb, float) + rng.normal(0, 5, (int(cov2.sum()), 3))
    for pts, rgb, sm in sc.cars:          # 車輛（含往外拖曳的立體變形）
        hull = _car_hull(pts, sm)
        halo = _poly_cov(X, Z, _ccw(_grow(hull, 0.15)))
        img[halo] *= 0.6
        col = np.array(rgb, float)
        cov = _poly_cov(X, Z, _ccw(hull))
        img[cov] = col * 0.85 + rng.normal(0, 10, (int(cov.sum()), 3))
        cov2 = _poly_cov(X, Z, _ccw(pts))
        img[cov2] = col + rng.normal(0, 6, (int(cov2.sum()), 3))
        p0, p1, p3 = pts[0], pts[1], pts[3]

        def at(fa, fb):
            return (p0[0] + fa * (p1[0] - p0[0]) + fb * (p3[0] - p0[0]),
                    p0[1] + fa * (p1[1] - p0[1]) + fb * (p3[1] - p0[1]))
        for (b0, b1, a0) in ((0.55, 0.72, 0.08), (0.18, 0.30, 0.10)):   # 前後擋風玻璃
            win = _poly_cov(X, Z, _ccw([at(a0, b0), at(1 - a0, b0), at(1 - a0, b1), at(a0, b1)]))
            img[win] = col * 0.25 + 20 + rng.normal(0, 5, (int(win.sum()), 3))
    img *= sc.dim
    if sc.style == 'own':
        ax = np.abs(X) - EGO_HX              # 接縫：各鏡頭亮度 / 白平衡不同
        reg = np.where((Z >= 0) & (Z - EGO_HZ >= ax), 0,
                       np.where((Z < 0) & (-Z - EGO_HZ >= ax), 1, np.where(X < 0, 2, 3)))
        if sc.seam_shift is not None:        # 各鏡頭的校正誤差：地面投影平移 + 繞鏡頭旋轉
            from scipy.ndimage import map_coordinates
            cams = ((0.0, 2.35), (0.0, -2.4), (-1.0, 0.85), (1.0, 0.85))
            out_img = img.copy()
            for r, ((dx, dz, rot), (px_, pz_)) in enumerate(zip(sc.seam_shift, cams)):
                m = reg == r
                cr, sr = math.cos(rot), math.sin(rot)
                Xs = px_ + (X[m] - px_) * cr - (Z[m] - pz_) * sr + dx
                Zs = pz_ + (X[m] - px_) * sr + (Z[m] - pz_) * cr + dz
                ii = Xs / mpp + cx - 0.5
                jj = cy - Zs / mpp - 0.5
                for ch in range(3):
                    out_img[:, :, ch][m] = map_coordinates(img[:, :, ch], [jj, ii], order=1, mode='nearest')
            img = out_img
        gains = rng.uniform(0.8, 1.2, 4)[:, None] * rng.uniform(0.95, 1.05, (4, 3))
        if sc.seam_gain:
            g0 = float(np.mean(gains))
            gains = np.array([1.0, 1.0, 1.0 + sc.seam_gain, 1.0 - sc.seam_gain])[:, None] * np.ones((4, 3)) * g0
        img *= gains[reg]
        if sc.base_blur > 0:
            img = np.stack([gaussian_filter(img[:, :, c], sc.base_blur) for c in range(3)], 2)
        dr = np.hypot(np.maximum(np.abs(X) - EGO_HX, 0), np.maximum(np.abs(Z) - EGO_HZ, 0))
        k = rng.uniform(0.7, 1.5) * sc.blur_mul  # 越遠越糊
        b1 = np.stack([gaussian_filter(img[:, :, c], 0.8 * k) for c in range(3)], 2)
        b2 = np.stack([gaussian_filter(img[:, :, c], 1.6 * k) for c in range(3)], 2)
        w1 = np.clip((dr - 2.0) / 2.0, 0, 1)
        w2 = np.clip((dr - 4.0) / 2.0, 0, 1)
        img = img * (1 - w1)[:, :, None] + b1 * (w1 - w2)[:, :, None] + b2 * w2[:, :, None]
        Rx, Rz = rng.uniform(5.3, 7.5), rng.uniform(6.2, 8.5)   # 無覆蓋黑角 + 自車黑框
        black = (np.abs(X) / Rx) ** 2.5 + (np.abs(Z) / Rz) ** 2.5 > 1
        black |= (np.abs(X) <= EGO_HX + 0.05) & (np.abs(Z) <= EGO_HZ + 0.05)
        img[black] = rng.uniform(0, 6)
        sc.black = black
    else:
        img = np.stack([gaussian_filter(img[:, :, c], 0.5) for c in range(3)], 2)
        icon = (np.abs(X) <= 0.95) & (np.abs(Z) <= 2.45)
        img[icon] = 235
        img[icon & (np.abs(Z) < 0.8) & (np.abs(X) < 0.7)] = 90
        sc.black = np.zeros((h, w), bool)
    img += rng.normal(0, rng.uniform(1.5, 3.5), img.shape)
    out = np.clip(np.round(img), 0, 255).astype(np.uint8)
    if rng.random() < 0.5:
        from PIL import Image
        import io
        buf = io.BytesIO()
        Image.fromarray(out).save(buf, 'JPEG', quality=int(rng.uniform(70, 92)))
        out = np.array(Image.open(io.BytesIO(buf.getvalue())).convert('RGB'))
    vehicles = []                          # 模擬 YOLO 車輛框：有誤差、偶爾漏掉
    for pts, rgb, sm in sc.cars:
        hull = _car_hull(pts, sm)
        xs = [p[0] for p in hull]
        zs = [p[1] for p in hull]
        if rng.random() < sc.yolo_miss:
            continue
        j = lambda: rng.uniform(-0.25, 0.15)
        vehicles.append([min(xs) - j(), min(zs) - j(), max(xs) + j(), max(zs) + j()])
    ego = [(-EGO_HX, -EGO_HZ), (EGO_HX, -EGO_HZ), (EGO_HX, EGO_HZ), (-EGO_HX, EGO_HZ)]
    for s in sc.slots:                     # 真值：被車（或自車）佔 > 30 %
        a = poly_area(s['p'])
        for pts in [c[0] for c in sc.cars] + [ego]:
            if poly_area(clip_convex(s['p'], _ccw(pts))) > OCC_FRAC * a:
                s['occupied'] = True
    sc.vehicles = vehicles
    return out


# ---------------------------------------------------------------- 評估
def _visible_pt(sc, x, z, margin=0.15):
    i = x / sc.mpp + sc.cx
    j = sc.cy - z / sc.mpp
    m = margin / sc.mpp
    if i < m or j < m or i > sc.w - m or j > sc.h - m:
        return False
    if sc.black[min(sc.h - 1, int(j)), min(sc.w - 1, int(i))]:
        return False
    return not (abs(x) <= EGO_HX + 0.05 and abs(z) <= EGO_HZ + 0.05)


def _vis_frac(sc, poly):
    xs = [p[0] for p in poly]
    zs = [p[1] for p in poly]
    n = tot = 0
    for x in np.linspace(min(xs), max(xs), 12):
        for z in np.linspace(min(zs), max(zs), 12):
            if _poly_cov(x, z, poly):
                tot += 1
                n += _visible_pt(sc, x, z, margin=0.0)
    return n / max(tot, 1)


def evaluate(sc, dets):
    """真值 / 偵測配對（IoU ≥ 0.3）。看不到入口或可見面積 < 25 % 的真值不算進 recall，配到它的偵測也不算 FP。"""
    for g in sc.slots:
        p = g['p']
        g['detectable'] = _visible_pt(sc, *p[0]) and _visible_pt(sc, *p[1]) and _vis_frac(sc, p) >= 0.25
        g['cvis'] = [_visible_pt(sc, *q) for q in p]
    res = {'tp': [], 'fp': [], 'fn': [], 'ign': []}
    used = set()
    for d in dets:
        dp = [(d['p'][2 * k], d['p'][2 * k + 1]) for k in range(4)]
        best, bi = 0.0, -1
        for gi, g in enumerate(sc.slots):
            iou = poly_iou(dp, g['p'])
            if iou > best:
                best, bi = iou, gi
        if best < 0.3:
            res['fp'].append(d)
            continue
        g = sc.slots[bi]
        if not g['detectable'] or bi in used:
            res['ign'].append(d)
            continue
        used.add(bi)
        errs = []
        for k, q in enumerate(g['p']):
            if g['cvis'][k]:
                errs.append((k < 2, min(math.hypot(q[0] - r[0], q[1] - r[1]) for r in dp)))
        gm = ((g['p'][0][0] + g['p'][1][0]) / 2, (g['p'][0][1] + g['p'][1][1]) / 2)
        dm = ((dp[0][0] + dp[1][0]) / 2, (dp[0][1] + dp[1][1]) / 2)
        res['tp'].append({'gt': g, 'det': d, 'iou': best, 'errs': errs,
                          'ent_ok': math.hypot(gm[0] - dm[0], gm[1] - dm[1]) < 0.6,
                          'kind_ok': d['kind'] == g['kind'], 'occ_ok': d['occupied'] == g['occupied']})
    for gi, g in enumerate(sc.slots):
        if g['detectable'] and gi not in used:
            res['fn'].append(g)
    return res


def overlay(rgb, sc, dets, path, det=None):
    """疊圖：綠 = 偵測（粗線 = 入口，紅色 = 被佔），藍 = 真值，黃 = 偵測到的線段。"""
    from PIL import Image, ImageDraw
    S = 2
    im = Image.fromarray(rgb).resize((rgb.shape[1] * S, rgb.shape[0] * S), Image.NEAREST)
    dr = ImageDraw.Draw(im)
    mpp, cx, cy = sc.mpp, sc.cx, sc.cy

    def px(x, z):
        return ((x / mpp + cx) * S, (cy - z / mpp) * S)
    if det is not None and 'lines' in det.dbg:
        for L in det.dbg['lines']:
            for (t0, t1) in L.pieces:
                dr.line([px(L.cx + t0 * L.ux, L.cz + t0 * L.uz), px(L.cx + t1 * L.ux, L.cz + t1 * L.uz)],
                        fill=(255, 220, 0), width=1)
    if sc is not None and getattr(sc, 'slots', None):
        for g in sc.slots:
            pts = [px(*q) for q in g['p']]
            dr.line(pts + [pts[0]], fill=(60, 120, 255) if g.get('detectable', True) else (60, 60, 140), width=1)
    for d in dets:
        q = [(d['p'][2 * k], d['p'][2 * k + 1]) for k in range(4)]
        pts = [px(*v) for v in q]
        col = (255, 60, 60) if d['occupied'] else (40, 255, 80)
        dr.line(pts + [pts[0]], fill=col, width=2)
        dr.line([pts[0], pts[1]], fill=col, width=5)
        m = px(sum(v[0] for v in q) / 4, sum(v[1] for v in q) / 4)
        dr.text((m[0] - 4, m[1] - 6), d['kind'], fill=col)
    im.save(path)


# ================================================================ 真實照片（車機螢幕翻拍 → 俯視圖）
IMG_DIR = os.environ.get('PARKING_IMAGES', '/private/tmp/claude-501/-Users-vito-development/'
                         '7b2a2a6f-c0ce-40ca-9bfc-33d8d3ea9c70/images')
# 手動標的 BEV 面板四角（照片像素，左上、右上、右下、左下），面板校正成 600x600 後量到的車身框
REAL = {
    '8': {'corners': [(462, 410), (1005, 405), (1015, 890), (505, 905)], 'car': (248, 158, 355, 455),
          # 原廠倒車輔助黃線（車尾後方）當排除區；左邊那台白車當車輛框
          'exclude': [[-1.6, -6.0, 1.6, -2.3]], 'vehicles_px': [(0, 160, 215, 590)]},
    '13': {'corners': [(288, 405), (862, 392), (870, 870), (345, 918)], 'car': (242, 148, 350, 448),
           'exclude': [], 'vehicles_px': [(150, 470, 450, 600)]},
}


def _homog(src, dst):
    A = []
    for (x, y), (u, v) in zip(src, dst):
        A.append([x, y, 1, 0, 0, 0, -u * x, -u * y, -u])
        A.append([0, 0, 0, x, y, 1, -v * x, -v * y, -v])
    _, _, Vt = np.linalg.svd(np.array(A, float))
    return Vt[-1].reshape(3, 3) / Vt[-1, -1]


def real_bev(name, w=300, h=350, mpp=0.04, cx=150.0, cy=175.0):
    """照片 → 公制俯視圖：面板 600x600 的 homography，再用車身框（4.84 x 1.85 m）定兩軸比例。"""
    from PIL import Image
    cfg = REAL[name]
    im = np.array(Image.open(os.path.join(IMG_DIR, name + '.jpg')).convert('RGB')).astype(float)
    Hm = _homog([(0, 0), (600, 0), (600, 600), (0, 600)], cfg['corners'])
    x0, y0, x1, y1 = cfg['car']
    sx = 1.85 / (x1 - x0)
    sy = 4.84 / (y1 - y0)
    pcx, pcy = 0.5 * (x0 + x1), 0.5 * (y0 + y1)
    X = (np.arange(w) + 0.5 - cx) * mpp
    Z = (cy - (np.arange(h) + 0.5)) * mpp
    XX, ZZ = np.meshgrid(X, Z)
    u = pcx + XX / sx
    v = pcy - ZZ / sy
    inside = (u >= 0) & (u < 600) & (v >= 0) & (v < 600)
    P = np.stack([u, v, np.ones_like(u)], -1) @ Hm.T
    pu, pv = P[..., 0] / P[..., 2], P[..., 1] / P[..., 2]
    u0 = np.clip(np.floor(pu).astype(int), 0, im.shape[1] - 2)
    v0 = np.clip(np.floor(pv).astype(int), 0, im.shape[0] - 2)
    fu, fv = (pu - u0)[..., None], (pv - v0)[..., None]
    o = (im[v0, u0] * (1 - fu) * (1 - fv) + im[v0, u0 + 1] * fu * (1 - fv) +
         im[v0 + 1, u0] * (1 - fu) * fv + im[v0 + 1, u0 + 1] * fu * fv)
    o[~inside] = 0
    rgb = np.clip(np.round(o), 0, 255).astype(np.uint8)
    veh = []
    for (a0, b0, a1, b1) in cfg['vehicles_px']:
        veh.append([(a0 - pcx) * sx, (pcy - b1) * sy, (a1 - pcx) * sx, (pcy - b0) * sy])
    return rgb, [EGO_EXCL] + cfg['exclude'], veh


class _RealScene:
    def __init__(self, w=300, h=350, mpp=0.04, cx=150.0, cy=175.0):
        self.w, self.h, self.mpp, self.cx, self.cy = w, h, mpp, cx, cy
        self.slots = []


def cmd_real():
    import time
    os.makedirs(SCRATCH, exist_ok=True)
    if not os.path.exists(os.path.join(IMG_DIR, '13.jpg')):
        print('找不到翻拍照（PARKING_IMAGES）：', IMG_DIR)
        return
    for name in REAL:
        rgb, excl, veh = real_bev(name)
        det = Detector()
        t0 = time.time()
        dets = det.detect(rgb, 0.04, 150.0, 150.0 + 25.0, excl, veh)
        dt = time.time() - t0
        path = os.path.join(SCRATCH, f'real_{name}.png')
        overlay(rgb, _RealScene(), dets, path, det)
        print(path, f'{len(det.lines)} lines, {len(dets)} slots ({dt:.2f}s python)')
        for d in dets:
            print('   ', d['kind'], 'occ' if d['occupied'] else 'free', f"conf={d['conf']:.2f}",
                  [round(v, 2) for v in d['p']])
    rgb, excl, veh, slots = composite13()
    det = Detector()
    dets = det.detect(rgb, 0.04, 150.0, 175.0, excl, veh)
    sc = _RealScene()
    sc.slots = slots
    path = os.path.join(SCRATCH, 'real_13_painted.png')
    overlay(rgb, sc, dets, path, det)
    print(path, f'{len(dets)} slots (painted row of 3 perpendicular boxes on the real 13 background)')
    for d in dets:
        print('   ', d['kind'], 'occ' if d['occupied'] else 'free', f"conf={d['conf']:.2f}", [round(v, 2) for v in d['p']])


def composite13():
    """真實照片 13 的俯視圖（真實地面紋理、接縫、模糊）上畫一排垂直格（白線 0.12 m、略為磨損）。"""
    rgb, excl, veh = real_bev('13')
    img = rgb.astype(float)
    mpp, cx, cy = 0.04, 150.0, 175.0
    X1 = (np.arange(300) + 0.5 - cx) * mpp
    Z1 = (cy - (np.arange(350) + 0.5)) * mpp
    X, Z = np.meshgrid(X1, Z1)
    xe, xb = -2.4, -7.9
    zs = [-3.6, -1.1, 1.4, 3.9]
    strokes = [(xe, zs[0], xe, zs[-1])] + [(xe, z, xb, z) for z in zs]
    rng = np.random.default_rng(13)
    wear = _smooth_noise(rng, 350, 300, 8)
    wear = (wear - wear.min()) / (np.ptp(wear) + 1e-9)
    for (x0, z0, x1, z1) in strokes:
        cov = np.zeros(X.shape)
        for oy in (-1 / 3, 0, 1 / 3):
            for ox in (-1 / 3, 0, 1 / 3):
                cov += _seg_dist(X + ox * mpp, Z - oy * mpp, x0, z0, x1, z1) <= 0.06
        a = (cov / 9) * np.clip((wear - 0.1) / 0.2, 0, 1) * 0.9
        img = img * (1 - a[:, :, None]) + np.array([245.0, 245.0, 240.0]) * a[:, :, None]
    valid = rgb.sum(2) >= 24
    img[~valid] = 0
    out = np.clip(np.round(img), 0, 255).astype(np.uint8)
    slots = []
    for k in range(3):
        p = [(xe, zs[k]), (xe, zs[k + 1]), (xb, zs[k + 1]), (xb, zs[k])]
        slots.append({'p': _ccw_first2(p), 'kind': 'p', 'occupied': False})
    return out, excl, veh, slots


def _ccw_first2(p):
    """保持入口兩點在前的逆時針順序。"""
    return p if poly_signed_area(p) > 0 else [p[1], p[0], p[3], p[2]]


# ---------------------------------------------------------------- Kotlin 測試 fixture
FIXTURES = [
    # (名稱, 場景類型, 種子序號, 變體)；種子與 stats 相同：1000 * 類型序號 + s（256 變體再加 500000）
    ('aisle_box_t', 'aisle', 5, '300'),
    ('aisle_sep', 'aisle', 11, '300'),
    ('aisle_u', 'aisle', 4, '300'),
    ('egoperp', 'egoperp', 0, '300'),
    ('parallel', 'parallel', 1, '300'),
    ('egopar', 'egopar', 2, '300'),
    ('angled', 'angled', 7, '300'),
    ('ahead', 'ahead', 5, '300'),
    ('road', 'road', 0, '300'),
    ('scooter', 'scooter', 0, '300'),
    ('v256_egoperp_own', 'egoperp', 3, '256'),
    ('v256_egoperp_stock', 'egoperp', 8, '256'),
]


ADV_FIXTURES = [
    # (名稱, 對抗場景類型, 序號)；種子 = adv_seed(類型, 序號)。neg = 不該有任何格子
    ('adv_scoot2_t', 'scoot2', 6),       # 機車格 T 字分隔（舊版 3 格誤報）
    ('adv_scoot2_box', 'scoot2', 19),    # 機車格框 + 部分分隔磨掉（舊版 3 格誤報）
    ('adv_xwalk', 'xwalk', 2),           # 車道線止於停止線 + 行人穿越道（舊版誤報垂直格）
    ('adv_curb', 'curb', 8),             # 機車停等區（舊版誤報垂直格）
    ('adv_pavers', 'pavers', 1),         # 植草磚格柵（舊版在自車周圍誤報一格；整片紋理，提早結束）
    ('adv_pavers_part', 'pavers', 2),    # 半邊植草磚（不會提早結束，最耗時的輸入）
    ('adv_shadow', 'shadow', 19),        # 空地 + 陰影亮縫（舊版誤報斜角格）
    ('adv_hatch', 'hatch', 14),          # 網狀線 + 斜紋禁停區（舊版誤報斜角格）
    ('adv_text', 'text', 1),             # 格內編號 / 文字
    ('adv_garage', 'garage', 10),        # 地下室、柱子擋住入口角（舊版鋸齒斜格、類型錯）
    ('adv_rot', 'rot', 9),               # 車頭與走道夾角大
    ('adv_seam', 'seam', 3),             # 15 % 接縫亮度差 + 各鏡頭校正誤差
    ('adv_blur', 'blur', 13),            # 遠處重度模糊
    ('adv_stopper', 'stopper', 28),      # 輪擋
    ('adv_double', 'double', 1),         # 雙線分隔 + 對側雙黃線
    ('adv_occl', 'occl', 18),            # 停歪的車、漏框、行人壓線
]


def _slot_json(d):
    return {'p': [float(v) for v in d['p']], 'kind': d['kind'], 'occupied': bool(d['occupied']),
            'conf': float(d.get('conf', 1.0))}


def _write_fixture(name, rgb, mpp, cx, cy, excl, veh, gts, dets, extra=None):
    from PIL import Image
    os.makedirs(FIXDIR, exist_ok=True)
    Image.fromarray(rgb).save(os.path.join(FIXDIR, name + '.png'))
    js = {'name': name, 'w': int(rgb.shape[1]), 'h': int(rgb.shape[0]), 'mpp': mpp, 'cx': cx, 'cy': cy,
          'exclude': [[float(v) for v in r] for r in excl], 'vehicles': [[float(v) for v in r] for r in veh],
          'gt': [], 'proto': [_slot_json(d) for d in dets]}
    for g in gts:
        js['gt'].append({'p': [float(v) for q in g['p'] for v in q], 'kind': g['kind'], 'occupied': bool(g['occupied']),
                         'detectable': bool(g.get('detectable', True))})
    if extra:
        js.update(extra)
    with open(os.path.join(FIXDIR, name + '.json'), 'w') as f:
        json.dump(js, f, indent=1)


def _quad_fixtures():
    """使用者車機存下來的原始四分割畫面 → 自家 BEV → fixture。

    合成場景生不出「自己停在格子裡、格內漆著車位號碼、格線貼著自車排除區」這種情況，
    但那正是實車遇到的（地下室那兩張）。固定成 fixture，以後改參數才不會又掉回去。
    路徑用環境變數 AVM_RAW_DIR 指定；沒有就跳過（repo 裡只留產好的 fixture）。
    """
    raw_dir = os.environ.get('AVM_RAW_DIR')
    if not raw_dir or not os.path.isdir(raw_dir):
        print('沒有 AVM_RAW_DIR，跳過實車四分割 fixture')
        return
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import avm_geometry as ag
    from PIL import Image
    calib = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                         'app', 'src', 'main', 'assets', 'avm_calib.json')
    geo = ag.Geometry.load(calib)
    ego = [-geo.car_width / 2 - 0.15, -geo.car_length / 2 - 0.15,
           geo.car_width / 2 + 0.15, geo.car_length / 2 + 0.15]
    for name, tag in (('raw_20261103_181414.jpg', 'quad_garage1'), ('raw_20261103_181806.jpg', 'quad_garage2')):
        path = os.path.join(raw_dir, name)
        if not os.path.exists(path):
            continue
        frame = np.array(Image.open(path).convert('RGB'))
        h, w = frame.shape[:2]
        rgb = ag.stitch(frame, ag.build_bev_lut(geo, w, h))
        det = Detector()
        dets = det.detect(rgb, 0.04, 150.0, 175.0, [ego], [])
        _write_fixture(tag, rgb, 0.04, 150.0, 175.0, [ego], [], [], dets, {'src': name})
        print(f'{tag}: {len(dets)} slots ({name})')


def cmd_fixtures():
    import time
    os.makedirs(FIXDIR, exist_ok=True)
    _quad_fixtures()
    for (name, kind, s, var) in FIXTURES:
        ki = SCENE_KINDS.index(kind)
        seed = 1000 * ki + s + (0 if var == '300' else 500000)
        sc, rgb, det, dets = run_scene(seed, kind, var)
        r = evaluate(sc, dets)
        _write_fixture(name, rgb, sc.mpp, sc.cx, sc.cy, [EGO_EXCL], sc.vehicles, sc.slots, dets,
                       {'tp': len(r['tp']), 'fp': len(r['fp']), 'fn': len(r['fn'])})
        print(f"{name}: {len(dets)} slots, tp={len(r['tp'])} fp={len(r['fp'])} fn={len(r['fn'])} tags={sc.tags}")
    for (name, kind, s) in ADV_FIXTURES:
        sc, rgb, det, dets = run_adv_scene(adv_seed(kind, s), kind)
        r = evaluate(sc, dets)
        _write_fixture(name, rgb, sc.mpp, sc.cx, sc.cy, [EGO_EXCL], sc.vehicles, sc.slots, dets,
                       {'tp': len(r['tp']), 'fp': len(r['fp']), 'fn': len(r['fn'])})
        print(f"{name}: {len(dets)} slots, tp={len(r['tp'])} fp={len(r['fp'])} fn={len(r['fn'])} tags={sc.tags}")
    if not os.path.exists(os.path.join(IMG_DIR, '13.jpg')):
        print('找不到翻拍照（PARKING_IMAGES），real* fixture 不重新產生：', IMG_DIR)
        return
    for name in REAL:
        rgb, excl, veh = real_bev(name)
        dets = Detector().detect(rgb, 0.04, 150.0, 175.0, excl, veh)
        _write_fixture('real' + name, rgb, 0.04, 150.0, 175.0, excl, veh, [], dets)
        print(f'real{name}: {len(dets)} slots')
    rgb, excl, veh, slots = composite13()
    dets = Detector().detect(rgb, 0.04, 150.0, 175.0, excl, veh)
    _write_fixture('real13_painted', rgb, 0.04, 150.0, 175.0, excl, veh, slots, dets)
    print(f'real13_painted: {len(dets)} slots')
    # Python 版耗時（參考）
    sc, rgb, det, dets = run_scene(5, 'aisle', '300')
    ts = []
    for _ in range(5):
        t0 = time.time()
        Detector().detect(rgb, sc.mpp, sc.cx, sc.cy, [EGO_EXCL], sc.vehicles)
        ts.append(time.time() - t0)
    print(f'python detect median {1000 * float(np.median(ts)):.1f} ms')


# ================================================================ CLI
VARIANTS = {
    '300': dict(w=300, h=350, mpp=0.04, cx=150.0, cy=175.0),
    '256': dict(w=256, h=300, mpp=0.03, cx=128.0, cy=150.0),
}


def run_scene(seed, kind, variant='300'):
    rng = np.random.default_rng(seed)
    style = 'own' if variant == '300' or rng.random() < 0.5 else 'stock'
    sc = make_scene(rng, kind, style=style, **VARIANTS[variant])
    rgb = render(sc)
    det = Detector()
    dets = det.detect(rgb, sc.mpp, sc.cx, sc.cy, [EGO_EXCL], sc.vehicles)
    return sc, rgb, det, dets


def _pct(v, q):
    return float(np.percentile(v, q)) if len(v) else float('nan')


def cmd_eval(n, variant='300', verbose=False):
    import time
    stats = {}
    fp_by_scene = {}
    ent_errs, far_errs = {}, {}
    misc = {'kind_bad': 0, 'ent_bad': 0, 'occ_bad': 0, 'tp': 0}
    t_all = []
    for ki, kind in enumerate(SCENE_KINDS):
        for s in range(n):
            seed = 1000 * ki + s + (0 if variant == '300' else 500000)
            t0 = time.time()
            sc, rgb, det, dets = run_scene(seed, kind, variant)
            t_all.append(time.time() - t0)
            r = evaluate(sc, dets)
            fp_by_scene[kind] = fp_by_scene.get(kind, 0) + len(r['fp'])
            for d in r['fp']:
                st = stats.setdefault(d['kind'], {'tp': 0, 'fn': 0, 'fp': 0})
                st['fp'] += 1
            for m in r['tp']:
                k = m['gt']['kind']
                st = stats.setdefault(k, {'tp': 0, 'fn': 0, 'fp': 0})
                st['tp'] += 1
                misc['tp'] += 1
                misc['kind_bad'] += not m['kind_ok']
                misc['ent_bad'] += not m['ent_ok']
                misc['occ_bad'] += not m['occ_ok']
                for is_ent, e in m['errs']:
                    (ent_errs if is_ent else far_errs).setdefault(k, []).append(e)
            for g in r['fn']:
                st = stats.setdefault(g['kind'], {'tp': 0, 'fn': 0, 'fp': 0})
                st['fn'] += 1
            if verbose and (r['fp'] or r['fn']):
                print(f"  {kind}#{s} seed={seed} tags={sc.tags} fp={len(r['fp'])} fn={len(r['fn'])} tp={len(r['tp'])}")
    print(f'variant {variant}: {n} scenes x {len(SCENE_KINDS)} kinds, python {np.mean(t_all):.2f}s/scene')
    for k in sorted(stats):
        st = stats[k]
        P = st['tp'] / max(1, st['tp'] + st['fp'])
        R = st['tp'] / max(1, st['tp'] + st['fn'])
        ee = ent_errs.get(k, [])
        fe = far_errs.get(k, [])
        print(f"  kind {k}: P={P:.3f} R={R:.3f} (tp={st['tp']} fp={st['fp']} fn={st['fn']})  "
              f"entrance corner err med={_pct(ee, 50):.3f} p90={_pct(ee, 90):.3f} <=0.25m {np.mean(np.array(ee) <= 0.25) if ee else float('nan'):.3f}  "
              f"far corner err med={_pct(fe, 50):.3f} p90={_pct(fe, 90):.3f} <=0.25m {np.mean(np.array(fe) <= 0.25) if fe else float('nan'):.3f}")
    print(f"  FP by scene: {fp_by_scene}")
    print(f"  of {misc['tp']} TP: wrong kind {misc['kind_bad']}, wrong entrance {misc['ent_bad']}, wrong occupied {misc['occ_bad']}")
    return stats


def run_adv_scene(seed, kind, variant='300'):
    rng = np.random.default_rng(seed)
    sc = make_adv_scene(rng, kind, **VARIANTS[variant])
    rgb = render(sc)
    det = Detector()
    dets = det.detect(rgb, sc.mpp, sc.cx, sc.cy, [EGO_EXCL], sc.vehicles)
    return sc, rgb, det, dets


def adv_seed(kind, s, variant='300'):
    return 7000000 + 1000 * ADV_KINDS.index(kind) + s + (0 if variant == '300' else 500000)


def cmd_adv(n, variant='300', kinds=None, verbose=False, preview=False):
    """對抗場景評估：每種場景 n 張，印出召回、誤報、角點誤差、類型 / 入口 / 佔用錯誤。"""
    tot = {'tp': 0, 'fp': 0, 'fn': 0}
    for kind in ADV_KINDS:
        if kinds and kind not in kinds:
            continue
        tp = fp = fn = kb = eb = ob = 0
        ee = []
        bad = []
        for s in range(n):
            seed = adv_seed(kind, s, variant)
            sc, rgb, det, dets = run_adv_scene(seed, kind, variant)
            r = evaluate(sc, dets)
            tp += len(r['tp'])
            fp += len(r['fp'])
            fn += len(r['fn'])
            for m in r['tp']:
                kb += not m['kind_ok']
                eb += not m['ent_ok']
                ob += not m['occ_ok']
                ee.extend(e for is_ent, e in m['errs'] if is_ent)
            if r['fp']:
                bad.append(s)
            if verbose and (r['fp'] or r['fn']):
                print(f"    {kind}#{s} tags={sc.tags} tp={len(r['tp'])} fp={len(r['fp'])} fn={len(r['fn'])} "
                      f"fpk={[d['kind'] for d in r['fp']]}")
            if preview and (r['fp'] or r['fn'] or s < 2):
                os.makedirs(os.path.join(SCRATCH, 'adv'), exist_ok=True)
                overlay(rgb, sc, dets, os.path.join(SCRATCH, 'adv', f'{variant}_{kind}_{s}.png'), det)
        ee = np.array(ee)
        print(f"  {kind:8s} tp={tp:4d} fp={fp:3d} fn={fn:4d}  R={tp / max(1, tp + fn):.3f} P={tp / max(1, tp + fp):.3f}  "
              f"ent err med={_pct(ee, 50):.3f} <=0.25m {np.mean(ee <= 0.25) if len(ee) else float('nan'):.3f}  "
              f"kind_bad={kb} ent_bad={eb} occ_bad={ob}  fp_scenes={bad[:12]}")
        for k in tot:
            tot[k] += {'tp': tp, 'fp': fp, 'fn': fn}[k]
    print(f"  TOTAL tp={tot['tp']} fp={tot['fp']} fn={tot['fn']} R={tot['tp'] / max(1, tot['tp'] + tot['fn']):.3f} "
          f"P={tot['tp'] / max(1, tot['tp'] + tot['fp']):.3f}")


def cmd_dump(n, variant, outdir):
    """把對抗場景與原本的合成場景（每種 n 張）連同原型輸出寫成 PNG + JSON，給 Kotlin 一致性比對用。"""
    from PIL import Image
    os.makedirs(outdir, exist_ok=True)
    jobs = [(k, s, True) for k in ADV_KINDS for s in range(n)] + [(k, s, False) for k in SCENE_KINDS for s in range(n)]
    for kind, s, adv in jobs:
        if adv:
            sc, rgb, det, dets = run_adv_scene(adv_seed(kind, s, variant), kind, variant)
        else:
            sc, rgb, det, dets = run_scene(1000 * SCENE_KINDS.index(kind) + s + (0 if variant == '300' else 500000),
                                           kind, variant)
        name = f"{'adv_' if adv else ''}{kind}_{variant}_{s}"
        Image.fromarray(rgb).save(os.path.join(outdir, name + '.png'))
        js = {'name': name, 'w': int(rgb.shape[1]), 'h': int(rgb.shape[0]), 'mpp': sc.mpp, 'cx': sc.cx, 'cy': sc.cy,
              'exclude': [EGO_EXCL], 'vehicles': [[float(v) for v in r] for r in sc.vehicles],
              'proto': [_slot_json(d) for d in dets]}
        with open(os.path.join(outdir, name + '.json'), 'w') as f:
            json.dump(js, f)
    print(f'{len(jobs)} scenes -> {outdir}')


def cmd_parity(outdir):
    """比對 dump 目錄裡 Kotlin 輸出（*.kt.json）與原型輸出。"""
    names = sorted(f[:-5] for f in os.listdir(outdir) if f.endswith('.json') and not f.endswith('.kt.json'))
    bad = 0
    worst = 0.0
    times = []
    for nm in names:
        p = json.load(open(os.path.join(outdir, nm + '.json')))['proto']
        kp = os.path.join(outdir, nm + '.kt.json')
        if not os.path.exists(kp):
            continue
        kt = json.load(open(kp))
        times.append(kt['ms'])
        k = kt['slots']
        ok = len(p) == len(k)
        if ok:
            for a in p:
                m = [b for b in k if b['kind'] == a['kind'] and b['occupied'] == a['occupied'] and
                     max(abs(x - y) for x, y in zip(a['p'], b['p'])) <= 0.02 and abs(a['conf'] - b['conf']) <= 0.01]
                if not m:
                    ok = False
                    break
                worst = max(worst, min(max(abs(x - y) for x, y in zip(a['p'], b['p'])) for b in m))
        if not ok:
            bad += 1
            print('MISMATCH', nm, 'proto', [(d['kind'], [round(v, 2) for v in d['p']]) for d in p],
                  'kotlin', [(d['kind'], [round(v, 2) for v in d['p']]) for d in k])
    t = np.array(times)
    print(f'{len(times)} compared, {bad} mismatched, worst corner diff {worst:.4f} m; kotlin ms median {np.median(t):.2f} '
          f'p90 {np.percentile(t, 90):.2f} max {t.max():.2f}')


def cmd_preview(n, variant='300', kinds=None, seeds=None):
    os.makedirs(SCRATCH, exist_ok=True)
    for ki, kind in enumerate(SCENE_KINDS):
        if kinds and kind not in kinds:
            continue
        for s in (seeds if seeds is not None else range(n)):
            seed = 1000 * ki + s + (0 if variant == '300' else 500000)
            sc, rgb, det, dets = run_scene(seed, kind, variant)
            r = evaluate(sc, dets)
            path = os.path.join(SCRATCH, f'syn_{variant}_{kind}_{s}.png')
            overlay(rgb, sc, dets, path, det)
            print(path, sc.tags, f"tp={len(r['tp'])} fp={len(r['fp'])} fn={len(r['fn'])} lines={len(det.dbg['lines'])} cells={len(det.dbg['cells'])}")


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else 'stats'
    if cmd == 'stats':
        n = int(sys.argv[2]) if len(sys.argv) > 2 else 40
        var = sys.argv[3] if len(sys.argv) > 3 else '300'
        cmd_eval(n, var, verbose='-v' in sys.argv)
    elif cmd == 'preview':
        n = int(sys.argv[2]) if len(sys.argv) > 2 else 3
        var = sys.argv[3] if len(sys.argv) > 3 else '300'
        kinds = sys.argv[4].split(',') if len(sys.argv) > 4 else None
        seeds = [int(v) for v in sys.argv[5].split(',')] if len(sys.argv) > 5 else None
        cmd_preview(n, var, kinds, seeds)
    elif cmd == 'adv':
        n = int(sys.argv[2]) if len(sys.argv) > 2 else 20
        args = [a for a in sys.argv[3:] if not a.startswith('-')]
        var = args[0] if args else '300'
        kinds = args[1].split(',') if len(args) > 1 else None
        cmd_adv(n, var, kinds, verbose='-v' in sys.argv, preview='-p' in sys.argv)
    elif cmd == 'dump':
        cmd_dump(int(sys.argv[2]), sys.argv[3], sys.argv[4])
    elif cmd == 'parity':
        cmd_parity(sys.argv[2])
    elif cmd == 'real':
        cmd_real()
    elif cmd == 'fixtures':
        cmd_fixtures()
    else:
        print(__doc__)
