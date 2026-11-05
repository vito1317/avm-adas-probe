package com.vito.avmprobe

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.media.Image
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * 鏡頭直讀模式：Camera2 四分割原始畫面 → 各鏡頭輪流 YOLO → 魚眼投影到地面 → 3D 面板。
 * 同時拼一張自己的俯視圖，給實景地面、道路辨識、三角錐用。不需要全景360。
 * 畫面離開前景就釋放鏡頭（倒車時全景360 要能接手）。
 */
class QuadActivity : AppCompatActivity(), CameraQuadSource.Listener {

    companion object {
        private const val OBJ_TTL_MS = 1300L   // > 鏡頭重訪週期（FPS 掉到 10 時一輪 1.3 s）
        private const val TL_TTL_MS = 1200L
        private const val BEV_TTL_MS = 1500L
        private const val OBS_TTL_MS = 4000L   // 障礙物每 4 輪才算一次，而且柱子不會動
        private const val EGO_HW = 0.925f   // 自車半寬 / 半長（m）
        private const val EGO_HL = 2.42f
        private const val SLOT_TL = "tl"     // 前鏡頭上半部專找紅綠燈
        private val NAMES = mapOf(
            "car" to "轎車", "van" to "箱型車", "truck" to "卡車", "bus" to "公車", "moto" to "機車",
            "bigbike" to "重機", "bike" to "腳踏車", "ped" to "行人", "cone" to "三角錐"
        )
        private val CAM_ZH = mapOf("front" to "前", "rear" to "後", "left" to "左", "right" to "右")
        private val LETTER = mapOf('F' to "front", 'B' to "rear", 'L' to "left", 'R' to "right")
        // 物件半寬、半長（m），在俯視圖上蓋掉車身，避免車身反光被當成標線
        private val HALF = mapOf(
            "car" to floatArrayOf(1.0f, 2.4f), "van" to floatArrayOf(1.05f, 2.5f),
            "truck" to floatArrayOf(1.2f, 3.2f), "bus" to floatArrayOf(1.35f, 6.0f),
            "moto" to floatArrayOf(0.5f, 1.1f), "bigbike" to floatArrayOf(0.5f, 1.2f),
            "bike" to floatArrayOf(0.4f, 1.0f), "ped" to floatArrayOf(0.4f, 0.4f)
        )
    }

    private class WObj(val t: String, val x: Float, val z: Float, val c: Float, val clr: Float)
    private class CamResult(val at: Long, val objs: List<WObj>, val boxes: List<QuadOverlayView.Box>)
    private class TlResult(val at: Long, val s: String, val c: Float, val ax: Float, val box: RectF)

    private lateinit var prefs: AppPrefs
    private lateinit var source: CameraQuadSource
    private lateinit var web: Bev3DWeb
    private lateinit var texture: TextureView
    private lateinit var overlay: QuadOverlayView
    @Volatile private var bevVehicles = 0      // 上一輪傳給停車格偵測的車輛框數（狀態列診斷用）
    private var camSide: ViewGroup? = null     // 左半邊（鏡頭畫面＋按鈕），全螢幕時整塊藏起來
    private var root: ViewGroup? = null
    private var fsBtn: Button? = null
    private var geo: AvmGeometry? = null
    private var calibText = "{}"
    private var calibrated = false
    private var geoError: String? = null
    private var started = false

    // ---- 以下只在 frames 執行緒存取 ----
    private val frame = YuvFrame()
    private var yolo: Yolo? = null
    private var yoloError: String? = null
    private var lut: IntArray? = null
    private var lutKey = 0L
    private var slotIdx = 0
    private var frameNo = 0L
    private val camResults = HashMap<String, CamResult>()
    private var tl: TlResult? = null
    // 多目標追蹤 + 盲點/變道輔助（只在 frames 執行緒呼叫，不用鎖）
    // camPos 只給位置不給軸向：一次只送半個 FOV 的裁切，countMisses 的整鏡視野錐會記假 miss（見 runCamera）
    private val trackMgr = TrackManager(camPos = TrackManager.DEFAULT_CAMS.mapValues { it.value.copyOf(2) })
    private val lca = LaneChangeAssist()
    // 起步提醒（前車開走／紅燈轉綠）與車道偏離警示；都只在 frames 執行緒更新
    private val startAssist = StartAssist()
    private val ldw = LaneDepartureWarning()
    @Volatile private var startState: StartAssist.State? = null
    @Volatile private var ldwState: LaneDepartureWarning.State? = null
    private var lastBeepAt = 0L
    private var tone: android.media.ToneGenerator? = null
    private var tracks: List<Track> = emptyList()
    private var tracksAt = 0L
    @Volatile private var lcaState: LcaState? = null

