package com.vito.avmprobe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCapture"
        private const val CH_ID = "avm_probe"
        private const val NOTI_ID = 1001
        private const val MAX_CAP_DIM = 1280   // 截圖長邊上限，降頻寬

        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var yolo: Yolo? = null

    private lateinit var windowManager: WindowManager
    private var overlay: OverlayView? = null

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var screenW = 0
    private var screenH = 0
    private var capW = 0
    private var capH = 0

    @Volatile private var busy = false
    private var emaInfer = 0f
    private var emaE2e = 0f
    private var lastFrameNs = 0L
    private var emaFps = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTI_ID, buildNotification())

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (code == 0 || data == null) {
            Log.e(TAG, "缺少 MediaProjection 授權")
            stopSelf()
            return START_NOT_STICKY
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        computeScreenSize()

        thread = HandlerThread("capture").also { it.start() }
        handler = Handler(thread!!.looper)

        try {
            yolo = Yolo(this)
        } catch (t: Throwable) {
            Log.e(TAG, "模型載入失敗（assets/model.tflite 是否存在？）：${t.message}", t)
            showOverlay()
            overlay?.update(emptyList(), "模型載入失敗：\n請確認 assets/model.tflite\n${t.message}")
            return START_STICKY
        }

        showOverlay()

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data).also { mp ->
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped")
                    stopSelf()
                }
            }, handler)
        }

        startCapture()
        return START_STICKY
    }

    private fun computeScreenSize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            screenW = b.width(); screenH = b.height()
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            screenW = dm.widthPixels; screenH = dm.heightPixels
        }
        val scale = min(1f, MAX_CAP_DIM.toFloat() / max(screenW, screenH))
        capW = (screenW * scale).toInt().coerceAtLeast(2)
        capH = (screenH * scale).toInt().coerceAtLeast(2)
        // 寬高對齊偶數
        capW -= capW % 2; capH -= capH % 2
        Log.i(TAG, "screen=${screenW}x$screenH capture=${capW}x$capH")
    }

    private fun startCapture() {
        val dm = resources.displayMetrics
        imageReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    if (busy) { image.close(); return@setOnImageAvailableListener }
                    busy = true
                    val e2e0 = System.nanoTime()

                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * capW
                    val bmpW = capW + rowPadding / pixelStride

                    val bmp = Bitmap.createBitmap(bmpW, capH, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(buffer)
                    image.close()

                    // 裁掉 rowStride padding
                    val frame = if (bmpW != capW) Bitmap.createBitmap(bmp, 0, 0, capW, capH) else bmp

                    val (dets, inferMs) = yolo!!.detect(frame)

                    // 截圖座標 → 螢幕座標
                    val sx = screenW.toFloat() / capW
                    val sy = screenH.toFloat() / capH
                    val boxes = dets.map {
                        val r = RectF(it.rect.left * sx, it.rect.top * sy, it.rect.right * sx, it.rect.bottom * sy)
                        r to "${it.label} ${(it.score * 100).toInt()}%"
                    }

                    val e2eMs = (System.nanoTime() - e2e0) / 1_000_000
                    updateFps()
                    emaInfer = if (emaInfer == 0f) inferMs.toFloat() else emaInfer * 0.8f + inferMs * 0.2f
                    emaE2e = if (emaE2e == 0f) e2eMs.toFloat() else emaE2e * 0.8f + e2eMs * 0.2f

                    val stats = "引擎 ${yolo!!.engineName}  截圖 ${capW}x$capH\n" +
                        "推論 ${emaInfer.toInt()}ms  端到端 ${emaE2e.toInt()}ms\n" +
                        "FPS ${"%.1f".format(emaFps)}  偵測 ${dets.size} 台"
                    overlay?.update(boxes, stats)

                    if (frame !== bmp) bmp.recycle()
                    frame.recycle()
                } catch (t: Throwable) {
                    Log.e(TAG, "frame 處理失敗：${t.message}", t)
                    try { image.close() } catch (_: Throwable) {}
                } finally {
                    busy = false
                }
            }, handler)
        }

        virtualDisplay = projection?.createVirtualDisplay(
            "avm-cap",
            capW, capH, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, handler
        )
    }

    private fun updateFps() {
        val now = System.nanoTime()
        if (lastFrameNs != 0L) {
            val fps = 1_000_000_000f / (now - lastFrameNs)
            emaFps = if (emaFps == 0f) fps else emaFps * 0.8f + fps * 0.2f
        }
        lastFrameNs = now
    }

    private fun showOverlay() {
        if (overlay != null) return
        overlay = OverlayView(this)
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        try {
            windowManager.addView(overlay, lp)
        } catch (t: Throwable) {
            Log.e(TAG, "懸浮窗加入失敗（是否授予 SYSTEM_ALERT_WINDOW？）：${t.message}")
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CH_ID, "AVM Probe", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        return Notification.Builder(this, CH_ID)
            .setContentTitle("AVM ADAS Probe 執行中")
            .setContentText("截螢幕偵測周遭車輛")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        try { imageReader?.close() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        try { yolo?.close() } catch (_: Throwable) {}
        overlay?.let { try { windowManager.removeView(it) } catch (_: Throwable) {} }
        overlay = null
        thread?.quitSafely()
    }
}
