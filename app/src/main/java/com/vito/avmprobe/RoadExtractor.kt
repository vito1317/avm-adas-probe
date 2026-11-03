package com.vito.avmprobe

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** 一條地面標線（車體座標，公尺）。kind：'w' 白、'y' 黃、'r' 紅色路緣漆。 */
data class RoadLine(
    val kind: Char, val dashed: Boolean,
    val x1: Float, val z1: Float, val x2: Float, val z2: Float, val conf: Float
)

/** edgeL < 0 < edgeR：左右路緣（路面邊界）的 x；找不到就是 null。ms = 這次花的時間。 */
data class RoadResult(val lines: List<RoadLine>, val edgeL: Float?, val edgeR: Float?, val ms: Long)

/**
 * 俯視圖（公制 BEV）上的道路標線與路緣。純 Kotlin，演算法、門檻與 tools/perception_proto.py 的 TUNED 一致。
 *
 * 1. 灰階 opening（只用有效像素）做 top-hat：比 0.44 m 窄的亮東西才算，車身、圖示這種大塊亮面不會變成線。
 * 2. 白：中性色 + top-hat + 周圍是灰色路面 + 旁邊沒有三角錐橘；黃：比周圍黃（色度對比）；紅：色相 0–5°。
 * 3. 8 連通元件：細長的沿自己方向 ±8° 投票，T/L 形（車道線接停止線）全角度投票。
 * 4. Hough 取峰 → 兩次 PCA 重新擬合 → 量漆寬、去重複 → 投影切段（空隙 > 0.6 m）→ 虛實線判斷。
 * 5. 路緣：車旁路面取色模型，每 0.25 m 一列往外掃，取最密集的 0.4 m 群。
 * 所有暫存陣列都留在物件裡重複用；同一個 instance 不要多執行緒同時呼叫。
 */
class RoadExtractor {

    companion object {
        const val EGO_HX = 0.925
        const val EGO_HZ = 2.42
        private const val EGO_MARGIN = 0.15        // 自車外擴一圈不找標線（螢幕模式的車子圖示、後照鏡）
        private const val BG_HALF_M = 0.20         // opening 視窗半寬
        private const val T_WHITE = 24
        private const val Y_WHITE = 100
        private const val NEUTRAL = 50
        private const val CHROMA_HALF_M = 0.30
        private const val BG_CHROMA = 36
        private const val Y_CHROMA = 20
        private const val CONE_NEAR_M = 0.25
        private const val MIN_AREA = 0.03
        private const val MIN_LEN = 0.40
        private const val MAX_WIDTH = 0.50
        private const val MIN_ASPECT = 3.0
        private const val THIN_M = 0.50
        private const val THIN_FRAC = 0.85
        private const val D_BIN = 0.05
        private const val INV_D_BIN = 1.0 / D_BIN     // 投票用乘法（Python 端同樣寫 * (1.0 / dBin)）
        private const val VOTE_WIN = 8
        private const val SUPPORT_M = 0.8
        private const val BAND1 = 0.10
        private const val BAND = 0.08
        private const val MAX_LINES = 8
        private const val MAX_ITER = 40
        private const val GAP_M = 0.6
        private const val MIN_SEG_M = 0.5
        private const val MIN_COVERED = 0.8
        private const val SOLID_M = 5.0
        private const val DASH_GAP_M = 1.5
        private const val THICK_M = 0.25
        private const val DUP_M = 0.17
        private const val DUP_DEG = 4.0
        private const val WIDE_M = 0.30
        private const val MIN_FILL = 0.25
        private const val CLUTTER_MAX = 0.25
        private const val EDGE_MIN_ROWS = 4
        private const val EDGE_ROWS = 33

        private val COS_T = DoubleArray(180) { cos(Math.toRadians((it - 90).toDouble())) }
        private val SIN_T = DoubleArray(180) { sin(Math.toRadians((it - 90).toDouble())) }

        internal fun wrap90(a: Double): Double {
            val m = (a + 90.0) % 180.0
            return (if (m < 0) m + 180.0 else m) - 90.0
        }

        internal fun floorHalf(v: Double): Int = floor(v + 0.5).toInt()
    }

    // ───── 依影像大小配置、跨呼叫重複使用的緩衝區 ─────
    private var bw = -1
    private var bh = -1
    private var xs = DoubleArray(0)
    private var zs = DoubleArray(0)
    private var lum = IntArray(0)
    private var rgb = IntArray(0)              // 0x00RRGGBB
    private var flags = ByteArray(0)           // 1 有效、2 未被呼叫端排除、4 可找標線
    private var bufA = IntArray(0)
    private var bufB = IntArray(0)
    private var iCnt = IntArray(0)
    private var iR = IntArray(0)
    private var iG = IntArray(0)
    private var iB = IntArray(0)
    private var iOr = IntArray(0)
    private var cls = ByteArray(0)
    private var lab = IntArray(0)
    private var hrun = IntArray(0)
    private var vrun = IntArray(0)
    private var stack = IntArray(0)

    private var cap = 0
    private var cN = IntArray(0)
    private var cSi = LongArray(0)
    private var cSj = LongArray(0)
    private var cSii = LongArray(0)
    private var cSij = LongArray(0)
    private var cSjj = LongArray(0)
    private var cThin = IntArray(0)
    private var cMode = ByteArray(0)
    private var cPhi = DoubleArray(0)

    private var kCap = 0
    private var kX = DoubleArray(0)
    private var kZ = DoubleArray(0)
    private var kC = ByteArray(0)
    private var kLab = IntArray(0)
    private var kClaim = BooleanArray(0)
    private var kSel = BooleanArray(0)
    private var kGrab = BooleanArray(0)
    private var kR = DoubleArray(0)
    private var kN = 0

    private var aCap = 0
    private var aX = DoubleArray(0)
    private var aZ = DoubleArray(0)
    private var aN = 0

