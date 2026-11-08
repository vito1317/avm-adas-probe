package com.vito.avmprobe

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 車道偏離警示（LDWS）。
 *
 * 吃 [RoadExtractor] 在俯視圖上找到的標線（車身座標，z 向前、x 向右），算自車**車輪外緣**
 * 離左右那條線還有多遠，快壓到線、而且是在往那邊靠的時候才警示。
 *
 * ### 幾個刻意的選擇
 *
 * - **要「正在靠過去」才警示**：只看距離的話，本來就貼著線開（窄路、路邊停車旁）會一直叫。
 *   橫向速度由線的位置跨幀差分得到，需要 [MIN_RATE] 以上。
 * - **打方向燈往同一邊偏不算**：那是在變換車道。方向燈方向與偏離方向一致就整個跳過
 *   （連距離都不判），燈熄掉之後再等 [SIGNAL_GRACE_MS] 才恢復 —— 變換車道的動作會跨過燈熄的那一刻。
 * - **低速不作用**：[MIN_KMH] 以下（巷弄、停車、塞車）壓線是常態。沒有車速訊號就不作用。
 * - **只認縱向的線**：橫過去的停止線、斑馬線不是車道邊界。
 * - **虛線、實線都算**，但實線（多半是路緣或禁止跨越）門檻放寬一點點，因為跨過去後果比較嚴重。
 *
 * 警示是**一次性**的：叫過之後要先回到車道中間（離線 > [REARM_M]）或換邊，才會再叫。
 * 同一個 instance 不要多執行緒同時呼叫。
 */
class LaneDepartureWarning {

    companion object {
        const val MIN_KMH = 55f            // 這個速度以下不作用
        const val EGO_HALF_W = 0.925f      // 自車半寬（車輪外緣）
        const val WARN_M = 0.15f           // 車輪外緣離線這麼近就算要壓線了
        const val WARN_M_SOLID = 0.25f     // 實線放寬一點
        const val REARM_M = 0.55f          // 回到這個距離以外才重新武裝
        const val MIN_RATE = 0.12f         // 橫向靠近速度下限（m/s）
        const val MAX_DIST = 2.6f          // 離自車中心超過這麼遠的線不是本車道邊界
        const val MIN_LEN = 1.8f           // 太短的線段不採信
        const val MIN_CONF = 0.45f
        const val LONGITUDINAL = 0.62f     // |dz| / 長度 要大於這個才算縱向的線（約 ±52°）
        const val SIGNAL_GRACE_MS = 1200L  // 方向燈熄掉後還寬限這麼久
        const val RATE_TAU_MS = 400f       // 橫向速度的平滑時間常數
        const val STALE_MS = 900L          // 超過這麼久沒看到線就重新開始算速度
        /** 壓線行駛：線在車輪外緣內側超過這麼多（m），也就是線已經在車底下 */
        const val STRADDLE_IN = 0.10f
        /** 壓線持續這麼久才提醒（變換車道跨過去的那一兩秒不算） */
        const val STRADDLE_MS = 2000L
    }

    /** 作用的最低車速（km/h），設定可調；預設 [MIN_KMH]。 */
    @Volatile var minKmh: Float = MIN_KMH

    /** [left]/[right] 是否正在警示；[distL]/[distR] 車輪外緣離線的距離（m，看不到線是 NaN）。 */
    data class State(
        val left: Boolean, val right: Boolean,
        val distL: Float, val distR: Float,
        /** 壓線行駛（線在車底下持續 [STRADDLE_MS]、沒打那一側的燈）：'L'／'R'，沒有是 null */
        val straddle: Char? = null,
        /** 壓的是實線（禁止跨越／禁止變換車道，違規） */
        val straddleSolid: Boolean = false
    ) {
        val any: Boolean get() = left || right
    }

    private var stradSide: Char? = null
    private var stradSince = 0L

    private class Side {
        var dist = Float.NaN       // 車輪外緣到線的距離
        var at = 0L
        var rate = 0f              // 靠近速度（正 = 正在靠過去）
        var fired = false
    }

    private val l = Side()
    private val r = Side()
    private var signalLitAt = 0L
    private var lastTurn: Char? = null

