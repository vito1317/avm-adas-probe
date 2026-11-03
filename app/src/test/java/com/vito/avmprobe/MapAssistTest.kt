package com.vito.avmprobe

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 地圖輔助：真實 Overpass 回應（台北忠孝東路四段、陽明山仰德大道、台南中西區）+ 合成 GPS 軌跡
 * （雜訊 5–7 m 相關誤差、低速航向亂跳、迴轉、路口轉彎）。軌跡由 resources/map/make_traces.py 產生。
 */
class MapAssistTest {

    // ---------------- 測試資料 ----------------

    private fun res(name: String): String =
        javaClass.classLoader!!.getResource("map/$name")!!.readText(Charsets.UTF_8)

    private class Truth(val lat: Double, val lon: Double, val hdg: Double, val v: Double, val part: Int,
                        val s: Double, val way: Long, val uturn: Boolean)

    private class Fix(val t: Long, val lat: Double, val lon: Double, val spd: Double, val brg: Double,
                      val acc: Double, val truth: Truth)

    private class Part(val poly: List<DoubleArray>, val signals: List<Double>, val turns: List<Double>, val length: Double)

    private class Trace(val name: String, val osm: String, val parts: List<Part>, val fixes: List<Fix>)

    private fun trace(name: String): Trace {
        val o = JSONObject(res("trace_$name.json"))
        val parts = ArrayList<Part>()
        val pa = o.getJSONArray("parts")
        for (i in 0 until pa.length()) {
            val p = pa.getJSONObject(i)
            val poly = ArrayList<DoubleArray>()
            val pl = p.getJSONArray("poly")
            for (k in 0 until pl.length()) {
                val q = pl.getJSONArray(k)
                poly.add(doubleArrayOf(q.getDouble(0), q.getDouble(1), q.getDouble(2)))
            }
            parts.add(Part(poly, dlist(p.getJSONArray("signals")), dlist(p.getJSONArray("turns")), p.getDouble("length")))
        }
        val fixes = ArrayList<Fix>()
        val fa = o.getJSONArray("fixes")
        for (i in 0 until fa.length()) {
            val f = fa.getJSONObject(i)
            val t = f.getJSONObject("truth")
            fixes.add(Fix(
                f.getLong("t"), f.getDouble("lat"), f.getDouble("lon"), f.getDouble("spd"),
                if (f.isNull("brg")) Double.NaN else f.getDouble("brg"), f.getDouble("acc"),
                Truth(t.getDouble("lat"), t.getDouble("lon"), t.getDouble("hdg"), t.getDouble("v"), t.getInt("part"),
                    t.getDouble("s"), if (t.isNull("way")) 0L else t.getLong("way"), t.getBoolean("uturn"))
            ))
        }
        return Trace(o.getString("name"), o.getString("osm"), parts, fixes)
    }

    private fun dlist(a: JSONArray) = (0 until a.length()).map { a.getDouble(it) }

    private class Step(val fix: Fix, val st: MapState?)

    private fun run(tr: Trace, assist: MapAssist = MapAssist(), reloadAt: Int = -1): List<Step> {
        val osm = res(tr.osm)
        val f0 = tr.fixes[0]
        assist.loadOverpass(osm, f0.lat, f0.lon)
        // MAP_DEBUG=trace:t0:t1 → 印出該區間每個候選的分數
        val dbg = System.getenv("MAP_DEBUG")?.split(":")
        return tr.fixes.mapIndexed { i, f ->
            if (i == reloadAt) assist.loadOverpass(osm, f.lat + 0.003, f.lon - 0.002)   // 換參考點重載
            val sec = f.t / 1000
            assist.debug = if (dbg != null && dbg.size == 3 && dbg[0] == tr.name && sec in dbg[1].toLong()..dbg[2].toLong())
                { s: String -> println(s) } else null
            val st = assist.update(f.lat, f.lon, f.spd, f.brg, f.acc, f.t)
            if (assist.debug != null) {
                val g = assist.graph!!
                println(String.format("  truth=(%.1f,%.1f) hdg=%.0f -> %s %d", g.toX(f.truth.lon), g.toY(f.truth.lat), f.truth.hdg, st?.name, st?.wayId))
            }
            Step(f, st)
        }
    }

    // ---------------- 幾何 ----------------

    private val kLat = MapGraph.R_EARTH * Math.PI / 180.0

    /** 以 (lat0, lon0) 為原點的平面座標（x 東、y 北）。 */
    private fun xy(lat: Double, lon: Double, lat0: Double, lon0: Double) =
        doubleArrayOf((lon - lon0) * kLat * cos(Math.toRadians(lat0)), (lat - lat0) * kLat)