    private var acc = IntArray(0)
    private var spTmp = DoubleArray(0)
    private var occ = BooleanArray(0)
    private val hist = IntArray(30)
    private val histY = IntArray(256)
    private val histU = IntArray(511)
    private val histV = IntArray(511)
    private val histD = IntArray(511)
    private val edgeVals = DoubleArray(EDGE_ROWS)

    private class Cand(
        val kind: Int, val dashed: Boolean, val phi: Double, val phiDeg: Double, val off: Double,
        val s0: Double, val s1: Double, val conf: Double, val wd: Double
    )

    private val found = ArrayList<Cand>()

    private fun ensure(w: Int, h: Int) {
        if (w == bw && h == bh) return
        bw = w
        bh = h
        val n = w * h
        xs = DoubleArray(w)
        zs = DoubleArray(h)
        lum = IntArray(n)
        rgb = IntArray(n)
        flags = ByteArray(n)
        bufA = IntArray(n)
        bufB = IntArray(n)
        val ni = (w + 1) * (h + 1)
        iCnt = IntArray(ni)
        iR = IntArray(ni)
        iG = IntArray(ni)
        iB = IntArray(ni)
        iOr = IntArray(ni)
        cls = ByteArray(n)
        lab = IntArray(n)
        hrun = IntArray(n)
        vrun = IntArray(n)
        stack = IntArray(n)
    }

    private fun ensureComps(n: Int) {
        if (n <= cap) return
        cap = max(n, cap * 2 + 64)
        // 標記途中會長大：已累積的統計要保留
        cN = cN.copyOf(cap)
        cSi = cSi.copyOf(cap)
        cSj = cSj.copyOf(cap)
        cSii = cSii.copyOf(cap)
        cSij = cSij.copyOf(cap)
        cSjj = cSjj.copyOf(cap)
        cThin = cThin.copyOf(cap)
        cMode = cMode.copyOf(cap)
        cPhi = cPhi.copyOf(cap)
    }

    private fun ensureKept(n: Int) {
        if (n <= kCap) return
        kCap = max(n, kCap * 2 + 256)
        kX = DoubleArray(kCap)
        kZ = DoubleArray(kCap)
        kC = ByteArray(kCap)
        kLab = IntArray(kCap)
        kClaim = BooleanArray(kCap)
        kSel = BooleanArray(kCap)
        kGrab = BooleanArray(kCap)
        kR = DoubleArray(kCap)
        spTmp = DoubleArray(kCap)
    }

    private fun ensureAll(n: Int) {
        if (n <= aCap) return
        aCap = max(n, aCap * 2 + 256)
        aX = DoubleArray(aCap)
        aZ = DoubleArray(aCap)
    }