    // ---- 俯視圖辨識（道路、三角錐、停車格、地面貼圖）在 ground 執行緒，結果用 volatile 交給 frames ----
    private val groundThread = HandlerThread("ground").apply { start() }
    private val ground = Handler(groundThread.looper)
    @Volatile private var groundBusy = false
    private var bevArgb = IntArray(0)          // groundBusy=false 時才由 frames 寫入
    private val roadEx = RoadExtractor()
    private val roadTrack = RoadTracker()
    private val coneDet = ConeDetector()
    private val slotDet = ParkingSlotDetector()
    private val slotTrack = SlotTracker()
    private var bevBmp: Bitmap? = null
    private val jpeg = ByteArrayOutputStream(96 * 1024)
    @Volatile private var slots: List<ParkingSlot> = emptyList()
    @Volatile private var slotsAt = 0L
    @Volatile private var road: RoadResult? = null
    @Volatile private var roadAt = 0L
    @Volatile private var cones: List<Cone> = emptyList()
    @Volatile private var conesAt = 0L
    // 高於地面的障礙物（柱子、牆、車身）：不靠模型，用四顆鏡頭重疊區的垂直平面掃描。
    // 估 20–35 ms，會跟 YOLO 搶 CPU，所以只在停車速度下跑、而且降頻。
    private var heightDet: HeightDetector? = null
    private var heightArgb = IntArray(0)
    @Volatile private var obstacles: List<Obstacle> = emptyList()
    @Volatile private var obstaclesAt = 0L
    private var heightEvery = 0L
    @Volatile private var heightBusy = false
    @Volatile private var groundUrl: String? = null
    @Volatile private var groundAt = 0L
    private var previewArgb = IntArray(0)
    private var previewBmp: Bitmap? = null
    private var emaInfer = 0f
    private var emaProc = 0f
    private var emaFps = 0f
    private var lastFrameAt = 0L

    @Volatile private var saveRawRequested = false
    @Volatile private var camState = "啟動中…"

