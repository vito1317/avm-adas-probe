package com.vito.avmprobe

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** 測試資料：tools/height_proto.py fixtures 產生的原始 2x2 畫面 PNG + JSON（參數、真值、Python 輸出）。 */
internal object HeightFixtures {
    class Img(val argb: IntArray, val w: Int, val h: Int)

    private fun stream(name: String) =
        HeightFixtures::class.java.classLoader!!.getResourceAsStream("height/$name")
            ?: error("找不到測試資料 height/$name")

    fun json(name: String): JSONObject = JSONObject(stream(name).bufferedReader().use { it.readText() })

    /** 同 PerceptionFixtures.image：單元測試的編譯 classpath 是 android.jar，javax.imageio 只有執行時才有。 */
    fun image(name: String): Img {
        val io = Class.forName("javax.imageio.ImageIO")
        val im = stream(name).use { io.getMethod("read", java.io.InputStream::class.java).invoke(null, it) }
            ?: error("PNG 解不開：$name")
        val c = im.javaClass
        val w = c.getMethod("getWidth").invoke(im) as Int
        val h = c.getMethod("getHeight").invoke(im) as Int
        val I = Int::class.javaPrimitiveType
        val get = c.getMethod("getRGB", I, I, I, I, IntArray::class.java, I, I)
        return Img(get.invoke(im, 0, 0, w, h, null, 0, w) as IntArray, w, h)
    }

    fun names(): List<String> {
        val a = json("index.json").getJSONArray("cases")
        return (0 until a.length()).map { a.getString(it) }
    }

    fun geo(doc: JSONObject): AvmGeometry = AvmGeometry.fromJson(doc.getJSONObject("calib").toString())

    fun pyObstacles(doc: JSONObject): List<Obstacle> {
        val a = doc.getJSONArray("py")
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Obstacle(o.getDouble("x").toFloat(), o.getDouble("z").toFloat(),
                o.getDouble("w").toFloat(), o.getDouble("l").toFloat(), o.getDouble("conf").toFloat())
        }
    }

    fun median(v: List<Double>): Double = v.sorted()[v.size / 2]
}

class HeightDetectorTest {
    private val F = HeightFixtures

    private fun run(name: String): Triple<JSONObject, List<Obstacle>, HeightDetector> {
        val doc = F.json("$name.json")
        val img = F.image("$name.png")
        assertEquals("$name 畫面寬", doc.getInt("frameW"), img.w)
        assertEquals("$name 畫面高", doc.getInt("frameH"), img.h)
        val det = HeightDetector(F.geo(doc))
        return Triple(doc, det.detect(img.argb, img.w, img.h), det)
    }

    /** fixture 的參數要跟 HeightDetector 的常數一致，不然比對沒有意義。 */
    @Test
    fun fixtureMatchesConstants() {
        val doc = F.json("flat.json")
        assertEquals(HeightDetector.STRIDE, doc.getInt("stride"))
        assertEquals(HeightDetector.TAP, doc.getInt("tap"))
        val h = doc.getJSONArray("heights")
        assertEquals("掃描高度數量", HeightDetector.HEIGHTS.size, h.length())
        for (k in 0 until h.length()) {
            assertEquals("heights[$k]", h.getDouble(k), HeightDetector.HEIGHTS[k], 1e-9)
        }
    }

    /** 跟 Python 原型逐筆對：數量、位置、範圍、信心。 */
    @Test
    fun matchesPythonPrototype() {
        var total = 0
        for (name in F.names()) {
            val (doc, got) = run(name)
            val py = F.pyObstacles(doc)
            assertEquals("$name 障礙物數（Kotlin $got）", py.size, got.size)
            for ((i, p) in py.withIndex()) {
                assertEquals("$name[$i] x", p.x.toDouble(), got[i].x.toDouble(), 1e-6)
                assertEquals("$name[$i] z", p.z.toDouble(), got[i].z.toDouble(), 1e-6)
                assertEquals("$name[$i] w", p.w.toDouble(), got[i].w.toDouble(), 1e-6)
                assertEquals("$name[$i] l", p.l.toDouble(), got[i].l.toDouble(), 1e-6)
                assertEquals("$name[$i] conf", p.conf.toDouble(), got[i].conf.toDouble(), 1e-6)
            }
            total += py.size
        }
        assertTrue("fixture 裡的障礙物太少：$total", total >= 8)
    }

