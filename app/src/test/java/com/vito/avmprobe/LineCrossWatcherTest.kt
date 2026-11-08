package com.vito.avmprobe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LineCrossWatcherTest {
    private fun st(dl: Float, dr: Float) = LaneDepartureWarning.State(false, false, dl, dr)

    @Test
    fun rightLaneChangeCompletesAfterCrossing() {
        val w = LineCrossWatcher()
        assertFalse(w.update('R', st(0.9f, 0.8f)))      // 車道中間
        assertFalse(w.update('R', st(1.3f, -0.3f)))     // 右邊的線到車底下：正在跨
        assertFalse(w.update('R', st(-0.4f, 2.2f)))     // 線換到左邊的那側（還在車底下）
        assertTrue(w.update('R', st(0.4f, 2.0f)))       // 線在左側、離車輪 0.4 m → 完成
        assertFalse("關燈重新開始", w.update(null, st(0.4f, 2.0f)))
    }

    @Test
    fun noCrossingNoCompletion() {
        val w = LineCrossWatcher()
        repeat(10) { assertFalse(w.update('L', st(0.6f, 0.9f))) }
    }

    @Test
    fun yawRateOdometryMovesTarget() {
        val o = LaneChangeOdometry()
        var t = 0L
        val first = o.update(t, 'R', 50f, null, 3.5f, 0.0)!!
        // 先往右轉 0.1 rad/s 1.5 秒、再往左轉回來
        var r: Pair<Double, Double>? = first
        for (i in 0 until 15) { t += 100; r = o.update(t, 'R', 50f, null, 3.5f, 0.1) ?: break }
        for (i in 0 until 15) { t += 100; r = o.update(t, 'R', 50f, null, 3.5f, -0.1) ?: break }
        assertTrue("目標要變近：${r?.first}", r == null || r.first < first.first - 1.0)
    }
}
