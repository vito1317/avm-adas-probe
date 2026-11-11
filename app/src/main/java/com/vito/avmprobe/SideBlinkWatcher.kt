package com.vito.avmprobe

import kotlin.math.hypot

/**
 * 周圍車輛（側邊、後方魚眼鏡頭看到的）有沒有在打方向燈（純邏輯，可單元測試）。
 *
 * 每次某顆鏡頭辨識到車，就給這台車的位置（車身座標）和車框裡亮琥珀色的像素數。
 * 用位置把同一台車串起來（[MATCH_M] 內），記最近 [WINDOW_MS] 的「亮／不亮」；
 * 亮、不亮各至少 [MIN_EACH] 次、而且有交替（至少 [MIN_TOGGLES] 次切換）→ 在打方向燈。
 * 每顆鏡頭一秒只輪到 1～3 次，方向燈一秒閃約 1.5 次：取樣跟閃爍同步時可能漏掉，所以要看幾秒。
 */
class SideBlinkWatcher {
    companion object {
        const val MATCH_M = 1.8f
        const val WINDOW_MS = 4000L
        const val MIN_EACH = 2
        const val MIN_TOGGLES = 3
        const val AMBER_MIN = 3
        const val AMBER_FRAC = 0.002f

        /** 一個像素是不是亮的方向燈琥珀色（跟 [LeadLightDetector] 同一套）。 */
        fun isAmber(c: Int): Boolean {
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            return r > 185 && g in 80..215 && b < 125 && r - g > 30 && g - b > 35 && g - b > r - g
        }
    }

    private class M(var x: Float, var z: Float, var seen: Long) {
        val samples = ArrayList<Pair<Long, Boolean>>()
    }

    private val list = ArrayList<M>()

    /** 自車移動（同 [SlotSelection.egoMotion]）：記住的位置跟著換算。 */
    fun egoMotion(dz: Float, dyaw: Float) {
        val c = kotlin.math.cos(dyaw); val s = kotlin.math.sin(dyaw)
        for (m in list) { val x = m.x; val z = m.z - dz; m.x = x * c - z * s; m.z = x * s + z * c }
    }

    /** 一筆觀測：車在 ([x], [z])，車框 [area] 個像素裡有 [amber] 個亮琥珀色。 */
    fun observe(now: Long, x: Float, z: Float, amber: Int, area: Int) {
        val on = amber >= AMBER_MIN && amber.toFloat() / area.coerceAtLeast(1) >= AMBER_FRAC
        val m = list.minByOrNull { hypot(it.x - x, it.z - z) }?.takeIf { hypot(it.x - x, it.z - z) < MATCH_M }
            ?: M(x, z, now).also { list.add(it) }
        m.x = x; m.z = z; m.seen = now
        m.samples.add(now to on)
        m.samples.removeAll { now - it.first > WINDOW_MS }
    }

    /** 目前在打方向燈的車（位置）。順便清掉太久沒看到的。 */
    fun blinking(now: Long): List<FloatArray> {
        list.removeAll { now - it.seen > WINDOW_MS }
        return list.filter { m ->
            val s = m.samples
            val on = s.count { it.second }; val off = s.size - on
            var tog = 0
            for (i in 1 until s.size) if (s[i].second != s[i - 1].second) tog++
            on >= MIN_EACH && off >= MIN_EACH && tog >= MIN_TOGGLES
        }.map { floatArrayOf(it.x, it.z) }
    }
}
