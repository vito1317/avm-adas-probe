package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerspectiveMapperTest {

    // 3D 格子佔整個 2000x1000 畫面，鏡頭在正後方
    private val base = PerspectiveMapper.Params(
        clipL = 0f, clipT = 0f, clipR = 1f, clipB = 1f,
        azDeg = 0f, elDeg = 30f, dist = 10f, fovDeg = 60f, tx = 0f, tz = 0f
    )

    @Test
    fun targetLandsInPaneCenter() {
        val m = PerspectiveMapper(base, 2000, 1000)
        val p = m.project(0f, 0f, 0f)!!
        assertEquals(1000f, p[0], 1e-2f)
        assertEquals(500f, p[1], 1e-2f)
    }

    @Test
    fun fromBehindRightIsRightAndAheadIsUp() {
        val m = PerspectiveMapper(base, 2000, 1000)
        assertTrue(m.project(2f, 0f, 0f)!![0] > 1000f)   // 右邊 → 螢幕右
        assertTrue(m.project(0f, 0f, 5f)!![1] < 500f)    // 前方地面 → 螢幕上方
        assertTrue(m.project(0f, 1.5f, 0f)!![1] < 500f)  // 往上 → 螢幕上方
    }

    @Test
    fun cameraFromLeftSeesNoseToTheLeft() {
        // 站在車的左側看車：車頭朝你的左手邊 → 畫面上車頭在左、車尾在右
        val m = PerspectiveMapper(base.copy(azDeg = -60f), 2000, 1000)
        assertTrue(m.project(0f, 0f, 4f)!![0] < m.project(0f, 0f, -4f)!![0])
    }

    @Test
    fun mirrorFlipsLeftRight() {
        val l = PerspectiveMapper(base.copy(azDeg = -40f, tx = 0.5f), 2000, 1000)
        val r = PerspectiveMapper(base.copy(azDeg = -40f, tx = 0.5f).mirrored(), 2000, 1000)
        val a = l.project(1f, 0f, 3f)!!
        val b = r.project(-1f, 0f, 3f)!!
        assertEquals(2000f - a[0], b[0], 1e-2f)
        assertEquals(a[1], b[1], 1e-2f)
    }

    @Test
    fun pointsBehindTheCameraAreDropped() {
        val m = PerspectiveMapper(base, 2000, 1000)
        assertNull(m.project(0f, 0f, -30f))   // 鏡頭在 z ≈ -8.7，這點在它背後
        assertNotNull(m.project(0f, 0f, -5f))
    }

    @Test
    fun widerFovMakesThingsSmaller() {
        val narrow = PerspectiveMapper(base.copy(fovDeg = 40f), 2000, 1000).project(2f, 0f, 0f)!!
        val wide = PerspectiveMapper(base.copy(fovDeg = 80f), 2000, 1000).project(2f, 0f, 0f)!!
        assertTrue(narrow[0] - 1000f > wide[0] - 1000f)
    }
}

class PerspectiveUnprojectTest {
    @org.junit.Test
    fun unprojectInvertsProject() {
        val p = PerspectiveMapper.Params(0.4f, 0f, 1f, 0.9f, 35f, 22f, 9f, 55f, -1.5f, 3f)
        val m = PerspectiveMapper(p, 1920, 720)
        for ((x, z) in listOf(0f to 2f, -3f to 5f, 2.5f to -1f, 1f to 8f)) {
            val s = m.project(x, 0f, z) ?: continue
            val g = m.unprojectToGround(s[0], s[1])!!
            org.junit.Assert.assertEquals(x, g[0], 1e-3f)
            org.junit.Assert.assertEquals(z, g[1], 1e-3f)
        }
    }
}
