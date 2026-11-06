package com.vito.avmprobe

import kotlin.math.hypot

/**
 * 使用者點選要停的停車格。格子是以自車為原點的座標，車一動它就跟著移，
 * 所以記的是「格子中心」，每次偵測更新時換成離上次中心最近的那一格（1.5 m 內）。
 * 一直沒看到（被車擋住、離開畫面）超過 [LOSE_MS] 就取消。純邏輯，可單元測試。
 */
class SlotSelection {
    companion object {
        const val FOLLOW_M = 1.5f
        const val LOSE_MS = 20_000L

        fun center(s: ParkingSlot): FloatArray =
            floatArrayOf((s.p[0] + s.p[2] + s.p[4] + s.p[6]) / 4f, (s.p[1] + s.p[3] + s.p[5] + s.p[7]) / 4f)

        /** 點 (x, z) 在格子（四邊形，任一繞向）裡面。 */
        fun contains(s: ParkingSlot, x: Float, z: Float): Boolean {
            var sign = 0
            for (k in 0 until 4) {
                val ax = s.p[k * 2]; val az = s.p[k * 2 + 1]
                val bx = s.p[((k + 1) % 4) * 2]; val bz = s.p[((k + 1) % 4) * 2 + 1]
                val cr = (bx - ax) * (z - az) - (bz - az) * (x - ax)
                val sg = if (cr > 0f) 1 else if (cr < 0f) -1 else 0
                if (sg != 0) { if (sign == 0) sign = sg else if (sg != sign) return false }
            }
            return true
        }
    }

    @Volatile var center: FloatArray? = null
        private set
    private var lastSeen = 0L

    /**
     * 使用者點了 (x, z)：點在空格裡 → 選它；點在已選的那格 → 取消。回傳是否點到格子（點到就吃掉這次觸控）。
     */
    fun tap(slots: List<ParkingSlot>, x: Float, z: Float, now: Long): Boolean {
        val hit = slots.firstOrNull { !it.occupied && contains(it, x, z) } ?: return false
        val c = center(hit)
        val cur = center
        center = if (cur != null && hypot(cur[0] - c[0], cur[1] - c[1]) < FOLLOW_M) null else c
        lastSeen = now
        return true
    }

    fun clear() { center = null }

    /** 偵測更新：回傳目前選的格子（跟著移動）；沒選或跟丟回 null。 */
    fun track(slots: List<ParkingSlot>, now: Long): ParkingSlot? {
        val cur = center ?: return null
        val best = slots.minByOrNull { val c = center(it); hypot(c[0] - cur[0], c[1] - cur[1]) }
        if (best != null) {
            val c = center(best)
            if (hypot(c[0] - cur[0], c[1] - cur[1]) < FOLLOW_M) {
                center = c; lastSeen = now
                return best
            }
        }
        if (now - lastSeen > LOSE_MS) center = null
        return null
    }

    fun isSelected(s: ParkingSlot): Boolean {
        val cur = center ?: return false
        val c = center(s)
        return hypot(c[0] - cur[0], c[1] - cur[1]) < 0.3f
    }
}
