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
}
