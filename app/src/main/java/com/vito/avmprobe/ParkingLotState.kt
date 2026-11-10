package com.vito.avmprobe

import kotlin.math.abs

/**
 * 判斷是不是在停車場（純邏輯，可單元測試）。取代「90 秒內開過 30 km/h 就當在馬路上」。
 * 只看慢速會把高速公路塞車當成停車場（使用者指出），所以要停車場才有、塞車不會有的線索：
 *
 * - 打 R／P 檔 → 直接算停車場。
 * - 否則要：慢慢開（2～18 km/h）累積 [SLOW_MS]，而且那段時間裡
 *   有「停車場的證據」（慢速時方向盤打超過 [BIG_STEER]，或看到 2 格以上停車格），
 *   而且「馬路的證據」不能占一半以上：正前方 15 m 內一直有車（塞車在排隊）、車旁有長長的縱向車道線。
 * - 離開：超過 [EXIT_KMH] 持續 [EXIT_MS]。
 */
class ParkingLotState {
    companion object {
        const val SLOW_MIN = 2f
        const val SLOW_MAX = 18f
        const val SLOW_MS = 8000L
        const val EXIT_KMH = 25f
        const val EXIT_MS = 5000L
        const val BIG_STEER = 90f
        const val ROAD_FRAC = 0.5f
    }

    data class Clues(val leadClose: Boolean = false, val longLanes: Boolean = false, val slots: Int = 0)

    var inLot = false; private set
    private var slowMs = 0L
    private var leadMs = 0L
    private var laneMs = 0L
    private var evidence = false
    private var fastSince = 0L
    private var lastAt = 0L

    private fun resetWindow() { slowMs = 0L; leadMs = 0L; laneMs = 0L; evidence = false }

    fun update(now: Long, speedKmh: Float?, gear: Char?, steerDeg: Float?, clues: Clues = Clues()): Boolean {
        val dt = if (lastAt == 0L) 0L else (now - lastAt).coerceIn(0L, 500L)
        lastAt = now
        val v = speedKmh ?: 0f
        if (v > EXIT_KMH) {
            if (fastSince == 0L) fastSince = now
            resetWindow()
            if (inLot && now - fastSince >= EXIT_MS) inLot = false
            return inLot
        }
        fastSince = 0L
        if (gear == 'R' || gear == 'P') { inLot = true; return true }
        if (inLot) return true
        // 只在「慢慢開」的時候累積（停住不動的時間不算：停紅燈、塞車停住都不會讓它變成停車場）
        if (v in SLOW_MIN..SLOW_MAX) {
            slowMs += dt
            if (clues.leadClose) leadMs += dt
            if (clues.longLanes) laneMs += dt
            if ((steerDeg != null && abs(steerDeg) >= BIG_STEER) || clues.slots >= 2) evidence = true
        }
        if (slowMs >= SLOW_MS && evidence &&
            leadMs < ROAD_FRAC * slowMs && laneMs < ROAD_FRAC * slowMs) inLot = true
        return inLot
    }
}