    /** 車輛座標 → 以車為原點的平面座標。 */
    private fun toWorld(x: Double, z: Double, hdgDeg: Double): DoubleArray {
        val h = Math.toRadians(hdgDeg)
        return doubleArrayOf(x * cos(h) + z * sin(h), -x * sin(h) + z * cos(h))
    }

    /** 以車為原點的平面座標 → 車輛座標 (x, z)。 */
    private fun toVehicle(dx: Double, dy: Double, hdgDeg: Double): DoubleArray {
        val h = Math.toRadians(hdgDeg)
        return doubleArrayOf(dx * cos(h) - dy * sin(h), dx * sin(h) + dy * cos(h))
    }

    private fun segDistD(p: DoubleArray, a: DoubleArray, b: DoubleArray) = segDist(p[0], p[1], a[0], a[1], b[0], b[1])

    private fun angle(a: Double, b: Double): Double = angDiff(a, b)

    /** 軌跡真值位置在車輛座標下離配對中心線（path）多遠。 */
    private fun truthToPath(st: MapState, f: Fix): Double {
        val d = xy(f.truth.lat, f.truth.lon, st.lat, st.lon)
        val v = toVehicle(d[0], d[1], st.headingDeg.toDouble())
        var best = Double.MAX_VALUE
        for (i in 1 until st.path.size) {
            val a = st.path[i - 1]; val b = st.path[i]
            best = min(best, segDist(v[0], v[1], a[0].toDouble(), a[1].toDouble(), b[0].toDouble(), b[1].toDouble()))
        }
        return best
    }

    /** 配對正確：真值位置在配對中心線 offset+3.5 m 內，且航向差 < 30°。 */
    private fun correct(st: MapState?, f: Fix, laneOff: Double): Boolean {
        if (st == null || !st.matched || st.path.size < 2) return false
        return truthToPath(st, f) <= laneOff + 3.5 && angle(st.headingDeg.toDouble(), f.truth.hdg) < 30
    }

    // ---------------- 解析 ----------------

    @Test
    fun parsesRealOverpassResponses() {
        for (n in listOf("taipei_zhongxiao", "yangde", "tainan_west")) {
            val g = MapGraph.parse(res("$n.json"), 25.0, 121.5)
            assertTrue("$n ways", g.wayCount > 150)
            assertTrue("$n segs", g.segmentCount > g.wayCount)
            assertTrue("$n signals", g.signalCount >= 10)
        }
        val g = MapGraph.parse(res("taipei_zhongxiao.json"), 25.0415, 121.55)
        val zx = g.ways.first { it.id == 255431333L }
        assertEquals("忠孝東路四段", zx.name)
        assertEquals(1, zx.oneway)
        assertEquals(4, zx.lanes)
        assertEquals("primary", zx.highway)
        val dh = g.ways.first { it.id == 506322869L }   // 敦化南路一段 雙向主線
        assertEquals(0, dh.oneway)
        val fx = g.ways.first { it.id == 49073266L }    // 復興南路一段 maxspeed=50
        assertEquals(50, fx.maxspeed)
        // 號誌方向 tag
        val gt = MapGraph.parse(res("taipei_zhongxiao.json"), 25.0415, 121.55)
        val idx = gt.nodeIndex(9608431962L)
        assertTrue(idx >= 0)
        assertEquals(2, gt.nSig[idx].toInt())
    }

    @Test
    fun tagParsing() {
        assertEquals(3, OsmWay.parseInt("3"))
        assertEquals(3, OsmWay.parseInt("3;2"))
        assertNull(OsmWay.parseInt("many"))
        assertEquals(50, OsmWay.parseSpeed("50"))
        assertEquals(50, OsmWay.parseSpeed("50|40"))
        assertEquals(60, OsmWay.parseSpeed("60 km/h"))
        assertEquals(48, OsmWay.parseSpeed("30 mph"))
        assertEquals(50, OsmWay.parseSpeed("TW:urban"))
        assertNull(OsmWay.parseSpeed("none"))
        assertEquals(7.5f, OsmWay.parseWidth("7.5 m")!!, 1e-6f)
        assertEquals(7.5f, OsmWay.parseWidth("7,5")!!, 1e-6f)
        assertNull(OsmWay.parseWidth("12'"))
    }

