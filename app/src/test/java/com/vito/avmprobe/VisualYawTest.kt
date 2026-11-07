package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualYawTest {
    private val S = 200
    /** 遠景：一排高低不一的「建築」邊緣，整片往左平移 [shift] 像素。 */
    private fun scene(shift: Double): IntArray {
        val a = IntArray(S * S)
        val rnd = java.util.Random(7)
        val heights = DoubleArray(400) { rnd.nextDouble() }
        for (y in 0 until S) for (x in 0 until S) {
            val wx = x + shift + 100
            val col = heights[(wx / 7).toInt().coerceIn(0, 399)]
            val v = if (y < S * (0.3 + 0.15 * col)) 200 else 60
            a[y * S + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return a
    }

    @Test
    fun rightTurnShiftsSceneLeft() {
        val vy = VisualYaw()
        val f = 100.0
        // 每 0.1 秒景物往左 3 px → 每幀轉 atan(3/100) ≈ 0.03 rad → 0.3 rad/s 往右
        var t = 0L
        for (i in 0 until 15) { vy.update(t, scene(i * 3.0), S, f); t += 100 }
        assertTrue("往右轉要是正的：${vy.rate}", vy.rate > 0)
        assertEquals(0.3, vy.rate, 0.06)
    }

    @Test
    fun straightIsZero() {
        val vy = VisualYaw()
        var t = 0L
        for (i in 0 until 10) { vy.update(t, scene(0.0), S, 100.0); t += 100 }
        assertEquals(0.0, vy.rate, 0.01)
    }
}
