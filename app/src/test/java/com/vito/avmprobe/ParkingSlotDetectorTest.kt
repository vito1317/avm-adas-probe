package com.vito.avmprobe

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * ParkingSlotDetector 與 tools/parking_proto.py 的一致性、合成真值的精度、負樣本、輸出慣例、耗時。
 * fixture（app/src/test/resources/parking 下的 PNG + JSON）由 `parking_proto.py fixtures` 產生：
 * json 內有輸入參數（mpp、cx、cy、排除區、車輛框）、真值 gt 與原型輸出 proto。
 * adv_* 是對抗場景（機車格 T 字 / 框、停止線 + 車道線、機車停等區、植草磚、陰影亮縫、網狀線、地下室柱子、
 * 車頭斜對走道、接縫 + 校正誤差、重度模糊、輪擋、雙線、停歪的車），多數是舊版會誤報或類型錯的情況。
 */
class ParkingSlotDetectorTest {

    private class Gt(val p: DoubleArray, val kind: Char, val occupied: Boolean, val detectable: Boolean)

    private class Fix(
        val name: String, val argb: IntArray, val w: Int, val h: Int, val mpp: Float, val cx: Float, val cy: Float,
        val exclude: List<FloatArray>, val vehicles: List<FloatArray>, val gt: List<Gt>, val proto: List<ParkingSlot>
    )