    @Test
    fun filtersNonDrivableAndImpliedOneway() {
        val j = osm(
            listOf(1L to doubleArrayOf(25.0, 121.5), 2L to doubleArrayOf(25.0, 121.501), 3L to doubleArrayOf(25.0, 121.502)),
            listOf(
                way(10, listOf(1, 2), "highway" to "footway"),
                way(11, listOf(1, 2), "highway" to "residential", "access" to "no"),
                way(12, listOf(2, 3), "highway" to "motorway"),
                way(13, listOf(1, 2), "highway" to "tertiary", "junction" to "roundabout"),
                way(14, listOf(1, 3), "highway" to "service", "area" to "yes")
            )
        )
        val g = MapGraph.parse(j, 25.0, 121.5)
        assertEquals(listOf(12L, 13L), g.ways.map { it.id })
        assertTrue(g.ways.all { it.oneway == 1 })
    }

    // ---------------- 配對 ----------------

    @Test
    fun noDataBeforeLoadAndFarAway() {
        val a = MapAssist()
        assertNull(a.update(25.04, 121.55, 10.0, 90.0, 5.0, 0L))
        a.loadOverpass(res("taipei_zhongxiao.json"), 25.0415, 121.55)
        val st = a.update(25.20, 121.70, 10.0, 90.0, 5.0, 1000L)   // 20 km 外
        assertNotNull(st)
        assertFalse(st!!.matched)
        assertTrue(st.path.isEmpty())
    }

    @Test
    fun taipeiDualCarriagewayMatching() {
        val tr = trace("taipei_zhongxiao")
        val steps = run(tr)
        val turnS = tr.parts[0].turns[0]
        var ok = 0; var n = 0; var wrongDir = 0; var sameWay = 0
        for ((i, s) in steps.withIndex()) {
            if (i < 3) continue
            n++
            if (correct(s.st, s.fix, 1.75)) ok++
            if (s.st?.wayId == s.fix.truth.way) sameWay++
            // 對向車道（西行）方向相反：一次都不可以配到
            if (s.st != null && s.st.matched && angle(s.st.headingDeg.toDouble(), s.fix.truth.hdg) > 90) wrongDir++
        }
        println("taipei: correct $ok/$n, same way $sameWay/$n, wrongDir $wrongDir")
        assertTrue("correct ratio $ok/$n", ok >= 0.95 * n)
        assertTrue("same way $sameWay/$n", sameWay >= 0.85 * n)
        assertEquals(0, wrongDir)

        // 路口右轉光復南路：轉過後 3 筆內配到新路
        val after = steps.filter { it.fix.truth.s > turnS + 15 }
        assertTrue(after.size > 5)
        for (s in after.drop(3)) assertEquals("光復南路", s.st!!.name)

        // 屬性：忠孝東路四段、單行、4–5 車道
        val on = steps.drop(3).filter { it.fix.truth.s < turnS - 25 }
        val attrOk = on.count { it.st!!.name == "忠孝東路四段" && it.st.oneway && (it.st.lanes ?: 0) in 4..5 }
        assertTrue("attrs $attrOk/${on.size}", attrOk >= 0.95 * on.size)
    }

    @Test
    fun taipeiPathShapeThroughIntersections() {
        val tr = trace("taipei_zhongxiao")
        val steps = run(tr)
        val part = tr.parts[0]
        val turnS = part.turns[0]
        var checked = 0
        for (s in steps.drop(3)) {
            val st = s.st ?: continue
            if (!correct(st, s.fix, 1.75)) continue
            // 取樣：每 5 m 一點，車後 30 m 起
            for (i in 1 until st.path.size) {
                val d = hypot((st.path[i][0] - st.path[i - 1][0]).toDouble(), (st.path[i][1] - st.path[i - 1][1]).toDouble())
                assertTrue("step $d", d in 3.5..5.01)
            }
            if (s.fix.truth.s > 60) {
                assertEquals(-30.0, st.path[0][1].toDouble(), 1.5)
                // s = 0 的點（第 6 點）在 x = -offset
                assertEquals(-st.offsetM.toDouble(), st.path[6][0].toDouble(), 0.05)
                assertEquals(0.0, st.path[6][1].toDouble(), 0.05)
            }
            // 忠孝東路直行穿過多個路口 → 路徑一路延伸到 ~200 m
            if (s.fix.truth.s < turnS - 10) assertTrue("ahead ${st.path.last()[1]}", st.path.last()[1] > 190f)
            // 前方路徑與真實路線重合（到右轉點前）
            val limit = if (s.fix.truth.s < turnS) turnS - s.fix.truth.s - 20 else part.length - s.fix.truth.s - 20
            val dmax = maxDistToRoute(st, part, 0.0, min(180.0, limit))
            assertTrue("path off route by $dmax at s=${s.fix.truth.s}", dmax < 1.5)
            checked++
        }
        assertTrue(checked > 120)
    }

