package com.vito.avmprobe

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** 測試資料：tools/perception_proto.py fixtures 產生的 PNG + JSON（GT 與 Python 原型輸出）。 */
internal object PerceptionFixtures {
    class Img(val argb: IntArray, val w: Int, val h: Int)

    private fun stream(name: String) =
        PerceptionFixtures::class.java.classLoader!!.getResourceAsStream("perception/$name")
            ?: error("找不到測試資料 perception/$name")

    fun json(name: String): JSONObject = JSONObject(stream(name).bufferedReader().use { it.readText() })

    /**
     * 用 javax.imageio 解 PNG。單元測試的編譯 classpath 是 android.jar（沒有 javax.imageio / java.awt），
     * 執行時跑在桌機 JDK 上才有，所以用反射呼叫。
     */
    fun image(name: String): Img {
        val io = Class.forName("javax.imageio.ImageIO")
        val im = stream(name).use { io.getMethod("read", java.io.InputStream::class.java).invoke(null, it) }
            ?: error("PNG 解不開：$name")
        val c = im.javaClass
        val w = c.getMethod("getWidth").invoke(im) as Int
        val h = c.getMethod("getHeight").invoke(im) as Int
        val I = Int::class.javaPrimitiveType
        val get = c.getMethod("getRGB", I, I, I, I, IntArray::class.java, I, I)
        val argb = get.invoke(im, 0, 0, w, h, null, 0, w) as IntArray
        return Img(argb, w, h)
    }

    fun roadNames(): List<String> {
        val a = json("index.json").getJSONArray("road")
        return (0 until a.length()).map { a.getString(it) }
    }

    fun exclude(doc: JSONObject): List<FloatArray> {
        val a = doc.getJSONArray("exclude")
        return (0 until a.length()).map { k ->
            val r = a.getJSONArray(k)
            FloatArray(4) { r.getDouble(it).toFloat() }
        }
    }

    fun optD(o: JSONObject, key: String): Double? = if (o.isNull(key)) null else o.getDouble(key)

    fun objs(a: JSONArray): List<JSONObject> = (0 until a.length()).map { a.getJSONObject(it) }

    fun median(v: List<Double>): Double = v.sorted()[v.size / 2]

    /** 本執行緒累計配置了多少 bytes（HotSpot 的 com.sun.management；同樣用反射，拿不到就回 -1）。 */
    fun allocated(): Long = try {
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        Class.forName("com.sun.management.ThreadMXBean")
            .getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
            .invoke(bean, Thread.currentThread().id) as Long
    } catch (e: Exception) {
        -1L
    }
}

class RoadExtractorTest {
    private val F = PerceptionFixtures

    private fun lineAngle(L: RoadLine): Double {
        var a = Math.toDegrees(atan2((L.x2 - L.x1).toDouble(), (L.z2 - L.z1).toDouble()))
        if (a >= 90) a -= 180
        if (a < -90) a += 180
        return a
    }

    /** det 對 GT 線：角度差、det 中點到 GT 線距離、軸向重疊。 */
    private fun err(L: RoadLine, g: JSONObject): Triple<Double, Double, Double> {
        val gphi = g.getDouble("phi")
        val da = abs(RoadExtractor.wrap90(lineAngle(L) - gphi))
        val c = cos(Math.toRadians(gphi))
        val s = sin(Math.toRadians(gphi))
        val xm = (L.x1 + L.x2) / 2.0
        val zm = (L.z1 + L.z2) / 2.0
        val dd = abs(xm * c - zm * s - g.getDouble("off"))
        val sa = L.x1 * s + L.z1 * c
        val sb = L.x2 * s + L.z2 * c
        val lo = min(sa, sb)
        val hi = max(sa, sb)
        val ov = when {
            g.has("s0") -> min(hi, g.getDouble("s1")) - max(lo, g.getDouble("s0"))
            g.has("b0") -> min(hi, g.getDouble("b1")) - max(lo, g.getDouble("b0"))
            else -> 1.0
        }
        return Triple(da, dd, ov)
    }

