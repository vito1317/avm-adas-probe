package com.vito.avmprobe

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.tan

/**
 * 車身訊號：單車模型、輔助線幾何、各欄位過期（含方向燈閃爍保持）、JSON、模擬來源，
 * 以及 vehicle3d.js 與 Kotlin 輔助線的一致性（vehicle_guides_fixture.json + node）。
 */
class VehicleSignalsTest {

    private val g = VehicleGeometry()
    private val inf = Float.POSITIVE_INFINITY

    // ---------- 單車模型 ----------

    @Test
    fun wheelAngleFromSteeringRatio() {
        assertEquals(10.0, BicycleModel.wheelAngleDeg(150.0, g), 1e-12)
        assertEquals(-20.0, BicycleModel.wheelAngleDeg(-300.0, g), 1e-12)
        assertEquals(0.0, BicycleModel.wheelAngleDeg(null, g), 0.0)
        assertEquals(0.0, BicycleModel.wheelAngleDeg(Double.NaN, g), 0.0)
        assertEquals(10.0, BicycleModel.wheelAngleDeg(165.0, g.copy(steerRatio = 16.5)), 1e-12)
        // 超過打到底的角度 → 夾在打到底
        assertEquals(g.maxWheelDeg, BicycleModel.wheelAngleDeg(900.0, g), 1e-12)
        assertEquals(-g.maxWheelDeg, BicycleModel.wheelAngleDeg(-900.0, g), 1e-12)
    }

    @Test
    fun turningRadiusForKnownSteer() {
        // 方向盤 150° ÷ 15 = 前輪 10° → R = 2.888 / tan10° ≈ 16.379 m（向右為正）
        assertEquals(2.888 / tan(Math.toRadians(10.0)), BicycleModel.turningRadius(150.0, g), 1e-9)
        assertEquals(16.379, BicycleModel.turningRadius(150.0, g), 0.001)
        assertEquals(-2.888 / tan(Math.toRadians(20.0)), BicycleModel.turningRadius(-300.0, g), 1e-9)
        assertEquals(-7.935, BicycleModel.turningRadius(-300.0, g), 0.001)
        assertTrue(BicycleModel.turningRadius(0.0, g).isInfinite())
        assertTrue(BicycleModel.turningRadius(null, g).isInfinite())
        assertEquals(0.0, BicycleModel.curvature(null, g), 0.0)
        assertEquals(1 / BicycleModel.turningRadius(222.0, g), BicycleModel.curvature(222.0, g), 1e-15)
    }

    @Test
    fun fullLockMatchesTurningCircle() {
        // E60：2.9 圈 lock-to-lock → 單邊 522°，15:1 → 前輪 34.8°；原廠迴轉直徑約 11.4 m
        val rFull = BicycleModel.turningRadius(g.maxSteerDeg, g)
        assertEquals(4.155, rFull, 0.01)
        val d = BicycleModel.turningCircle(g.maxSteerDeg, g)
        assertEquals(11.4, d, 0.25)
        assertEquals(d, BicycleModel.turningCircle(-g.maxSteerDeg, g), 1e-9)
        assertEquals(rFull, BicycleModel.turningRadius(700.0, g), 1e-12)
        assertTrue(BicycleModel.turningCircle(0.0, g).isInfinite())
        assertEquals(0.833, g.frontOverhang, 0.005)
    }

    @Test
    fun steerRatioFromMeasuredLock() {
        // turningCircle 的反函數：來回一趟回到同一個前輪角 / 轉向比
        val d = BicycleModel.turningCircle(g.maxSteerDeg, g)
        assertEquals(g.maxWheelDeg, BicycleModel.wheelAngleForTurningCircle(d, g), 1e-9)
        val back = g.calibratedToLock(g.maxSteerDeg, d)
        assertEquals(15.0, back.steerRatio, 1e-9)
        assertEquals(522.0, back.maxSteerDeg, 0.0)
        // 實測：打到底 522°、迴轉直徑 11.4 m → δmax ≈ 34.93°、轉向比 ≈ 14.94
        val cal = g.calibratedToLock(522.0, 11.4)
        assertEquals(34.93, cal.maxWheelDeg, 0.01)
        assertEquals(14.94, cal.steerRatio, 0.01)
        assertEquals(11.4, BicycleModel.turningCircle(522.0, cal), 1e-9)
        // 打到底 480° 但迴轉直徑一樣 → 轉向比變小
        assertTrue(g.calibratedToLock(480.0, 11.4).steerRatio < cal.steerRatio)
        assertThrows(IllegalArgumentException::class.java) { BicycleModel.wheelAngleForTurningCircle(5.0, g) }
    }

