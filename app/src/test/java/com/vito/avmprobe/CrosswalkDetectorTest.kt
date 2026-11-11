package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CrosswalkDetectorTest {
    private val W = 300; private val H = 350; private val MPP = 0.04f; private val CX = 150f; private val CY = 175f
    private val ego = listOf(floatArrayOf(-1.075f, -2.57f, 1.075f, 2.57f))

    private fun canvas() = IntArray(W * H) { val n = 70 + (it * 7919 % 13); (0xFF shl 24) or (n shl 16) or (n shl 8) or n }

    /** 在 (x0..x1, z0..z1) 畫白色長方形（公尺）。 */
    private fun rect(a: IntArray, x0: Float, x1: Float, z0: Float, z1: Float, v: Int = 225) {
        for (j in 0 until H) for (i in 0 until W) {
            val x = (i + 0.5f - CX) * MPP; val z = (CY - j - 0.5f) * MPP
            if (x in x0..x1 && z in z0..z1) a[j * W + i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
    }

    @Test fun zebraAheadIsFound() {
        val a = canvas()
        // 車頭前方 3.5～6.5 m：沿行車方向的白條，每條寬 0.45 m、中心距 0.9 m，從 x = -4 排到 +4
        var x = -4f
        while (x <= 4f) { rect(a, x - 0.225f, x + 0.225f, 3.5f, 6.5f); x += 0.9f }
        val r = CrosswalkDetector().detect(a, W, H, MPP, CX, CY, null, ego)
        assertEquals("一塊斑馬線：$r", 1, r.size)
        val c = r[0]
        assertTrue("白條數 ${c.stripes}", c.stripes >= 7)
        assertTrue("在車頭前方約 1 m：${c.ahead(2.57f)}", abs(c.ahead(2.57f) - 0.93f) < 0.3f)
        assertTrue("白條是縱向的", abs(c.uz) > 0.95f)
    }

    @Test fun laneDashesAreNotZebra() {
        val a = canvas()
        // 兩條車道虛線：寬 0.15 m、長 3 m，前後排，左右相距 3.5 m
        for (lx in listOf(-1.75f, 1.75f)) for (z0 in listOf(-6f, 0f, 6f).map { it - 1.5f }) rect(a, lx - 0.075f, lx + 0.075f, z0, z0 + 3f)
        assertTrue(CrosswalkDetector().detect(a, W, H, MPP, CX, CY, null, ego).isEmpty())
    }

    @Test fun parkingSlotLinesAreNotZebra() {
        val a = canvas()
        // 垂直停車格：分隔線寬 0.12 m、間距 2.5 m
        var x = -5f
        while (x <= 5f) { rect(a, x - 0.06f, x + 0.06f, 3f, 6.5f); x += 2.5f }
        assertTrue(CrosswalkDetector().detect(a, W, H, MPP, CX, CY, null, ego).isEmpty())
    }

    @Test fun twoStripesAreNotEnough() {
        val a = canvas()
        rect(a, -0.7f, -0.25f, 3.5f, 6f); rect(a, 0.2f, 0.65f, 3.5f, 6f)
        assertTrue(CrosswalkDetector().detect(a, W, H, MPP, CX, CY, null, ego).isEmpty())
    }
}
