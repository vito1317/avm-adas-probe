package com.vito.avmprobe

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * AvmGeometry 與 tools/avm_geometry.py 的一致性（geometry_fixtures.json 由 `avm_geometry.py fixtures` 產生），
 * 加上來回投影與方向檢查。
 */
class AvmGeometryTest {

    companion object {
        private const val QW = 960
        private const val QH = 540
        private const val TOL = 1e-6
        private const val NEAR_EDGE = 1e-6

        private val fixtures: JSONObject by lazy {
            val s = AvmGeometryTest::class.java.getResourceAsStream("/geometry_fixtures.json")
                ?: error("找不到 geometry_fixtures.json")
            JSONObject(s.bufferedReader(Charsets.UTF_8).use { it.readText() })
        }

        private fun set(name: String): JSONObject {
            val sets = fixtures.getJSONArray("sets")
            for (k in 0 until sets.length()) if (sets.getJSONObject(k).getString("name") == name) return sets.getJSONObject(k)
            error("fixtures 沒有 $name")
        }

        private fun geoOf(name: String): AvmGeometry = AvmGeometry.fromJson(set(name).getJSONObject("calib").toString())

        /** app 模組的 assets/avm_calib.json（Gradle 單元測試的工作目錄是 app/）。 */
        private fun assetGeo(): AvmGeometry {
            val f = listOf("src/main/assets/avm_calib.json", "app/src/main/assets/avm_calib.json")
                .map { File(it) }.firstOrNull { it.isFile } ?: error("找不到 avm_calib.json")
            return AvmGeometry.fromJson(f.readText(Charsets.UTF_8))
        }

        /** 大數值（射線接近水平、打到幾百公尺外）用相對誤差，否則用絕對誤差。 */
        private fun close(a: Double, ref: Double): Boolean = abs(a - ref) <= TOL * max(1.0, abs(ref))
    }

    private fun JSONArray.d(i: Int) = getDouble(i)

    @Test
    fun assetCalibParses() {
        // 校正值之後會改，這裡只驗結構與 fromJson 每個欄位都讀對
        val g = assetGeo()
        assertEquals(listOf("front", "rear", "left", "right"), g.cams.map { it.name })
        assertEquals(listOf(0, 1, 2, 3), g.cams.map { it.quad })
        val f = listOf("src/main/assets/avm_calib.json", "app/src/main/assets/avm_calib.json")
            .map { File(it) }.first { it.isFile }
        val raw = JSONObject(f.readText(Charsets.UTF_8))
        assertEquals(raw.getJSONObject("car").getDouble("length"), g.carLength, 0.0)
        assertEquals(raw.getJSONObject("car").getDouble("width"), g.carWidth, 0.0)
        for (c in g.cams) {
            val o = raw.getJSONObject("cams").getJSONObject(c.name)
            val pos = o.getJSONArray("pos")
            assertEquals(o.getBoolean("mirror"), c.mirror)
            val pairs = listOf(
                "cx" to c.cx, "cy" to c.cy, "fx" to c.fx, "fy" to c.fy, "k1" to c.k1, "k2" to c.k2,
                "maxDeg" to c.maxDeg, "yaw" to c.yaw, "pitch" to c.pitch, "roll" to c.roll
            )
            for ((key, value) in pairs) assertEquals("${c.name}.$key", o.getDouble(key), value, 0.0)
            assertEquals(pos.getDouble(0), c.px, 0.0)
            assertEquals(pos.getDouble(1), c.py, 0.0)
            assertEquals(pos.getDouble(2), c.pz, 0.0)
        }
        val b = raw.getJSONObject("bev")
        assertEquals(BevSpec(b.getDouble("xMin"), b.getDouble("xMax"), b.getDouble("zMin"), b.getDouble("zMax"), b.getDouble("mpp")), g.bev)
        assertEquals("right", g.camForQuad(3)!!.name)
        assertNull(g.camForQuad(4))
        assertNull(g.cam("roof"))
    }