    /** path 上 z ∈ [z0, z1] 的點（換回平面座標）離真實路線中心線的最大距離。 */
    private fun maxDistToRoute(st: MapState, part: Part, z0: Double, z1: Double): Double {
        val lat0 = part.poly[0][0]; val lon0 = part.poly[0][1]
        val car = xy(st.lat, st.lon, lat0, lon0)
        val route = part.poly.map { xy(it[0], it[1], lat0, lon0) }
        var dmax = 0.0
        for (p in st.path) {
            if (p[1] < z0 || p[1] > z1) continue
            val w = toWorld(p[0].toDouble(), p[1].toDouble(), st.headingDeg.toDouble())
            val q = doubleArrayOf(car[0] + w[0], car[1] + w[1])
            var d = Double.MAX_VALUE
            for (i in 1 until route.size) d = min(d, segDistD(q, route[i - 1], route[i]))
            dmax = max(dmax, d)
        }
        return dmax
    }

    @Test
    fun taipeiSignalsAhead() {
        val tr = trace("taipei_zhongxiao")
        val steps = run(tr)
        val part = tr.parts[0]
        val turnS = part.turns[0]
        var good = 0; var n = 0
        for (s in steps.drop(3)) {
            val st = s.st ?: continue
            val s0 = s.fix.truth.s
            if (s0 > turnS - 5 || !correct(st, s.fix, 1.75)) continue
            n++
            val horizon = min(250.0, turnS - s0 + 1)
            val truthAhead = part.signals.map { it - s0 }.filter { it in 15.0..horizon - 15 }
            val rep = st.signalsAheadM.map { it.toDouble() }
            val missed = truthAhead.count { t -> rep.none { abs(it - t) < 15 } }
            val ghost = rep.filter { it in 15.0..horizon - 15 }.count { r -> part.signals.none { abs(it - s0 - r) < 15 } }
            if (missed == 0 && ghost == 0) good++
        }
        println("taipei signals: $good/$n")
        assertTrue("signals $good/$n", good >= 0.9 * n)
        // 停在延吉街口：前方號誌 < 25 m
        val stopped = steps.filter { it.fix.truth.v == 0.0 && it.fix.truth.s > 1000 }
        assertTrue(stopped.isNotEmpty())
        for (s in stopped) {
            val first = s.st!!.signalsAheadM.firstOrNull()
            assertNotNull(first)
            assertTrue("stop signal $first", first!! < 30f)
        }
    }

    @Test
    fun taipeiStopKeepsMatch() {
        val tr = trace("taipei_zhongxiao")
        val steps = run(tr)
        val stopped = steps.filter { it.fix.truth.v == 0.0 && it.fix.truth.s > 1000 }
        assertTrue(stopped.size >= 10)
        val ways = stopped.map { it.st!!.wayId }.toSet()
        assertEquals("停車時不跳路：$ways", 1, ways.size)
        for (s in stopped) assertTrue(angle(s.st!!.headingDeg.toDouble(), s.fix.truth.hdg) < 10)
        // 停車時路徑不亂跳：前後兩筆 200 m 處側向差 < 2 m
        for (i in 1 until stopped.size) {
            val a = stopped[i - 1].st!!.path.last(); val b = stopped[i].st!!.path.last()
            assertTrue(abs(a[0] - b[0]) < 2f)
        }
    }