    private fun run(name: String): Pair<JSONObject, RoadResult> {
        val doc = F.json("$name.json")
        val img = F.image("$name.png")
        val r = RoadExtractor().extract(
            img.argb, img.w, img.h, doc.getDouble("mpp").toFloat(),
            doc.getDouble("cx").toFloat(), doc.getDouble("cy").toFloat(), F.exclude(doc)
        )
        return doc to r
    }

    @Test
    fun matchesGroundTruth() {
        var nGt = 0
        for (name in F.roadNames()) {
            val (doc, r) = run(name)
            val gt = doc.getJSONObject("gt")
            val gl = F.objs(gt.getJSONArray("lines"))
            val ign = F.objs(gt.getJSONArray("ignore"))
            // 每條看得到的 GT 線都要有一條偵測對上：位移 ±0.10 m、角度 ±3°、種類、虛實
            for (g in gl) {
                nGt++
                val hit = r.lines.any { L ->
                    val (da, dd, ov) = err(L, g)
                    da <= 3.0 && dd <= 0.10 && ov > 0 &&
                        L.kind == g.getString("k")[0] && L.dashed == g.getBoolean("d")
                }
                assertTrue("$name: GT 線 ${g} 沒有對上 (偵測 ${r.lines})", hit)
            }
            // 沒有多出來的線（斑馬紋 / 水泥路緣算可接受）
            for (L in r.lines) {
                val ok = gl.any { g -> val (da, dd, ov) = err(L, g); da <= 3.0 && dd <= 0.10 && ov > 0 } ||
                    ign.any { g -> val (da, dd, ov) = err(L, g); da <= 5.0 && dd <= 0.3 && ov > -0.5 }
                assertTrue("$name: 多出來的線 $L", ok)
            }
            // 路緣 ±0.25 m；GT 沒有就不該亂報
            for (key in listOf("edgeL", "edgeR")) {
                val g = F.optD(gt, key)
                val k = if (key == "edgeL") r.edgeL else r.edgeR
                if (g == null) assertNull("$name $key 應為 null", k)
                else if (k != null) assertEquals("$name $key", g, k.toDouble(), 0.25)
                else assertNull("$name $key：GT=$g 但 Python 也沒找到才可以缺", F.optD(doc.getJSONObject("py"), key))
            }
        }
        assertTrue("fixture 太少", nGt >= 20)
    }

    @Test
    fun matchesPythonPrototype() {
        for (name in F.roadNames()) {
            val (doc, r) = run(name)
            val py = doc.getJSONObject("py")
            val pl = F.objs(py.getJSONArray("lines"))
            assertEquals("$name 線數 (Kotlin ${r.lines})", pl.size, r.lines.size)
            for ((i, p) in pl.withIndex()) {
                val L = r.lines[i]
                assertEquals("$name[$i] kind", p.getString("k")[0], L.kind)
                assertEquals("$name[$i] dashed", p.getBoolean("d"), L.dashed)
                for ((key, v) in listOf("x1" to L.x1, "z1" to L.z1, "x2" to L.x2, "z2" to L.z2)) {
                    assertEquals("$name[$i] $key", p.getDouble(key), v.toDouble(), 0.02)
                }
                assertEquals("$name[$i] conf", p.getDouble("c"), L.conf.toDouble(), 0.02)
            }
            for (key in listOf("edgeL", "edgeR")) {
                val pv = F.optD(py, key)
                val k = if (key == "edgeL") r.edgeL else r.edgeR
                if (pv == null) assertNull("$name $key", k)
                else { assertNotNull("$name $key", k); assertEquals("$name $key", pv, k!!.toDouble(), 0.005) }
            }
        }
    }

    @Test
    fun timing300x350() {
        // 一般路段 + 最重的路口（停止線接車道線要全角度投票）
        for (name in listOf("road_urban_0", "road_intersection_0")) {
            val doc = F.json("$name.json")
            val img = F.image("$name.png")
            assertEquals(300, img.w)
            assertEquals(350, img.h)
            val ex = F.exclude(doc)
            val mpp = doc.getDouble("mpp").toFloat()
            val cx = doc.getDouble("cx").toFloat()
            val cy = doc.getDouble("cy").toFloat()
            val re = RoadExtractor()
            repeat(30) { re.extract(img.argb, img.w, img.h, mpp, cx, cy, ex) }
            val t = ArrayList<Double>()
            val a0 = F.allocated()
            repeat(50) {
                val s = System.nanoTime()
                re.extract(img.argb, img.w, img.h, mpp, cx, cy, ex)
                t.add((System.nanoTime() - s) / 1e6)
            }
            val alloc = if (a0 >= 0) (F.allocated() - a0) / 50 else -1
            val med = F.median(t)
            println("RoadExtractor.extract 300x350 [$name]：中位數 %.2f ms（50 次，暖機 30 次），每次配置約 %d bytes".format(med, alloc))
            assertTrue("太慢：$med ms", med < 100.0)
        }
    }

