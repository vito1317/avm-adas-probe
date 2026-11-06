package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationMapperTest {

    private val p = AnnotationMapper.Params(
        clipL = 0f, clipT = 0f, clipR = 0.5f, clipB = 1f,
        cx = 0.25f, cy = 0.5f, scale = 0.1f, flip = false
    )

    @Test
    fun egoCenterMapsToConfiguredScreenPoint() {
        val m = AnnotationMapper(p, 2000, 1000)
        assertEquals(500f, m.sx(0f, 0f), 1e-3f)
        assertEquals(500f, m.sy(0f, 0f), 1e-3f)
    }

    @Test
    fun forwardIsUpAndRightIsRight() {
        val m = AnnotationMapper(p, 2000, 1000)   // 1 m = 100 px
        assertEquals(400f, m.sy(0f, 1f), 1e-3f)   // 前方 1 m → 往上 100 px
        assertEquals(600f, m.sx(1f, 0f), 1e-3f)   // 右方 1 m → 往右 100 px
    }

    @Test
    fun flipTurnsEverything180() {
        val m = AnnotationMapper(p.copy(flip = true), 2000, 1000)
        assertEquals(600f, m.sy(0f, 1f), 1e-3f)
        assertEquals(400f, m.sx(1f, 0f), 1e-3f)
        assertEquals(180f, m.screenAngleDeg(0f), 1e-3f)
    }

    @Test
    fun clipRectIsInScreenPixels() {
        val m = AnnotationMapper(p, 2000, 1000)
        assertTrue(m.inside(100f, 100f))
        assertFalse(m.inside(1500f, 100f))   // 右半是原廠的鏡頭畫面，不畫
    }
}

class CorridorTest {

    @Test
    fun straightPathGetsCarWidth() {
        val c = AnnotationMapper.corridor(listOf(floatArrayOf(0f, 0f), floatArrayOf(0f, 5f)), 0.925f)
        assertEquals(4, c.size)
        // 往前開：左緣在 x = -0.925、右緣在 x = +0.925
        assertEquals(-0.925f, c[0][0], 1e-4f); assertEquals(0f, c[0][1], 1e-4f)
        assertEquals(-0.925f, c[1][0], 1e-4f); assertEquals(5f, c[1][1], 1e-4f)
        assertEquals(0.925f, c[2][0], 1e-4f); assertEquals(5f, c[2][1], 1e-4f)
        assertEquals(0.925f, c[3][0], 1e-4f); assertEquals(0f, c[3][1], 1e-4f)
    }

    @Test
    fun sidewaysPathOffsetsAlongZ() {
        // 往右走（+x）：左側是前方（+z）
        val c = AnnotationMapper.corridor(listOf(floatArrayOf(0f, 0f), floatArrayOf(3f, 0f)), 1f)
        assertEquals(1f, c[0][1], 1e-4f)
        assertEquals(-1f, c[2][1], 1e-4f)
    }

    @Test
    fun widthStaysConstantAroundABend() {
        val pts = (0..20).map { i ->
            val a = i / 20.0 * Math.PI / 2
            floatArrayOf((5 - 5 * Math.cos(a)).toFloat(), (5 * Math.sin(a)).toFloat())
        }
        val c = AnnotationMapper.corridor(pts, 0.925f)
        val n = pts.size
        for (i in 0 until n) {
            val l = c[i]; val r = c[2 * n - 1 - i]
            assertEquals(1.85f, kotlin.math.hypot(l[0] - r[0], l[1] - r[1]), 1e-3f)
        }
    }

    @Test
    fun tooFewPointsGivesNothing() {
        assertTrue(AnnotationMapper.corridor(listOf(floatArrayOf(0f, 0f)), 1f).isEmpty())
    }
}