    @Test
    fun yangdeCurvesAndUturn() {
        val tr = trace("yangde")
        val steps = run(tr)
        var ok = 0; var n = 0
        for ((i, s) in steps.withIndex()) {
            if (i < 3 || s.fix.truth.uturn) continue
            // 迴轉後重新起步的前幾秒航向還在變：看 v > 3 之後
            if (s.fix.truth.part == 1 && s.fix.truth.s < 25) continue
            n++
            if (correct(s.st, s.fix, 1.75)) ok++
        }
        println("yangde: correct $ok/$n")
        assertTrue("correct $ok/$n", ok >= 0.95 * n)

        // 迴轉完成後要換成下山方向（雙向道的反向邊）
        val down = steps.filter { it.fix.truth.part == 1 && it.fix.truth.s > 30 }
        assertTrue(down.size > 40)
        for (s in down) assertTrue("down hdg ${s.st!!.headingDeg} vs ${s.fix.truth.hdg}",
            angle(s.st.headingDeg.toDouble(), s.fix.truth.hdg) < 30)

        // 雙向 2 車道、仰德大道
        val up = steps.drop(3).filter { it.fix.truth.part == 0 && it.fix.truth.s > 60 && it.fix.truth.v > 3 }
        val attr = up.count { it.st?.name?.startsWith("仰德大道") == true && !it.st.oneway && it.st.lanes == 2 }
        assertTrue("attrs $attr/${up.size}", attr >= 0.95 * up.size)
        // 雙向道：車在中心線右側
        assertTrue(up.filter { it.st?.matched == true }.all { it.st!!.offsetM > 0.4f })

        // 彎道：路徑形狀貼著真實路線
        for (s in steps.drop(3)) {
            val st = s.st ?: continue
            if (!correct(st, s.fix, 1.75) || s.fix.truth.uturn) continue
            val part = tr.parts[s.fix.truth.part]
            val limit = min(180.0, part.length - s.fix.truth.s - 20)
            if (limit < 20) continue
            val dmax = maxDistToRoute(st, part, if (s.fix.truth.s > 45) -25.0 else 0.0, limit)
            assertTrue("yangde path off by $dmax at part ${s.fix.truth.part} s=${s.fix.truth.s}", dmax < 1.5)
        }
    }

    @Test
    fun yangdePathCurvesAhead() {
        // 上山路段：前方路徑真的會彎（不是直線外插）
        val tr = trace("yangde")
        val steps = run(tr)
        val bend = steps.mapNotNull { it.st }.filter { it.matched && it.path.size > 30 }
            .maxOf { st -> st.path.maxOf { abs(it[0]) } }
        assertTrue("max lateral $bend", bend > 40f)
    }

    @Test
    fun tainanSmallStreetsLowSpeedAndTurns() {
        val tr = trace("tainan_west")
        val steps = run(tr)
        var ok = 0; var n = 0
        for ((i, s) in steps.withIndex()) {
            if (i < 3) continue
            n++
            if (correct(s.st, s.fix, 0.8)) ok++
            if (System.getenv("MAP_DEBUG") != null) {
                val st = s.st
                println(String.format("t=%3d s=%6.1f v=%4.1f brg=%5.0f | %s way=%d truth=%d dPath=%.1f dH=%.0f off=%.1f",
                    s.fix.t / 1000, s.fix.truth.s, s.fix.truth.v, s.fix.brg, st?.name, st?.wayId, s.fix.truth.way,
                    if (st != null && st.matched) truthToPath(st, s.fix) else -1.0,
                    if (st != null) angle(st.headingDeg.toDouble(), s.fix.truth.hdg) else -1.0, st?.offsetM))
            }
        }
        println("tainan: correct $ok/$n")
        assertTrue("correct $ok/$n", ok >= 0.9 * n)

        // 慢行（< 2 m/s，GPS 航向亂跳）時不能掉頭配到反向
        val slow = steps.drop(3).filter { it.fix.truth.v in 0.5..2.0 }
        assertTrue(slow.size > 30)
        val slowOk = slow.count { it.st != null && it.st.matched && angle(it.st.headingDeg.toDouble(), it.fix.truth.hdg) < 35 }
        assertTrue("slow $slowOk/${slow.size}", slowOk >= 0.95 * slow.size)

        // 兩次路口轉彎：中成路 → 慈音街 → 公園路
        val turns = tr.parts[0].turns
        val mid = steps.filter { it.fix.truth.s > turns[0] + 20 && it.fix.truth.s < turns[1] - 10 }
        assertTrue(mid.count { it.st?.name == "慈音街" } >= 0.9 * mid.size)
        val last = steps.filter { it.fix.truth.s > turns[1] + 20 }.drop(2)
        assertTrue(last.isNotEmpty())
        assertTrue(last.count { it.st?.name == "公園路" } >= 0.9 * last.size)
    }

    @Test
    fun reloadMidTraceKeepsContinuity() {
        val tr = trace("taipei_zhongxiao")
        val a = run(tr)
        val b = run(tr, MapAssist(), reloadAt = 60)
        var same = 0
        for (i in 60 until a.size) if (a[i].st?.wayId == b[i].st?.wayId) same++
        assertTrue("same $same/${a.size - 60}", same >= 0.95 * (a.size - 60))
        for (i in 61 until b.size) assertTrue(correct(b[i].st, b[i].fix, 1.75) || !correct(a[i].st, a[i].fix, 1.75))
    }

