package com.vito.avmprobe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.media.Image
import android.media.ImageReader
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * B 模式（影格共用）的核心：跑在**原廠 360 App 的 process 裡**（由注入的 Hook 叫起），
 * 或以我們 App 自己的 process 跑也行。
 *
 * - 它開一個 [ImageReader]（1920x1080 YUV），把那個 Surface 透過 Binder 交給注入在原廠相機
 *   流程裡的 FrameTap；FrameTap 把它加進原廠的 capture session → 相機一次開、原廠 bowl 跟
 *   我們同時拿到同一份四分割影格。
 * - 收到影格就跑跟 [QuadActivity] 一樣的偵測管線，結果用 [Bev3DPanel] 疊在原廠 bowl 上面。
 *
 * 整條管線是從 QuadActivity 搬過來的同一套邏輯（這邊沒有鏡頭預覽／2D 疊圖，所以精簡掉那部分），
 * 這樣標準版 App 不受影響，B 壞也只壞這裡。詳見 docs/frame-share.md。
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        /** Binder transaction：回傳我們 ImageReader 的 Surface，給原廠 process 加進 capture session。 */
        const val TRANSACT_GET_SURFACE = IBinder.FIRST_CALL_TRANSACTION + 0
        private const val CAP_W = 1920
        private const val CAP_H = 1080
        private const val OBJ_TTL_MS = 1300L
        private const val TL_TTL_MS = 1200L
        private const val BEV_TTL_MS = 1500L
        private const val OBS_TTL_MS = 4000L
        private const val EGO_HW = 0.925f
        private const val EGO_HL = 2.42f
        private const val SLOT_TL = "tl"
        private const val CH_ID = "avm_overlay"
        private const val NOTI_ID = 42
        private val NAMES = mapOf(
            "car" to "轎車", "van" to "箱型車", "truck" to "卡車", "bus" to "公車", "moto" to "機車",
            "bigbike" to "重機", "bike" to "腳踏車", "ped" to "行人", "cone" to "三角錐"
        )
        private val LETTER = mapOf('F' to "front", 'B' to "rear", 'L' to "left", 'R' to "right")
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

    // Binder：原廠 process 來拿 Surface。只回傳 Surface，不接受任何指令，不碰原廠的東西。
    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == TRANSACT_GET_SURFACE) {
                val s = reader?.surface
                if (reply != null) {
                    if (s != null) { reply.writeInt(1); s.writeToParcel(reply, 0) }
                    else reply.writeInt(0)
                }
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private lateinit var prefs: AppPrefs
    private lateinit var windowManager: WindowManager
    private var panel: Bev3DPanel? = null
    private var reader: ImageReader? = null

    private var geo: AvmGeometry? = null
    private var calibText = "{}"
    private var calibrated = false

    // ── frames 執行緒 ──
    private val camThread = HandlerThread("overlay-frames").apply { start() }
    private val cam = Handler(camThread.looper)
    private val frame = YuvFrame()
    private var yolo: Yolo? = null
    private var yoloError: String? = null
    private var lut: IntArray? = null
    private var lutKey = 0L
    private var slotIdx = 0
    private val camResults = HashMap<String, CamResult>()
    private var tl: TlResult? = null
    private val trackMgr = TrackManager(camPos = TrackManager.DEFAULT_CAMS.mapValues { it.value.copyOf(2) })
    private val lca = LaneChangeAssist()
    private var tracks: List<Track> = emptyList()
    private var tracksAt = 0L
    @Volatile private var lcaState: LcaState? = null
    private val startAssist = StartAssist()
    private val ldw = LaneDepartureWarning()
    @Volatile private var startState: StartAssist.State? = null
    @Volatile private var ldwState: LaneDepartureWarning.State? = null
    private var lastBeepAt = 0L
    private var tone: android.media.ToneGenerator? = null

    // ── ground 執行緒（俯視圖辨識）──
    private val groundThread = HandlerThread("overlay-ground").apply { start() }
    private val ground = Handler(groundThread.looper)
    @Volatile private var groundBusy = false
    private var bevArgb = IntArray(0)
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
    private var heightDet: HeightDetector? = null
    private var heightArgb = IntArray(0)
    @Volatile private var obstacles: List<Obstacle> = emptyList()
    @Volatile private var obstaclesAt = 0L
    private var heightEvery = 0L
    @Volatile private var heightBusy = false
    @Volatile private var groundUrl: String? = null
    @Volatile private var groundAt = 0L
    @Volatile private var bevVehicles = 0

    private var emaInfer = 0f
    private var emaProc = 0f
    private var emaFps = 0f
    private var lastFrameAt = 0L
    @Volatile private var frames = 0L
    @Volatile private var mapSrc: MapSource? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            calibText = assets.open("avm_calib.json").bufferedReader().readText()
            geo = AvmGeometry.fromJson(calibText)
            calibrated = JSONObject(calibText).optBoolean("calibrated", false)
        } catch (t: Throwable) {
            Log.e(TAG, "校正讀取失敗：${t.message}", t)
        }
        startForeground(NOTI_ID, buildNotification())

        reader = ImageReader.newInstance(CAP_W, CAP_H, android.graphics.ImageFormat.YUV_420_888, 3).apply {
            setOnImageAvailableListener({ rd ->
                val img = try { rd.acquireLatestImage() } catch (_: Throwable) { null }
                    ?: return@setOnImageAvailableListener
                try { onFrame(img) } catch (t: Throwable) { Log.e(TAG, "frame 失敗：${t.message}", t) }
                finally { img.close() }
            }, cam)
        }
        showPanel()
        startMap()
        Log.i(TAG, "OverlayService 起來了，等原廠相機把影格餵進我們的 Surface")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /** 整張螢幕的透明懸浮窗，裡面是 3D 面板 → 疊在原廠 bowl 上。 */
    private fun showPanel() {
        if (panel != null) return
        val dm = resources.displayMetrics
        panel = Bev3DPanel(this, windowManager) {}.also {
            it.show(Rect(0, 0, dm.widthPixels, dm.heightPixels))
        }
    }

    private fun startMap() {
        if (mapSrc != null) return
        val m = MapSource(this)
        if (m.start()) mapSrc = m
    }

    // ───────────────────────────── 每幀：跟 QuadActivity 同一套管線 ─────────────────────────────

    private fun onFrame(img: Image) {
        val t0 = SystemClock.elapsedRealtime()
        frame.load(img)
        val g = geo ?: return
        frames++

        val det = yolo ?: initYolo() ?: return
        val vs = VehicleBus.signals.snapshot()
        val qw = frame.width / 2
        val qh = frame.height / 2
        val sl = slots()
        if (sl.isNotEmpty()) {
            val s = sl[(slotIdx++) % sl.size]
            if (s == SLOT_TL) runTrafficLightPass(det, g, qw, qh) else runCamera(det, g, s, qw, qh, vs, t0)
        }
        val now = SystemClock.elapsedRealtime()
        if (prefs.camBev && !groundBusy) runBev(g, now)

        emaProc = ema(emaProc, (SystemClock.elapsedRealtime() - t0).toFloat())
        if (lastFrameAt != 0L) emaFps = ema(emaFps, 1000f / max(1L, t0 - lastFrameAt))
        lastFrameAt = t0
        pushPayload(g, det, now, vs)
    }

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
            yoloError = "模型載入失敗：${t.message}"; Log.e(TAG, yoloError!!, t); null
        }
    }

    private fun runCamera(det: Yolo, g: AvmGeometry, slot: String, qw: Int, qh: Int, vs: VehicleState, atMs: Long) {
        val name = slot.substringBefore(':')
        val camP = g.cam(name) ?: return
        val qx0 = (camP.quad % 2) * qw
        val qy0 = (camP.quad / 2) * qh
        val side = qh.coerceAtMost(qw)
        val rx0 = qx0 + when (slot.substringAfter(':')) {
            "a" -> 0; "b" -> qw - side; else -> (qw - side) / 2
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
                if (name == "front") considerTrafficLight(g, camP, d, qx0, qy0, qw, qh, now)
                continue
            }
            val u = d.rect.centerX() - qx0
            val v = d.rect.bottom - qy0
            if (v > qh * 0.97f && d.rect.width() > side * 0.85f) continue
            val w = toWorld(g, camP, u.toDouble(), v.toDouble(), qw, qh, d.type) ?: continue
            objs.add(WObj(d.type, w[0], w[1], d.score, w[2]))
            if (w[3] > 0f) tDets.add(Det(d.type, w[0], w[1], d.score, cam = name, atMs = atMs))
            boxes.add(QuadOverlayView.Box(RectF(d.rect), "${NAMES[d.type] ?: d.label} ${"%.1f".format(w[2])}m", w[2] < 1.2f))
        }
        camResults[slot] = CamResult(now, objs, boxes)

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
        val t = tl
        val tlFresh = t != null && now - t.at < TL_TTL_MS
        startState = startAssist.update(
            now, vs.speedKmh,
            currentObjects(now).map { StartAssist.Obj(it.t, it.x, it.z) },
            if (tlFresh) t!!.s else null, if (tlFresh) t!!.c else 0f
        )
        ldwState = ldw.update(
            now, vs.speedKmh,
            if (rd != null && now - roadAt < BEV_TTL_MS) rd.lines else emptyList(), vs.turn
        )
        beepIfNeeded(now)
    }

    private fun beepIfNeeded(now: Long) {
        val st = startState; val ld = ldwState
        val want = (st != null && (st.lead || st.green)) || (ld != null && ld.any)
        if (!want || now - lastBeepAt < 2000L) return
        lastBeepAt = now
        try {
            val tg = tone ?: android.media.ToneGenerator(
                android.media.AudioManager.STREAM_NOTIFICATION, 80
            ).also { tone = it }
            tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 250)
        } catch (_: Throwable) { tone = null }
    }

    private fun egoMps(vs: VehicleState): Float? =
        vs.speedKmh?.div(3.6f)?.let { if (vs.gear == 'R') -it else it }

    private fun yaw(tr: Track, ego: Float?): Float? {
        if (ego == null || tr.t == "cone" || tr.t == "ped") return null
        val avx = tr.vx
        val avz = tr.vz + ego
        val sp = hypot(avx, avz)
        if (sp < 1.5f || hypot(tr.svx, tr.svz) > sp) return null
        return atan2(avx, avz)
    }

    private fun laneAt0(rd: RoadResult): FloatArray {
        var lx = Float.NaN; var rx = Float.NaN; var la = Float.NaN; var ra = Float.NaN
        for (l in rd.lines) {
            var dx = l.x2 - l.x1; var dz = l.z2 - l.z1
            if (dz < 0f) { dx = -dx; dz = -dz }
            if (dz < 1f) continue
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
        val camP = g.cam("front") ?: return
        val qx0 = (camP.quad % 2) * qw
        val qy0 = (camP.quad / 2) * qh
        val rHor = (camP.fx * qw * Math.toRadians(camP.pitch)).toFloat()
        val side = (qh * 0.55f).toInt().coerceAtMost(qw)
        val cxPx = (camP.cx * qw).toFloat()
        val cyPx = (camP.cy * qh).toFloat()
        val rx0 = (qx0 + (cxPx - side / 2f)).toInt().coerceIn(qx0, qx0 + qw - side)
        val ry0 = (qy0 + (cyPx - rHor - side * 0.55f)).toInt().coerceIn(qy0, qy0 + qh - side)
        val (dets, inf) = det.detectWith(side, side, rx0.toFloat(), ry0.toFloat()) { buf, nchw, size, lb ->
            frame.fillModelInput(rx0, ry0, side, side, buf, nchw, size, lb)
        }
        emaInfer = ema(emaInfer, inf.toFloat())
        val now = SystemClock.elapsedRealtime()
        for (d in dets) if (d.type == ObjTypes.LIGHT) considerTrafficLight(g, camP, d, qx0, qy0, qw, qh, now)
    }

    private fun considerTrafficLight(
        g: AvmGeometry, camP: CamParams, d: Detection, qx0: Int, qy0: Int, qw: Int, qh: Int, now: Long
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
        val ray = g.pixelToRay(camP, (r.centerX() - qx0).toDouble(), (r.centerY() - qy0).toDouble(), qw, qh)
        val ax = Math.toDegrees(atan2(ray[0], ray[2])).toFloat()
        tl = TlResult(now, state, score, ax, RectF(r))
    }

    private fun toWorld(g: AvmGeometry, camP: CamParams, u: Double, v: Double, qw: Int, qh: Int, t: String): FloatArray? {
        val p = g.pixelToGround(camP, u, v, qw, qh)
        val nx: Double; val nz: Double; val real: Float
        if (p != null && hypot(p[0] - camP.px, p[1] - camP.pz) < 20.0) {
            nx = p[0]; nz = p[1]; real = 1f
        } else {
            val ray = g.pixelToRay(camP, u, v, qw, qh)
            val az = atan2(ray[0], ray[2])
            nx = camP.px + Math.sin(az) * 15.0
            nz = camP.pz + Math.cos(az) * 15.0
            real = 0f
        }
        if (abs(nx) < EGO_HW + 0.05 && abs(nz) < EGO_HL + 0.05) return null
        val dx = nx - camP.px; val dz = nz - camP.pz
        val d = max(hypot(dx, dz), 1e-3)
        val rad = ObjTypes.radius(t)
        val clr = hypot(max(abs(nx) - EGO_HW, 0.0), max(abs(nz) - EGO_HL, 0.0)).toFloat()
        return floatArrayOf((nx + dx / d * rad).toFloat(), (nz + dz / d * rad).toFloat(), clr, real)
    }

    private fun currentObjects(now: Long): List<WObj> {
        val all = ArrayList<WObj>()
        for (r in camResults.values) if (now - r.at < OBJ_TTL_MS) all.addAll(r.objs)
        all.sortByDescending { it.c }
        val kept = ArrayList<WObj>()
        for (o in all) if (kept.none { it.t == o.t && hypot(it.x - o.x, it.z - o.z) < 1.5f }) kept.add(o)
        return kept
    }

    private fun runBev(g: AvmGeometry, now: Long) {
        val key = (frame.width.toLong() shl 32) or frame.height.toLong()
        var table = lut
        if (table == null || lutKey != key) {
            table = g.buildBevLut(frame.width, frame.height)
            lut = table; lutKey = key
            bevArgb = IntArray(g.bev.width * g.bev.height)
        }
        val bw = g.bev.width; val bh = g.bev.height
        val mpp = g.bev.mpp.toFloat(); val cx = g.bev.originX.toFloat(); val cy = g.bev.originY.toFloat()
        val ego = floatArrayOf(-EGO_HW - 0.15f, -EGO_HL - 0.15f, EGO_HW + 0.15f, EGO_HL + 0.15f)
        val excl = ArrayList<FloatArray>(); val vehicles = ArrayList<FloatArray>()
        excl.add(ego)
        for (o in currentObjects(now)) {
            val hl = HALF[o.t] ?: continue
            val r = floatArrayOf(o.x - hl[0], o.z - hl[1], o.x + hl[0], o.z + hl[1])
            excl.add(r)
            if (o.t == "car" || o.t == "van" || o.t == "truck" || o.t == "bus") vehicles.add(r)
        }
        bevVehicles = vehicles.size
        val spd = VehicleBus.signals.snapshot().speedKmh
        val slow = spd == null || spd < 15f
        if (prefs.camObstacle && slow && !heightBusy && (heightEvery++ % 4L == 0L)) {
            val hw = frame.width; val hh = frame.height
            val hd = heightDet ?: HeightDetector(g).also { heightDet = it }
            if (heightArgb.size != hw * hh) heightArgb = IntArray(hw * hh)
            frame.fillArgbAt(hd.pixelsNeeded(hw, hh), heightArgb)
            val buf2 = heightArgb
            heightBusy = true
            ground.post {
                try { obstacles = hd.detect(buf2, hw, hh); obstaclesAt = SystemClock.elapsedRealtime() }
                catch (t: Throwable) { obstacles = emptyList() } finally { heightBusy = false }
            }
        }
        frame.sampleLut(table, bevArgb)
        groundBusy = true
        val buf = bevArgb
        val mode = prefs.groundMode
        ground.post {
            try {
                cones = coneDet.detect(buf, bw, bh, mpp, cx, cy, excl); conesAt = now
                slots = slotTrack.update(slotDet.detect(buf, bw, bh, mpp, cx, cy, listOf(ego), vehicles), now); slotsAt = now
                if (mode == AppPrefs.GROUND_VIRTUAL) {
                    road = roadTrack.update(roadEx.extract(buf, bw, bh, mpp, cx, cy, excl, g.ipmValidMask()), now); roadAt = now
                }
                if (mode == AppPrefs.GROUND_REAL) {
                    var bmp = bevBmp
                    if (bmp == null || bmp.width != bw || bmp.height != bh) {
                        bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888); bevBmp = bmp
                    }
                    bmp.setPixels(buf, 0, bw, 0, 0, bw, bh)
                    jpeg.reset(); bmp.compress(Bitmap.CompressFormat.JPEG, 65, jpeg)
                    groundUrl = "data:image/jpeg;base64," + Base64.encodeToString(jpeg.toByteArray(), Base64.NO_WRAP)
                    groundAt = now
                }
            } catch (t: Throwable) { Log.e(TAG, "俯視辨識失敗：${t.message}") } finally { groundBusy = false }
        }
    }

    private fun pushPayload(g: AvmGeometry, det: Yolo, now: Long, vs: VehicleState) {
        val objs = JSONArray()
        val ego = egoMps(vs)
        val coneOut = ArrayList<WObj>(); val used = HashSet<Int>()
        for (o in currentObjects(now)) {
            if (o.t == "cone") coneOut.add(o)
            var best: Track? = null; var bd = 1.5f
            if (now - tracksAt < OBJ_TTL_MS) for (tr in tracks) {
                if (!tr.confirmed || tr.t != o.t || tr.id in used) continue
                val d = hypot(tr.x - o.x, tr.z - o.z); if (d < bd) { bd = d; best = tr }
            }
            val j = JSONObject().put("t", o.t).put("x", r2(o.x)).put("z", r2(o.z)).put("c", r2(o.c))
            best?.let { tr ->
                used.add(tr.id)
                j.put("id", tr.id).put("vx", r2(tr.vx)).put("vz", r2(tr.vz))
                yaw(tr, ego)?.let { y -> j.put("yaw", r2(y)) }
            }
            objs.put(j)
        }
        if (now - conesAt < BEV_TTL_MS) for (c in cones) {
            if (coneOut.any { hypot(it.x - c.x, it.z - c.z) < 1f }) continue
            objs.put(JSONObject().put("t", "cone").put("x", r2(c.x)).put("z", r2(c.z)).put("c", r2(c.conf)))
        }
        val bev = g.bev
        val p = JSONObject()
            .put("objs", objs)
            .put("wm", r2((bev.xMax - bev.xMin).toFloat())).put("hm", r2((bev.zMax - bev.zMin).toFloat()))
            .put("flip", false).put("overlap", false).put("src", "cam")
            .put("stat", JSONObject().put("fps", r2(emaFps)).put("inf", emaInfer.toInt())
                .put("e2e", emaProc.toInt()).put("eng", det.engineName).put("res", det.inputSize))
        val mode = prefs.groundMode
        if (mode == AppPrefs.GROUND_REAL && now - groundAt < BEV_TTL_MS) groundUrl?.let { p.put("ground", it) }
        val rd = road
        if (mode == AppPrefs.GROUND_VIRTUAL && rd != null && now - roadAt < BEV_TTL_MS) {
            val lines = JSONArray()
            for (l in rd.lines) lines.put(JSONObject().put("k", l.kind.toString()).put("d", if (l.dashed) 1 else 0)
                .put("x1", r2(l.x1)).put("z1", r2(l.z1)).put("x2", r2(l.x2)).put("z2", r2(l.z2))
                .put("c", r2(l.conf)).put("kv", r4(l.k)))
            p.put("road", JSONObject().put("lines", lines)
                .put("el", rd.edgeL?.let { r2(it) } ?: JSONObject.NULL)
                .put("er", rd.edgeR?.let { r2(it) } ?: JSONObject.NULL))
        }
        if (now - slotsAt < BEV_TTL_MS && slots.isNotEmpty()) {
            p.put("slots", slotsJson(slots, false))
            if (spd(vs)) parkPathJson(slots)?.let { p.put("park", it) }
        }
        val obs = obstacles
        if (now - obstaclesAt < OBS_TTL_MS && obs.isNotEmpty()) {
            val oa = JSONArray()
            for (o in obs) oa.put(JSONObject().put("x", r2(o.x)).put("z", r2(o.z))
                .put("w", r2(o.w)).put("l", r2(o.l)).put("c", r2(o.conf)))
            p.put("obs", oa)
        }
        TurnPathPlanner.plan(vs.turn, vs.steerDeg, vs.speedKmh)?.let { tp ->
            val outer = JSONArray(); for (q in tp.pts) outer.put(JSONArray().put(r2(q[0])).put(r2(q[1])))
            val inner = JSONArray(); for (q in tp.inner) inner.put(JSONArray().put(r2(q[0])).put(r2(q[1])))
            val hits = TurnPathPlanner.conflicts(tp, currentObjects(now).map { StartAssist.Obj(it.t, it.x, it.z) })
            p.put("turn", JSONObject().put("o", outer).put("i", inner).put("d", tp.dir.toString())
                .put("r", r2(tp.radius)).put("hit", hits.size))
        }
        startState?.let { st -> if (st.lead || st.green) p.put("start", JSONObject()
            .put("lead", st.lead).put("green", st.green).put("gap", r2(st.gap))) }
        ldwState?.let { ld -> if (ld.any) p.put("ldw", JSONObject().put("l", ld.left).put("r", ld.right)) }
        if (!vs.isEmpty) p.put("veh", VehicleSignals.toJson(vs))
        if (now - tracksAt < OBJ_TTL_MS) lcaState?.let { p.put("lca", lca.toJson(it)) }
        mapSrc?.takeJsonIfNew()?.let { p.put("map", it) }
        val t = tl
        if (t != null && now - t.at < TL_TTL_MS) p.put("tl", JSONObject().put("s", t.s).put("c", r2(t.c)).put("ax", r2(t.ax)))
        panel?.push(p.toString())
    }

    private fun spd(vs: VehicleState): Boolean { val s = vs.speedKmh; return s == null || s < 15f }

    private fun parkPathJson(list: List<ParkingSlot>): JSONObject? {
        val free = list.filter { !it.occupied && (it.kind == 'p' || it.kind == 'a') }
            .sortedBy { s -> (0 until 4).minOf { hypot(s.p[it * 2], s.p[it * 2 + 1]) } }
        for (s in free.take(3)) {
            val plan = ParkingPathPlanner.plan(s) ?: continue
            if (!plan.ok || plan.points.size < 2) continue
            val pts = JSONArray(); for (q in plan.points) pts.put(JSONArray().put(r2(q[0])).put(r2(q[1])))
            return JSONObject().put("pts", pts).put("rev", plan.reverseFrom).put("r", r2(plan.turnRadius))
                .put("goal", JSONArray().put(r2(plan.goal[0])).put(r2(plan.goal[1])).put(r4(plan.goal[2])))
        }
        return null
    }

    private fun r2(f: Float): Double = Math.round(f * 100f) / 100.0
    private fun r4(f: Float): Double = Math.round(f * 10000f) / 10000.0
    private fun ema(prev: Float, v: Float) = if (prev == 0f) v else prev * 0.8f + v * 0.2f

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(NotificationChannel(CH_ID, "AVM Overlay", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CH_ID)
            .setContentTitle("AVM 偵測疊圖執行中")
            .setContentText("跟原廠 360 共用影格")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        panel?.hide(); panel = null
        try { reader?.close() } catch (_: Throwable) {}
        reader = null
        mapSrc?.stop(); mapSrc = null
        cam.post { try { yolo?.close() } catch (_: Throwable) {}; yolo = null }
        camThread.quitSafely(); groundThread.quitSafely()
        tone?.release(); tone = null
    }
}
