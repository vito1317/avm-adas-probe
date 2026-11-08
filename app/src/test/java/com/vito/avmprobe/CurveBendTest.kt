package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Test

class CurveBendTest {
    @Test
    fun centerlineFollowsArc() {
        // 右彎 R = 30 m：沿中心線走 1/4 圓（s = π/2·30）→ 到 (30, 30)
        val p = CurveBend.point(0f, (Math.PI / 2 * 30).toFloat(), 1.0 / 30)
        assertEquals(30f, p[0], 0.01f); assertEquals(30f, p[1], 0.01f)
    }

    @Test
    fun offsetStaysParallel() {
        // 右側 3.5 m 的車道線，在彎道上離中心線始終是 3.5 m（離圓心 30 − 3.5）
        val k = 1.0 / 30
        for (s in listOf(0f, 10f, 25f)) {
            val p = CurveBend.point(3.5f, s, k)
            val r = kotlin.math.hypot(p[0] - 30.0, p[1].toDouble())
            assertEquals(26.5, r, 0.01)
        }
    }

    @Test
    fun straightIsUnchanged() {
        val p = CurveBend.point(1.2f, 10f, 0.0)
        assertEquals(1.2f, p[0], 1e-6f); assertEquals(10f, p[1], 1e-6f)
    }

    @Test
    fun leftCurveBendsLeft() {
        val p = CurveBend.point(0f, 20f, -1.0 / 40)
        assertEquals(true, p[0] < -4f)
    }
}
