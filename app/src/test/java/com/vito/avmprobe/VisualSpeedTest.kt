package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Test

class VisualSpeedTest {
    private val W = 300; private val H = 350; private val MPP = 0.04f; private val OX = 150f; private val OY = 175f
    private val noise = run { val r = java.util.Random(5); IntArray(5000) { 40 + r.nextInt(180) } }

    /** 地面紋理（隨機條紋），自車往前走了 [s] 公尺 → 地面在影像裡往下移。 */
    private fun bev(s: Double): IntArray {
        val a = IntArray(W * H)
        for (j in 0 until H) {
            val zWorld = (OY - (j + 0.5)) * MPP + s          // 這一列對應的地面位置（世界座標）
            // 路面紋理：隨機粗細紋（每 4 cm 一個亂數再平滑），不會週期性重複
            val k = Math.floorMod(Math.floor(zWorld / 0.04).toInt(), noise.size)
            val v = ((noise[k] + noise[(k + 1) % noise.size] + noise[(k + 2) % noise.size]) / 3).coerceIn(0, 255)
            for (i in 0 until W) a[j * W + i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return a
    }

    @Test
    fun measuresForwardSpeed() {
        val c = VisualSpeed.Core()
        // 18 km/h = 5 m/s，每 0.1 秒走 0.5 m
        var t = 0L
        for (i in 0 until 20) { c.update(t, bev(i * 0.5), W, H, MPP, OX, OY); t += 100 }
        assertEquals(5.0, c.speed, 0.4)
    }

    @Test
    fun stationaryIsZero() {
        val c = VisualSpeed.Core()
        var t = 0L
        for (i in 0 until 10) { c.update(t, bev(0.0), W, H, MPP, OX, OY); t += 100 }
        assertEquals(0.0, c.speed, 0.05)
    }

    @Test
    fun reversingIsNegative() {
        val c = VisualSpeed.Core()
        var t = 0L
        for (i in 0 until 20) { c.update(t, bev(-i * 0.1), W, H, MPP, OX, OY); t += 100 }
        assertEquals(-1.0, c.speed, 0.2)
    }
}
