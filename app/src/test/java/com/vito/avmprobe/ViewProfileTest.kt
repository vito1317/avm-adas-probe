package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewProfileTest {

    @Test
    fun jsonRoundTrip() {
        val v = ViewProfile(
            AnnotationMapper.Params.DEFAULT.copy(cx = 0.2f, flip = true),
            PerspectiveMapper.Params.DEFAULT_LEFT.copy(azDeg = 42f, tz = 3.5f)
        )
        val back = ViewProfile.fromJson(v.toJson())
        assertEquals(v.top!!.cx, back.top!!.cx, 1e-6f)
        assertEquals(true, back.top!!.flip)
        assertEquals(42f, back.p3!!.azDeg, 1e-6f)
        assertEquals(3.5f, back.p3!!.tz, 1e-6f)
    }

    @Test
    fun missingPartsStayNull() {
        val back = ViewProfile.fromJson(ViewProfile(null, null).toJson())
        assertNull(back.top)
        assertNull(back.p3)
    }

    @Test
    fun sideViewsGet3dDefaults() {
        val g = AnnotationMapper.Params.DEFAULT
        assertEquals(PerspectiveMapper.Params.DEFAULT_LEFT, ViewProfile.defaultFor("BV_LEFT_ROTATE", g).p3)
        assertEquals(PerspectiveMapper.Params.DEFAULT_RIGHT, ViewProfile.defaultFor("BV_MW3D_RIGHT", g).p3)
        assertNull(ViewProfile.defaultFor("BV_FRONT", g).p3)
    }

    @Test
    fun counterpartsSwapSides() {
        assertEquals("BV_RIGHT_ROTATE", ViewProfile.counterpart("BV_LEFT_ROTATE"))
        assertEquals("BV_LEFT", ViewProfile.counterpart("BV_RIGHT"))
        assertNull(ViewProfile.counterpart("BV_REAR_3D"))
    }
}
