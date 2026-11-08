package com.vito.avmprobe

import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 用俯視圖量車速：車子左右兩側那條地面（側鏡頭拍得最準的地方）前後兩幀往後移了多少 → 車速。
 * 這台車沒有輪速：原廠的車速也是車機轉來的 GPS（使用者：GPS 超級不準；停車時還有 2～3 km/h 的雜訊）。
 *
 * 做法：左右各一條（車身外 1.3～2.6 m、前後 ±3 m），每一列平均亮度做帶通成「沿前後方向的剖面」，
 * 跟上一幀做一維正規化相關找位移（次像素）；左右兩條結果要一致才採用（旁邊開過的車只會影響一邊）。
 * 一幀最多比對 [MAX_SHIFT_M]，再快就交回 GPS。純計算在 [Core]，可單元測試。
 */
object VisualSpeed {

    class Core {
        companion object {
            const val X_IN = 1.3f
            const val X_OUT = 2.6f
            const val Z_HALF = 3.0f
            const val MAX_SHIFT_M = 1.6f
            const val MIN_CORR = 0.6
            const val AGREE_MPS = 1.0
        }

        private var prevL: DoubleArray? = null
        private var prevR: DoubleArray? = null
        private var prevAt = 0L
        /** m/s，+ = 前進；EMA。 */
        var speed = 0.0; private set
        var at = 0L; private set

        fun update(now: Long, argb: IntArray, w: Int, h: Int, mpp: Float, ox: Float, oy: Float) {
            val l = profile(argb, w, h, mpp, ox, oy, -X_OUT, -X_IN)
            val r = profile(argb, w, h, mpp, ox, oy, X_IN, X_OUT)
            val pl = prevL; val pr = prevR
            val dt = (now - prevAt) / 1000.0
            prevL = l; prevR = r; prevAt = now
            if (pl == null || pr == null || dt <= 0.03 || dt > 0.5) return
            val maxS = (MAX_SHIFT_M / mpp).toInt()
            val sl = shift(pl, l, maxS) ?: return
            val sr = shift(pr, r, maxS) ?: return
            // 地面往後退（剖面往影像下方移，row 變大）= 前進
            val vl = sl * mpp / dt; val vr = sr * mpp / dt
            if (abs(vl - vr) > AGREE_MPS + 0.15 * abs(vl + vr) / 2) return
            val v = (vl + vr) / 2
            speed += 0.4 * (v - speed)
            at = now
        }

        /** 沿前後方向（每一列）的亮度梯度剖面：只看 x 在 [x0, x1]、z 在 ±Z_HALF 的那條。 */
        private fun profile(argb: IntArray, w: Int, h: Int, mpp: Float, ox: Float, oy: Float, x0: Float, x1: Float): DoubleArray {
            val i0 = (ox + x0 / mpp).toInt().coerceIn(0, w - 1); val i1 = (ox + x1 / mpp).toInt().coerceIn(i0 + 1, w)
            val j0 = (oy - Z_HALF / mpp).toInt().coerceIn(1, h - 1); val j1 = (oy + Z_HALF / mpp).toInt().coerceIn(j0 + 1, h)
            // 每一列的平均亮度，再扣掉前後約 0.6 m 的平均（帶通）：保留粗細紋理與亮暗正負，
            // 不用梯度絕對值（那會把紋理變成很細的重複圖案，相關會對錯一個週期）
            val n = j1 - j0
            val raw = DoubleArray(n)
            for (j in j0 until j1) {
                var s = 0.0
                for (i in i0 until i1) s += lum(argb[j * w + i])
                raw[j - j0] = s / (i1 - i0)
            }
            val half = (0.3f / mpp).toInt().coerceAtLeast(2)
            val out = DoubleArray(n)
            for (k in 0 until n) {
                var s = 0.0; var c = 0
                for (q in maxOf(0, k - half)..minOf(n - 1, k + half)) { s += raw[q]; c++ }
                out[k] = raw[k] - s / c
            }
            return out
        }

        /** 這一幀相對上一幀往下（row 變大）移了多少像素；相關性不夠回 null。 */
        private fun shift(prev: DoubleArray, cur: DoubleArray, maxS: Int): Double? {
            val n = minOf(prev.size, cur.size); if (n < 3 * maxS) return null
            val corr = DoubleArray(2 * maxS + 1)
            var best = Double.NEGATIVE_INFINITY; var bestS = 0
            for (s in -maxS..maxS) {
                var sab = 0.0; var saa = 0.0; var sbb = 0.0; var sa = 0.0; var sb = 0.0; var m = 0
                for (j in maxS until n - maxS) {
                    val a = prev[j]; val b = cur[j + s]
                    sab += a * b; saa += a * a; sbb += b * b; sa += a; sb += b; m++
                }
                val cov = sab - sa * sb / m; val va = saa - sa * sa / m; val vb = sbb - sb * sb / m
                val c = if (va > 1e-6 && vb > 1e-6) cov / sqrt(va * vb) else 0.0
                corr[s + maxS] = c
                if (c > best) { best = c; bestS = s }
            }
            if (best < MIN_CORR || abs(bestS) == maxS) return null
            // 第二高的峰（離最佳 3 px 以上）也差不多高 → 紋理有週期性（磚、格線），位移不可靠
            var second = Double.NEGATIVE_INFINITY
            for (k in corr.indices) if (abs(k - maxS - bestS) >= 3) second = maxOf(second, corr[k])
            if (second > best - 0.12) return null
            val i = bestS + maxS
            val den = corr[i - 1] - 2 * corr[i] + corr[i + 1]
            return bestS + if (abs(den) > 1e-9) 0.5 * (corr[i - 1] - corr[i + 1]) / den else 0.0
        }

        private fun lum(c: Int): Int = (((c shr 16) and 255) * 3 + ((c shr 8) and 255) * 6 + (c and 255)) / 10
    }

    private val core = Core()

    fun update(argb: IntArray, w: Int, h: Int, mpp: Float, ox: Float, oy: Float) =
        synchronized(core) { core.update(SystemClock.elapsedRealtime(), argb, w, h, mpp, ox, oy) }

    /** 最近 0.8 秒內量到的車速（km/h，≥ 0）；量不到回 null（交給 GPS）。 */
    fun kmh(): Float? = synchronized(core) {
        if (SystemClock.elapsedRealtime() - core.at > 800) null else (abs(core.speed) * 3.6).toFloat()
    }
}
