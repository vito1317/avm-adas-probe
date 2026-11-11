package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeadLightDetectorTest {
    private val S = 100
    private val GRAY = (0xFF shl 24) or (90 shl 16) or (90 shl 8) or 90
    private val DIM_RED = (0xFF shl 24) or (120 shl 16) or (30 shl 8) or 30
    private val RED = (0xFF shl 24) or (240 shl 16) or (40 shl 8) or 40
    private val AMBER = (0xFF shl 24) or (250 shl 16) or (150 shl 8) or 40

    /** 前車框 (30,40)–(70,70)，左右燈區各畫一塊。 */
    private fun frame(left: Int, right: Int): IntArray {
        val a = IntArray(S * S) { GRAY }
        fun lamp(x0: Int, c: Int) { for (y in 50 until 58) for (x in x0 until x0 + 8) a[y * S + x] = c }
        lamp(32, left); lamp(60, right)
        return a
    }
    private fun upd(d: LeadLightDetector, t: Long, l: Int, r: Int) = d.update(t, frame(l, r), S, 30f, 40f, 70f, 70f)

    @Test
    fun brakeWhenTailLightsBrighten() {
        val d = LeadLightDetector()
        var s = upd(d, 0, DIM_RED, DIM_RED)
        for (t in 1..10) s = upd(d, t * 100L, DIM_RED, DIM_RED)
        assertFalse("平常的尾燈不是煞車", s.brake)
        s = upd(d, 1200, RED, RED)
        assertTrue(s.brake)
        for (t in 13..16) s = upd(d, t * 100L, DIM_RED, DIM_RED)
        assertFalse("放開煞車", s.brake)
    }

    @Test
    fun hazardIsBothSidesBlinkingTogether() {
        val d = LeadLightDetector()
        var s = upd(d, 0, DIM_RED, DIM_RED)
        for (i in 0 until 12) {
            val on = i % 2 == 0
            s = upd(d, 100L + i * 500L, if (on) AMBER else DIM_RED, if (on) AMBER else DIM_RED)
        }
        assertTrue(s.hazard)
        assertNull(s.turn)
    }

    @Test
    fun oneSideBlinkingIsTurnSignal() {
        val d = LeadLightDetector()
        var s = upd(d, 0, DIM_RED, DIM_RED)
        for (i in 0 until 12) s = upd(d, 100L + i * 500L, DIM_RED, if (i % 2 == 0) AMBER else DIM_RED)
        assertFalse(s.hazard)
        assertEquals('R', s.turn)
    }

    @Test
    fun brakeWithOverexposedPinkLampsOnGreyCar() {
        // 實車：灰色車煞車燈中心過曝成白偏粉紅（以前只數「很紅」的像素，偵測不到；只有紅色車才偵測得到）
        val PINK = (0xFF shl 24) or (255 shl 16) or (175 shl 8) or 170
        val d = LeadLightDetector()
        var s = upd(d, 0, DIM_RED, DIM_RED)
        for (t in 1..10) s = upd(d, t * 100L, DIM_RED, DIM_RED)
        assertFalse(s.brake)
        s = upd(d, 1200, PINK, PINK)
        assertTrue("過曝的煞車燈也要偵測到", s.brake)
    }

    @Test
    fun redCarBodyAloneIsNotBraking() {
        // 紅色車：整個車尾（含燈區）都是暗紅色車漆，燈沒亮 → 不是煞車
        val BODY = (0xFF shl 24) or (150 shl 16) or (25 shl 8) or 30
        val d = LeadLightDetector()
        var s = upd(d, 0, BODY, BODY)
        for (t in 1..30) s = upd(d, t * 100L, BODY, BODY)
        assertFalse(s.brake)
    }
}