    fun extract(
        argb: IntArray, w: Int, h: Int, mpp: Float, cx: Float, cy: Float,
        exclude: List<FloatArray>
    ): RoadResult {
        val t0 = System.nanoTime()
        ensure(w, h)
        val m = mpp.toDouble()
        val ox = cx.toDouble()
        val oy = cy.toDouble()
        val n = w * h
        for (i in 0 until w) xs[i] = (i + 0.5 - ox) * m
        for (j in 0 until h) zs[j] = (oy - (j + 0.5)) * m

        // ── 像素：亮度、有效、排除 ──
        for (p in 0 until n) {
            val c = argb[p]
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val b = c and 255
            rgb[p] = c and 0xFFFFFF
            lum[p] = (77 * r + 150 * g + 29 * b) shr 8
            flags[p] = if (r + g + b >= 24) 7 else 0
        }
        for (rc in exclude) markRect(rc[0].toDouble(), rc[1].toDouble(), rc[2].toDouble(), rc[3].toDouble(), 6)
        val emx = (-EGO_HX - EGO_MARGIN).toFloat().toDouble()
        val emz = (-EGO_HZ - EGO_MARGIN).toFloat().toDouble()
        markRect(emx, emz, (EGO_HX + EGO_MARGIN).toFloat().toDouble(), (EGO_HZ + EGO_MARGIN).toFloat().toDouble(), 4)

        // ── opening（有效像素）→ top-hat ──
        val half = floorHalf(BG_HALF_M / m)
        for (p in 0 until n) bufA[p] = if (flags[p].toInt() and 1 != 0) lum[p] else 255
        minFilter(bufA, bufB, w, h, half)                  // 侵蝕
        for (p in 0 until n) bufA[p] = 255 - bufA[p]
        minFilter(bufA, bufB, w, h, half)                  // 膨脹 = 255 − min(255 − v)
        for (p in 0 until n) bufA[p] = 255 - bufA[p]       // bufA = opening

        // ── 積分影像：有效數、R/G/B（有效）、三角錐橘 ──
        integrals(w, h)
        val hc = floorHalf(CHROMA_HALF_M / m)
        val ho = floorHalf(CONE_NEAR_M / m)

        // ── 候選分類：1 白、2 黃、3 紅 ──
        var nCand = 0
        for (j in 0 until h) for (i in 0 until w) {
            val p = j * w + i
            cls[p] = 0
            if (flags[p].toInt() and 4 == 0) continue
            val c = rgb[p]
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val b = c and 255
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val y = lum[p]
            // 先做便宜的顏色 / 亮度判斷，過了才算視窗和
            val yelColor = r >= 90 && g >= 70 && r - b >= 45 && g - b >= 30 && abs(r - g) <= 80
            val whiteColor = mx - mn <= NEUTRAL && y >= Y_WHITE && y - bufA[p] >= T_WHITE
            if (yelColor || whiteColor) {
                val cnt = box(iCnt, w, i, j, hc, h)
                val sr = box(iR, w, i, j, hc, h)
                val sg = box(iG, w, i, j, hc, h)
                val sb = box(iB, w, i, j, hc, h)
                if (yelColor && (r + g - 2 * b) * cnt - (sr + sg - 2 * sb) >= 2 * Y_CHROMA * cnt) {
                    cls[p] = 2; nCand++; continue
                }
                if (isRed(r, g, b)) { cls[p] = 3; nCand++; continue }
                if (!whiteColor || box(iOr, w, i, j, ho, h) >= 3) continue
                val smax = max(sr, max(sg, sb))
                val smin = min(sr, min(sg, sb))
                if (smax - smin > BG_CHROMA * cnt) continue
                cls[p] = 1
                nCand++
            } else if (isRed(r, g, b)) {
                cls[p] = 3
                nCand++
            }
        }

        // ── 所有候選像素座標（雜訊帶檢查用）──
        ensureAll(nCand)
        aN = 0
        for (p in 0 until n) if (cls[p].toInt() != 0) {
            aX[aN] = xs[p % w]
            aZ[aN] = zs[p / w]
            aN++
        }

        // ── 連通元件 + 形狀 ──
        val nc = label(w, h)
        runLengths(w, h)
        val thinPx = floor(THIN_M / m + 1e-6).toInt()
        for (p in 0 until n) {
            val l = lab[p]
            if (l > 0 && min(hrun[p], vrun[p]) <= thinPx) cThin[l]++
        }
        val a1 = m * m
        for (k in 1..nc) {
            cMode[k] = 0
            val c = cN[k].toDouble()
            val mi = cSi[k] / c
            val mj = cSj[k] / c
            val cii = cSii[k] / c - mi * mi
            val cij = cSij[k] / c - mi * mj
            val cjj = cSjj[k] / c - mj * mj
            val hd = (cii - cjj) / 2.0
            val disc = sqrt(hd * hd + cij * cij)
            val l1 = (cii + cjj) / 2.0 + disc
            val l2 = max((cii + cjj) / 2.0 - disc, 0.0)
            val al = 0.5 * atan2(2.0 * cij, cii - cjj)
            var phi = Math.toDegrees(atan2(cos(al), -sin(al)))
            while (phi >= 90.0) phi -= 180.0
            while (phi < -90.0) phi += 180.0
            cPhi[k] = phi
            val area = c * a1
            val len = sqrt(12.0 * l1 + 1.0) * m
            val wid = sqrt(12.0 * l2 + 1.0) * m
            if (area < MIN_AREA || len < MIN_LEN) continue
            if (wid <= MAX_WIDTH && len >= MIN_ASPECT * wid) cMode[k] = 1
            else if (area >= 0.06 && cThin[k] >= THIN_FRAC * c) cMode[k] = 2
        }

        // ── 保留的像素（raster 順序）──
        var nk = 0
        for (p in 0 until n) { val l = lab[p]; if (l > 0 && cMode[l].toInt() != 0) nk++ }
        ensureKept(nk)
        kN = 0
        for (p in 0 until n) {
            val l = lab[p]
            if (l <= 0 || cMode[l].toInt() == 0) continue
            kX[kN] = xs[p % w]
            kZ[kN] = zs[p / w]
            kC[kN] = cls[p]
            kLab[kN] = l
            kClaim[kN] = false
            kN++
        }

        found.clear()
        if (kN > 0) findLines(w, h, m, ox, oy)

        // ── 斑馬紋：比一般標線粗、又跟某條細標線平行 ──
        val lines = ArrayList<RoadLine>(MAX_LINES)
        for (L in found) {
            if (lines.size >= MAX_LINES) break
            var zebra = false
            if (L.wd > WIDE_M) {
                for (M in found) if (M.wd <= WIDE_M && abs(wrap90(M.phiDeg - L.phiDeg)) < 10.0) { zebra = true; break }
            }
            if (zebra) continue
            val c = cos(L.phi)
            val s = sin(L.phi)
            lines.add(
                RoadLine(
                    "wyr"[L.kind - 1], L.dashed,
                    (L.off * c + L.s0 * s).toFloat(), (-L.off * s + L.s0 * c).toFloat(),
                    (L.off * c + L.s1 * s).toFloat(), (-L.off * s + L.s1 * c).toFloat(),
                    min(1.0, L.conf).toFloat()
                )
            )
        }

        val eL = edge(w, h, m, ox, oy, -1)
        val eR = edge(w, h, m, ox, oy, 1)
        val ms = (System.nanoTime() - t0) / 1_000_000L
        return RoadResult(lines, eL?.toFloat(), eR?.toFloat(), ms)
    }

    /** 中心落在 [x0,x1]×[z0,z1] 內的像素清掉 flag 位元 bits。 */
    private fun markRect(x0: Double, z0: Double, x1: Double, z1: Double, bits: Int) {
        val keep = (bits.inv() and 7).toByte()
        for (j in 0 until bh) {
            val z = zs[j]
            if (z < z0 || z > z1) continue
            val row = j * bw
            for (i in 0 until bw) {
                val x = xs[i]
                if (x >= x0 && x <= x1) flags[row + i] = (flags[row + i].toInt() and keep.toInt()).toByte()
            }
        }
    }

    private fun isRed(r: Int, g: Int, b: Int): Boolean =
        r >= 90 && r - g >= 55 && r - b >= 45 && 12 * abs(g - b) <= r - min(g, b)

