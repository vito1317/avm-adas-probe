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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        private const val GROUND_INSET = 6     // 俯視區往內縮，避開自己畫的 ROI 框

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
    private var groundArgb = IntArray(0)
    private val coneDet = ConeDetector()
    private val roadEx = RoadExtractor()
    private val roadTrack = RoadTracker()
    private val slotDet = ParkingSlotDetector()
    private val slotTrack = SlotTracker()

    @Volatile private var busy = false
    @Volatile private var saveRequested = false
    @Volatile private var mapSrc: MapSource? = null
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
        mapSrc = MapSource(this).takeIf { it.start() }   // 沒有定位權限就不開，不影響偵測
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

            if (saveRequested) {
                saveRequested = false
                saveCapture(bmp)
            }

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
            val excl = ArrayList<FloatArray>()
            var tl: JSONObject? = null
            var tlScore = 0f
            for (d in dets) {
                val cx = d.rect.centerX()
                val cy = d.rect.centerY()
                if (pnl != null && pnl.contains(cx.toInt(), cy.toInt())) continue   // 自己的 3D 面板
                if (d.type == ObjTypes.LIGHT) {
                    // 俯視圖裡不會有紅綠燈；只有關掉「只偵測俯視區」、全景360 切到前鏡頭畫面時才看得到
                    val st = classifyLight(bmp, d.rect)
                    val score = d.score * if (st.first == "off") 0.3f else st.second
                    if (score > tlScore) {
                        tlScore = score
                        tl = JSONObject().put("s", st.first).put("c", r2(score))
                    }
                    boxes.add(RectF(d.rect.left * sx, d.rect.top * sy, d.rect.right * sx, d.rect.bottom * sy) to
                        "紅綠燈 ${st.first}")
                    continue
                }
                val inRoi = roiValid && roiCap.contains(cx.toInt(), cy.toInt())
                if (inRoi) {
                    // 俯視圖本身就是地面平面（全景360 已做 IPM）→ 像素直接換公尺
                    val mpp = wm / roiCap.width()
                    var x = (cx - roiCap.exactCenterX()) * mpp
                    var z = (roiCap.exactCenterY() - cy) * mpp     // 畫面上方 = 前方
                    if (flip) { x = -x; z = -z }
                    if (abs(x) < 1.1f && abs(z) < 2.5f) continue      // 俯視圖正中央是自車本身
                    val hw = d.rect.width() * mpp / 2f
                    val hh = d.rect.height() * mpp / 2f
                    excl.add(floatArrayOf(x - hw, z - hh, x + hw, z + hh))
                    objs.put(
                        JSONObject()
                            .put("t", d.type)
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

            // 俯視區縮圖：實景地面、三角錐、道路共用
            val mode = prefs.groundMode
            var groundUrl: String? = null
            var road: JSONObject? = null
            var slotsArr: JSONArray? = null
            val ground = if (roiValid) buildGround(bmp, roiCap) else null
            if (ground != null) {
                val gw = ground.width
                val gh = ground.height
                val gmpp = (wm * (roiCap.width() - 2 * GROUND_INSET) / roiCap.width()) / gw
                if (groundArgb.size != gw * gh) groundArgb = IntArray(gw * gh)
                ground.getPixels(groundArgb, 0, gw, 0, 0, gw, gh)
                // 俯視圖座標（未翻轉）的排除區：全景360 的車子圖示＋導引線、偵測到的車
                val gx = ArrayList<FloatArray>()
                gx.add(floatArrayOf(-1.3f, -2.85f, 1.3f, 2.85f))
                gx.add(floatArrayOf(-1.4f, -9f, -0.7f, 9f))
                gx.add(floatArrayOf(0.7f, -9f, 1.4f, 9f))
                for (e in excl) gx.add(if (flip) floatArrayOf(-e[2], -e[3], -e[0], -e[1]) else e)
                val gcx = gw / 2f
                val gcy = gh / 2f
                for (c in coneDet.detect(groundArgb, gw, gh, gmpp, gcx, gcy, gx)) {
                    val x = if (flip) -c.x else c.x
                    val z = if (flip) -c.z else c.z
                    objs.put(JSONObject().put("t", "cone").put("x", r2(x)).put("z", r2(z)).put("c", r2(c.conf)))
                }
                if (mode == AppPrefs.GROUND_VIRTUAL) {
                    val rr = roadTrack.update(roadEx.extract(groundArgb, gw, gh, gmpp, gcx, gcy, gx), System.currentTimeMillis())
                    road = roadJson(rr, flip)
                }
                // 停車格：車子另外傳（線常被停著的車蓋住），只排除車子圖示與導引線
                val veh = gx.subList(3, gx.size)
                val sl = slotTrack.update(
                    slotDet.detect(groundArgb, gw, gh, gmpp, gcx, gcy, gx.subList(0, 3), veh),
                    System.currentTimeMillis()
                )
                if (sl.isNotEmpty()) slotsArr = slotsJson(sl, flip)
                if (mode == AppPrefs.GROUND_REAL) {
                    jpegBuf.reset()
                    ground.compress(Bitmap.CompressFormat.JPEG, 60, jpegBuf)
                    groundUrl = "data:image/jpeg;base64," + Base64.encodeToString(jpegBuf.toByteArray(), Base64.NO_WRAP)
                }
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
                    .put("src", "screen")
                    .put("stat", JSONObject()
                        .put("fps", r2(emaFps)).put("inf", emaInfer.toInt())
                        .put("e2e", emaE2e.toInt())
                        .put("eng", det.engineName).put("res", det.inputSize))
                groundUrl?.let { payload.put("ground", it) }
                road?.let { payload.put("road", it) }
                slotsArr?.let { payload.put("slots", it) }
                val vs = VehicleBus.signals.snapshot()
                if (!vs.isEmpty) payload.put("veh", VehicleSignals.toJson(vs))
                mapSrc?.takeJsonIfNew()?.let { payload.put("map", it) }
                tl?.let { payload.put("tl", it) }
                p.push(payload.toString())
            }
        } catch (t: Throwable) {
            Log.e(TAG, "frame 處理失敗：${t.message}", t)
            try { image.close() } catch (_: Throwable) {}
        } finally {
            busy = false
        }
    }

    /** 把俯視區縮成小圖（地面貼圖、三角錐、道路共用）。往內縮幾 px，避開自己畫的 ROI 框。 */
    private fun buildGround(src: Bitmap, roi: Rect): Bitmap? {
        val inset = GROUND_INSET
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
        return g
    }

    /** 道路結果 → 3D 面板 JSON；俯視圖車頭朝下時整組轉 180°。 */
    private fun roadJson(r: RoadResult, flip: Boolean): JSONObject {
        val s = if (flip) -1f else 1f
        val lines = JSONArray()
        for (l in r.lines) {
            // 座標乘上 s，曲率的量綱是 1/長度 → 要除以 s
            lines.put(JSONObject().put("k", l.kind.toString()).put("d", if (l.dashed) 1 else 0)
                .put("x1", r2(l.x1 * s)).put("z1", r2(l.z1 * s))
                .put("x2", r2(l.x2 * s)).put("z2", r2(l.z2 * s)).put("c", r2(l.conf))
                .put("kv", r4(if (s != 0f) l.k / s else 0f)))
        }
        val el = if (flip) r.edgeR?.let { -it } else r.edgeL
        val er = if (flip) r.edgeL?.let { -it } else r.edgeR
        return JSONObject().put("lines", lines)
            .put("el", el?.let { r2(it) } ?: JSONObject.NULL)
            .put("er", er?.let { r2(it) } ?: JSONObject.NULL)
    }

    /** 紅綠燈燈色：從截圖裁出框內縮圖判斷。 */
    private fun classifyLight(src: Bitmap, r: RectF): Pair<String, Float> {
        val l = r.left.toInt().coerceIn(0, capW - 1)
        val t = r.top.toInt().coerceIn(0, capH - 1)
        val w = (r.width().toInt()).coerceIn(1, capW - l)
        val h = (r.height().toInt()).coerceIn(1, capH - t)
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, l, t, w, h)
        return TrafficLightState.classify(px, w, h)
    }

    /** 存目前整張截圖＋校正參數（給離線擬合鏡頭幾何用）。 */
    private fun saveCapture(bmp: Bitmap) {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        try {
            val shot = Bitmap.createBitmap(bmp, 0, 0, capW, capH)
            val png = ByteArrayOutputStream()
            shot.compress(Bitmap.CompressFormat.PNG, 100, png)
            shot.recycle()
            val path = MediaSaver.save(this, "screen_$ts.png", "image/png", png.toByteArray())
            val r = prefs.roi
            val meta = JSONObject()
                .put("width", capW).put("height", capH).put("screenW", screenW).put("screenH", screenH)
                .put("roi", JSONArray().put(r.left.toDouble()).put(r.top.toDouble()).put(r.right.toDouble()).put(r.bottom.toDouble()))
                .put("wm", prefs.bevMetersW.toDouble()).put("flip", prefs.bevFlip)
            MediaSaver.save(this, "screen_$ts.json", "application/json", meta.toString(2).toByteArray())
            panel?.notifySaved("已存：$path")
        } catch (t: Throwable) {
            panel?.notifySaved("存檔失敗：${t.message}")
        }
    }

    private fun r2(f: Float): Double = Math.round(f * 100f) / 100.0

    /** 曲率是 0.0x 量級（半徑 30 m → 0.033），捨到小數 2 位會整個變 0 */
    private fun r4(f: Float): Double = Math.round(f * 10000f) / 10000.0

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
        panel = Bev3DPanel(this, windowManager) { saveRequested = true }.also { it.show(px) }
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
        mapSrc?.stop()
        mapSrc = null
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
