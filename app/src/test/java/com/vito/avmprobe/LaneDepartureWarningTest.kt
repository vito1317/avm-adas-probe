package com.vito.avmprobe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaneDepartureWarningTest {

    /** x 固定、沿 z 的一條縱向線。 */
    private fun line(x: Float, dashed: Boolean = true, conf: Float = 0.8f) =
        RoadLine('w', dashed, x, -3f, x, 6f, conf)

    /** 橫過去的線（停止線、斑馬線）。 */
    private fun transverse(z: Float) = RoadLine('w', false, -3f, z, 3f, z, 0.9f)

    /** 從車道中間一路往左飄到壓線 → 要警示。 */
    @Test
    fun driftsLeftWarns() {
        val w = LaneDepartureWarning()
        var t = 0L
        var x = -1.8f                       // 左線在 x = -1.8（輪外緣離線 0.875 m）
        var fired = false
        repeat(20) {
            val s = w.update(t, 90f, listOf(line(x), line(x + 3.5f)), null)
            if (s.left) fired = true
            t += 100
            x += 0.04f                      // 每 100 ms 靠近 4 cm = 0.4 m/s
        }
        assertTrue("往左飄到壓線應該警示", fired)
    }

    /** 往右飄一樣要警示（左右對稱）。 */
    @Test
    fun driftsRightWarns() {
        val w = LaneDepartureWarning()
        var t = 0L
        var x = 1.8f
        var fired = false
        repeat(20) {
            val s = w.update(t, 90f, listOf(line(x), line(x - 3.5f)), null)
            if (s.right) fired = true
            t += 100
            x -= 0.04f
        }
        assertTrue(fired)
    }

    /** 打左燈往左偏 = 變換車道，不可以叫。 */
    @Test
    fun turnSignalSuppressesSameSide() {
        val w = LaneDepartureWarning()
        var t = 0L
        var x = -1.8f
        repeat(20) {
            val s = w.update(t, 90f, listOf(line(x), line(x + 3.5f)), 'L')
            assertFalse("打左燈往左偏不該警示", s.left)
            t += 100
            x += 0.04f
        }
    }

    /** 打右燈卻往左偏 —— 那不是在變換車道，還是要叫。 */
    @Test
    fun oppositeSignalStillWarns() {
        val w = LaneDepartureWarning()
        var t = 0L
        var x = -1.8f
        var fired = false
        repeat(20) {
            if (w.update(t, 90f, listOf(line(x), line(x + 3.5f)), 'R').left) fired = true
            t += 100
            x += 0.04f
        }
        assertTrue(fired)
    }

    /** 方向燈熄掉之後還有寬限期（變換車道會跨過燈熄那一刻）。 */
    @Test
    fun graceAfterSignalOff() {
        val w = LaneDepartureWarning()
        var t = 0L
        var x = -1.8f
        repeat(8) {                                  // 先打燈往左偏
            w.update(t, 90f, listOf(line(x), line(x + 3.5f)), 'L')
            t += 100
            x += 0.04f
        }
        repeat(8) {                                  // 燈熄了，但還在寬限期內
            val s = w.update(t, 90f, listOf(line(x), line(x + 3.5f)), null)
            assertFalse("寬限期內不該叫", s.left)
            t += 100
            x += 0.04f
        }
    }

    /** 低速不作用（巷弄、塞車壓線是常態）。 */
    @Test
    fun noWarnBelowMinSpeed() {
        val w = LaneDepartureWarning()
        var t = 0L
        var x = -1.8f
        repeat(20) {
            val s = w.update(t, 30f, listOf(line(x), line(x + 3.5f)), null)
            assertFalse(s.any)
            t += 100
            x += 0.04f
        }
    }

    /** 沒有車速訊號就不作用。 */
    @Test
    fun noWarnWithoutSpeed() {
        val w = LaneDepartureWarning()
        var t = 0L
        var x = -1.8f
        repeat(20) {
            assertFalse(w.update(t, null, listOf(line(x), line(x + 3.5f)), null).any)
            t += 100
            x += 0.04f
        }
    }

    /** 一直貼著線穩穩開（沒有在靠過去）不可以一直叫。 */
    @Test
    fun steadyCloseDoesNotWarn() {
        val w = LaneDepartureWarning()
        var t = 0L
        repeat(30) {
            val s = w.update(t, 90f, listOf(line(-1.02f), line(2.5f)), null)
            assertFalse("貼著線但沒在靠過去", s.left)
            t += 100
        }
    }

    /** 橫過去的停止線、斑馬線不是車道邊界。 */
    @Test
    fun ignoresTransverseLines() {
        val w = LaneDepartureWarning()
        var t = 0L
        var z = 3.0f
        repeat(20) {
            assertFalse(w.update(t, 90f, listOf(transverse(z)), null).any)
            t += 100
            z -= 0.1f
        }
    }

    /** 叫過一次之後回到車道中間，再偏一次要能再叫。 */
    @Test
    fun rearmsAfterReturning() {
        val w = LaneDepartureWarning()
        var t = 0L
        var x = -1.8f
        var first = false
        repeat(20) {
            if (w.update(t, 90f, listOf(line(x), line(x + 3.5f)), null).left) first = true
            t += 100; x += 0.04f
        }
        assertTrue(first)
        repeat(12) {                                  // 回到中間（離線 > REARM_M 才會重新武裝）
            w.update(t, 90f, listOf(line(x), line(x + 3.5f)), null)
            t += 100; x -= 0.05f
        }
        var second = false
        repeat(20) {
            if (w.update(t, 90f, listOf(line(x), line(x + 3.5f)), null).left) second = true
            t += 100; x += 0.04f
        }
        assertTrue("回到中間之後要能再警示", second)
    }

    /** 信心低、太短的線不採信。 */
    @Test
    fun ignoresWeakLines() {
        val w = LaneDepartureWarning()
        var t = 0L
        var x = -1.05f
        repeat(20) {
            val weak = RoadLine('w', true, x, -0.2f, x, 0.3f, 0.2f)   // 短又沒把握
            assertFalse(w.update(t, 90f, listOf(weak), null).any)
            t += 100; x += 0.04f
        }
    }
}