    // ───── RoadTracker ─────

    private fun line(x: Float, dashed: Boolean = false, kind: Char = 'w') = RoadLine(kind, dashed, x, -5f, x, 5f, 1f)

    @Test
    fun trackerNeedsTwoHitsAndSmooths() {
        val tr = RoadTracker()
        val r1 = tr.update(RoadResult(listOf(line(1.70f)), null, 3.0f, 5), 0)
        assertTrue("第一次只看到一次，不該輸出", r1.lines.isEmpty())
        assertEquals(3.0f, r1.edgeR!!, 1e-6f)
        val r2 = tr.update(RoadResult(listOf(line(1.80f)), null, 3.2f, 5), 100)
        assertEquals(1, r2.lines.size)
        // EMA α=0.35：1.70 + 0.35×0.10 = 1.735；路緣 α=0.3：3.0 + 0.3×0.2 = 3.06
        assertEquals(1.735f, r2.lines[0].x1, 1e-4f)
        assertEquals(3.06f, r2.edgeR!!, 1e-4f)
        // 不同種類 / 太遠的不會併進同一軌
        val r3 = tr.update(RoadResult(listOf(line(1.80f), line(1.80f, kind = 'y'), line(2.5f)), null, null, 5), 200)
        assertEquals(1, r3.lines.size)
        val r4 = tr.update(RoadResult(listOf(line(1.80f), line(1.80f, kind = 'y'), line(2.5f)), null, null, 5), 300)
        assertEquals(3, r4.lines.size)
    }

    @Test
    fun trackerDropsStaleTracksAndEdges() {
        val tr = RoadTracker()
        tr.update(RoadResult(listOf(line(-1.6f, dashed = true)), -3.0f, null, 5), 0)
        val a = tr.update(RoadResult(listOf(line(-1.6f, dashed = true)), -3.0f, null, 5), 300)
        assertEquals(1, a.lines.size)
        assertTrue(a.lines[0].dashed)
        val b = tr.update(RoadResult(emptyList(), null, null, 5), 600)
        assertEquals("最近 5 次還有 2 次對到、600 ms 內 → 留著", 1, b.lines.size)
        assertEquals(-3.0f, b.edgeL!!, 1e-6f)
        val c = tr.update(RoadResult(emptyList(), null, null, 5), 1200)
        assertTrue("超過 800 ms 沒對到要丟掉", c.lines.isEmpty())
        assertEquals(-3.0f, c.edgeL!!, 1e-6f)
        val d = tr.update(RoadResult(emptyList(), null, null, 5), 1700)
        assertEquals("最後一次有值在 300 ms，1.4 s 後還留著", -3.0f, d.edgeL!!, 1e-6f)
        val e = tr.update(RoadResult(emptyList(), null, null, 5), 1900)
        assertNull("路緣超過 1.5 s 沒值要變 null", e.edgeL)
    }

    @Test
    fun trackerHandlesTransverseWrap() {
        // 橫向停止線：方向角在 +89° 與 -89° 之間跳（同一條線的兩種表示）
        val tr = RoadTracker()
        tr.update(RoadResult(listOf(RoadLine('w', false, -1.8f, 4.00f, 1.8f, 4.06f, 0.9f)), null, null, 5), 0)
        val r = tr.update(RoadResult(listOf(RoadLine('w', false, -1.8f, 4.04f, 1.8f, 3.98f, 0.9f)), null, null, 5), 100)
        assertEquals(1, r.lines.size)
        val L = r.lines[0]
        assertEquals(4.02, (L.z1 + L.z2) / 2.0, 0.03)
        assertFalse(L.dashed)
    }
}
