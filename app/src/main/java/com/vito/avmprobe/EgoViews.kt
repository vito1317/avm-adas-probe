package com.vito.avmprobe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import org.json.JSONObject
import kotlin.math.abs

/**
 * 自車 E60 的多角度渲染圖（tools/render/e60_atlas.py：方位每 15°、俯角 10/20/35/60/89°，正交、每像素 0.012 m）。
 * 行車記錄器右半邊重建原廠 3D 視角時，挑最接近的角度貼在自車位置（不用在車機上跑 3D）。
 */
object EgoViews {
    private class V(val az: Int, val el: Int, val file: String, val ox: Float, val oy: Float)
    private var views: List<V>? = null
    private var mpp = 0.012f
    private val cache = HashMap<String, Bitmap?>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private fun load(ctx: Context): List<V> {
        views?.let { return it }
        val list = try {
            val j = JSONObject(ctx.assets.open("e60_views/index.json").bufferedReader().readText())
            mpp = j.optDouble("mpp", 0.012).toFloat()
            val a = j.getJSONArray("views")
            List(a.length()) { i -> a.getJSONObject(i).let { V(it.getInt("az"), it.getInt("el"), it.getString("file"), it.getDouble("ox").toFloat(), it.getDouble("oy").toFloat()) } }
        } catch (_: Throwable) { emptyList() }
        views = list
        return list
    }

    /**
     * 在 [c] 上把 E60 畫在自車位置：(x, y) = 地面原點在畫布上的位置、[pxPerM] = 每公尺像素、視角方位／俯角（度）、要不要左右翻。
     */
    fun draw(ctx: Context, c: Canvas, x: Float, y: Float, pxPerM: Float, azDeg: Float, elDeg: Float, mirror: Boolean): Boolean {
        val vs = load(ctx); if (vs.isEmpty()) return false
        fun angDiff(a: Float, b: Float): Float { var d = (a - b) % 360f; if (d > 180f) d -= 360f; if (d < -180f) d += 360f; return abs(d) }
        val best = vs.minByOrNull { angDiff(azDeg, it.az.toFloat()) + 1.5f * abs(elDeg - it.el) } ?: return false
        val bmp = cache.getOrPut(best.file) { try { ctx.assets.open("e60_views/" + best.file).use { BitmapFactory.decodeStream(it) } } catch (_: Throwable) { null } } ?: return false
        val sc = pxPerM * mpp
        val l = x - best.ox * sc; val t = y - best.oy * sc
        val dst = RectF(l, t, l + bmp.width * sc, t + bmp.height * sc)
        if (mirror) { c.save(); c.scale(-1f, 1f, x, y); c.drawBitmap(bmp, null, dst, paint); c.restore() }
        else c.drawBitmap(bmp, null, dst, paint)
        return true
    }
}
