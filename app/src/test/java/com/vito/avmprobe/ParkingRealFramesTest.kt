package com.vito.avmprobe

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 實車漏抓／誤判過的畫面（俯視圖已存成 PNG），修好之後鎖住。
 *
 * lot_left_empty：露天停車場，自車停在格內，左邊一格空位。跟隔壁格共用的分隔線緊貼自車，
 * 大半被自己的車身擋住（只看得到車頭旁 1 m），以前因此湊不成格子而漏掉。
 */
class ParkingRealFramesTest {

    private fun load(name: String): Pair<JSONObject, Triple<Int, Int, IntArray>> {
        val js = JSONObject(javaClass.getResourceAsStream("/parking_real/$name.json")!!.bufferedReader().readText())
        val io = Class.forName("javax.imageio.ImageIO")
        val img = javaClass.getResourceAsStream("/parking_real/$name.png")!!.use {
            io.getMethod("read", java.io.InputStream::class.java).invoke(null, it)
        }!!
        val c = img.javaClass; val I = Int::class.javaPrimitiveType
        val w = c.getMethod("getWidth").invoke(img) as Int; val h = c.getMethod("getHeight").invoke(img) as Int
        val argb = c.getMethod("getRGB", I, I, I, I, IntArray::class.java, I, I).invoke(img, 0, 0, w, h, null, 0, w) as IntArray
        return js to Triple(w, h, argb)
    }

    private fun rects(js: JSONObject, k: String) = js.getJSONArray(k).let { a ->
        (0 until a.length()).map { i -> val r = a.getJSONArray(i); FloatArray(r.length()) { r.getDouble(it).toFloat() } }
    }

    @Test
    fun emptySlotBesideEgoWithOccludedSharedLine() {
        val (js, img) = load("lot_left_empty")
        val (w, h, argb) = img
        val slots = ParkingSlotDetector().detect(
            argb, w, h, js.getDouble("mpp").toFloat(), js.getDouble("cx").toFloat(), js.getDouble("cy").toFloat(),
            rects(js, "exclude"), rects(js, "vehicles")
        )
        val e = js.getJSONObject("expect")
        val hit = slots.firstOrNull { s ->
            val xs = (0 until 4).map { s.p[it * 2] }; val zs = (0 until 4).map { s.p[it * 2 + 1] }
            s.kind == e.getString("kind")[0] && s.occupied == e.getBoolean("occupied") &&
                xs.min() >= e.getDouble("xMin") && xs.max() <= e.getDouble("xMax") &&
                zs.min() >= e.getDouble("zMin") && zs.max() <= e.getDouble("zMax") &&
                xs.max() - xs.min() in 2.0f..3.0f && zs.max() - zs.min() in 4.2f..5.8f
        }
        assertTrue("左邊那格空位要認得出來，實際輸出：" + slots.joinToString { it.kind + it.p.joinToString(",") { v -> "%.2f".format(v) } }, hit != null)
    }
}
