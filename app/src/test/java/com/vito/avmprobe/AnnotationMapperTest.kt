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