    @Test
    fun steerCalibration() {
        assertEquals(30f, SteerCalibration().apply(30f), 0f)
        assertEquals(-30f, SteerCalibration.ISO8855.apply(30f), 0f)          // ISO 8855：+ = 向左
        assertEquals(-88f, SteerCalibration(-1, 12f).apply(100f), 1e-6f)     // 置中時讀到 12°
        assertThrows(IllegalArgumentException::class.java) { SteerCalibration(sign = 2) }
        val vs = VehicleSignals(clock = { 0L })
        vs.steerCal = SteerCalibration(-1, 5f)
        vs.reportSteerRaw(95f)
        assertEquals(-90f, vs.snapshot().steerDeg)
        vs.reportSteerRaw(Float.NaN, 10)
        assertNull(vs.snapshot(10).steerDeg)
    }

    // ---------- 輔助線 ----------

    @Test
    fun guideLinesStartAtBumperCorners() {
        for (steer in listOf(null, 0.0, 200.0, -522.0)) for (rev in listOf(false, true)) {
            val gl = BicycleModel.guideLines(steer, rev, g)
            val zb = if (rev) -g.length / 2 else g.length / 2
            assertEquals(-g.width / 2, gl.left[0].x, 1e-12)
            assertEquals(zb, gl.left[0].z, 1e-12)
            assertEquals(g.width / 2, gl.right[0].x, 1e-12)
            assertEquals(zb, gl.right[0].z, 1e-12)
            assertEquals(51, gl.left.size)
            assertEquals(51, gl.right.size)
            assertEquals(0.0, gl.dist.first(), 0.0)
            assertEquals(5.0, gl.dist.last(), 1e-12)
            for (i in 1 until gl.dist.size) assertEquals(0.1, gl.dist[i] - gl.dist[i - 1], 1e-9)
            assertEquals(listOf(0.5, 1.0, 2.0, 3.0), gl.markers.map { it.distM })
        }
    }

    @Test
    fun straightWhenSteerZeroOrUnknown() {
        for (steer in listOf(null, 0.0, Double.NaN)) for (rev in listOf(false, true)) {
            val gl = BicycleModel.guideLines(steer, rev, g)
            val sg = if (rev) -1.0 else 1.0
            for (i in gl.dist.indices) {
                val z = sg * (g.length / 2 + gl.dist[i])
                assertEquals(-g.width / 2, gl.left[i].x, 1e-12)
                assertEquals(z, gl.left[i].z, 1e-9)
                assertEquals(g.width / 2, gl.right[i].x, 1e-12)
                assertEquals(z, gl.right[i].z, 1e-9)
            }
            for (m in gl.markers) {
                assertEquals(sg * (g.length / 2 + m.distM), m.left.z, 1e-9)
                assertEquals(-g.width / 2, m.left.x, 1e-12)
                assertEquals(g.width / 2, m.right.x, 1e-12)
            }
        }
    }

