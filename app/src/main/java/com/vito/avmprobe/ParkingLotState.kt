package com.vito.avmprobe

import kotlin.math.abs

/**
 * 判斷是不是在停車場（純邏輯，可單元測試）。取代「90 秒內開過 30 km/h 就當在馬路上」：
 * 從馬路開進停車場、很快停進格子，常常還在那 90 秒內 → 整段停車都被當成馬路，停車格不顯示（實車）。
 *
 * 進入：打 R／P 檔；或慢慢開（2～18 km/h）累積 [SLOW_MS] 以上、期間沒超過 [EXIT_KMH]；
 *       或慢速時方向盤打很大（停車在轉）。停紅燈是停住不動，不會累積「慢慢開」的時間 → 不會誤判。
 * 離開：超過 [EXIT_KMH] 持續 [EXIT_MS]。
 */
class ParkingLotState {
    companion object {
        const val SLOW_MIN = 2f
        const val SLOW_MAX = 18f
        const val SLOW_MS = 8000L
        const val EXIT_KMH = 25f
        const val EXIT_MS = 5000L
        const val BIG_STEER = 150f
    }

    var inLot = false; private set
    private var slowMs = 0L
    private var fastSince = 0L
    private var lastAt = 0L

    fun update(now: Long, speedKmh: Float?, gear: Char?, steerDeg: Float?): Boolean {
        val dt = if (lastAt == 0L) 0L else (now - lastAt).coerceIn(0L, 500L)
        lastAt = now
        val v = speedKmh ?: 0f
        if (v > EXIT_KMH) {
            if (fastSince == 0L) fastSince = now
            slowMs = 0L
            if (inLot && now - fastSince >= EXIT_MS) inLot = false
            return inLot
        }
        fastSince = 0L
        if (v in SLOW_MIN..SLOW_MAX) slowMs += dt
        when {
            gear == 'R' || gear == 'P' -> inLot = true
            slowMs >= SLOW_MS -> inLot = true
            v <= 15f && steerDeg != null && abs(steerDeg) >= BIG_STEER -> inLot = true
        }
        return inLot
    }
}