    @Test
    fun nearbyRoadsForMiniMap() {
        val tr = trace("taipei_zhongxiao")
        val steps = run(tr)
        val st = steps[80].st!!
        assertTrue("roads ${st.roads.size}", st.roads.size > 30)
        assertEquals(st.roads.size, st.roadClass.size)
        var pts = 0
        for (r in st.roads) {
            pts += r.size
            assertTrue(r.size >= 2)
            // 折線至少有一段在 300 m 內（端點可能在外）
            var dmin = Double.MAX_VALUE
            for (i in 1 until r.size) dmin = min(dmin, segDist(0.0, 0.0, r[i - 1][0].toDouble(), r[i - 1][1].toDouble(), r[i][0].toDouble(), r[i][1].toDouble()))
            assertTrue("road dmin $dmin", dmin <= 301.0)
        }
        assertTrue("pts $pts", pts <= 2500)
        // 大路排在後面（畫在最上層）
        assertEquals(0, st.roadClass.last())
        // 配對的道路本身在 roads 裡：有一段離車子 < 5 m
        assertTrue(st.roads.any { r -> (1 until r.size).any { i -> segDist(0.0, 0.0, r[i - 1][0].toDouble(), r[i - 1][1].toDouble(), r[i][0].toDouble(), r[i][1].toDouble()) < 5.0 } })
    }

    // ---------------- 輸出 ----------------

    @Test
    fun toJsonPayload() {
        val tr = trace("taipei_zhongxiao")
        val st = run(tr)[50].st!!
        val j = MapAssist.toJson(st)
        for (k in listOf("path", "lanes", "width", "oneway", "maxspeed", "name", "signals", "roads", "acc", "spd", "matched"))
            assertTrue("missing $k", j.has(k))
        assertEquals(st.path.size, j.getJSONArray("path").length())
        val p0 = j.getJSONArray("path").getJSONArray(0)
        assertEquals(2, p0.length())
        assertEquals(st.path[0][0].toDouble(), p0.getDouble(0), 0.051)
        assertTrue(j.isNull("width"))
        assertEquals(st.lanes, j.getInt("lanes"))
        assertEquals("忠孝東路四段", j.getString("name"))
        assertTrue(j.getBoolean("oneway"))
        assertTrue(j.getBoolean("matched"))
        assertEquals(st.speedKmh.toDouble(), j.getDouble("spd"), 0.051)
        assertTrue(j.getJSONArray("roads").getJSONArray(0).getJSONArray(0).get(0) is Int)
        // 字串化再解析（WebView 收到的就是這個）
        val back = JSONObject(j.toString())
        assertEquals(j.getJSONArray("signals").length(), back.getJSONArray("signals").length())
        // 沒配到時的 null 欄位
        val un = MapAssist.toJson(MapState(emptyList(), null, null, false, null, null, emptyList(), emptyList(), false, 0f))
        assertTrue(un.isNull("lanes") && un.isNull("maxspeed") && un.isNull("name"))
        assertFalse(un.getBoolean("matched"))
    }

    // ---------------- 合成小圖：單行、T 字路口 ----------------

    private fun osm(nodes: List<Pair<Long, DoubleArray>>, ways: List<JSONObject>, signals: Map<Long, String> = emptyMap()): String {
        val els = JSONArray()
        for (w in ways) els.put(w)
        for ((id, ll) in nodes) {
            val n = JSONObject().put("type", "node").put("id", id).put("lat", ll[0]).put("lon", ll[1])
            signals[id]?.let { n.put("tags", JSONObject().put("highway", "traffic_signals").apply { if (it.isNotEmpty()) put("traffic_signals:direction", it) }) }
            els.put(n)
        }
        return JSONObject().put("elements", els).toString()
    }

    private fun way(id: Long, nodes: List<Long>, vararg tags: Pair<String, String>): JSONObject {
        val tg = JSONObject()
        for ((k, v) in tags) tg.put(k, v)
        return JSONObject().put("type", "way").put("id", id).put("nodes", JSONArray(nodes)).put("tags", tg)
    }

    private val lat0 = 25.0
    private val lon0 = 121.5
    private val dLat = 1.0 / 111_195.0
    private val dLon = 1.0 / (111_195.0 * cos(Math.toRadians(25.0)))
    private fun ll(xm: Double, ym: Double) = doubleArrayOf(lat0 + ym * dLat, lon0 + xm * dLon)

