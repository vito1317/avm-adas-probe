package com.vito.avmprobe

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

/**
 * 車道線虛／實（tools/lane_label/line_dash_ds.py 取特徵、邏輯迴歸）。
 *
 * 以前：最長一段 ≥ 5 m 才算實線、看到 ≥ 2 段就算虛線 → 白實線有 30% 被判成虛線（線被車子擋住、陰影、磨損、接縫切斷就變好幾段）。
 * 現在：沿線兩條剖面（每 0.04 m）：
 *   - 模型剖面：線左右 ±0.12 m 內車道線機率 ≥ 0.5；
 *   - 亮度剖面：線上（±0.08 m 最亮）比旁邊柏油（±0.30、±0.45 m 中位數）亮 max(14, 0.18×柏油亮度) 以上 —
 *     模型常把虛線中間的空隙也畫成線，空隙要看亮度才準；
 *   各算 12 個特徵（看得到的長度、有漆長度、最長一段、段數、≥0.8 m 的空隙數…）→ 邏輯迴歸。
 * 第二版（加第五輪新路段）：在完全沒看過的新路段上 78%（舊規則同一批約 60%）。
 * 黃線一律實線（台灣黃虛線極少：審過的 704 條黃線只有 3 條虛線）。
 * 3035 條審過的線、依路段切開驗證：整體 77% → 88%（實線認對 70% → 89%、虛線 87% → 86%；晚上 85%、白天 93%）。
 * 純邏輯，可單元測試。
 */
object LineDash {
    private const val STEP = 0.04
    private const val OBS_M = 5.0
    private val CAMS = arrayOf(doubleArrayOf(0.0, 2.42), doubleArrayOf(0.0, -2.42), doubleArrayOf(-0.93, 0.9), doubleArrayOf(0.93, 0.9))

    // 模型剖面 12 ＋ 亮度剖面 12 ＋ 亮度剖面可用（c_ok）
    private val MU_F = doubleArrayOf(13.7407, 9.03675, 0.655363, 8.46536, 1.41774, 0.219039, 0.484621, 7.53998, 0.430148, 0.0545735, 13.8036, 0.612659, 13.6707, 6.30551, 0.457609, 3.7451, 2.96396, 1.05314, 1.76636, 2.3253, 0.840425, 0.314085, 13.7335, 0.271431, 0.993993)
    private val SD_F = doubleArrayOf(0.787177, 2.8074, 0.198953, 2.97624, 0.719533, 0.470074, 1.07585, 3.51607, 0.976109, 0.120123, 0.593482, 0.213733, 1.28926, 3.11073, 0.224142, 1.93444, 1.68323, 0.996967, 1.5265, 1.46916, 1.08035, 0.227615, 1.18267, 0.141266, 0.0772752)
    private val W_F = doubleArrayOf(-0.184733, 1.69255, -1.01895, 1.08536, -0.346713, 0.181832, 0.428002, -0.459339, -0.177052, -0.211604, 0.0622074, -1.27602, 0.402483, -0.599248, -0.636446, -0.437654, -0.268271, 0.545829, 0.44372, -0.0817429, -0.103146, -0.767893, 0.445126, 0.0742355, -0.540644)
    private const val B_F = 0.64445947
    // 沒有車道線模型時（亮度規則模式）：只用亮度剖面
    private val MU_C = doubleArrayOf(13.6855, 6.36215, 0.461624, 3.76856, 2.97436, 1.05621, 1.77544, 2.33704, 0.835779, 0.312991, 13.7501, 0.273134, 0.995069)
    private val SD_C = doubleArrayOf(1.21239, 3.10946, 0.223783, 1.92052, 1.66775, 0.99842, 1.52991, 1.46424, 1.07727, 0.226375, 1.09425, 0.140319, 0.0700485)
    private val W_C = doubleArrayOf(0.397691, -0.663579, -0.449062, -0.621299, -0.343047, 0.578375, 0.459203, -0.126933, -0.117028, -0.754549, 0.811452, 0.208382, -0.80528)
    private const val B_C = 0.69923697

    /**
     * 是虛線的機率；算不出來（看得到的太短、幾乎沒漆）回 null。
     * 線：點(u) = (off·cos φ + u·sin φ, −off·sin φ + u·cos φ)；[rgb] 俯視圖（0xRRGGBB，0 = 沒畫到）、[paint] 車道線機率（可為 null）。
     */
    fun dashedProb(rgb: IntArray, paint: FloatArray?, w: Int, h: Int, m: Double, ox: Double, oy: Double, phi: Double, off: Double): Double? {
        val c = kotlin.math.cos(phi); val s = kotlin.math.sin(phi)
        val stC = profileRgb(rgb, w, h, m, ox, oy, c, s, off)
        val fc = feats(stC)
        if (paint == null) {
            val f = fc ?: return null
            return lr(f + 1.0, MU_C, SD_C, W_C, B_C)
        }
        val fm = feats(profileModel(rgb, paint, w, h, m, ox, oy, c, s, off)) ?: return null
        return lr(fm + (fc ?: DoubleArray(12)) + (if (fc != null) 1.0 else 0.0), MU_F, SD_F, W_F, B_F)
    }

