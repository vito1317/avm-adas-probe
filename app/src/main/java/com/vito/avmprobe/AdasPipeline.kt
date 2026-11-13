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
 * 偵測管線本體（從 OverlayService 抽出來）：吃四分割 YUV 影格，跑 YOLO、追蹤、俯視圖
 * （道路、停車格、三角錐、障礙物）、起步／LDW／變道／紅綠燈／轉彎與倒車路徑，
 * 每幀把結果組成一份 JSON（跟 3D 面板同一份格式）交給 [sink]。
 *
 * 誰顯示結果由呼叫端決定：
 *  - [OverlayService]（B 模式，兩個 App）→ 3D 面板。
 *  - [Engine360]（合併進原廠 360）→ 直接畫在原廠畫面上的標注圖層。
 *
 * [onFrame] 在呼叫端的影格執行緒上跑；[close] 也要在同一條執行緒上呼叫。
 */
class AdasPipeline(
    private val ctx: Context,
    private val prefs: AppPrefs,
    private val sink: (JSONObject) -> Unit
) {
    companion object {
        private const val TAG = "AdasPipeline"
        private const val OBJ_TTL_MS = 1300L
        // 車頭朝向模型：信心（8 個方向機率的向量長度）≥ 0.7 才用（驗證：涵蓋約 35%，誤差 60° 內 88%、前後顛倒 7%；再加每台車平滑）
        private const val ORIENT_MIN_CONF = 0.7f
        private const val TL_TTL_MS = 1200L
        private const val BEV_TTL_MS = 1500L
        private const val OBS_TTL_MS = 4000L
        private const val EGO_HW = 0.925f
        private const val EGO_HL = 2.42f
        private const val SLOT_TL = "tl"
        /** 前方去畸變虛擬鏡頭的視角：寬看近處與隔壁車道，窄把遠處放大（約是魚眼原圖的兩倍大）。 */
        private const val FRONT_WIDE_FOV = 100.0
        private const val FRONT_NARROW_FOV = 50.0
        /** 紅綠燈：往上 14°、視角 40°（10～30 m 外的號誌都落在這格，而且被放大）。 */
        private const val TL_PITCH = -14.0
        private const val TL_FOV = 40.0
        /** 找燈點用的高解析畫面：窄一點、像素多一點（20 m 外的燈頭約 8 px）。 */
        private const val TL_HI_FOV = 34.0
        private const val TL_HI_SIZE = 512
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

    /** vyaw／vconf：車頭朝向模型看出來的方向（世界 yaw）與信心；沒跑或沒把握是 NaN／0。 */
    private class WObj(val t: String, val x: Float, val z: Float, val c: Float, val clr: Float,
                       val vyaw: Float = Float.NaN, val vconf: Float = 0f)
    private class CamResult(val at: Long, val objs: List<WObj>, val boxes: List<QuadOverlayView.Box>)
    private class TlResult(val at: Long, val s: String, val c: Float, val ax: Float, val box: RectF)

    private var geo: AvmGeometry? = null
    private var calibText = "{}"
    private var calibrated = false

    private val frame = YuvFrame()
    private var yolo: Yolo? = null
    private var yoloError: String? = null
    private var lut: IntArray? = null
    private var lutKey = 0L
    private var slotIdx = 0
    private val camResults = HashMap<String, CamResult>()
    /** 每個偵測位置兩次更新的間隔（EMA，ms）：降溫降速時每顆鏡頭隔很久才再偵測一次。 */
    private val camGap = HashMap<String, Float>()

    private fun putCamResult(slot: String, r: CamResult) {
        camResults[slot]?.let { old -> val g = (r.at - old.at).toFloat(); camGap[slot] = camGap[slot]?.let { it * 0.7f + g * 0.3f } ?: g }
        camResults[slot] = r
    }

    /**
     * 這個位置的偵測結果保留多久：至少 [OBJ_TTL_MS]，間隔變長就跟著拉長（間隔的 2.2 倍）。
     * 實車：車機太熱降速後，後鏡頭要隔將近 4 秒才再偵測一次，結果 1.3 秒就過期 → 框一直閃。
     */
    private fun ttlFor(slot: String): Long = maxOf(OBJ_TTL_MS, ((camGap[slot] ?: 0f) * 2.2f).toLong()).coerceAtMost(6000L)
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
    private var tone: android.media.ToneGenerator? = null

    // ── ground 執行緒（俯視圖辨識）──
    private val groundThread = HandlerThread("overlay-ground").apply { start() }
    private val ground = Handler(groundThread.looper)
    @Volatile private var groundBusy = false
    private var bevArgb = IntArray(0)
    private val roadEx = RoadExtractor()
    private val cwDet = CrosswalkDetector()
    // 車道線模型（自己訓練的魚眼分割模型）：四顆鏡頭輪流在自己的執行緒跑，結果拼成俯視圖給 RoadExtractor
    private var laneSeg: LaneSeg? = null
    private var laneSegFailed = false
    @Volatile private var segBusy = false
    private var segBev: FloatArray? = null
    private var segCounter = 0L
    private val segThread by lazy { android.os.Handler(android.os.HandlerThread("lane-seg").apply { start() }.looper) }
    private fun laneSegOrNull(): LaneSeg? {
        if (!prefs.laneModel || laneSegFailed || Crumbs.off("laneseg")) return null
        laneSeg?.let { return it }
        return try {
            if (!LaneSeg.available(ctx)) { laneSegFailed = true; null }
            else LaneSeg(ctx).also { laneSeg = it; Engine360.log("車道線模型已載入") }
        } catch (t: Throwable) { laneSegFailed = true; Engine360.log("車道線模型載入失敗：${t.message}"); null }
    }
    /** 最近一次模型跑一顆鏡頭花幾毫秒（設定頁顯示）。 */
    val laneSegMs: Long get() = laneSeg?.lastMs ?: -1L
    @Volatile private var cws: List<CrosswalkDetector.Crosswalk> = emptyList()
    @Volatile private var cwAt = 0L
    private val bjDet = BoxJunctionDetector()
    @Volatile private var bjs: List<BoxJunctionDetector.Box> = emptyList()
    @Volatile private var bjAt = 0L
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

    init {
        try {
            calibText = ctx.assets.open("avm_calib.json").bufferedReader().readText()
            geo = AvmGeometry.fromJson(calibText)
            geo?.let { g ->
                fun pos(n: String) = g.cam(n)?.let { floatArrayOf(it.px.toFloat(), it.py.toFloat(), it.pz.toFloat()) }
                FactoryViews.setCameras(pos("front"), pos("rear"), pos("left"), pos("right"))
                FactoryViews.followWheel = prefs.fpvFollowWheel; FactoryViews.wheelFlip = prefs.fpvWheelFlip
                ThermalGovernor.enabled = prefs.thermalThrottle
            }
            calibrated = JSONObject(calibText).optBoolean("calibrated", false)
        } catch (t: Throwable) {
            Log.e(TAG, "校正讀取失敗：${t.message}", t)
        }
        startMap()
    }

    @Volatile private var saveRawCb: ((String) -> Unit)? = null
    @Volatile private var bevCb: ((AutoCalibrator.Bev?) -> Unit)? = null
    private var snapLut: IntArray? = null
    private var snapLutKey = 0L

    /**
     * 每一幀偵測完、結果送出之後呼叫（影格執行緒）：行車記錄用來取這一幀的俯視圖與前方畫面。
     */
    @Volatile var afterFrame: ((AdasPipeline) -> Unit)? = null

    /** 這一幀的俯視圖畫進 [out]（geo.bev 的寬×高）。在影格執行緒上呼叫。 */
    fun renderBevInto(out: IntArray): Boolean {
        val g = geo ?: return false
        if (out.size != g.bev.width * g.bev.height || frame.width == 0) return false
        val key = (frame.width.toLong() shl 32) or frame.height.toLong()
        val table = snapLut?.takeIf { snapLutKey == key } ?: g.buildBevLut(frame.width, frame.height).also { snapLut = it; snapLutKey = key }
        frame.sampleLut(table, out)
        return true
    }

    /** 這一幀的前方去畸變畫面（寬視角）畫進 [out]（[size]×[size]）。在影格執行緒上呼叫。 */
    /**
     * 行車記錄器右半邊：跟著使用者在原廠切的視角（[Engine360.recordView]）——前鏡頭、後鏡頭（左右翻轉，跟原廠一樣）、
     * 打方向燈時的左／右鏡頭；原廠的 3D 視角我們畫不出來，就錄前方。
     */
    fun renderFrontInto(out: IntArray, size: Int): Boolean {
        val g = geo ?: return false
        if (frame.width == 0) return false
        val (cam, mirror) = try { Engine360.recordView() } catch (_: Throwable) { Pair("front", false) }
        val v = recFront?.takeIf { it.size == size && it.camName == cam } ?: try {
            when (cam) {
                "rear" -> VirtualView(g, "rear", 0.0, 12.0, 120.0, size)
                "left", "right" -> VirtualView(g, cam, 0.0, 18.0, 120.0, size)
                else -> VirtualView(g, "front", 0.0, 6.0, 110.0, size)
            }
        } catch (_: Throwable) { null }
        recFront = v ?: return false
        frame.sampleLut(v.lut(frame.width, frame.height), out)
        if (mirror) for (j in 0 until size) {
            var a = j * size; var b = a + size - 1
            while (a < b) { val t = out[a]; out[a] = out[b]; out[b] = t; a++; b-- }
        }
        return true
    }
    private var recFront: VirtualView? = null

    val bevWidth: Int get() = geo?.bev?.width ?: 0
    val bevHeight: Int get() = geo?.bev?.height ?: 0
    val bevMpp: Float get() = geo?.bev?.mpp?.toFloat() ?: 0.04f
    val bevOriginX: Float get() = geo?.bev?.originX?.toFloat() ?: 0f
    val bevOriginY: Float get() = geo?.bev?.originY?.toFloat() ?: 0f

    /** 下一幀算一張俯視圖給自動對位（不管「俯視辨識」有沒有開）。[done] 在影格執行緒上被呼叫。 */
    fun requestBev(done: (AutoCalibrator.Bev?) -> Unit) { bevCb = done }

    private fun bevSnapshot(g: AvmGeometry): AutoCalibrator.Bev? = try {
        // 自己的查表快取：不能動 runBev 的 lut／lutKey（它靠「查表剛建」來配置輸出緩衝）
        val key = (frame.width.toLong() shl 32) or frame.height.toLong()
        val table = snapLut?.takeIf { snapLutKey == key } ?: g.buildBevLut(frame.width, frame.height).also { snapLut = it; snapLutKey = key }
        val out = IntArray(g.bev.width * g.bev.height)
        frame.sampleLut(table, out)
        AutoCalibrator.Bev(out, g.bev.width, g.bev.height, g.bev.mpp.toFloat(), g.bev.originX.toFloat(), g.bev.originY.toFloat())
    } catch (_: Throwable) { null }
    @Volatile private var lastPayload: JSONObject? = null

    /**
     * 下一幀存一份原始資料到「下載/avm-adas」給開發者分析：四分割原圖（JPEG）、
     * 校正與裝置資訊，以及當下的偵測結果（payload）。[done] 在影格執行緒上被呼叫，帶結果文字。
     */
    fun requestSaveRaw(done: (String) -> Unit) { saveRawCb = done }

    private fun saveRaw(): String {
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        return try {
            val bos = ByteArrayOutputStream()
            android.graphics.YuvImage(frame.toNv21(), android.graphics.ImageFormat.NV21, frame.width, frame.height, null)
                .compressToJpeg(Rect(0, 0, frame.width, frame.height), 95, bos)
            val path = MediaSaver.save(ctx, "raw_$ts.jpg", "image/jpeg", bos.toByteArray())
            val meta = JSONObject()
                .put("width", frame.width).put("height", frame.height).put("time", ts)
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("android", Build.VERSION.RELEASE)
                .put("calib", JSONObject(calibText))
                .put("payload", lastPayload ?: JSONObject.NULL)
            MediaSaver.save(ctx, "raw_$ts.json", "application/json", meta.toString(2).toByteArray())
            "已存：$path"
        } catch (t: Throwable) {
            "存檔失敗：${t.message}"
        }
    }

    /** 收掉模型、執行緒、地圖、音效。在影格執行緒上呼叫。 */
    fun close() {
        mapSrc?.stop(); mapSrc = null
        try { yolo?.close() } catch (_: Throwable) {}
        yolo = null
        groundThread.quitSafely()
        tone?.release(); tone = null
    }

    private fun startMap() {
        if (mapSrc != null) return
        val m = MapSource(ctx)
        if (m.start()) mapSrc = m
    }

    // ───────────────────────────── 每幀：跟 QuadActivity 同一套管線 ─────────────────────────────

    /** 每一幀（在呼叫端的執行緒上跑）：偵測、追蹤、俯視圖，最後把結果交給 [sink]。 */
    fun onFrame(img: Image) { loadFrame(img); processFrame() }

    /**
     * 只把影像複製進來（幾毫秒）。呼叫端複製完就能馬上把 Image 還給相機——
     * 拿著 Image 跑完整套偵測（第一幀要載模型、建表，好幾秒）會讓原廠相機沒有緩衝可用而整個卡死、閃退（實車：一到 9 秒就閃退）。
     */
    fun loadFrame(img: Image) { Crumbs.step(Crumbs.FRAME, "load") { frame.load(img) } }

    /** 對 [loadFrame] 複製進來的那一幀跑偵測（可以在別的執行緒）。 */
    fun processFrame() {
        val t0 = SystemClock.elapsedRealtime()
        val g = geo ?: return
        frames++
        saveRawCb?.let { cb -> saveRawCb = null; cb(saveRaw()) }
        bevCb?.let { cb -> bevCb = null; cb(bevSnapshot(g)) }

        val det = yolo ?: initYolo() ?: return
        val vs = VehicleBus.signals.snapshot()
        val qw = frame.width / 2
        val qh = frame.height / 2
        val sl = slots()
        if (sl.isNotEmpty()) {
            val s = sl[(slotIdx++) % sl.size]
            Crumbs.at(Crumbs.FRAME, "det|$s")
            when {
                s == SLOT_TL -> runTrafficLightVirtual(det, g)
                s == "front:w" || s == "front:n" -> runFrontVirtual(det, g, s, vs, t0)
                else -> runCamera(det, g, s, qw, qh, vs, t0)
            }
        }
        val now = SystemClock.elapsedRealtime()
        Crumbs.step(Crumbs.FRAME, "ego") { accumulateEgoMotion(now) }
        // 在馬路上（90 秒內開過 30 km/h 以上、沒打 R）＝停等紅綠燈也算：不找停車格（實車：停紅燈時旁邊車道被當成格子）
        if ((vs.speedKmh ?: 0f) > 30f) lastFastAt = now
        // 停車場判斷（慢慢開、打 R／P、慢速大角度轉方向盤）：在停車場就不算馬路（從馬路開進來很快停進格子也認得到）
        val gearNow = CanVehicle.info.gear ?: vs.gear
        gearCur = gearNow
        inLot = lot.update(now, vs.speedKmh, gearNow, vs.steerDeg, lotClues(now))
        onRoad = gearNow != 'R' && gearNow != 'P' && !inLot && lastFastAt > 0L && now - lastFastAt < 90_000L
        // 推論緊貼車身的分隔線只在停車時（R／P 檔，或在停車場、或慢速且不在馬路上）
        slotDet.allowInfer = gearNow == 'R' || gearNow == 'P' || (!onRoad && (vs.speedKmh ?: 99f) < 3f)
        if (prefs.camBev && !groundBusy) Crumbs.step(Crumbs.FRAME, "bev") { runBev(g, now) }

        emaProc = ema(emaProc, (SystemClock.elapsedRealtime() - t0).toFloat())
        if (lastFrameAt != 0L) emaFps = ema(emaFps, 1000f / max(1L, t0 - lastFrameAt))
        lastFrameAt = t0
        Crumbs.step(Crumbs.FRAME, "publish") { pushPayload(g, det, now, vs) }
        val lampEvt = if (prefs.trainCollect) lampEvent(now) else null
        if (prefs.trainCollect && !Crumbs.off("train") && (TrainCollector.due(now, vs.speedKmh) || TrainCollector.dueEvent(now, lampEvt))) Crumbs.step(Crumbs.FRAME, "train") {
            val rd = road?.takeIf { now - roadAt < BEV_TTL_MS }
            val meta = JSONObject().put("t", System.currentTimeMillis())
                .put("speed", vs.speedKmh ?: JSONObject.NULL).put("gear", gearCur?.toString() ?: JSONObject.NULL)
                .put("steer", vs.steerDeg ?: JSONObject.NULL).put("turn", vs.turn?.toString() ?: JSONObject.NULL)
                .put("lot", inLot).put("lines", TrainCollector.lines(rd?.lines)).put("evt", lampEvt ?: JSONObject.NULL)
                .put("edgeL", rd?.edgeL ?: JSONObject.NULL).put("edgeR", rd?.edgeR ?: JSONObject.NULL)
            TrainCollector.submit(ctx, frame.toNv21(), frame.width, frame.height, now, meta, calibText)
        }
    }

    /** 目前有沒有車燈事件（給訓練資料收集多存幾張）：前車煞車／方向燈／雙黃燈、旁邊車打方向燈。 */
    private fun lampEvent(now: Long): String? {
        leadLight?.takeIf { now - leadLightAt < 1500 }?.let { ll ->
            if (ll.brake) return "lead_brake"
            if (ll.hazard) return "lead_hazard"
            if (ll.turn != null) return "lead_turn"
        }
        if (!Crumbs.off("sideblink") && sideBlink.blinking(now).isNotEmpty()) return "side_blink"
        return null
    }

    private fun slots(): List<String> {
        val en = prefs.camDetect.mapNotNull { LETTER[it] }.toSet()
        val out = listOf(
            // 前方改用去畸變的虛擬鏡頭：寬（近處、隔壁車道）與窄（遠處放大）輪流
            "left:a", "right:a", "rear:c", "left:b", "right:b", "front:w",
            "left:a", "right:a", "rear:c", "left:b", "right:b", "front:n"
        ).filter { it.substringBefore(':') in en }.toMutableList()
        if (prefs.camTrafficLight) out.add(SLOT_TL)
        return out
    }

    private fun initYolo(): Yolo? {
        if (yoloError != null) return null
        return try {
            Yolo(ctx, prefs.model, useGpu = prefs.useGpu).also { yolo = it }
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
        var nOrient = 0
        for (d in dets) {
            if (d.type == ObjTypes.LIGHT) {
                if (name == "front") considerTrafficLight(g, camP, d, qx0, qy0, qw, qh, now)
                continue
            }
            val u = d.rect.centerX() - qx0
            val v = d.rect.bottom - qy0
            if (v > qh * 0.97f && d.rect.width() > side * 0.85f) continue
            val w = toWorld(g, camP, u.toDouble(), v.toDouble(), qw, qh, d.type) ?: continue
            var vyaw = Float.NaN; var vconf = 0f
            if (ObjTypes.group(d.type) == "veh" && nOrient < OrientNet.MAX_PER_CALL && d.rect.width() >= 24f && !Crumbs.off("orient")) {
                orientNet?.let { on ->
                    nOrient++
                    val (rel, conf) = on.classify(frame, d.rect.left, d.rect.top, d.rect.right, d.rect.bottom)
                    if (conf >= ORIENT_MIN_CONF) { vyaw = OrientHeading.worldYaw(camP.px.toFloat(), camP.pz.toFloat(), w[0], w[1], rel); vconf = conf }
                }
            }
            objs.add(WObj(d.type, w[0], w[1], d.score, w[2], vyaw, vconf))
            // 周圍車輛的方向燈：數車框裡亮琥珀色的像素（縮成最多 64x48 取樣）
            if (ObjTypes.group(d.type) == "veh" && !Crumbs.off("sideblink")) {
                val bw = d.rect.width().toInt().coerceIn(8, 64); val bh = d.rect.height().toInt().coerceIn(6, 48)
                if (blinkBuf.size < bw * bh) blinkBuf = IntArray(64 * 48)
                frame.cropArgb(d.rect.left.toInt(), d.rect.top.toInt(), d.rect.right.toInt(), d.rect.bottom.toInt(), bw, bh, blinkBuf)
                var amber = 0
                for (k in 0 until bw * bh) if (SideBlinkWatcher.isAmber(blinkBuf[k])) amber++
                sideBlink.observe(now, w[0], w[1], amber, bw * bh)
            }
            if (w[3] > 0f) tDets.add(Det(d.type, w[0], w[1], d.score, cam = name, atMs = atMs))
            boxes.add(QuadOverlayView.Box(RectF(d.rect), "${NAMES[d.type] ?: d.label} ${"%.1f".format(w[2])}m", w[2] < 1.2f))
        }
        putCamResult(slot, CamResult(now, objs, boxes))
        afterDetections(tDets, vs, now)
    }

    /** 每輪偵測之後（不管哪顆鏡頭、魚眼或去畸變）：追蹤、變道、起步、LDW、提示音。 */
    private fun afterDetections(tDets: List<Det>, vs: VehicleState, now: Long) {
        val ego = egoMps(vs)
        tracks = trackMgr.update(tDets, now, ego, egoYawRate = null, cam = null)
        tracksAt = now
        val rd = road
        val lane = if (prefs.groundMode == AppPrefs.GROUND_VIRTUAL && rd != null && now - roadAt < BEV_TTL_MS)
            laneAt0(rd) else null
        lcaState = lca.update(
            tracks, now,
            lane?.get(0)?.takeIf { !it.isNaN() }, lane?.get(1)?.takeIf { !it.isNaN() },
            turnForLaneAids(vs), vs.steerDeg, ego,
            lane?.get(2)?.takeIf { !it.isNaN() }, reverse = vs.gear == 'R'
        )
        val t = tl
        val tlFresh = t != null && now - t.at < TL_TTL_MS
        // 倒車時自車往後退，前車看起來像往前開走 → 誤報「前車已起步」（實車）。R 檔時不跑，車速當成在動讓它歸零
        startState = startAssist.update(
            now, if (vs.gear == 'R') 99f else vs.speedKmh,
            currentObjects(now).map { StartAssist.Obj(it.t, it.x, it.z) },
            if (tlFresh) t!!.s else null, if (tlFresh) t!!.c else 0f
        )
        ldw.minKmh = prefs.ldwMinKmh.toFloat()
        ldwState = ldw.update(
            now, vs.speedKmh,
            if (rd != null && now - roadAt < BEV_TTL_MS && !inLot) rd.lines else emptyList(), vs.turn
        )
        beepIfNeeded(now)
    }

    @Volatile private var turnHit = false
    @Volatile private var noLaneChange = false
    @Volatile private var laneChangeBlocked = false
    private val sounder = AlertSounder { k -> playTone(k) }

    /** 每輪偵測後判斷要不要響（各種警示剛出現才響、各自冷卻，見 [AlertSounder]）。 */
    private fun beepIfNeeded(now: Long) {
        val vs = VehicleBus.signals.snapshot()
        val st = startState; val ld = ldwState; val l = lcaState
        val lcaFresh = l != null && now - tracksAt < OBJ_TTL_MS
        val state = AlertSounder.State(
            start = st != null && (st.lead || st.green),
            ldw = (ld != null && (ld.any || ld.straddle != null)) || noLaneChange,
            lcaDanger = (lcaFresh && (l!!.L >= 2 || l.R >= 2)) || laneChangeBlocked ||
                (cutIn != null && now - cutInAt < 1500) || aidsDanger,
            turnHit = turnHit,
            collision = AlertSounder.moving(vs.speedKmh, vs.gear) && currentObjects(now).any {
                // 只算行進路線上的（前進看前方、倒車看後方），旁邊並排的不叫
                it.clr < 0.5f && abs(it.x) < AnnotationMapper.EGO_HW + 0.4f && (if (vs.gear == 'R') it.z < 0f else it.z > 0f)
            }
        )
        // 氛圍燈連動：危險紅閃（那一側有車只亮那側）、偏離橘、起步綠
        if (!Crumbs.off("ambient")) try {
            // 氛圍燈分區是前座／後座：後方來的危險（盲點來車、倒車）亮後座，前方的（碰撞、切入、轉彎掃到）亮前座
            val side = when {
                lcaFresh && (l!!.L >= 2 || l.R >= 2) -> 'B'
                vs.gear == 'R' -> 'B'
                state.collision || state.turnHit || (cutIn != null && now - cutInAt < 1500) -> 'F'
                else -> null
            }
            AmbientLight.onAlerts(state.lcaDanger || state.turnHit || state.collision, side, state.ldw, state.start)
        } catch (t: Throwable) { Log.w(TAG, "ambient", t) }
        // 後擋看板：急煞／雙閃、倒車、跟車太近、路邊停車各切到看板裡存好的那一則
        if (!Crumbs.off("reardisp")) try {
            val v = vs.speedKmh ?: 0f
            // 紅燈停等：在馬路上（或看到紅燈）停下來 3 秒以上；起步：停等中轉綠燈／前車起步，或剛起步的 3 秒
            val tlNow = tl?.takeIf { now - it.at < TL_TTL_MS }?.s
            val stopped = v < 2f && vs.gear != 'R' && vs.gear != 'P' && (onRoad || tlNow == "red")
            if (stopped) { if (rdStopAt == 0L) rdStopAt = now } else {
                if (rdStopAt != 0L && now - rdStopAt >= 3000L && v >= 3f) rdStartUntil = now + 3000L
                rdStopAt = 0L
            }
            val waiting = rdStopAt != 0L && now - rdStopAt >= 3000L
            val st = startState
            val go = (waiting && (tlNow == "green" || (st != null && (st.lead || st.green)))) || now < rdStartUntil
            RearDisplay.onState(
                hazardOrBrake = vs.turn == 'H' || "hazbrake" in aidKeys || (v > 30f && decel > 4f),
                reverse = vs.gear == 'R',
                tooClose = "headway" in aidKeys,
                parking = vs.turn == 'H' && v <= 25f,
                turn = vs.turn?.takeIf { it == 'L' || it == 'R' },
                start = go,
                wait = waiting && !go
            )
        } catch (t: Throwable) { Log.w(TAG, "rear display", t) }
        if (!prefs.alertSound) return
        sounder.update(now, state)
    }

    /** 用媒體音量（車機的通知音量常常是關的）。 */
    fun playTone(k: AlertSounder.Kind) {
        // 不用 ToneGenerator＋媒體聲道：車機沒播音樂時擴大機是關的，聽不到（實車）
        try { AlertTone.play(ctx, k, prefs.alertChannel) } catch (_: Throwable) {}
    }

    private fun egoMps(vs: VehicleState): Float? =
        vs.speedKmh?.div(3.6f)?.let { if (vs.gear == 'R') -it else it }

    /** 物件中心落在哪一格（或離格子中心 1.5 m 內）→ 那一格長邊的方向（弧度，0 = 跟自車同向）。 */
    private fun slotYaw(sl: List<ParkingSlot>, x: Float, z: Float): Float? {
        var best: ParkingSlot? = null; var bd = 1.5f
        for (s in sl) {
            if (s.p.size < 8) continue
            val cx = (s.p[0] + s.p[2] + s.p[4] + s.p[6]) / 4f; val cz = (s.p[1] + s.p[3] + s.p[5] + s.p[7]) / 4f
            val d = hypot(cx - x, cz - z)
            if (d < bd) { bd = d; best = s }
        }
        val p = best?.p ?: return null
        val e1x = p[2] - p[0]; val e1z = p[3] - p[1]; val e2x = p[4] - p[2]; val e2z = p[5] - p[3]
        val (dx, dz) = if (hypot(e1x, e1z) >= hypot(e2x, e2z)) e1x to e1z else e2x to e2z
        return atan2(dx, dz)
    }

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

    // ───────────── 前方：去畸變的虛擬鏡頭（魚眼原圖裡遠方的車和紅綠燈太小又變形）─────────────

    private val virtualViews = HashMap<String, VirtualView>()
    private val virtualArgb = HashMap<String, IntArray>()

    /** 前方三個虛擬鏡頭；尺寸跟模型輸入一樣，不用再縮放。 */
    private fun virtualView(g: AvmGeometry, key: String, size: Int): VirtualView? {
        virtualViews[key]?.let { if (it.size == size) return it }
        val v = try {
            when (key) {
                "front:w" -> VirtualView(g, "front", 0.0, 2.0, FRONT_WIDE_FOV, size)
                "front:n" -> VirtualView(g, "front", 0.0, 1.0, FRONT_NARROW_FOV, size)
                SLOT_TL -> VirtualView(g, "front", 0.0, TL_PITCH, TL_FOV, size)
                "tl:hi" -> VirtualView(g, "front", 0.0, TL_PITCH, TL_HI_FOV, size)
                else -> null
            }
        } catch (_: Throwable) { null } ?: return null
        virtualViews[key] = v
        return v
    }

    /** 取樣出虛擬鏡頭的 ARGB 影像（size×size）。 */
    private fun renderVirtual(v: VirtualView, key: String): IntArray {
        val n = v.size * v.size
        val buf = virtualArgb[key]?.takeIf { it.size == n } ?: IntArray(n).also { virtualArgb[key] = it }
        frame.sampleLut(v.lut(frame.width, frame.height), buf)
        return buf
    }

    /** 把 ARGB 影像（src×src）依 letterbox 填進模型輸入。 */
    private fun fillFromArgb(argb: IntArray, src: Int, buf: java.nio.ByteBuffer, nchw: Boolean, size: Int, lb: Letterbox) {
        val gray = 114f / 255f
        val plane = size * size
        for (j in 0 until size) {
            val sy = ((j + 0.5f - lb.padY) / lb.scale).toInt()
            for (i in 0 until size) {
                val sx = ((i + 0.5f - lb.padX) / lb.scale).toInt()
                var r = gray; var gg = gray; var b = gray
                if (sx in 0 until src && sy in 0 until src && j + 0.5f >= lb.padY && i + 0.5f >= lb.padX) {
                    val p = argb[sy * src + sx]
                    r = ((p shr 16) and 0xFF) / 255f; gg = ((p shr 8) and 0xFF) / 255f; b = (p and 0xFF) / 255f
                }
                val k = j * size + i
                if (nchw) {
                    buf.putFloat(k * 4, r); buf.putFloat((plane + k) * 4, gg); buf.putFloat((2 * plane + k) * 4, b)
                } else {
                    buf.putFloat(k * 12, r); buf.putFloat(k * 12 + 4, gg); buf.putFloat(k * 12 + 8, b)
                }
            }
        }
    }

    private fun runFrontVirtual(det: Yolo, g: AvmGeometry, slot: String, vs: VehicleState, atMs: Long) {
        val v = virtualView(g, slot, det.inputSize) ?: return
        val argb = renderVirtual(v, slot)
        val (dets, inf) = det.detectWith(v.size, v.size, 0f, 0f) { buf, nchw, size, lb ->
            fillFromArgb(argb, v.size, buf, nchw, size, lb)
        }
        emaInfer = ema(emaInfer, inf.toFloat())
        val now = SystemClock.elapsedRealtime()
        val objs = ArrayList<WObj>()
        val tDets = ArrayList<Det>()
        for (d in dets) {
            if (d.type == ObjTypes.LIGHT) { considerTrafficLightVirtual(v, argb, d, now); continue }
            val w = toWorldVirtual(v, d.rect.centerX().toDouble(), d.rect.bottom.toDouble(), d.type,
                widthRange(v, d.rect.left.toDouble(), d.rect.right.toDouble(), d.rect.bottom.toDouble(), d.type)) ?: continue
            objs.add(WObj(d.type, w[0], w[1], d.score, w[2]))
            if (w[3] > 0f) tDets.add(Det(d.type, w[0], w[1], d.score, cam = "front", atMs = atMs))
        }
        putCamResult(slot, CamResult(now, objs, emptyList()))
        // 前車燈號：用窄角（看得比較大）那一格；正前方（框中心在畫面中間 30%）最近的那台車
        // 車子轉向角速度（遠處景物的橫向平移）→ 道路彎度
        if (slot == "front:w" && !Crumbs.off("vyaw")) try {
            Crumbs.at(Crumbs.FRAME, "vyaw")
            visualYaw.update(now, argb, v.size, (v.size / 2.0) / kotlin.math.tan(Math.toRadians(FRONT_WIDE_FOV / 2.0)))
            if (visualYaw.rateAt == now) MotionCurvature.onVisual(visualYaw.rate)
        } catch (t: Throwable) { Log.w(TAG, "visual yaw", t) }
        // 旁邊車道的車打方向燈要切進來：寬角那一格
        if (slot == "front:w" && !Crumbs.off("cutin")) try {
            Crumbs.at(Crumbs.FRAME, "cutin")
            val boxes = dets.mapNotNull { d ->
                if (ObjTypes.group(d.type) != "veh") return@mapNotNull null
                val w = toWorldVirtual(v, d.rect.centerX().toDouble(), d.rect.bottom.toDouble(), d.type) ?: return@mapNotNull null
                CutInWatcher.Box(d.rect.left, d.rect.top, d.rect.right, d.rect.bottom, w[0], w[1])
            }
            cutIn = cutInWatch.update(now, argb, v.size, boxes); cutInAt = now
            // 停車場判斷用：正前方（同車道）15 m 內有車 = 在排隊（塞車），不是在停車場找車位
            if (slot == "front:n" && boxes.any { abs(it.worldX) < 1.6f && it.worldZ in 0.5f..15f }) leadCloseAt = now
        } catch (t: Throwable) { Log.w(TAG, "cut-in", t) }
        if (slot == "front:n" && !Crumbs.off("lead")) {
            Crumbs.at(Crumbs.FRAME, "lead")
            val lead = dets.filter { ObjTypes.group(it.type) == "veh" && it.rect.centerX() in v.size * 0.35f..v.size * 0.65f }
                .maxByOrNull { it.rect.bottom }
            leadLight = if (lead != null) leadLights.update(now, argb, v.size, lead.rect.left, lead.rect.top, lead.rect.right, lead.rect.bottom)
                else leadLights.update(now, null, v.size, 0f, 0f, 0f, 0f)
            leadLightAt = now
        }
        Crumbs.at(Crumbs.FRAME, "det+")
        afterDetections(tDets, vs, now)
    }

    private val leadLights = LeadLightDetector()
    private val cutInWatch = CutInWatcher()
    private val visualYaw = VisualYaw()
    @Volatile private var cutIn: Triple<Char, Float, Float>? = null
    @Volatile private var cutInAt = 0L
    @Volatile private var leadLight: LeadLightDetector.State? = null
    @Volatile private var leadLightAt = 0L

    /** 虛擬鏡頭裡偵測框底部中點 → 世界座標（跟 [toWorld] 同一套處理：太遠用方位角、推到物件中心、離自車距離）。 */
    private fun toWorldVirtual(v: VirtualView, u: Double, vv: Double, t: String, widthDist: Double? = null): FloatArray? {
        val c = v.cam
        val gp = v.groundAt(u, vv)
        val gd = gp?.let { hypot(it[0] - c.px, it[1] - c.pz) }
        val nx: Double; val nz: Double; val real: Float
        if (widthDist != null && (gd == null || gd >= 40.0 || widthDist > gd)) {
            // 用車寬估的比較遠 → 用車寬的（前鏡頭裝得低、俯角校正差一點，遠處接地點就會被算得太近）
            val r = v.rayAt(u, vv)
            val az = atan2(r[0], r[2])
            nx = c.px + Math.sin(az) * widthDist; nz = c.pz + Math.cos(az) * widthDist; real = 1f
        } else if (gp != null && gd != null && gd < 40.0) {
            nx = gp[0]; nz = gp[1]; real = 1f
        } else {
            val r = v.rayAt(u, vv)
            val az = atan2(r[0], r[2])
            nx = c.px + Math.sin(az) * 15.0; nz = c.pz + Math.cos(az) * 15.0; real = 0f
        }
        return finishWorld(c, nx, nz, real, t)
    }

    /**
     * 用車寬估距離：車尾大約多寬是固定的（轎車 1.8 m），偵測框左右緣兩條射線夾的水平角 → 距離。
     * 只看方位角，不受鏡頭俯角、高度校正誤差影響（接地點法在遠處差一兩度就差好幾公尺）。
     * 框碰到畫面邊緣（車只拍到一部分）或不是汽車類 → null。斜著看到車側時框比較寬、估得比較近，
     * 呼叫端只在「車寬估的比接地點遠」時採用，所以不會因為這樣變得更近。
     */
    private fun widthRange(v: VirtualView, left: Double, right: Double, bottom: Double, t: String): Double? {
        val wReal = when (t) { "car" -> 1.8; "van" -> 1.9; "bus", "truck" -> 2.5; else -> return null }
        val m = v.size * 0.02
        if (left < m || right > v.size - m || right - left < 4) return null
        val a = v.rayAt(left, bottom); val b = v.rayAt(right, bottom)
        var ang = abs(atan2(a[0], a[2]) - atan2(b[0], b[2]))
        if (ang > Math.PI) ang = 2 * Math.PI - ang
        if (ang < 1e-3) return null
        return (wReal / 2.0 / kotlin.math.tan(ang / 2.0)).coerceAtMost(60.0)
    }

    private fun runTrafficLightVirtual(det: Yolo, g: AvmGeometry) {
        val v = virtualView(g, SLOT_TL, det.inputSize) ?: return
        val argb = renderVirtual(v, SLOT_TL)
        val (dets, inf) = det.detectWith(v.size, v.size, 0f, 0f) { buf, nchw, size, lb ->
            fillFromArgb(argb, v.size, buf, nchw, size, lb)
        }
        emaInfer = ema(emaInfer, inf.toFloat())
        val now = SystemClock.elapsedRealtime()
        // 同一個畫面裡的車（方位、仰角範圍，往上多留半個車高）：紅點落在車上 = 煞車燈，不是紅燈
        //（實車：高速上前車煞車被當成紅燈；大車、上坡時煞車燈會高過「地平線上方 4°」那條線）
        vehAngles = dets.filter { ObjTypes.group(it.type) == "veh" }.map { d ->
            val a = angles(v, d.rect.left, d.rect.top); val b = angles(v, d.rect.right, d.rect.bottom)
            val elLo = minOf(a[1], b[1]); val elHi = maxOf(a[1], b[1])
            floatArrayOf(minOf(a[0], b[0]) - 1f, maxOf(a[0], b[0]) + 1f, elLo, elHi + (elHi - elLo) * 0.5f)
        }
        for (d in dets) if (d.type == ObjTypes.LIGHT) {
            val c = angles(v, d.rect.centerX(), d.rect.centerY())
            if (onVehicle(c)) continue
            considerTrafficLightVirtual(v, argb, d, now)
        }
        // 模型這次沒抓到 → 高解析畫面找燈點（遠處燈頭只有幾個像素，模型看不到）
        val cur = tl
        if ((cur == null || now - cur.at > 400) && now - tlBlobAt >= 500) {
            tlBlobAt = now
            val hv = virtualView(g, "tl:hi", TL_HI_SIZE) ?: return
            val hargb = renderVirtual(hv, "tl:hi")
            val l = tlBlobs.update(hargb, hv.size) ?: return
            if (onVehicle(angles(hv, l.x, l.y))) return
            // 高速（≥ 80 km/h）只有找燈點看到的紅燈不採用：國道沒有紅綠燈，多半是前車煞車燈
            if (l.state == "red" && (VehicleBus.signals.snapshot().speedKmh ?: 0f) >= 80f) return
            val ray = hv.rayAt(l.x.toDouble(), l.y.toDouble())
            tl = TlResult(now, l.state, l.conf * 0.8f, Math.toDegrees(atan2(ray[0], ray[2])).toFloat(),
                RectF(l.x - 4f, l.y - 4f, l.x + 4f, l.y + 4f))
        }
    }

    private val tlBlobs = TrafficLightBlobs()
    @Volatile private var vehAngles: List<FloatArray> = emptyList()
    /** 畫面上一點 → (方位角, 仰角)（度）。 */
    private fun angles(v: VirtualView, x: Float, y: Float): FloatArray {
        val r = v.rayAt(x.toDouble(), y.toDouble())
        return floatArrayOf(Math.toDegrees(atan2(r[0], r[2])).toFloat(), Math.toDegrees(atan2(r[1], hypot(r[0], r[2]))).toFloat())
    }
    private fun onVehicle(a: FloatArray): Boolean = vehAngles.any { a[0] in it[0]..it[1] && a[1] in it[2]..it[3] }
    private var tlBlobAt = 0L

    private fun considerTrafficLightVirtual(v: VirtualView, argb: IntArray, d: Detection, now: Long) {
        val r = d.rect
        if (r.width() < 3f || r.height() < 3f) return
        val ow = r.width().toInt().coerceIn(6, 48)
        val oh = r.height().toInt().coerceIn(6, 48)
        val crop = IntArray(ow * oh)
        val x0 = r.left.toInt().coerceIn(0, v.size - 1); val y0 = r.top.toInt().coerceIn(0, v.size - 1)
        val x1 = r.right.toInt().coerceIn(x0 + 1, v.size); val y1 = r.bottom.toInt().coerceIn(y0 + 1, v.size)
        for (j in 0 until oh) for (i in 0 until ow) {
            val sx = x0 + (x1 - x0) * i / ow; val sy = y0 + (y1 - y0) * j / oh
            crop[j * ow + i] = argb[sy * v.size + sx]
        }
        val (state, conf) = TrafficLightState.classify(crop, ow, oh)
        val score = d.score * if (state == "off") 0.3f else conf
        val cur = tl
        if (cur != null && now - cur.at < 300 && cur.c >= score) return
        val ray = v.rayAt(r.centerX().toDouble(), r.centerY().toDouble())
        tl = TlResult(now, state, score, Math.toDegrees(atan2(ray[0], ray[2])).toFloat(), RectF(r))
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
        return finishWorld(camP, nx, nz, real, t)
    }

    /** 地面點（或方位角推估點）→ 推到物件中心、算離自車外框多遠。[real] = 1 是真的地面交點。 */
    private fun finishWorld(camP: CamParams, nx: Double, nz: Double, real: Float, t: String): FloatArray? {
        if (abs(nx) < EGO_HW + 0.05 && abs(nz) < EGO_HL + 0.05) return null
        val dx = nx - camP.px; val dz = nz - camP.pz
        val d = max(hypot(dx, dz), 1e-3)
        val rad = ObjTypes.radius(t)
        val clr = hypot(max(abs(nx) - EGO_HW, 0.0), max(abs(nz) - EGO_HL, 0.0)).toFloat()
        return floatArrayOf((nx + dx / d * rad).toFloat(), (nz + dz / d * rad).toFloat(), clr, real)
    }

    private fun currentObjects(now: Long): List<WObj> {
        val all = ArrayList<WObj>()
        for ((slot, r) in camResults) if (now - r.at < ttlFor(slot)) all.addAll(r.objs)
        all.sortByDescending { it.c }
        val kept = ArrayList<WObj>()
        for (o in all) if (kept.none { sameObject(it, o) }) kept.add(o)
        return kept
    }

    /**
     * 兩顆鏡頭（或兩種偵測）看到的是不是同一個東西：同一群（四輪車不分轎車／廂型／大車，兩輪車一群），
     * 而且位置夠近。距離是單眼估的，**沿著視線方向**誤差大、越遠越大；橫向（方位角）準得多 →
     * 分開看：橫向差要小，縱深差可以大（隨距離放寬）。實車：同一台轎車被畫成兩個重疊的框。
     */
    private fun sameObject(a: WObj, b: WObj): Boolean {
        val g = ObjTypes.group(a.t)
        if (g != ObjTypes.group(b.t)) return false
        // 框大面積重疊（中心距離小於兩者半寬／半長和的 80%）→ 同一個，只留信心度高的（使用者要求）
        val ha = ObjTypes.radius(a.t); val hb = ObjTypes.radius(b.t)
        val la = if (g == "veh") ha * 2.5f else ha; val lb = if (g == "veh") hb * 2.5f else hb   // 半長：車約是半寬的 2.5 倍
        if (abs(a.x - b.x) < (ha + hb) * 0.8f && abs(a.z - b.z) < (la + lb) * 0.8f) return true
        val (lat, rad) = when (g) { "veh" -> 1.3f to 2.6f; "two" -> 0.8f to 1.5f; else -> 0.6f to 1.0f }
        val mx = (a.x + b.x) / 2f; val mz = (a.z + b.z) / 2f
        val r = hypot(mx, mz).coerceAtLeast(1e-3f)
        val ux = mx / r; val uz = mz / r                  // 視線方向（從自車往外）
        val dx = a.x - b.x; val dz = a.z - b.z
        val dRad = abs(dx * ux + dz * uz)
        val dLat = abs(-dx * uz + dz * ux)
        return dLat < lat && dRad < rad + 0.15f * r
    }

    /**
     * 四個輪子在俯視圖上的黑影範圍（自車框外側 0.45 m、輪軸前後 0.6 m）：拼接時立起來的輪胎被拉長成一大塊，
     * 輪子擺正時左前輪剛好壓在隔壁格裡，停車格偵測就把那格當成不乾淨或有東西
     * （實車：左邊的格子要方向盤右轉到底、輪子往內轉才偵測得到）。當成「看不到」，不算證據。
     */
    private fun wheelZones(): List<FloatArray> {
        val vg = VehicleGeometry()
        val front = (vg.rearAxleZ + vg.wheelbase).toFloat(); val rear = vg.rearAxleZ.toFloat()
        val x0 = EGO_HW + 0.15f; val x1 = EGO_HW + 0.6f
        return listOf(front, rear).flatMap { z ->
            listOf(floatArrayOf(-x1, z - 0.6f, -x0, z + 0.6f), floatArrayOf(x0, z - 0.6f, x1, z + 0.6f))
        }
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
        // 車道線模型：取下一顆鏡頭的畫面（幾毫秒），丟到自己的執行緒跑
        laneSegOrNull()?.let { ls ->
            if (!segBusy && ThermalGovernor.laneModelAllowed(segCounter++)) {
                Crumbs.at(Crumbs.FRAME, "laneseg")
                val q = ls.grab(frame)
                segBusy = true
                segThread.post {
                    try { Crumbs.step(Crumbs.OTHER, "laneseg") { ls.infer(q) } }
                    catch (t: Throwable) { Log.w(TAG, "車道線模型失敗", t) } finally { segBusy = false }
                }
            }
        }
        // 停車格專用的大範圍俯視圖（左右 9.5 m、前後 9 m，每格 5 cm；像素數跟一般俯視圖差不多）
        if (!Crumbs.off("slotbev") && ThermalGovernor.farSlotsAllowed) try {
            Crumbs.at(Crumbs.FRAME, "slotbev")
            val sg = slotGeo ?: AvmGeometry(g.cams, BevSpec(-9.5, 9.5, -9.0, 9.0, 0.05), g.carLength, g.carWidth).also {
                slotGeo = it; slotTrack.viewX = 9.5f; slotTrack.viewZ = 9f
            }
            if (slotLut == null || slotLutKey != key) { slotLut = sg.buildBevLut(frame.width, frame.height); slotLutKey = key }
            val n = sg.bev.width * sg.bev.height
            val sb = slotBuf?.takeIf { it.size == n } ?: IntArray(n).also { slotBuf = it }
            frame.sampleLut(slotLut!!, sb)
        } catch (t: Throwable) { slotGeo = null; slotBuf = null }
        Crumbs.at(Crumbs.FRAME, "bev+")
        groundBusy = true
        val buf = bevArgb
        val mode = prefs.groundMode
        ground.post {
            try {
                Crumbs.at(Crumbs.GROUND, "cones")
                cones = coneDet.detect(buf, bw, bh, mpp, cx, cy, excl); conesAt = now
                // 停車格：用比較大的俯視圖（看得遠一點），記住跑出畫面的格子並依自車移動推算位置
                val (mdz, myaw) = takeEgoMotion()
                slotTrack.egoMotion(mdz, myaw); slotSelection.egoMotion(mdz, myaw); roadTrack.egoMotion(mdz, myaw)
                Crumbs.at(Crumbs.GROUND, "slots")
                val sb = slotBuf.takeIf { !Crumbs.off("slotbev") }
                val sg = slotGeo
                // 近的用一般俯視圖（解析度高、在鏡頭清楚的範圍，準）；大範圍俯視圖只補一般俯視圖範圍外的格子
                //（實車：全部改用大範圍的之後準確率變差——每格 5 cm、又用到魚眼邊緣模糊的地方）
                val near = slotDet.detect(buf, bw, bh, mpp, cx, cy, listOf(ego) + wheelZones(), vehicles)
                val far = if (sb != null && sg != null) {
                    val nb = g.bev
                    slotDet.detect(sb, sg.bev.width, sg.bev.height, sg.bev.mpp.toFloat(), sg.bev.originX.toFloat(), sg.bev.originY.toFloat(), listOf(ego) + wheelZones(), vehicles)
                        .filter { f ->
                            val fx = (f.p[0] + f.p[2] + f.p[4] + f.p[6]) / 4f; val fz = (f.p[1] + f.p[3] + f.p[5] + f.p[7]) / 4f
                            val outside = fx < nb.xMin + 1.0 || fx > nb.xMax - 1.0 || fz < nb.zMin + 1.0 || fz > nb.zMax - 1.0
                            outside && near.none { n ->
                                hypot((n.p[0] + n.p[2] + n.p[4] + n.p[6]) / 4f - fx, (n.p[1] + n.p[3] + n.p[5] + n.p[7]) / 4f - fz) < 1.5f
                            }
                        }
                } else emptyList()
                slots = slotTrack.update(near + far, now)
                slotsAt = now
                Crumbs.at(Crumbs.GROUND, "road")
                if (!Crumbs.off("crosswalk")) Crumbs.step(Crumbs.GROUND, "crosswalk") {
                    cws = cwDet.detect(buf, bw, bh, mpp, cx, cy, g.ipmValidMask(), excl); cwAt = now
                }
                if (!Crumbs.off("boxjunc")) Crumbs.step(Crumbs.GROUND, "boxjunc") {
                    bjs = bjDet.detect(buf, bw, bh, mpp, cx, cy, g.ipmValidMask(), excl); bjAt = now
                }
                if (mode == AppPrefs.GROUND_VIRTUAL) {
                    // 有車道線模型、四顆鏡頭都有新結果 → 用模型決定哪裡是漆；否則照舊用亮度規則
                    val paint = laneSeg?.takeIf { prefs.laneModel && ThermalGovernor.level < 2 && it.ready(now) }?.let { ls ->
                        val a = segBev?.takeIf { it.size == bw * bh } ?: FloatArray(bw * bh).also { segBev = it }
                        ls.bevProb(table, frame.width, a, now); a
                    }
                    road = roadTrack.update(steepFilter(roadEx.extract(buf, bw, bh, mpp, cx, cy, excl, g.roadValidMask(), paint), VehicleBus.signals.snapshot().speedKmh), now); roadAt = now
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
                Crumbs.at(Crumbs.GROUND, "ground:ok")
            } catch (t: Throwable) { Log.e(TAG, "俯視辨識失敗：${t.message}") } finally { groundBusy = false }
        }
    }

    private fun pushPayload(g: AvmGeometry, det: Yolo, now: Long, vs: VehicleState) {
        val objs = JSONArray()
        val ego = egoMps(vs)
        val coneOut = ArrayList<WObj>(); val used = HashSet<Int>()
        val aidObjs = ArrayList<DriverAids.Obj>()
        for (o in currentObjects(now)) {
            if (o.t == "cone") coneOut.add(o)
            var best: Track? = null; var bd = 1.5f
            if (now - tracksAt < OBJ_TTL_MS) for (tr in tracks) {
                if (!tr.confirmed || tr.t != o.t || tr.id in used) continue
                val d = hypot(tr.x - o.x, tr.z - o.z); if (d < bd) { bd = d; best = tr }
            }
            // d：最靠近自車那一點到自車外框的淨距（偵測時算的，見 finishWorld）。x／z 是物件中心，
            // 拿中心算距離會多算對方半個車身（實車：快撞到了還顯示 3 m，位置標註是對的）
            val j = JSONObject().put("t", o.t).put("x", r2(o.x)).put("z", r2(o.z)).put("c", r2(o.c)).put("d", r2(o.clr))
            best?.let { tr ->
                used.add(tr.id)
                aidObjs.add(DriverAids.Obj(o.t, o.x, o.z, tr.vx, tr.vz, o.clr))
                j.put("id", tr.id).put("vx", r2(tr.vx)).put("vz", r2(tr.vz))
                val isVeh = o.t == "car" || o.t == "van" || o.t == "truck" || o.t == "bus"
                val gs = if (ego != null) hypot(tr.vx, tr.vz + ego) else Float.MAX_VALUE
                // 車頭朝向模型：每台車平滑起來（停著的車方向不變，越看越穩）
                val vy = if (isVeh && !o.vyaw.isNaN()) orientSmooth.update(tr.id, o.vyaw, o.vconf, now) else if (isVeh) orientSmooth.get(tr.id, now) else null
                if (isVeh && gs <= StaticStabilizer.MOVING_MPS) {
                    // 停著的車：釘在地上、方向鎖住（照格子方向；不在格子裡就用車頭朝向模型；都沒有就沿用上次）
                    val hint = (if (now - slotsAt < BEV_TTL_MS) slotYaw(slots, o.x, o.z) else null) ?: vy
                    val (sx, sz, sy) = stabilizer.apply(tr.id, o.x, o.z, gs, hint, now)
                    j.put("x", r2(sx)).put("z", r2(sz)).put("still", 1)
                    sy?.let { y -> j.put("yaw", r2(y)) }
                } else {
                    if (isVeh) stabilizer.apply(tr.id, o.x, o.z, gs, null, now)   // 在動 → 放掉
                    (yaw(tr, ego) ?: vy)?.let { y -> j.put("yaw", r2(y)) }
                }
            }
            // 停著的車看不出方向 → 停在偵測到的格子裡就照格子的方向（停車場裡橫的、斜的都畫對）
            if (!j.has("yaw") && (o.t == "car" || o.t == "van" || o.t == "truck" || o.t == "bus") && now - slotsAt < BEV_TTL_MS)
                slotYaw(slots, o.x, o.z)?.let { y -> j.put("yaw", r2(y)) }
            objs.put(j)
        }
        stabilizer.prune(now)
        orientSmooth.prune(now)
        // 周圍車輛打方向燈：標在物件上（道路畫面閃琥珀色）
        val blinks = if (Crumbs.off("sideblink")) emptyList() else sideBlink.blinking(now)
        if (blinks.isNotEmpty()) for (i in 0 until objs.length()) {
            val j = objs.getJSONObject(i)
            val x = j.optDouble("x").toFloat(); val z = j.optDouble("z").toFloat()
            if (blinks.any { hypot(it[0] - x, it[1] - z) < SideBlinkWatcher.MATCH_M }) j.put("blink", 1)
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
        // 停車場裡不畫道路線（停車格的白線、輪擋會被當成車道線，實車：一堆奇怪的線）
        if (mode == AppPrefs.GROUND_VIRTUAL && rd != null && now - roadAt < BEV_TTL_MS && !inLot) {
            val lines = JSONArray()
            for (l in rd.lines) lines.put(JSONObject().put("k", l.kind.toString()).put("d", if (l.dashed) 1 else 0)
                .put("x1", r2(l.x1)).put("z1", r2(l.z1)).put("x2", r2(l.x2)).put("z2", r2(l.z2))
                .put("c", r2(l.conf)).put("kv", r4(l.k)))
            p.put("road", JSONObject().put("lines", lines)
                .put("el", rd.edgeL?.let { r2(it) } ?: JSONObject.NULL)
                .put("er", rd.edgeR?.let { r2(it) } ?: JSONObject.NULL))
        }
        // 斑馬線（停車場裡不畫：停車場的斜線區、行人道標線不是斑馬線）
        val cwNow = if (now - cwAt < BEV_TTL_MS && !inLot) cws else emptyList()
        if (cwNow.isNotEmpty()) {
            val a = JSONArray()
            for (c in cwNow) a.put(JSONObject().put("p", JSONArray().apply { for (v in c.p) put(r2(v)) }).put("n", c.stripes)
                .put("ux", r2(c.ux)).put("uz", r2(c.uz)))
            p.put("cw", a)
        }
        // 黃色網狀線（禁止臨時停車區）
        val bjNow = if (now - bjAt < BEV_TTL_MS && !inLot) bjs else emptyList()
        if (bjNow.isNotEmpty()) {
            val a = JSONArray()
            for (b in bjNow) a.put(JSONObject().put("p", JSONArray().apply { for (v in b.p) put(r2(v)) }))
            p.put("bj", a)
        }
        // 開車（> 20 km/h）不顯示停車格：旁邊車道的標線會被湊成格子（實車）
        if (now - slotsAt < BEV_TTL_MS && slots.isNotEmpty() && (vs.speedKmh ?: 0f) <= 20f && !onRoad) {
            // 倒車時打方向燈 → 自動選那一側最近的空格
            val tn = vs.turn
            if (vs.gear == 'R' && (tn == 'L' || tn == 'R')) slotSelection.autoPick(slots, tn, now)
            // 有車的格子常被車身擋住湊不出來 → 用認出來的格子當尺，停著的車在同一排、整數格距上就補一格（只顯示）
            val cars = currentObjects(now).filter { it.t == "car" || it.t == "van" || it.t == "truck" || it.t == "bus" }
                .map { SlotRowFiller.Car(it.x, it.z) }
            val shown = slots + (if (cars.isNotEmpty()) SlotRowFiller.fill(slots, cars) else emptyList())
            val sj = slotsJson(shown, false)
            for (i in shown.indices) {
                if (slotSelection.isSelected(shown[i])) sj.getJSONObject(i).put("sel", 1)
                // 自車停在裡面的那格（有車所以是「佔用」，但要畫出來：目前車格）
                if (SlotSelection.contains(shown[i], 0f, 0f)) sj.getJSONObject(i).put("own", 1)
            }
            p.put("slots", sj)
            // 停好車：打 P，或已經停在選的那格裡（格子中心離自車中心 0.8 m 內、停住）→ 清掉選擇，路徑跟著消失
            slotSelection.center?.let { c ->
                if (gearCur == 'P' || (hypot(c[0], c[1]) < 0.8f && (vs.speedKmh ?: 0f) < 1f)) slotSelection.clear()
            }
            if (spd(vs)) parkPathJson(slots)?.let { p.put("park", it) }
        }
        roadsideAndShoulder(p, vs, now)
        if (inLot) p.put("lot", 1)
        // 駕駛輔助警示：倒車兩側來車、開門預警、後方追撞、跟車秒數、休息提醒
        if (!Crumbs.off("aids")) try {
            Crumbs.at(Crumbs.FRAME, "aids")
            val ld = ldwState
            val msgs = ArrayList(aids.update(DriverAids.Input(now, vs.speedKmh, vs.gear, aidObjs, FactoryVehicle.doorOpen(),
                ld != null && (ld.any || ld.straddle != null))))
            // 停車輔助：方向盤指示（照停車路徑）、停正、窄路、停車位置記錄
            p.optJSONObject("park")?.let { pk ->
                val arr = pk.optJSONArray("pts")
                if (arr != null && gearCur != 'P') {   // P 檔＝已經停好，不再叫人「往前開、打 R 檔」
                    val pts = (0 until arr.length()).map { k -> arr.getJSONArray(k).let { q -> floatArrayOf(q.getDouble(0).toFloat(), q.getDouble(1).toFloat()) } }
                    parkAids.steering(pts, pk.optInt("rev"), vs.gear, vs.steerDeg, pk.optDouble("r", 5.0).toFloat())
                        ?.let { msgs.add(DriverAids.Msg(it.key, it.text, it.level)) }
                }
            }
            // 停正：只在停車過程中（不是 P 檔）顯示，藍色資訊、不念（實車：停在格子裡一直念「停正 左 右」）
            if (now - slotsAt < BEV_TTL_MS && vs.gear != 'P') parkAids.alignment(slots, vs.speedKmh)?.let { msgs.add(DriverAids.Msg(it.key, it.text, 0)) }
            val things = aidObjs.map { ParkingAids.Thing(it.x, it.z, ObjTypes.radius(it.t)) } +
                (if (now - obstaclesAt < OBS_TTL_MS) obstacles.map { ParkingAids.Thing(it.x, it.z, it.w / 2f) } else emptyList())
            parkAids.narrowGap(things, vs.speedKmh, vs.gear)?.let { msgs.add(DriverAids.Msg(it.key, it.text, it.level)) }
            if (parkAids.shouldSnapshot(now, vs.speedKmh, vs.gear) && !Crumbs.off("parkshot")) { Crumbs.at(Crumbs.FRAME, "parkshot"); saveParkedSnapshot() }
            // 周圍車輛打方向燈（開車時）：最近那台，旁邊車道近距離的用黃色
            if ((vs.speedKmh ?: 0f) >= 15f) sideBlink.blinking(now).minByOrNull { hypot(it[0], it[1]) }?.let { b ->
                val (x, z) = b[0] to b[1]
                if (kotlin.math.abs(x) < 8f && kotlin.math.abs(z) < 15f) {
                    val where = (if (x < 0) "左" else "右") + when { z > 2.5f -> "前方"; z < -2.5f -> "後方"; else -> "側" }
                    val near = kotlin.math.abs(x) < 4.5f && kotlin.math.abs(z) < 8f
                    msgs.add(DriverAids.Msg("sideblink", "${where}車輛打方向燈", if (near) 1 else 0))
                }
            }
            // 網狀線：慢下來（< 8 km/h、D 檔）時自車壓在上面或車頭前 2 m 內 → 提醒不可停車
            if ((vs.speedKmh ?: 99f) < 8f && gearCur != 'R' && gearCur != 'P') {
                val hit = (if (now - bjAt < BEV_TTL_MS && !inLot) bjs else emptyList()).any { b ->
                    val xs = (0 until 4).map { b.p[it * 2] }; val zs = (0 until 4).map { b.p[it * 2 + 1] }
                    xs.min() < 1.0f && xs.max() > -1.0f && zs.min() < 2.57f + 2f && zs.max() > -2.57f
                }
                if (hit) msgs.add(DriverAids.Msg("boxjunc", "網狀線區，不可停車", 1))
            }
            // 斑馬線：前方 6 m 內（含正壓在上面）→ 提示；上面有行人 → 警示
            if ((vs.speedKmh ?: 0f) >= 3f && gearCur != 'R' && gearCur != 'P') {
                val front = 2.57f
                cwNow.filter { it.ahead(front) in -2f..6f }.minByOrNull { it.ahead(front) }?.let { c ->
                    val xs = (0 until 4).map { c.p[it * 2] }; val zs = (0 until 4).map { c.p[it * 2 + 1] }
                    val ped = aidObjs.any { it.t == "ped" && it.x in xs.min() - 1.5f..xs.max() + 1.5f && it.z in zs.min() - 1.5f..zs.max() + 1.5f }
                    msgs.add(if (ped) DriverAids.Msg("cw", "斑馬線上有行人", 2)
                        else DriverAids.Msg("cw", if (c.ahead(front) > 0.5f) "前方斑馬線 %.0f m".format(c.ahead(front)) else "斑馬線，注意行人", 0))
                }
            }
            // 提醒開雙閃（車機沒辦法自己開）：高速急煞、高速時前車打雙閃
            val leadHaz = leadLight?.takeIf { now - leadLightAt < 1500 }?.hazard == true
            hazardPrompt.update(now, vs.speedKmh, decel, CanVehicle.brake(), vs.turn, leadHaz)?.let { msgs.add(DriverAids.Msg(it.key, it.text, it.level)) }
            // 停好車問要不要升窗、鎖車（遠端模組）
            if (!Crumbs.off("cyremote")) CyRemote.checkParked(vs.speedKmh)
            // 車機 CAN 車況提醒：安全帶、手煞車、續航、水溫、電壓、結冰、遠光
            if (CanVehicle.fresh()) for (m in CanVehicle.alerts(CanVehicle.info, vs.speedKmh)) msgs.add(DriverAids.Msg(m.key, m.text, m.level))
            aidsDanger = msgs.any { it.level >= 2 }
            aidKeys = msgs.map { it.key }.toSet()
            // 事件自動存影片：危險警示、太近、急減速 → 鎖定行車記錄器這段和上一段（一分鐘內最多一次）
            val collide = aidObjs.any { it.clr < 0.5f } && (vs.speedKmh ?: 0f) > 5f
            if (!Crumbs.off("evlock") && (aidsDanger || collide || decel > 4f) && now - lastEventLock > 60_000L && Dashcam.recorder?.running == true) {
                lastEventLock = now; Crumbs.at(Crumbs.FRAME, "evlock"); Dashcam.lock(); Engine360.log("事件：自動鎖定這段行車記錄影片")
            }
            if (msgs.isNotEmpty()) p.put("aids", JSONArray().apply { for (m in msgs) put(JSONObject().put("k", m.key).put("t", m.text).put("l", m.level)) })
        } catch (t: Throwable) { Log.w(TAG, "aids", t) }
        Crumbs.at(Crumbs.FRAME, "publish+")
        cutIn?.takeIf { now - cutInAt < 1500 }?.let { (side, x, z) ->
            p.put("cutin", JSONObject().put("side", side.toString()).put("x", r2(x)).put("z", r2(z)))
        }
        leadLight?.takeIf { now - leadLightAt < 1500 && (it.brake || it.hazard || it.turn != null) }?.let { ll ->
            p.put("lead", JSONObject().put("brake", ll.brake).put("hazard", ll.hazard).put("turn", ll.turn?.toString() ?: JSONObject.NULL))
        }
        val obs = obstacles
        if (now - obstaclesAt < OBS_TTL_MS && obs.isNotEmpty()) {
            val oa = JSONArray()
            for (o in obs) oa.put(JSONObject().put("x", r2(o.x)).put("z", r2(o.z))
                .put("w", r2(o.w)).put("l", r2(o.l)).put("c", r2(o.conf)))
            p.put("obs", oa)
        }
        // 轉彎：方向盤轉過門檻照實際角度；還沒轉、慢速打燈 → 照前方道路彎度（或路口典型半徑）預估
        // 方向盤讀值不合理（實車：34 km/h 在緩彎上讀到 329°，一分鐘前後都差不多，像卡住）→ 當作沒有
        val steer = vs.steerDeg?.takeIf { TurnPathPlanner.plausibleSteer(it, vs.speedKmh) }
        val turnPlan = TurnPathPlanner.plan(vs.turn, steer, vs.speedKmh)
            ?: TurnPathPlanner.estimate(vs.turn, vs.speedKmh, roadCurvature(now, vs.speedKmh))
            ?: (if (Crumbs.off("turnopen")) null else Crumbs.step(Crumbs.FRAME, "turnopen") { turnIntoOpening(vs, now) })
        Crumbs.at(Crumbs.FRAME, "publish+turn")
        if (turnPlan == null) turnHit = false
        // 變道預測：打方向燈、方向盤還沒轉到要轉彎 → 從目前車道畫到那一側車道。
        // 變道輔助有算出路徑（有車道線、速度夠）就用它的；沒有（慢速被當成可能轉彎、或沒偵測到車道）
        // 就用車道寬畫一條，長度 = 車速 × 3 s（10～30 m），畫面上才看得出是切過去的。
        // 前方是直路（沒有轉彎路徑）→ 一律畫變道輔助線（使用者要求：不分車速）
        val slowTurn = false
        val laneWNow = lcaState?.laneW ?: LaneChangeAssist.LANE_W
        val lcTargetDeferred = turnPlan == null && !slowTurn
        // 車道線鎖不到時的備援：車速＋方向盤推算橫移（每幀都要算，打燈前用來估方向盤零點）
        val odoDt = if (odoAt == 0L) 0.0 else (now - odoAt).coerceIn(0, 300) / 1000.0
        odoAt = now
        if (vs.turn != 'L' && vs.turn != 'R') lcOdo.learnZero(vs.speedKmh, steer, odoDt)
        // 道路彎度：沒打燈時慢慢平均（約 2 秒），打燈就鎖住——變道動作本身也會讓車頭轉向，不鎖的話
        // 一變道量到的「彎度」就被變道動作混進去，預測線亂彎（實車：彎道上變道軌跡很怪）
        val kNow = steerCurvature() ?: MotionCurvature.curvature(vs.speedKmh)
        val signaling = vs.turn == 'L' || vs.turn == 'R'
        if (!signaling) { if (kNow != null) roadK = if (roadK.isNaN()) kNow else roadK + (kNow - roadK) * (odoDt / 2.0).coerceIn(0.0, 1.0) }
        val kLocked = roadK.takeIf { !it.isNaN() }
        // 推算橫移：扣掉彎道本身造成的轉向（彎度 × 車速），只算變道多出來的
        val yawRel = if (steer == null) MotionCurvature.yawRate()?.let { it - (kLocked ?: 0.0) * ((vs.speedKmh ?: 0f) / 3.6) } else null
        val yBefore = lcOdo.y
        val odo = lcOdo.update(now, vs.turn, vs.speedKmh, steer, laneWNow, yawRel)
        val steerMoved = if (vs.turn == 'L' || vs.turn == 'R') (lcOdo.y - yBefore).toFloat().takeIf { steer != null || yawRel != null } else null
        // 周圍的車整批往一邊移 = 自己往另一邊移（扣掉車頭轉向造成的偏移）；看得到車時優先用它
        var shiftMoved: Float? = null
        if (tracksAt != shiftTracksAt && !Crumbs.off("vshift")) {
            Crumbs.at(Crumbs.FRAME, "vshift")
            val dtT = if (shiftTracksAt == 0L) 0.0 else (tracksAt - shiftTracksAt) / 1000.0
            val dTheta = MotionCurvature.visualYawRate()?.let { it * dtT }
            val tdir = when (vs.turn) { 'L' -> -1; 'R' -> 1; else -> 0 }
            shiftMoved = vehShift.update(tracks.filter { it.confirmed && ObjTypes.group(it.t) == "veh" }
                .map { VehicleShiftOdometry.Obj(it.id, it.x, it.z) }, dTheta, tdir)
            shiftTracksAt = tracksAt
        }
        Crumbs.at(Crumbs.FRAME, "publish+lc")
        val moved = shiftMoved ?: steerMoved
        if (vs.turn == 'L' || vs.turn == 'R') { fusedY += moved ?: 0f; if (shiftMoved != null) usedShift = true }
        else { fusedY = 0f; usedShift = false }
        val lcTarget = if (lcTargetDeferred) lcTrack.update(now, vs.turn,
            road?.takeIf { now - roadAt < BEV_TTL_MS }?.lines, laneWNow, moved) else { lcTrack.reset(); null }
        // 壓線判斷：打燈那側的車道線跑到車底下 = 正在變道；跑到另一側 = 變道完成（偵測得到線時以這個為準）
        val crossDone = lineCross.update(vs.turn, ldwState)
        val shiftDone = usedShift && lcTarget == null && run { val d = if (vs.turn == 'L') -1 else 1; (d * laneWNow - fusedY) * d < LaneChangeOdometry.ARRIVED_M }
        val odoDone = crossDone || shiftDone || (lcTarget == null && !usedShift && lcOdo.done)
        if (turnPlan == null && !slowTurn && lcTarget != 0f && !odoDone && turnForLaneAids(vs).let { it == 'L' || it == 'R' }) {
            val dir = if (vs.turn == 'L') -1 else 1
            val st = lcaState
            val w = st?.laneW ?: LaneChangeAssist.LANE_W
            val len = ((vs.speedKmh ?: 30f) / 3.6f * 3f).coerceIn(10f, 30f)
            // 鎖定到要跨過的車道線 → 終點跟著目標車道中央走（變道過程中線會跟著移、到了就收掉）
            val fusedRemain = (dir * w - fusedY).takeIf { usedShift }
            val path0 = lcTarget?.let { lca.lanePath(it.toDouble(), 0.0, len.toDouble()) }
                ?: fusedRemain?.let { lca.lanePath(it.toDouble(), odo?.second ?: 0.0, len.toDouble()) }
                ?: odo?.let { (tx, phi) -> lca.lanePath(tx, phi, len.toDouble()) }
                ?: st?.path?.takeIf { now - tracksAt < OBJ_TTL_MS && it.size >= 2 }
                ?: lca.lanePath(dir * w.toDouble(), 0.0, len.toDouble())
            // 在彎道上：照目前量到的彎度把路徑彎過去（不然是一條直的斜線，切出彎道外）
            val curveK = kLocked
            val path = CurveBend.bend(path0, curveK)
            // 打燈那一側有車（相鄰車道／盲點，不只是快速接近的危險等級）→ 暫緩變道
            val sideLv = if (st != null && now - tracksAt < OBJ_TTL_MS) (if (dir < 0) st.L else st.R) else 0
            val danger = sideLv >= 1
            laneChangeBlocked = danger
            // 要切過去的那一側是實線／雙線 → 禁止變換車道：走廊打叉
            val rdNow = road
            val ban = if (rdNow != null && now - roadAt < BEV_TTL_MS) LaneChangeRule.check(rdNow.lines, vs.turn!!) else null
            noLaneChange = ban != null
            val pts = JSONArray(); for (q in path) pts.put(JSONArray().put(r2(q[0])).put(r2(q[1])))
            // 變道進度給畫面顯示：離目標車道中心多遠、依據是什麼
            val src = when { lcTarget != null && lcTrack.matched -> "車道線"; lcTarget != null && shiftMoved != null -> "車道線＋周圍車輛"
                lcTarget != null -> "車道線＋推算"; fusedRemain != null -> "周圍車輛"
                steer != null && odo != null -> "方向盤"; odo != null -> "畫面轉向"; else -> "固定" }
            val remain = lcTarget ?: fusedRemain ?: odo?.first?.toFloat()
            p.put("lc", JSONObject().put("pts", pts).put("dir", dir).put("w", r2(w)).put("car", sideLv)
                .put("src", src).apply { remain?.let { put("rem", r2(kotlin.math.abs(it))) } }
                .put("moved", r2(if (usedShift) fusedY else lcOdo.y.toFloat())).put("st", steer?.let { r2(it) } ?: JSONObject.NULL)
                .put("v", vs.speedKmh?.let { r2(it) } ?: JSONObject.NULL)
                .apply { curveK?.takeIf { kotlin.math.abs(it) >= CurveBend.MIN_K }?.let { put("k", (it * 1e5).toLong() / 1e5) } }
                .put("ok", !danger && ban == null && (st?.pathOk ?: true))
                .apply { ban?.let { put("ban", LaneChangeRule.label(it)) } })
        } else {
            noLaneChange = false
            laneChangeBlocked = false
        }
        Crumbs.at(Crumbs.FRAME, "publish+json")
        turnPlan?.let { tp ->
            val outer = JSONArray(); for (q in tp.pts) outer.put(JSONArray().put(r2(q[0])).put(r2(q[1])))
            val inner = JSONArray(); for (q in tp.inner) inner.put(JSONArray().put(r2(q[0])).put(r2(q[1])))
            // 掃過範圍（兩端沿車身收口）：標注直接畫這個多邊形
            val swept = JSONArray(); for (q in tp.sweptPolygon()) swept.put(JSONArray().put(r2(q[0])).put(r2(q[1])))
            val hits = TurnPathPlanner.conflicts(tp, currentObjects(now).map { StartAssist.Obj(it.t, it.x, it.z) })
            turnHit = hits.isNotEmpty() && !tp.estimated   // 預估路徑只變色、不叫（是猜的，容易誤報）
            p.put("turn", JSONObject().put("o", outer).put("i", inner).put("sw", swept).put("d", tp.dir.toString())
                .put("est", if (tp.estimated) 1 else 0)
                .put("r", r2(tp.radius)).put("hit", hits.size))
        }
        startState?.let { st -> if (st.lead || st.green) p.put("start", JSONObject()
            .put("lead", st.lead).put("green", st.green).put("gap", r2(st.gap))) }
        ldwState?.let { ld ->
            if (ld.any || ld.straddle != null) p.put("ldw", JSONObject().put("l", ld.left).put("r", ld.right)
                .apply { ld.straddle?.let { s -> put("st", s.toString()).put("sts", ld.straddleSolid) } })
        }
        if (!vs.isEmpty) p.put("veh", VehicleSignals.toJson(vs))
        if (now - tracksAt < OBJ_TTL_MS) lcaState?.let { p.put("lca", lca.toJson(it)) }
        mapSrc?.takeJsonIfNew()?.let { p.put("map", it) }
        val t = tl
        if (t != null && now - t.at < TL_TTL_MS) p.put("tl", JSONObject().put("s", t.s).put("c", r2(t.c)).put("ax", r2(t.ax)))
        lastPayload = p
        Crumbs.at(Crumbs.FRAME, "sink")
        sink(p)
        if (!Crumbs.off("dashcam")) Crumbs.step(Crumbs.FRAME, "dashcam") { afterFrame?.invoke(this) }
    }

    private fun spd(vs: VehicleState): Boolean { val s = vs.speedKmh; return s == null || s < 15f }

    /**
     * 前方道路的彎度（1/m，正 = 往右彎）：自車前方、跟車身大致平行的標線的曲率，依長度加權平均。
     * RoadLine.k 的正負跟端點順序有關（k > 0 = 往左彎是指端點由後往前），先換成「由後往前」。
     */
    /**
     * 前方道路彎度：車子正在轉的程度（陀螺儀／GPS，見 [MotionCurvature]）明顯在轉時優先用它；
     * 否則用俯視圖車道線（只看得到車旁幾公尺，彎道多半看起來是直的）。
     */
    private fun roadCurvature(now: Long, speedKmh: Float?): Double? {
        // 方向盤（原廠 CAN，很準）有在轉：照方向盤算彎度
        steerCurvature()?.let { return it }
        val km = MotionCurvature.curvature(speedKmh)
        if (km != null && abs(km) >= TurnPathPlanner.ROAD_K_MIN) return km
        return roadCurvatureAhead(now) ?: km
    }

    /** 原廠 CAN 方向盤（前輪角）算出的彎度（1/m，+ = 右）；沒有或幾乎擺正回 null。 */
    private fun steerCurvature(): Double? {
        if (!FactoryVehicle.steerFresh()) return null
        val w = FactoryVehicle.wheel ?: return null
        if (kotlin.math.abs(w) < 0.5f) return null
        return kotlin.math.tan(Math.toRadians(w.toDouble())) / VehicleGeometry().wheelbase
    }

    private fun roadCurvatureAhead(now: Long): Double? {
        val rd = road ?: return null
        if (now - roadAt > BEV_TTL_MS) return null
        var sum = 0.0; var wsum = 0.0
        for (l in rd.lines) {
            val dx = l.x2 - l.x1; val dz = l.z2 - l.z1
            val len = hypot(dx, dz)
            if (len < 3f || abs(dz) < 2f * abs(dx)) continue
            if (maxOf(l.z1, l.z2) < 0f || abs((l.x1 + l.x2) / 2f) > 6f) continue
            val kFwd = if (dz >= 0f) l.k else -l.k      // 由後往前時 k > 0 = 往左彎
            sum += -kFwd * len; wsum += len               // 換成正 = 往右彎
        }
        return if (wsum > 0) sum / wsum else null
    }

    private val lcTrack = LaneChangeTracker()
    private val lcOdo = LaneChangeOdometry()
    private val lineCross = LineCrossWatcher()

    /** 打燈那側有開口（路口、巷口、入口）→ 轉進去不會碰到東西的預估轉彎路徑（見 [TurnSpacePlanner]）。 */
    private fun turnIntoOpening(vs: VehicleState, now: Long): TurnPathPlanner.TurnPath? {
        if (vs.turn != 'L' && vs.turn != 'R') return null
        val pts = ArrayList<FloatArray>()
        for (o in currentObjects(now)) { val r = ObjTypes.radius(o.t); pts.addAll(TurnSpacePlanner.pointsOf(o.x, o.z, r, if (ObjTypes.group(o.t) == "veh") r * 2.5f else r)) }
        if (now - obstaclesAt < OBS_TTL_MS) for (ob in obstacles) pts.addAll(TurnSpacePlanner.pointsOf(ob.x, ob.z, ob.w / 2f, ob.l / 2f))
        road?.takeIf { now - roadAt < BEV_TTL_MS }?.let { rd ->
            for (l in rd.lines) if (l.kind == 'r') pts.addAll(TurnSpacePlanner.pointsOfLine(l.x1, l.z1, l.x2, l.z2))
            rd.edgeL?.let { pts.addAll(TurnSpacePlanner.pointsOfLine(it, -2f, it, 6f)) }
            rd.edgeR?.let { pts.addAll(TurnSpacePlanner.pointsOfLine(it, -2f, it, 6f)) }
        }
        return TurnSpacePlanner.plan(vs.turn, vs.speedKmh, pts)
    }
    private val aids = DriverAids()
    private val hazardPrompt = HazardPrompt()
    @Volatile private var lastFastAt = 0L
    private val lot = ParkingLotState()

    /**
     * 開快（≥ 40 km/h）時車子不可能斜斜地壓過車道線：跟車頭方向差超過 25° 的「線」都是假的
     * （接縫、反光、旁車的影子），不拿來畫也不拿來做偏離／壓線警示（實車 120 km/h：兩條斜線穿過車身成 X，左右偏離一起叫）。
     */
    private fun steepFilter(r: RoadResult, kmh: Float?): RoadResult {
        if ((kmh ?: 0f) < 40f) return r
        var keep = r.lines.filter { l -> val dx = abs(l.x2 - l.x1); val dz = abs(l.z2 - l.z1); dx <= dz * 0.466f }   // tan 25°
        // 國道最左邊（內側）的邊線是黃色實線（使用者確認）：晚上在燈光下常被認成白線，
        // 時速 60 以上，自車左側最外面那條實線就當黃線（不然會誤報「白實線禁止變換車道」）
        if ((kmh ?: 0f) >= 60f) {
            fun xAt0(l: RoadLine) = if (abs(l.z2 - l.z1) > 0.1f) l.x1 + (0f - l.z1) * (l.x2 - l.x1) / (l.z2 - l.z1) else (l.x1 + l.x2) / 2f
            val left = keep.filter { !it.dashed && xAt0(it) < -1.0f }.minByOrNull { xAt0(it) }
            if (left != null && left.kind == 'w') keep = keep.map { if (it === left) it.copy(kind = 'y') else it }
        }
        return if (keep == r.lines) r else r.copy(lines = keep)
    }
    @Volatile private var gearCur: Char? = null
    /** P 檔或在停車場：方向燈不當成要變道（停車場裡打燈、打雙閃不出變道提示、盲點警示） */
    private fun turnForLaneAids(vs: VehicleState): Char? = if (inLot || gearCur == 'P' || gearCur == 'R') null else vs.turn
    @Volatile private var leadCloseAt = 0L

    /**
     * 停車場 vs 塞車的畫面線索：前面 15 m 內有車（排隊）、車子左右兩側都有長的縱向線且間距像車道（2.6～4.4 m；
     * 停車場走道兩側的格子底線間距約 5.5～6 m，不會算進來）、附近有幾格停車格。
     */
    private fun lotClues(now: Long): ParkingLotState.Clues {
        val lines = road?.takeIf { now - roadAt < BEV_TTL_MS }?.lines.orEmpty()
        val lon = lines.filter { abs(it.z2 - it.z1) >= 5f && abs(it.x2 - it.x1) < 0.35f * abs(it.z2 - it.z1) }
            .map { (it.x1 + it.x2) / 2f }
        val l = lon.filter { it in -4.4f..-0.8f }.maxOrNull()
        val r = lon.filter { it in 0.8f..4.4f }.minOrNull()
        val lanes = (l != null && r != null && r - l in 2.6f..4.4f) ||
            lines.any { it.dashed && abs(it.z2 - it.z1) >= 3f && abs(it.x2 - it.x1) < 0.35f * abs(it.z2 - it.z1) }
        val nSlots = if (now - slotsAt < 2000L) slots.size else 0
        return ParkingLotState.Clues(leadClose = now - leadCloseAt < 1500L, longLanes = lanes, slots = nSlots)
    }
    @Volatile private var inLot = false
    @Volatile private var aidKeys: Set<String> = emptySet()
    private var rdStopAt = 0L
    private var rdStartUntil = 0L
    @Volatile private var onRoad = false
    private val parkAids = ParkingAids()
    private var lastEventLock = 0L

    /** 停好車：存一張俯視圖到「下載/avm-adas」（parked_時間.png），看得到格號、柱子，之後找得到車。 */
    private fun saveParkedSnapshot() {
        val buf = bevArgb.copyOf(); val g = geo ?: return
        Thread {
            try {
                val bmp = Bitmap.createBitmap(buf, g.bev.width, g.bev.height, Bitmap.Config.ARGB_8888)
                val bos = java.io.ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                MediaSaver.save(ctx, "parked_$ts.png", "image/png", bos.toByteArray())
                lastParkedShot = "parked_$ts.png"
                Engine360.log("已存停車位置：parked_$ts.png")
            } catch (t: Throwable) { Log.w(TAG, "停車位置存檔失敗", t) }
        }.start()
    }

    @Volatile var lastParkedShot: String? = null; private set
    @Volatile private var aidsDanger = false
    @Volatile private var slotGeo: AvmGeometry? = null
    private var slotLut: IntArray? = null
    private var slotLutKey = 0L
    @Volatile private var slotBuf: IntArray? = null
    // 自車移動累計（給停車格記憶用）：每幀加，偵測前取走
    private val egoLock = Any()
    private var egoDz = 0f
    private var egoYaw = 0f
    private var egoAt = 0L

    /** 每幀：照車速＋方向盤（自行車模型）累計自車移動。倒車檔往後。 */
    private fun accumulateEgoMotion(now: Long) {
        val vs = VehicleBus.signals.snapshot()
        val dt = if (egoAt == 0L) 0f else ((now - egoAt).coerceIn(0, 300)) / 1000f
        egoAt = now
        val v = (vs.speedKmh ?: return) / 3.6f * (if (vs.gear == 'R') -1f else 1f)
        val st = vs.steerDeg?.takeIf { TurnPathPlanner.plausibleSteer(it, vs.speedKmh) } ?: 0f
        val geo = VehicleGeometry()
        val wheel = Math.toRadians((st / geo.steerRatio).toDouble().coerceIn(-geo.maxWheelDeg, geo.maxWheelDeg))
        val dyaw = (v * kotlin.math.tan(wheel) / geo.wheelbase * dt).toFloat()
        synchronized(egoLock) {
            egoDz += v * dt
            egoYaw += dyaw
        }
        stabilizer.egoMotion(v * dt, dyaw)
        sideBlink.egoMotion(v * dt, dyaw)
    }

    /** 周圍車輛打方向燈（側邊、後方鏡頭）。 */
    private val sideBlink = SideBlinkWatcher()
    private var blinkBuf = IntArray(64 * 48)

    /** 停著的車在畫面上釘住不飄（只影響畫的位置；警示照用即時偵測）。 */
    private val stabilizer = StaticStabilizer()
    // 車頭朝向模型（有 orient.tflite 才用）：停著／慢慢開、又不在格子裡的車也知道車頭朝哪
    private val orientNet: OrientNet? by lazy { if (OrientNet.available(ctx)) try { OrientNet(ctx) } catch (t: Throwable) { Log.w(TAG, "orient", t); null } else null }
    private val orientSmooth = OrientHeading.Smoother()

    private fun takeEgoMotion(): Pair<Float, Float> = synchronized(egoLock) { (egoDz to egoYaw).also { egoDz = 0f; egoYaw = 0f } }
    private val vehShift = VehicleShiftOdometry()
    private var shiftTracksAt = 0L
    private var fusedY = 0f
    private var usedShift = false
    private var roadK = Double.NaN
    private var odoAt = 0L
    private val roadside = RoadsideSpaceFinder()
    private var shoulderSince = 0L
    private var spdPrev = Float.NaN; private var spdPrevAt = 0L; private var decel = 0f   // 自車減速度（m/s²，EMA）

    /**
     * 打雙閃：
     * - 慢速（≤ 25 km/h）→ 找右側路邊空位，找到就畫成選好的格子＋平行停車路徑（往前開到起點再倒車入位）。
     * - 高速（≥ 50 km/h）→ 前車急速接近或自己在急減速 = 前方急煞（打雙閃是提醒後車），不引導，只提醒；
     *   否則當成要靠邊停：畫往右到路肩的引導線（路肩線是實線，這時不打叉）。
     */
    private fun roadsideAndShoulder(p: JSONObject, vs: VehicleState, now: Long) {
        val v = vs.speedKmh
        if (v != null) {
            if (!spdPrev.isNaN() && now > spdPrevAt) {
                val a = ((spdPrev - v) / 3.6f) / ((now - spdPrevAt) / 1000f)
                decel += 0.3f * (a - decel)
            }
            spdPrev = v; spdPrevAt = now
        }
        val rd = road?.takeIf { now - roadAt < BEV_TTL_MS }
        val objs = currentObjects(now)
        val red = rd?.lines?.any { it.kind == 'r' && minOf(it.x1, it.x2) > 0.8f && minOf(it.z1, it.z2) < 2f && maxOf(it.z1, it.z2) > -2f } ?: false
        val spot = roadside.update(now, v, vs.turn, vs.gear, objs.map { RoadsideSpaceFinder.Obj(it.t, it.x, it.z) }, rd?.edgeR, red)
        if (spot != null) {
            val sj = p.optJSONArray("slots") ?: JSONArray().also { p.put("slots", it) }
            val c = spot.corners()
            sj.put(JSONObject().put("p", JSONArray().apply { c.forEach { put(r2(it)) } }).put("k", "l").put("o", 0).put("c", 1.0).put("sel", 1))
            roadside.plan(spot)?.let { pl ->
                val pts = JSONArray(); for (q in pl.points) pts.put(JSONArray().put(r2(q[0])).put(r2(q[1])))
                p.put("park", JSONObject().put("pts", pts).put("rev", pl.reverseFrom).put("r", r2(pl.radius))
                    .put("goal", JSONArray().put(r2(pl.goal[0])).put(r2(pl.goal[1])).put(0)))
                p.put("curb", JSONObject().put("len", r2(spot.len)).put("ahead", r2(pl.stopAheadM)))
            }
        }
        // 已經在路肩：左邊緊鄰一條實線（路肩線），右邊沒有別的車道線、或很近就是路緣 → 不用再引導
        // 路肩：慢速（≤ 20 km/h）才算「已在路肩」，而且要連續 1.5 秒（實車：高速行駛中打雙閃也顯示已在路肩，
        // 是右邊車道線漏抓造成的）
        val shoulderNow = vs.turn == 'H' && (v ?: 99f) <= 20f && rd != null && RoadsideRules.onShoulder(rd.lines, rd.edgeR)
        if (!shoulderNow) shoulderSince = 0L else if (shoulderSince == 0L) shoulderSince = now
        val onShoulder = shoulderNow && now - shoulderSince >= 1500
        if (onShoulder) p.put("shoulder", JSONObject().put("on", true))
        if (vs.turn == 'H' && v != null && v >= 50f && !onShoulder) {
            // 前方急煞：正前方的車快速接近（相對速度 < −4 m/s），或自車減速度 > 3 m/s²
            val leadClosing = now - tracksAt < OBJ_TTL_MS && tracks.any { it.confirmed && abs(it.x) < 1.8f && it.z in 3f..60f && it.vz < -4f }
            if (leadClosing || decel > 3f) {
                p.put("shoulder", JSONObject().put("brake", true))
            } else {
                // 路肩：右側路緣往內 1.2 m；沒有路緣 → 最右邊那條車道線再往右 1.6 m；都沒有 → 一個半車道
                val rightLine = rd?.lines?.filter { abs(it.z2 - it.z1) > 2f * abs(it.x2 - it.x1) }
                    ?.map { l -> l.x1 + (l.x2 - l.x1) * (if (abs(l.z2 - l.z1) < 1e-6f) 0f else -l.z1 / (l.z2 - l.z1)) }
                    ?.filter { it > 0.5f }?.maxOrNull()
                val target = rd?.edgeR?.let { it - 1.2f } ?: rightLine?.let { it + 1.6f } ?: (LaneChangeAssist.LANE_W * 1.5f)
                val len = (v / 3.6f * 5f).coerceIn(30f, 80f)
                val path = lca.lanePath(target.toDouble(), 0.0, len.toDouble())
                val pts = JSONArray(); for (q in path) pts.put(JSONArray().put(r2(q[0])).put(r2(q[1])))
                val st = lcaState
                val danger = st != null && now - tracksAt < OBJ_TTL_MS && st.R >= 1
                p.put("lc", JSONObject().put("pts", pts).put("dir", 1).put("w", r2(LaneChangeAssist.LANE_W)).put("car", if (danger) 1 else 0)
                    .put("ok", !danger).put("sh", 1))
                p.put("shoulder", JSONObject().put("brake", false).put("x", r2(target)))
            }
        }
    }

    /** 使用者點選要停的格子（[AnnotationView] 點俯視圖）。 */
    val slotSelection = SlotSelection()

    /** 點了俯視圖的 (x, z)：點到空格就選它（再點一次取消）。回傳是否點到格子。 */
    fun tapSlot(x: Float, z: Float): Boolean =
        slotSelection.tap(slots, x, z, SystemClock.elapsedRealtime())

    private fun parkPathJson(list: List<ParkingSlot>): JSONObject? {
        // 只有「選了格子」才畫停車路徑：使用者在畫面上點的，或倒車打方向燈自動找的那一格。
        // 沒選就不畫（以前會自動挑最近的空格畫路徑，停車場裡一直有路徑晃來晃去）
        val chosen = slotSelection.track(list, SystemClock.elapsedRealtime()) ?: return null
        val free = listOf(chosen).filter { it.kind == 'p' || it.kind == 'a' }
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
}
