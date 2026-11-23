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
}