    @Test
    fun guideLinesFollowTurningCircle() {
        for (steer in listOf(90.0, -250.0, 522.0, -522.0)) for (rev in listOf(false, true)) {
            val gl = BicycleModel.guideLines(steer, rev, g)
            val cx = BicycleModel.turningRadius(steer, g)
            val cz = g.rearAxleZ
            fun rad(p: PointXZ) = hypot(p.x - cx, p.z - cz)
            // 剛體繞轉彎中心旋轉：每個角點到中心距離不變
            val rl = rad(gl.left[0])
            val rr = rad(gl.right[0])
            for (i in gl.dist.indices) {
                assertEquals(rl, rad(gl.left[i]), 1e-9)
                assertEquals(rr, rad(gl.right[i]), 1e-9)
            }
            // 保險桿中心走過的弧長 = dist
            var arc = 0.0
            for (i in 1 until gl.dist.size) {
                val ax = (gl.left[i].x + gl.right[i].x) / 2 - (gl.left[i - 1].x + gl.right[i - 1].x) / 2
                val az = (gl.left[i].z + gl.right[i].z) / 2 - (gl.left[i - 1].z + gl.right[i - 1].z) / 2
                arc += hypot(ax, az)
            }
            assertEquals(5.0, arc, 1e-3)
            // 向右打：前進往右；倒車時車尾也往右擺
            val endX = (gl.left.last().x + gl.right.last().x) / 2
            if (steer > 0) assertTrue("steer=$steer rev=$rev endX=$endX", endX > 0.3)
            else assertTrue("steer=$steer rev=$rev endX=$endX", endX < -0.3)
            // 前進往前、倒車往後
            val endZ = (gl.left.last().z + gl.right.last().z) / 2
            if (rev) assertTrue(endZ < -g.length / 2) else assertTrue(endZ > g.length / 2)
            // 距離標記就是對應距離的取樣點
            val m1 = gl.markers.first { it.distM == 1.0 }
            assertEquals(gl.left[10].x, m1.left.x, 1e-12)
            assertEquals(gl.right[10].z, m1.right.z, 1e-12)
        }
    }

    @Test
    fun innerTraceShowsOfftracking() {
        for (steer in listOf(150.0, -300.0, 522.0, -522.0)) for (rev in listOf(false, true)) {
            val gl = BicycleModel.guideLines(steer, rev, g)
            val r = BicycleModel.turningRadius(steer, g)
            val side = if (r > 0) 1 else -1
            assertEquals(side, gl.innerSide)
            val inner = gl.inner!!
            assertEquals(gl.dist.size, inner.size)
            // 起點：內側車身、後軸處
            assertEquals(side * g.width / 2, inner[0].x, 1e-12)
            assertEquals(g.rearAxleZ, inner[0].z, 1e-12)
            // 整條都在半徑 |R| − W/2 上（整台車離轉彎中心最近的點）
            for (p in inner) assertEquals(abs(r) - g.width / 2, hypot(p.x - r, p.z - g.rearAxleZ), 1e-9)
            // 內側保險桿角線比真正的內側邊界外面多少（前進 = 內輪差被藏住的量）
            val innerCorner = if (side > 0) gl.right[0] else gl.left[0]
            val hidden = hypot(innerCorner.x - r, innerCorner.z - g.rearAxleZ) - (abs(r) - g.width / 2)
            val exp = when (abs(steer)) {
                522.0 -> if (rev) 0.19 else 1.70
                300.0 -> if (rev) 0.09 else 0.93
                else -> if (rev) 0.04 else 0.44
            }
            assertEquals("steer=$steer rev=$rev", exp, hidden, 0.01)
            // 同一個弧長參數：第 i 點跟保險桿角線同一個姿態（相對位置固定）
            val c0 = if (side > 0) gl.right else gl.left
            for (i in gl.dist.indices) {
                assertEquals(hypot(c0[0].x - inner[0].x, c0[0].z - inner[0].z),
                    hypot(c0[i].x - inner[i].x, c0[i].z - inner[i].z), 1e-9)
            }
        }
        // 直行沒有內側
        for (steer in listOf(null, 0.0)) for (rev in listOf(false, true)) {
            val gl = BicycleModel.guideLines(steer, rev, g)
            assertEquals(0, gl.innerSide)
            assertNull(gl.inner)
            assertNull(gl.swing)
        }
    }

