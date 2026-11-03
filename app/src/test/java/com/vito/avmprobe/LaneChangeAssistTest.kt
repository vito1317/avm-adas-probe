package com.vito.avmprobe

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2

class LaneChangeAssistTest {

    private fun car(id: Int, x: Float, z: Float, vz: Float, sog: Float? = null, t: String = "car") =
        Track(id, t, x, z, 0f, vz, 5000, 10, true, 1.8f, 4.5f, sog)

    /** 連續餵同一組軌跡 n 次（每次 100 ms），回傳最後狀態。 */
    private fun feed(
        lca: LaneChangeAssist, tracks: List<Track>, from: Long, n: Int, turn: Char? = null,
        steer: Float? = null, ego: Float? = 16.7f, lane: Pair<Float?, Float?> = Pair(null, null)
    ): LcaState {
        var s: LcaState? = null
        for (i in 0 until n) s = lca.update(tracks, from + i * 100L, lane.first, lane.second, turn, steer, ego)
        return s!!
    }

    @Test
    fun quinticPathShape() {
        val lca = LaneChangeAssist()
        val p = lca.lanePath(-3.5, 0.0, 15.0)
        assertEquals(16, p.size)
        assertArrayEquals(floatArrayOf(0f, 0f), p.first(), 1e-6f)
        assertArrayEquals(floatArrayOf(-3.5f, 15f), p.last(), 1e-5f)
        assertEquals(-1.75, p[7].let { (it[0] + p[8][0]) / 2.0 }, 0.05)   // 中點附近剛好半條車道
        for (i in 1 until p.size) assertEquals(1.0, (p[i][1] - p[i - 1][1]).toDouble(), 1e-6)
        assertTrue("尾端平行車道", abs(p[15][0] - p[14][0]) < 0.02)
        assertTrue("起點沿車頭方向", abs(p[1][0]) < 0.03)
        for (i in 1 until p.size) assertTrue("單調往左", p[i][0] <= p[i - 1][0] + 1e-6)

        // 車頭已經偏左 0.05 rad（車道看起來往右偏）：起點仍沿著車頭方向，終點落在目標車道中心、與車道平行
        val phi = 0.05
        val q = lca.lanePath(-3.5, phi, 20.0)
        assertEquals(21, q.size)
        assertTrue(abs(q[1][0]) < 0.05)
        val end = q.last()
        val dEnd = end[0] * kotlin.math.cos(phi) - end[1] * kotlin.math.sin(phi)   // 到車道中心線的法向距離
        assertEquals(-3.5 * kotlin.math.cos(phi), dEnd, 0.01)
        val dir = atan2((q[20][0] - q[19][0]).toDouble(), (q[20][1] - q[19][1]).toDouble())
        assertEquals(phi, dir, 0.01)
    }

    @Test
    fun zonesDefaultAndFromLaneLines() {
        val lca = LaneChangeAssist()
        val s = lca.update(emptyList(), 0, null, null, null, null, null)
        assertArrayEquals(floatArrayOf(-5.25f, -12.42f, -1.75f, 1f), s.zoneL, 1e-4f)
        assertArrayEquals(floatArrayOf(1.75f, -12.42f, 5.25f, 1f), s.zoneR, 1e-4f)
        assertNull(s.latVel)
        val lca2 = LaneChangeAssist()
        var t: LcaState? = null
        for (i in 0..5) t = lca2.update(emptyList(), i * 100L, -1.4f, 1.8f, null, null, null)   // 偏左 0.2 m、車道寬 3.2 m
        assertEquals(-1.4f, t!!.zoneL[2], 0.05f)
        assertEquals(1.8f, t.zoneR[0], 0.05f)
        assertTrue(t.laneW in 3.19f..3.5f)
        assertEquals(t.zoneL[2] - t.laneW, t.zoneL[0], 1e-4f)
    }

