package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotSelectionTest {

    /** 左邊一格：x −3.6～−1.2、z −2.5～2.5。 */
    private fun slot(dx: Float = 0f, dz: Float = 0f, occ: Boolean = false) = ParkingSlot(
        floatArrayOf(-1.2f + dx, 2.5f + dz, -3.6f + dx, 2.5f + dz, -3.6f + dx, -2.5f + dz, -1.2f + dx, -2.5f + dz), 'p', occ, 0.8f
    )

    @Test
    fun tapInsideSelectsAndTapAgainClears() {
        val s = SlotSelection()
        assertTrue(s.tap(listOf(slot()), -2.4f, 0f, 0))
        assertNotNull(s.center)
        assertTrue(s.tap(listOf(slot()), -2.0f, 1f, 100))
        assertNull(s.center)
    }

    @Test
    fun tapOutsideIsNotConsumed() {
        val s = SlotSelection()
        assertFalse(s.tap(listOf(slot()), 2f, 0f, 0))   // 右邊沒格子 → 交給原廠
        assertNull(s.center)
    }

    @Test
    fun occupiedSlotCannotBeSelected() {
        assertFalse(SlotSelection().tap(listOf(slot(occ = true)), -2.4f, 0f, 0))
    }

    @Test
    fun followsSlotAsCarMoves() {
        val s = SlotSelection()
        s.tap(listOf(slot()), -2.4f, 0f, 0)
        // 車往前開 0.5 m → 格子在自車座標裡往後移 0.5 m；旁邊還有一格
        val moved = listOf(slot(dz = -0.5f), slot(dx = -2.6f, dz = -0.5f))
        val t = s.track(moved, 1000)
        assertNotNull(t)
        assertEquals(-2.4f, SlotSelection.center(t!!)[0], 1e-3f)
        assertEquals(-0.5f, SlotSelection.center(t)[1], 1e-3f)
        assertTrue(s.isSelected(t))
        assertFalse(s.isSelected(moved[1]))
    }

    @Test
    fun losesSelectionAfterLongAbsence() {
        val s = SlotSelection()
        s.tap(listOf(slot()), -2.4f, 0f, 0)
        assertNull(s.track(emptyList(), 5_000))
        assertNotNull(s.center)                     // 暫時看不到：還記著
        s.track(emptyList(), SlotSelection.LOSE_MS + 5_000)
        assertNull(s.center)                        // 太久沒看到：取消
    }
}
