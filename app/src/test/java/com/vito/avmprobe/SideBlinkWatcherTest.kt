package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SideBlinkWatcherTest {
    @Test fun blinkingNeighbourIsFoundWithSparseSampling() {
        // 左側車（x=-3.5）打方向燈：一秒閃 1.5 次（亮 333 ms、暗 333 ms），側鏡頭每 450 ms 才看一次
        val w = SideBlinkWatcher()
        var t = 0L
        repeat(10) {
            val on = (t / 333) % 2 == 0L
            w.observe(t, -3.5f + it * 0.01f, 1f, if (on) 12 else 0, 2000)
            t += 450
        }
        val b = w.blinking(t)
        assertEquals(1, b.size)
        assertTrue(b[0][0] < -3f)
    }

    @Test fun steadyAmberOrNoneIsNotBlinking() {
        val w = SideBlinkWatcher()
        repeat(10) { w.observe(it * 450L, 3.5f, 0f, 15, 2000) }       // 一直亮（例如黃色車身反光）
        repeat(10) { w.observe(it * 450L, -3.5f, 0f, 0, 2000) }       // 都沒亮
        assertTrue(w.blinking(4500).isEmpty())
    }

    @Test fun twoCarsAreKeptApart() {
        val w = SideBlinkWatcher()
        var t = 0L
        repeat(10) {
            val on = (t / 333) % 2 == 0L
            w.observe(t, -3.5f, 1f, if (on) 12 else 0, 2000)          // 左邊在閃
            w.observe(t, 3.5f, -4f, 0, 2000)                           // 右後沒閃
            t += 450
        }
        val b = w.blinking(t)
        assertEquals(1, b.size); assertTrue(b[0][0] < 0f)
    }
}
