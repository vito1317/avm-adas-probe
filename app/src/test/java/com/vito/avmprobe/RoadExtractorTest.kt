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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

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

    fun curveNames(): List<String> {
        val a = json("index.json").getJSONArray("curve")
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

    /**
     * 彎道：曲率的正負與大小。正負錯了彎道會畫成鏡像，而且不會有人發現，所以一定要釘住。
     * 約定：k 是「以兩端點的弦為基準、往法向凸為正」，所以**向右彎的路 k 是負的**
     * （向右彎的弧相對自己的弦是往左凸）。
     */
    @Test
    fun recoversCurvature() {
        var checked = 0
        for (name in F.curveNames()) {
            val (doc, r) = run(name)
            val want = doc.getDouble("want")
            assertEquals("$name 應該只找到一條線", 1, r.lines.size)
            val got = r.lines[0].k.toDouble()
            if (want == 0.0) {
                assertEquals("$name 直線不可以生出曲率", 0.0, got, 1e-9)
            } else if (name.endsWith("r200") || name.startsWith("curve_short")) {
                // 太緩（弧高小於門檻）或跨度太短：寧可回直線，也不要擬合雜訊
                assertEquals("$name 應該退回直線", 0.0, got, 1e-9)
            } else {
                assertTrue("$name 曲率正負錯了：want $want got $got", want * got > 0)
                assertEquals("$name 曲率大小", want, got, abs(want) * 0.15)
                checked++
            }
        }
        assertTrue("沒有任何彎道樣本被驗到", checked >= 4)
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
                // 合成場景的線全是直的；曲率要跟 Python 一樣是 0
                assertEquals("$name[$i] 曲率", p.getDouble("curv"), L.k.toDouble(), 1e-4)
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

    // ───── 彎道（RoadLine.k） ─────
    //
    // 曲率的大小與正負由 curve_* fixture（recoversCurvature / matchesPythonPrototype）釘住。
    // 這裡再自己畫幾張乾淨的合成圖，補「什麼時候不可以擬合」：直線、短線、斷續歪斜的雜訊。
    // 畫法跟 tools/perception_proto.py 的 synth_arc 一樣（同樣的視野、漆寬、顏色、一個像素寬的覆蓋率漸層）。

    private val CV_W = 300
    private val CV_H = 350
    private val CV_MPP = 0.04f
    private val CV_CX = 150f
    private val CV_CY = 175f
    private val CV_HALF_W = 0.06
    private val CV_ROAD = doubleArrayOf(100.0, 100.0, 100.0)
    private val CV_PAINT = doubleArrayOf(228.0, 228.0, 222.0)
    private val EGO = listOf(floatArrayOf(-0.925f, -2.42f, 0.925f, 2.42f))

    /** cov(x, z) ∈ [0,1] 的覆蓋率 → 柏油與漆的線性混色（floor(v+0.5)，跟 Python 一致）。 */
    private fun renderBev(cov: (Double, Double) -> Double): PerceptionFixtures.Img {
        val m = CV_MPP.toDouble()
        val ox = CV_CX.toDouble()
        val oy = CV_CY.toDouble()
        val argb = IntArray(CV_W * CV_H)
        for (j in 0 until CV_H) {
            val z = (oy - (j + 0.5)) * m
            for (i in 0 until CV_W) {
                val x = (i + 0.5 - ox) * m
                val c = cov(x, z).coerceIn(0.0, 1.0)
                var v = 0xFF shl 24
                for (ch in 0..2) {
                    val q = floor(CV_ROAD[ch] + (CV_PAINT[ch] - CV_ROAD[ch]) * c + 0.5).toInt()
                    v = v or (q shl (16 - 8 * ch))
                }
                argb[j * CV_W + i] = v
            }
        }
        return PerceptionFixtures.Img(argb, CV_W, CV_H)
    }

    /** 半徑 R 的弧（R > 0：圓心在 +x 側；R = 0：直線），畫在 |z| ≤ zhalf 的範圍。 */
    private fun synthArc(R: Double, x0: Double, zhalf: Double): PerceptionFixtures.Img {
        val m = CV_MPP.toDouble()
        return renderBev { x, z ->
            if (abs(z) > zhalf) 0.0
            else {
                val dist = if (R == 0.0) abs(x - x0) else {
                    val dx = x - (x0 + R)
                    abs(sqrt(dx * dx + z * z) - abs(R))
                }
                (0.5 + (CV_HALF_W - dist) / m).coerceIn(0.0, 1.0)
            }
        }
    }

    private fun extractCv(img: PerceptionFixtures.Img) =
        RoadExtractor().extract(img.argb, img.w, img.h, CV_MPP, CV_CX, CV_CY, EGO)

    /** 依 RoadLine.k 的定義取弧上的點：t ∈ [−1,1] 對應 s = t·L/2，d(s) = (k/2)((L/2)² − s²)。 */
    private fun arcPoint(L: RoadLine, t: Double): DoubleArray {
        val vx = (L.x2 - L.x1).toDouble()
        val vz = (L.z2 - L.z1).toDouble()
        val len = sqrt(vx * vx + vz * vz)
        val phi = atan2(vx, vz)
        val hl = len / 2.0
        val s = t * hl
        val d = L.k / 2.0 * (hl * hl - s * s)
        return doubleArrayOf(
            (L.x1 + L.x2) / 2.0 + sin(phi) * s + cos(phi) * d,
            (L.z1 + L.z2) / 2.0 + cos(phi) * s - sin(phi) * d
        )
    }

    @Test
    fun arcDefinitionPassesEndpointsAndSagitta() {
        // 縱向線、k > 0 → 弧在中間往 n（對 φ=0 就是 +x／右邊）凸，弧高 = k·L²/8
        val L = RoadLine('w', false, 1.0f, -3f, 1.0f, 3f, 1f, 0.05f)
        val a = arcPoint(L, -1.0)
        val b = arcPoint(L, 1.0)
        assertEquals("弧要通過端點 1", L.x1.toDouble(), a[0], 1e-9)
        assertEquals("弧要通過端點 1", L.z1.toDouble(), a[1], 1e-9)
        assertEquals("弧要通過端點 2", L.x2.toDouble(), b[0], 1e-9)
        assertEquals("弧要通過端點 2", L.z2.toDouble(), b[1], 1e-9)
        val mid = arcPoint(L, 0.0)
        assertEquals("弧高 = k·L²/8", 1.0 + L.k.toDouble() * 36.0 / 8.0, mid[0], 1e-9)
        assertEquals("弧高 ≈ 0.225 m", 1.225, mid[0], 1e-6)
        assertEquals(0.0, mid[1], 1e-9)
        // k = 0 就是直線
        val S = RoadLine('w', false, 1.0f, -3f, 1.0f, 3f, 1f)
        assertEquals(1.0, arcPoint(S, 0.0)[0], 1e-9)
        assertEquals(1.0, arcPoint(S, 0.5)[0], 1e-9)
    }

    @Test
    fun arcSignFlipsWhenEndpointsSwap() {
        val a = RoadLine('w', false, 1.0f, -3f, 1.2f, 3f, 1f, 0.04f)
        val b = RoadLine('w', false, a.x2, a.z2, a.x1, a.z1, 1f, -a.k)
        // 端點對調 + k 變號 = 同一條實體弧線
        for (t in listOf(-1.0, -0.5, 0.0, 0.3, 1.0)) {
            val p = arcPoint(a, t)
            val q = arcPoint(b, -t)
            assertEquals("t=$t x", p[0], q[0], 1e-9)
            assertEquals("t=$t z", p[1], q[1], 1e-9)
        }
        // 同樣的端點但 k 不變號 → 畫出來不一樣（確認測試有在管符號）
        val wrong = RoadLine('w', false, a.x2, a.z2, a.x1, a.z1, 1f, a.k)
        assertTrue(abs(arcPoint(a, 0.0)[0] - arcPoint(wrong, 0.0)[0]) > 0.1)
    }

    @Test
    fun straightLineStaysStraight() {
        // 位置、方向都不影響：直線就是直線
        for (x0 in listOf(-2.5, 1.5, 2.0, 3.5)) {
            val r = extractCv(synthArc(0.0, x0, 3.0))
            assertTrue("x0=$x0 應該找得到線", r.lines.isNotEmpty())
            for (L in r.lines) assertEquals("直線不可以有曲率 ($L)", 0f, L.k, 0f)
        }
        // 很緩的彎（R = 200 m，6 m 的弧高只有 0.0225 m）寧可畫直線
        for (L in extractCv(synthArc(200.0, 2.0, 3.0)).lines) assertEquals(0f, L.k, 0f)
        // 跨度太短（2.4 m）也不擬合
        for (L in extractCv(synthArc(30.0, 2.0, 1.2)).lines) assertEquals(0f, L.k, 0f)
    }

    /** 固定種子的 LCG，讓雜訊測試每次都一樣。 */
    private class Rnd(private var s: Long) {
        fun next(): Double {
            s = s * 6364136223846793005L + 1442695040888963407L
            return ((s ushr 11).toDouble() + 0.5) / 9007199254740992.0
        }

        fun sym() = next() * 2.0 - 1.0
    }

    @Test
    fun noiseNeverProducesCurvature() {
        val m = CV_MPP.toDouble()
        for (seed in 1L..6L) {
            val g = Rnd(seed * 7919L)
            // (a) 散落的小亮點
            val spots = Array(400) { doubleArrayOf(g.sym() * 5.5, g.sym() * 6.5, 0.05 + 0.06 * g.next()) }
            for (L in extractCv(renderBev { x, z ->
                var c = 0.0
                for (p in spots) {
                    val d = sqrt((x - p[0]) * (x - p[0]) + (z - p[1]) * (z - p[1]))
                    c = max(c, (0.5 + (p[2] - d) / m).coerceIn(0.0, 1.0))
                }
                c
            }).lines) assertEquals("散點雜訊不可以生出彎度 ($L)", 0f, L.k, 0f)

            // (b) 斷續 + 橫向亂抖的「線」：最容易被二次項硬擬出一個假彎道
            val nd = 14
            val off = DoubleArray(nd) { g.sym() * 0.15 }
            val skew = g.sym() * 0.08
            for (L in extractCv(renderBev { x, z ->
                val t = (z + 7.0) / 1.0
                val k = floor(t).toInt()
                if (k < 0 || k >= nd || t - k > 0.55) 0.0
                else (0.5 + (CV_HALF_W - abs(x - (1.8 + off[k] + skew * z))) / m).coerceIn(0.0, 1.0)
            }).lines) assertEquals("斷續歪斜的雜訊不可以生出彎度 ($L)", 0f, L.k, 0f)

            // (c) 一小段短線（1.4 m）
            for (L in extractCv(renderBev { x, z ->
                if (abs(z - 1.0) > 0.7) 0.0
                else (0.5 + (CV_HALF_W - abs(x - 2.2)) / m).coerceIn(0.0, 1.0)
            }).lines) assertEquals("短線不可以生出彎度 ($L)", 0f, L.k, 0f)
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

    @Test
    fun trackerSmoothsCurvature() {
        val tr = RoadTracker()
        fun curved(k: Float) = RoadLine('w', false, 1.7f, -5f, 1.7f, 5f, 1f, k)
        tr.update(RoadResult(listOf(curved(0f)), null, null, 5), 0)
        val a = tr.update(RoadResult(listOf(curved(0.04f)), null, null, 5), 100)
        // EMA α=0.35：0 + 0.35×0.04 = 0.014
        assertEquals(1, a.lines.size)
        assertEquals(0.014f, a.lines[0].k, 1e-5f)
        val b = tr.update(RoadResult(listOf(curved(0.04f)), null, null, 5), 200)
        assertEquals(0.0231f, b.lines[0].k, 1e-4f)
        // 曲率差很大也不可以把同一條線拆成兩軌
        val c = tr.update(RoadResult(listOf(curved(-0.06f)), null, null, 5), 300)
        assertEquals("曲率差異不可以造成分裂", 1, c.lines.size)
        assertTrue(c.lines[0].k < b.lines[0].k)
    }

    @Test
    fun trackerFlipsCurvatureWithDirection() {
        // 同一條弧，端點順序對調（k 也跟著變號）送進來，應該併進同一軌、曲率不會互相抵銷
        val tr = RoadTracker()
        val fwd = RoadLine('w', false, 1.7f, -5f, 1.9f, 5f, 1f, 0.05f)
        val rev = RoadLine('w', false, 1.9f, 5f, 1.7f, -5f, 1f, -0.05f)
        tr.update(RoadResult(listOf(fwd), null, null, 5), 0)
        val a = tr.update(RoadResult(listOf(rev), null, null, 5), 100)
        assertEquals(1, a.lines.size)
        assertEquals("兩種表示法的曲率要一致", 0.05, abs(a.lines[0].k).toDouble(), 1e-4)
        // 輸出的端點順序決定符號：端點 1 的 z 比較小 → 跟 fwd 同向 → k > 0
        assertTrue(a.lines[0].z1 < a.lines[0].z2)
        assertTrue(a.lines[0].k > 0f)
    }
}

/**
 * 實車窄巷擷取（兩側黃線、路邊機車、店面招牌）：
 * 使用者回報「只會顯示直線」「車子底下多出很多奇怪的直線」。
 *
 * 原因是俯視圖外圈的入射角太平，立起來的東西被拉成放射狀拖痕，又長又亮，
 * 被當成標線抽出來；而且二次擬合被那些拖痕拉著，怎麼擬都是直線。
 * 切掉投地假設不成立的區域（[IPM_MIN_ELEV_DEG]）之後兩個問題一起好轉。
 */
class RoadExtractorStreetTest {
    private val F = PerceptionFixtures

    private fun run(withMask: Boolean, name: String = "quad_street"): List<RoadLine> {
        val doc = F.json("$name.json")
        val img = F.image("$name.png")
        val geo = AvmGeometry.fromJson(
            RoadExtractorStreetTest::class.java.classLoader!!
                .getResourceAsStream("../../../main/assets/avm_calib.json")
                ?.bufferedReader()?.use { it.readText() }
                ?: java.io.File("src/main/assets/avm_calib.json").readText()
        )
        val mask = if (withMask) geo.ipmValidMask() else null
        return RoadExtractor().extract(
            img.argb, img.w, img.h,
            doc.getDouble("mpp").toFloat(), doc.getDouble("cx").toFloat(), doc.getDouble("cy").toFloat(),
            F.exclude(doc), mask
        ).lines
    }

    private fun lenOf(l: RoadLine) = kotlin.math.hypot((l.x2 - l.x1).toDouble(), (l.z2 - l.z1).toDouble())
    private fun longitudinal(l: RoadLine) =
        abs(l.z2 - l.z1) > abs(l.x2 - l.x1)      // 沿著車身方向的線

    /** 兩側的黃線要抽得到，而且其中至少一條是彎的（那條路確實是彎的）。 */
    @Test
    fun findsCurvedLaneLines() {
        val lines = run(true)
        val lon = lines.filter { longitudinal(it) && lenOf(it) >= 4.0 }
        assertTrue("應該抽得到兩側的縱向標線，實際 ${lon.size} 條（全部 ${lines.size} 條）", lon.size >= 2)
        assertTrue("這條路是彎的，至少要有一條帶曲率：${lines.map { it.k }}", lines.any { abs(it.k) > 1e-4f })
    }

    /** 不可以再冒出一堆橫越整張圖的假線。 */
    @Test
    fun noLongTransverseGarbage() {
        val lines = run(true)
        val bad = lines.filter { !longitudinal(it) && lenOf(it) >= 8.0 }
        assertTrue("橫越整張圖的長假線太多：${bad.size} 條", bad.size <= 1)
        assertTrue("線太多了：${lines.size} 條", lines.size <= 6)
    }

    /**
     * 遮罩才是關鍵，不是順便加的：
     * 沒有它線會變多，而且**一條彎的都抽不到**（二次擬合被外圈的拖痕拉直了）。
     */
    @Test
    fun maskIsWhatFixesIt() {
        val without = run(false)
        val with = run(true)
        assertTrue("沒遮罩時線應該更多（${without.size} vs ${with.size}）", without.size > with.size)
        // 沒遮罩時唯一帶曲率的是一條橫越整張圖的假線 —— 曲率有出現，但出現在錯的東西上
        assertTrue(
            "沒遮罩時不該有彎的縱向線：${without.filter { longitudinal(it) }.map { it.k }}",
            without.none { longitudinal(it) && abs(it.k) > 1e-4f }
        )
        assertTrue(
            "有遮罩才抽得到彎的縱向線：${with.filter { longitudinal(it) }.map { it.k }}",
            with.any { longitudinal(it) && abs(it.k) > 1e-4f }
        )
    }

    /**
     * 實車地下停車場：使用者說「車子底下會多出很多奇怪的直線」。
     * 自車那一塊整個被排除在遮罩外，那裡一個證據都沒有 —— 穿過車底的線
     * 一定是把車子兩側不相干的東西接起來的。
     */
    @Test
    fun noLineCrossesUnderTheCar() {
        for (name in listOf("quad_garage_road", "quad_street")) {
            val lines = run(true, name)
            for (l in lines) {
                var inside = 0
                val n = 80
                for (q in 0 until n) {
                    val f = (q + 0.5) / n
                    val x = l.x1 + (l.x2 - l.x1) * f
                    val z = l.z1 + (l.z2 - l.z1) * f
                    if (abs(x) <= RoadExtractor.EGO_HX + 0.15f && abs(z) <= RoadExtractor.EGO_HZ + 0.15f) inside++
                }
                assertTrue(
                    "$name 有線穿過車底 ${inside * 100 / n}%：(${l.x1},${l.z1})→(${l.x2},${l.z2})",
                    inside.toDouble() / n < 0.15
                )
            }
            assertTrue("$name 線太多：${lines.size}", lines.size <= 8)
        }
    }
}