    /** 東西向直路 x = -300..300（id 1..7，每 100 m 一點），在 x = 100 有十字或 T 字路口。 */
    private fun grid(onewayRev: Boolean, tJunction: Boolean, sig: String? = null): String {
        val nodes = ArrayList<Pair<Long, DoubleArray>>()
        for (k in 0..6) nodes.add((k + 1).toLong() to ll(-300.0 + 100 * k, 0.0))
        nodes.add(20L to ll(100.0, 100.0)); nodes.add(21L to ll(100.0, -100.0))
        val main = if (tJunction) listOf(1L, 2, 3, 4, 5) else listOf(1L, 2, 3, 4, 5, 6, 7)
        val ws = arrayListOf(
            if (onewayRev) way(100, main, "highway" to "secondary", "name" to "主路", "oneway" to "-1", "maxspeed:backward" to "40", "maxspeed" to "60")
            else way(100, main, "highway" to "secondary", "name" to "主路", "lanes" to "4", "maxspeed" to "60"),
            way(101, listOf(20L, 5, 21), "highway" to "residential", "name" to "橫街")
        )
        return osm(nodes, ws, if (sig != null) mapOf(5L to sig) else emptyMap())
    }

    @Test
    fun onewayReverseFollowsAllowedDirection() {
        val a = MapAssist()
        a.loadOverpass(grid(onewayRev = true, tJunction = false), lat0, lon0)
        var st: MapState? = null
        // 往西開（x 由 200 → 0），way 節點順序是往東、oneway=-1 → 只准往西
        for (k in 0..10) {
            val p = ll(200.0 - 10 * k, 1.0)
            st = a.update(p[0], p[1], 10.0, 270.0, 4.0, k * 1000L)
        }
        st!!
        assertTrue(st.matched)
        assertTrue(st.oneway)
        assertEquals(270.0, st.headingDeg.toDouble(), 1.0)
        assertEquals(40, st.maxspeedKmh)      // maxspeed:backward 優先
        assertTrue(st.path.last()[1] > 190f)  // 往西還有 300 m
        // 單行道：橫向偏移可以在中心線兩側（不強制靠右）
        assertTrue(abs(st.offsetM) < 3f)
    }

    @Test
    fun straightestContinuationAndTJunctionStop() {
        // 十字路口：直行穿過
        val a = MapAssist()
        a.loadOverpass(grid(onewayRev = false, tJunction = false), lat0, lon0)
        var st: MapState? = null
        for (k in 0..8) {
            val p = ll(-200.0 + 10 * k, -1.5)
            st = a.update(p[0], p[1], 10.0, 90.0, 4.0, k * 1000L)
        }
        val s1 = st!!
        assertEquals("主路", s1.name)
        assertEquals(4, s1.lanes)
        assertEquals(60, s1.maxspeedKmh)
        assertTrue(s1.path.last()[1] > 190f)
        assertTrue(s1.path.all { abs(it[0] + s1.offsetM) < 0.2f })   // 直路：x 固定
        // 雙向道：車在右側
        assertTrue(s1.offsetM > 0.4f)

        // T 字路口：主路在 x = 100 結束，左右都轉 90° → 路徑停在路口
        val b = MapAssist()
        b.loadOverpass(grid(onewayRev = false, tJunction = true), lat0, lon0)
        for (k in 0..16) {
            val p = ll(-200.0 + 10 * k, -1.5)
            st = b.update(p[0], p[1], 10.0, 90.0, 4.0, k * 1000L)
        }
        val end = st!!.path.last()[1]
        assertEquals(140.0, end.toDouble(), 6.0)    // 車在 x ≈ -40，路口在 x = 100
    }

    @Test
    fun signalDirectionTag() {
        for ((tag, expect) in listOf("" to true, "forward" to true, "backward" to false)) {
            val a = MapAssist()
            a.loadOverpass(grid(onewayRev = false, tJunction = false, sig = tag), lat0, lon0)
            var st: MapState? = null
            for (k in 0..5) {
                val p = ll(-100.0 + 10 * k, -1.5)
                st = a.update(p[0], p[1], 10.0, 90.0, 4.0, k * 1000L)
            }
            val sig = st!!.signalsAheadM
            if (expect) {
                assertEquals("tag '$tag'", 1, sig.size)
                assertEquals(150.0, sig[0].toDouble(), 3.0)
            } else assertTrue("tag '$tag' $sig", sig.isEmpty())
        }
    }