    @Test
    fun specDefaults() {
        // fixtures 的 default 組 = spec 2.1 的預設檔
        val g = geoOf("default")
        assertEquals(4.84, g.carLength, 0.0)
        assertEquals(1.85, g.carWidth, 0.0)
        assertEquals(300, g.bev.width)
        assertEquals(350, g.bev.height)
        assertEquals(150.0, g.bev.originX, 1e-9)
        assertEquals(175.0, g.bev.originY, 1e-9)
        val f = g.cam("front")!!
        assertEquals(0.55, f.py, 0.0)
        assertEquals(25.0, f.pitch, 0.0)
        assertEquals(95.0, f.maxDeg, 0.0)
        assertEquals(180.0, g.cam("rear")!!.yaw, 0.0)
        assertEquals(-90.0, g.cam("left")!!.yaw, 0.0)
    }

    @Test
    fun bevPixelConvention() {
        val b = geoOf("default").bev
        for (i in listOf(0, 17, 149, 150, 299)) {
            val x = (i + 0.5 - b.originX) * b.mpp
            assertEquals(b.xMin + (i + 0.5) * b.mpp, x, 1e-12)
        }
        for (j in listOf(0, 33, 174, 175, 349)) {
            val z = (b.originY - (j + 0.5)) * b.mpp
            assertEquals(b.zMax - (j + 0.5) * b.mpp, z, 1e-12)
        }
        val bs = set("default").getJSONObject("bev")
        assertEquals(bs.getInt("width"), b.width)
        assertEquals(bs.getInt("height"), b.height)
        assertEquals(bs.getDouble("originX"), b.originX, 0.0)
        assertEquals(bs.getDouble("originY"), b.originY, 0.0)
    }

    @Test
    fun pixelToRayAndGroundMatchPython() {
        var n = 0; var nullBoth = 0; var skippedEdge = 0
        var maxRay = 0.0; var maxAbs = 0.0; var maxRel = 0.0
        for (setName in listOf("default", "variant")) {
            val geo = geoOf(setName)
            val cams = set(setName).getJSONArray("cams")
            for (k in 0 until cams.length()) {
                val cj = cams.getJSONObject(k)
                val c = geo.cam(cj.getString("name"))!!
                val rows = cj.getJSONArray("pix")
                for (r in 0 until rows.length()) {
                    val row = rows.getJSONArray(r)
                    val u = row.d(0); val v = row.d(1)
                    val ray = geo.pixelToRay(c, u, v, QW, QH)
                    assertEquals(1.0, sqrt(ray[0] * ray[0] + ray[1] * ray[1] + ray[2] * ray[2]), 1e-12)
                    for (a in 0..2) {
                        val d = abs(ray[a] - row.d(2 + a))
                        maxRay = max(maxRay, d)
                        assertTrue("$setName/${c.name} ray ($u,$v)[$a] 差 $d", d <= TOL)
                    }
                    val g = geo.pixelToGround(c, u, v, QW, QH)
                    val refNull = row.isNull(5)
                    if ((g == null) != refNull) {
                        assertTrue("$setName/${c.name} ($u,$v) null 不一致", row.d(7) < NEAR_EDGE)
                        skippedEdge++
                        continue
                    }
                    n++
                    if (g == null) { nullBoth++; continue }
                    for (a in 0..1) {
                        val ref = row.d(5 + a)
                        assertTrue("$setName/${c.name} ($u,$v) ground[$a] ${g[a]} vs $ref", close(g[a], ref))
                        maxAbs = max(maxAbs, abs(g[a] - ref))
                        maxRel = max(maxRel, abs(g[a] - ref) / max(1.0, abs(ref)))
                    }
                }
            }
        }
        println("pixel→ground：比對 $n 點（雙方都 null $nullBoth），邊界略過 $skippedEdge；" +
            "ray 最大差 $maxRay，ground 最大絕對差 $maxAbs m、最大相對差 $maxRel")
        assertTrue(n > 2000)
    }

