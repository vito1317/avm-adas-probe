package com.vito.avmprobe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * 原廠俯視圖上的 3D 車模（從原廠畫面截下來），給行車記錄器的俯視圖當自車圖案，
 * 看起來跟原廠一樣（原廠車模檔 .vmd 只解得出頂點、沒有面，自己畫不好看）。
 *
 * 截法：原廠畫面截圖 + 這個視角的俯視對位 → 自車外框（再放大一點）那一塊。
 * 自動對位成功時順便存；設定裡也有按鈕可以手動更新。
 */
object EgoSprite {
    /** 截的範圍：自車半寬、半長再往外放（m）。行車記錄器照同樣的範圍貼回去。 */
    const val HW = AnnotationMapper.EGO_HW + 0.25f
    const val HL = AnnotationMapper.EGO_HL + 0.25f

    private fun file(ctx: Context) = File(ctx.filesDir, "ego_sprite.png")

    @Volatile private var cached: Bitmap? = null
    @Volatile private var cachedAt = 0L

    /** 從原廠畫面截下自車那一塊存起來；回傳結果說明。 */
    fun save(ctx: Context, scr: AutoCalibrator.Screen, top: AnnotationMapper.Params): String {
        val m = AnnotationMapper(top, scr.screenW, scr.screenH)
        // 螢幕像素 → 截圖像素
        fun bx(x: Float) = (x - scr.sx0) * scr.w / scr.sw
        fun by(y: Float) = (y - scr.sy0) * scr.h / scr.sh
        val xs = listOf(m.sx(-HW, HL), m.sx(HW, HL), m.sx(-HW, -HL), m.sx(HW, -HL)).map { bx(it) }
        val ys = listOf(m.sy(-HW, HL), m.sy(HW, HL), m.sy(-HW, -HL), m.sy(HW, -HL)).map { by(it) }
        val x0 = xs.min().toInt(); val x1 = xs.max().toInt(); val y0 = ys.min().toInt(); val y1 = ys.max().toInt()
        if (x0 < 0 || y0 < 0 || x1 > scr.w || y1 > scr.h || x1 - x0 < 8 || y1 - y0 < 16) return "車模在畫面外，沒存（先自動對位俯視圖）"
        val w = x1 - x0; val h = y1 - y0
        val px = IntArray(w * h)
        for (j in 0 until h) System.arraycopy(scr.argb, (y0 + j) * scr.w + x0, px, j * w, w)
        var bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        if (top.flip) bmp = Bitmap.createBitmap(bmp, 0, 0, w, h, android.graphics.Matrix().apply { postRotate(180f) }, true)
        file(ctx).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        cached = bmp; cachedAt = file(ctx).lastModified()
        return "已存原廠車模圖案（${w}×${h}），行車記錄器會用"
    }

    /** 存過的車模圖案（沒有回 null）。 */
    fun get(ctx: Context): Bitmap? {
        val f = file(ctx)
        if (!f.exists()) return null
        if (cached == null || cachedAt != f.lastModified()) {
            cached = try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Throwable) { null }
            cachedAt = f.lastModified()
        }
        return cached
    }
}
