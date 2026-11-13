package com.vito.avmprobe

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 道路追蹤：記住每個車道的用途（路面箭頭：直行、左轉、右轉…），箭頭被車頭壓過、看不到之後還記得。純邏輯，可單元測試。
 *
 * - 看到箭頭（[observe]）：箭頭的橫向位置換算成「相對自車車道」的車道編號（0 = 自車車道、-1 = 左邊一道、+1 = 右邊一道），
 *   同一車道多次看到同一種箭頭就越來越確定（不會因為一次看錯就換）。
 * - 換車道（[laneChanged]）：記憶整組平移（往左換 → 原本 -1 的變成 0）。
 * - 往前開（[advance]）：累計距離與轉向；轉了大彎（過路口）或開太遠都沒再看到箭頭 → 清掉（已經是另一條路）。
 * - [current] 回傳某個車道目前記得的用途（夠確定才給）。
 *
 * 只用來顯示與提示（「此車道直行」），不拿去控制車子。
 */
class LaneArrowMemory(
    private val forgetAfterM: Float = 150f,
    private val turnResetDeg: Float = 55f
) {
    enum class Kind { STRAIGHT, LEFT, RIGHT, STRAIGHT_LEFT, STRAIGHT_RIGHT, UTURN, LEFT_RIGHT }

    /** 箭頭觀測：x = 箭頭中心的橫向位置（m，+ = 右），egoLaneCenterX／laneWidth 是同一個縱向位置的自車車道中心與車道寬。 */
    data class Arrow(val kind: Kind, val x: Float, val egoLaneCenterX: Float, val laneWidth: Float, val conf: Float = 1f)

    private class Slot { val votes = HashMap<Kind, Float>(); var sinceM = 0f }
    private val lanes = HashMap<Int, Slot>()
    private var turned = 0f
    private var sinceAnyM = 0f

    fun observe(a: Arrow) {
        if (a.laneWidth < 2.2f || a.laneWidth > 5f) return
        val off = (a.x - a.egoLaneCenterX) / a.laneWidth
        val idx = off.roundToInt()
        if (abs(off - idx) > 0.35f || abs(idx) > 3) return      // 落在線上、看不出是哪一道
        val s = lanes.getOrPut(idx) { Slot() }
        // 舊的票慢慢淡掉，新的加進來
        for (k in s.votes.keys.toList()) s.votes[k] = s.votes[k]!! * 0.85f
        s.votes[a.kind] = (s.votes[a.kind] ?: 0f) + a.conf
        s.sinceM = 0f; sinceAnyM = 0f; turned = 0f
    }

    /** 換車道：dir = -1 往左換、+1 往右換。 */
    fun laneChanged(dir: Int) {
        if (dir == 0) return
        val moved = HashMap<Int, Slot>()
        for ((k, v) in lanes) moved[k - dir] = v
        lanes.clear(); lanes.putAll(moved)
    }

    /** 往前開了 [dz] 公尺、轉了 [dyawDeg] 度。 */
    fun advance(dz: Float, dyawDeg: Float) {
        val d = abs(dz)
        sinceAnyM += d; turned += dyawDeg
        for (s in lanes.values) s.sinceM += d
        if (abs(turned) > turnResetDeg || sinceAnyM > forgetAfterM) clear()
        lanes.entries.removeAll { it.value.sinceM > forgetAfterM }
    }

    fun clear() { lanes.clear(); turned = 0f; sinceAnyM = 0f }

    /** 某個車道（0 = 自車）目前的用途；票數夠多、而且明顯多過其他種類才給。 */
    fun current(lane: Int = 0): Kind? {
        val s = lanes[lane] ?: return null
        val best = s.votes.maxByOrNull { it.value } ?: return null
        val second = s.votes.filter { it.key != best.key }.maxOfOrNull { it.value } ?: 0f
        return if (best.value >= 1.5f && best.value >= second * 2f) best.key else null
    }

    /** 給畫面：每個記得的車道 → 用途。 */
    fun snapshot(): Map<Int, Kind> = lanes.keys.mapNotNull { k -> current(k)?.let { k to it } }.toMap()

    companion object {
        fun label(k: Kind): String = when (k) {
            Kind.STRAIGHT -> "直行"; Kind.LEFT -> "左轉"; Kind.RIGHT -> "右轉"
            Kind.STRAIGHT_LEFT -> "直行／左轉"; Kind.STRAIGHT_RIGHT -> "直行／右轉"; Kind.UTURN -> "迴轉"; Kind.LEFT_RIGHT -> "左轉／右轉"
        }
        /** 打的方向燈跟車道用途對不起來（例如在只能直行的車道打左轉燈）。 */
        fun conflicts(k: Kind, turn: Char?): Boolean = when (turn) {
            'L' -> k == Kind.STRAIGHT || k == Kind.RIGHT || k == Kind.STRAIGHT_RIGHT
            'R' -> k == Kind.STRAIGHT || k == Kind.LEFT || k == Kind.STRAIGHT_LEFT || k == Kind.UTURN
            else -> false
        }
    }
}
