package com.vito.avmprobe

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 紅綠燈燈色（偵測框裁切 → "red" / "yellow" / "green" / "off" + 信心）。純 Kotlin、全整數判斷，
 * 跟 tools/perception_proto.py 的 tl_classify 一致。
 *
 * 只看框的中間（短軸兩側各去 15%、長軸兩端各去 5%，框邊多半是天空或樹）；
 * 亮（V ≥ max(140, 98 百分位的 60%)）又飽和（≥ 35%）的像素依色相分三帶加權（V×彩度）：
 * 紅 <15° 或 >340°、黃 25–65°、綠 90–200°（LED 綠常偏青）。前兩名太接近時看亮點在長軸的位置
 * （直式紅上綠下、橫式紅左綠右）。整個框都暗 → "off"，暗燈箱不會被當成紅燈。
 */
object TrafficLightState {
    private const val V_MIN = 140
    private const val V_REL_PCT = 60
    private const val S_MIN_PCT = 35
    private const val DARK_V = 90
    private const val MIN_PERMIL = 12
    private const val TIE_PCT = 60
    private const val SIDE_PCT = 15
    private const val END_PCT = 5
    private val STATES = arrayOf("red", "yellow", "green")

    /** 色相帶：0 紅、1 黃、2 綠、-1 都不是。 */
    internal fun band(r: Int, g: Int, b: Int): Int {
        val mx = max(r, max(g, b))
        val c = mx - min(r, min(g, b))
        val rmax = r >= g && r >= b
        val gmax = !rmax && g >= b
        val bmax = !rmax && !gmax
        if (rmax && ((g >= b && 4 * (g - b) < c) || (b > g && 3 * (b - g) < c))) return 0
        if ((rmax && g >= b && 12 * (g - b) >= 5 * c) || (gmax && r >= b && 12 * (r - b) >= 11 * c)) return 1
        if ((gmax && (b >= r || 2 * (r - b) <= c)) || (bmax && g > r && 3 * (g - r) >= 2 * c)) return 2
        return -1
    }

    /** crop of one detected traffic-light box. Returns state "red"|"yellow"|"green"|"off" and confidence 0..1. */
    fun classify(argb: IntArray, w: Int, h: Int): Pair<String, Float> {
        val vert = h >= w
        val a0 = (if (vert) w else h) * SIDE_PCT / 100
        val b0 = (if (vert) h else w) * END_PCT / 100
        val i0 = if (vert) a0 else b0
        val i1 = if (vert) w - a0 else w - b0
        val j0 = if (vert) b0 else a0
        val j1 = if (vert) h - b0 else h - a0
        if (i1 <= i0 || j1 <= j0) return "off" to 0.5f
        val histV = IntArray(256)
        var nRoi = 0
        for (j in j0 until j1) for (i in i0 until i1) {
            val c = argb[j * w + i]
            histV[max((c shr 16) and 255, max((c shr 8) and 255, c and 255))]++
            nRoi++
        }
        // 98 百分位亮度（排序後第 n*98/100 個）
        val rank = nRoi * 98 / 100
        var accN = 0
        var vtop = 255
        for (v in 0..255) {
            accN += histV[v]
            if (accN > rank) { vtop = v; break }
        }
        if (vtop < DARK_V) return "off" to 0.9f
        val vthr = max(V_MIN, vtop * V_REL_PCT / 100)
        val score = LongArray(3)
        val npx = IntArray(3)
        val posSum = DoubleArray(3)
        for (j in j0 until j1) for (i in i0 until i1) {
            val c = argb[j * w + i]
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val b = c and 255
            val mx = max(r, max(g, b))
            val ch = mx - min(r, min(g, b))
            if (mx < vthr || 100 * ch < S_MIN_PCT * mx) continue
            val k = band(r, g, b)
            if (k < 0) continue
            score[k] += (mx * ch).toLong()
            npx[k]++
            posSum[k] += (if (vert) j else i) + 0.5
        }
        val tot = score[0] + score[1] + score[2]
        if (tot <= 0 || max(npx[0], max(npx[1], npx[2])) < max(2, nRoi * MIN_PERMIL / 1000)) return "off" to 0.6f
        // 依分數由大到小（同分取前面的）
        val order = intArrayOf(0, 1, 2).sortedWith(compareBy<Int>({ -score[it] }, { it }))
        var best = order[0]
        val second = order[1]
        if (100 * score[second] > TIE_PCT * score[best]) {
            val len = (if (vert) h else w).toDouble()
            val p = (posSum[best] + posSum[second]) / max(1, npx[best] + npx[second]) / len
            val exp = doubleArrayOf(1.0 / 6, 0.5, 5.0 / 6)
            val db = abs(p - exp[best])
            val ds = abs(p - exp[second])
            best = if (ds < db || (ds == db && second < best)) second else best
        }
        return STATES[best] to (score[best].toDouble() / tot).toFloat()
    }
}
