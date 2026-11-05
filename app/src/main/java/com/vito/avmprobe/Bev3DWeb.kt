package com.vito.avmprobe

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

/**
 * 跑 Three.js 3D 面板（assets/bev3d/）的 WebView。懸浮視窗（截螢幕模式）和鏡頭直讀畫面共用。
 * push() 送一幀 JSON；面板內的按鈕/校正透過 AndroidBridge 直接改 AppPrefs。
 * 必須在主執行緒建立。
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
class Bev3DWeb(
    private val context: Context,
    private val src: String,                       // "screen" | "cam"
    private val onSaveCapture: (() -> Unit)? = null,
    private val onClose: (() -> Unit)? = null      // 有給才在頁面上顯示「關閉」鈕（B 模式懸浮窗用）
) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = AppPrefs(context)
    private var ready = false
    private var inFlight = false
    private var latest: String? = null   // 只留最新一幀，舊的直接丟
    private var destroyed = false

    val view: WebView

    init {
        WebView.setWebContentsDebuggingEnabled(true)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()

        view = WebView(ContextThemeWrapper(context, R.style.Theme_AvmProbe))
        view.setBackgroundColor(Color.rgb(17, 23, 31))
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.addJavascriptInterface(Bridge(), "AndroidBridge")
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView, url: String) {
                ready = true
                drain()
            }
        }
        view.loadUrl("https://appassets.androidplatform.net/assets/bev3d/index.html")
    }

    /** 送一幀資料（任何執行緒皆可）。忙碌時只保留最新一幀。 */
    fun push(json: String) {
        main.post {
            latest = json
            drain()
        }
    }

    /** 還沒有影格時，在「等待」提示上顯示診斷文字（B 模式用）。 */
    fun setWait(text: String) {
        main.post {
            if (ready) view.evaluateJavascript("window.setWait&&window.setWait(${JSONObject.quote(text)})", null)
        }
    }

    /** 面板內「存截圖」完成後回報結果文字。 */
    fun notifySaved(text: String) {
        main.post {
            if (ready) view.evaluateJavascript("window.onSaved&&window.onSaved(${JSONObject.quote(text)})", null)
        }
    }

    private fun drain() {
        if (!ready || inFlight || destroyed) return
        val j = latest ?: return
        latest = null
        inFlight = true
        view.evaluateJavascript("window.setFrame&&window.setFrame($j)") {
            inFlight = false
            drain()
        }
    }

    fun destroy() {
        main.post {
            destroyed = true
            ready = false
            view.destroy()
        }
    }

    /** 給面板內按鈕/校正 UI 用。 */
    inner class Bridge {
        @JavascriptInterface
        fun getPrefs(): String {
            val r = prefs.roi
            return JSONObject()
                .put("l", r.left.toDouble()).put("t", r.top.toDouble())
                .put("r", r.right.toDouble()).put("b", r.bottom.toDouble())
                .put("meters", prefs.bevMetersW.toDouble())
                .put("flip", prefs.bevFlip)
                .put("realGround", prefs.groundMode == AppPrefs.GROUND_REAL)
                .put("groundMode", prefs.groundMode)
                .put("roiOnly", prefs.roiOnly)
                .put("src", src)
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
        fun setRealGround(v: Boolean) {
            // 新版頁面會先呼叫 setGroundMode；這裡只處理舊頁面
            if (v) prefs.groundMode = AppPrefs.GROUND_REAL
            else if (prefs.groundMode == AppPrefs.GROUND_REAL) prefs.groundMode = AppPrefs.GROUND_OFF
        }

        @JavascriptInterface
        fun setGroundMode(mode: String) {
            if (mode == AppPrefs.GROUND_REAL || mode == AppPrefs.GROUND_VIRTUAL || mode == AppPrefs.GROUND_OFF) {
                prefs.groundMode = mode
            }
        }

        @JavascriptInterface
        fun setRoiOnly(v: Boolean) { prefs.roiOnly = v }

        @JavascriptInterface
        fun canClose(): Boolean = onClose != null

        @JavascriptInterface
        fun close() { onClose?.let { main.post(it) } }

        @JavascriptInterface
        fun saveCapture() {
            val cb = onSaveCapture
            if (cb == null) notifySaved("此模式不支援存截圖") else cb()
        }
    }
}
