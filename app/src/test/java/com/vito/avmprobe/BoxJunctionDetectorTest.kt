package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BoxJunctionDetectorTest {
    private val W = 300; private val H = 350; private val MPP = 0.04f; private val CX = 150f; private val CY = 175f
    private val ego = listOf(floatArrayOf(-1.075f, -2.57f, 1.075f, 2.57f))
    private val YEL = (0xFF shl 24) or (230 shl 16) or (190 shl 8) or 40
    private fun canvas() = IntArray(W * H) { val n = 70 + (it * 7919 % 13); (0xFF shl 24) or (n shl 16) or (n shl 8) or n }
    private fun paint(a: IntArray, f: (Float, Float) -> Boolean) {
        for (j in 0 until H) for (i in 0 until W) {
            val x = (i + 0.5f - CX) * MPP; val z = (CY - j - 0.5f) * MPP
            if (f(x, z)) a[j * W + i] = YEL
        }
    }

    @Test fun yellowCrossHatchAheadIsFound() {
        val a = canvas()
        // 前方 3～7 m、左右 ±2.5 m 的方框，裡面 ±45° 兩組斜線（間距 0.8 m、線寬 0.15 m）＋外框
        paint(a) { x, z ->
            if (x < -2.5f || x > 2.5f || z < 3f || z > 7f) false
            else {
                val edge = x < -2.35f || x > 2.35f || z < 3.15f || z > 6.85f
                val d1 = ((x + z) % 0.8f + 0.8f) % 0.8f; val d2 = ((x - z) % 0.8f + 0.8f) % 0.8f
                edge || d1 < 0.15f || d2 < 0.15f
            }
        }
        val r = BoxJunctionDetector().detect(a, W, H, MPP, CX, CY, null, ego)
        assertEquals("一塊網狀線：$r", 1, r.size)
        val p = r[0].p
        assertTrue(abs(p[0] + 2.5f) < 0.2f && abs(p[2] - 2.5f) < 0.2f && abs(p[1] - 3f) < 0.2f && abs(p[5] - 7f) < 0.2f)
    }

    @Test fun doubleYellowCentreLineIsNotHatch() {
        val a = canvas()
        paint(a) { x, _ -> (x in -2.2f..-2.05f) || (x in -1.9f..-1.75f) }
        assertTrue(BoxJunctionDetector().detect(a, W, H, MPP, CX, CY, null, ego).isEmpty())
    }

    @Test fun solidYellowBlockIsNotHatch() {
        val a = canvas()
        paint(a) { x, z -> x in -2f..2f && z in 3f..6f }        // 整片黃（例如黃色車、招牌倒影）
        assertTrue(BoxJunctionDetector().detect(a, W, H, MPP, CX, CY, null, ego).isEmpty())
    }

    @Test fun sodiumLitRoadIsNotHatch() {
        // 晚上鈉燈：整片路面偏黃（有些亮暗起伏），沒有漆
        val a = IntArray(W * H) { val k = (it * 7919 % 23); (0xFF shl 24) or ((150 + k) shl 16) or ((115 + k) shl 8) or (40 + k / 2) }
        assertTrue(BoxJunctionDetector().detect(a, W, H, MPP, CX, CY, null, ego).isEmpty())
    }
}
