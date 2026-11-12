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
 * - 離開（任一）：超過 [EXIT_KMH] 持續 [EXIT_MS]；從最後一次停車場證據（打 R／P、方向盤大角度、看到格子）
 *   又開了 [EXIT_DIST_M]；慢慢開的這段（每 [ROAD_WIN_MS] 一看）一半以上時間像馬路（前車排隊、兩旁車道線）。
 *   以前只有「超過 25 km/h 持續 5 秒」：市區走走停停開不到，開出停車場還一直當停車場（不畫道路線）。
 */
class ParkingLotState {
    companion object {
        const val SLOW_MIN = 2f
        const val SLOW_MAX = 18f
        const val SLOW_MS = 8000L
        const val EXIT_KMH = 20f
        const val EXIT_MS = 3000L
        const val EXIT_DIST_M = 150f
        const val ROAD_WIN_MS = 8000L
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
    // 在停車場裡：離最後一次停車場證據開了多遠、最近一段像不像馬路
    private var sinceEvidenceM = 0f
    private var rWinMs = 0L; private var rLeadMs = 0L; private var rLaneMs = 0L

    private fun resetWindow() { slowMs = 0L; leadMs = 0L; laneMs = 0L; evidence = false }
    private fun leave() { inLot = false; sinceEvidenceM = 0f; rWinMs = 0L; rLeadMs = 0L; rLaneMs = 0L; resetWindow() }

    fun update(now: Long, speedKmh: Float?, gear: Char?, steerDeg: Float?, clues: Clues = Clues()): Boolean {
        val dt = if (lastAt == 0L) 0L else (now - lastAt).coerceIn(0L, 500L)
        lastAt = now
        val v = speedKmh ?: 0f
        if (v > EXIT_KMH) {
            if (fastSince == 0L) fastSince = now
            resetWindow()
            if (inLot && now - fastSince >= EXIT_MS) leave()
            return inLot
        }
        fastSince = 0L
        if (gear == 'R' || gear == 'P') { inLot = true; sinceEvidenceM = 0f; return true }
        if (inLot) {
            val lotSign = (steerDeg != null && abs(steerDeg) >= BIG_STEER) || clues.slots >= 2
            if (lotSign) sinceEvidenceM = 0f else sinceEvidenceM += v / 3.6f * dt / 1000f
            if (sinceEvidenceM >= EXIT_DIST_M) { leave(); return false }
            if (v >= SLOW_MIN) {
                rWinMs += dt
                if (clues.leadClose) rLeadMs += dt
                if (clues.longLanes) rLaneMs += dt
                if (rWinMs >= ROAD_WIN_MS) {
                    val road = rLeadMs >= ROAD_FRAC * rWinMs || rLaneMs >= ROAD_FRAC * rWinMs
                    rWinMs = 0L; rLeadMs = 0L; rLaneMs = 0L
                    if (road && !lotSign) { leave(); return false }
                }
            }
            return true
        }
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