    @Test
    fun reverseFrontSwing() {
        // 倒車向右打到底：外側（左）前角甩出，比外側後角線多約 1.10 m
        val gl = BicycleModel.guideLines(522.0, true, g)
        val r = BicycleModel.turningRadius(522.0, g)
        val sw = gl.swing!!
        assertEquals(26, sw.size)                                    // 0..2.5 m，每 0.1 m
        assertEquals(-g.width / 2, sw[0].x, 1e-12)
        assertEquals(g.length / 2, sw[0].z, 1e-12)
        val rs = hypot(sw[0].x - r, sw[0].z - g.rearAxleZ)
        for (p in sw) assertEquals(rs, hypot(p.x - r, p.z - g.rearAxleZ), 1e-9)
        val rOuterRear = hypot(gl.left[0].x - r, gl.left[0].z - g.rearAxleZ)
        assertEquals(1.10, rs - rOuterRear, 0.01)
        // 車頭往外（左）甩
        assertTrue(sw.last().x < -g.width / 2 - 0.3)
        // 向左打：甩向右；前進不算；swingM = 0 不算
        val gl2 = BicycleModel.guideLines(-300.0, true, g)
        assertEquals(g.width / 2, gl2.swing!![0].x, 1e-12)
        assertTrue(gl2.swing!!.last().x > g.width / 2)
        assertNull(BicycleModel.guideLines(300.0, false, g).swing)
        assertNull(BicycleModel.guideLines(300.0, true, g, GuideConfig(swingM = 0.0)).swing)
        assertEquals(11, BicycleModel.guideLines(300.0, true, g, GuideConfig(swingM = 1.0)).swing!!.size)
    }

    @Test
    fun guideConfigLengthAndStep() {
        val gl = BicycleModel.guideLines(300.0, true, g, GuideConfig(lengthM = 3.5, stepM = 0.25))
        assertEquals(15, gl.left.size)
        assertEquals(3.5, gl.dist.last(), 1e-12)
        val short = BicycleModel.guideLines(300.0, false, g, GuideConfig(lengthM = 2.0))
        assertEquals(listOf(0.5, 1.0, 2.0), short.markers.map { it.distM })
        assertEquals(21, short.left.size)
    }

    // ---------- 過期 ----------

    @Test
    fun fieldsExpireIndependently() {
        val vs = VehicleSignals()
        vs.reportGear('R', 0)
        vs.reportSteer(120f, 0)
        vs.reportSpeed(3f, 0)
        vs.reportPdc(floatArrayOf(inf, inf, 1.1f, inf), floatArrayOf(0.8f, 0.5f, inf, inf), 0)

        var s = vs.snapshot(1000)
        assertEquals('R', s.gear)
        assertEquals(0.5f, s.pdcMin(false))
        assertEquals(1.1f, s.pdcMin(true))
        assertEquals(1000L, s.atMs)

        s = vs.snapshot(1001)                // 雷達 1 s 過期，其他還在
        assertNull(s.pdcFront)
        assertNull(s.pdcRear)
        assertEquals('R', s.gear)
        assertEquals(120f, s.steerDeg)

        vs.reportSteer(-30f, 1500)
        s = vs.snapshot(2001)                // 檔位/車速 2 s 過期；方向盤剛更新
        assertNull(s.gear)
        assertNull(s.speedKmh)
        assertEquals(-30f, s.steerDeg)

        assertTrue(vs.snapshot(3501).isEmpty)
    }

    @Test
    fun turnHeldThroughBlinkOffPhase() {
        val vs = VehicleSignals()
        val stopAt = 5000L
        // 亮 0.33 s、暗 0.7 s（偏慢的閃爍），每 10 ms 回報一次燈的亮暗
        for (t in 0L..7000L step 10) {
            val lit = t < stopAt && t % 1030 < 330
            vs.reportTurn(if (lit) 'L' else null, t)
            if (t < stopAt) assertEquals("t=$t", 'L', vs.snapshot(t).turn)
        }
        // 最後一次亮在 4440 → 保持到 4440 + 1500
        assertEquals('L', vs.snapshot(5940).turn)
        assertNull(vs.snapshot(5941).turn)
    }