    @Test
    fun groundToPixelMatchesPython() {
        var n = 0; var nullBoth = 0; var skippedEdge = 0
        var maxPix = 0.0; var maxTh = 0.0
        val tmp = DoubleArray(3)
        for (setName in listOf("default", "variant")) {
            val geo = geoOf(setName)
            val cams = set(setName).getJSONArray("cams")
            for (k in 0 until cams.length()) {
                val cj = cams.getJSONObject(k)
                val c = geo.cam(cj.getString("name"))!!
                val rows = cj.getJSONArray("gnd")
                for (r in 0 until rows.length()) {
                    val row = rows.getJSONArray(r)
                    val gx = row.d(0); val gz = row.d(1)
                    geo.project(c, gx, gz, QW, QH, tmp)
                    val dTh = abs(tmp[2] - row.d(4))
                    maxTh = max(maxTh, dTh)
                    assertTrue("θ 差 $dTh", dTh <= TOL)
                    val p = geo.groundToPixel(c, gx, gz, QW, QH)
                    if ((p == null) != row.isNull(2)) {
                        assertTrue("$setName/${c.name} ($gx,$gz) null 不一致", row.d(5) < NEAR_EDGE)
                        skippedEdge++
                        continue
                    }
                    n++
                    if (p == null) { nullBoth++; continue }
                    for (a in 0..1) {
                        val d = abs(p[a] - row.d(2 + a))
                        maxPix = max(maxPix, d)
                        assertTrue("$setName/${c.name} ($gx,$gz) pixel[$a] 差 $d", d <= TOL)
                    }
                }
            }
        }
        println("ground→pixel：比對 $n 點（雙方都 null $nullBoth），邊界略過 $skippedEdge；" +
            "pixel 最大差 $maxPix px、θ 最大差 $maxTh rad")
        assertTrue(n > 4000)
    }

    @Test
    fun bevLutMatchesPython() {
        val lj = fixtures.getJSONObject("lut")
        val geo = geoOf(lj.getString("set"))
        val fw = lj.getInt("frameW"); val fh = lj.getInt("frameH")
        val lut = geo.buildBevLut(fw, fh)
        assertEquals(lj.getInt("width") * lj.getInt("height"), lut.size)
        val valid = lut.count { it >= 0 }
        val refValid = lj.getInt("valid")
        val step = lj.getInt("step")
        val samples = ArrayList<Int>()
        val rows = lj.getJSONArray("samples")
        for (r in 0 until rows.length()) {
            val row = rows.getJSONArray(r)
            for (k in 0 until row.length()) samples += row.getInt(k)
        }
        assertEquals((lut.size + step - 1) / step, samples.size)
        var mismatch = 0; var unexplained = 0
        for ((k, ref) in samples.withIndex()) {
            val idx = k * step
            if (lut[idx] == ref) continue
            mismatch++
            if (!nearLutBoundary(geo, idx, fw, fh)) unexplained++
        }
        println("BEV LUT ${geo.bev.width}x${geo.bev.height}：有效 $valid（Python $refValid），" +
            "取樣 ${samples.size} 筆不一致 $mismatch（無法以邊界解釋 $unexplained）")
        assertEquals(0, unexplained)
        assertTrue(mismatch <= samples.size / 1000)
        assertTrue(abs(valid - refValid) <= lut.size / 1000)
    }

    /** LUT 差異只允許來自 floor() 剛好落在像素邊界、θ 剛好在 maxDeg、或兩顆鏡頭 θ 幾乎相同。 */
    private fun nearLutBoundary(geo: AvmGeometry, idx: Int, fw: Int, fh: Int): Boolean {
        val b = geo.bev
        val gx = b.xMin + (idx % b.width + 0.5) * b.mpp
        val gz = b.zMax - (idx / b.width + 0.5) * b.mpp
        val qw = fw / 2; val qh = fh / 2
        val t = DoubleArray(3)
        val ths = ArrayList<Double>()
        for (c in geo.cams) {
            val ok = geo.project(c, gx, gz, qw, qh, t)
            val u = t[0]; val v = t[1]
            if (abs(t[2] - c.maxRad) < 1e-9) return true
            if (abs(u - Math.rint(u)) < NEAR_EDGE || abs(v - Math.rint(v)) < NEAR_EDGE) return true
            if (ok) ths += t[2]
        }
        ths.sort()
        for (k in 1 until ths.size) if (ths[k] - ths[k - 1] < 1e-9) return true
        return false
    }

