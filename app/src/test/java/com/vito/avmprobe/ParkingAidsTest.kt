package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkingAidsTest {
    private val pa = ParkingAids()

    /** 倒車路徑：從 (0,−1.3) 往後，車尾往 [side]（+1 右）彎，半徑 5 m。 */
    private fun reverseArc(side: Int): List<FloatArray> {
        val r = 5.0
        return (0..20).map { k -> val t = k * 0.06; floatArrayOf((side * r * (1 - Math.cos(t))).toFloat(), (-1.3 - r * Math.sin(t)).toFloat()) }
    }

    @Test
    fun reversingIntoRightTellsSteerRight() {
        val m = pa.steering(reverseArc(1), 0, 'R', 0f, 5f)!!
        assertTrue(m.text, m.text.contains("往右"))
        val l = pa.steering(reverseArc(-1), 0, 'R', 0f, 5f)!!
        assertTrue(l.text, l.text.contains("往左"))
    }

    @Test
    fun steerAlreadyRightIsOk() {
        val wheel = Math.toDegrees(Math.atan(VehicleGeometry().wheelbase / 5.0)) * VehicleGeometry().steerRatio
        val m = pa.steering(reverseArc(1), 0, 'R', wheel.toFloat(), 5f)!!
        assertTrue(m.text, m.text.contains("OK"))
    }

    @Test
    fun beforeReverseTellsDriveForwardFirst() {
        val pts = listOf(floatArrayOf(0f, -1.3f), floatArrayOf(0f, 0.7f), floatArrayOf(0f, 1.7f), floatArrayOf(0.2f, 0f))
        assertTrue(pa.steering(pts, 2, 'D', 0f, 5f)!!.text.contains("往前"))
    }

    @Test
    fun alignmentInSlot() {
        // 格子 x −1.25..1.35（自車偏左 5 cm），長 5 m
        val s = ParkingSlot(floatArrayOf(-1.25f, 2.6f, 1.35f, 2.6f, 1.35f, -2.6f, -1.25f, -2.6f), 'p', true, 0.9f)
        val m = pa.alignment(listOf(s), 0f)!!
        assertTrue(m.text, m.text.contains("左 0.33") && m.text.contains("右 0.43"))
        assertNull(pa.alignment(listOf(s), 10f))
    }

    @Test
    fun narrowGapLevels() {
        val wall = listOf(ParkingAids.Thing(-1.6f, 5f, 0.5f), ParkingAids.Thing(1.6f, 5f, 0.5f))   // 中間 2.2 m
        assertEquals(1, pa.narrowGap(wall, 5f, 'D')!!.level)
        val tight = listOf(ParkingAids.Thing(-1.3f, 5f, 0.5f), ParkingAids.Thing(1.3f, 5f, 0.5f))   // 1.6 m
        assertEquals(2, pa.narrowGap(tight, 5f, 'D')!!.level)
        assertNull(pa.narrowGap(listOf(ParkingAids.Thing(-1.6f, 5f, 0.5f)), 5f, 'D'))
    }

    @Test
    fun snapshotOnceAfterParking() {
        val p = ParkingAids()
        var t = 0L
        repeat(20) { assertTrue(!p.shouldSnapshot(t, 3f, 'R')); t += 500 }
        var shots = 0
        repeat(40) { if (p.shouldSnapshot(t, 0f, null)) shots++; t += 500 }
        assertEquals(1, shots)
    }
}
