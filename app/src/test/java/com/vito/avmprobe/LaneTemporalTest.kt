package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaneTemporalTest {
    private val w = 50; private val h = 60; private val mpp = 0.1f; private val cx = 25f; private val cy = 30f
    private fun k(x: Double, z: Double): Int = (Math.floor(cy - z / mpp)).toInt() * w + Math.floor(x / mpp + cx).toInt()
    private val idx = IntArray(w * h) { it }                 // 全部是鏡頭 0、機率索引 = 俯視圖索引

    private fun moved(dz: Double, yaw: Double = 0.0): LaneTemporal = LaneTemporal().apply {
        odo(0, dz / 0.1, yaw / 0.1); odo(100, dz / 0.1, yaw / 0.1)   // 0.1 秒內往前 dz 公尺
    }

    @Test fun groundLineKeptCarFixedBlobSuppressed() {
        val t = moved(1.0)
        val prev = FloatArray(w * h)
        for (i in -10..10) prev[k(i * 0.1, 2.0)] = 0.9f      // 地上的橫線：上一次在前方 2 m
        prev[k(2.0, 1.0)] = 0.9f                             // 跟著車走的反光：一直在 (2, 1)
        val gate = FloatArray(w * h) { 1f }
        assertTrue(t.gate(0, idx, w, h, mpp, cx, cy, prev, 0, 100, gate))
        assertEquals(1f, gate[k(0.0, 1.0)], 1e-4f)            // 往前開 1 m → 現在在 1 m 處，對得上
        assertEquals(LaneTemporal.FLOOR, gate[k(2.0, 1.0)], 1e-4f)   // 反光：上一次 (2, 2) 那裡沒東西
    }

    @Test fun noMoveNoJudgement() {
        val t = moved(0.1)
        val gate = FloatArray(w * h) { 0f }
        assertTrue(!t.gate(0, idx, w, h, mpp, cx, cy, FloatArray(w * h), 0, 100, gate))
        assertEquals(1f, gate[k(0.0, 1.0)], 1e-4f)
    }

    @Test fun unknownOutsideKept() {
        val t = moved(1.0)
        val gate = FloatArray(w * h) { 0f }
        t.gate(0, idx, w, h, mpp, cx, cy, FloatArray(w * h), 0, 100, gate)
        assertEquals(1f, gate[k(0.0, 2.85)], 1e-4f)           // 對到上一次的俯視圖外面 → 不知道 → 保留
    }
}
