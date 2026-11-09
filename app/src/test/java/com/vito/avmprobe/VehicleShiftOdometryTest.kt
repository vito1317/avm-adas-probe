package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleShiftOdometryTest {
    private fun o(id: Int, x: Float, z: Float) = VehicleShiftOdometry.Obj(id, x, z)

    @Test
    fun carsShiftLeftMeansWeMovedRight() {
        val v = VehicleShiftOdometry()
        v.update(listOf(o(1, -3.5f, 12f), o(2, 3.5f, 20f), o(3, -3.4f, -5f)), 0.0, 1)
        // 自己往右 0.3 m、車頭沒轉
        val dy = v.update(listOf(o(1, -3.8f, 12f), o(2, 3.2f, 20f), o(3, -3.7f, -5f)), 0.0, 1)
        assertEquals(0.3f, dy!!, 0.01f)
    }

    @Test
    fun headingChangeIsCompensated() {
        val v = VehicleShiftOdometry()
        v.update(listOf(o(1, 0f, 20f), o(2, 3.5f, 10f)), 0.0, 1)
        // 自己往右 0.2 m，同時車頭往右轉 0.05 rad：遠的車多偏 −dθ·z
        val dTh = 0.05
        val dy = v.update(listOf(o(1, -0.2f - 0.05f * 20f, 20f), o(2, 3.3f - 0.05f * 10f, 10f)), dTh, 1)
        assertEquals(0.2f, dy!!, 0.02f)
    }

    @Test
    fun singleCarCuttingAcrossIsIgnoredWithoutMatchingSignal() {
        val v = VehicleShiftOdometry()
        v.update(listOf(o(1, 0f, 15f)), 0.0, 0)
        // 前車自己往右移（看起來像我們往左），沒打燈 → 不信
        assertNull(v.update(listOf(o(1, 0.4f, 15f)), 0.0, 0))
    }

    @Test
    fun disagreeingCarsAreRejected() {
        val v = VehicleShiftOdometry()
        v.update(listOf(o(1, -3.5f, 10f), o(2, 3.5f, 10f), o(3, 0f, 15f)), 0.0, 1)
        // 一台往左、一台往右、一台不動 → 不一致
        assertNull(v.update(listOf(o(1, -3.9f, 10f), o(2, 3.9f, 10f), o(3, 0f, 15f)), 0.0, 1))
    }
}
