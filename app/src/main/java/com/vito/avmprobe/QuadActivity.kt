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
import android.os.SystemClock
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
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
        private const val OBJ_TTL_MS = 1000L
        private const val TL_TTL_MS = 1200L
        private const val BEV_TTL_MS = 1500L
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
    private var geo: AvmGeometry? = null
    private var calibText = "{}"
    private var calibrated = false
    private var geoError: String? = null
    private var started = false

    // ---- 以下只在 frames 執行緒存取 ----
    private val frame = YuvFrame()
    private var yolo: Yolo? = null
    private var yoloError: String? = null
    private val roadEx = RoadExtractor()
    private val roadTrack = RoadTracker()
    private val coneDet = ConeDetector()
    private val slotDet = ParkingSlotDetector()
    private val slotTrack = SlotTracker()
    private var slots: List<ParkingSlot> = emptyList()
    private var slotsAt = 0L
    private var lut: IntArray? = null
    private var lutKey = 0L
    private var bevArgb = IntArray(0)
    private var bevBmp: Bitmap? = null
    private val jpeg = ByteArrayOutputStream(96 * 1024)
    private var slotIdx = 0
    private var frameNo = 0L
    private val camResults = HashMap<String, CamResult>()
    private var tl: TlResult? = null
    private var road: RoadResult? = null
    private var roadAt = 0L
    private var cones: List<Cone> = emptyList()
    private var conesAt = 0L
    private var groundUrl: String? = null
    private var groundAt = 0L
    private var previewArgb = IntArray(0)
    private var previewBmp: Bitmap? = null
    private var emaInfer = 0f
    private var emaProc = 0f
    private var emaFps = 0f
    private var lastFrameAt = 0L

    @Volatile private var saveRawRequested = false
    @Volatile private var camState = "啟動中…"

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok -> if (ok && started) source.start() else if (!ok) camState = "需要相機權限" }

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
        return root
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            source.start()
        } else {
            permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStop() {
        super.onStop()
        started = false
        source.stop()       // 一定要放掉鏡頭：倒車時全景360 要用
        web.view.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        source.frames.post {
            try { yolo?.close() } catch (_: Throwable) {}
            yolo = null
        }
        source.release()
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

        val qw = frame.width / 2
        val qh = frame.height / 2
        val slots = slots()
        if (slots.isNotEmpty()) {
            val s = slots[(slotIdx++) % slots.size]
            if (s == SLOT_TL) runTrafficLightPass(det, g, qw, qh) else runCamera(det, g, s, qw, qh)
        }
        val now = SystemClock.elapsedRealtime()
        if (prefs.camBev && frameNo % 2 == 0L) runBev(g, now)

        val proc = (SystemClock.elapsedRealtime() - t0).toFloat()
        emaProc = ema(emaProc, proc)
        if (lastFrameAt != 0L) emaFps = ema(emaFps, 1000f / max(1L, t0 - lastFrameAt))
        lastFrameAt = t0

        pushPayload(g, det, now)
    }

    /** 偵測順序：左右兩側優先（行車時主要看旁車），前後各一次，紅綠燈再插一輪。 */
    private fun slots(): List<String> {
        val en = prefs.camDetect.mapNotNull { LETTER[it] }.toSet()
        val out = listOf("left", "right", "front", "left", "right", "rear").filter { it in en }.toMutableList()
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

    private fun runCamera(det: Yolo, g: AvmGeometry, name: String, qw: Int, qh: Int) {
        val cam = g.cam(name) ?: return
        val qx0 = (cam.quad % 2) * qw
        val qy0 = (cam.quad / 2) * qh
        val (dets, inf) = det.detectWith(qw, qh, qx0.toFloat(), qy0.toFloat()) { buf, nchw, size, lb ->
            frame.fillModelInput(qx0, qy0, qw, qh, buf, nchw, size, lb)
        }
        emaInfer = ema(emaInfer, inf.toFloat())
        val now = SystemClock.elapsedRealtime()
        val objs = ArrayList<WObj>()
        val boxes = ArrayList<QuadOverlayView.Box>()
        for (d in dets) {
            if (d.type == ObjTypes.LIGHT) {
                if (name == "front") considerTrafficLight(g, cam, d, qx0, qy0, qw, qh, now)
                continue
            }
            val u = d.rect.centerX() - qx0
            val v = d.rect.bottom - qy0
            // 框貼著畫面底邊又很寬：拍到自己的車身（引擎蓋、車門、保險桿）
            if (v > qh * 0.97f && d.rect.width() > qw * 0.55f) continue
            val w = toWorld(g, cam, u.toDouble(), v.toDouble(), qw, qh, d.type) ?: continue
            objs.add(WObj(d.type, w[0], w[1], d.score, w[2]))
            boxes.add(QuadOverlayView.Box(
                RectF(d.rect), "${NAMES[d.type] ?: d.label} ${"%.1f".format(w[2])}m", w[2] < 1.2f))
        }
        camResults[name] = CamResult(now, objs, boxes)
    }

    private fun runTrafficLightPass(det: Yolo, g: AvmGeometry, qw: Int, qh: Int) {
        val cam = g.cam("front") ?: return
        val qx0 = (cam.quad % 2) * qw
        val qy0 = (cam.quad / 2) * qh
        // 前鏡頭上半部（地平線以上）放大再偵測一次，紅綠燈在魚眼裡很小
        val rx0 = qx0 + (qw * 0.15f).toInt()
        val rw = (qw * 0.7f).toInt()
        val rh = (qh * 0.5f).toInt()
        val (dets, inf) = det.detectWith(rw, rh, rx0.toFloat(), qy0.toFloat()) { buf, nchw, size, lb ->
            frame.fillModelInput(rx0, qy0, rw, rh, buf, nchw, size, lb)
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
     * 回傳 (x, z, 離車身距離)；落在自車車身上（拍到自己）回傳 null。
     */
    private fun toWorld(g: AvmGeometry, cam: CamParams, u: Double, v: Double, qw: Int, qh: Int, t: String): FloatArray? {
        val p = g.pixelToGround(cam, u, v, qw, qh)
        val nx: Double
        val nz: Double
        if (p != null && hypot(p[0] - cam.px, p[1] - cam.pz) < 20.0) {
            nx = p[0]; nz = p[1]
        } else {
            // 框底在地平線以上（幾何還沒校正或物件很遠）：放在該方位 15 m
            val ray = g.pixelToRay(cam, u, v, qw, qh)
            val az = atan2(ray[0], ray[2])
            nx = cam.px + sin(az) * 15.0
            nz = cam.pz + cos(az) * 15.0
        }
        if (abs(nx) < EGO_HW + 0.05 && abs(nz) < EGO_HL + 0.05) return null
        val dx = nx - cam.px
        val dz = nz - cam.pz
        val d = max(hypot(dx, dz), 1e-3)
        val rad = ObjTypes.radius(t)
        val clr = hypot(max(abs(nx) - EGO_HW, 0.0), max(abs(nz) - EGO_HL, 0.0)).toFloat()
        return floatArrayOf((nx + dx / d * rad).toFloat(), (nz + dz / d * rad).toFloat(), clr)
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
        frame.sampleLut(table, bevArgb)
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

        cones = coneDet.detect(bevArgb, bw, bh, mpp, cx, cy, excl)
        conesAt = now
        // 停車格的線常被停著的車蓋住，車子另外傳進去判斷「有車」，不當排除區
        slots = slotTrack.update(slotDet.detect(bevArgb, bw, bh, mpp, cx, cy, listOf(ego), vehicles), now)
        slotsAt = now
        val mode = prefs.groundMode
        if (mode == AppPrefs.GROUND_VIRTUAL) {
            road = roadTrack.update(roadEx.extract(bevArgb, bw, bh, mpp, cx, cy, excl), now)
            roadAt = now
        }
        if (mode == AppPrefs.GROUND_REAL) {
            var bmp = bevBmp
            if (bmp == null || bmp.width != bw || bmp.height != bh) {
                bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                bevBmp = bmp
            }
            bmp.setPixels(bevArgb, 0, bw, 0, 0, bw, bh)
            jpeg.reset()
            bmp.compress(Bitmap.CompressFormat.JPEG, 65, jpeg)
            groundUrl = "data:image/jpeg;base64," + Base64.encodeToString(jpeg.toByteArray(), Base64.NO_WRAP)
            groundAt = now
        }
    }

    private fun pushPayload(g: AvmGeometry, det: Yolo, now: Long) {
        val objs = JSONArray()
        val boxes = ArrayList<QuadOverlayView.Box>()
        for (r in camResults.values) if (now - r.at < OBJ_TTL_MS) boxes.addAll(r.boxes)
        for (o in currentObjects(now)) {
            objs.put(JSONObject().put("t", o.t).put("x", r2(o.x)).put("z", r2(o.z))
                .put("c", r2(o.c)).put("yaw", 0))
        }
        if (now - conesAt < BEV_TTL_MS) {
            for (c in cones) objs.put(JSONObject().put("t", "cone").put("x", r2(c.x)).put("z", r2(c.z)).put("c", r2(c.conf)))
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
                    .put("c", r2(l.conf)))
            }
            p.put("road", JSONObject().put("lines", lines)
                .put("el", rd.edgeL?.let { r2(it) } ?: JSONObject.NULL)
                .put("er", rd.edgeR?.let { r2(it) } ?: JSONObject.NULL))
        }
        if (now - slotsAt < BEV_TTL_MS && slots.isNotEmpty()) p.put("slots", slotsJson(slots, false))
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