    @Volatile private var mapSrc: MapSource? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (!started) return@registerForActivityResult
        if (granted(Manifest.permission.CAMERA)) source.start() else camState = "需要相機權限"
        startMap()
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /** 地圖輔助：有定位權限才開（沒有也不影響鏡頭偵測）。 */
    private fun startMap() {
        if (mapSrc != null) return
        val m = MapSource(this)
        if (m.start()) mapSrc = m
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        try {
            calibText = assets.open("avm_calib.json").bufferedReader().readText()
            geo = AvmGeometry.fromJson(calibText)
            calibrated = JSONObject(calibText).optBoolean("calibrated", false)
        } catch (t: Throwable) {
            geoError = "讀取鏡頭幾何失敗：${t.message}"
        }

        web = Bev3DWeb(this, "cam")
        setContentView(buildUi())
        source = CameraQuadSource(this, prefs.camId, this)

        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                source.setPreview(st)
                applyZoom()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = applyZoom()
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                source.setPreview(null)
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    private fun buildUi(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(9, 13, 19))
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        val holder = FrameLayout(this)
        val aspect = AspectFrame(this, 16f / 9f)
        texture = TextureView(this)
        overlay = QuadOverlayView(this)
        geo?.let { g -> overlay.quadNames = Array(4) { q -> CAM_ZH[g.camForQuad(q)?.name] ?: "?" } }
        aspect.addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        aspect.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                overlay.zoomQuad = if (overlay.zoomQuad >= 0) -1 else {
                    (if (e.x > v.width / 2f) 1 else 0) + (if (e.y > v.height / 2f) 2 else 0)
                }
                applyZoom()
            }
            true
        }
        holder.addView(aspect, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        left.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 偵測哪些鏡頭（點一下切換）
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((letter, name) in listOf('L' to "左", 'R' to "右", 'F' to "前", 'B' to "後")) {
            row1.addView(toggle("偵測$name", { letter in prefs.camDetect }) {
                prefs.camDetect = if (letter in prefs.camDetect) prefs.camDetect.replace(letter.toString(), "")
                else prefs.camDetect + letter
            })
        }
        row1.addView(toggle("紅綠燈", { prefs.camTrafficLight }) { prefs.camTrafficLight = !prefs.camTrafficLight })
        row1.addView(toggle("自建俯視", { prefs.camBev }) { prefs.camBev = !prefs.camBev })
        row1.addView(toggle("障礙物", { prefs.camObstacle }) {
            prefs.camObstacle = !prefs.camObstacle
            if (!prefs.camObstacle) obstacles = emptyList()
        })
        left.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(button("存原始畫面（校正用）") {
            saveRawRequested = true
            toast("下一幀存檔中…")
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        row2.addView(button("返回") { finish() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        left.addView(row2)

        root.addView(web.view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        camSide = left
        this.root = root

        // 全螢幕切換鈕浮在最上層：切到全螢幕之後左邊整塊會被藏起來，
        // 按鈕要是放在裡面就回不來了。
        val stack = FrameLayout(this)
        stack.addView(root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fun floatBtn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; textSize = 11f; alpha = 0.55f
            setPadding(18, 6, 18, 6)
            setOnClickListener { onClick() }
        }
        fsBtn = floatBtn("") { cycleFullscreen() }
        // 全螢幕時左邊整塊（含「返回」）會不見，系統列也收起來了，所以返回也要浮著
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(fsBtn)
        bar.addView(floatBtn("返回") { finish() })
        stack.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.START; leftMargin = 8; topMargin = 8 })
        applyFullscreen()
        return stack
    }

    /** 0 = 左右分割、1 = 3D 全螢幕、2 = 鏡頭全螢幕 */
    private fun cycleFullscreen() {
        prefs.fullScreen = (prefs.fullScreen + 1) % 3
        applyFullscreen()
    }

    private fun applyFullscreen() {
        val m = prefs.fullScreen
        camSide?.visibility = if (m == 1) View.GONE else View.VISIBLE
        web.view.visibility = if (m == 2) View.GONE else View.VISIBLE
        fsBtn?.text = when (m) { 1 -> "⤢ 3D"; 2 -> "⤢ 鏡頭"; else -> "⤢ 分割" }
        // 全螢幕時連系統列一起收掉，小螢幕才看得到東西
        val w = window ?: return
        @Suppress("DEPRECATION")
        w.decorView.systemUiVisibility = if (m == 0) 0 else
            (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun toggle(label: String, isOn: () -> Boolean, flip: () -> Unit): Button {
        val b = Button(this).apply { isAllCaps = false; textSize = 13f }
        fun refresh() { b.text = if (isOn()) "● $label" else label }
        b.setOnClickListener { flip(); refresh() }
        refresh()
        b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        return b
    }

    private fun applyZoom() {
        val z = overlay.zoomQuad
        val m = Matrix()
        if (z >= 0) {
            m.setScale(2f, 2f)
            m.postTranslate(-(z % 2) * texture.width.toFloat(), -(z / 2) * texture.height.toFloat())
        }
        texture.setTransform(m)
        texture.invalidate()
        overlay.invalidate()
    }

    override fun onStart() {
        super.onStart()
        started = true
        web.view.onResume()
        if (granted(Manifest.permission.CAMERA)) source.start()
        startMap()
        val need = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).filter { !granted(it) }
        if (need.isNotEmpty()) permLauncher.launch(need.toTypedArray())
    }

    override fun onStop() {
        super.onStop()
        started = false
        source.stop()       // 一定要放掉鏡頭：倒車時全景360 要用
        source.frames.post {   // 舊軌跡與轉向意圖遲滯不留到鏡頭重啟後
            trackMgr.reset()
            lca.reset()
            tracks = emptyList()
            lcaState = null
            startState = null
            ldwState = null
        }
        mapSrc?.stop()
        mapSrc = null
        tone?.release()
        tone = null
        web.view.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        source.frames.post {
            try { yolo?.close() } catch (_: Throwable) {}
            yolo = null
        }
        source.release()
        groundThread.quitSafely()
        web.destroy()
    }

    // ---------------- CameraQuadSource.Listener ----------------

    override fun onState(text: String, running: Boolean) {
        camState = text
        if (!running) overlay.update(emptyList(), statusText())
    }

    override fun onFrame(img: Image) {
        val t0 = SystemClock.elapsedRealtime()
        frame.load(img)
        img.close()
        val g = geo ?: return overlay.update(emptyList(), geoError ?: "")
        frameNo++

        if (saveRawRequested) {
            saveRawRequested = false
            saveRaw()
        }
        if (source.previewFromYuv) drawPreviewFromYuv()

        val det = yolo ?: initYolo() ?: return overlay.update(emptyList(), statusText())

        val vs = VehicleBus.signals.snapshot()   // 這一幀共用：追蹤、變道輔助、payload 的 veh
        val qw = frame.width / 2
        val qh = frame.height / 2
        val slots = slots()
        if (slots.isNotEmpty()) {
            val s = slots[(slotIdx++) % slots.size]
            if (s == SLOT_TL) runTrafficLightPass(det, g, qw, qh) else runCamera(det, g, s, qw, qh, vs, t0)
        }
        val now = SystemClock.elapsedRealtime()
        if (prefs.camBev && !groundBusy) runBev(g, now)

        val proc = (SystemClock.elapsedRealtime() - t0).toFloat()
        emaProc = ema(emaProc, proc)
        if (lastFrameAt != 0L) emaFps = ema(emaFps, 1000f / max(1L, t0 - lastFrameAt))
        lastFrameAt = t0

        pushPayload(g, det, now, vs)
    }

    /**
     * 偵測順序（鏡頭:區塊）：左右兩側優先（行車時主要看旁車），前後鏡頭每輪各兩次、兩次之間
     * 等距——候選軌跡 1 s 內要第二次命中（TrackManager.TENTATIVE_MS），重訪週期必須壓在 1 s 內，
     * 後方來車預警也要更高的更新率。紅綠燈再插一輪。
     * 整個魚眼象限縮到模型輸入會讓車小到抓不到（實車畫面 0/8），所以只送正方形裁切：
     * 側鏡頭分前後兩塊（a、b，重疊 12%），前後鏡頭取中央一塊（c）。
     */
    private fun slots(): List<String> {
        val en = prefs.camDetect.mapNotNull { LETTER[it] }.toSet()
        val out = listOf(
            "left:a", "right:a", "rear:c", "left:b", "right:b", "front:c",
            "left:a", "right:a", "rear:c", "left:b", "right:b", "front:c"
        ).filter { it.substringBefore(':') in en }.toMutableList()
        if (prefs.camTrafficLight) out.add(SLOT_TL)
        return out
    }

    private fun initYolo(): Yolo? {
        if (yoloError != null) return null
        return try {
            Yolo(this, prefs.model, useGpu = prefs.useGpu).also { yolo = it }
        } catch (t: Throwable) {
            yoloError = "模型載入失敗：${prefs.model} ${t.message}"
            camState = yoloError!!
            null
        }
    }

    /** atMs：這一幀的擷取時間（onFrame 的 t0），給追蹤補償推論延遲。 */
    private fun runCamera(det: Yolo, g: AvmGeometry, slot: String, qw: Int, qh: Int, vs: VehicleState, atMs: Long) {
        val name = slot.substringBefore(':')
        val cam = g.cam(name) ?: return
        val qx0 = (cam.quad % 2) * qw
        val qy0 = (cam.quad / 2) * qh
        // 正方形裁切（邊長 = 象限高度）：a 靠左、b 靠右、c 置中
        val side = qh.coerceAtMost(qw)
        val rx0 = qx0 + when (slot.substringAfter(':')) {
            "a" -> 0
            "b" -> qw - side
            else -> (qw - side) / 2
        }
        val (dets, inf) = det.detectWith(side, qh, rx0.toFloat(), qy0.toFloat()) { buf, nchw, size, lb ->
            frame.fillModelInput(rx0, qy0, side, qh, buf, nchw, size, lb)
        }
        emaInfer = ema(emaInfer, inf.toFloat())
        val now = SystemClock.elapsedRealtime()
        val objs = ArrayList<WObj>()
        val boxes = ArrayList<QuadOverlayView.Box>()
        val tDets = ArrayList<Det>()
        for (d in dets) {
            if (d.type == ObjTypes.LIGHT) {
                if (name == "front") considerTrafficLight(g, cam, d, qx0, qy0, qw, qh, now)
                continue
            }
            val u = d.rect.centerX() - qx0
            val v = d.rect.bottom - qy0
            // 框貼著畫面底邊又幾乎跟裁切一樣寬：拍到自己的車身（引擎蓋、車門、保險桿）
            if (v > qh * 0.97f && d.rect.width() > side * 0.85f) continue
            val w = toWorld(g, cam, u.toDouble(), v.toDouble(), qw, qh, d.type) ?: continue
            objs.add(WObj(d.type, w[0], w[1], d.score, w[2]))
            // 只收真實地面命中：地平線 fallback 的假距離會變假速度 → 假的後方來車警告
            if (w[3] > 0f) tDets.add(Det(d.type, w[0], w[1], d.score, cam = name, atMs = atMs))
            boxes.add(QuadOverlayView.Box(
                RectF(d.rect), "${NAMES[d.type] ?: d.label} ${"%.1f".format(w[2])}m", w[2] < 1.2f))
        }
        camResults[slot] = CamResult(now, objs, boxes)

        // 追蹤 + 變道輔助。cam 傳 null：一次只送半個 FOV 的裁切，傳鏡頭名稱會讓 countMisses
        // 對不在本次裁切內的軌跡記假 miss、在確認前殺掉盲點軌跡；假軌跡改由 TENTATIVE_MS / COAST_MS 逾時清除。
        val ego = egoMps(vs)
        tracks = trackMgr.update(tDets, now, ego, egoYawRate = null, cam = null)
        tracksAt = now
        val rd = road
        val lane = if (prefs.groundMode == AppPrefs.GROUND_VIRTUAL && rd != null && now - roadAt < BEV_TTL_MS)
            laneAt0(rd) else null
        lcaState = lca.update(
            tracks, now,
            lane?.get(0)?.takeIf { !it.isNaN() }, lane?.get(1)?.takeIf { !it.isNaN() },
            vs.turn, vs.steerDeg, ego,
            lane?.get(2)?.takeIf { !it.isNaN() }, reverse = vs.gear == 'R'
        )

        // 起步提醒：用「要顯示的物件」而不是軌跡 —— 前車常常只被前鏡頭看到一兩次，
        // 等軌跡 confirmed 會慢半拍，而這個提醒本來就是要快
        val t = tl
        val tlFresh = t != null && now - t.at < TL_TTL_MS
        startState = startAssist.update(
            now, vs.speedKmh,
            currentObjects(now).map { StartAssist.Obj(it.t, it.x, it.z) },
            if (tlFresh) t!!.s else null, if (tlFresh) t!!.c else 0f
        )
        // 車道偏離：吃道路辨識的線。虛擬道路關掉時 road 不會更新，LDWS 就沒東西可吃
        ldwState = ldw.update(
            now, vs.speedKmh,
            if (rd != null && now - roadAt < BEV_TTL_MS) rd.lines else emptyList(),
            vs.turn
        )
        beepIfNeeded(now)
    }

    /**
     * 起步提醒與車道偏離各給一聲短提示音。畫面在右半邊、駕駛未必在看，沒有聲音等於沒有提醒。
     * 兩秒內只響一次，免得連環叫。
     */
    private fun beepIfNeeded(now: Long) {
        val st = startState
        val ld = ldwState
        val want = (st != null && (st.lead || st.green)) || (ld != null && ld.any)
        if (!want || now - lastBeepAt < 2000L) return
        lastBeepAt = now
        try {
            val tg = tone ?: android.media.ToneGenerator(
                android.media.AudioManager.STREAM_NOTIFICATION, 80
            ).also { tone = it }
            tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 250)
        } catch (_: Throwable) {
            tone = null     // 有些車機沒有這個音路，失敗就算了，畫面還是有
        }
    }

    /** 自車車速 m/s；排檔 R 時為負（GPS/CAN 只有速率，分不出前進倒退）。 */
    private fun egoMps(vs: VehicleState): Float? =
        vs.speedKmh?.div(3.6f)?.let { if (vs.gear == 'R') -it else it }

    /**
     * 車頭朝向（弧度，+ = 偏右）＝ 對地速度的方向：vx/vz 是相對速度，縱向要加回自車車速，
     * 對向車才不會跟自車同方向。null = 這一刻不送（前端沿用上一次的朝向，停著的車不會亂轉）：
     * 沒有車速（分不出靜止車與同向車）、三角錐／行人（朝向沒意義）、對地速率 < 1.5 m/s（方向是雜訊），
     * 或速度標準差比速率還大（軌跡剛生成，速度還停在初值）。
     */
    private fun yaw(tr: Track, ego: Float?): Float? {
        if (ego == null || tr.t == "cone" || tr.t == "ped") return null
        val avx = tr.vx
        val avz = tr.vz + ego
        val sp = hypot(avx, avz)
        if (sp < 1.5f || hypot(tr.svx, tr.svz) > sp) return null
        return atan2(avx, avz)
    }

    /** 近縱向（±30°）的標線在 z=0 內插出 x：左取 x<0 最大、右取 x>0 最小。回傳 [左x, 右x, 車道角]，沒有為 NaN。 */
    private fun laneAt0(rd: RoadResult): FloatArray {
        var lx = Float.NaN
        var rx = Float.NaN
        var la = Float.NaN
        var ra = Float.NaN
        for (l in rd.lines) {
            var dx = l.x2 - l.x1
            var dz = l.z2 - l.z1
            if (dz < 0f) { dx = -dx; dz = -dz }   // 一律朝前
            if (dz < 1f) continue                  // 太短或橫向（停止線）
            val ang = Math.toDegrees(atan2(dx.toDouble(), dz.toDouble())).toFloat()
            if (abs(ang) > 30f) continue
            val x0 = l.x1 - l.z1 * dx / dz
            if (x0 < 0f && (lx.isNaN() || x0 > lx)) { lx = x0; la = ang }
            if (x0 > 0f && (rx.isNaN() || x0 < rx)) { rx = x0; ra = ang }
        }
        val ang = when {
            !la.isNaN() && !ra.isNaN() -> (la + ra) / 2f
            !la.isNaN() -> la
            else -> ra
        }
        return floatArrayOf(lx, rx, ang)
    }

    private fun runTrafficLightPass(det: Yolo, g: AvmGeometry, qw: Int, qh: Int) {
        val cam = g.cam("front") ?: return
        val qx0 = (cam.quad % 2) * qw
        val qy0 = (cam.quad / 2) * qh
        // 紅綠燈在魚眼裡非常小：前鏡頭裝在約 0.3 m 高、下傾約 39°（那是停車用的鏡頭），
        // 18 m 外 1 m 寬的燈箱只有約 9 px。原本裁 0.7qw x 0.5qh 再縮到模型輸入，等於再砍掉六成，
        // 剩 3 px 根本抓不到。改成貼著地平線裁一塊**正方形**，縮放損失幾乎為零。
        // 地平線在影像上是以主點為中心、半徑 = fx*QW*pitch 的圓（我們的等距模型）。
        val rHor = (cam.fx * qw * Math.toRadians(cam.pitch)).toFloat()
        val side = (qh * 0.55f).toInt().coerceAtMost(qw)
        val cxPx = (cam.cx * qw).toFloat()
        val cyPx = (cam.cy * qh).toFloat()
        // 正方形中心放在地平線稍微上面一點（號誌在地平線以上）
        val rx0 = (qx0 + (cxPx - side / 2f)).toInt().coerceIn(qx0, qx0 + qw - side)
        val ry0 = (qy0 + (cyPx - rHor - side * 0.55f)).toInt().coerceIn(qy0, qy0 + qh - side)
        val rw = side
        val rh = side
        val (dets, inf) = det.detectWith(rw, rh, rx0.toFloat(), ry0.toFloat()) { buf, nchw, size, lb ->
            frame.fillModelInput(rx0, ry0, rw, rh, buf, nchw, size, lb)
        }
        emaInfer = ema(emaInfer, inf.toFloat())
        val now = SystemClock.elapsedRealtime()
        for (d in dets) if (d.type == ObjTypes.LIGHT) considerTrafficLight(g, cam, d, qx0, qy0, qw, qh, now)
    }

    private fun considerTrafficLight(
        g: AvmGeometry, cam: CamParams, d: Detection, qx0: Int, qy0: Int, qw: Int, qh: Int, now: Long
    ) {
        val r = d.rect
        if (r.width() < 3f || r.height() < 3f) return
        val ow = r.width().toInt().coerceIn(6, 48)
        val oh = r.height().toInt().coerceIn(6, 48)
        val crop = IntArray(ow * oh)
        frame.cropArgb(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt(), ow, oh, crop)
        val (state, conf) = TrafficLightState.classify(crop, ow, oh)
        val score = d.score * if (state == "off") 0.3f else conf
        val cur = tl
        if (cur != null && now - cur.at < 300 && cur.c >= score) return
        val ray = g.pixelToRay(cam, (r.centerX() - qx0).toDouble(), (r.centerY() - qy0).toDouble(), qw, qh)
        val ax = Math.toDegrees(atan2(ray[0], ray[2])).toFloat()
        tl = TlResult(now, state, score, ax, RectF(r))
    }

    /**
     * 框底邊中點（物件最靠近鏡頭、貼地的那一點）→ 地面公尺座標；物件中心再沿鏡頭視線往外推半寬。
     * 回傳 (x, z, 離車身距離, 真實落地 1/0)；落在自車車身上（拍到自己）回傳 null。
     * 第 4 欄 0 = 地平線 fallback（捏造的 15 m 點）：只能顯示，不可餵追蹤（假距離 → 假速度）。
     */
    private fun toWorld(g: AvmGeometry, cam: CamParams, u: Double, v: Double, qw: Int, qh: Int, t: String): FloatArray? {
        val p = g.pixelToGround(cam, u, v, qw, qh)
        val nx: Double
        val nz: Double
        val real: Float
        if (p != null && hypot(p[0] - cam.px, p[1] - cam.pz) < 20.0) {
            nx = p[0]; nz = p[1]; real = 1f
        } else {
            // 框底在地平線以上（幾何還沒校正或物件很遠）：放在該方位 15 m
            val ray = g.pixelToRay(cam, u, v, qw, qh)
            val az = atan2(ray[0], ray[2])
            nx = cam.px + sin(az) * 15.0
            nz = cam.pz + cos(az) * 15.0
            real = 0f
        }
        if (abs(nx) < EGO_HW + 0.05 && abs(nz) < EGO_HL + 0.05) return null
        val dx = nx - cam.px
        val dz = nz - cam.pz
        val d = max(hypot(dx, dz), 1e-3)
        val rad = ObjTypes.radius(t)
        val clr = hypot(max(abs(nx) - EGO_HW, 0.0), max(abs(nz) - EGO_HL, 0.0)).toFloat()
        return floatArrayOf((nx + dx / d * rad).toFloat(), (nz + dz / d * rad).toFloat(), clr, real)
    }

    /** 跨鏡頭合併：同種類 1.5 m 內只留信心高的（前鏡頭和側鏡頭會看到同一台）。 */
    private fun currentObjects(now: Long): List<WObj> {
        val all = ArrayList<WObj>()
        for (r in camResults.values) if (now - r.at < OBJ_TTL_MS) all.addAll(r.objs)
        all.sortByDescending { it.c }
        val kept = ArrayList<WObj>()
        for (o in all) {
            if (kept.none { it.t == o.t && hypot(it.x - o.x, it.z - o.z) < 1.5f }) kept.add(o)
        }
        return kept
    }

    private fun runBev(g: AvmGeometry, now: Long) {
        val key = (frame.width.toLong() shl 32) or frame.height.toLong()
        var table = lut
        if (table == null || lutKey != key) {
            table = g.buildBevLut(frame.width, frame.height)
            lut = table
            lutKey = key
            bevArgb = IntArray(g.bev.width * g.bev.height)
        }
        val bw = g.bev.width
        val bh = g.bev.height
        val mpp = g.bev.mpp.toFloat()
        val cx = g.bev.originX.toFloat()
        val cy = g.bev.originY.toFloat()

        val ego = floatArrayOf(-EGO_HW - 0.15f, -EGO_HL - 0.15f, EGO_HW + 0.15f, EGO_HL + 0.15f)
        val excl = ArrayList<FloatArray>()
        val vehicles = ArrayList<FloatArray>()
        excl.add(ego)
        for (o in currentObjects(now)) {
            val hl = HALF[o.t] ?: continue
            val r = floatArrayOf(o.x - hl[0], o.z - hl[1], o.x + hl[0], o.z + hl[1])
            excl.add(r)
            if (o.t == "car" || o.t == "van" || o.t == "truck" || o.t == "bus") vehicles.add(r)
        }
        // 停車格回報 0 個時要分得出是「沒看到車」還是別的問題：
        // 停在格子裡的車如果沒被框起來，它的車身邊緣會被當成「格內有線」而把格子整個刷掉。
        bevVehicles = vehicles.size

        // 障礙物偵測吃的是原始四分割畫面（要分別從各鏡頭取樣），不是俯視圖。
        // 只在停車速度（< 15 km/h 或車速不明）下跑，而且每 4 輪才跑一次：
        // 柱子和牆不會動，更新率低沒關係，但它跟 YOLO 搶 CPU。
        val spd = VehicleBus.signals.snapshot().speedKmh
        val slow = spd == null || spd < 15f
        // heightBusy：上一輪還在算就不要覆寫緩衝區（它在 ground 執行緒讀）
        if (prefs.camObstacle && slow && !heightBusy && (heightEvery++ % 4L == 0L)) {
            val hw = frame.width
            val hh = frame.height
            val hd = heightDet ?: HeightDetector(g).also { heightDet = it }
            if (heightArgb.size != hw * hh) heightArgb = IntArray(hw * hh)
            // 降解析度會漏掉小柱子（實測 4 張實車畫面少一個真實目標），所以維持原解析度，
            // 改成只轉偵測真的會取樣到的那些點
            frame.fillArgbAt(hd.pixelsNeeded(hw, hh), heightArgb)
            val buf2 = heightArgb
            heightBusy = true
            ground.post {
                try {
                    obstacles = hd.detect(buf2, hw, hh)
                    obstaclesAt = SystemClock.elapsedRealtime()
                } catch (t: Throwable) {
                    obstacles = emptyList()
                } finally {
                    heightBusy = false
                }
            }
        }

        // 取樣在這裡做（frame 只在這條執行緒有效），辨識丟到 ground 執行緒，跟下一輪偵測並行
        frame.sampleLut(table, bevArgb)
        groundBusy = true
        val buf = bevArgb
        val mode = prefs.groundMode
        ground.post {
            try {
                cones = coneDet.detect(buf, bw, bh, mpp, cx, cy, excl)
                conesAt = now
                // 停車格的線常被停著的車蓋住，車子另外傳進去判斷「有車」，不當排除區
                slots = slotTrack.update(slotDet.detect(buf, bw, bh, mpp, cx, cy, listOf(ego), vehicles), now)
                slotsAt = now
                if (mode == AppPrefs.GROUND_VIRTUAL) {
                    road = roadTrack.update(
                        roadEx.extract(buf, bw, bh, mpp, cx, cy, excl, g.ipmValidMask()), now)
                    roadAt = now
                }
                if (mode == AppPrefs.GROUND_REAL) {
                    var bmp = bevBmp
                    if (bmp == null || bmp.width != bw || bmp.height != bh) {
                        bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                        bevBmp = bmp
                    }
                    bmp.setPixels(buf, 0, bw, 0, 0, bw, bh)
                    jpeg.reset()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 65, jpeg)
                    groundUrl = "data:image/jpeg;base64," + Base64.encodeToString(jpeg.toByteArray(), Base64.NO_WRAP)
                    groundAt = now
                }
            } catch (t: Throwable) {
                camState = "俯視圖辨識失敗：${t.message}"
            } finally {
                groundBusy = false
            }
        }
    }

    private fun pushPayload(g: AvmGeometry, det: Yolo, now: Long, vs: VehicleState) {
        val objs = JSONArray()
        val boxes = ArrayList<QuadOverlayView.Box>()
        for (r in camResults.values) if (now - r.at < OBJ_TTL_MS) boxes.addAll(r.boxes)
        // 顯示用偵測結果（跟 2D 框同一個來源，有框就一定有物件），追蹤只負責加值：
        // 配到同種類最近的確認軌跡才加 id / vx / vz / yaw。沒 id 的物件不會被 lca3d 的 threat.id 配到，
        // LaneChangeAssist 也只吃 tracks 不吃 objs，所以多顯示出來的東西不會變成假警告。
        val ego = egoMps(vs)
        val coneOut = ArrayList<WObj>()
        val used = HashSet<Int>()
        for (o in currentObjects(now)) {
            if (o.t == "cone") coneOut.add(o)
            var best: Track? = null
            var bd = 1.5f
            if (now - tracksAt < OBJ_TTL_MS) for (tr in tracks) {
                if (!tr.confirmed || tr.t != o.t || tr.id in used) continue   // 一個軌跡只配一個物件
                val d = hypot(tr.x - o.x, tr.z - o.z)
                if (d < bd) { bd = d; best = tr }
            }
            val j = JSONObject().put("t", o.t).put("x", r2(o.x)).put("z", r2(o.z)).put("c", r2(o.c))
            best?.let { tr ->
                used.add(tr.id)
                j.put("id", tr.id).put("vx", r2(tr.vx)).put("vz", r2(tr.vz))
                yaw(tr, ego)?.let { y -> j.put("yaw", r2(y)) }
            }
            objs.put(j)
        }
        if (now - conesAt < BEV_TTL_MS) {
            for (c in cones) {
                // 鏡頭已經看到的錐就跳過 BEV 錐，面板才不會畫成一對。
                // 留鏡頭捨 BEV：位置跟 2D 框一致、也才帶得到 id；代價是 BEV 較準的那個位置用不到
                if (coneOut.any { hypot(it.x - c.x, it.z - c.z) < 1f }) continue
                objs.put(JSONObject().put("t", "cone").put("x", r2(c.x)).put("z", r2(c.z)).put("c", r2(c.conf)))
            }
        }
        val bev = g.bev
        val p = JSONObject()
            .put("objs", objs)
            .put("wm", r2((bev.xMax - bev.xMin).toFloat())).put("hm", r2((bev.zMax - bev.zMin).toFloat()))
            .put("flip", false).put("overlap", false).put("src", "cam")
            .put("stat", JSONObject()
                .put("fps", r2(emaFps)).put("inf", emaInfer.toInt()).put("e2e", emaProc.toInt())
                .put("eng", det.engineName).put("res", det.inputSize))
        val mode = prefs.groundMode
        if (mode == AppPrefs.GROUND_REAL && now - groundAt < BEV_TTL_MS) groundUrl?.let { p.put("ground", it) }
        val rd = road
        if (mode == AppPrefs.GROUND_VIRTUAL && rd != null && now - roadAt < BEV_TTL_MS) {
            val lines = JSONArray()
            for (l in rd.lines) {
                lines.put(JSONObject().put("k", l.kind.toString()).put("d", if (l.dashed) 1 else 0)
                    .put("x1", r2(l.x1)).put("z1", r2(l.z1)).put("x2", r2(l.x2)).put("z2", r2(l.z2))
                    .put("c", r2(l.conf)).put("kv", r4(l.k)))
            }
            p.put("road", JSONObject().put("lines", lines)
                .put("el", rd.edgeL?.let { r2(it) } ?: JSONObject.NULL)
                .put("er", rd.edgeR?.let { r2(it) } ?: JSONObject.NULL))
        }
        if (now - slotsAt < BEV_TTL_MS && slots.isNotEmpty()) p.put("slots", slotsJson(slots, false))
        // 障礙物更新率低（每 4 輪一次、又只在停車速度下跑），TTL 放寬成 4 秒
        val obs = obstacles
        if (now - obstaclesAt < OBS_TTL_MS && obs.isNotEmpty()) {
            val oa = JSONArray()
            for (o in obs) {
                oa.put(JSONObject().put("x", r2(o.x)).put("z", r2(o.z))
                    .put("w", r2(o.w)).put("l", r2(o.l)).put("c", r2(o.conf)))
            }
            p.put("obs", oa)
        }
        startState?.let { st ->
            if (st.lead || st.green) {
                p.put("start", JSONObject()
                    .put("lead", st.lead).put("green", st.green).put("gap", r2(st.gap)))
            }
        }
        ldwState?.let { ld ->
            if (ld.any) p.put("ldw", JSONObject().put("l", ld.left).put("r", ld.right))
        }
        if (!vs.isEmpty) p.put("veh", VehicleSignals.toJson(vs))
        if (now - tracksAt < OBJ_TTL_MS) lcaState?.let { p.put("lca", lca.toJson(it)) }
        mapSrc?.takeJsonIfNew()?.let { p.put("map", it) }   // 每個 GPS 定位只送一次，3D 自己沿路推算
        val t = tl
        if (t != null && now - t.at < TL_TTL_MS) {
            p.put("tl", JSONObject().put("s", t.s).put("c", r2(t.c)).put("ax", r2(t.ax)))
            boxes.add(QuadOverlayView.Box(t.box, when (t.s) {
                "red" -> "紅燈"; "yellow" -> "黃燈"; "green" -> "綠燈"; else -> "紅綠燈"
            }, t.s == "red"))
        }
        web.push(p.toString())
        overlay.frameW = frame.width
        overlay.frameH = frame.height
        overlay.update(boxes, statusText(det))
    }

    private fun statusText(det: Yolo? = yolo): String {
        val sb = StringBuilder(camState)
        if (det != null) {
            sb.append("\n引擎 ${det.engineName} · 輸入 ${det.inputSize} · 推論 ${emaInfer.toInt()}ms · " +
                "每幀 ${emaProc.toInt()}ms · ${"%.1f".format(emaFps)} fps")
        }
        if (!calibrated) sb.append("\n鏡頭幾何：預設值（未校正），距離與實景地面僅供參考")
        // 俯視圖辨識的階段計數：停車格回報 0 個時，這行才分得出是哪一關掉的
        if (prefs.camBev) {
            sb.append("\n俯視：車道線 ").append(road?.lines?.size ?: 0)
                .append(" · 三角錐 ").append(cones.size)
                .append(" · 停車格 ").append(slots.size)
                .append(" · 車輛框 ").append(bevVehicles)
            if (prefs.camObstacle) sb.append(" · 障礙物 ").append(obstacles.size)
        }
        return sb.toString()
    }

    private fun drawPreviewFromYuv() {
        val step = 3
        val ow = frame.width / step
        val oh = frame.height / step
        if (previewArgb.size != ow * oh) {
            previewArgb = IntArray(ow * oh)
            previewBmp = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        }
        val bmp = previewBmp ?: return
        frame.downsampleArgb(step, previewArgb)
        bmp.setPixels(previewArgb, 0, ow, 0, 0, ow, oh)
        val c = try { texture.lockCanvas() } catch (_: Throwable) { null } ?: return
        try {
            c.drawBitmap(bmp, null, Rect(0, 0, c.width, c.height), null)
        } finally {
            texture.unlockCanvasAndPost(c)
        }
    }

    private fun saveRaw() {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        try {
            val bos = ByteArrayOutputStream()
            YuvImage(frame.toNv21(), ImageFormat.NV21, frame.width, frame.height, null)
                .compressToJpeg(Rect(0, 0, frame.width, frame.height), 95, bos)
            val path = MediaSaver.save(this, "raw_$ts.jpg", "image/jpeg", bos.toByteArray())
            val meta = JSONObject()
                .put("camId", prefs.camId).put("width", frame.width).put("height", frame.height)
                .put("time", ts).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("android", Build.VERSION.RELEASE)
                .put("calib", JSONObject(calibText))
            MediaSaver.save(this, "raw_$ts.json", "application/json", meta.toString(2).toByteArray())
            bevBmp?.let { b ->
                val png = ByteArrayOutputStream()
                b.compress(Bitmap.CompressFormat.PNG, 100, png)
                MediaSaver.save(this, "bev_$ts.png", "image/png", png.toByteArray())
            }
            toast("已存：$path")
        } catch (t: Throwable) {
            toast("存檔失敗：${t.message}")
        }
    }

    private fun toast(s: String) = runOnUiThread {
        Toast.makeText(this, s, Toast.LENGTH_LONG).apply { setGravity(Gravity.CENTER, 0, 0) }.show()
    }

    private fun r2(f: Float): Double = Math.round(f * 100f) / 100.0

    /** 曲率是 0.0x 量級（半徑 30 m → 0.033），捨到小數 2 位會整個變 0 */
    private fun r4(f: Float): Double = Math.round(f * 10000f) / 10000.0

    private fun ema(prev: Float, v: Float) = if (prev == 0f) v else prev * 0.8f + v * 0.2f

    /** 保持固定長寬比、置中的 FrameLayout。 */
    private class AspectFrame(context: Context, private val ratio: Float) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            var cw = w
            var ch = (w / ratio).toInt()
            if (ch > h) {
                ch = h
                cw = (h * ratio).toInt()
            }
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY)
            )
        }
    }
}