    /**
     * @param turn 方向燈 'L'/'R'/'H'，沒打傳 null
     * @return 這一輪的警示狀態
     */
    fun update(
        nowMs: Long,
        speedKmh: Float?,
        lines: List<RoadLine>,
        turn: Char?
    ): State {
        if (turn == 'L' || turn == 'R' || turn == 'H') {
            signalLitAt = nowMs
            lastTurn = turn
        }
        // 方向燈剛熄掉的那幾百毫秒也還算在變換車道中
        val grace = lastTurn != null && nowMs - signalLitAt <= SIGNAL_GRACE_MS
        val sigL = grace && (lastTurn == 'L' || lastTurn == 'H')
        val sigR = grace && (lastTurn == 'R' || lastTurn == 'H')

        var dl = Float.NaN
        var dr = Float.NaN
        var solidL = false
        var solidR = false
        for (ln in lines) {
            if (ln.conf < MIN_CONF) continue
            val dx = ln.x2 - ln.x1
            val dz = ln.z2 - ln.z1
            val len = hypot(dx, dz)
            if (len < MIN_LEN) continue
            if (abs(dz) / len < LONGITUDINAL) continue        // 停止線、斑馬線不是車道邊界
            // 線在 z = 0（車身中心橫斷面）附近的 x：用線段兩端內插，超出範圍就取最近端
            val x0 = xAtZeroZ(ln)
            if (abs(x0) > MAX_DIST) continue
            if (x0 < 0f) {
                val d = -x0 - EGO_HALF_W
                if (dl.isNaN() || d < dl) { dl = d; solidL = !ln.dashed }
            } else {
                val d = x0 - EGO_HALF_W
                if (dr.isNaN() || d < dr) { dr = d; solidR = !ln.dashed }
            }
        }

        // 車速不夠：不警示，但距離照樣回報（變道進度判斷要用，市區變道也要知道有沒有壓線）
        if (speedKmh == null || speedKmh < minKmh) {
            reset()
            stradSide = null
            return State(false, false, dl, dr)
        }

        val warnL = step(l, dl, nowMs, solidL, sigL)
        val warnR = step(r, dr, nowMs, solidR, sigR)

        // 壓線行駛：線已經在車底下（車輪外緣內側），打了那一側的燈（正在變換車道）不算
        val onL = !dl.isNaN() && dl < -STRADDLE_IN && !sigL
        val onR = !dr.isNaN() && dr < -STRADDLE_IN && !sigR
        val side = when {
            onL && onR -> if (dl <= dr) 'L' else 'R'
            onL -> 'L'
            onR -> 'R'
            else -> null
        }
        if (side == null) stradSide = null
        else if (side != stradSide) { stradSide = side; stradSince = nowMs }
        val straddle = if (side != null && nowMs - stradSince >= STRADDLE_MS) side else null
        return State(warnL, warnR, dl, dr, straddle, straddle == 'L' && solidL || straddle == 'R' && solidR)
    }

    /** 更新一側的距離與靠近速度，回傳是否要警示。 */
    private fun step(s: Side, d: Float, nowMs: Long, solid: Boolean, suppressed: Boolean): Boolean {
        if (d.isNaN()) {                 // 這一側這一輪沒看到線：保留狀態但不警示
            s.dist = Float.NaN
            return false
        }
        val dt = nowMs - s.at
        if (!s.dist.isNaN() && dt in 1..STALE_MS) {
            val inst = (s.dist - d) * 1000f / dt          // 距離變小 = 正在靠過去 → 正值
            val a = min(1f, dt / RATE_TAU_MS)
            s.rate += (inst - s.rate) * a
        } else {
            s.rate = 0f
        }
        s.dist = d
        s.at = nowMs

        if (d > REARM_M) s.fired = false                  // 回到車道中間 → 重新武裝
        if (suppressed) {                                 // 打了同方向的燈：不警示，也不要留著待發
            s.fired = false
            return false
        }
        val lim = if (solid) WARN_M_SOLID else WARN_M
        if (!s.fired && d <= lim && s.rate >= MIN_RATE) s.fired = true
        return s.fired && d <= REARM_M
    }

    /**
     * 線段在 z = 0 的 x（車身中心橫斷面）。z 不跨過 0 的線段取最近的那一端 ——
     * 只看得到前方一段線是常態（後方被車身蓋住），不能因此就不判斷。
     */
    private fun xAtZeroZ(ln: RoadLine): Float {
        val z1 = ln.z1
        val z2 = ln.z2
        if ((z1 <= 0f && z2 >= 0f) || (z2 <= 0f && z1 >= 0f)) {
            val t = if (abs(z2 - z1) < 1e-6f) 0f else (0f - z1) / (z2 - z1)
            return ln.x1 + (ln.x2 - ln.x1) * t
        }
        return if (abs(z1) < abs(z2)) ln.x1 else ln.x2
    }

    private fun reset() {
        l.dist = Float.NaN; l.rate = 0f; l.fired = false
        r.dist = Float.NaN; r.rate = 0f; r.fired = false
    }
}
