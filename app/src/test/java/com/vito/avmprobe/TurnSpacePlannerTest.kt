package com.vito.avmprobe

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnSpacePlannerTest {
    @Test
    fun openCornerOnRightGivesRightTurn() {
        // 右邊路口：右前方空的，只有路口對面角落有東西
        val blockers = TurnSpacePlanner.pointsOf(9f, 16f, 1f, 1f)
        val tp = TurnSpacePlanner.plan('R', 12f, blockers)
        assertNotNull(tp); tp!!
        assertTrue("往右轉", tp.pts.last()[0] > 2f)
    }

    @Test
    fun continuousCurbMeansNoOpening() {
        // 右邊整排路緣（x = 3）→ 不是路口
        val curb = TurnSpacePlanner.pointsOfLine(3f, -2f, 3f, 14f)
        assertNull(TurnSpacePlanner.plan('R', 12f, curb))
    }

    @Test
    fun postRightBesideTheCornerBlocksTheTurn() {
        // 柱子就在右前角旁邊（車邊外 0.7 m、車頭前 2 m）：任何右轉都會掃到 → 不畫轉彎
        assertNull(TurnSpacePlanner.plan('R', 10f, TurnSpacePlanner.pointsOf(1.6f, 4.6f, 0.2f, 0.2f)))
    }

    @Test
    fun returnedPathNeverHitsBlockers() {
        val blk = TurnSpacePlanner.pointsOf(6f, 9f, 0.3f, 0.3f) + TurnSpacePlanner.pointsOf(-4f, 6f, 1f, 2f)
        val tp = TurnSpacePlanner.plan('R', 10f, blk) ?: return
        assertTrue(blk.none { TurnSpacePlanner.inside(tp.sweptPolygon(), it[0], it[1]) })
    }

    @Test
    fun fastIsNotATurn() {
        assertNull(TurnSpacePlanner.plan('R', 40f, emptyList()))
        assertNull(TurnSpacePlanner.plan(null, 10f, emptyList()))
    }
}