    @Test
    fun hysteresisOnTwoUpdatesOffAfter800ms() {
        val lca = LaneChangeAssist()
        val inZone = listOf(car(1, -3.5f, -4f, 0f))
        assertEquals(0, lca.update(inZone, 0, null, null, null, null, 16.7f).L)
        assertEquals(0, lca.update(emptyList(), 100, null, null, null, null, 16.7f).L)   // 單次閃現不亮
        assertEquals(0, lca.update(inZone, 200, null, null, null, null, 16.7f).L)
        assertEquals(1, lca.update(inZone, 300, null, null, null, null, 16.7f).L)
        assertEquals(1, lca.update(emptyList(), 600, null, null, null, null, 16.7f).L)
        assertEquals(1, lca.update(emptyList(), 1100, null, null, null, null, 16.7f).L)
        assertEquals(0, lca.update(emptyList(), 1200, null, null, null, null, 16.7f).L)
        assertEquals(0, lca.update(emptyList(), 1200, null, null, null, null, 16.7f).R)
    }

    @Test
    fun closingVehicleTtcLevels() {
        val lca = LaneChangeAssist()
        // 後方 14 m（車頭在 −11.75），+4 m/s：距後保桿 9.33 m，TTC 2.33 s → 一級；在盲點區外也要警示
        var s = feed(lca, listOf(car(7, -3.5f, -20f, 4f)), 0, 3)
        assertEquals(0, s.L)   // 太遠：TTC 3.8 s
        s = feed(lca, listOf(car(7, -3.5f, -14f, 4f)), 300, 3)
        assertEquals(1, s.L)
        assertEquals(7, s.threatId)
        assertEquals(9.33f / 4f, s.threatTtc!!, 0.02f)
        // TTC < 2 s → 二級（不需要意圖）
        s = feed(lca, listOf(car(7, -3.5f, -10f, 4f)), 600, 3)
        assertEquals(2, s.L)
        assertEquals(0, s.R)
        // 同車道正後方的車不算
        val l2 = LaneChangeAssist()
        assertEquals(0, feed(l2, listOf(car(8, 0.2f, -10f, 4f)), 0, 4).let { it.L + it.R })
        // 接近速度 < 1 m/s 不算來車（但在盲點區內仍是一級）
        val l3 = LaneChangeAssist()
        val s3 = feed(l3, listOf(car(9, 3.5f, -15f, 0.8f)), 0, 4)
        assertEquals(0, s3.R)
        assertNull(s3.threatId)
    }

    @Test
    fun stationaryAndOvertakenAreSuppressed() {
        // 路邊停車（自車 10 m/s 經過，對地速度 0）
        val a = LaneChangeAssist()
        assertEquals(0, feed(a, listOf(car(1, 3.4f, -3f, -10f, sog = 0.1f)), 0, 5, ego = 10f).R)
        // 自車以 15 km/h 超越的慢車
        val b = LaneChangeAssist()
        assertEquals(0, feed(b, listOf(car(2, -3.5f, -3f, -4.2f, sog = 12.5f)), 0, 5).L)
        // 緩慢超越（5 km/h）：仍在盲點 → 一級
        val c = LaneChangeAssist()
        assertEquals(1, feed(c, listOf(car(3, -3.5f, -3f, -1.4f, sog = 15.3f)), 0, 5).L)
        // 沒有車速時，靠相對速度判斷：被以 8 m/s 超越 → 抑制；同速 → 一級
        val d = LaneChangeAssist()
        assertEquals(0, feed(d, listOf(car(4, -3.5f, -3f, -8f)), 0, 5, ego = null).L)
        val e = LaneChangeAssist()
        assertEquals(1, feed(e, listOf(car(5, -3.5f, -3f, 0f)), 0, 5, ego = null).L)
        // 自車低速爬行（< 3 m/s）時，旁邊停住的車仍提示
        val f = LaneChangeAssist()
        assertEquals(1, feed(f, listOf(car(6, 3.4f, -3f, -1f, sog = 0f)), 0, 5, ego = 1f).R)
    }

    @Test
    fun onlyVehiclesAndTwoWheelers() {
        val lca = LaneChangeAssist()
        val s = feed(lca, listOf(
            car(1, -3.5f, -3f, 0f, t = "ped"), car(2, 3.5f, -3f, 0f, t = "cone"),
            car(3, -3.5f, -20f, 0f).copy(confirmed = false)
        ), 0, 5)
        assertEquals(0, s.L)
        assertEquals(0, s.R)
        val m = LaneChangeAssist()
        assertEquals(1, feed(m, listOf(Track(4, "moto", 2.6f, -2f, 0f, 0.5f, 5000, 9, true, 0.7f, 1.9f)), 0, 5).R)
    }