    @Test
    fun roundTripPixelGroundPixel() {
        val tmp = DoubleArray(3)
        var n = 0; var beyondFov = 0; var maxErr = 0.0
        for (setName in listOf("default", "variant")) {
            val geo = geoOf(setName)
            for (c in geo.cams) {
                var v = 0.5
                while (v < QH) {
                    var u = 0.5
                    while (u < QW) {
                        val g = geo.pixelToGround(c, u, v, QW, QH)
                        if (g != null) {
                            val p = geo.groundToPixel(c, g[0], g[1], QW, QH)
                            if (p == null) {
                                // 象限內的像素反投影回來只可能因為超出 maxDeg 而無效
                                geo.project(c, g[0], g[1], QW, QH, tmp)
                                assertTrue("$setName/${c.name} ($u,$v) 來回後無效但 θ=${tmp[2]}", tmp[2] > c.maxRad - 1e-9)
                                beyondFov++
                            } else {
                                val e = max(abs(p[0] - u), abs(p[1] - v))
                                maxErr = max(maxErr, e)
                                assertTrue("$setName/${c.name} ($u,$v) → (${p[0]},${p[1]}) 差 $e px", e <= TOL)
                                n++
                            }
                        }
                        u += 7.0
                    }
                    v += 7.0
                }
            }
        }
        println("pixel→ground→pixel：$n 點，最大誤差 $maxErr px（超出 maxDeg 的 $beyondFov 點不比）")
        assertTrue(n > 20000)
    }

    @Test
    fun roundTripGroundPixelGround() {
        var n = 0; var maxErr = 0.0
        for (setName in listOf("default", "variant")) {
            val geo = geoOf(setName)
            for (c in geo.cams) {
                var gz = -9.0
                while (gz <= 9.0) {
                    var gx = -8.0
                    while (gx <= 8.0) {
                        val p = geo.groundToPixel(c, gx, gz, QW, QH)
                        if (p != null) {
                            val g = geo.pixelToGround(c, p[0], p[1], QW, QH)
                            assertNotNull("$setName/${c.name} ($gx,$gz) 投影有效但反投影打不到地面", g)
                            val e = max(abs(g!![0] - gx), abs(g[1] - gz))
                            maxErr = max(maxErr, e)
                            assertTrue("$setName/${c.name} ($gx,$gz) 差 $e m", e <= TOL)
                            n++
                        }
                        gx += 0.25
                    }
                    gz += 0.25
                }
            }
        }
        println("ground→pixel→ground：$n 點，最大誤差 $maxErr m")
        assertTrue(n > 5000)
    }

    @Test
    fun newtonInvertsDistortion() {
        for ((k1, k2) in listOf(-0.05 to 0.004, 0.02 to -0.003, -0.035 to 0.0021)) {
            var th = 0.0
            while (th <= 2.4) {
                val rd = th * (1.0 + k1 * th * th + k2 * th * th * th * th)
                assertEquals("k1=$k1 k2=$k2 θ=$th", th, solveTheta(rd, k1, k2), 1e-12)
                th += 0.01
            }
        }
        assertEquals(0.7, solveTheta(0.7, 0.0, 0.0), 0.0)
    }