    private fun lr(f: DoubleArray, mu: DoubleArray, sd: DoubleArray, wt: DoubleArray, b: Double): Double {
        var z = b
        for (i in f.indices) z += wt[i] * (f[i] - mu[i]) / sd[i]
        return 1.0 / (1.0 + exp(-z))
    }

    private fun observable(x: Double, z: Double) = CAMS.any { hypot(x - it[0], z - it[1]) <= OBS_M }

    private fun idx(w: Int, h: Int, m: Double, ox: Double, oy: Double, x: Double, z: Double): Int {
        val i = floor(x / m + ox).toInt(); val j = floor(oy - z / m).toInt()
        return if (i in 0 until w && j in 0 until h) j * w + i else -1
    }

    /** 每步：1 有漆、0 沒漆、-1 看不到（u 從 −7.5 到 7.5 m）。 */
    internal fun profileModel(rgb: IntArray, paint: FloatArray, w: Int, h: Int, m: Double, ox: Double, oy: Double, c: Double, s: Double, off: Double): IntArray {
        val out = ArrayList<Int>()
        var u = -7.5
        while (u <= 7.5) {
            val x = off * c + u * s; val z = -off * s + u * c; u += STEP
            if (!observable(x, z)) { out.add(-1); continue }
            var best = -1f; var seen = false
            for (k in -3..3) {
                val o = k * 0.04
                val p = idx(w, h, m, ox, oy, x + o * c, z - o * s)
                if (p >= 0 && rgb[p] != 0) { seen = true; best = max(best, paint[p]) }
            }
            out.add(if (!seen) -1 else if (best >= 0.5f) 1 else 0)
        }
        return out.toIntArray()
    }

    internal fun profileRgb(rgb: IntArray, w: Int, h: Int, m: Double, ox: Double, oy: Double, c: Double, s: Double, off: Double): IntArray {
        fun lum(p: Int): Double { val v = rgb[p]; return 0.299 * ((v shr 16) and 255) + 0.587 * ((v shr 8) and 255) + 0.114 * (v and 255) }
        val out = ArrayList<Int>(); val bg = DoubleArray(4)
        var u = -7.5
        while (u <= 7.5) {
            val x = off * c + u * s; val z = -off * s + u * c; u += STEP
            if (!observable(x, z)) { out.add(-1); continue }
            var onMax = -1.0; var nOn = 0
            for (k in -2..2) {
                val o = k * 0.04; val p = idx(w, h, m, ox, oy, x + o * c, z - o * s)
                if (p >= 0 && rgb[p] != 0) { onMax = max(onMax, lum(p)); nOn++ }
            }
            var nb = 0
            for (o in doubleArrayOf(0.30, 0.45, -0.30, -0.45)) {
                val p = idx(w, h, m, ox, oy, x + o * c, z - o * s)
                if (p >= 0 && rgb[p] != 0) bg[nb++] = lum(p)
            }
            if (nOn == 0 || nb == 0) { out.add(-1); continue }
            val b = median(bg.copyOf(nb))
            out.add(if (onMax - b > max(14.0, 0.18 * b)) 1 else 0)
        }
        return out.toIntArray()
    }

    /** 跟 line_dash_ds.feats 一樣的 12 個特徵；看得到 < 1 m 或有漆 < 0.3 m → null。 */
    internal fun feats(st: IntArray): DoubleArray? {
        val nObs = st.count { it >= 0 }; val nPaint = st.count { it == 1 }
        if (nObs * STEP < 1.0 || nPaint * STEP < 0.3) return null
        val first = st.indexOfFirst { it >= 0 }; val last = st.indexOfLast { it >= 0 }
        val span = (last - first + 1) * STEP
        val runs = ArrayList<Double>(); val gaps = ArrayList<Double>()
        var cur = 0.0; var gap = 0.0; var started = false
        for (q in first..last) {
            when (st[q]) {
                1 -> { if (gap > 0 && started) gaps.add(gap); gap = 0.0; cur += STEP; started = true }
                0 -> { if (cur > 0) { runs.add(cur); cur = 0.0 }; if (started) gap += STEP }
            }
        }
        if (cur > 0) runs.add(cur)
        val r = runs.filter { it >= 0.2 }; val g = gaps.filter { it >= 0.2 }
        val paint = nPaint * STEP; val obs = nObs * STEP
        val longest = r.maxOrNull() ?: 0.0
        return doubleArrayOf(obs, paint, paint / obs, longest, r.size.toDouble(), g.count { it >= 0.8 }.toDouble(),
            g.maxOrNull() ?: 0.0, if (r.isEmpty()) 0.0 else r.average(), g.firstOrNull() ?: 0.0,
            g.sum() / max(1e-6, g.sum() + paint), span, longest / max(0.2, span))
    }

    private fun median(a: DoubleArray): Double { a.sort(); return if (a.size % 2 == 1) a[a.size / 2] else (a[a.size / 2 - 1] + a[a.size / 2]) / 2 }
}
