package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Test

class SlotTrackerTest {
    private fun slot(z0: Float, depth: Float, occ: Boolean = false) =
        ParkingSlot(floatArrayOf(-1.2f, z0, -3.7f, z0, -3.7f, z0 - depth, -1.2f, z0 - depth), 'p', occ, 0.8f)

    @Test
    fun missedDetectionDoesNotFlicker() {
        val t = SlotTracker()
        t.update(listOf(slot(2.9f, 5.5f)), 0)
        assertEquals(1, t.update(listOf(slot(2.9f, 5.5f)), 300).size)
        // 漏了幾次（1.5 秒內）：照樣顯示
        assertEquals(1, t.update(emptyList(), 700).size)
        assertEquals(1, t.update(emptyList(), 1500).size)
        // 太久沒看到才消失
        assertEquals(0, t.update(emptyList(), 2600).size)
    }

    @Test
    fun differentDepthOfSameSlotIsOneSlot() {
        val t = SlotTracker()
        t.update(listOf(slot(2.9f, 5.5f)), 0)
        t.update(listOf(slot(2.9f, 5.5f)), 300)
        // 同一格這次深度不一樣（中心差 0.35 m）
        val out = t.update(listOf(slot(2.9f, 4.8f)), 600)
        assertEquals(1, out.size)
        assertEquals(1, t.update(listOf(slot(2.9f, 4.8f)), 900).size)
    }

    @Test
    fun occupancyNeedsTwoReadingsToFlip() {
        val t = SlotTracker()
        t.update(listOf(slot(2.9f, 5.5f)), 0)
        t.update(listOf(slot(2.9f, 5.5f)), 300)
        assertEquals(false, t.update(listOf(slot(2.9f, 5.5f, occ = true)), 600)[0].occupied)   // 一次不算
        assertEquals(true, t.update(listOf(slot(2.9f, 5.5f, occ = true)), 900)[0].occupied)    // 連續兩次才改
    }
}