    @Test
    fun turnLitFollowsLampPhase() {
        val vs = VehicleSignals()
        // 只回報「亮」的來源（撥桿層級）：不知道亮暗 → null，畫面端自己閃
        vs.reportTurn('R', 0)
        vs.reportTurn('R', 200)
        assertEquals('R', vs.snapshot(300).turn)
        assertNull(vs.snapshot(300).turnLit)
        // 會回報暗期的來源（燈層級）：亮 0.33 s、暗 0.33 s
        val ws = VehicleSignals()
        for (t in 0L..3000L step 10) {
            val lit = t < 2000 && t % 667 < 333
            ws.reportTurn(if (lit) 'L' else null, t)
            val s = ws.snapshot(t)
            assertEquals("t=$t", 'L', s.turn)
            if (t >= 400) assertEquals("t=$t", lit, s.turnLit)    // 第一次暗期之後才知道亮暗
        }
        // 撥桿回正後：方向還保持（作用中），但燈是暗的
        assertEquals(false, ws.snapshot(3000).turnLit)
        assertNull(ws.snapshot(3500).turn)
        assertNull(ws.snapshot(3500).turnLit)
    }

    @Test
    fun clockJumpBackDoesNotFreeze() {
        // 車機沒有 RTC：開機後 GPS/NTP 對時，牆鐘可能往回跳
        var now = 1_000_000L
        val vs = VehicleSignals(clock = { now })
        vs.reportGear('R')
        vs.reportPdcRear(floatArrayOf(0.4f, 0.5f, inf, inf))
        vs.reportTurn('L')
        assertEquals('R', vs.snapshot().gear)
        now -= 60_000                                   // 往回跳 60 s
        var s = vs.snapshot()
        assertNull("舊值不能凍住", s.gear)
        assertNull(s.pdcRear)
        assertNull(s.turn)
        vs.reportGear('D')                              // 新回報照收
        vs.reportPdcRear(floatArrayOf(1.5f, inf, inf, inf))
        s = vs.snapshot()
        assertEquals('D', s.gear)
        assertEquals(1.5f, s.pdcMin(false))
        now += 30_000
        assertNull("新值照常過期", vs.snapshot().gear)
        // 小幅倒退（< 1 s）仍當成亂序：拒收、舊值照常有效
        vs.reportGear('N')
        vs.reportGear('R', now - 300)
        assertEquals('N', vs.snapshot().gear)
        assertEquals('N', vs.snapshot(now - 500).gear)
        assertNull(vs.snapshot(now - 1001).gear)
    }

    @Test
    fun defaultClockIsMonotonic() {
        val vs = VehicleSignals()
        val a = vs.now()
        assertTrue(abs(a - System.nanoTime() / 1_000_000) < 1000)
        vs.reportGear('D')                               // 預設時間 = sink 的時鐘
        assertEquals('D', vs.snapshot().gear)
        assertEquals('D', vs.snapshot(a + 1500).gear)
        assertNull(vs.snapshot(vs.now() + 2500).gear)
    }

    @Test
    fun hazardAndExplicitClear() {
        val vs = VehicleSignals()
        vs.reportLamps(left = true, right = true, atMs = 0)
        assertEquals('H', vs.snapshot(0).turn)
        vs.reportLamps(left = false, right = false, atMs = 400)   // 雙黃燈的暗期
        assertEquals('H', vs.snapshot(900).turn)
        vs.reportTurn('R', 1000)
        assertEquals('R', vs.snapshot(1000).turn)
        vs.clearTurn(1100)
        assertNull(vs.snapshot(1100).turn)
        vs.reportTurn('L', 1050)                                  // 比清除還舊的回報（亂序）不採用
        assertNull(vs.snapshot(1200).turn)
        vs.reportGear('D', 3000)
        vs.reportGear('R', 2500)
        assertEquals('D', vs.snapshot(3000).gear)
        assertThrows(IllegalArgumentException::class.java) { vs.reportTurn('X', 0) }
        assertThrows(IllegalArgumentException::class.java) { vs.reportGear('S', 0) }
    }

    @Test
    fun reportAllTreatsNullTurnAsDarkLamp() {
        val vs = VehicleSignals()
        vs.reportAll(VehicleState(turn = 'L', gear = 'R', pdcRear = floatArrayOf(1f, 1f, 1f, 1f), atMs = 0))
        vs.reportAll(VehicleState(turn = null, gear = 'R', pdcRear = null, atMs = 300))
        val s = vs.snapshot(300)
        assertEquals('L', s.turn)                // 燈暗 → 保持
        assertNull(s.pdcRear)                    // 雷達關掉 → 立即消失
        assertEquals('R', s.gear)
    }

