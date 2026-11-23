package com.vito.avmprobe

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LaneRibbonTest {
    private fun line(k: String, d: Int, x: Double) = JSONObject().put("k", k).put("d", d).put("x1", x).put("z1", -3.0).put("x2", x).put("z2", 4.0)
    private fun payload(vararg l: JSONObject) = JSONObject().put("road", JSONObject().put("lines", JSONArray().apply { l.forEach { put(it) } }))

    @Test fun bothSides() {
        val r = LaneRibbon().update(payload(line("y", 0, -1.7), line("w", 1, 1.8)), 1000)
        assertNotNull(r)
        assertEquals(-1.7f, r!!.left[3][0], 1e-3f)
        assertEquals(1.8f, r.right[3][0], 1e-3f)
        assertEquals('y', r.leftLine!!.kind); assertEquals(false, r.leftLine!!.dashed)
        assertEquals(true, r.rightLine!!.dashed)
        assertEquals(LaneRibbon.AHEAD_M, r.left.last()[1], 1e-3f)
    }

    @Test fun oneSideInfersOther() {
        val r = LaneRibbon().update(payload(line("y", 0, -1.7)), 1000)!!
        assertNull(r.rightLine)
        assertEquals(-1.7f + 3.4f, r.right[3][0], 1e-3f)
    }

    @Test fun holdsBrieflyThenDrops() {
        val rb = LaneRibbon()
        rb.update(payload(line("w", 0, -1.7), line("w", 1, 1.8)), 1000)
        assertNotNull(rb.update(payload(), 1500))           // 0.5 秒沒看到：撐著
        assertNull(rb.update(payload(), 3000))              // 2 秒沒看到：不畫
    }
}
