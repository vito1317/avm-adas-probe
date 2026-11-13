package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Test

class VehicleSpeedFilterTest {
    private var now = 1000L
    private fun sig(): VehicleSignals { now = 1000L; return VehicleSignals(clock = { now }) }

    @Test fun emergencyBrakingPassesThrough() {
        val s = sig()
        // 100 → 0 km/h 3 秒（約 0.95 g），每 0.2 秒一筆：每一筆都要即時反映
        var v = 100f
        s.reportSpeed(v)
        repeat(15) { now += 200; v -= 100f / 15; s.reportSpeed(v); assertEquals(v, s.snapshot().speedKmh!!, 0.01f) }
    }

    @Test fun garbageSpikeIsIgnoredButRealChangeConfirms() {
        val s = sig()
        s.reportSpeed(10f)
        now += 200; s.reportSpeed(157f)                   // 0.2 秒跳 147 km/h：垃圾
        assertEquals(10f, s.snapshot().speedKmh!!, 0.01f)
        now += 200; s.reportSpeed(12f)
        assertEquals(12f, s.snapshot().speedKmh!!, 0.01f)
    }

    @Test fun parkIsZero() {
        val s = sig()
        s.reportGear('P'); now += 100; s.reportSpeed(33f)
        assertEquals(0f, s.snapshot().speedKmh!!, 0.01f)
    }
}
