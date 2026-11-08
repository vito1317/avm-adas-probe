package com.vito.avmprobe

import kotlin.math.abs

/** 路肩判斷（純邏輯，可單元測試）。 */
object RoadsideRules {
    /**
     * 已經在路肩：
     * - 左邊緊鄰一條**實線**（路肩線）：車輪外緣到線 0～1.5 m（線在車身左側 0.9～2.4 m）；
     * - 右邊沒有別的車道線（右側 0.9～3.5 m 內沒有縱向的線），或右側路緣在 2 m 內。
     */
    fun onShoulder(lines: List<RoadLine>, edgeR: Float?): Boolean {
        var leftSolid = false; var rightLine = false
        for (l in lines) {
            val dx = l.x2 - l.x1; val dz = l.z2 - l.z1
            if (abs(dz) < 2f * abs(dx) || kotlin.math.hypot(dx, dz) < 1.5f) continue
            val x0 = if (abs(dz) < 1e-6f) l.x1 else l.x1 - l.z1 * dx / dz
            if (x0 in -2.4f..-0.9f && !l.dashed && l.kind != 'r') leftSolid = true
            if (x0 in 0.9f..3.5f) rightLine = true
        }
        val curbNear = edgeR != null && edgeR < 2.0f
        return leftSolid && (!rightLine || curbNear)
    }
}
