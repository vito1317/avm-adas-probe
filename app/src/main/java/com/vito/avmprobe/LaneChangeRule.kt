package com.vito.avmprobe

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 打方向燈要切過去的那一側，是不是「禁止變換車道」的線（台灣：白實線、雙白線禁止變換車道；
 * 黃實線、雙黃線禁止跨越；虛線可以變換）。純邏輯，可單元測試。
 *
 * 看的是自車那一側**最近**、跟車身大致平行、在車旁邊的那條線：
 * - 左側：實線（白或黃）就禁止。
 * - 右側：最外側的白實線多半是**路邊線**，打右燈靠邊停車是合法的 → 實線外面還有另一條線
 *   （代表外面還有車道）才算禁止。紅線是路緣，不算。
 * - 同色、平行、相距 0.08～0.5 m 的兩條實線 → 雙線。
 */
object LaneChangeRule {

    data class Verdict(val side: Char, val kind: Char, val double: Boolean)

    private const val NEAR_MIN = 0.5f    // 線在自車中心外多遠才算「那一側的線」（m）
    private const val NEAR_MAX = 2.6f
    private const val BEYOND_MAX = 4.8f  // 右側：實線外面這麼遠內還有線 → 外面還有車道
    private const val DOUBLE_MIN = 0.08f
    private const val DOUBLE_MAX = 0.5f

    /** 線在 z = 0 的橫向位置；不平行於車身或太短 → null。 */
    private fun x0(l: RoadLine): Float? {
        val dx = l.x2 - l.x1; val dz = l.z2 - l.z1
        if (abs(dz) < 2f * abs(dx) || hypot(dx, dz) < 1.5f) return null
        if (maxOf(l.z1, l.z2) < -4f || minOf(l.z1, l.z2) > 10f) return null   // 要在車旁邊或前方不遠
        return l.x1 + (0f - l.z1) * dx / dz
    }

    /** [side] = 'L' / 'R'。不是禁止變換車道（或看不到線）回 null。 */
    fun check(lines: List<RoadLine>, side: Char): Verdict? {
        val sg = if (side == 'L') -1f else 1f
        val cands = lines.mapNotNull { l -> x0(l)?.let { x -> l to x * sg } }   // 換成「往那一側多遠」
            .filter { it.first.kind != 'r' }
        val nearest = cands.filter { it.second in NEAR_MIN..NEAR_MAX }.minByOrNull { it.second } ?: return null
        val (line, d) = nearest
        if (line.dashed) return null
        val double = cands.any { (o, od) ->
            o !== line && !o.dashed && o.kind == line.kind && abs(od - d) in DOUBLE_MIN..DOUBLE_MAX
        }
        if (side == 'R') {
            // 外面還要有線（不含雙線的另一條）才是車道分隔線，不是路邊線
            val beyond = cands.any { (o, od) -> o !== line && od - d > DOUBLE_MAX && od <= BEYOND_MAX }
            if (!beyond && !double) return null
        }
        return Verdict(side, line.kind, double)
    }

    /** 給畫面與字幕的文字。 */
    fun label(v: Verdict): String {
        val color = if (v.kind == 'y') "黃" else "白"
        val name = (if (v.double) "雙" else "") + color + (if (v.double) "線" else "實線")
        val rule = if (v.kind == 'y') "禁止跨越" else "禁止變換車道"
        return if (v.side == 'L') "◀ $name $rule" else "$name $rule ▶"
    }
}