    /**
     * 方形 (2half+1)² 的 min 濾波（van Herk / Gil-Werman，每點約 3 次比較），超出影像補 255（等於不算）。
     * 結果寫回 a；b 當暫存。max 濾波用 255−v 轉過來做。
     */
    private fun minFilter(a: IntArray, b: IntArray, w: Int, h: Int, half: Int) {
        val win = 2 * half + 1
        // 水平：每列各自做
        val nbH = (w + 2 * half + win - 1) / win * win
        if (lineE.size < nbH) { lineE = IntArray(nbH); lineG = IntArray(nbH); lineH = IntArray(nbH) }
        val e = lineE
        val g = lineG
        val hh = lineH
        for (j in 0 until h) {
            val row = j * w
            for (k in 0 until nbH) {
                val s = k - half
                e[k] = if (s >= 0 && s < w) a[row + s] else 255
            }
            var k = 0
            while (k < nbH) {
                g[k] = e[k]
                val last = k + win - 1
                hh[last] = e[last]
                for (t in 1 until win) {
                    val v = e[k + t]
                    val pg = g[k + t - 1]
                    g[k + t] = if (v < pg) v else pg
                    val u = e[last - t]
                    val ph = hh[last - t + 1]
                    hh[last - t] = if (u < ph) u else ph
                }
                k += win
            }
            for (p in 0 until w) {
                val x = hh[p]
                val y = g[p + win - 1]
                b[row + p] = if (x < y) x else y
            }
        }
        // 垂直：整列整列平行做（記憶體連續）
        val nbV = (h + 2 * half + win - 1) / win * win
        if (vG.size < nbV * w) { vG = IntArray(nbV * w); vH = IntArray(nbV * w) }
        val G = vG
        val H = vH
        var k0 = 0
        while (k0 < nbV) {
            for (t in 0 until win) {
                val jp = k0 + t
                val s = jp - half
                val o = jp * w
                if (s in 0 until h) {
                    val so = s * w
                    if (t == 0) System.arraycopy(b, so, G, o, w)
                    else for (i in 0 until w) { val v = b[so + i]; val pv = G[o - w + i]; G[o + i] = if (v < pv) v else pv }
                } else {
                    if (t == 0) java.util.Arrays.fill(G, o, o + w, 255)
                    else System.arraycopy(G, o - w, G, o, w)      // 跟 255 取 min 不變
                }
            }
            for (t in win - 1 downTo 0) {
                val jp = k0 + t
                val s = jp - half
                val o = jp * w
                if (s in 0 until h) {
                    val so = s * w
                    if (t == win - 1) System.arraycopy(b, so, H, o, w)
                    else for (i in 0 until w) { val v = b[so + i]; val pv = H[o + w + i]; H[o + i] = if (v < pv) v else pv }
                } else {
                    if (t == win - 1) java.util.Arrays.fill(H, o, o + w, 255)
                    else System.arraycopy(H, o + w, H, o, w)
                }
            }
            k0 += win
        }
        for (j in 0 until h) {
            val o = j * w
            val og = (j + win - 1) * w
            for (i in 0 until w) {
                val x = H[o + i]
                val y = G[og + i]
                a[o + i] = if (x < y) x else y
            }
        }
    }

    private var lineE = IntArray(0)
    private var lineG = IntArray(0)
    private var lineH = IntArray(0)
    private var vG = IntArray(0)
    private var vH = IntArray(0)

    private fun integrals(w: Int, h: Int) {
        val W1 = w + 1
        for (i in 0..w) { iCnt[i] = 0; iR[i] = 0; iG[i] = 0; iB[i] = 0; iOr[i] = 0 }
        for (j in 0 until h) {
            var rc = 0
            var rr = 0
            var rg = 0
            var rb = 0
            var ro = 0
            val o0 = j * W1
            val o1 = (j + 1) * W1
            iCnt[o1] = 0; iR[o1] = 0; iG[o1] = 0; iB[o1] = 0; iOr[o1] = 0
            for (i in 0 until w) {
                val p = j * w + i
                val c = rgb[p]
                val r = (c shr 16) and 255
                val g = (c shr 8) and 255
                val b = c and 255
                if (flags[p].toInt() and 1 != 0) { rc++; rr += r; rg += g; rb += b }
                if (ConeDetector.isOrange(r, g, b, ConeDetector.MIN_R, ConeDetector.SAT_PCT)) ro++
                iCnt[o1 + i + 1] = iCnt[o0 + i + 1] + rc
                iR[o1 + i + 1] = iR[o0 + i + 1] + rr
                iG[o1 + i + 1] = iG[o0 + i + 1] + rg
                iB[o1 + i + 1] = iB[o0 + i + 1] + rb
                iOr[o1 + i + 1] = iOr[o0 + i + 1] + ro
            }
        }
    }

    private fun box(ii: IntArray, w: Int, i: Int, j: Int, half: Int, h: Int): Int {
        val W1 = w + 1
        val i0 = max(0, i - half)
        val i1 = min(w, i + half + 1)
        val j0 = max(0, j - half)
        val j1 = min(h, j + half + 1)
        return ii[j1 * W1 + i1] - ii[j0 * W1 + i1] - ii[j1 * W1 + i0] + ii[j0 * W1 + i0]
    }

    /** 8 連通標記（raster 順序編號，跟 scipy.ndimage.label 一樣），順便累積 PCA 用的整數和。 */
    private fun label(w: Int, h: Int): Int {
        val n = w * h
        java.util.Arrays.fill(lab, 0, n, 0)
        var nc = 0
        for (p0 in 0 until n) {
            if (cls[p0].toInt() == 0 || lab[p0] != 0) continue
            nc++
            ensureComps(nc + 1)
            cN[nc] = 0; cSi[nc] = 0; cSj[nc] = 0; cSii[nc] = 0; cSij[nc] = 0; cSjj[nc] = 0; cThin[nc] = 0
            var sp = 0
            stack[sp++] = p0
            lab[p0] = nc
            while (sp > 0) {
                val p = stack[--sp]
                val i = p % w
                val j = p / w
                cN[nc]++
                cSi[nc] += i.toLong()
                cSj[nc] += j.toLong()
                cSii[nc] += (i * i).toLong()
                cSij[nc] += (i * j).toLong()
                cSjj[nc] += (j * j).toLong()
                for (dj in -1..1) {
                    val jj = j + dj
                    if (jj < 0 || jj >= h) continue
                    for (di in -1..1) {
                        val ii = i + di
                        if (ii < 0 || ii >= w) continue
                        val q = jj * w + ii
                        if (cls[q].toInt() != 0 && lab[q] == 0) {
                            lab[q] = nc
                            stack[sp++] = q
                        }
                    }
                }
            }
        }
        return nc
    }

