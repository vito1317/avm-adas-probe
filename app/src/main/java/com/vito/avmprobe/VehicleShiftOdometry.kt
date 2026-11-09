package com.vito.avmprobe

import kotlin.math.abs

/**
 * 用周圍的車判斷自己橫移了多少（使用者：前面的車跑到右邊，代表往左變道了）。
 *
 * 周圍的車多半穩穩待在自己的車道：自己往右移 dy，它們在自車座標裡就整批往左移 dy。
 * 但車頭轉向也會讓它們左右移，而且越遠移越多（轉 dθ，z 公尺外的車橫移 −dθ·z）：
 *   Δx = −dy − dθ·z  →  dy = −(Δx + dθ·z)
 * dθ 用前方畫面量的轉向（不靠方向盤）；量不到就只用旁邊、近的車（|z| < [NEAR_Z]，轉向影響小）。
 * 至少 2 台、而且大多數（≥ 2/3）方向一致才採用——只有一台時可能是它自己在變道，要跟打燈方向對得上。
 * 純邏輯，可單元測試。
 */
class VehicleShiftOdometry {
    companion object {
        const val NEAR_Z = 6f
        const val MAX_X = 9f
        const val MAX_STEP = 0.8f            // 一次更新最多橫移這麼多（再多是配錯對象）
    }

    data class Obj(val id: Int, val x: Float, val z: Float)

    private var prev = HashMap<Int, Obj>()

    fun reset() { prev = HashMap() }

    /**
     * 每次追蹤更新呼叫一次。[dTheta]：這段時間車頭轉了多少（rad，+ = 右），不知道傳 null。
     * [turnDir]：打燈方向（-1 左、+1 右、0 沒打）。回傳這段時間自己的橫移（m，+ = 右）；判斷不了回 null。
     */
    fun update(objs: List<Obj>, dTheta: Double?, turnDir: Int): Float? {
        val ests = ArrayList<Float>()
        for (o in objs) {
            if (abs(o.x) > MAX_X || o.z < -15f || o.z > 35f) continue
            if (dTheta == null && abs(o.z) > NEAR_Z) continue
            val p = prev[o.id] ?: continue
            val dx = o.x - p.x
            val dy = -(dx + (dTheta ?: 0.0).toFloat() * (o.z + p.z) / 2f)
            if (abs(dy) <= MAX_STEP) ests.add(dy)
        }
        prev = HashMap(objs.associateBy { it.id })
        if (ests.isEmpty()) return null
        ests.sort()
        val med = ests[ests.size / 2]
        if (ests.size == 1) {
            // 只有一台：它自己變道也會這樣 → 要跟打燈方向一致才信
            return if (turnDir != 0 && med * turnDir > 0f) med else null
        }
        if (ests.last() - ests.first() > 0.5f) return null        // 各台車差太多：有車自己在動，不可靠
        val agree = ests.count { (it >= 0f) == (med >= 0f) || abs(it) < 0.03f }
        return if (agree * 3 >= ests.size * 2) med else null
    }
}