class LaneStraddleTest {
    private fun line(x: Float, dashed: Boolean = true) = RoadLine('w', dashed, x, 3f, x, 12f, 0.9f)

    @org.junit.Test
    fun sustainedStraddleWarnsAfterTwoSeconds() {
        val w = LaneDepartureWarning()
        // 線在自車左邊 0.6 m（車輪外緣 0.925 → 線在車底下 0.33 m）
        var s = w.update(0, 70f, listOf(line(-0.6f)), null)
        org.junit.Assert.assertNull(s.straddle)
        s = w.update(1500, 70f, listOf(line(-0.6f)), null)
        org.junit.Assert.assertNull("還沒滿 2 秒", s.straddle)
        s = w.update(2100, 70f, listOf(line(-0.6f)), null)
        org.junit.Assert.assertEquals('L', s.straddle)
        org.junit.Assert.assertEquals(false, s.straddleSolid)
    }

    @org.junit.Test
    fun solidLineStraddleIsViolation() {
        val w = LaneDepartureWarning()
        w.update(0, 70f, listOf(line(0.5f, dashed = false)), null)
        val s = w.update(2500, 70f, listOf(line(0.5f, dashed = false)), null)
        org.junit.Assert.assertEquals('R', s.straddle)
        org.junit.Assert.assertEquals(true, s.straddleSolid)
    }

    @org.junit.Test
    fun signalingThatSideIsLaneChangeNotStraddle() {
        val w = LaneDepartureWarning()
        w.update(0, 70f, listOf(line(-0.6f)), 'L')
        val s = w.update(2500, 70f, listOf(line(-0.6f)), 'L')
        org.junit.Assert.assertNull(s.straddle)
    }

    @org.junit.Test
    fun minSpeedIsAdjustable() {
        val w = LaneDepartureWarning()
        w.update(0, 35f, listOf(line(-0.6f)), null)
        org.junit.Assert.assertNull(w.update(2500, 35f, listOf(line(-0.6f)), null).straddle)   // 預設 55 以下不作用
        w.minKmh = 30f
        w.update(3000, 35f, listOf(line(-0.6f)), null)
        org.junit.Assert.assertEquals('L', w.update(5500, 35f, listOf(line(-0.6f)), null).straddle)
    }

    @org.junit.Test
    fun normalDrivingInLaneIsQuiet() {
        val w = LaneDepartureWarning()
        w.update(0, 70f, listOf(line(-1.7f), line(1.8f)), null)
        org.junit.Assert.assertNull(w.update(3000, 70f, listOf(line(-1.7f), line(1.8f)), null).straddle)
    }
}
