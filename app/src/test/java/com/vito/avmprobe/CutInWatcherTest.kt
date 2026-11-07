package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CutInWatcherTest {
    private val S = 100
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private val GRAY = rgb(90, 90, 90); private val DIM = rgb(120, 30, 30); private val AMBER = rgb(250, 150, 40)

    /** 右前方一台車，框 (60,40)–(90,62)；左側燈（框左邊 30%）亮或暗。 */
    private fun frame(leftOn: Boolean, rightOn: Boolean = false): IntArray {
        val a = IntArray(S * S) { GRAY }
        fun lamp(x0: Int, c: Int) { for (y in 48 until 54) for (x in x0 until x0 + 6) a[y * S + x] = c }
        lamp(61, if (leftOn) AMBER else DIM); lamp(83, if (rightOn) AMBER else DIM)
        return a
    }
    private val box = listOf(CutInWatcher.Box(60f, 40f, 90f, 62f, 3.5f, 12f))

    @Test
    fun rightLaneCarSignalingLeftIsCutIn() {
        val w = CutInWatcher()
        var r: Triple<Char, Float, Float>? = null
        for (i in 0 until 12) r = w.update(i * 500L, frame(i % 2 == 0), S, box)
        assertEquals('R', r!!.first)
    }

    @Test
    fun rightLaneCarSignalingRightIsNotCutIn() {
        val w = CutInWatcher()
        var r: Triple<Char, Float, Float>? = null
        for (i in 0 until 12) r = w.update(i * 500L, frame(false, i % 2 == 0), S, box)
        assertNull(r)
    }

    @Test
    fun hazardIsNotCutIn() {
        val w = CutInWatcher()
        var r: Triple<Char, Float, Float>? = null
        for (i in 0 until 12) { val on = i % 2 == 0; r = w.update(i * 500L, frame(on, on), S, box) }
        assertNull(r)
    }
}