    /**
     * 合成柱子：已知位置的方柱／牆，看偵測到的位置差多遠。
     *
     * 回報的是「拖痕離車最近的一端」，物體本體一定在那一端與車子之間，所以誤差是**往外偏**的，
     * 量到約 1.0–1.5 m（0.5 m 見方的柱子）。這裡只守住「不會偏到離譜」與「不會整個漏掉」。
     */
    @Test
    fun findsSyntheticPillars() {
        val (doc, got) = run("pillars")
        val gt = doc.getJSONArray("gt")
        assertTrue("合成場景應該要偵測到東西", got.isNotEmpty())
        var hit = 0
        for (k in 0 until gt.length()) {
            val g = gt.getJSONObject(k)
            val e = got.minOf { hypot(it.x - g.getDouble("x"), it.z - g.getDouble("z")) }
            println("合成真值 (%.2f, %.2f) 高 %.1f m → 最近偵測誤差 %.2f m"
                .format(g.getDouble("x"), g.getDouble("z"), g.getDouble("h"), e))
            if (e <= 2.0) hit++
        }
        assertTrue("三個合成物體至少要抓到兩個（2 m 內），實際 $hit", hit >= 2)
        // 偵測到的東西都要落在重疊楔形的距離範圍內，不能飛到 BEV 邊界外
        for (o in got) {
            assertTrue("偵測位置超出 BEV：$o", abs(o.x) <= 6.5 && abs(o.z) <= 7.5)
            assertTrue("回報的範圍不合理：$o", o.w > 0f && o.l > 0f && o.w < 6f && o.l < 6f)
        }
    }

    /** 最重要的一條：平坦地面不可以誤報。假警告比漏報更會讓人不信任系統。 */
    @Test
    fun noFalseAlarmOnFlatGround() {
        val (_, got, det) = run("flat")
        assertTrue("純平地誤報 $got", got.isEmpty())
        assertTrue("可判定面積太小，這個測試沒意義：${det.decidableArea()}", det.decidableArea() > 40.0)
    }

    /** 退化輸入不可以崩潰，也不可以亂報。 */
    @Test
    fun degenerateInputs() {
        for (name in listOf("black", "white")) {
            val (_, got) = run(name)
            assertTrue("$name 不該有障礙物：$got", got.isEmpty())
        }
        // 全黑：每個 tap 都在暗角門檻以下 → 沒有任何格子可判定
        val blackDet = run("black").third
        assertEquals("全黑應該沒有可判定的格子", 0.0, blackDet.decidableArea(), 1e-9)

        val doc = F.json("flat.json")
        val geo = F.geo(doc)
        val w = doc.getInt("frameW")
        val h = doc.getInt("frameH")
        val det = HeightDetector(geo)
        // 畫面大小中途改變：查表要重建，不可以炸
        det.detect(IntArray(w * h) { 0x4080C0 }, w, h)
        det.detect(IntArray((w / 2) * (h / 2)) { 0x4080C0 }, w / 2, h / 2)
        det.detect(IntArray(w * h) { if (it % 3 == 0) 0xFFFFFF else 0 }, w, h)
        // 只有一顆鏡頭看得到的區域（把三個象限塗黑）→ 沒有鏡頭對，應該安靜地回空
        val one = IntArray(w * h)
        for (j in 0 until h / 2) for (i in 0 until w / 2) one[j * w + i] = 0x808080
        assertTrue("只有一個象限有畫面時不該有輸出", det.detect(one, w, h).isEmpty())
    }

