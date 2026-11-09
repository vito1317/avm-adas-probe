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

class SlotMemoryTest {
    private fun slot(cx: Float, cz: Float) = ParkingSlot(floatArrayOf(cx - 1.2f, cz + 2.5f, cx + 1.2f, cz + 2.5f, cx + 1.2f, cz - 2.5f, cx - 1.2f, cz - 2.5f), 'p', false, 0.9f)

    @org.junit.Test
    fun slotIsRememberedAfterLeavingViewAndMovesWithCar() {
        val t = SlotTracker(); t.viewX = 9.5f; t.viewZ = 9f
        var now = 0L
        repeat(3) { t.update(listOf(slot(3f, 2f)), now); now += 200 }
        // 往前開 12 m，格子跑出偵測範圍（z = −10），偵測不到了
        for (i in 0 until 12) { t.egoMotion(1f, 0f); t.update(emptyList(), now); now += 200 }
        val out = t.update(emptyList(), now)
        org.junit.Assert.assertEquals("跑出畫面還要記得", 1, out.size)
        val cz = (out[0].p[1] + out[0].p[3] + out[0].p[5] + out[0].p[7]) / 4f
        org.junit.Assert.assertEquals(-10f, cz, 0.3f)
    }

    @org.junit.Test
    fun slotStillInViewButNotSeenIsDropped() {
        val t = SlotTracker(); t.viewX = 9.5f; t.viewZ = 9f
        var now = 0L
        repeat(3) { t.update(listOf(slot(3f, 2f)), now); now += 200 }
        now += SlotTracker.HOLD_MS + 500
        org.junit.Assert.assertEquals("還在範圍內卻一直沒偵測到 → 刪掉（可能是誤判）", 0, t.update(emptyList(), now).size)
    }
}