    private fun runLengths(w: Int, h: Int) {
        for (j in 0 until h) {
            var i = 0
            while (i < w) {
                val p = j * w + i
                if (cls[p].toInt() == 0) { hrun[p] = 0; i++; continue }
                var e = i
                while (e < w && cls[j * w + e].toInt() != 0) e++
                for (q in i until e) hrun[j * w + q] = e - i
                i = e
            }
        }
        // 垂直：往下累計長度，再由下往上把整段長度傳回去（逐列存取，記憶體連續）
        for (j in 0 until h) {
            val o = j * w
            for (i in 0 until w) {
                vrun[o + i] = if (cls[o + i].toInt() == 0) 0 else if (j > 0) vrun[o - w + i] + 1 else 1
            }
        }
        for (j in h - 2 downTo 0) {
            val o = j * w
            for (i in 0 until w) {
                if (vrun[o + i] != 0 && vrun[o + w + i] != 0) vrun[o + i] = vrun[o + w + i]
            }
        }
    }

    // ───────────── Hough + 擬合 ─────────────

    private var halfR = 0
    private var nR = 0

    private fun forVotes(q: Int, sign: Int) {
        val mode = cMode[kLab[q]].toInt()
        val x = kX[q]
        val z = kZ[q]
        if (mode == 1) {
            val k0 = floor(cPhi[kLab[q]] + 0.5).toInt() + 90
            for (o in -VOTE_WIN..VOTE_WIN) {
                val k = Math.floorMod(k0 + o, 180)
                val r = floor((x * COS_T[k] - z * SIN_T[k]) * INV_D_BIN).toInt() + halfR
                acc[k * nR + r] += sign
                rowDirty[k] = true
            }
        } else {
            for (k in 0 until 180) {
                val r = floor((x * COS_T[k] - z * SIN_T[k]) * INV_D_BIN).toInt() + halfR
                acc[k * nR + r] += sign
            }
            java.util.Arrays.fill(rowDirty, true)
        }
    }

    // 每個角度列的最大三格和（第一個最大）；有改過票的列才重算
    private val rowDirty = BooleanArray(180)
    private val rowBest = IntArray(180)
    private val rowBestR = IntArray(180)

    private fun rescanRow(k: Int) {
        val row = k * nR
        var best = Int.MIN_VALUE
        var br = 1
        var a0 = acc[row]
        var a1 = acc[row + 1]
        for (r in 1 until nR - 1) {
            val a2 = acc[row + r + 1]
            val sc = a0 + a1 + a2
            if (sc > best) { best = sc; br = r }
            a0 = a1
            a1 = a2
        }
        rowBest[k] = best
        rowBestR[k] = br
        rowDirty[k] = false
    }

    private fun unvote(sel: BooleanArray) {
        for (q in 0 until kN) if (sel[q]) forVotes(q, -1)
    }

    private var fitPhi = 0.0
    private var fitD = 0.0

    /** sel 裡像素的 PCA 直線（跟 Python _pca_line 同一套算式）。 */
    private fun pcaLine(sel: BooleanArray): Int {
        var cnt = 0
        var sx = 0.0
        var sz = 0.0
        for (q in 0 until kN) if (sel[q]) { cnt++; sx += kX[q]; sz += kZ[q] }
        if (cnt < 2) return cnt
        val mx = sx / cnt
        val mz = sz / cnt
        var cxx = 0.0
        var cxz = 0.0
        var czz = 0.0
        for (q in 0 until kN) if (sel[q]) {
            val dx = kX[q] - mx
            val dz = kZ[q] - mz
            cxx += dx * dx
            cxz += dx * dz
            czz += dz * dz
        }
        cxx /= cnt
        cxz /= cnt
        czz /= cnt
        val al = 0.5 * atan2(2.0 * cxz, cxx - czz)
        var phi = atan2(cos(al), sin(al))
        if (phi >= Math.PI / 2) phi -= Math.PI
        if (phi < -Math.PI / 2) phi += Math.PI
        fitPhi = phi
        fitD = mx * cos(phi) - mz * sin(phi)
        return cnt
    }

    private fun selectBand(c: Double, s: Double, d: Double, band: Double): Int {
        var cnt = 0
        for (q in 0 until kN) {
            val v = !kClaim[q] && abs(kX[q] * c - kZ[q] * s - d) <= band
            kSel[q] = v
            if (v) cnt++
        }
        return cnt
    }

    // 覆蓋段（公尺），segN 段
    private var segA = DoubleArray(64)
    private var segB = DoubleArray(64)
    private var segN = 0

    private fun segments(c: Double, s: Double, m: Double) {
        var ns = 0
        var smin = Double.MAX_VALUE
        for (q in 0 until kN) if (kSel[q]) {
            val v = kX[q] * s + kZ[q] * c
            spTmp[ns++] = v
            if (v < smin) smin = v
        }
        var nb = 0
        for (t in 0 until ns) nb = max(nb, floor((spTmp[t] - smin) / m).toInt() + 1)
        if (occ.size < nb) occ = BooleanArray(nb * 2)
        java.util.Arrays.fill(occ, 0, nb, false)
        for (t in 0 until ns) occ[floor((spTmp[t] - smin) / m).toInt()] = true
        val maxGap = floor(GAP_M / m + 1e-6).toInt()
        val minSeg = floor(MIN_SEG_M / m + 1e-6).toInt()
        segN = 0
        var start = 0
        var last = 0
        for (q in 1 until nb) {
            if (!occ[q]) continue
            if (q - last - 1 > maxGap) {
                addSeg(start, last, minSeg, smin, m)
                start = q
            }
            last = q
        }
        addSeg(start, last, minSeg, smin, m)
    }

