package com.vito.avmprobe

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup

/**
 * 擷取原廠 360 畫出來的畫面（自動對位用）。原廠的 bowl／俯視圖是原生程式畫在它畫面裡的
 * SurfaceView（或 TextureView）上 —— 合併版跟它在同一個 process，直接複製那塊畫面就好，
 * 不用螢幕錄影權限，也不會擷取到我們自己的標注或設定視窗（那些在別的圖層）。
 */
object FactoryScreen {

    /** 畫面裡最大的 SurfaceView／TextureView（原廠的繪圖面）。 */
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

    /**
     * 擷取原廠畫面（縮到寬約 [maxW]）並包成 [AutoCalibrator.Screen]。在主執行緒呼叫，結果也在主執行緒回呼。
     */
    fun capture(act: Activity, maxW: Int = 960, done: (AutoCalibrator.Screen?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        try {
            val decor = act.window?.decorView ?: return done(null)
            val v = findRenderView(decor) ?: return done(null)
            val loc = IntArray(2); v.getLocationOnScreen(loc)
            val k = minOf(1f, maxW.toFloat() / v.width)
            val bw = (v.width * k).toInt().coerceAtLeast(1); val bh = (v.height * k).toInt().coerceAtLeast(1)
            fun wrap(b: Bitmap?) {
                if (b == null) { done(null); return }
                val px = IntArray(b.width * b.height)
                b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
                done(AutoCalibrator.Screen(px, b.width, b.height, loc[0], loc[1], v.width, v.height, decor.width, decor.height))
            }
            when (v) {
                is TextureView -> wrap(v.getBitmap(bw, bh))
                is SurfaceView -> {
                    val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    PixelCopy.request(v, bmp, { r -> wrap(if (r == PixelCopy.SUCCESS) bmp else null) }, main)
                }
                else -> done(null)
            }
        } catch (_: Throwable) {
            done(null)
        }
    }
}
