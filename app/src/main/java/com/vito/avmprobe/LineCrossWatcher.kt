package com.vito.avmprobe

/**
 * 用車道線判斷變道進度（使用者：壓線就代表正在變道）。看 [LaneDepartureWarning] 量到的車輪外緣到兩側線的距離：
 * - 打燈那一側的線距離 < 0（線跑到車底下）→ 正在跨線；
 * - 跨線之後，原本那條線出現在另一側（另一側距離由負轉正、或打燈那側重新出現一條遠的線）→ 變道完成。
 * 換燈或關燈就重新開始。純邏輯，可單元測試。
 */
class LineCrossWatcher {
    private var dir = 0
    private var crossing = false
    var done = false; private set

    fun reset() { dir = 0; crossing = false; done = false }

    /** 每幀呼叫；回傳這次打燈是否已經完成變道。 */
    fun update(turn: Char?, ldw: LaneDepartureWarning.State?): Boolean {
        val d = when (turn) { 'L' -> -1; 'R' -> 1; else -> { reset(); return false } }
        if (d != dir) { reset(); dir = d }
        if (done || ldw == null) return done
        val near = if (d < 0) ldw.distL else ldw.distR      // 打燈那側
        val far = if (d < 0) ldw.distR else ldw.distL       // 另一側
        if (!crossing) {
            if (!near.isNaN() && near < -0.2f) crossing = true
        } else {
            // 跨過去了：剛剛壓的線現在在另一側、離車輪外緣有一點距離
            if (!far.isNaN() && far > 0.15f && far < 1.2f && (near.isNaN() || near > 0.6f)) done = true
        }
        return done
    }
}
