package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class OrientHeadingTest {
    private fun onehot(i: Int) = FloatArray(8) { if (it == i) 10f else 0f }
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun angDiff(a: Double, b: Double) = abs(OrientHeading.wrap(a - b))

    @Test fun binsMapToRelativeAngles() {
        // F FR R BR B BL L FL
        val want = doubleArrayOf(180.0, 135.0, 90.0, 45.0, 0.0, -45.0, -90.0, -135.0)
        for (i in 0 until 8) {
            val (rel, conf) = OrientHeading.relFromLogits(onehot(i))
            assertTrue("bin $i → ${deg(rel)}", angDiff(rel, Math.toRadians(want[i])) < 0.01)
            assertTrue(conf > 0.9f)
        }
    }

    @Test fun carBehindFacingUsIsGoingForward() {
        // 後鏡頭（0, -2.5）看正後方 10 m 的車，看到車頭（F）→ 那台車跟我們同方向（yaw ≈ 0）
        val (rel, _) = OrientHeading.relFromLogits(onehot(0))
        val y = OrientHeading.worldYaw(0f, -2.5f, 0f, -12f, rel)
        assertTrue("yaw ${deg(y.toDouble())}", angDiff(y.toDouble(), 0.0) < 0.05)
    }

    @Test fun carOnLeftSideShowingNoseToImageRightIsGoingForward() {
        // 左鏡頭看左邊的車：鏡頭朝 −x，畫面右邊 = 往前（+z）→ 車頭朝畫面右（R）= 跟我們同方向
        val (rel, _) = OrientHeading.relFromLogits(onehot(2))
        val y = OrientHeading.worldYaw(-1.0f, 0.5f, -3.5f, 0.5f, rel)
        assertTrue("yaw ${deg(y.toDouble())}", angDiff(y.toDouble(), 0.0) < 0.05)
    }

    @Test fun frontAndBackSplitIsNotTrusted() {
        val l = FloatArray(8); l[0] = 5f; l[4] = 5f          // 一半說車頭、一半說車尾
        val (_, conf) = OrientHeading.relFromLogits(l)
        assertTrue("conf $conf", conf < OrientHeading.MIN_CONF)
    }

    @Test fun smootherSettlesAndExpires() {
        val s = OrientHeading.Smoother()
        var y: Float? = null
        for (k in 0 until 10) y = s.update(7, (PI / 2).toFloat(), 0.9f, 1000L + k * 100)
        assertTrue(angDiff(y!!.toDouble(), PI / 2) < 0.05)
        // 一次雜訊（朝反方向）不會把方向翻過去
        y = s.update(7, (-PI / 2).toFloat(), 0.9f, 2100L)
        assertTrue("yaw ${deg(y!!.toDouble())}", angDiff(y.toDouble(), PI / 2) < 0.05)
        assertNull(s.get(7, 2100L + 10_000L))
        assertEquals(null, s.get(99, 2100L))
    }
}
