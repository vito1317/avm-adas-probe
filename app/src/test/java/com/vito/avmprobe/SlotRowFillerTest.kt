package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SlotRowFillerTest {
    /** 前方一排垂直格：格寬 2.5 m、深 5 m，中心 z = 6.5，第 k 格中心 x = k × 2.5。 */
    private fun slot(k: Int, occ: Boolean = false) = ParkingSlot(
        floatArrayOf(k * 2.5f - 1.25f, 4f, k * 2.5f + 1.25f, 4f, k * 2.5f + 1.25f, 9f, k * 2.5f - 1.25f, 9f), 'p', occ, 0.8f)

    @Test fun parkedCarsBesideFreeSlotGetSlots() {
        // 只認出 x=0 那一格空位；兩邊各停一台車（x = -2.5、+5.1），還有一台在馬路上（不在這排）
        val cars = listOf(SlotRowFiller.Car(-2.5f, 6.6f), SlotRowFiller.Car(5.1f, 6.3f), SlotRowFiller.Car(1.0f, -6f))
        val add = SlotRowFiller.fill(listOf(slot(0)), cars)
        assertEquals("補兩格有車的", 2, add.size)
        assertTrue(add.all { it.occupied })
        val xs = add.map { SlotSelection.center(it)[0] }.sorted()
        assertTrue("補在 -2.5 與 +5：$xs", abs(xs[0] + 2.5f) < 0.01f && abs(xs[1] - 5f) < 0.01f)
    }

    @Test fun carInsideExistingSlotOrOffRowIsIgnored() {
        val cars = listOf(SlotRowFiller.Car(2.5f, 6.5f), SlotRowFiller.Car(3.7f, 6.5f))   // 一台在已有的格子裡、一台卡在兩格中間
        val add = SlotRowFiller.fill(listOf(slot(0), slot(1, occ = true)), cars)
        assertTrue("已有格子的不補、沒對齊的不補：$add", add.isEmpty())
    }
}
