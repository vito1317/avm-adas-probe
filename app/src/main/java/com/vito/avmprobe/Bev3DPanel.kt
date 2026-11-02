package com.vito.avmprobe

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

/**
 * 3D 俯視面板：懸浮視窗裡的 WebView 跑 Three.js（assets/bev3d/）。
 * 偵測服務每幀呼叫 push() 送 JSON；面板內的校正滑桿透過 AndroidBridge 直接改 AppPrefs。
 */
class Bev3DPanel(private val context: Context, private val wm: WindowManager) {

    companion object { private const val TAG = "Bev3DPanel" }

    private val main = Handler(Looper.getMainLooper())
    private val prefs = AppPrefs(context)
    private var web: WebView? = null
    private var ready = false
    private var inFlight = false
    private var latest: String? = null   // 只留最新一幀，舊的直接丟

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun show(rectPx: Rect) {
        if (web != null) return
        WebView.setWebContentsDebuggingEnabled(true)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()

        val wv = WebView(ContextThemeWrapper(context, R.style.Theme_AvmProbe))
        wv.setBackgroundColor(Color.rgb(17, 23, 31))
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.addJavascriptInterface(Bridge(), "AndroidBridge")
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView, url: String) {
                ready = true
                drain()
            }
        }

        val lp = WindowManager.LayoutParams(
            rectPx.width(), rectPx.height(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = rectPx.left
        lp.y = rectPx.top
        try {
            wm.addView(wv, lp)
        } catch (t: Throwable) {
            Log.e(TAG, "3D 面板加入失敗：${t.message}")
            wv.destroy()
            return
        }
        wv.loadUrl("https://appassets.androidplatform.net/assets/bev3d/index.html")
        web = wv
    }

    /** 送一幀資料（任何執行緒皆可）。忙碌時只保留最新一幀。 */
    fun push(json: String) {
        main.post {
            latest = json
            drain()
        }
    }

    private fun drain() {
        val wv = web ?: return
        if (!ready || inFlight) return
        val j = latest ?: return
        latest = null
        inFlight = true
        wv.evaluateJavascript("window.setFrame&&window.setFrame($j)") {
            inFlight = false
            drain()
        }
    }

    fun hide() {
        main.post {
            web?.let {
                try { wm.removeView(it) } catch (_: Throwable) {}
                it.destroy()
            }
            web = null
            ready = false
        }
    }

    /** 給面板內校正 UI 用。 */
    inner class Bridge {
        @JavascriptInterface
        fun getPrefs(): String {
            val r = prefs.roi
            return JSONObject()
                .put("l", r.left.toDouble()).put("t", r.top.toDouble())
                .put("r", r.right.toDouble()).put("b", r.bottom.toDouble())
                .put("meters", prefs.bevMetersW.toDouble())
                .put("flip", prefs.bevFlip)
                .put("realGround", prefs.realGround)
                .put("roiOnly", prefs.roiOnly)
                .toString()
        }

        @JavascriptInterface
        fun setRoi(l: Float, t: Float, r: Float, b: Float) {
            if (r - l > 0.02f && b - t > 0.02f) prefs.roi = RectF(l, t, r, b)
        }

        @JavascriptInterface
        fun setMeters(m: Float) { prefs.bevMetersW = m.coerceIn(2f, 40f) }

        @JavascriptInterface
        fun setFlip(v: Boolean) { prefs.bevFlip = v }

        @JavascriptInterface
        fun setRealGround(v: Boolean) { prefs.realGround = v }

        @JavascriptInterface
        fun setRoiOnly(v: Boolean) { prefs.roiOnly = v }
    }
}