    @Test
    fun snapshotArraysAreCopies() {
        val vs = VehicleSignals()
        val a = floatArrayOf(1f, 2f, 3f, 4f)
        vs.reportPdcRear(a, 0)
        a[0] = 9f
        val s = vs.snapshot(0)
        assertEquals(1f, s.pdcRear!![0])
        s.pdcRear!![1] = 9f
        assertEquals(2f, vs.snapshot(0).pdcRear!![1])
        assertEquals(s.copy(), s)
        assertEquals(vs.snapshot(0), vs.snapshot(0))
    }

    @Test
    fun listeners() {
        val vs = VehicleSignals()
        val got = ArrayList<VehicleState>()
        val l = VehicleSignals.Listener { got.add(it) }
        vs.addListener(VehicleSignals.Listener { throw IllegalStateException("壞掉的 listener") })
        vs.addListener(l)
        vs.reportGear('D', 10)
        vs.reportSpeed(12f, 20)
        assertEquals(2, got.size)
        assertEquals('D', got[1].gear)
        assertEquals(12f, got[1].speedKmh)
        assertEquals(20L, got[1].atMs)
        vs.removeListener(l)
        vs.reportGear('P', 30)
        assertEquals(2, got.size)
    }

    // ---------- JSON ----------

    @Test
    fun jsonPayload() {
        val s = VehicleState('L', 'R', 123.456f, 4.04f, floatArrayOf(inf, 1.234f, 0.3f, 120f),
            floatArrayOf(Float.NaN, -0.2f, Float.NEGATIVE_INFINITY, 0.456f), 0, turnLit = true)
        val j = JSONObject(VehicleSignals.toJson(s).toString())
        assertEquals("L", j.getString("turn"))
        assertTrue(j.getBoolean("lit"))
        assertEquals("R", j.getString("gear"))
        assertEquals(123.5, j.getDouble("steer"), 1e-12)
        assertEquals(4.0, j.getDouble("speed"), 1e-12)
        val f = j.getJSONObject("pdc").getJSONArray("f")
        assertEquals(4, f.length())
        assertEquals(99.0, f.getDouble(0), 0.0)
        assertEquals(1.23, f.getDouble(1), 1e-12)
        assertEquals(0.3, f.getDouble(2), 1e-12)
        assertEquals(99.0, f.getDouble(3), 0.0)
        val r = j.getJSONObject("pdc").getJSONArray("r")
        assertTrue("NaN = 故障 → null", r.isNull(0))
        assertEquals(0.0, r.getDouble(1), 0.0)
        assertTrue(r.isNull(2))
        assertEquals(0.46, r.getDouble(3), 1e-12)
        assertEquals(0f, s.pdcMin(false))                 // 負值當 0、故障略過
        assertEquals(0.456f, VehicleState(pdcRear = floatArrayOf(Float.NaN, 0.8f, inf, 0.456f)).pdcMin(false))
        assertNull(VehicleState(pdcRear = floatArrayOf(Float.NaN, inf, inf, Float.NaN)).pdcMin(false))

        val e = JSONObject(VehicleSignals.toJson(VehicleState()).toString())
        for (k in listOf("turn", "lit", "gear", "steer", "speed")) assertTrue(k, e.has(k) && e.isNull(k))
        assertTrue(e.getJSONObject("pdc").isNull("f"))
        assertTrue(e.getJSONObject("pdc").isNull("r"))
        assertEquals(false, JSONObject(VehicleSignals.toJson(VehicleState(turn = 'H', turnLit = false)).toString()).get("lit"))
    }

    // ---------- 模擬來源 ----------