    companion object {
        /** 對抗場景（parking_proto.py adv）：負樣本 = 舊版會誤報的機車格 T 字 / 框、停等區、植草磚、陰影亮縫。 */
        private val ADV_NEG = listOf("adv_scoot2_t", "adv_scoot2_box", "adv_curb", "adv_pavers", "adv_pavers_part", "adv_shadow")
        private val ADV_POS = listOf(
            "adv_xwalk", "adv_hatch", "adv_text", "adv_garage", "adv_rot", "adv_seam", "adv_blur", "adv_stopper",
            "adv_double", "adv_occl"
        )
        private val BASE = listOf(
            "aisle_box_t", "aisle_sep", "aisle_u", "egoperp", "parallel", "egopar", "angled", "ahead",
            "road", "scooter", "v256_egoperp_own", "v256_egoperp_stock"
        )
        private val SYNTH = BASE + ADV_NEG + ADV_POS
        private val REAL = listOf("real8", "real13", "real13_painted")
        // 實車車機存下來的原始四分割 → 自家 BEV：自己停在格子裡、格內漆著車位號碼「565」、
        // 格線緊貼自車排除區。合成場景生不出這種組合，但實車就是這樣（v0.4.4 之前這兩張是 0 格）。
        private val QUAD = listOf("quad_garage1", "quad_garage2")
        private val NEGATIVE = listOf("road", "scooter", "real8", "real13") + ADV_NEG

        /**
         * 每個 fixture 至少要正確找到幾格（真值入口兩角 ≤ 0.25 m、類型正確）。aisle_box_t 有兩格的分隔線被停著的車蓋住，
         * 量到的是車身側邊（差 0.25–0.33 m）；v256_egoperp_own 自車旁的分隔線只露出頭尾兩小段，角度差約 5°。
         */
        private val MIN_TP = mapOf(
            "aisle_box_t" to 7, "aisle_sep" to 7, "aisle_u" to 6, "egoperp" to 2, "parallel" to 1,
            "angled" to 3, "ahead" to 3, "v256_egoperp_stock" to 2, "real13_painted" to 3,
            // 對抗場景；adv_double 的真值角點在雙線中間，偵測取內側兩條線（寬 2.04 m 對 2.43 m，角點差 0.20–0.27 m），不計入
            "adv_xwalk" to 1, "adv_hatch" to 4, "adv_text" to 4, "adv_garage" to 1, "adv_rot" to 3, "adv_seam" to 4,
            "adv_blur" to 4, "adv_stopper" to 5, "adv_occl" to 4
        )

        private val cache = HashMap<String, Fix>()

        /**
         * 用 javax.imageio 解 PNG。單元測試的編譯 classpath 是 android.jar（沒有 javax.imageio），
         * 但執行時是一般 JVM，所以用反射呼叫。回傳 (w, h, ARGB)。
         */
        private fun decodePng(name: String): Triple<Int, Int, IntArray> {
            val io = Class.forName("javax.imageio.ImageIO")
            val img = ParkingSlotDetectorTest::class.java.getResourceAsStream(name)!!.use {
                io.getMethod("read", java.io.InputStream::class.java).invoke(null, it)
            }!!
            val cls = img.javaClass
            val w = cls.getMethod("getWidth").invoke(img) as Int
            val h = cls.getMethod("getHeight").invoke(img) as Int
            val ip = Int::class.javaPrimitiveType
            val argb = cls.getMethod("getRGB", ip, ip, ip, ip, IntArray::class.java, ip, ip)
                .invoke(img, 0, 0, w, h, null, 0, w) as IntArray
            return Triple(w, h, argb)
        }

        private fun floats(a: JSONArray): FloatArray = FloatArray(a.length()) { a.getDouble(it).toFloat() }

        private fun slots(a: JSONArray): List<ParkingSlot> = (0 until a.length()).map {
            val o = a.getJSONObject(it)
            ParkingSlot(floats(o.getJSONArray("p")), o.getString("kind")[0], o.getBoolean("occupied"), o.getDouble("conf").toFloat())
        }

        private fun load(name: String): Fix = cache.getOrPut(name) {
            val js = JSONObject(
                ParkingSlotDetectorTest::class.java.getResourceAsStream("/parking/$name.json")!!.bufferedReader().readText()
            )
            val (iw, ih, argb) = decodePng("/parking/$name.png")
            val w = js.getInt("w")
            val h = js.getInt("h")
            assertEquals(w, iw)
            assertEquals(h, ih)
            val ex = js.getJSONArray("exclude").let { a -> (0 until a.length()).map { floats(a.getJSONArray(it)) } }
            val ve = js.getJSONArray("vehicles").let { a -> (0 until a.length()).map { floats(a.getJSONArray(it)) } }
            val gt = js.getJSONArray("gt").let { a ->
                (0 until a.length()).map {
                    val o = a.getJSONObject(it)
                    val p = o.getJSONArray("p")
                    Gt(DoubleArray(8) { k -> p.getDouble(k) }, o.getString("kind")[0], o.getBoolean("occupied"), o.getBoolean("detectable"))
                }
            }
            Fix(name, argb, w, h, js.getDouble("mpp").toFloat(), js.getDouble("cx").toFloat(), js.getDouble("cy").toFloat(),
                ex, ve, gt, slots(js.getJSONArray("proto")))
        }

        private fun run(f: Fix, det: ParkingSlotDetector = ParkingSlotDetector()): List<ParkingSlot> =
            det.detect(f.argb, f.w, f.h, f.mpp, f.cx, f.cy, f.exclude, f.vehicles)

        private fun area(p: DoubleArray): Double {
            var s = 0.0
            for (k in 0 until 4) s += p[2 * k] * p[(2 * k + 3) % 8] - p[(2 * k + 2) % 8] * p[2 * k + 1]
            return 0.5 * s
        }

        private fun inside(p: DoubleArray, x: Double, z: Double): Boolean {
            val sg = if (area(p) >= 0) 1.0 else -1.0
            for (k in 0 until 4) {
                val ax = p[2 * k]; val az = p[2 * k + 1]
                val bx = p[(2 * k + 2) % 8]; val bz = p[(2 * k + 3) % 8]
                if (sg * ((bx - ax) * (z - az) - (bz - az) * (x - ax)) < 0) return false
            }
            return true
        }

        private fun centroid(p: DoubleArray) = doubleArrayOf((p[0] + p[2] + p[4] + p[6]) / 4, (p[1] + p[3] + p[5] + p[7]) / 4)

        private fun d(s: ParkingSlot) = DoubleArray(8) { s.p[it].toDouble() }

        /** 偵測與真值互相包含對方中心 → 同一格。 */
        private fun same(a: DoubleArray, b: DoubleArray): Boolean {
            val ca = centroid(a); val cb = centroid(b)
            return inside(b, ca[0], ca[1]) && inside(a, cb[0], cb[1])
        }

        /** 入口兩角的誤差（兩種對應取小）。 */
        private fun entErr(a: DoubleArray, g: DoubleArray): Double {
            val e1 = max(hypot(a[0] - g[0], a[1] - g[1]), hypot(a[2] - g[2], a[3] - g[3]))
            val e2 = max(hypot(a[0] - g[2], a[1] - g[3]), hypot(a[2] - g[0], a[3] - g[1]))
            return min(e1, e2)
        }

        /** 真值入口兩角到偵測四角中最近者的距離（不管偵測把哪一邊當入口）。 */
        private fun cornerErr(a: DoubleArray, g: DoubleArray): Double {
            var e = 0.0
            for (k in 0 until 2) {
                var m = Double.MAX_VALUE
                for (q in 0 until 4) m = min(m, hypot(a[2 * q] - g[2 * k], a[2 * q + 1] - g[2 * k + 1]))
                e = max(e, m)
            }
            return e
        }

        private class Score(val tp: Int, val fp: Int, val entOk: Int, val errs: List<Double>)

        /** 正確 = 對到可偵測的真值、類型對、入口兩角（幾何）誤差 ≤ 0.25 m；另外計入口邊選對的數量。 */
        private fun score(f: Fix, dets: List<ParkingSlot>): Score {
            var tp = 0
            var fp = 0
            var ent = 0
            val errs = ArrayList<Double>()
            val used = HashSet<Int>()
            for (s in dets) {
                val p = d(s)
                val gi = f.gt.indices.firstOrNull { same(p, f.gt[it].p) }
                if (gi == null) { fp++; continue }
                val g = f.gt[gi]
                if (!g.detectable || gi in used) continue
                used.add(gi)
                val e = cornerErr(p, g.p)
                errs.add(e)
                if (e <= 0.25 && s.kind == g.kind) tp++
                if (entErr(p, g.p) <= 0.25) ent++
            }
            return Score(tp, fp, ent, errs)
        }
    }