    private fun addSeg(a: Int, e: Int, minSeg: Int, smin: Double, m: Double) {
        if (e - a + 1 < minSeg) return
        if (segN == segA.size) {
            segA = segA.copyOf(segN * 2)
            segB = segB.copyOf(segN * 2)
        }
        segA[segN] = smin + a * m
        segB[segN] = smin + (e + 1) * m
        segN++
    }

    private var obsN = 0
    private var obsOk = 0

    /** 沿線 [s0,s1) 每 mpp 取樣，結果在 obsN / obsOk。 */
    private fun observable(w: Int, h: Int, m: Double, ox: Double, oy: Double,
                           c: Double, s: Double, d: Double, s0: Double, s1: Double) {
        var nn = 0
        var ok = 0
        var t = s0
        while (t < s1) {
            val x = d * c + t * s
            val z = -d * s + t * c
            val i = floor(x / m + ox).toInt()
            val j = floor(oy - z / m).toInt()
            nn++
            if (i in 0 until w && j in 0 until h && (flags[j * w + i].toInt() and 4) != 0) ok++
            t += m
        }
        obsN = nn
        obsOk = ok
    }

    private fun findLines(w: Int, h: Int, m: Double, ox: Double, oy: Double) {
        val dmax = sqrt(max(ox, w - ox).let { it * it } + max(oy, h - oy).let { it * it }) * m + 0.1
        halfR = ceil(dmax / D_BIN).toInt() + 1
        nR = 2 * halfR
        if (acc.size < 180 * nR) acc = IntArray(180 * nR) else java.util.Arrays.fill(acc, 0, 180 * nR, 0)
        java.util.Arrays.fill(rowDirty, true)
        for (q in 0 until kN) forVotes(q, 1)
        val minVotes = floorHalf(SUPPORT_M / m * 2.0)

        for (iter in 0 until MAX_ITER) {
            if (found.size >= MAX_LINES + 6) break
            var best = Int.MIN_VALUE
            var bk = 0
            var br = 1
            for (k in 0 until 180) {
                if (rowDirty[k]) rescanRow(k)
                if (rowBest[k] > best) { best = rowBest[k]; bk = k; br = rowBestR[k] }
            }
            if (best < minVotes) break
            val row = bk * nR
            val a0 = acc[row + br - 1].toDouble()
            val a1 = acc[row + br].toDouble()
            val a2 = acc[row + br + 1].toDouble()
            val dc = ((br - 1 - halfR + 0.5) * a0 + (br - halfR + 0.5) * a1 + (br + 1 - halfR + 0.5) * a2) * D_BIN / best
            var c = COS_T[bk]
            var s = SIN_T[bk]
            var ok = selectBand(c, s, dc, BAND1) >= 2
            var phi = 0.0
            var d = 0.0
            var covered = 0.0
            if (ok) {
                pcaLine(kSel)
                c = cos(fitPhi); s = sin(fitPhi)
                ok = selectBand(c, s, fitD, BAND) >= 2
            }
            if (ok) {
                pcaLine(kSel)
                phi = fitPhi
                d = fitD
                c = cos(phi); s = sin(phi)
                segments(c, s, m)
                covered = 0.0
                for (q in 0 until segN) covered += segB[q] - segA[q]
                ok = covered >= MIN_COVERED
            }
            if (!ok) {
                unvote(kSel)
                acc[row + br - 1] = 0
                acc[row + br] = 0
                acc[row + br + 1] = 0
                rowDirty[bk] = true
                continue
            }
            // 漆寬：±0.30 m 內未認領像素的橫向分佈，從中心往外找連續區
            java.util.Arrays.fill(hist, 0)
            for (q in 0 until kN) {
                val rr = kX[q] * c - kZ[q] * s - d
                kR[q] = rr
                if (!kClaim[q] && abs(rr) < 0.30) {
                    val b = floor((rr + 0.30) / 0.02).toInt().coerceIn(0, 29)
                    hist[b]++
                }
            }
            val pk = max(hist[14], hist[15])
            var lo = 14
            var hi = 15
            while (pk > 0 && lo > 0 && hist[lo - 1] * 4 >= pk) lo--
            while (pk > 0 && hi < 29 && hist[hi + 1] * 4 >= pk) hi++
            val wlo = lo * 0.02 - 0.30
            val whi = (hi + 1) * 0.02 - 0.30
            for (q in 0 until kN) {
                val rr = kR[q]
                kGrab[q] = kSel[q] || (!kClaim[q] && abs(rr) < 0.30 && rr >= wlo && rr < whi)
            }
            val s0all = segA[0]
            val s1all = segB[segN - 1]
            val mx = d * c + (s0all + s1all) / 2 * s
            val mz = -d * s + (s0all + s1all) / 2 * c
            var n1 = 0
            var n2 = 0
            var n3 = 0
            for (q in 0 until kN) if (kSel[q]) when (kC[q].toInt()) { 1 -> n1++; 2 -> n2++; 3 -> n3++ }
            val kind = if (n1 >= n2 && n1 >= n3) 1 else if (n2 >= n3) 2 else 3
            val phiDeg = Math.toDegrees(phi)
            var dup = false
            for (L in found) {
                if (L.kind != kind || abs(wrap90(L.phiDeg - phiDeg)) > DUP_DEG) continue
                val pr = Math.toRadians(L.phiDeg)
                if (abs(mx * cos(pr) - mz * sin(pr) - L.off) < DUP_M) { dup = true; break }
            }
            for (q in 0 until kN) if (kGrab[q]) kClaim[q] = true
            unvote(kGrab)
            // 認領過的像素之後都不會再用到：壓縮掉（保持 raster 順序），後面每一輪少掃很多
            var wq = 0
            for (q in 0 until kN) {
                if (kClaim[q]) continue
                if (wq != q) { kX[wq] = kX[q]; kZ[wq] = kZ[q]; kC[wq] = kC[q]; kLab[wq] = kLab[q] }
                kClaim[wq] = false
                wq++
            }
            kN = wq
            if (dup) continue

            // 虛實：空隙太短或看不到的都併起來
            var spN = 0
            var spA0 = segA[0]
            var spB0 = segB[0]
            var longest = 0.0
            var curA = segA[0]
            var curB = segB[0]
            for (q in 1 until segN) {
                val a = segA[q]
                val e = segB[q]
                observable(w, h, m, ox, oy, c, s, d, curB, a)
                if (a - curB < DASH_GAP_M || (obsN > 0 && obsOk * 2 < obsN)) {
                    curB = e
                } else {
                    if (spN == 0) { spA0 = curA; spB0 = curB }
                    longest = max(longest, curB - curA)
                    spN++
                    curA = a
                    curB = e
                }
            }
            if (spN == 0) { spA0 = curA; spB0 = curB }
            longest = max(longest, curB - curA)
            spN++
            val wd = (hi - lo + 1) * 0.02
            val dashed = if (longest >= SOLID_M) false
            else if (spN >= 2) true
            else if (wd >= THICK_M) false
            else {
                val ext = floor(1.5 / m + 1e-6).toInt()
                val need = floor(1.0 / m + 1e-6).toInt()
                observable(w, h, m, ox, oy, c, s, d, spA0 - ext * m, spA0)
                val okA = obsOk
                observable(w, h, m, ox, oy, c, s, d, spB0, spB0 + ext * m)
                val okB = obsOk
                okA >= need || okB >= need || longest < 2.5
            }
            // 零散小片湊成的長線不要（不可觀測的部分不算分母）
            observable(w, h, m, ox, oy, c, s, d, s0all, s1all)
            if (covered < MIN_FILL * obsOk * m) continue
            // 兩側 0.35–0.8 m 還有一堆候選像素 → 牆面、樹叢、車身紋理
            var nb = 0
            for (q in 0 until aN) {
                val r2 = aX[q] * c - aZ[q] * s - d
                val s2 = aX[q] * s + aZ[q] * c
                if (abs(r2) >= 0.35 && abs(r2) <= 0.80 && s2 >= s0all && s2 <= s1all) nb++
            }
            if (nb * m * m > CLUTTER_MAX * 0.9 * (s1all - s0all)) continue
            found.add(Cand(kind, dashed, phi, phiDeg, d, s0all, s1all, covered / 4.0, wd))
        }
    }

