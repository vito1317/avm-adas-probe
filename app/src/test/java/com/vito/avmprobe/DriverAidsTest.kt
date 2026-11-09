package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverAidsTest {
    private fun inp(now: Long, v: Float?, gear: Char?, objs: List<DriverAids.Obj>, door: Boolean = false, ldw: Boolean = false) =
        DriverAids.Input(now, v, gear, objs, door, ldw)
    private fun keys(m: List<DriverAids.Msg>) = m.map { it.key to it.level }

    @Test
    fun reversingWithCrossTrafficFromRight() {
        val a = DriverAids()
        val m = a.update(inp(0, 3f, 'R', listOf(DriverAids.Obj("car", 7f, -4f, -3f, 0f, 5f))))
        assertTrue(keys(m).contains("rcta" to 2))
        assertTrue(m.first { it.key == "rcta" }.text.contains("右"))
    }

    @Test
    fun crossTrafficMovingAwayIsIgnored() {
        val a = DriverAids()
        assertTrue(a.update(inp(0, 3f, 'R', listOf(DriverAids.Obj("car", 7f, -4f, 3f, 0f, 5f)))).none { it.key == "rcta" })
    }

    @Test
    fun doorWarningWhenScooterComesFromBehind() {
        val a = DriverAids()
        val scooter = DriverAids.Obj("moto", -1.8f, -10f, 0f, 4f, 7f)
        assertEquals(listOf("dow" to 1), keys(a.update(inp(0, 0f, null, listOf(scooter)))))
        assertEquals("車門打開了 → 危險", listOf("dow" to 2), keys(a.update(inp(100, 0f, null, listOf(scooter), door = true))))
    }

    @Test
    fun rearApproachTooFast() {
        val a = DriverAids()
        val m = a.update(inp(0, 0f, null, listOf(DriverAids.Obj("car", 0.2f, -8f, 0f, 6f, 5f))))
        assertTrue(keys(m).contains("rear" to 2))
    }

    @Test
    fun headwayLevels() {
        val a = DriverAids()
        // 72 km/h = 20 m/s，前車淨距 20 m → 1.0 秒 → 注意
        assertEquals(listOf("headway" to 1), keys(a.update(inp(0, 72f, 'D', listOf(DriverAids.Obj("car", 0.1f, 24f, 0f, 0f, 20f))))))
        // 淨距 12 m → 0.6 秒 → 危險
        assertEquals(listOf("headway" to 2), keys(a.update(inp(0, 72f, 'D', listOf(DriverAids.Obj("car", 0.1f, 16f, 0f, 0f, 12f))))))
    }

    @Test
    fun restReminderAfterTwoHours() {
        val a = DriverAids()
        var t = 0L
        while (t < DriverAids.DRIVE_LIMIT_MS) { assertTrue(a.update(inp(t, 80f, 'D', emptyList())).none { it.key == "rest" }); t += 60_000 }
        t += 60_000
        assertTrue(a.update(inp(t, 80f, 'D', emptyList())).any { it.key == "rest" })
    }
}