    @Test
    fun matchesPrototype() {
        for (name in SYNTH + REAL + QUAD) {
            val f = load(name)
            val out = run(f)
            assertEquals("$name: 格數與原型不同", f.proto.size, out.size)
            for (ps in f.proto) {
                val m = out.firstOrNull { k ->
                    k.kind == ps.kind && k.occupied == ps.occupied &&
                        (0 until 8).all { abs(k.p[it] - ps.p[it]) <= 0.02f } && abs(k.conf - ps.conf) <= 0.01f
                }
                if (m == null) fail("$name: 原型的 ${ps.kind} ${ps.p.joinToString()} 在 Kotlin 輸出中找不到：${out.map { it.p.joinToString() }}")
            }
        }
    }

    /**
     * 實車地下室：自己停在格子裡，格內漆著車位號碼。
     * 三個把這一格刷掉的原因（延長線假交叉、號碼被當成機車格 T 字、號碼灌爆格內雜線比例）
     * 只要回來任何一個，這裡就會變 0 格。
     */
    @Test
    fun realGarageSlotFound() {
        for (name in QUAD) {
            val f = load(name)
            val out = run(f)
            assertTrue("$name: 應該找得到自己停的那一格，實際 ${out.size} 格", out.isNotEmpty())
            val s = out.first()
            val xs = (0 until 4).map { s.p[it * 2] }
            val zs = (0 until 4).map { s.p[it * 2 + 1] }
            val wd = xs.max() - xs.min()
            val ln = zs.max() - zs.min()
            assertTrue("$name: 寬度 $wd 不像停車格", wd in 2.0f..3.2f)
            assertTrue("$name: 長度 $ln 不像停車格", ln in 4.5f..7.0f)
            assertTrue("$name: 自車就在格子裡，應該標成有車", s.occupied)
        }
    }

    @Test
    fun syntheticGroundTruth() {
        var tpAll = 0
        var fpAll = 0
        var entAll = 0
        val errs = ArrayList<Double>()
        for (name in SYNTH + listOf("real13_painted")) {
            val f = load(name)
            val out = run(f)
            val sc = score(f, out)
            val pr = score(f, f.proto)
            println("parking $name: ${out.size} 格，正確 ${sc.tp}（原型 ${pr.tp}），入口邊選對 ${sc.entOk}/${sc.errs.size}，誤報 ${sc.fp}")
            assertEquals("$name: 不應有誤報", 0, sc.fp)
            assertTrue("$name: 正確 ${sc.tp} < 原型 ${pr.tp}", sc.tp >= pr.tp)
            assertTrue("$name: 正確 ${sc.tp} < ${MIN_TP[name] ?: 0}", sc.tp >= (MIN_TP[name] ?: 0))
            tpAll += sc.tp
            fpAll += sc.fp
            entAll += sc.entOk
            errs.addAll(sc.errs)
        }
        errs.sort()
        val med = errs[errs.size / 2]
        val within = errs.count { it <= 0.25 }.toDouble() / errs.size
        println("parking 合計：正確 $tpAll，誤報 $fpAll，入口邊選對 $entAll/${errs.size}，" +
            "入口角點誤差中位數 ${"%.3f".format(med)} m，≤ 0.25 m 比例 ${"%.2f".format(within)}")
        assertTrue(med <= 0.05)
        assertTrue(within >= 0.85)
    }