    // ───────────── 路緣 ─────────────

    private var mY = 0
    private var mU = 0
    private var mV = 0
    private var sY = 0
    private var sU = 0
    private var sV = 0
    private var modelOk = false

    private fun histMedian(hh: IntArray, offset: Int, cnt: Int): Int {
        val rank = cnt / 2
        var acc = 0
        for (v in hh.indices) {
            acc += hh[v]
            if (acc > rank) return v - offset
        }
        return hh.size - 1 - offset
    }

    private fun buildModel(w: Int, h: Int) {
        java.util.Arrays.fill(histY, 0)
        java.util.Arrays.fill(histU, 0)
        java.util.Arrays.fill(histV, 0)
        var cnt = 0
        for (j in 0 until h) {
            if (abs(zs[j]) > 2.4) continue
            for (i in 0 until w) {
                val ax = abs(xs[i])
                if (ax < 1.2 || ax > 1.95) continue
                val p = j * w + i
                if (flags[p].toInt() and 2 == 0 || cls[p].toInt() != 0) continue
                val c = rgb[p]
                val y = lum[p]
                histY[y]++
                histU[(c and 255) - y + 255]++
                histV[((c shr 16) and 255) - y + 255]++
                cnt++
            }
        }
        modelOk = cnt >= 50
        if (!modelOk) return
        mY = histMedian(histY, 0, cnt)
        mU = histMedian(histU, 255, cnt)
        mV = histMedian(histV, 255, cnt)
        sY = max(mad(histY, 0, mY, cnt), 6)
        sU = max(mad(histU, 255, mU, cnt), 3)
        sV = max(mad(histV, 255, mV, cnt), 3)
    }

    /** 顏色像車旁的路面（亮度 3·MAD+12、色度 3·MAD+10 以內）。 */
    private fun roadColor(p: Int): Boolean {
        val c = rgb[p]
        val y = lum[p]
        val u = (c and 255) - y
        val v = ((c shr 16) and 255) - y
        return abs(y - mY) <= 3 * sY + 12 && abs(u - mU) <= 3 * sU + 10 && abs(v - mV) <= 3 * sV + 10
    }

    private fun mad(hh: IntArray, offset: Int, med: Int, cnt: Int): Int {
        java.util.Arrays.fill(histD, 0)
        for (q in hh.indices) if (hh[q] > 0) histD[abs(q - offset - med)] += hh[q]
        return histMedian(histD, 0, cnt)
    }

    private fun edge(w: Int, h: Int, m: Double, ox: Double, oy: Double, side: Int): Double? {
        if (side < 0) buildModel(w, h)
        if (!modelOk) return null
        val need = ceil(0.3 / m - 1e-6).toInt()
        var nv = 0
        var scanned = 0
        for (q in 0 until EDGE_ROWS) {
            val z = -4.0 + 0.25 * q
            val j = floor(oy - z / m).toInt()
            if (j < 0 || j >= h) continue
            var i = floor(ox + side * 1.0 / m).toInt()
            var run = 0
            var first = -1
            var blocked = false
            var hit = false
            while (i in 0 until w) {
                val p = j * w + i
                val f = flags[p].toInt()
                if (f and 2 == 0) {
                    blocked = (f and 1) != 0
                    break
                }
                val isCand = cls[p].toInt() != 0
                if (!isCand && roadColor(p)) {
                    run = 0
                    first = -1
                } else if (isCand) {
                    if (first < 0) first = i
                } else {
                    if (first < 0) first = i
                    run++
                    if (run >= need) {
                        edgeVals[nv++] = if (side > 0) (first - ox) * m else (first + 1 - ox) * m
                        hit = true
                        break
                    }
                }
                i += side
            }
            if (hit || !blocked) scanned++
        }
        if (nv == 0) return null
        java.util.Arrays.sort(edgeVals, 0, nv)
        var bestN = 0
        var bestA = 0
        var b = 0
        for (a in 0 until nv) {
            while (b < nv && edgeVals[b] - edgeVals[a] <= 0.4 + 1e-9) b++
            if (b - a > bestN) { bestN = b - a; bestA = a }
        }
        if (bestN < EDGE_MIN_ROWS || bestN * 10 < scanned * 3) return null
        return edgeVals[bestA + bestN / 2]
    }
}

