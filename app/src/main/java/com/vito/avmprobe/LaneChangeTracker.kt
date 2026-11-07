package com.vito.avmprobe

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 變道過程中讓預測線跟著走：打燈時鎖定「要跨過去的那條車道線」，之後每一幀在偵測到的車道線裡
 * 找它（車往旁邊移，線在畫面上就往反方向移，每幀找上次位置附近那條），
 * 目標 = 那條線再往打燈那側半個車道寬（目標車道中央）。車身到了目標車道中央附近 → [update] 回 0，表示變道完成。
 * 純邏輯，可單元測試。
 */
class LaneChangeTracker {
    companion object {
        const val PICK_MAX_M = 3.5f      // 打燈時，那一側最近的線要在這麼近內才鎖定
        const val MATCH_M = 0.7f         // 每幀找上次位置附近多遠內的線
        const val HOLD_MS = 2000L        // 線沒看到（虛線間隙、反光）撐這麼久
        const val ARRIVED_M = 0.3f       // 離目標車道中央這麼近 = 變道完成
    }

    private var dir = 0
    private var lineX = Float.NaN
    private var seenAt = 0L

    fun reset() { dir = 0; lineX = Float.NaN }

    /**
     * @return 目標車道中央的橫向位置（m，+ = 右；相對目前車身）；0 = 已經到了；null = 沒鎖定到線（呼叫端用預設車道寬）
     */
    fun update(now: Long, turn: Char?, lines: List<RoadLine>?, laneW: Float): Float? {
        val d = when (turn) { 'L' -> -1; 'R' -> 1; else -> { reset(); return null } }
        if (d != dir) { reset(); dir = d }
        val xs = lines.orEmpty().mapNotNull { x0(it) }
        if (lineX.isNaN()) {
            val pick = xs.filter { it * d > 0.3f && it * d < PICK_MAX_M }.minByOrNull { abs(it) } ?: return null
            lineX = pick; seenAt = now
        } else {
            val m = xs.minByOrNull { abs(it - lineX) }
            if (m != null && abs(m - lineX) < MATCH_M) { lineX = m; seenAt = now }
            else if (now - seenAt > HOLD_MS) { lineX = Float.NaN; return null }
        }
        val target = lineX + d * laneW / 2f
        return if (target * d < ARRIVED_M) 0f else target
    }

    /** 大致跟車身平行、在車旁邊的線 → 它在 z = 0 的橫向位置；不是就 null。 */
    private fun x0(l: RoadLine): Float? {
        val dx = l.x2 - l.x1; val dz = l.z2 - l.z1
        if (hypot(dx, dz) < 1.5f || abs(dz) < 2f * abs(dx)) return null
        if (maxOf(l.z1, l.z2) < -3f || minOf(l.z1, l.z2) > 12f) return null
        val t = if (abs(dz) < 1e-6f) 0f else -l.z1 / dz
        return l.x1 + dx * t
    }
}
