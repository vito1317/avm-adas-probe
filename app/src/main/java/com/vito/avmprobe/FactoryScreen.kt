package com.vito.avmprobe

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup

/**
 * 擷取原廠 360 畫出來的畫面（自動對位、存原始影格用）。依序試：
 *
 * 1. Activity 畫面樹裡最大的 SurfaceView／TextureView → PixelCopy／getBitmap（只有原廠畫面，最乾淨）
 * 2. 這個 process 所有視窗（原廠可能把繪圖面開在另一個視窗）
 * 3. 系統截圖 SurfaceControl.screenshot：原廠 360 用平台金鑰簽、system uid，有權限也不受隱藏 API 限制。
 *    原生程式自己建圖層（不是 View）時只有這招。截的是整個螢幕，所以先把我們的標注、設定視窗藏起來。
 *
 * 全部失敗會在 [lastError] 留原因（含畫面樹裡看到什麼），顯示在診斷文字裡。
 */
object FactoryScreen {

    @Volatile var lastError: String = ""
        private set

    /** 畫面裡最大的 SurfaceView／TextureView。 */
    fun findRenderView(root: View): View? {
        var best: View? = null; var area = 0
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE || v.width <= 0 || v.height <= 0) return
            if (v is SurfaceView || v is TextureView) {
                val a = v.width * v.height
                if (a > area) { area = a; best = v }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return best
    }

    /** 這個 process 所有視窗的根 View（WindowManagerGlobal.mViews，隱藏 API；system uid 可用）。 */
    private fun allRoots(): List<View> = try {
        val g = Class.forName("android.view.WindowManagerGlobal")
        val inst = g.getMethod("getInstance").invoke(null)
        val f = g.getDeclaredField("mViews").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (f.get(inst) as? List<View>)?.toList() ?: emptyList()
    } catch (_: Throwable) { emptyList() }

    /** 畫面樹摘要（診斷用）：有哪些繪圖面、各多大。 */
    fun describe(act: Activity): String {
        val sb = StringBuilder()
        val roots = (listOfNotNull(act.window?.decorView) + allRoots()).distinct()
        sb.append("視窗 ${roots.size} 個；")
        for (r in roots) {
            fun walk(v: View) {
                if (v is SurfaceView || v is TextureView) sb.append("${v.javaClass.simpleName} ${v.width}x${v.height}${if (v.visibility == View.VISIBLE) "" else "(隱藏)"}；")
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(r)
        }
        return sb.toString()
    }

    /**
     * 擷取原廠畫面（縮到寬約 [maxW]）。在主執行緒呼叫，結果也在主執行緒回呼。
     * [hide] 是截圖前要先藏起來的我們自己的東西（標注圖層、設定視窗），截完會恢復。
     */
    fun capture(act: Activity, maxW: Int = 960, hide: List<View> = emptyList(), done: (AutoCalibrator.Screen?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val decor = act.window?.decorView ?: run { lastError = "沒有畫面"; return done(null) }
        val sw = decor.width; val sh = decor.height
        val views = (listOf(decor) + allRoots()).distinct().mapNotNull { findRenderView(it) }
        val v = views.maxByOrNull { it.width * it.height }

        fun fromBitmap(b: Bitmap?, x0: Int, y0: Int, w: Int, h: Int): AutoCalibrator.Screen? {
            if (b == null) return null
            val bmp = if (b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            return AutoCalibrator.Screen(px, bmp.width, bmp.height, x0, y0, w, h, sw, sh)
        }

        // 3. 系統截圖（整個螢幕）
        fun systemShot() {
            val restore = hide.map { it to it.visibility }
            for (h in hide) h.visibility = View.INVISIBLE
            // 等一幀讓隱藏生效再截
            main.postDelayed({
                val shot = try { screenshot(sw, sh, maxW) } catch (t: Throwable) { lastError = "系統截圖失敗：$t"; null }
                for ((h, vis) in restore) h.visibility = vis
                val scr = fromBitmap(shot, 0, 0, sw, sh)
                if (scr == null && lastError.isEmpty()) lastError = "系統截圖回傳空的"
                if (scr == null) lastError += "｜" + describe(act)
                done(scr)
            }, 120)
        }

        if (v == null) { lastError = "畫面樹裡沒有繪圖面，改用系統截圖"; systemShot(); return }
        try {
            val loc = IntArray(2); v.getLocationOnScreen(loc)
            val k = minOf(1f, maxW.toFloat() / v.width)
            val bw = (v.width * k).toInt().coerceAtLeast(1); val bh = (v.height * k).toInt().coerceAtLeast(1)
            when (v) {
                is TextureView -> {
                    val scr = fromBitmap(v.getBitmap(bw, bh), loc[0], loc[1], v.width, v.height)
                    if (scr != null && !blank(scr)) done(scr) else { lastError = "TextureView 取不到內容"; systemShot() }
                }
                is SurfaceView -> {
                    val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    PixelCopy.request(v, bmp, { r ->
                        val scr = if (r == PixelCopy.SUCCESS) fromBitmap(bmp, loc[0], loc[1], v.width, v.height) else null
                        // 原生程式另外建圖層時，SurfaceView 本身是空的（全黑）→ 改系統截圖
                        if (scr != null && !blank(scr)) done(scr) else { lastError = "SurfaceView 複製失敗（$r）或是空的"; systemShot() }
                    }, main)
                }
                else -> systemShot()
            }
        } catch (t: Throwable) {
            lastError = "擷取失敗：$t"; systemShot()
        }
    }

    /** 幾乎全黑（沒內容）。 */
    private fun blank(s: AutoCalibrator.Screen): Boolean {
        var bright = 0
        var i = 0
        while (i < s.argb.size) {
            val c = s.argb[i]
            if (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF) > 60) bright++
            i += 97
        }
        return bright < s.argb.size / 97 / 20
    }

    /**
     * SurfaceControl.screenshot（隱藏 API，各版本簽名不同，依序試）。需要 READ_FRAME_BUFFER，
     * 原廠 360 是 system uid、平台簽章，有這個權限也不受隱藏 API 限制。
     */
    private fun screenshot(w: Int, h: Int, maxW: Int): Bitmap? {
        val k = minOf(1f, maxW.toFloat() / w)
        val ow = (w * k).toInt(); val oh = (h * k).toInt()
        val sc = Class.forName("android.view.SurfaceControl")
        val I = Int::class.javaPrimitiveType!!
        val Z = Boolean::class.javaPrimitiveType!!
        val tries: List<() -> Any?> = listOf(
            { sc.getMethod("screenshot", Rect::class.java, I, I, I).invoke(null, Rect(0, 0, w, h), ow, oh, 0) },
            { sc.getMethod("screenshot", Rect::class.java, I, I, Z, I).invoke(null, Rect(0, 0, w, h), ow, oh, false, 0) },
            { sc.getMethod("screenshot", I, I).invoke(null, ow, oh) }
        )
        var err: Throwable? = null
        for (t in tries) {
            try { (t() as? Bitmap)?.let { return it } } catch (e: Throwable) { err = e }
        }
        if (err != null) throw err
        return null
    }
}