/**
 * 時間平滑：每次 extract 之後呼叫一次。線用（種類、角度、橫向位移、軸向範圍）追蹤，
 * 最近 5 次裡至少 2 次有對到才輸出，800 ms 沒對到就丟；路緣 EMA，1.5 s 沒值就變 null。
 */
class RoadTracker {
    private class Track(
        val kind: Char, var ang: Double, var off: Double, var sMin: Double, var sMax: Double,
        var conf: Double, var dashP: Double, var last: Long, var hist: Int
    )

    private val tracks = ArrayList<Track>()
    private var eL: Double? = null
    private var eR: Double? = null
    private var eLt = 0L
    private var eRt = 0L

    companion object {
        private const val ALPHA = 0.35
        private const val EDGE_ALPHA = 0.3
        private const val MATCH_DEG = 8.0
        private const val MATCH_OFF = 0.35
        private const val DROP_MS = 800L
        private const val EDGE_NULL_MS = 1500L
    }

    fun update(r: RoadResult, nowMs: Long): RoadResult {
        for (t in tracks) t.hist = (t.hist shl 1) and 0x1F
        val nOld = tracks.size          // 這次新開的軌不參與配對
        val used = BooleanArray(nOld)
        for (L in r.lines) {
            // 方向角 φ（+z 往 +x）、橫向位移 d、軸向 s
            var ang = Math.toDegrees(atan2((L.x2 - L.x1).toDouble(), (L.z2 - L.z1).toDouble()))
            if (ang >= 90.0) ang -= 180.0
            if (ang < -90.0) ang += 180.0
            val pr = Math.toRadians(ang)
            val c = cos(pr)
            val s = sin(pr)
            val off = L.x1 * c - L.z1 * s
            val sa = L.x1 * s + L.z1 * c
            val sb = L.x2 * s + L.z2 * c
            var bestI = -1
            var bestCost = Double.MAX_VALUE
            for (ti in 0 until nOld) {
                val t = tracks[ti]
                if (used[ti] || t.kind != L.kind) continue
                val da = RoadExtractor.wrap90(ang - t.ang)
                if (abs(da) >= MATCH_DEG) continue
                // ±90° 交界：同一條線的另一種表示法 (φ±180, -d)
                val flip = abs(ang - t.ang) > 90.0
                val o = if (flip) -off else off
                val dd = abs(o - t.off)
                if (dd >= MATCH_OFF) continue
                val cost = abs(da) / MATCH_DEG + dd / MATCH_OFF
                if (cost < bestCost) { bestCost = cost; bestI = ti }
            }
            if (bestI < 0) {
                tracks.add(Track(L.kind, ang, off, min(sa, sb), max(sa, sb), L.conf.toDouble(),
                    if (L.dashed) 1.0 else 0.0, nowMs, 1))
                continue
            }
            used[bestI] = true
            val t = tracks[bestI]
            val flip = abs(ang - t.ang) > 90.0
            val a2 = if (flip) (if (ang > t.ang) ang - 180.0 else ang + 180.0) else ang
            val o2 = if (flip) -off else off
            val lo = if (flip) -max(sa, sb) else min(sa, sb)
            val hi = if (flip) -min(sa, sb) else max(sa, sb)
            t.ang += ALPHA * (a2 - t.ang)
            t.off += ALPHA * (o2 - t.off)
            t.sMin += ALPHA * (lo - t.sMin)
            t.sMax += ALPHA * (hi - t.sMax)
            t.conf += ALPHA * (L.conf - t.conf)
            t.dashP += ALPHA * ((if (L.dashed) 1.0 else 0.0) - t.dashP)
            if (t.ang >= 90.0) { t.ang -= 180.0; flipTrack(t) } else if (t.ang < -90.0) { t.ang += 180.0; flipTrack(t) }
            t.last = nowMs
            t.hist = t.hist or 1
        }
        tracks.removeAll { nowMs - it.last > DROP_MS }

        r.edgeL?.let { v -> eL = eL?.let { it + EDGE_ALPHA * (v - it) } ?: v.toDouble(); eLt = nowMs }
        r.edgeR?.let { v -> eR = eR?.let { it + EDGE_ALPHA * (v - it) } ?: v.toDouble(); eRt = nowMs }
        if (eL != null && nowMs - eLt > EDGE_NULL_MS) eL = null
        if (eR != null && nowMs - eRt > EDGE_NULL_MS) eR = null

        val out = ArrayList<RoadLine>()
        for (t in tracks) {
            if (Integer.bitCount(t.hist) < 2) continue
            val pr = Math.toRadians(t.ang)
            val c = cos(pr)
            val s = sin(pr)
            out.add(
                RoadLine(
                    t.kind, t.dashP >= 0.5,
                    (t.off * c + t.sMin * s).toFloat(), (-t.off * s + t.sMin * c).toFloat(),
                    (t.off * c + t.sMax * s).toFloat(), (-t.off * s + t.sMax * c).toFloat(),
                    t.conf.toFloat()
                )
            )
        }
        return RoadResult(out, eL?.toFloat(), eR?.toFloat(), r.ms)
    }

    private fun flipTrack(t: Track) {
        t.off = -t.off
        val a = -t.sMax
        t.sMax = -t.sMin
        t.sMin = a
    }

    fun reset() {
        tracks.clear()
        eL = null
        eR = null
    }
}
