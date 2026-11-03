package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficLightStateTest {
    private val F = PerceptionFixtures

    private fun crop(img: PerceptionFixtures.Img, x: Int, y: Int, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (j in 0 until h) System.arraycopy(img.argb, (y + j) * img.w + x, out, j * w, w)
        return out
    }

    @Test
    fun syntheticCropsMatchTruthAndPython() {
        val doc = F.json("tl_atlas.json")
        val img = F.image("tl_atlas.png")
        val items = F.objs(doc.getJSONArray("items"))
        assertTrue(items.size >= 24)
        val seen = HashSet<String>()
        for ((k, it) in items.withIndex()) {
            val w = it.getInt("w")
            val h = it.getInt("h")
            val (s, c) = TrafficLightState.classify(crop(img, it.getInt("x"), it.getInt("y"), w, h), w, h)
            val tag = "#$k ${it.getString("state")} ${if (it.getBoolean("vertical")) "直" else "橫"} ${w}x$h"
            assertEquals("$tag 真值", it.getString("state"), s)
            assertEquals("$tag 與 Python 一致", it.getString("py"), s)
            assertEquals("$tag 信心與 Python 一致", it.getDouble("pyConf"), c.toDouble(), 0.02)
            assertTrue("$tag 信心範圍", c in 0f..1f)
            seen.add(s)
        }
        assertEquals(setOf("red", "yellow", "green", "off"), seen)
    }

    private fun solid(w: Int, h: Int, rgb: Int) = IntArray(w * h) { (0xFF shl 24) or rgb }

    /** 暗燈箱 + 一顆亮燈（直式），燈的顏色跟位置自己給。 */
    private fun housing(w: Int, h: Int, lamp: Int, slot: Int, housingRgb: Int = 0x202020): IntArray {
        val a = solid(w, h, housingRgb)
        val cy = (slot + 0.5) * h / 3.0
        val cx = w / 2.0
        val r = 0.36 * w
        for (j in 0 until h) for (i in 0 until w) {
            val dx = i + 0.5 - cx
            val dy = j + 0.5 - cy
            if (dx * dx + dy * dy <= r * r) a[j * w + i] = (0xFF shl 24) or lamp
        }
        return a
    }

    @Test
    fun darkHousingIsOff() {
        assertEquals("off", TrafficLightState.classify(solid(12, 32, 0x1E1E22), 12, 32).first)
        // 偏紅的暗燈箱也不能叫紅燈
        assertEquals("off", TrafficLightState.classify(solid(12, 32, 0x3C1A1A), 12, 32).first)
        assertEquals("off", TrafficLightState.classify(housing(14, 40, 0x461612, 0), 14, 40).first)
    }

    @Test
    fun ledColours() {
        assertEquals("red", TrafficLightState.classify(housing(14, 40, 0xFF2A1E, 0), 14, 40).first)
        assertEquals("yellow", TrafficLightState.classify(housing(14, 40, 0xFFAA10, 1), 14, 40).first)
        // LED 綠常偏青
        assertEquals("green", TrafficLightState.classify(housing(14, 40, 0x20FFC8, 2), 14, 40).first)
        assertEquals("green", TrafficLightState.classify(housing(14, 40, 0x40FFE6, 2), 14, 40).first)
    }
}
