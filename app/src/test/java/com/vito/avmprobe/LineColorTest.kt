package com.vito.avmprobe

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineColorTest {
    private val w = 300; private val h = 350; private val m = 0.04; private val ox = 150.0; private val oy = 175.0

    /** 灰色柏油上、x = [x] 處一條縱向的線（寬 12 cm），顏色 [col]。 */
    private fun bev(x: Double, col: Int, road: Int = 0x505050): IntArray {
        val a = IntArray(w * h) { road }
        val i0 = Math.floor((x - 0.06) / m + ox).toInt(); val i1 = Math.floor((x + 0.06) / m + ox).toInt()
        for (j in 0 until h) for (i in i0..i1) a[j * w + i] = col
        return a
    }

    @Test fun yellowLeftIsYellowWhiteRightIsWhite() {
        val y = LineColor.features(bev(-1.8, 0xD8B040), w, h, m, ox, oy, 0.0, -1.8, -3.0, 3.0, false)
        assertNotNull(y)
        assertTrue(LineColor.yellowProb(y!!, -1.8, true, false) > 0.7f)
        val wt = LineColor.features(bev(1.8, 0xE0E0E0), w, h, m, ox, oy, 0.0, 1.8, -3.0, 3.0, false)
        assertNotNull(wt)
        assertTrue(LineColor.yellowProb(wt!!, 1.8, false, true) < 0.3f)
    }

    @Test fun tooFewPointsNull() {
        assertNull(LineColor.features(IntArray(w * h), w, h, m, ox, oy, 0.0, 1.8, -3.0, 3.0, false))
    }
}
