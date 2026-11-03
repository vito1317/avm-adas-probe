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
        // 自車低速爬行（塞車，< 3 m/s）時，旁邊停住的車平常不亮（不然整段塞車兩側一直亮、蓋掉鑽車縫的機車）…
        val f = LaneChangeAssist()
        assertEquals(0, feed(f, listOf(car(6, 3.4f, -3f, -1f, sog = 0f)), 0, 5, ego = 1f).R)
        // …但打方向燈往它那邊 → 危險
        assertEquals(2, feed(f, listOf(car(6, 3.4f, -3f, -1f, sog = 0f)), 500, 4, turn = 'R', ego = 1f).R)
        // 正被超越（> 10 km/h）但還在車旁：平常不亮，打燈往它那邊 → 危險（切回去會擦到）
        val g = LaneChangeAssist()
        assertEquals(0, feed(g, listOf(car(7, -3.5f, -2f, -4.2f, sog = 12.5f)), 0, 4).L)
        assertEquals(2, feed(g, listOf(car(7, -3.5f, -2f, -4.2f, sog = 12.5f)), 400, 4, turn = 'L').L)
        // 速度還不確定的新軌跡（svz 大）不因為估出 −4 m/s 就被當成「正被超越」而抑制
        val h = LaneChangeAssist()
        val young = Track(8, "car", -3.5f, -3f, 0f, -4.0f, 800, 4, true, 1.8f, 4.5f, 12.7f, svz = 2.5f)
        assertEquals(1, feed(h, listOf(young), 0, 4).L)
    }

    @Test
    fun reversingDisablesWarnings() {
        // 倒車經過路邊停車：停著的車相對自車往前 1.5 m/s，看起來就像「後方來車」
        val parked = listOf(car(1, 2.7f, -6f, 1.5f), car(2, 2.7f, -1f, 1.5f))
        val a = LaneChangeAssist()
        val s = feed(a, parked, 0, 6, ego = -1.5f)        // 車速帶負號 = 倒車
        assertEquals(0, s.R)
        assertTrue(s.reversing)
        val b = LaneChangeAssist()
        var t: LcaState? = null
        for (i in 0 until 6) t = b.update(parked, i * 100L, null, null, 'R', null, null, reverse = true)   // 只知道排檔
        assertEquals(0, t!!.R)
        assertNull(t.intent)
        assertNull(t.path)
        // 不知道在倒車（GPS 只有速率）：無法分辨，會警示 —— 整合端要傳排檔
        val c = LaneChangeAssist()
        assertTrue(feed(c, parked, 0, 6, ego = 1.5f).R >= 1)
    }

    @Test
    fun squeezingTwoWheelerBetweenEgoAndLaneLine() {
        // 台灣常見：機車從後方鑽進自車與車道線之間（自車靠右，左線在 −2.45）
        val lanes = Pair<Float?, Float?>(-2.45f, 1.05f)
        val moto = { z: Float, vz: Float -> Track(3, "moto", -1.7f, z, 0f, vz, 3000, 8, true, 0.7f, 1.9f, 10f, svz = 0.5f) }
        val a = LaneChangeAssist()
        var s = feed(a, listOf(moto(-14f, 5f)), 0, 3, lane = lanes, ego = 5.6f)
        assertEquals("後方 14 m、+5 m/s 鑽縫機車：來車（TTC 2.1 s）", 1, s.L)
        assertEquals(3, s.threatId)
        s = feed(a, listOf(moto(-1f, 5f)), 300, 3, lane = lanes, ego = 5.6f)
        assertEquals("並排：盲點", 1, s.L)
        assertEquals("畫出來的盲點區延伸到自車側面", -(LaneChangeAssist.EGO_HW + 0.2f), s.zoneL[2], 1e-4f)
        s = feed(a, listOf(moto(-1f, 5f)), 600, 3, lane = lanes, ego = 5.6f, turn = 'L')
        assertEquals("打左燈：危險", 2, s.L)
        assertFalse(s.pathOk)
        // 同一台機車停在自車正後方偏左 1.3 m、5 m 外跟車：不在並排帶，也沒接近 → 不亮
        val b = LaneChangeAssist()
        assertEquals(0, feed(b, listOf(moto(-8f, 0f)), 0, 5, lane = lanes, ego = 5.6f).L)
        // 汽車不會擠在自車與車道線之間：同一個位置的汽車軌跡（多半是定位誤差）不算鑽縫
        val car = LaneChangeAssist()
        assertEquals(0, feed(car, listOf(car(4, -1.3f, -1f, 0f)), 0, 5, lane = lanes).L)
    }

    @Test
    fun wrongLaneLineDoesNotShiftZones() {
        // 3 m 車道、自車置中：先正常 1 s，接著最近的左線被旁邊的車擋住，RoadTracker 報成再外一條（−4.5）
        val lca = LaneChangeAssist()
        val adj = listOf(car(1, -3.0f, -3f, 0.5f))
        var s: LcaState? = null
        for (i in 0 until 10) s = lca.update(adj, i * 100L, -1.5f, 1.5f, null, null, 16.7f)
        assertEquals(1, s!!.L)
        for (i in 10 until 60) {
            s = lca.update(adj, i * 100L, -4.5f, 1.5f, null, null, 16.7f)
            assertEquals("錯線 t=${i * 100} 盲點區不能整個移走", 1, s.L)
            assertEquals(-1.5f, s.zoneL[2], 0.2f)
            assertNull("錯線不能變成偏移意圖", s.intent)
        }
        // 地上補丁貼著車身被當成左線（−0.95）時亮時不亮
        val b = LaneChangeAssist()
        for (i in 0 until 60) {
            val l = if (i > 10 && i % 3 == 0) -0.95f else -1.75f
            s = b.update(emptyList(), i * 100L, l, 1.75f, null, null, 16.7f)
            assertNull("假線 t=${i * 100}", s.intent)
            assertEquals(-1.75f, s.zoneL[2], 0.15f)
        }
        // 只有一條錯線（右線報成 4.875）且沒有別的：不採用，1.5 s 後改用預設車道
        val c = LaneChangeAssist()
        for (i in 0 until 10) s = c.update(emptyList(), i * 100L, -1.625f, 1.625f, null, null, 16.7f)
        for (i in 10 until 40) s = c.update(emptyList(), i * 100L, null, 4.875f, null, null, 16.7f)
        assertFalse(s!!.laneOk)
        assertEquals(1.75f, s.zoneR[0], 1e-4f)
    }

    @Test
    fun laneWidthIsLearnedEvenIfFirstFrameHadOneLine() {
        // 3 m 車道。第一筆只有左線（右線剛好掉了）→ 用預設寬度 3.5 初始化；之後要從兩條線學到 3 m，
        // 左線再被擋掉、只剩右線時中心不能跳 0.25 m 以上，也不能冒出假的偏移意圖
        val lca = LaneChangeAssist()
        var s = lca.update(emptyList(), 0, -1.5f, null, null, null, 16.7f)
        var t = 100L
        repeat(20) { s = lca.update(emptyList(), t, -1.5f + (it % 2) * 0.01f, 1.5f, null, null, 16.7f); t += 100 }
        assertEquals(3.0f, s.laneW, 0.05f)
        repeat(20) {
            s = lca.update(emptyList(), t, null, 1.5f + (it % 2) * 0.01f, null, null, 16.7f)
            assertNull("t=$t", s.intent)
            assertEquals(-1.5f, s.zoneL[2], 0.1f)
            t += 100
        }
    }

    @Test
    fun pathConflictWithCarAheadInTargetLane() {
        // 左車道的車在前車門以前（盲點區外）、同速並行：盲點不亮，但換過去會切到它屁股 → 路徑不可行
        val a = LaneChangeAssist()
        var s = feed(a, listOf(car(1, -3.5f, 4.5f, 0f, sog = 16.7f)), 0, 4, turn = 'L')
        assertEquals(0, s.L)
        assertFalse(s.pathOk)
        // 同一台車在前方 20 m，或比自車快 3 m/s、已在前方 6 m（越拉越遠）：可行
        val b = LaneChangeAssist()
        assertTrue(feed(b, listOf(car(1, -3.5f, 20f, 0f, sog = 16.7f)), 0, 4, turn = 'L').pathOk)
        val c = LaneChangeAssist()
        assertTrue(feed(c, listOf(car(1, -3.5f, 6f, 3f, sog = 19.7f)), 0, 4, turn = 'L').pathOk)
        // 後方 16 m（盲點區外）、慢 1.5 m/s 越拉越遠：可行；後方 9 m、快 4 m/s 趕上來：不可行（也會因 TTC 亮）
        val d = LaneChangeAssist()
        assertTrue(feed(d, listOf(car(1, -3.5f, -16f, -1.5f, sog = 15.2f)), 0, 4, turn = 'L').pathOk)
        val e = LaneChangeAssist()
        assertFalse(feed(e, listOf(car(1, -3.5f, -9f, 4f, sog = 20.7f)), 0, 4, turn = 'L').pathOk)
        // 右邊的車不影響往左換
        val f = LaneChangeAssist()
        assertTrue(feed(f, listOf(car(1, 3.5f, 2f, 0f, sog = 16.7f)), 0, 4, turn = 'L').pathOk)
        // 自己車道正前方的車是跟車距離的問題，不算換道衝突
        val g = LaneChangeAssist()
        assertTrue(feed(g, listOf(car(1, 0f, 7f, 0f, sog = 16.7f)), 0, 4, turn = 'L').pathOk)
    }

    @Test
    fun pathTargetAfterCrossingTheLine() {
        // 打左燈換道：跨過線之後燈還亮著，路徑目標要是剛進入的車道，不是再下一條
        val lca = LaneChangeAssist()
        var t = 0L
        var x = 0.0       // 自車相對原車道中心的位置（往左為負）
        var s: LcaState?
        repeat(60) {
            if (it >= 10) x -= 0.06    // 0.6 m/s 往左
            var l = -1.75 - x
            var r = 1.75 - x
            if (l > 0) { r = l; l -= 3.5 }
            s = lca.update(emptyList(), t, l.toFloat(), r.toFloat(), 'L', null, null)   // 沒車速 → 路徑沿車頭方向，終點 x = 目標
            val end = s!!.path!!.last()[0]
            val newLaneCentre = (-3.5 - x).toFloat()
            assertEquals("t=$t 自車 x=$x", newLaneCentre, end, 0.3f)
            t += 100
        }
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
        // 「後→側鏡頭交接 +5」只有 5 km/h 接近：設計上不靠 TTC 升紅燈（< 7 km/h 只亮琥珀），不列入
        for (n in listOf("左後來車 +10km/h", "左後來車 +30km/h", "打左燈+盲點有車", "未打燈左偏+盲點有車")) {
            val a = r(n)
            assertTrue("$n 二級漏報 ${a.misses(2)}/${a.onsets(2)}", a.misses(2) <= a.onsets(2) / 10)
            // +30 km/h：後方 15 m 才看得到，第一眼 TTC 就只剩 ~1.3 s；要 3–4 次偵測才估得出接近速度
            assertTrue("$n 二級延遲 ${a.latMean(2)}", a.latMean(2) < if (n.contains("+30")) 1.25 else 0.6)
        }
        assertTrue(r("打左燈+盲點有車").pathOkAgree > 0.9)
        assertTrue(r("未打燈左偏 無來車").intentMiss <= 2)
    }

    @Test
    fun scenarioSafetyReview() {
        println("\n=== 安全審查：獨立需求（必須/不該亮），每情境 25 個亂數種子 ===")
        val res = (LcaScenarios.all() + LcaScenarios.hard()).associate { it.name to LcaScenarios.evaluate(it) }
        for (a in res.values) println(a.reqRow())
        fun r(n: String) = res.getValue(n)

        for ((n, a) in res) {
            // 漏報（危險）：必須亮的整段沒亮——只有感測距離極限的情境（+50 km/h 來車在 15 m 處才看得到）容許少數
            val ep1 = if (n.contains("+50")) 2 else 0
            val ep2 = when { n.contains("+50") -> 4; n.contains("煞車") -> 2; else -> 1 }
            assertTrue("$n 必須一級漏段 ${a.reqMissEp(1)}/${a.reqEp(1)}", a.reqMissEp(1) <= ep1)
            assertTrue("$n 必須二級漏段 ${a.reqMissEp(2)}/${a.reqEp(2)}", a.reqMissEp(2) <= ep2)
            assertTrue("$n 必須一級漏時 ${a.reqMissRate(1)}", a.reqMissRate(1) < 0.25)
            assertTrue("$n 危險卻顯示可行 ${a.badPathOk}", a.badPathOk < 0.01)
            // 擾人：不該亮卻亮（不知道排檔的倒車是已知限制，另外記錄）
            if (n == "倒車經過路邊停車 不知排檔") continue
            assertTrue("$n 不該亮一級 ${a.faReq(1)}", a.faReq(1) < 0.015)
            assertTrue("$n 不該亮二級 ${a.faReq(2)}", a.faReq(2) < 0.015)
        }
        // 這次審查修掉的缺陷（括號內是修之前）
        assertEquals("鑽車縫機車（19/25 段漏報）", 0, r("機車鑽縫 左 自車靠右").reqMissEp(1))
        assertEquals("右側鑽車縫（1/25）", 0, r("機車鑽縫 右 不打燈").reqMissEp(1))
        assertTrue("車道線錯一條（98% 漏報）", r("車道線 漏近線報遠線(3m道)").reqMissRate(1) < 0.02)
        assertEquals("知道排檔的倒車（48% 一級、28% 二級誤報）", 0.0, r("倒車經過路邊停車 知道排檔").faReq(1) + r("倒車經過路邊停車 知道排檔").faReq(2), 0.0)
        assertTrue("塞車時靜止車讓兩側一直亮（50%）", r("停滯車陣 機車鑽縫").extraOn < 0.15)
        assertTrue("目標車道前方並行車：路徑仍顯示可行（98%）", r("打左燈 左前方並行車").pathOkShare < 0.1)
        assertTrue("目標車道前方遠車：路徑可行", r("打左燈 左前方遠車").pathOkShare > 0.9)
        // 已知限制：GPS 只有速率、又不知道排檔時倒車，路邊停的車看起來就像後方來車
        println("已知限制 倒車不知排檔：不該亮 一級 %.1f%% 二級 %.1f%%".format(
            r("倒車經過路邊停車 不知排檔").faReq(1) * 100, r("倒車經過路邊停車 不知排檔").faReq(2) * 100))
    }
}
