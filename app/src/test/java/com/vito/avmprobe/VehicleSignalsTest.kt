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
        val s = VehicleState('L', 'R', 123.456f, 4.04f, floatArrayOf(inf, 1.234f, 0.3f, 120f), null, 0)
        val j = JSONObject(VehicleSignals.toJson(s).toString())
        assertEquals("L", j.getString("turn"))
        assertEquals("R", j.getString("gear"))
        assertEquals(123.5, j.getDouble("steer"), 1e-12)
        assertEquals(4.0, j.getDouble("speed"), 1e-12)
        val f = j.getJSONObject("pdc").getJSONArray("f")
        assertEquals(4, f.length())
        assertEquals(99.0, f.getDouble(0), 0.0)
        assertEquals(1.23, f.getDouble(1), 1e-12)
        assertEquals(0.3, f.getDouble(2), 1e-12)
        assertEquals(99.0, f.getDouble(3), 0.0)
        assertTrue(j.getJSONObject("pdc").isNull("r"))

        val e = JSONObject(VehicleSignals.toJson(VehicleState()).toString())
        for (k in listOf("turn", "gear", "steer", "speed")) assertTrue(k, e.has(k) && e.isNull(k))
        assertTrue(e.getJSONObject("pdc").isNull("f"))
        assertTrue(e.getJSONObject("pdc").isNull("r"))
    }

    // ---------- 模擬來源 ----------

    @Test
    fun simulatedScenario() {
        val vs = VehicleSignals()
        for (t in 0L until SimulatedSource.CYCLE_MS step 50) {
            SimulatedSource.pump(vs, t)
            val s = vs.snapshot(t)
            when (SimulatedSource.phaseName(t)) {
                "前進左轉" -> { assertEquals("t=$t", 'L', s.turn); assertEquals('D', s.gear) }
                // 最後一次亮在 6950 → 保持到 8450
                "換檔" -> { assertEquals('N', s.gear); if (t > 8_450) assertNull("t=$t", s.turn) else assertEquals('L', s.turn) }
                "倒車入庫" -> {
                    assertEquals('R', s.gear); assertNull(s.turn)
                    assertNotNull(s.pdcRear); assertNotNull(s.pdcFront); assertNotNull(s.steerDeg)
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
            out.add(Case(steer, rev, g2, GuideConfig(lengthM = 3.5, stepM = 0.25)))
        }
        out.add(Case(200.0, true, VehicleGeometry(length = 4.5, width = 1.8, wheelbase = 2.7, rearOverhang = 1.0),
            GuideConfig(lengthM = 2.0)))
        return out
    }

    private fun num(v: Double): Any = if (v.isFinite()) v else JSONObject.NULL

    /** 一個 case 一行，方便看 diff */
    private fun buildFixture(): String {
        val lines = fixtureCases().map { c ->
            val gl = BicycleModel.guideLines(c.steer, c.reverse, c.g, c.cfg)
            val opts = JSONObject()
                .put("length", c.g.length).put("width", c.g.width).put("wheelbase", c.g.wheelbase)
                .put("rearOverhang", c.g.rearOverhang).put("steerRatio", c.g.steerRatio)
                .put("maxSteerDeg", c.g.maxSteerDeg).put("guideLen", c.cfg.lengthM)
                .put("guideStep", c.cfg.stepM).put("markers", JSONArray(c.cfg.markersM))
            fun pts(p: List<PointXZ>) = JSONArray().also { a -> p.forEach { a.put(JSONArray().put(it.x).put(it.z)) } }
            val mk = JSONArray().also { a ->
                gl.markers.forEach { a.put(JSONArray().put(it.distM).put(it.left.x).put(it.left.z).put(it.right.x).put(it.right.z)) }
            }
            JSONObject()
                .put("steer", c.steer ?: JSONObject.NULL).put("reverse", c.reverse).put("opts", opts)
                .put("radius", num(BicycleModel.turningRadius(c.steer, c.g)))
                .put("dist", JSONArray(gl.dist)).put("left", pts(gl.left)).put("right", pts(gl.right))
                .put("markers", mk)
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
        val node = listOf("/opt/homebrew/bin/node", "/usr/local/bin/node", "/usr/bin/node").firstOrNull { File(it).canExecute() }
            ?: System.getenv("PATH")?.split(File.pathSeparator)?.map { File(it, "node") }?.firstOrNull { it.canExecute() }?.path
        assumeTrue("找不到 node，略過 JS 一致性檢查", node != null)
        val script = File("src/test/js/vehicle_guides_check.js")
        assumeTrue("找不到 ${script.absolutePath}", script.exists())
        val p = ProcessBuilder(node!!, script.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        assertTrue("node 逾時", p.waitFor(60, TimeUnit.SECONDS))
        assertEquals(out, 0, p.exitValue())
        println(out.trim())
    }
}