    @Test
    fun simulatedScenario() {
        val vs = VehicleSignals()
        for (t in 0L until SimulatedSource.CYCLE_MS step 50) {
            SimulatedSource.pump(vs, t)
            val s = vs.snapshot(t)
            when (SimulatedSource.phaseName(t)) {
                "前進左轉" -> {
                    assertEquals("t=$t", 'L', s.turn); assertEquals('D', s.gear)
                    if (t >= 400) assertEquals("t=$t", SimulatedSource.stateAt(t)!!.turn != null, s.turnLit)
                }
                // 最後一次亮在 6950 → 保持到 8450（保持期間燈是暗的）
                "換檔" -> {
                    assertEquals('N', s.gear)
                    if (t > 8_450) assertNull("t=$t", s.turn) else { assertEquals('L', s.turn); assertEquals(false, s.turnLit) }
                }
                "倒車入庫" -> {
                    assertEquals('R', s.gear); assertNull(s.turn)
                    assertNotNull(s.pdcRear); assertNotNull(s.pdcFront); assertNotNull(s.steerDeg)
                    assertEquals("t=$t", t >= SimulatedSource.FAULT_FROM_MS, s.pdcFront!![0].isNaN())
                }
                "雙黃燈" -> { assertEquals("t=$t", 'H', s.turn); assertEquals('P', s.gear) }
                "右轉" -> { assertEquals("t=$t", 'R', s.turn); assertEquals(120f, s.steerDeg) }
                "訊號中斷" -> if (t >= 39_000) assertTrue("t=$t $s", s.isEmpty)
                else -> fail("未知段落 ${SimulatedSource.phaseName(t)}")
            }
        }
        // 倒車時後方牆面越來越近，最後進入連續紅（< 0.3 m）
        assertTrue(SimulatedSource.stateAt(10_000)!!.pdcMin(false)!! > 1.5f)
        assertTrue(SimulatedSource.stateAt(24_900)!!.pdcMin(false)!! < 0.3f)
        // 方向盤來回打到接近底
        val steers = (9_000L until 25_000L step 100).map { SimulatedSource.stateAt(it)!!.steerDeg!! }
        assertTrue(steers.max() > 450f && steers.min() < -450f)
        // 低速右轉才有前進輔助線（< 15 km/h）
        assertTrue(SimulatedSource.stateAt(32_000)!!.speedKmh!! >= 15f)
        assertTrue(SimulatedSource.stateAt(35_000)!!.speedKmh!! < 15f)
        assertNull(SimulatedSource.stateAt(38_000))
        assertEquals(SimulatedSource.stateAt(1_234), SimulatedSource.stateAt(1_234 + SimulatedSource.CYCLE_MS))
    }

    @Test
    fun simulatedSourceThread() {
        val vs = VehicleSignals()
        val sim = SimulatedSource(periodMs = 20)
        vs.attach(sim)
        try {
            assertEquals("模擬", vs.sourceName)
            var s = vs.snapshot()
            val deadline = System.currentTimeMillis() + 2000
            while (s.gear == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
                s = vs.snapshot()
            }
            assertEquals('D', s.gear)
        } finally {
            vs.detach()
        }
        assertNull(vs.sourceName)
    }

    // ---------- Kotlin ↔ vehicle3d.js 輔助線一致性 ----------

    private class Case(val steer: Double?, val reverse: Boolean, val g: VehicleGeometry, val cfg: GuideConfig)

    private fun fixtureCases(): List<Case> {
        val out = ArrayList<Case>()
        val d = VehicleGeometry()
        for (steer in listOf(null, 0.0, 45.5, -90.0, 250.0, 522.0, -522.0, 700.0)) for (rev in listOf(false, true)) {
            out.add(Case(steer, rev, d, GuideConfig()))
        }
        val g2 = d.copy(steerRatio = 16.5, maxSteerDeg = 540.0)
        for (steer in listOf(300.0, -480.0)) for (rev in listOf(false, true)) {
            out.add(Case(steer, rev, g2, GuideConfig(lengthM = 3.5, stepM = 0.25, swingM = 1.2)))
        }
        out.add(Case(200.0, true, VehicleGeometry(length = 4.5, width = 1.8, wheelbase = 2.7, rearOverhang = 1.0),
            GuideConfig(lengthM = 2.0)))
        out.add(Case(-350.0, true, d, GuideConfig(swingM = 0.0)))
        out.add(Case(150.0, true, d.calibratedToLock(500.0, 11.4), GuideConfig(swingM = 4.0)))
        return out
    }

    private fun num(v: Double): Any = if (v.isFinite()) v else JSONObject.NULL

