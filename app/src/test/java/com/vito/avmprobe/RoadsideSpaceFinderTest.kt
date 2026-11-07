package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadsideSpaceFinderTest {
    private fun car(x: Float, z: Float) = RoadsideSpaceFinder.Obj("car", x, z)

    /** 以 10 km/h 開過一排路邊停車（世界座標 s 上的車），每 0.1 秒一幀。 */
    private fun drive(f: RoadsideSpaceFinder, parked: List<Float>, untilS: Double, turn: Char? = 'H'): RoadsideSpaceFinder.Spot? {
        var s = 0.0; var t = 0L; var spot: RoadsideSpaceFinder.Spot? = null
        while (s < untilS) {
            val objs = parked.map { it - s.toFloat() }.filter { it > -5f && it < 6f }.map { car(2.6f, it) }
            spot = f.update(t, 10f, turn, null, objs, null, false)
            t += 100; s += 10 / 3.6 * 0.1
        }
        return spot
    }

    @Test
    fun findsGapBetweenParkedCars() {
        val f = RoadsideSpaceFinder()
        // 路邊車子中心在 0、5.5、（空 8 m）、18.5、24 → 11.0～16.1 之間有 7.1 m 的空隙
        val spot = drive(f, listOf(0f, 5.5f, 18.5f, 24f), 22.0)
        assertNotNull(spot); spot!!
        assertTrue("空位要夠長：${spot.len}", spot.len >= RoadsideSpaceFinder.NEED_M - 0.01)
        assertEquals(2.6f, spot.x, 0.3f)
    }

    @Test
    fun noGapWhenCarsAreTight() {
        val f = RoadsideSpaceFinder()
        assertNull(drive(f, listOf(0f, 5.2f, 10.4f, 15.6f, 20.8f, 26f), 22.0))
    }

    @Test
    fun needsHazard() {
        val f = RoadsideSpaceFinder()
        assertNull(drive(f, listOf(0f, 5.5f, 18.5f, 24f), 22.0, turn = null))
    }

    @Test
    fun parallelPlanEndsInsideSpotStraight() {
        val f = RoadsideSpaceFinder()
        val spot = RoadsideSpaceFinder.Spot(2.6f, -8f, -1.5f)
        val p = f.plan(spot)!!
        val end = p.points.last()
        assertEquals("橫移到空位中心", 2.6f, end[0], 0.05f)
        assertTrue("後軸停在空位裡", end[1] > spot.z0 && end[1] < spot.z1)
        // 最後一段跟路邊平行（x 幾乎不變）
        val prev = p.points[p.points.size - 2]
        assertTrue(kotlin.math.abs(end[0] - prev[0]) < 0.05f)
        assertTrue("先往前開到起點再倒車", p.stopAheadM > 0f && p.reverseFrom > 0)
    }
}
