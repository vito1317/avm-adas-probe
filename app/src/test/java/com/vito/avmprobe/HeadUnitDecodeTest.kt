package com.vito.avmprobe

import com.vito.avmprobe.HeadUnitSignalAdapter.Decode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 解碼規則來自「全景360」反編譯，見 docs/head-unit-signals.md。 */
class HeadUnitDecodeTest {

    @Test
    fun `方向燈位元遮罩`() {
        assertNull(Decode.turnOf(0))
        assertEquals('R', Decode.turnOf(1))
        assertEquals('L', Decode.turnOf(2))
        assertEquals('H', Decode.turnOf(3))
    }

    @Test
    fun `雙閃優先於單邊`() {
        // CarStatusManager.checkLightSignal 的順序：左、右、雙閃；但遮罩判定 3 要先看
        assertEquals(3, Decode.turnBits(left = 0, right = 0, hazard = 1))
        assertEquals(2, Decode.turnBits(left = 1, right = 0, hazard = 0))
        assertEquals(1, Decode.turnBits(left = 0, right = 1, hazard = 0))
        assertEquals(0, Decode.turnBits(left = 0, right = 0, hazard = 0))
        assertEquals('H', Decode.turnOf(Decode.turnBits(0, 0, 1)))
    }

    @Test
    fun `轉角換算端點與中心`() {
        // deg = -((eps+1080)*1560/2160 - 780)
        assertEquals(780f, Decode.steerDeg(-1080), 0.01f)
        assertEquals(0f, Decode.steerDeg(0), 0.01f)
        assertEquals(-780f, Decode.steerDeg(1080), 0.01f)
    }

    @Test
    fun `轉角是線性且單調`() {
        var prev = Float.MAX_VALUE
        for (eps in -1080..1080 step 60) {
            val d = Decode.steerDeg(eps)
            assertTrue("eps=$eps 應單調遞減", d < prev)
            prev = d
        }
    }

    @Test
    fun `雷達等級換算成公尺`() {
        assertEquals(0.10f, Decode.radarMetres(1), 1e-4f)
        assertEquals(0.25f, Decode.radarMetres(2), 1e-4f)
        assertEquals(1.45f, Decode.radarMetres(10), 1e-4f)
        assertTrue(Decode.radarMetres(11).isInfinite())
        assertTrue(Decode.radarMetres(12).isInfinite())
        // 0 或負值也視為無障礙，不可以變成「貼著車」的假警告
        assertTrue(Decode.radarMetres(0).isInfinite())
        assertTrue(Decode.radarMetres(-1).isInfinite())
    }

    @Test
    fun `雷達陣列切前後四顆`() {
        val raw = intArrayOf(1, 2, 3, 11, 5, 6, 7, 8)
        val f = Decode.radar(raw, 0)!!
        val r = Decode.radar(raw, 4)!!
        assertEquals(0.10f, f[0], 1e-4f)
        assertEquals(0.25f, f[1], 1e-4f)
        assertEquals(0.40f, f[2], 1e-4f)
        assertTrue(f[3].isInfinite())
        assertEquals(0.70f, r[0], 1e-4f)
        assertEquals(1.15f, r[3], 1e-4f)
    }

    @Test
    fun `雷達陣列長度不足回 null 而不是亂切`() {
        assertNull(Decode.radar(intArrayOf(1, 2, 3), 0))
        assertNull(Decode.radar(intArrayOf(1, 2, 3, 4, 5), 4))
    }

    @Test
    fun `廣播 action 與 key 不可手滑改掉`() {
        assertEquals("Signal_State", Decode.KEY_STATE)
        assertEquals("Voice_Reverse_State", Decode.ACT_REVERSE)
        assertEquals("Voice_LRReverse_State", Decode.ACT_LRREVERSE)
        assertEquals("Voice_Speed_State", Decode.ACT_SPEED)
        assertEquals("com.ts.main.auto_avm_speed_change", Decode.ACT_TS_SPEED)
        assertEquals("com.ts.main.mcustate", Decode.ACT_TS_MCU)
        assertEquals("com.ts.MainUI", Decode.TS_PKG)
        assertEquals("com.ts.tsspeechlib.car.TsCarService", Decode.TS_ACTION)
        assertEquals(10, Decode.ACTIONS.size)
        // 這台車機實際掃到的是 ts 這兩個，Voice_* 那組一個都沒出現 —— 兩組都要掛著
        assertTrue(Decode.ACT_TS_SPEED in Decode.ACTIONS)
        assertTrue(Decode.ACT_SPEED in Decode.ACTIONS)
    }
}

/**
 * 不知道鍵名時從 extras 認出車速。
 * 使用者車機掃出來的 170 個候選廣播裡沒有 Voice_Speed_State，
 * 只有 com.ts.main.auto_avm_speed_change —— 鍵名不明，只能用值的範圍猜。
 */
class HeadUnitSpeedExtraTest {
    @Test
    fun prefersKnownKey() {
        val v = HeadUnitSignalAdapter.Decode.speedFromExtras(
            listOf("other" to 7, HeadUnitSignalAdapter.Decode.KEY_STATE to 42)
        )
        assertEquals(42f, v!!, 1e-6f)
    }

    @Test
    fun fallsBackToFirstPlausibleValue() {
        val v = HeadUnitSignalAdapter.Decode.speedFromExtras(listOf("zz" to 60, "aa" to "x"))
        assertEquals(60f, v!!, 1e-6f)
    }

    /** 鍵名排序後再掃，兩個都合理時結果才不會隨 Bundle 順序跳動。 */
    @Test
    fun deterministicOrder() {
        val a = HeadUnitSignalAdapter.Decode.speedFromExtras(listOf("b" to 20, "a" to 30))
        val b = HeadUnitSignalAdapter.Decode.speedFromExtras(listOf("a" to 30, "b" to 20))
        assertEquals(a!!, b!!, 1e-6f)
        assertEquals(30f, a, 1e-6f)
    }

    @Test
    fun rejectsOutOfRange() {
        assertNull(HeadUnitSignalAdapter.Decode.speedFromExtras(listOf("x" to -5, "y" to 9999)))
        assertNull(HeadUnitSignalAdapter.Decode.speedFromExtras(emptyList()))
    }
}
