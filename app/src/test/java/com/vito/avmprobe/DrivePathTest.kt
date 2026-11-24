package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivePathTest {
    private fun lane(xl: Float, xr: Float): LaneRibbon.Lane {
        val l = ArrayList<FloatArray>(); val r = ArrayList<FloatArray>()
        for (q in 0..22) { val z = -2.5f + q; l.add(floatArrayOf(xl, z)); r.add(floatArrayOf(xr, z)) }
        return LaneRibbon.Lane(l, r, null, null)
    }

    @Test fun straightConvergesToLaneCenter() {
        val p = DrivePath.predict(lane(-1.2f, 2.2f), 0f, 60f, null, false)     // 車道中線在 x = 0.5
        assertEquals(0f, p.first()[0], 0.05f)                                   // 車頭在車子正前方
        assertEquals(0.5f, p.last()[0], 0.05f)                                  // 遠處照車道中線
        assertTrue(p.last()[1] > 30f)                                           // 60 km/h → 約 3 秒 ≈ 50 m（上限 40）
    }

    @Test fun steeringRightCurvesRight() {
        val p = DrivePath.predict(null, 150f, 20f, null, false)
        assertTrue(p.last()[0] > 1f)
    }

    @Test fun reverseGoesBack() {
        val p = DrivePath.predict(null, 0f, 5f, null, true)
        assertTrue(p.last()[1] < p.first()[1])
        assertTrue(p.first()[1] < 0f)
    }

    @Test fun turnSignalIgnoresLane() {
        val p = DrivePath.predict(lane(-1.2f, 2.2f), 0f, 30f, 'L', false)
        assertEquals(0f, p.last()[0], 0.05f)                                    // 打燈：不跟車道中線，照方向盤（直）
    }

    // ── 避開障礙物 ──
    private fun straight() = DrivePath.predict(null, 0f, 50f, null, false)      // 直走，約 40 m

    private fun xAt(r: DrivePath.Result, z: Float) = r.pts.minByOrNull { kotlin.math.abs(it[1] - z) }!![0]

    @Test fun parkedCarOnRightPathGoesLeft() {
        val r = DrivePath.avoid(straight(), listOf(DrivePath.Obs(0.8, 12.0, 0.9, moving = false)), null)
        assertTrue(!r.blocked)
        assertTrue("繞過時在左邊：${xAt(r, 12f)}", xAt(r, 12f) < 0.8f - 2.0f)
        assertEquals(0f, xAt(r, 35f), 0.05f)                                     // 過了之後回來
        assertEquals(0f, xAt(r, 3f), 0.05f)                                      // 車頭附近還沒開始偏
    }

    @Test fun movingLeadCarFollowed() {
        val r = DrivePath.avoid(straight(), listOf(DrivePath.Obs(0.0, 15.0, 0.9, moving = true)), null)
        assertTrue(r.following && !r.blocked)
        assertTrue("停在前車後面：${r.pts.last()[1]}", r.pts.last()[1] < 15f - 0.9f - 1.5f)
        assertEquals(0f, xAt(r, 8f), 0.05f)                                      // 不繞
    }

    @Test fun noRoomBlocked() {
        val r = DrivePath.avoid(DrivePath.predict(lane(-1.75f, 1.75f), 0f, 50f, null, false),
            listOf(DrivePath.Obs(0.0, 12.0, 0.9, moving = false)), lane(-1.75f, 1.75f))
        assertTrue(r.blocked)
        assertTrue("停在障礙物前：${r.pts.last()[1]}", r.pts.last()[1] < 12f - 0.9f)
    }

    @Test fun farSideObstacleIgnored() {
        val base = straight()
        val r = DrivePath.avoid(base, listOf(DrivePath.Obs(3.5, 12.0, 0.9, moving = false)), null)
        assertEquals(base.size, r.pts.size)
        assertEquals(0f, xAt(r, 12f), 1e-4f)
    }

    // ── 智慧規劃 ──
    private fun xNear(r: DrivePath.Result, z: Float) = r.pts.minByOrNull { kotlin.math.abs(it[1] - z) }!![0]
    private fun parked(x: Double, z: Double) = DrivePath.Agent(x, z, 0.0, 0.0, 0.9, 2.3, false)

    @Test fun plannerGoesAroundParkedCar() {
        val r = DrivePath.Planner().plan(straight(), listOf(parked(0.8, 14.0)), emptyList(), null, null, 14.0)
        assertTrue(!r.blocked)
        assertTrue("在停著的車旁邊要偏左：${xNear(r, 14f)}", xNear(r, 14f) <= 0.8f - 1.85f + 0.01f)
        assertTrue("過了要回來：${r.pts.last()[0]}", kotlin.math.abs(r.pts.last()[0]) < 0.6f)
    }

    @Test fun plannerThreadsGap() {
        val r = DrivePath.Planner().plan(straight(), listOf(parked(1.0, 14.0), parked(-3.3, 15.0)), emptyList(), null, null, 14.0)
        assertTrue(!r.blocked)
        val x = xNear(r, 14f)
        assertTrue("從兩台中間過：$x", x <= 1.0f - 1.85f + 0.01f && x >= -3.3f + 1.85f - 0.01f)
    }

    @Test fun plannerFollowsLeadCar() {
        val r = DrivePath.Planner().plan(straight(), listOf(DrivePath.Agent(0.0, 16.0, 0.0, 10.0, 0.9, 2.3, false)), emptyList(), null, null, 14.0)
        assertTrue(r.following && !r.blocked)
        assertTrue(r.pts.last()[1] < 16f - 2.3f)
        assertTrue(r.pts.all { kotlin.math.abs(it[0]) < 0.3f })
    }

    @Test fun plannerAvoidsCrossingSolidWhenPossible() {
        // 左邊白實線在 −1.5：沒有障礙物時不會去壓它
        val r = DrivePath.Planner().plan(straight(), emptyList(), listOf(DrivePath.Line(-1.5, -5.0, -1.5, 40.0, 'w', false)), null, null, 14.0)
        assertTrue(r.pts.all { kotlin.math.abs(it[0]) < 0.3f })
    }
}
