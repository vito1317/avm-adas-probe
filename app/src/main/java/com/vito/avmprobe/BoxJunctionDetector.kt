package com.vito.avmprobe

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 黃色網狀線（路口禁止臨時停車區）：俯視圖上一大塊黃漆，裡面的線有兩組交叉的斜向
 * （跟一般黃線、雙黃線只有一個方向分得開）。純 Kotlin，可單元測試。
 *
 * 1. 黃漆遮罩（跟 RoadExtractor 的黃線顏色條件一樣）。
 * 2. 8 連通元件（網狀線整片連在一起）：外框夠大（[MIN_BOX_M2]）、黃漆夠多但不是整片塗滿（[DENSITY_MIN]～[DENSITY_MAX]）。
 * 3. 元件裡的線的方向（遮罩邊緣的梯度方向，0～180° 分 12 格）：要有兩個明顯的方向、相差 50°～130°，
 *    而且都不是順著車道（跟縱向夾角 > [DIAG_MIN_DEG]）—— 單條黃線、雙黃線只有縱向一個方向。
 * 座標同 [CrosswalkDetector]：x 右、z 前（m）。
 */
class BoxJunctionDetector {
    companion object {
        const val MIN_BOX_M2 = 4.0
        const val MIN_SIDE_M = 1.5
        const val DENSITY_MIN = 0.06
        const val DENSITY_MAX = 0.55
        const val PEAK_FRAC = 0.18
        const val DIAG_MIN_DEG = 20.0
        private const val BINS = 12

        fun isYellow(c: Int): Boolean {
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            return r >= 90 && g >= 70 && r - b >= 45 && g - b >= 30 && abs(r - g) <= 80
        }
    }

    /** 一塊網狀線：外框四角 (x,z)×4。 */
    data class Box(val p: FloatArray) {
        override fun equals(other: Any?) = other is Box && p.contentEquals(other.p)
        override fun hashCode() = p.contentHashCode()
        fun contains(x: Float, z: Float) = x in p[0]..p[2] && z in minOf(p[1], p[5])..maxOf(p[1], p[5])
    }

    fun detect(argb: IntArray, w: Int, h: Int, mpp: Float, cx: Float, cy: Float, valid: BooleanArray?, exclude: List<FloatArray>): List<Box> {
        val n = w * h; val m = mpp.toDouble()
        val yel = BooleanArray(n)
        for (j in 0 until h) for (i in 0 until w) {
            val p = j * w + i
            if (valid != null && !valid[p]) continue
            if (!isYellow(argb[p])) continue
            val x = (i + 0.5 - cx) * m; val z = (cy - j - 0.5) * m
            if (exclude.any { x >= it[0] && x <= it[2] && z >= it[1] && z <= it[3] }) continue
            yel[p] = true
        }
        val lab = IntArray(n); val stack = IntArray(n); var next = 0
        val out = ArrayList<Box>()
        for (s0 in 0 until n) {
            if (!yel[s0] || lab[s0] != 0) continue
            next++
            var sp = 0; stack[sp++] = s0; lab[s0] = next
            var cnt = 0; var i0 = w; var i1 = -1; var j0 = h; var j1 = -1
            val hist = DoubleArray(BINS)
            while (sp > 0) {
                val p = stack[--sp]; val i = p % w; val j = p / w
                cnt++; i0 = min(i0, i); i1 = max(i1, i); j0 = min(j0, j); j1 = max(j1, j)
                // 邊緣方向：遮罩的梯度（3x3 Sobel），只算邊上的像素
                if (i in 1 until w - 1 && j in 1 until h - 1) {
                    fun v(a: Int, b: Int) = if (yel[(j + b) * w + i + a]) 1 else 0
                    val gx = (v(1, -1) + 2 * v(1, 0) + v(1, 1)) - (v(-1, -1) + 2 * v(-1, 0) + v(-1, 1))
                    val gz = (v(-1, 1) + 2 * v(0, 1) + v(1, 1)) - (v(-1, -1) + 2 * v(0, -1) + v(1, -1))
                    val mag = hypot(gx.toDouble(), gz.toDouble())
                    if (mag > 0) {
                        // 線的方向 = 梯度轉 90°；0° = 縱向（沿 z）
                        var a = Math.toDegrees(atan2(gx.toDouble(), -gz.toDouble())) + 90.0
                        a = ((a % 180) + 180) % 180
                        hist[((a / (180.0 / BINS)).toInt()).coerceIn(0, BINS - 1)] += mag
                    }
                }
                for (dj in -1..1) for (di in -1..1) {
                    if (di == 0 && dj == 0) continue
                    val ii = i + di; val jj = j + dj
                    if (ii < 0 || jj < 0 || ii >= w || jj >= h) continue
                    val q = jj * w + ii
                    if (yel[q] && lab[q] == 0) { lab[q] = next; stack[sp++] = q }
                }
            }
            val bwM = (i1 - i0 + 1) * m; val bhM = (j1 - j0 + 1) * m
            if (bwM < MIN_SIDE_M || bhM < MIN_SIDE_M || bwM * bhM < MIN_BOX_M2) continue
            val dens = cnt * m * m / (bwM * bhM)
            if (dens < DENSITY_MIN || dens > DENSITY_MAX) continue
            if (!twoDiagonals(hist)) continue
            val x0 = ((i0 - cx) * m).toFloat(); val x1 = ((i1 + 1 - cx) * m).toFloat()
            val z0 = ((cy - (j1 + 1)) * m).toFloat(); val z1 = ((cy - j0) * m).toFloat()
            out.add(Box(floatArrayOf(x0, z0, x1, z0, x1, z1, x0, z1)))
        }
        return out
    }

    /** 方向直方圖有兩個明顯的峰、相差 50°～130°，而且都不是縱向／橫向。 */
    private fun twoDiagonals(hist: DoubleArray): Boolean {
        val tot = hist.sum(); if (tot <= 0) return false
        val step = 180.0 / BINS
        // 相鄰兩格合併算峰（線的方向常落在兩格中間）
        val sm = DoubleArray(BINS) { hist[it] + hist[(it + 1) % BINS] }
        val a = sm.indices.maxByOrNull { sm[it] }!!
        var b = -1
        for (k in sm.indices) {
            val d = minOf(abs(k - a), BINS - abs(k - a)) * step
            if (d < 50.0) continue
            if (b < 0 || sm[k] > sm[b]) b = k
        }
        if (b < 0 || sm[a] < PEAK_FRAC * tot || sm[b] < PEAK_FRAC * tot) return false
        fun center(k: Int) = (k + 1) * step                       // 合併兩格的中心角度
        fun diag(k: Int): Boolean { val c = center(k) % 180; val off = minOf(c, abs(c - 90), 180 - c); return off > DIAG_MIN_DEG }
        val sep = minOf(abs(a - b), BINS - abs(a - b)) * step
        return sep in 50.0..130.0 && diag(a) && diag(b)
    }
}