    @Test
    fun turnSignalWithAndWithoutThreat() {
        val lca = LaneChangeAssist()
        val blind = listOf(car(1, -3.5f, -4f, 0f))
        var s = feed(lca, blind, 0, 3)
        assertEquals(1, s.L)
        assertNull(s.intent)
        assertNull(s.path)
        // 方向燈閃爍（亮 4 次更新、暗 4 次更新）：意圖不掉、等級 2、路徑不可行
        for (k in 0 until 4) {
            s = feed(lca, blind, 300 + k * 800L, 4, turn = 'L')
            assertEquals('L', s.intent)
            s = feed(lca, blind, 700 + k * 800L, 4, turn = null)
            assertEquals('L', s.intent)
        }
        assertEquals(2, s.L)
        assertFalse(s.pathOk)
        assertNotNull(s.path)
        assertEquals(-3.5f, s.path!!.last()[0], 0.01f)
        assertEquals(max15(16.7f * 4), s.path!!.last()[1], 0.01f)

        // 沒車：路徑可行、等級 0
        val clear = LaneChangeAssist()
        val c = feed(clear, emptyList(), 0, 5, turn = 'R', ego = null)
        assertEquals('R', c.intent)
        assertEquals(0, c.R)
        assertTrue(c.pathOk)
        assertEquals(16, c.path!!.size)     // 沒車速：15 m
        assertEquals(3.5f, c.path!!.last()[0], 0.01f)
        // 燈熄 1.2 s 後意圖消失
        val c2 = feed(clear, emptyList(), 500 + 1300, 2, ego = null)
        assertNull(c2.intent)
        assertNull(c2.path)
    }

    private fun max15(v: Float) = maxOf(15f, v)

    @Test
    fun steeringTowardSideEscalates() {
        val lca = LaneChangeAssist()
        val blind = listOf(car(1, 3.5f, -2f, 0.3f))
        assertEquals(1, feed(lca, blind, 0, 3, steer = 3f).R)
        assertEquals(2, feed(lca, blind, 300, 3, steer = 15f).R)
        val l2 = LaneChangeAssist()
        assertEquals("往另一側打方向盤不升級", 1, feed(l2, blind, 0, 4, steer = -15f).R)
    }

    @Test
    fun driftWithoutSignal() {
        val lca = LaneChangeAssist()
        val blind = listOf(car(1, -3.5f, -4f, 0f))
        var s: LcaState? = null
        var t = 0L
        // 置中 1 s
        repeat(10) { s = lca.update(blind, t, -1.75f, 1.75f, null, null, 16.7f); t += 100 }
        assertNull(s!!.intent)
        assertEquals(1, s!!.L)
        // 往左偏 0.4 m/s：車道線往右移
        var off = 0.0
        var firstIntent = -1L
        repeat(15) {
            off += 0.04
            s = lca.update(blind, t, (-1.75 + off).toFloat(), (1.75 + off).toFloat(), null, null, 16.7f)
            if (firstIntent < 0 && s!!.intent == 'L') firstIntent = t
            t += 100
        }
        assertEquals('L', s!!.intent)
        assertTrue("偏移意圖延遲 ${firstIntent - 1000} ms", firstIntent in 1500..1900)
        assertEquals(-0.4f, s!!.latVel!!, 0.08f)
        assertEquals(2, s!!.L)
        assertFalse(s!!.pathOk)
        // 路徑起點沿車頭方向（車頭偏左，車道看起來往右偏）
        assertTrue(s!!.path!![1][0] < 0.05f)
    }

    @Test
    fun laneLineReassociationIsNotDrift() {
        // 換道完成：最近的左線從 −0.1 跳到 −3.6、右線從 3.4 跳到 −0.1 … 不該出現假的偏移速度
        val lca = LaneChangeAssist()
        var t = 0L
        var x = 0.0
        var maxV = 0.0
        repeat(40) {
            x += 0.01   // 自車往左 0.1 m/s（低於門檻），左線從 −0.15 一路移到車底再到右邊
            var l = -0.15 + x
            var r = 3.35 + x
            if (l > 0) { r = l; l -= 3.5 }
            val s = lca.update(emptyList(), t, l.toFloat(), r.toFloat(), null, null, 16.7f)
            maxV = maxOf(maxV, abs(s.latVel ?: 0f).toDouble())
            assertNull(s.intent)
            t += 100
        }
        assertTrue("最大橫向速度 $maxV", maxV < 0.2)
    }

