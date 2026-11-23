package com.vito.avmprobe

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineDashTest {
    private val w = 300; private val h = 350; private val m = 0.04; private val ox = 150.0; private val oy = 175.0

    /** 灰色柏油上 x = 1.8 m 的縱向白線；[painted] 決定每個 z 有沒有漆。模型機率跟著漆走。 */
    private fun scene(painted: (Double) -> Boolean): Pair<IntArray, FloatArray> {
        val rgb = IntArray(w * h) { 0x505050 }; val p = FloatArray(w * h)
        val i0 = Math.floor((1.8 - 0.06) / m + ox).toInt(); val i1 = Math.floor((1.8 + 0.06) / m + ox).toInt()
        for (j in 0 until h) {
            val z = (oy - (j + 0.5)) * m
            if (!painted(z)) continue
            for (i in i0..i1) { rgb[j * w + i] = 0xE8E8E8; p[j * w + i] = 0.95f }
        }
        return rgb to p
    }

    @Test fun solidLineIsSolid() {
        val (rgb, p) = scene { true }
        val d = LineDash.dashedProb(rgb, p, w, h, m, ox, oy, 0.0, 1.8)
        assertNotNull(d); assertTrue("實線的虛線機率 $d", d!! < 0.5)
    }

    @Test fun dashedLineIsDashed() {
        val (rgb, p) = scene { z -> ((z + 100.0) % 10.0) < 4.0 }       // 4 m 漆、6 m 空
        val d = LineDash.dashedProb(rgb, p, w, h, m, ox, oy, 0.0, 1.8)
        assertNotNull(d); assertTrue("虛線的虛線機率 $d", d!! > 0.5)
    }
}