    @Test
    fun lowSpeedBearingNoiseHoldsDirection() {
        val a = MapAssist()
        a.loadOverpass(grid(onewayRev = false, tJunction = false), lat0, lon0)
        val rnd = java.util.Random(5)
        var t = 0L
        for (k in 0..6) {
            val p = ll(-200.0 + 12 * k, -1.5)
            a.update(p[0], p[1], 12.0, 90.0, 4.0, t); t += 1000
        }
        // 停下來 30 s：位置抖 6 m、航向亂給（含反方向）
        for (k in 0 until 30) {
            val p = ll(-128.0 + rnd.nextGaussian() * 6, -1.5 + rnd.nextGaussian() * 6)
            val st = a.update(p[0], p[1], abs(rnd.nextGaussian() * 0.3), rnd.nextDouble() * 360, 8.0, t)!!
            t += 1000
            assertTrue(st.matched)
            assertEquals(90.0, st.headingDeg.toDouble(), 1.0)
        }
    }

    // ---------------- 圖磚 ----------------

    @Test
    fun tilesSplitAndMergeMatchOriginal() {
        val raw = res("taipei_zhongxiao.json")
        val tiles = OsmTiles.around(25.0415, 121.5505, 500.0)
        assertTrue(tiles.size in 4..16)
        val parts = OsmTiles.split(raw, tiles)
        assertEquals(tiles.size, parts.size)
        val merged = MapGraph.parse(parts.values.toList(), 25.0415, 121.5505)
        val orig = MapGraph.parse(raw, 25.0415, 121.5505)
        // 範圍內的 way 都在、號誌方向 tag 保留
        val inBox = OsmTiles.bbox(tiles)
        val origIds = orig.ways.filter { w ->
            w.nodes.any { n ->
                val la = orig.toLat(orig.ny[n]); val lo = orig.toLon(orig.nx[n])
                la >= inBox[0] && la < inBox[2] && lo >= inBox[1] && lo < inBox[3]
            }
        }.map { it.id }.toSet()
        val mergedIds = merged.ways.map { it.id }.toSet()
        assertTrue((origIds - mergedIds).isEmpty())
        val si = merged.nodeIndex(9608431962L)
        if (si >= 0) assertEquals(2, merged.nSig[si].toInt())
        // 合併後配對結果一樣
        val tr = trace("taipei_zhongxiao")
        val a = MapAssist(); a.load(orig)
        val b = MapAssist(); b.load(merged)
        var same = 0; var n = 0
        for (f in tr.fixes) {
            val sa = a.update(f.lat, f.lon, f.spd, f.brg, f.acc, f.t)
            val sb = b.update(f.lat, f.lon, f.spd, f.brg, f.acc, f.t)
            val ll0 = doubleArrayOf(f.lat, f.lon)
            if (ll0[0] < inBox[0] + 0.002 || ll0[0] > inBox[2] - 0.002 || ll0[1] < inBox[1] + 0.002 || ll0[1] > inBox[3] - 0.002) continue
            n++
            if (sa?.wayId == sb?.wayId) same++
        }
        assertTrue(n > 20)
        assertTrue("same $same/$n", same >= n - 2)
    }

    @Test
    fun tileMath() {
        assertEquals("5008_24310", OsmTiles.key(OsmTiles.ix(121.5505), OsmTiles.iy(25.0415)))
        val b = OsmTiles.bounds(24310, 5008)
        assertEquals(25.04, b[0], 1e-9); assertEquals(121.55, b[1], 1e-9)
        // 停車：車子周圍 ±700 m；高速往東：前導點在東邊 300 m
        val still = OsmTiles.needed(25.0415, 121.5505, Double.NaN, 0.0)
        val east = OsmTiles.needed(25.0415, 121.5505, 90.0, 25.0)
        assertTrue(east.maxOf { it[0] } > still.maxOf { it[0] } || east.size > still.size)
        assertTrue(still.size in 9..16)
        val q = OsmTiles.query(25.04, 121.55, 25.045, 121.555)
        assertTrue(q.startsWith("[out:json][timeout:25];"))
        assertTrue(q.contains("traffic_signals"))
        assertTrue(q.contains("residential") && !q.contains("footway"))
    }

    // ---------------- 示範資料（map-demo.html 用） ----------------

    /** 設環境變數 MAP_DEMO_OUT=目錄 時，輸出 3D 面板示範用的 payload 序列。 */
    @Test
    fun dumpDemoPayloads() {
        val out = System.getenv("MAP_DEMO_OUT") ?: return
        for ((name, stride) in listOf("taipei_zhongxiao" to 1, "yangde" to 1)) {
            val tr = trace(name)
            val arr = JSONArray()
            for (s in run(tr)) {
                val st = s.st ?: continue
                if ((s.fix.t / 1000) % stride != 0L) continue
                arr.put(MapAssist.toJson(st))
            }
            File(out, "map-demo-$name.json").writeText(arr.toString())
        }
    }
}
