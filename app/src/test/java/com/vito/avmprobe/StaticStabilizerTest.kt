package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class StaticStabilizerTest {
    @Test fun parkedCarStopsJitteringWhileEgoCreeps() {
        // 自車 5 km/h 往前開，旁邊停著一台車（世界座標固定在 x=3、z=8）；偵測位置每次亂跳 ±0.6 m
        val s = StaticStabilizer(); val rnd = Random(1)
        var egoZ = 0f; var t = 0L
        val v = 5f / 3.6f
        var worstRaw = 0f; var worstOut = 0f
        repeat(100) { i ->
            val dz = v * 0.1f
            egoZ += dz; s.egoMotion(dz, 0f); t += 100
            val trueZ = 8f - egoZ
            val rx = 3f + (rnd.nextFloat() - 0.5f) * 1.2f; val rz = trueZ + (rnd.nextFloat() - 0.5f) * 1.2f
            val (x, z, _) = s.apply(7, rx, rz, 0.3f, null, t)
            if (i > 20) { worstRaw = maxOf(worstRaw, abs(rz - trueZ)); worstOut = maxOf(worstOut, abs(z - trueZ), abs(x - 3f)) }
        }
        assertTrue("穩定後誤差 $worstOut 要比原始 $worstRaw 小很多", worstOut < 0.3f && worstOut < worstRaw / 2)
    }

    @Test fun yawLocksToSlotAndIgnoresMissingHints() {
        val s = StaticStabilizer()
        val (_, _, y1) = s.apply(3, 2f, 5f, 0.2f, 1.57f, 0)
        val (_, _, y2) = s.apply(3, 2.1f, 5f, 0.2f, null, 100)
        assertEquals(1.57f, y1!!, 1e-3f)
        assertEquals("沒有提示時沿用上次的方向", 1.57f, y2!!, 1e-3f)
    }

    @Test fun movingCarIsNotHeld() {
        val s = StaticStabilizer()
        s.apply(5, 2f, 5f, 0.2f, null, 0)
        val (x, z, _) = s.apply(5, 2f, 9f, 3f, null, 100)
        assertEquals(9f, z, 1e-3f); assertEquals(2f, x, 1e-3f)
    }

    @Test fun realJumpIsAcceptedAfterAFewFrames() {
        val s = StaticStabilizer()
        repeat(5) { s.apply(9, 2f, 5f, 0.1f, null, it * 100L) }
        val a = s.apply(9, 2f, 12f, 0.1f, null, 600)
        assertTrue("第一次大跳當雜訊", abs(a.second - 5f) < 0.5f)
        s.apply(9, 2f, 12f, 0.1f, null, 700)
        val c = s.apply(9, 2f, 12f, 0.1f, null, 800)
        assertEquals("連續三次才相信", 12f, c.second, 1e-3f)
    }
}