    /** 真實停車場：應該在四個斜角的重疊楔形裡抓到牆 / 柱 / 旁邊的車。 */
    @Test
    fun findsGarageStructures() {
        val name = F.names().first { it.startsWith("real_") }
        val (_, got, det) = run(name)
        println("$name：可判定 %.1f m²（重疊覆蓋率 %.0f%%），偵測 %d 個".format(
            det.decidableArea(), det.coverage() * 100, got.size))
        for (o in got) println("  $o")
        assertTrue("停車場應該要抓到好幾個結構，實際 ${got.size}", got.size >= 4)
        // 四個斜角都該有東西（這張圖四周都是牆、柱子跟車）
        val quads = got.map { (if (it.x < 0) 0 else 1) + (if (it.z < 0) 0 else 2) }.toSet()
        assertTrue("偵測結果集中在 ${quads.size} 個象限，應該四個斜角都有", quads.size >= 3)
        assertTrue("重疊覆蓋率應該有四成以上，實際 ${det.coverage()}", det.coverage() >= 0.35)
    }

    /**
     * QuadActivity 不轉整張畫面，只填 [HeightDetector.pixelsNeeded] 回報的位置。
     * 那份清單要是漏了任何一點，偵測就會讀到沒填過的黑色、而且不會有任何錯誤訊息，
     * 所以這裡用「只填清單上的點」的畫面跑一次，結果必須跟整張都填一模一樣。
     */
    @Test
    fun sparseFillMatchesFullFrame() {
        val name = F.names().first { it.startsWith("real_") }
        val doc = F.json("$name.json")
        val img = F.image("$name.png")

        val full = HeightDetector(F.geo(doc)).detect(img.argb, img.w, img.h)

        val det = HeightDetector(F.geo(doc))
        val need = det.pixelsNeeded(img.w, img.h)
        assertTrue("清單是空的", need.isNotEmpty())
        assertTrue("清單比整張畫面還大", need.size < img.w * img.h)
        // 填成跟原圖互補的垃圾色，漏填的點會被讀成明顯不同的值
        val sparse = IntArray(img.w * img.h) { img.argb[it].inv() and 0xFFFFFF }
        for (p in need) sparse[p] = img.argb[p]
        val got = det.detect(sparse, img.w, img.h)

        assertEquals("障礙物數量", full.size, got.size)
        for (i in full.indices) {
            assertEquals("第 $i 個 x", full[i].x, got[i].x, 1e-6f)
            assertEquals("第 $i 個 z", full[i].z, got[i].z, 1e-6f)
            assertEquals("第 $i 個 w", full[i].w, got[i].w, 1e-6f)
            assertEquals("第 $i 個 l", full[i].l, got[i].l, 1e-6f)
            assertEquals("第 $i 個 conf", full[i].conf, got[i].conf, 1e-6f)
        }
        println("pixelsNeeded %dx%d：%d 點（整張的 %.1f%%）"
            .format(img.w, img.h, need.size, 100.0 * need.size / (img.w * img.h)))
    }

    /** 效能：車機是 Mali-G52，這東西跟 YOLO 搶 CPU。桌機數字 × 大約 6–10 倍才是車機上的時間。 */
    @Test
    fun timing() {
        val name = F.names().first { it.startsWith("real_") }
        val doc = F.json("$name.json")
        val img = F.image("$name.png")
        val det = HeightDetector(F.geo(doc))
        repeat(20) { det.detect(img.argb, img.w, img.h) }
        val t = ArrayList<Double>()
        repeat(40) {
            val s = System.nanoTime()
            det.detect(img.argb, img.w, img.h)
            t.add((System.nanoTime() - s) / 1e6)
        }
        val med = F.median(t)
        println("HeightDetector.detect %dx%d（網格 %.2f m，%d 個高度）：中位數 %.2f ms（40 次，暖機 20 次）"
            .format(img.w, img.h, 0.04 * HeightDetector.STRIDE, HeightDetector.HEIGHTS.size, med))
        assertTrue("太慢：$med ms", med < 60.0)

        // 車機實際餵進來的是 1920x1080：網格大小一樣，差別在查表去抓原始畫面的快取命中率
        val big = IntArray(1920 * 1080) { (it * 2654435761L).toInt() and 0x7F7F7F }
        val det2 = HeightDetector(F.geo(doc))
        repeat(20) { det2.detect(big, 1920, 1080) }
        val t2 = ArrayList<Double>()
        repeat(40) {
            val s = System.nanoTime()
            det2.detect(big, 1920, 1080)
            t2.add((System.nanoTime() - s) / 1e6)
        }
        println("HeightDetector.detect 1920x1080：中位數 %.2f ms".format(F.median(t2)))
    }
}