    @Test
    fun negativesHaveNoSlots() {
        for (name in NEGATIVE) assertEquals("$name 不應偵測到停車格", 0, run(load(name)).size)
    }

    @Test
    fun realBackgroundPaintedRow() {
        val f = load("real13_painted")
        val out = run(f)
        assertEquals(3, out.size)
        for (g in f.gt) {
            val s = out.firstOrNull { same(d(it), g.p) } ?: error("找不到真值格 ${g.p.joinToString()}")
            assertEquals('p', s.kind)
            assertTrue("入口角點誤差 ${entErr(d(s), g.p)}", entErr(d(s), g.p) <= 0.1)
            assertTrue(!s.occupied)
        }
    }

    @Test
    fun outputConventions() {
        for (name in SYNTH + REAL) {
            val f = load(name)
            for (s in run(f)) {
                val p = d(s)
                assertEquals(8, s.p.size)
                assertTrue("$name: 角點須逆時針", area(p) > 0)
                assertTrue(s.kind in "pla")
                assertTrue(s.conf in 0f..1f)
                val ent = hypot(p[2] - p[0], p[3] - p[1])
                val side = hypot(p[4] - p[2], p[5] - p[3])
                if (s.kind == 'l') {
                    assertTrue("$name: 平行格入口邊 $ent", ent in 4.5..7.6)
                    assertTrue("$name: 平行格深度 $side", side in 1.7..3.1)
                } else {
                    assertTrue("$name: 入口邊 $ent", ent in 1.9..4.0)
                    assertTrue("$name: 深度 $side", side in 3.9..7.1)
                }
                // 被車輛框（或自車排除區）佔 > 30 % → occupied；這裡只檢查有車輛框覆蓋中心時一定是 occupied
                val c = centroid(p)
                val covered = (f.vehicles + f.exclude.filter { it[2] - it[0] >= 1.2f && it[3] - it[1] >= 1.2f }).any {
                    c[0] >= it[0] && c[0] <= it[2] && c[1] >= it[1] && c[1] <= it[3] &&
                        p.indices.step(2).all { k -> p[k] >= it[0] - 0.01 && p[k] <= it[2] + 0.01 && p[k + 1] >= it[1] - 0.01 && p[k + 1] <= it[3] + 0.01 }
                }
                if (covered) assertTrue("$name: 整格在車輛框內卻不是 occupied", s.occupied)
            }
        }
    }

    /** 對到真值的偵測，類型都要對（柱子擋住入口角的地下室、車頭斜對走道時不能變成斜角格）。 */
    @Test
    fun matchedSlotsHaveRightKind() {
        var n = 0
        for (name in SYNTH + listOf("real13_painted")) {
            val f = load(name)
            for (s in run(f)) {
                val g = f.gt.firstOrNull { same(d(s), it.p) } ?: continue
                assertEquals("$name: ${s.p.joinToString()} 類型", g.kind, s.kind)
                n++
            }
        }
        assertTrue(n >= 60)
    }

