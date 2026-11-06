package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LaneChangeRuleTest {

    private fun line(x: Float, kind: Char = 'w', dashed: Boolean = false) =
        RoadLine(kind, dashed, x, -3f, x, 8f, 0.8f)

    @Test
    fun dashedLineAllowsLaneChange() {
        assertNull(LaneChangeRule.check(listOf(line(-1.7f, dashed = true)), 'L'))
    }

    @Test
    fun solidWhiteOnLeftForbids() {
        val v = LaneChangeRule.check(listOf(line(-1.7f)), 'L')
        assertNotNull(v); assertEquals('w', v!!.kind); assertEquals(false, v.double)
        assertEquals("◀ 白實線 禁止變換車道", LaneChangeRule.label(v))
    }

    @Test
    fun doubleYellowDetected() {
        val v = LaneChangeRule.check(listOf(line(-1.7f, 'y'), line(-1.9f, 'y')), 'L')!!
        assertEquals(true, v.double); assertEquals('y', v.kind)
        assertEquals("◀ 雙黃線 禁止跨越", LaneChangeRule.label(v))
    }

    @Test
    fun rightEdgeLineAloneIsCurbNotLaneDivider() {
        // 右邊只有一條白實線、外面沒線 → 路邊線，打右燈靠邊停是合法的
        assertNull(LaneChangeRule.check(listOf(line(1.7f)), 'R'))
    }

    @Test
    fun rightSolidWithLaneBeyondForbids() {
        val v = LaneChangeRule.check(listOf(line(1.7f), line(5.2f - 0.9f, dashed = true)), 'R')
        assertNotNull(v)
    }

    @Test
    fun rightDoubleWhiteForbidsEvenWithoutLineBeyond() {
        assertNotNull(LaneChangeRule.check(listOf(line(1.7f), line(1.95f)), 'R'))
    }

    @Test
    fun nearestLineDecides() {
        // 最近的是虛線（可以換），更外面的實線不管
        assertNull(LaneChangeRule.check(listOf(line(-1.6f, dashed = true), line(-5.2f)), 'L'))
    }

    @Test
    fun redCurbIgnoredAndCrossingLinesIgnored() {
        assertNull(LaneChangeRule.check(listOf(line(-1.6f, 'r')), 'L'))
        // 橫的線（停止線）不算
        assertNull(LaneChangeRule.check(listOf(RoadLine('w', false, -3f, 2f, 0f, 2.2f, 0.8f)), 'L'))
    }
}
