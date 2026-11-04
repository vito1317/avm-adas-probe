package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartAssistTest {

    private fun car(z: Float, x: Float = 0f) = listOf(StartAssist.Obj("car", x, z))
    private val none = emptyList<StartAssist.Obj>()

    /** 停住、前車在 5 m，等它拉到 6.6 m（> GAP_M）→ 提醒一次就好。 */
    @Test
    fun leadDeparture() {
        val a = StartAssist()
        var t = 0L
        assertFalse(a.update(t, 0f, car(5f), null, 0f).lead)
        t += StartAssist.ARM_MS + 100
        assertFalse("剛武裝不該叫", a.update(t, 0f, car(5f), null, 0f).lead)
        t += 500
        assertFalse("只動 0.5 m 不算", a.update(t, 0f, car(5.5f), null, 0f).lead)
        t += 500
        assertTrue("拉開 1.6 m 要提醒", a.update(t, 0f, car(6.6f), null, 0f).lead)
        t += 500
        assertTrue("狀態要保持住（畫面才有東西顯示）", a.update(t, 0f, car(8f), null, 0f).lead)
    }

    /** 前車先往前蹭了 0.5 m 再開走：基準要跟著更新，不能從一開始那個距離算。 */
    @Test
    fun leadCreepsForwardFirst() {
        val a = StartAssist()
        var t = StartAssist.ARM_MS + 100
        a.update(0L, 0f, car(5f), null, 0f)
        assertFalse(a.update(t, 0f, car(5f), null, 0f).lead)
        t += 300
        assertFalse(a.update(t, 0f, car(4.5f), null, 0f).lead)   // 往前蹭
        t += 300
        assertFalse("離 4.5 m 只拉開 1.4 m，還不到門檻", a.update(t, 0f, car(5.9f), null, 0f).lead)
        t += 300
        assertTrue(a.update(t, 0f, car(6.1f), null, 0f).lead)
    }

    /** 自己在走的時候前車拉開是正常的，不可以叫。 */
    @Test
    fun noAlertWhileMoving() {
        val a = StartAssist()
        var t = 0L
        repeat(10) {
            t += 300
            assertFalse(a.update(t, 20f, car(5f + it), null, 0f).lead)
        }
    }

    /** 沒有車速訊號就整個不作用（分不出是誰在動）。 */
    @Test
    fun needsSpeed() {
        val a = StartAssist()
        var t = 0L
        repeat(10) {
            t += 300
            val s = a.update(t, null, car(5f + it), "green", 1f)
            assertFalse(s.lead)
            assertFalse(s.green)
        }
    }

    /** 旁邊車道的車開走不算前車。 */
    @Test
    fun ignoresAdjacentLane() {
        val a = StartAssist()
        var t = StartAssist.ARM_MS + 100
        a.update(0L, 0f, car(5f, x = 3.2f), null, 0f)
        repeat(6) {
            t += 300
            assertFalse(a.update(t, 0f, car(5f + it, x = 3.2f), null, 0f).lead)
        }
    }

    /** 只看到綠燈不算（一停下來就看到綠燈，代表不是為它停的）。 */
    @Test
    fun greenAloneDoesNotFire() {
        val a = StartAssist()
        var t = StartAssist.ARM_MS + 100
        repeat(12) {
            t += 300
            assertFalse(a.update(t, 0f, none, "green", 0.9f).green)
        }
    }

    /** 紅燈撐夠久 → 轉綠 → 提醒一次。 */
    @Test
    fun redThenGreen() {
        val a = StartAssist()
        var t = 0L
        repeat(10) { t += 300; assertFalse(a.update(t, 0f, none, "red", 0.9f).green) }   // 等紅燈
        t += 100
        assertFalse("綠燈還沒穩定", a.update(t, 0f, none, "green", 0.9f).green)
        t += StartAssist.GREEN_MS + 50
        assertTrue(a.update(t, 0f, none, "green", 0.9f).green)
    }

    /** 號誌被前車擋住一下再出現：紅燈記憶不能掉，不然永遠等不到提醒。 */
    @Test
    fun greenSurvivesBlockedLight() {
        val a = StartAssist()
        var t = 0L
        repeat(10) { t += 300; a.update(t, 0f, none, "red", 0.9f) }
        repeat(5) { t += 300; a.update(t, 0f, none, null, 0f) }   // 擋住
        t += 100
        a.update(t, 0f, none, "green", 0.9f)
        t += StartAssist.GREEN_MS + 50
        assertTrue(a.update(t, 0f, none, "green", 0.9f).green)
    }

    /** 信心不足的號誌不算數。 */
    @Test
    fun lowConfidenceIgnored() {
        val a = StartAssist()
        var t = 0L
        repeat(10) { t += 300; a.update(t, 0f, none, "red", 0.2f) }
        repeat(6) { t += 300; assertFalse(a.update(t, 0f, none, "green", 0.2f).green) }
    }

    /** 開走之後再停一次，要能重新提醒（不能只叫一輩子一次）。 */
    @Test
    fun rearmsAfterDriving() {
        val a = StartAssist()
        var t = 0L
        a.update(t, 0f, car(5f), null, 0f)
        t += StartAssist.ARM_MS + 100
        a.update(t, 0f, car(5f), null, 0f)
        t += 500
        assertTrue(a.update(t, 0f, car(7f), null, 0f).lead)
        t += 500
        assertFalse("開起來就該收掉", a.update(t, 30f, car(7f), null, 0f).lead)
        t += 500
        a.update(t, 0f, car(4f), null, 0f)                 // 又停下來
        t += StartAssist.ARM_MS + 100
        assertFalse(a.update(t, 0f, car(4f), null, 0f).lead)
        t += 500
        assertTrue("第二次也要會叫", a.update(t, 0f, car(6f), null, 0f).lead)
    }

    /** 龜速前進（STOP 與 MOVE 之間）既不武裝也不解除。 */
    @Test
    fun crawlKeepsState() {
        val a = StartAssist()
        var t = 0L
        a.update(t, 0f, car(5f), null, 0f)
        t += StartAssist.ARM_MS + 100
        a.update(t, 0f, car(5f), null, 0f)
        t += 500
        assertTrue(a.update(t, 0f, car(7f), null, 0f).lead)
        t += 300
        assertTrue("龜速時狀態保持", a.update(t, 2.5f, car(8f), null, 0f).lead)
    }

    /** gap 要回報實際拉開的距離，面板才能顯示。 */
    @Test
    fun reportsGap() {
        val a = StartAssist()
        var t = 0L
        a.update(t, 0f, car(5f), null, 0f)
        t += StartAssist.ARM_MS + 100
        a.update(t, 0f, car(5f), null, 0f)
        t += 500
        assertEquals(2.0f, a.update(t, 0f, car(7f), null, 0f).gap, 1e-4f)
    }
}
