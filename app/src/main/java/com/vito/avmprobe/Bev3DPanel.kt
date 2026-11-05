package com.vito.avmprobe

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager

/**
 * 截螢幕模式的 3D 面板：把 Bev3DWeb 放進懸浮視窗。
 * 偵測服務每幀呼叫 push() 送 JSON；面板內「存截圖」會回呼 onSaveCapture。
 */
class Bev3DPanel(
    private val context: Context,
    private val wm: WindowManager,
    private val onSaveCapture: (() -> Unit)? = null,
    private val onClose: (() -> Unit)? = null
) {
    companion object { private const val TAG = "Bev3DPanel" }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var web: Bev3DWeb? = null

    fun show(rectPx: Rect) {
        if (web != null) return
        val w = Bev3DWeb(context, "screen", onSaveCapture, onClose)
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
            wm.addView(w.view, lp)
        } catch (t: Throwable) {
            Log.e(TAG, "3D 面板加入失敗：${t.message}")
            w.destroy()
            return
        }
        web = w
    }

    /** 送一幀資料（任何執行緒皆可）。 */
    fun push(json: String) { web?.push(json) }

    fun notifySaved(text: String) { web?.notifySaved(text) }

    fun setWait(text: String) { web?.setWait(text) }

    fun hide() {
        main.post {
            web?.let {
                try { wm.removeView(it.view) } catch (_: Throwable) {}
                it.destroy()
            }
            web = null
        }
    }
}
