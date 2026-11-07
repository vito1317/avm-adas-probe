package com.vito.avmprobe

import kotlin.math.abs
import kotlin.math.atan

/**
 * 從前方去畸變畫面量車子的轉向角速度：遠處景物（大樓、樹、天際線）車子往前開時幾乎不動，
 * 只有轉向時才整片左右平移。比較前後兩幀「地平線附近那條帶狀區域」的橫向平移量 → 轉了幾度。
 * 只用方向、不量距離，所以不受前鏡頭高度校正不準影響（GPS 一秒一筆又會亂跳，實車不準）。
 *
 * 做法：帶狀區域每一欄的亮度梯度平均（對亮度變化不敏感）→ 跟上一幀做一維相關，找最佳平移（拋物線內插到次像素）。
 * 相關性太低（畫面一片平、晚上）就不給。純邏輯，可單元測試。
 */
class VisualYaw {
    companion object {
        const val BAND_TOP = 0.28f
        const val BAND_BOTTOM = 0.47f
        const val MAX_SHIFT = 24
        const val MIN_CORR = 0.55
    }

    private var prev: DoubleArray? = null
    private var prevAt = 0L
    /** 轉向角速度（rad/s，+ = 往右），EMA。 */
    var rate = 0.0; private set
    var rateAt = 0L; private set

    /** [argb] 是 size×size 的前方畫面，[focal] 是它的焦距（像素）。 */
    fun update(now: Long, argb: IntArray, size: Int, focal: Double) {
        val y0 = (size * BAND_TOP).toInt(); val y1 = (size * BAND_BOTTOM).toInt()
        val prof = DoubleArray(size)
        for (y in y0 until y1) {
            var last = lum(argb[y * size])
            for (x in 1 until size) {
                val l = lum(argb[y * size + x])
                prof[x] += abs(l - last).toDouble().coerceAtMost(60.0)   // 梯度強度，截掉極端值
                last = l
            }
        }
        val p = prev
        val dt = (now - prevAt) / 1000.0
        prev = prof; prevAt = now
        if (p == null || dt <= 0.02 || dt > 0.6) return
        // 一維正規化相關：這一幀往左移 s 個像素 = 景物往左 = 車子往右轉（s > 0）
        var best = Double.NEGATIVE_INFINITY; var bestS = 0
        val corr = DoubleArray(2 * MAX_SHIFT + 1)
        for (s in -MAX_SHIFT..MAX_SHIFT) {
            var sab = 0.0; var saa = 0.0; var sbb = 0.0; var sa = 0.0; var sb = 0.0; var n = 0
            for (x in MAX_SHIFT until size - MAX_SHIFT) {
                val a = p[x]; val b = prof[x - s]
                sab += a * b; saa += a * a; sbb += b * b; sa += a; sb += b; n++
            }
            val cov = sab - sa * sb / n; val va = saa - sa * sa / n; val vb = sbb - sb * sb / n
            val c = if (va > 1e-6 && vb > 1e-6) cov / Math.sqrt(va * vb) else 0.0
            corr[s + MAX_SHIFT] = c
            if (c > best) { best = c; bestS = s }
        }
        if (best < MIN_CORR || abs(bestS) == MAX_SHIFT) return
        // 次像素：拋物線
        val i = bestS + MAX_SHIFT
        val cm = corr[i - 1]; val c0 = corr[i]; val cp = corr[i + 1]
        val den = cm - 2 * c0 + cp
        val sub = if (abs(den) > 1e-9) 0.5 * (cm - cp) / den else 0.0
        val shift = bestS + sub
        val r = atan(shift / focal) / dt
        rate += 0.35 * (r - rate)
        rateAt = now
    }

    private fun lum(c: Int): Int = (((c shr 16) and 255) * 3 + ((c shr 8) and 255) * 6 + (c and 255)) / 10
}
