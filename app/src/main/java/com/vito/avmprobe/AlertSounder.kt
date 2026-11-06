package com.vito.avmprobe

/**
 * 提示音的判斷（純邏輯，可單元測試）：每種警示各自「剛出現才響」＋各自冷卻，不會一直叫。
 * 真的發聲交給 [play]（Android 上是 ToneGenerator）。
 *
 * 嚴重程度不同、聲音不同：
 * - [Kind.INFO]：前車起步、轉綠燈 → 溫和的兩聲
 * - [Kind.LANE]：偏離車道 → 短促的嗶嗶
 * - [Kind.DANGER]：變道時後方來車、轉彎路徑上有人車、移動中離障礙物 < 0.5 m → 急促警報
 */
class AlertSounder(private val play: (Kind) -> Unit) {

    enum class Kind(val cooldownMs: Long) { INFO(4000L), LANE(3000L), DANGER(1500L) }

    /** 這一幀各種警示「是否成立」。 */
    data class State(
        val start: Boolean = false,
        val ldw: Boolean = false,
        val lcaDanger: Boolean = false,
        val turnHit: Boolean = false,
        val collision: Boolean = false
    )

    private var prev = State()
    private val lastAt = HashMap<Kind, Long>()

    fun update(now: Long, s: State) {
        // 剛出現（上一幀沒有、這一幀有）才響
        val danger = (s.lcaDanger && !prev.lcaDanger) || (s.turnHit && !prev.turnHit) || (s.collision && !prev.collision)
        val lane = s.ldw && !prev.ldw
        val info = s.start && !prev.start
        prev = s
        when {
            danger -> fire(Kind.DANGER, now)
            lane -> fire(Kind.LANE, now)
            info -> fire(Kind.INFO, now)
        }
    }

    private fun fire(k: Kind, now: Long) {
        val last = lastAt[k]
        if (last != null && now - last < k.cooldownMs) return
        lastAt[k] = now
        play(k)
    }

    companion object {
        /** 碰撞音只在移動中才響（停著時旁邊本來就可能很近，例如停車場），倒車一律算移動。 */
        fun moving(speedKmh: Float?, gear: Char?): Boolean = gear == 'R' || (speedKmh != null && speedKmh >= 1f)
    }
}
