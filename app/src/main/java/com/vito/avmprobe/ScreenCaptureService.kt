package com.vito.avmprobe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
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
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 截「全景360」畫面 → 只對俯視區(ROI)做偵測 → 換算成公尺座標 → 送進 3D 面板，
 * 同時在螢幕上畫 2D 框 / ROI 校正框。
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCapture"
        private const val CH_ID = "avm_probe"
        private const val NOTI_ID = 1001
        private const val MAX_CAP_DIM = 1280   // 截圖長邊上限
        private const val GROUND_W = 256       // 實景地面貼圖寬度(px)

        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
    }

    private lateinit var prefs: AppPrefs
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var yolo: Yolo? = null

    private lateinit var windowManager: WindowManager
    private var overlay: OverlayView? = null
    private var panel: Bev3DPanel? = null
    private var panelCap: Rect? = null       // 3D 面板在截圖座標中的範圍

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var screenW = 0
    private var screenH = 0
    private var capW = 0
    private var capH = 0

    // 每幀重用的緩衝
    private var capBmp: Bitmap? = null
    private var groundBmp: Bitmap? = null
    private val groundCanvas = Canvas()
    private val groundPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val jpegBuf = ByteArrayOutputStream(64 * 1024)

    @Volatile private var busy = false
    private var emaInfer = 0f
    private var emaE2e = 0f
    private var emaFps = 0f
    private var lastFrameNs = 0L

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
        if (projection != null) return START_STICKY

        prefs = AppPrefs(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        computeScreenSize()

        thread = HandlerThread("capture").also { it.start() }
        handler = Handler(thread!!.looper)

        showPanel()     // 先加 3D 面板，再加全螢幕 2D 層（不可觸控，觸控會穿到面板）
        showOverlay()

        try {
            yolo = Yolo(this, prefs.model, useGpu = prefs.useGpu)
        } catch (t: Throwable) {
            Log.e(TAG, "模型載入失敗：${t.message}", t)
            overlay?.update(emptyList(), "模型載入失敗：${prefs.model}\n${t.message}")
            return START_STICKY
        }

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
        capW -= capW % 2; capH -= capH % 2
        Log.i(TAG, "screen=${screenW}x$screenH capture=${capW}x$capH")
    }

    private fun startCapture() {
        val dm = resources.displayMetrics
        imageReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ reader -> processFrame(reader) }, handler)
        }
        virtualDisplay = projection?.createVirtualDisplay(
            "avm-cap", capW, capH, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, handler
        )
    }

    private fun processFrame(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        val det = yolo
        if (busy || det == null) { image.close(); return }
        busy = true
        try {
            val e2e0 = System.nanoTime()

            // 截圖 → 重用的 bitmap（rowStride 的 padding 欄位不會被讀到，ROI 一律在 capW 內）
            val plane = image.planes[0]
            val bmpW = plane.rowStride / plane.pixelStride
            var bmp = capBmp
            if (bmp == null || bmp.width != bmpW || bmp.height != capH) {
                bmp?.recycle()
                bmp = Bitmap.createBitmap(bmpW, capH, Bitmap.Config.ARGB_8888)
                capBmp = bmp
            }
            bmp.copyPixelsFromBuffer(plane.buffer)
            image.close()

            val roiF = prefs.roi
            val roiCap = Rect(
                (roiF.left * capW).toInt().coerceIn(0, capW - 2),
                (roiF.top * capH).toInt().coerceIn(0, capH - 2),
                (roiF.right * capW).toInt().coerceIn(2, capW),
                (roiF.bottom * capH).toInt().coerceIn(2, capH)
            )
            val roiValid = roiCap.width() > 8 && roiCap.height() > 8
            val detRect = if (prefs.roiOnly && roiValid) roiCap else Rect(0, 0, capW, capH)

            val (dets, inferMs) = det.detect(bmp, detRect)

            val sx = screenW.toFloat() / capW
            val sy = screenH.toFloat() / capH
            val pnl = panelCap
            val panelOn = panel != null
            val wm = prefs.bevMetersW
            val hm = if (roiValid) wm * roiCap.height() / roiCap.width() else wm
            val flip = prefs.bevFlip

            val boxes = ArrayList<Pair<RectF, String>>()
            val objs = JSONArray()
            for (d in dets) {
                val cx = d.rect.centerX()
                val cy = d.rect.centerY()
                if (pnl != null && pnl.contains(cx.toInt(), cy.toInt())) continue   // 自己的 3D 面板
                val inRoi = roiValid && roiCap.contains(cx.toInt(), cy.toInt())
                if (inRoi) {
                    // 俯視圖本身就是地面平面（全景360 已做 IPM）→ 像素直接換公尺
                    val mpp = wm / roiCap.width()
                    var x = (cx - roiCap.exactCenterX()) * mpp
                    var z = (roiCap.exactCenterY() - cy) * mpp     // 畫面上方 = 前方
                    if (flip) { x = -x; z = -z }
                    if (abs(x) < 1.1f && abs(z) < 2.5f) continue      // 俯視圖正中央是自車本身
                    objs.put(
                        JSONObject()
                            .put("t", typeOf(d.classId))
                            .put("x", r2(x)).put("z", r2(z))
                            .put("w", r2(d.rect.width() * mpp))
                            .put("h", r2(d.rect.height() * mpp))
                            .put("c", r2(d.score))
                    )
                    if (panelOn) continue   // 俯視區的物件交給 3D 面板顯示
                }
                boxes.add(
                    RectF(d.rect.left * sx, d.rect.top * sy, d.rect.right * sx, d.rect.bottom * sy) to
                        "${d.label} ${(d.score * 100).toInt()}%"
                )
            }

            val e2eMs = (System.nanoTime() - e2e0) / 1_000_000
            updateFps()
            emaInfer = ema(emaInfer, inferMs.toFloat())
            emaE2e = ema(emaE2e, e2eMs.toFloat())

            val overlap = pnl != null && roiValid && Rect.intersects(pnl, roiCap)
            val roiScreen = if (roiValid)
                RectF(roiCap.left * sx, roiCap.top * sy, roiCap.right * sx, roiCap.bottom * sy) else null
            val stats = "引擎 ${det.engineName}  輸入 ${det.inputSize}\n" +
                "推論 ${emaInfer.toInt()}ms  端到端 ${emaE2e.toInt()}ms\n" +
                "FPS ${"%.1f".format(emaFps)}  偵測 ${dets.size}" +
                if (overlap) "\n⚠ 3D 面板擋到俯視區" else ""
            overlay?.update(boxes, if (panelOn) "" else stats, roiScreen)

            panel?.let { p ->
                val payload = JSONObject()
                    .put("objs", objs)
                    .put("wm", r2(wm)).put("hm", r2(hm)).put("flip", flip)
                    .put("overlap", overlap)
                    .put("stat", JSONObject()
                        .put("fps", r2(emaFps)).put("inf", emaInfer.toInt())
                        .put("e2e", emaE2e.toInt())
                        .put("eng", det.engineName).put("res", det.inputSize))
                if (prefs.realGround && roiValid) {
                    groundDataUrl(bmp, roiCap)?.let { payload.put("ground", it) }
                }
                p.push(payload.toString())
            }
        } catch (t: Throwable) {
            Log.e(TAG, "frame 處理失敗：${t.message}", t)
            try { image.close() } catch (_: Throwable) {}
        } finally {
            busy = false
        }
    }

    /** 把俯視區縮成小 JPEG 給 3D 地面當貼圖。往內縮幾 px，避開自己畫的 ROI 框。 */
    private fun groundDataUrl(src: Bitmap, roi: Rect): String? {
        val inset = 6
        val r = Rect(roi.left + inset, roi.top + inset, roi.right - inset, roi.bottom - inset)
        if (r.width() < 16 || r.height() < 16) return null
        val gw = min(GROUND_W, r.width())
        val gh = max(8, gw * r.height() / r.width())
        var g = groundBmp
        if (g == null || g.width != gw || g.height != gh) {
            g?.recycle()
            g = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888)
            groundBmp = g
        }
        groundCanvas.setBitmap(g)
        groundCanvas.drawBitmap(src, r, Rect(0, 0, gw, gh), groundPaint)
        jpegBuf.reset()
        g.compress(Bitmap.CompressFormat.JPEG, 60, jpegBuf)
        return "data:image/jpeg;base64," + Base64.encodeToString(jpegBuf.toByteArray(), Base64.NO_WRAP)
    }

    private fun typeOf(cls: Int) = when (cls) {
        0 -> "ped"
        1, 3 -> "moto"
        5, 7 -> "truck"
        else -> "car"
    }

    private fun r2(f: Float): Double = Math.round(f * 100f) / 100.0

    private fun ema(prev: Float, v: Float) = if (prev == 0f) v else prev * 0.8f + v * 0.2f

    private fun updateFps() {
        val now = System.nanoTime()
        if (lastFrameNs != 0L) {
            val fps = 1_000_000_000f / (now - lastFrameNs)
            emaFps = ema(emaFps, fps)
        }
        lastFrameNs = now
    }

    private fun showPanel() {
        val pr = prefs.panelRect() ?: return
        val px = Rect(
            (pr.left * screenW).toInt(), (pr.top * screenH).toInt(),
            (pr.right * screenW).toInt(), (pr.bottom * screenH).toInt()
        )
        panelCap = Rect(
            (pr.left * capW).toInt(), (pr.top * capH).toInt(),
            (pr.right * capW).toInt(), (pr.bottom * capH).toInt()
        )
        panel = Bev3DPanel(this, windowManager).also { it.show(px) }
    }

    private fun showOverlay() {
        if (overlay != null) return
        overlay = OverlayView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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
            nm.createNotificationChannel(
                NotificationChannel(CH_ID, "AVM Probe", NotificationManager.IMPORTANCE_LOW)
            )
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
        panel?.hide()
        overlay?.let { try { windowManager.removeView(it) } catch (_: Throwable) {} }
        overlay = null
        // 在 capture 執行緒上釋放模型與緩衝（排在尚未處理的幀之後），再結束執行緒
        handler?.post {
            try { yolo?.close() } catch (_: Throwable) {}
            yolo = null
            capBmp?.recycle()
            groundBmp?.recycle()
        }
        thread?.quitSafely()
    }
}