    /**
     * 只有自車（含原點的排除區）算佔用物：倒車時原廠導引線那種整塊排除區蓋到格子，格子仍是空的；
     * 同一塊換成車輛框才是 occupied。
     */
    @Test
    fun excludeRectIsNotOccupant() {
        val f = load("ahead")
        val base = run(f)
        val free = base.filter { !it.occupied }
        assertTrue(free.isNotEmpty())
        for (s in free) {
            val p = d(s)
            val xs = doubleArrayOf(p[0], p[2], p[4], p[6]); val zs = doubleArrayOf(p[1], p[3], p[5], p[7])
            // 格內（離四邊 ≥ 0.5 m）的一塊：不蓋到標線，面積 > 30 %
            val r = floatArrayOf((xs.min() + 0.5).toFloat(), (zs.min() + 0.6).toFloat(), (xs.max() - 0.5).toFloat(), (zs.max() - 0.6).toFloat())
            val ex = ParkingSlotDetector().detect(f.argb, f.w, f.h, f.mpp, f.cx, f.cy, f.exclude + listOf(r), f.vehicles)
            val m1 = ex.firstOrNull { same(d(it), p) } ?: error("加了排除區後找不到 ${p.joinToString()}")
            assertTrue("排除區（非自車）不應讓格子變成 occupied", !m1.occupied)
            val ve = ParkingSlotDetector().detect(f.argb, f.w, f.h, f.mpp, f.cx, f.cy, f.exclude, f.vehicles + listOf(r))
            val m2 = ve.firstOrNull { same(d(it), p) } ?: error("加了車輛框後找不到 ${p.joinToString()}")
            assertTrue("車輛框蓋 > 30 % 應為 occupied", m2.occupied)
        }
        // 自車在格內（自車排除區含原點）→ occupied
        val eg = load("egoperp")
        val around = run(eg).filter { s -> val c = centroid(d(s)); abs(c[0]) < 1.0 && abs(c[1]) < 1.0 }
        assertTrue(around.isNotEmpty() && around.all { it.occupied })
    }

    @Test
    fun reusesBuffersAcrossSizes() {
        val det = ParkingSlotDetector()
        val a = load("v256_egoperp_own")
        val b = load("aisle_box_t")
        val r1 = run(a, det)
        val r2 = run(b, det)
        val r3 = run(a, det)
        assertEquals(run(a), r1)
        assertEquals(run(b), r2)
        assertEquals(r1, r3)
    }

    @Test
    fun timing300x350() {
        val f = load("aisle_box_t")
        assertEquals(300, f.w)
        assertEquals(350, f.h)
        val det = ParkingSlotDetector()
        repeat(150) { run(f, det) }
        val ts = DoubleArray(50)
        for (k in ts.indices) {
            val t0 = System.nanoTime()
            run(f, det)
            ts[k] = (System.nanoTime() - t0) / 1e6
        }
        ts.sort()
        val neg = load("road")
        repeat(10) { run(neg, det) }
        val tn = DoubleArray(50)
        for (k in tn.indices) {
            val t0 = System.nanoTime()
            run(neg, det)
            tn[k] = (System.nanoTime() - t0) / 1e6
        }
        tn.sort()
        val prof = LongArray(5)
        det.profile = prof
        repeat(50) { run(f, det) }
        det.profile = null
        println("parking 各階段平均（ms）：遮罩 ${"%.2f".format(prof[0] / 50e6)}，線段 ${"%.2f".format(prof[1] / 50e6)}，" +
            "格子假設 ${"%.2f".format(prof[2] / 50e6)}，驗證 ${"%.2f".format(prof[3] / 50e6)}，其餘 ${"%.2f".format(prof[4] / 50e6)}")
        println("parking detect 300x350 暖機後中位數：停車場 ${"%.2f".format(ts[25])} ms（p90 ${"%.2f".format(ts[45])}），道路 ${"%.2f".format(tn[25])} ms")
        assertTrue("太慢：${ts[25]} ms", ts[25] < 40.0)
        // 最壞情況：每個 fixture 暖機後 15 次的中位數，取最大（整片紋理、斑馬線、雜線多的場景）
        var worst = 0.0
        var worstName = ""
        for (name in SYNTH + REAL) {
            val g = load(name)
            if (g.w != 300) continue
            repeat(5) { run(g, det) }
            val tw = DoubleArray(15)
            for (k in tw.indices) {
                val t0 = System.nanoTime()
                run(g, det)
                tw[k] = (System.nanoTime() - t0) / 1e6
            }
            tw.sort()
            if (tw[7] > worst) { worst = tw[7]; worstName = name }
        }
        println("parking detect 300x350 最慢的 fixture：$worstName ${"%.2f".format(worst)} ms")
        assertTrue("最壞情況太慢：$worstName $worst ms", worst < 80.0)
    }
}