    /** 一個 case 一行，方便看 diff */
    private fun buildFixture(): String {
        val lines = fixtureCases().map { c ->
            val gl = BicycleModel.guideLines(c.steer, c.reverse, c.g, c.cfg)
            val opts = c.g.jsOpts()
                .put("guideLen", c.cfg.lengthM).put("guideStep", c.cfg.stepM)
                .put("markers", JSONArray(c.cfg.markersM)).put("swingM", c.cfg.swingM)
            fun pts(p: List<PointXZ>?): Any =
                if (p == null) JSONObject.NULL else JSONArray().also { a -> p.forEach { a.put(JSONArray().put(it.x).put(it.z)) } }
            val mk = JSONArray().also { a ->
                gl.markers.forEach { a.put(JSONArray().put(it.distM).put(it.left.x).put(it.left.z).put(it.right.x).put(it.right.z)) }
            }
            JSONObject()
                .put("steer", c.steer ?: JSONObject.NULL).put("reverse", c.reverse).put("opts", opts)
                .put("radius", num(BicycleModel.turningRadius(c.steer, c.g)))
                .put("dist", JSONArray(gl.dist)).put("left", pts(gl.left)).put("right", pts(gl.right))
                .put("markers", mk)
                .put("innerSide", gl.innerSide).put("inner", pts(gl.inner)).put("swing", pts(gl.swing))
                .toString()
        }
        return "{\n\"note\": \"VehicleSignalsTest 由 BicycleModel 產生；vehicle3d.js 的 guides() 要對得上（src/test/js/vehicle_guides_check.js）。\",\n" +
            "\"cases\": [\n" + lines.joinToString(",\n") + "\n]}\n"
    }

    private fun assertJsonClose(exp: Any?, act: Any?, path: String) {
        when {
            exp is JSONObject && act is JSONObject -> {
                assertEquals(path, exp.keySet(), act.keySet())
                for (k in exp.keySet()) assertJsonClose(exp.get(k), act.get(k), "$path.$k")
            }
            exp is JSONArray && act is JSONArray -> {
                assertEquals("$path 長度", exp.length(), act.length())
                for (i in 0 until exp.length()) assertJsonClose(exp.get(i), act.get(i), "$path[$i]")
            }
            exp is Number && act is Number ->
                assertTrue("$path：${exp} vs ${act}", abs(exp.toDouble() - act.toDouble()) <= 1e-9)
            else -> assertEquals(path, exp, act)
        }
    }

    @Test
    fun guideFixtureMatchesKotlin() {
        val fresh = buildFixture()
        File("build/vehicle_guides_fixture.json").apply { parentFile?.mkdirs(); writeText(fresh) }
        val text = javaClass.getResourceAsStream("/vehicle_guides_fixture.json")?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: return fail("缺 vehicle_guides_fixture.json：把 app/build/vehicle_guides_fixture.json 複製到 app/src/test/resources/")
        assertJsonClose(JSONObject(text), JSONObject(fresh), "fixture")
    }

    @Test
    fun jsGuidesMatchKotlin() {
        val node = System.getenv("NODE")?.takeIf { File(it).canExecute() }
            ?: listOf("/opt/homebrew/bin/node", "/usr/local/bin/node", "/usr/bin/node").firstOrNull { File(it).canExecute() }
            ?: System.getenv("PATH")?.split(File.pathSeparator)?.map { File(it, "node") }?.firstOrNull { it.canExecute() }?.path
        val script = File("src/test/js/vehicle_guides_check.js")
        assertTrue("找不到 ${script.absolutePath}", script.exists())
        if (node == null) {
            // CI 上一定要跑：沒有 node 就失敗，不然 JS 跟 Kotlin 走鐘也不會發現
            if (System.getenv("CI") != null) fail("CI 環境找不到 node（可用 NODE 環境變數指定路徑），無法檢查 vehicle3d.js 與 Kotlin 的一致性")
            System.err.println("警告：找不到 node，略過 vehicle3d.js 與 Kotlin 的一致性檢查")
            assumeTrue("找不到 node，略過 JS 一致性檢查", false)
        }
        val p = ProcessBuilder(node!!, script.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        assertTrue("node 逾時", p.waitFor(60, TimeUnit.SECONDS))
        assertEquals(out, 0, p.exitValue())
        println(out.trim())
    }
}
