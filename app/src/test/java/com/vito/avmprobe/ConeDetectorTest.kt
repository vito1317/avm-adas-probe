package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class ConeDetectorTest {
    private val F = PerceptionFixtures

    private fun run(name: String): Pair<org.json.JSONObject, List<Cone>> {
        val doc = F.json("$name.json")
        val img = F.image("$name.png")
        val c = ConeDetector().detect(
            img.argb, img.w, img.h, doc.getDouble("mpp").toFloat(),
            doc.getDouble("cx").toFloat(), doc.getDouble("cy").toFloat(), F.exclude(doc)
        )
        return doc to c
    }

    @Test
    fun matchesGroundTruth() {
        var nGt = 0
        for (name in F.roadNames()) {
            val (doc, cones) = run(name)
            val gt = F.objs(doc.getJSONObject("gt").getJSONArray("cones"))
            assertEquals("$name 錐數 (Kotlin $cones)", gt.size, cones.size)
            val used = BooleanArray(cones.size)
            for (g in gt) {
                nGt++
                var bi = -1
                var be = Double.MAX_VALUE
                for ((i, c) in cones.withIndex()) {
                    val e = hypot(c.x - g.getDouble("x"), c.z - g.getDouble("z"))
                    if (!used[i] && e < be) { be = e; bi = i }
                }
                assertTrue("$name GT 錐 $g 最近的偵測差 $be m", bi >= 0 && be <= 0.3)
                used[bi] = true
            }
        }
        assertTrue("fixture 錐太少：$nGt", nGt >= 10)
    }

    @Test
    fun matchesPythonPrototype() {
        for (name in F.roadNames()) {
            val (doc, cones) = run(name)
            val py = F.objs(doc.getJSONObject("py").getJSONArray("cones"))
            assertEquals("$name 錐數", py.size, cones.size)
            for ((i, p) in py.withIndex()) {
                assertEquals("$name[$i] x", p.getDouble("x"), cones[i].x.toDouble(), 0.01)
                assertEquals("$name[$i] z", p.getDouble("z"), cones[i].z.toDouble(), 0.01)
                assertEquals("$name[$i] conf", p.getDouble("c"), cones[i].conf.toDouble(), 0.02)
            }
        }
    }

    @Test
    fun noConesOnPlainRoad() {
        // 紅色路緣漆、黃線、紅褐地磚都不能當成三角錐
        for (name in F.roadNames().filter { !it.contains("cones") }) {
            val (_, cones) = run(name)
            assertTrue("$name 不該有錐：$cones", cones.isEmpty())
        }
    }

    @Test
    fun timing300x350() {
        val doc = F.json("road_cones_0.json")
        val img = F.image("road_cones_0.png")
        val ex = F.exclude(doc)
        val mpp = doc.getDouble("mpp").toFloat()
        val cx = doc.getDouble("cx").toFloat()
        val cy = doc.getDouble("cy").toFloat()
        val det = ConeDetector()
        repeat(30) { det.detect(img.argb, img.w, img.h, mpp, cx, cy, ex) }
        val t = ArrayList<Double>()
        val a0 = F.allocated()
        repeat(50) {
            val s = System.nanoTime()
            det.detect(img.argb, img.w, img.h, mpp, cx, cy, ex)
            t.add((System.nanoTime() - s) / 1e6)
        }
        val alloc = if (a0 >= 0) (F.allocated() - a0) / 50 else -1
        val med = F.median(t)
        println("ConeDetector.detect %dx%d：中位數 %.2f ms（50 次，暖機 30 次），每次配置約 %d bytes".format(img.w, img.h, med, alloc))
        assertTrue("太慢：$med ms", med < 50.0)
    }
}
