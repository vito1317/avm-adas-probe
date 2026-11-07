package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TrafficLightBlobsTest {
    private val S = 120
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** 天空背景 + 暗燈箱 + 一顆亮燈。 */
    private fun scene(lamp: Int?, lx: Int = 60, ly: Int = 30, box: Boolean = true): IntArray {
        val a = IntArray(S * S) { rgb(170, 190, 210) }
        if (box) for (y in ly - 8..ly + 8) for (x in lx - 8..lx + 8) a[y * S + x] = rgb(30, 30, 30)
        if (lamp != null) for (y in ly - 2..ly + 2) for (x in lx - 2..lx + 2) a[y * S + x] = lamp
        return a
    }

    @Test
    fun redLightInDarkHousingIsFoundAfterThreeFrames() {
        val d = TrafficLightBlobs()
        val img = scene(rgb(255, 40, 30))
        assertNull(d.update(img, S)); assertNull(d.update(img, S))
        val l = assertNotNull(d.update(img, S)).let { d.update(img, S)!! }
        assertEquals("red", l.state)
        assertEquals(60f, l.x, 1.5f)
    }

    @Test
    fun greenLedIsGreen() {
        val d = TrafficLightBlobs()
        val img = scene(rgb(40, 230, 170))
        repeat(3) { d.update(img, S) }
        assertEquals("green", d.update(img, S)!!.state)
    }

    @Test
    fun brightRedWithoutHousingIsIgnored() {
        // 招牌、車尾燈：周圍不是暗的燈箱
        val d = TrafficLightBlobs()
        val img = scene(rgb(255, 40, 30), box = false)
        repeat(5) { d.update(img, S) }
        assertNull(d.update(img, S))
    }

    @Test
    fun lowerPartOfImageIgnored() {
        val d = TrafficLightBlobs()
        val img = scene(rgb(255, 40, 30), ly = 105)
        repeat(5) { d.update(img, S) }
        assertNull(d.update(img, S))
    }
}