    @Test
    fun jsonFormat() {
        val lca = LaneChangeAssist()
        val s = feed(lca, listOf(car(5, -3.5f, -12f, 4.5f)), 0, 3, turn = 'L')
        val j = JSONObject(lca.toJson(s).toString())
        assertEquals(2, j.getInt("L"))
        assertEquals(0, j.getInt("R"))
        assertEquals("L", j.getString("intent"))
        assertFalse(j.getBoolean("pathOk"))
        val path = j.getJSONArray("path")
        assertEquals(68, path.length())   // 16.7 m/s × 4 s = 66.8 m，每 1 m 一點 + 終點
        assertEquals(2, path.getJSONArray(0).length())
        assertEquals(5, j.getJSONObject("threat").getInt("id"))
        assertTrue(j.getJSONObject("threat").getDouble("ttc") in 1.5..2.0)
        assertEquals(4, j.getJSONObject("zones").getJSONArray("L").length())
        assertEquals(-12.42, j.getJSONObject("zones").getJSONArray("R").getDouble(1), 1e-9)

        val none = lca.toJson(LaneChangeAssist().update(emptyList(), 0, null, null, null, null, null))
        assertTrue(none.isNull("path"))
        assertTrue(none.isNull("threat"))
        assertTrue(none.isNull("intent"))
        assertTrue(none.getBoolean("pathOk"))
    }

    @Test
    fun scenarioWarnings() {
        val res = LcaScenarios.all().associate { it.name to LcaScenarios.evaluate(it) }
        println("\n=== 換道輔助 情境評估 ===")
        for (a in res.values) println(a.row())
        fun r(n: String) = res.getValue(n)

        // 不該警示的情境：路邊停車、被明顯超越的慢車、行人、打燈但沒車、偏移但沒車
        for (n in listOf("右側路邊停車 30km/h", "超越慢車 60vs45", "行人穿越車後", "打左燈 無來車", "未打燈左偏 無來車")) {
            assertEquals("$n 理想系統不應警示", 0, r(n).runs.maxOf { it.maxOracle })
            assertTrue("$n 誤報率 ${r(n).faRate}", r(n).faRate < 0.03)
        }
        // 該警示的情境：不漏報、延遲合理
        for (n in listOf("左後來車 +10km/h", "左後來車 +30km/h", "左後來車 +10 無GPS", "緩慢超越 60vs55",
            "後→側鏡頭交接 +5", "打左燈+盲點有車", "未打燈左偏+盲點有車", "右側切入")) {
            val a = r(n)
            assertTrue("$n 一級漏報 ${a.misses(1)}/${a.onsets(1)}", a.misses(1) <= a.onsets(1) / 10)
            assertTrue("$n 一級延遲 ${a.latMean(1)}", a.latMean(1) < 1.0)
            assertTrue("$n 誤報率 ${a.faRate}", a.faRate < 0.03)
        }
        for (n in listOf("左後來車 +10km/h", "左後來車 +30km/h", "打左燈+盲點有車", "未打燈左偏+盲點有車", "後→側鏡頭交接 +5")) {
            val a = r(n)
            assertTrue("$n 二級漏報 ${a.misses(2)}/${a.onsets(2)}", a.misses(2) <= a.onsets(2) / 10)
            // +30 km/h：後方 15 m 才看得到，第一眼 TTC 就只剩 ~1.3 s；要 3–4 次偵測才估得出接近速度
            assertTrue("$n 二級延遲 ${a.latMean(2)}", a.latMean(2) < if (n.contains("+30")) 1.25 else 0.6)
        }
        assertTrue(r("打左燈+盲點有車").pathOkAgree > 0.9)
        assertTrue(r("未打燈左偏 無來車").intentMiss <= 2)
    }
}
