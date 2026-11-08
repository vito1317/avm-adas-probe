package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MotionCurvatureTest {
    @Test
    fun gyroOnTiltedMountGivesRightTurnCurvature() {
        val c = MotionCurvature.Core()
        // 車機往後仰 30°：重力（往上）在裝置座標是 (0, sin30, cos30)·9.8
        val gy = (9.8 * Math.sin(Math.toRadians(30.0))).toFloat(); val gz = (9.8 * Math.cos(Math.toRadians(30.0))).toFloat()
        repeat(200) { c.onAccel(0f, gy, gz) }
        // 右轉：繞「上」軸順時針 = 負的角速度；R = 30 m、v = 10 m/s → ω = 1/3 rad/s
        val w = -1.0 / 3.0
        var t = 0L
        repeat(100) { c.onGyro(t, 0f, (w * Math.sin(Math.toRadians(30.0))).toFloat(), (w * Math.cos(Math.toRadians(30.0))).toFloat()); t += 20 }
        assertEquals(1.0 / 30.0, c.curvature(t, 36f)!!, 0.002)
    }

    @Test
    fun gpsBearingIsIgnored() {
        // 使用者：車上的 GPS 超級不準 → 彎度不用 GPS 行進方向
        val c = MotionCurvature.Core()
        var t = 0L; var b = 350.0
        repeat(10) { c.onBearing(t, b); t += 1000; b = (b + 10) % 360 }
        org.junit.Assert.assertNull(c.curvature(t - 1000, 54f))
    }

    @Test
    fun tooSlowOrStaleIsNull() {
        val c = MotionCurvature.Core()
        repeat(50) { c.onAccel(0f, 0f, 9.8f) }
        c.onGyro(0, 0f, 0f, 0.2f)
        assertNull(c.curvature(0, 5f))
        assertNull(c.curvature(5000, 40f))
    }
}
