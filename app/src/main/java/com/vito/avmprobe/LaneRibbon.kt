package com.vito.avmprobe

import android.os.SystemClock
import org.json.JSONObject
import kotlin.math.abs

/**
 * 360 畫面上的「前方道路」：自己車道的左右線往前延伸成一條車道帶（像原廠的行駛軌跡）。
 *
 * 從每幀偵測到的白／黃線（payload "road"）裡，挑車子左邊、右邊最近的那條（0.4～2.6 m）當自己車道的邊線；
 * 只找到一邊 → 另一邊用車道寬（兩邊都看到時量到的，平均；沒量過用 3.4 m）推出來，畫成淡的虛線。
 * 位置、方向用指數平均（不會一幀一跳）；短暫沒看到線撐 [HOLD_MS]，太久沒看到就不畫。
 * 往前延伸到 [AHEAD_M]（照線的方向直線延伸；偵測範圍只有前後約 7 m，曲率外推不可靠就不用）。
 * 只用來顯示，不拿去控制車子。
 */
class LaneRibbon {
    companion object {
        const val AHEAD_M = 20f
        const val BEHIND_M = -2.5f
        const val HOLD_MS = 1500L
        const val ALPHA = 0.35f
        const val NEAR = 0.4; const val FAR = 2.6
        const val W_MIN = 2.4; const val W_MAX = 4.6
    }

    data class Side(val kind: Char, val dashed: Boolean)
    /** [left]、[right]：(x, z) 點列（車身座標 m）；[leftLine]／[rightLine] = null 表示那側是推出來的。 */
    class Lane(val left: List<FloatArray>, val right: List<FloatArray>, val leftLine: Side?, val rightLine: Side?)

    private var xl = Float.NaN; private var xr = Float.NaN; private var slope = 0f; private var width = 3.4f
    private var sideL: Side? = null; private var sideR: Side? = null
    private var seenAt = 0L
    @Volatile private var cur: Lane? = null

    fun current(): Lane? = currentAt(SystemClock.elapsedRealtime())
    private fun currentAt(now: Long): Lane? = cur?.takeIf { now - seenAt < HOLD_MS }

    fun update(p: JSONObject?): Lane? = update(p, SystemClock.elapsedRealtime())

    internal fun update(p: JSONObject?, now: Long): Lane? {
        val a = p?.optJSONObject("road")?.optJSONArray("lines")
        var bestL: DoubleArray? = null; var bestR: DoubleArray? = null; var kL: Side? = null; var kR: Side? = null
        if (a != null) for (i in 0 until a.length()) {
            val l = a.optJSONObject(i) ?: continue
            val k = l.optString("k").firstOrNull() ?: continue
            if (k == 'r') continue
            val x1 = l.optDouble("x1"); val z1 = l.optDouble("z1"); val x2 = l.optDouble("x2"); val z2 = l.optDouble("z2")
            if (abs(z2 - z1) < 1.0) continue                         // 橫的（停止線之類）
            val sl = (x2 - x1) / (z2 - z1)
            if (abs(sl) > 0.35) continue                              // 跟車頭方向差太多（> 約 20°）
            val x0 = x1 + sl * (0 - z1)
            val side = Side(k, l.optInt("d") == 1)
            if (x0 in -FAR..-NEAR && (bestL == null || x0 > bestL[0])) { bestL = doubleArrayOf(x0, sl); kL = side }
            if (x0 in NEAR..FAR && (bestR == null || x0 < bestR[0])) { bestR = doubleArrayOf(x0, sl); kR = side }
        }
        if (bestL != null && bestR != null) {
            val wv = bestR[0] - bestL[0]
            if (wv in W_MIN..W_MAX) width += ALPHA * (wv.toFloat() - width)
            else { bestR = null; kR = null }                          // 寬度不合理：只信左邊（台灣：左邊多是實線、比較穩）
        }
        if (bestL == null && bestR == null) {
            if (now - seenAt >= HOLD_MS) { cur = null; xl = Float.NaN; xr = Float.NaN }
            return currentAt(now)
        }
        val tl = bestL?.get(0)?.toFloat() ?: (bestR!![0].toFloat() - width)
        val tr = bestR?.get(0)?.toFloat() ?: (bestL!![0].toFloat() + width)
        val ts = listOfNotNull(bestL?.get(1), bestR?.get(1)).average().toFloat()
        if (xl.isNaN() || now - seenAt >= HOLD_MS) { xl = tl; xr = tr; slope = ts }
        else { xl += ALPHA * (tl - xl); xr += ALPHA * (tr - xr); slope += ALPHA * (ts - slope) }
        sideL = kL; sideR = kR; seenAt = now
        val left = ArrayList<FloatArray>(); val right = ArrayList<FloatArray>()
        val n = (AHEAD_M - BEHIND_M).toInt()            // 每 1 m 一點，最後一點剛好在 AHEAD_M
        for (q in 0..n) {
            val z = BEHIND_M + (AHEAD_M - BEHIND_M) * q / n
            left.add(floatArrayOf(xl + slope * z, z)); right.add(floatArrayOf(xr + slope * z, z))
        }
        return Lane(left, right, sideL, sideR).also { cur = it }
    }
}
