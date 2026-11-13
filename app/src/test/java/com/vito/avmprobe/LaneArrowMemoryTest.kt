package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.vito.avmprobe.LaneArrowMemory.Kind

class LaneArrowMemoryTest {
    private fun arrow(k: Kind, x: Float) = LaneArrowMemory.Arrow(k, x, egoLaneCenterX = 0f, laneWidth = 3.4f)

    @Test fun remembersAfterArrowPassesUnderCar() {
        val m = LaneArrowMemory()
        repeat(3) { m.observe(arrow(Kind.LEFT, 0.1f)); m.advance(2f, 0f) }
        // 箭頭壓過去之後再開 40 m，還是記得自車車道是左轉道
        repeat(20) { m.advance(2f, 0f) }
        assertEquals(Kind.LEFT, m.current(0))
    }

    @Test fun oneMisreadDoesNotFlip() {
        val m = LaneArrowMemory()
        repeat(4) { m.observe(arrow(Kind.STRAIGHT, 0f)) }
        m.observe(arrow(Kind.RIGHT, 0f))
        assertEquals(Kind.STRAIGHT, m.current(0))
    }

    @Test fun laneChangeShiftsMemory() {
        val m = LaneArrowMemory()
        repeat(3) { m.observe(arrow(Kind.STRAIGHT, 0f)); m.observe(arrow(Kind.LEFT, -3.3f)) }
        assertEquals(Kind.LEFT, m.current(-1))
        m.laneChanged(-1)                    // 換到左邊那道
        assertEquals(Kind.LEFT, m.current(0))
        assertEquals(Kind.STRAIGHT, m.current(1))
    }

    @Test fun forgetsAfterTurningAtIntersection() {
        val m = LaneArrowMemory()
        repeat(3) { m.observe(arrow(Kind.LEFT, 0f)) }
        repeat(10) { m.advance(1.5f, -8f) }  // 左轉 80°
        assertNull(m.current(0))
    }

    @Test fun ignoresArrowOnTheLine() {
        val m = LaneArrowMemory()
        repeat(5) { m.observe(arrow(Kind.RIGHT, 1.7f)) }   // 正好在兩道中間的線上
        assertTrue(m.snapshot().isEmpty())
    }

    @Test fun turnSignalConflict() {
        assertTrue(LaneArrowMemory.conflicts(Kind.STRAIGHT, 'L'))
        assertTrue(!LaneArrowMemory.conflicts(Kind.STRAIGHT_LEFT, 'L'))
    }
}