    @Test
    fun opticalAxesPointTheRightWay() {
        for (geo in listOf(assetGeo(), geoOf("variant"))) {
            val halfL = geo.carLength / 2
            val halfW = geo.carWidth / 2
            val hits = HashMap<String, DoubleArray>()
            for (c in geo.cams) {
                // 主點（鏡像時主點在 QW - cx·QW）
                val u = if (c.mirror) QW - c.cx * QW else c.cx * QW
                val v = c.cy * QH
                val ray = geo.pixelToRay(c, u, v, QW, QH)
                val yaw = c.yaw * PI / 180; val pitch = c.pitch * PI / 180
                assertEquals(sin(yaw) * cos(pitch), ray[0], 1e-9)
                assertEquals(-sin(pitch), ray[1], 1e-9)
                assertEquals(cos(yaw) * cos(pitch), ray[2], 1e-9)
                hits[c.name] = geo.pixelToGround(c, u, v, QW, QH) ?: error("${c.name} 光軸打不到地面")
            }
            val f = hits["front"]!!; val r = hits["rear"]!!; val l = hits["left"]!!; val rt = hits["right"]!!
            assertTrue("前鏡頭光軸應落在車頭前方：${f.toList()}", f[1] > halfL && abs(f[0]) < 0.5)
            assertTrue("後鏡頭光軸應落在車尾後方：${r.toList()}", r[1] < -halfL && abs(r[0]) < 0.5)
            assertTrue("左鏡頭光軸應落在 x < 0：${l.toList()}", l[0] < -halfW && abs(l[1]) < halfL)
            assertTrue("右鏡頭光軸應落在 x > 0：${rt.toList()}", rt[0] > halfW && abs(rt[1]) < halfL)
        }
        // 前鏡頭（未鏡像）：畫面往下 = 離車更近，往右 = 車右側
        val g = assetGeo(); val fc = g.cam("front")!!
        val mid = g.pixelToGround(fc, 480.0, 300.0, QW, QH)!!
        val lower = g.pixelToGround(fc, 480.0, 400.0, QW, QH)!!
        val right = g.pixelToGround(fc, 600.0, 300.0, QW, QH)!!
        assertTrue(lower[1] < mid[1])
        assertTrue(right[0] > mid[0])
        // 左鏡頭（朝 -x、未鏡像）：畫面右側 = 車頭方向
        val lc = g.cam("left")!!
        val lm = g.pixelToGround(lc, 480.0, 300.0, QW, QH)!!
        val lr = g.pixelToGround(lc, 600.0, 300.0, QW, QH)!!
        assertTrue(lr[1] > lm[1])
    }

    @Test
    fun bevLutQuadrantsAndEgo() {
        val geo = geoOf("default")
        val fw = 1920; val fh = 1080
        val lut = geo.buildBevLut(fw, fh)
        val b = geo.bev
        fun at(x: Double, z: Double): Int {
            val i = floor(x / b.mpp + b.originX).toInt()
            val j = floor(b.originY - z / b.mpp).toInt()
            return lut[j * b.width + i]
        }
        fun quadOf(idx: Int): Int = (idx / fw) / (fh / 2) * 2 + (idx % fw) / (fw / 2)
        assertEquals(-1, at(0.0, 0.0))
        assertEquals(-1, at(0.9, 2.4))
        assertEquals(-1, at(-0.9, -2.4))
        assertEquals(0, quadOf(at(0.0, 5.0)))
        assertEquals(1, quadOf(at(0.0, -5.0)))
        assertEquals(2, quadOf(at(-4.0, 0.0)))
        assertEquals(3, quadOf(at(4.0, 0.0)))
        // 每個有效索引都在畫面內，且像素中心反投影回來離 BEV 點不超過 0.75 像素
        val qw = fw / 2; val qh = fh / 2
        val t = DoubleArray(3)
        for (j in 0 until b.height) for (i in 0 until b.width) {
            val idx = lut[j * b.width + i]
            if (idx < 0) continue
            assertTrue(idx < fw * fh)
            val q = quadOf(idx)
            val c = geo.camForQuad(q)!!
            val gx = b.xMin + (i + 0.5) * b.mpp
            val gz = b.zMax - (j + 0.5) * b.mpp
            assertTrue(geo.project(c, gx, gz, qw, qh, t))
            assertEquals((q % 2) * qw + floor(t[0]).toInt(), idx % fw)
            assertEquals((q / 2) * qh + floor(t[1]).toInt(), idx / fw)
        }
    }

    @Test
    fun bevLutSpeed() {
        val geo = assetGeo()
        var best = Long.MAX_VALUE
        var first = 0L
        repeat(6) { k ->
            val t0 = System.nanoTime()
            val lut = geo.buildBevLut(1920, 1080)
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (k == 0) first = ms
            best = minOf(best, ms)
            assertEquals(geo.bev.width * geo.bev.height, lut.size)
        }
        println("buildBevLut 1920x1080 → ${geo.bev.width}x${geo.bev.height}：首次 $first ms，最快 $best ms（JVM）")
        assertTrue(first < 1000)
    }
}
