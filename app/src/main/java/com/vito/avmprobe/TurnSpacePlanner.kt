package com.vito.avmprobe

/**
 * 轉進空地（使用者：轉彎能辨識成往空的地方轉嗎）：打燈、慢速、方向盤還沒轉時，
 * 看打燈那一側有沒有「開口」（路口、巷口、停車場入口），有的話挑一個轉進去不會碰到東西的半徑。
 *
 * - 擋住的東西：俯視圖的障礙物、偵測到的車／人／機車、紅線路緣、路緣（全部換成點）。
 * - 開口：打燈那側、橫向 1.5～8 m、前方 2～12 m 的範圍裡擋住的點很少；整排都是路緣或停著的車 → 一般道路，不當轉彎。
 * - 半徑：從一般轉彎半徑往兩邊試（右轉偏小、左轉偏大），取第一個掃過範圍裡沒有任何擋住點的。
 * 純邏輯，可單元測試。
 */
object TurnSpacePlanner {
    /** 試的後軸半徑（m）：右轉從 6.5 起、左轉從 10 起往外試。 */
    private val RIGHT_R = doubleArrayOf(6.5, 5.5, 8.0, 4.8, 10.0, 12.5, 15.0)
    private val LEFT_R = doubleArrayOf(10.0, 8.0, 12.5, 6.5, 15.0, 5.5, 18.0)
    const val MAX_KMH = 25f
    /** 開口範圍裡，擋住的東西最多佔這麼多公尺（以 1 m 為一格）。 */
    const val OPEN_MAX_BINS = 3

    /** [blockers] 擋住的點（自車座標 x, z）。沒有開口或找不到不碰撞的半徑 → null。 */
    fun plan(turn: Char?, speedKmh: Float?, blockers: List<FloatArray>): TurnPathPlanner.TurnPath? {
        if (turn != 'L' && turn != 'R') return null
        if (speedKmh == null || speedKmh > MAX_KMH) return null
        val d = if (turn == 'R') 1f else -1f
        // 開口：打燈那側的帶子（橫向 1.5～8 m、前方 2～12 m）裡，擋住的東西沿前後方向佔了多長。
        // 一根柱子只佔 1 m，不算擋住；整排路緣、停著的車會佔好幾公尺（以 1 m 為一格算）
        val bins = BooleanArray(10)
        for (p in blockers) if (p[0] * d in 1.5f..8f && p[1] in 2f..12f) bins[((p[1] - 2f).toInt()).coerceIn(0, 9)] = true
        if (bins.count { it } > OPEN_MAX_BINS) return null
        for (r in if (turn == 'R') RIGHT_R else LEFT_R) {
            val tp = TurnPathPlanner.arcPath(turn, r)
            val poly = tp.sweptPolygon()
            if (blockers.none { inside(poly, it[0], it[1]) }) return tp
        }
        return null
    }

    /** 點在多邊形內（射線法）。 */
    fun inside(poly: List<FloatArray>, x: Float, z: Float): Boolean {
        var c = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val xi = poly[i][0]; val zi = poly[i][1]; val xj = poly[j][0]; val zj = poly[j][1]
            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi + 1e-9f) + xi) c = !c
            j = i
        }
        return c
    }

    /** 物件／障礙物 → 擋住的點（中心與四周，半寬 [hw]）。 */
    fun pointsOf(x: Float, z: Float, hw: Float, hl: Float): List<FloatArray> = listOf(
        floatArrayOf(x, z), floatArrayOf(x - hw, z - hl), floatArrayOf(x + hw, z - hl),
        floatArrayOf(x - hw, z + hl), floatArrayOf(x + hw, z + hl)
    )

    /** 線段（路緣、紅線）每 0.5 m 取一點。 */
    fun pointsOfLine(x1: Float, z1: Float, x2: Float, z2: Float): List<FloatArray> {
        val n = maxOf(1, (kotlin.math.hypot(x2 - x1, z2 - z1) / 0.5f).toInt())
        return (0..n).map { k -> floatArrayOf(x1 + (x2 - x1) * k / n, z1 + (z2 - z1) * k / n) }
    }

}
