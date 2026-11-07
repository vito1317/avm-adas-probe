package com.vito.avmprobe

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.Image
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import java.lang.ref.WeakReference
import org.json.JSONObject

/**
 * 合併版的入口：這整包（偵測管線＋標注圖層）被編進**原廠 360 的 APK**，跑在原廠的 process 裡。
 * 原廠那邊注入的 FrameTap 用反射呼叫這裡的靜態方法（類別名稱 R8 會保留）：
 *
 *  - [attach]：原廠 bowl 畫面 onCreate → 在它的畫面上加透明的 [AnnotationView] 和一顆「ADAS」設定鈕。
 *  - [enabled] / [surface]：原廠相機加輸出前問開關、拿我們的 Surface（同一個 process，不用 IPC）。
 *  - [log]：FrameTap 回報的事件，顯示在診斷文字裡。
 *
 * 跟兩個 App 的 B 模式差在：沒有 3D 面板。原廠 360 以 system uid 執行，Android 不准系統 process
 * 用 WebView，所以結果直接畫在原廠俯視圖上（[AnnotationView]）。
 *
 * 安全：所有進入點都 try/catch；連續 3 次啟動沒撐過 [HEALTHY_MS] 就自動關掉自己（崩潰保護），
 * 使用者可以從「ADAS」鈕重新打開。
 */
object Engine360 {
    private const val TAG = "Engine360"
    private const val HEALTHY_MS = 60_000L
    /** 我們的程式害原廠閃退這麼多次（沒撐過 HEALTHY_MS）就自動停用。 */
    private const val MAX_OUR_CRASHES = 2
    private const val VIEW_TAG = "com.vito.avmprobe.annot"

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var view: WeakReference<AnnotationView>? = null
    @Volatile private var pipeline: AdasPipeline? = null
    private var signalsAttached = false
    private var ticking = false

    @JvmStatic
    fun enabled(ctx: Context): Boolean = try { AppPrefs(ctx).annotEnabled } catch (_: Throwable) { false }

    @JvmStatic
    fun surface(w: Int, h: Int): Surface? = try { FrameShareHub.surface(w, h) } catch (_: Throwable) { null }

    @JvmStatic
    fun log(msg: String) { try { FrameShareHub.log(msg) } catch (_: Throwable) {} }

    @Volatile private var lastViewKey: String? = null
    private var buttonRef: WeakReference<View>? = null

    /** 截全螢幕時要先藏起來的我們自己的東西：標注圖層、ADAS 鈕、設定視窗。 */
    private fun ownViews(): List<View> =
        listOfNotNull(view?.get(), buttonRef?.get(), Engine360Settings.dialogDecor?.get())
    private var activity: WeakReference<Activity>? = null
    private val calThread by lazy { android.os.HandlerThread("autocal").apply { start() } }
    private val cal by lazy { android.os.Handler(calThread.looper) }
    @Volatile private var calibrating = false

    /**
     * 自動對位目前（或指定）的視角：擷取原廠畫面＋同一刻我們的俯視圖 → [AutoCalibrator]。
     * 先找俯視圖（比例、位置、方向）與它那一格的範圍，剩下的格子當 3D／鏡頭畫面找虛擬鏡頭。
     * 背景執行緒算（車機上要幾秒到十幾秒），完成後存成這個視角的設定。[done] 在主執行緒回報結果文字。
     */
    fun autoCalibrate(key: String, done: ((String) -> Unit)? = null) {
        val act = activity?.get(); val p = pipeline
        if (act == null || p == null) { done?.invoke("還沒收到影格，等畫面出來再試"); return }
        if (calibrating) { done?.invoke("正在自動對位中…"); return }
        calibrating = true
        log("自動對位開始：$key")
        p.requestBev { bev ->
            main.post {
                if (bev == null) { calibrating = false; done?.invoke("拿不到俯視圖"); return@post }
                FactoryScreen.capture(act, hide = ownViews()) { scr ->
                    if (scr == null) {
                        calibrating = false
                        val why = "擷取不到原廠畫面：" + FactoryScreen.lastError
                        log(why); done?.invoke(why); return@capture
                    }
                    log("擷取原廠畫面 ${scr.sw}x${scr.sh} @(${scr.sx0},${scr.sy0})" + if (FactoryScreen.lastError.isNotEmpty()) "（${FactoryScreen.lastError}）" else "")
                    cal.post {
                        val msg = try { runCalibration(act, key, scr, bev) } catch (t: Throwable) { "自動對位失敗：$t" }
                        calibrating = false
                        log(msg)
                        main.post { done?.invoke(msg) }
                    }
                }
            }
        }
    }

    /** 從原廠畫面截下俯視圖上的車模（給行車記錄器用），用目前視角的俯視對位。 */
    fun captureEgoSprite(done: (String) -> Unit) {
        val act = activity?.get() ?: return done("原廠 360 畫面沒開")
        val top = view?.get()?.currentProfile()?.top ?: return done("這個視角沒有俯視圖，切到有俯視圖的畫面再按")
        FactoryScreen.capture(act, maxW = 1920, hide = ownViews()) { scr ->
            if (scr == null) { done("擷取不到原廠畫面：" + FactoryScreen.lastError); return@capture }
            cal.post {
                val msg = try { EgoSprite.save(act, scr, top) } catch (t: Throwable) { "存車模圖案失敗：$t" }
                log(msg); main.post { done(msg) }
            }
        }
    }

    /**
     * 自動對位用的資料存下來（原廠畫面、我們的俯視圖、結果）到「下載/avm-adas」：
     * 實車對不準時，把這幾個檔傳給開發者，在電腦上用真實畫面重跑、找原因。
     */
    private fun dumpCalibration(ctx: Context, tag: String, scr: AutoCalibrator.Screen, bev: AutoCalibrator.Bev, result: JSONObject) {
        cal.post {
            try {
                val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                val safe = tag.replace(Regex("[^A-Za-z0-9_]"), "_")
                fun png(argb: IntArray, w: Int, h: Int): ByteArray {
                    val b = android.graphics.Bitmap.createBitmap(argb, w, h, android.graphics.Bitmap.Config.ARGB_8888)
                    val bos = java.io.ByteArrayOutputStream(); b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
                    return bos.toByteArray()
                }
                MediaSaver.save(ctx, "autocal_${ts}_${safe}_screen.png", "image/png", png(scr.argb, scr.w, scr.h))
                MediaSaver.save(ctx, "autocal_${ts}_${safe}_bev.png", "image/png", png(bev.argb, bev.w, bev.h))
                val meta = JSONObject().put("tag", tag)
                    .put("screen", JSONObject().put("w", scr.w).put("h", scr.h).put("sx0", scr.sx0).put("sy0", scr.sy0)
                        .put("sw", scr.sw).put("sh", scr.sh).put("screenW", scr.screenW).put("screenH", scr.screenH)
                        .put("how", FactoryScreen.lastError.ifEmpty { "繪圖面直接複製" }))
                    .put("bev", JSONObject().put("w", bev.w).put("h", bev.h).put("mpp", bev.mpp.toDouble()).put("ox", bev.ox.toDouble()).put("oy", bev.oy.toDouble()))
                    .put("result", result)
                MediaSaver.save(ctx, "autocal_${ts}_${safe}.json", "application/json", meta.toString(2).toByteArray())
            } catch (t: Throwable) { Log.e(TAG, "dump", t) }
        }
    }

    private fun runCalibration(ctx: Context, key: String, scr: AutoCalibrator.Screen, bev: AutoCalibrator.Bev): String {
        val prefs = AppPrefs(ctx)
        val t0 = SystemClock.elapsedRealtime()
        val top = AutoCalibrator.calibrateTop(scr, bev)
        var topParams: AnnotationMapper.Params? = null
        // 3D 格：有俯視圖 → 原廠畫面去掉俯視格剩下的那一側；沒有 → 整個原廠畫面
        val surf = floatArrayOf(scr.sx0.toFloat() / scr.screenW, scr.sy0.toFloat() / scr.screenH,
            (scr.sx0 + scr.sw).toFloat() / scr.screenW, (scr.sy0 + scr.sh).toFloat() / scr.screenH)
        var pane3 = surf
        if (top != null) {
            val tp = AutoCalibrator.topPane(scr, top.params, bev)
            topParams = top.params.copy(clipL = tp[0], clipT = tp[1], clipR = tp[2], clipB = tp[3])
            val leftW = tp[0] - surf[0]; val rightW = surf[2] - tp[2]
            pane3 = when {
                rightW >= leftW && rightW > 0.15f -> floatArrayOf(tp[2], surf[1], surf[2], surf[3])
                leftW > 0.15f -> floatArrayOf(surf[0], surf[1], tp[0], surf[3])
                else -> floatArrayOf(0f, 0f, 0f, 0f)   // 整個都是俯視圖
            }
        }
        // 3D 不自動對位：用實車截圖驗證過，原廠 3D 貼在碗狀曲面上、車附近被車模蓋住，
        // 影像比對挑不出對的角度（分數 0.2～0.3、兩張同視角選出不同結果）。保留原本的 3D 設定（手動或預設）。
        val p3: AutoCalibrator.P3Result? = null
        val keep3d = prefs.viewProfile(key, lastBvName).p3
        dumpCalibration(ctx, "auto_$key", scr, bev, JSONObject()
            .put("top", top?.let { ViewProfile(topParams, null).toJson().put("score", it.score.toDouble()) } ?: JSONObject.NULL)
            .put("p3", p3?.let { ViewProfile(null, it.params).toJson().put("score", it.score.toDouble()) } ?: JSONObject.NULL)
            .put("pane3", org.json.JSONArray(pane3.map { it.toDouble() })))
        if (topParams == null) return "自動對位：$key 找不到俯視圖（停車、周圍地上有標線時再試）"
        prefs.setViewProfile(key, ViewProfile(topParams, keep3d))
        prefs.setViewCalMode(key, "auto")
        topParams?.let { prefs.annotParams = it }
        try { log(EgoSprite.save(ctx, scr, topParams)) } catch (t: Throwable) { log("存車模圖案失敗：$t") }
        view?.get()?.invalidateProfiles()
        val dt = (SystemClock.elapsedRealtime() - t0) / 1000f
        return "自動對位完成：$key（俯視 ${top?.score?.let { "%.2f".format(it) } ?: "—"}，${"%.1f".format(dt)} 秒；3D 請手動對位）"
    }

    // ───────── 使用者拖動／縮放原廠 3D：停手後從上一組參數快速重新對位 ─────────

    /** 拖動後的角度（只在目前這個視角有效，換視角就丟掉；不存檔，原廠下次會回到預設角度）。 */
    private val overrides = java.util.concurrent.ConcurrentHashMap<String, ViewProfile>()
    @Volatile private var gestureAt = 0L
    @Volatile private var viewChangedAt = 0L
    private var gPath = 0f; private var gLastA = 0; private var gLastB = 0; private var gKind = 0; private var gN = 0
    private const val GESTURE_QUIET_MS = 1200L

    /** FrameTap 在原廠 nativeDragControl／nativeDoubleDragControl／nativeScaleControl 之前呼叫。 */
    @JvmStatic
    fun onGesture(kind: Int, a: Int, b: Int) {
        try {
            val now = SystemClock.elapsedRealtime()
            // 一次手勢（間隔 < 0.6 秒算同一次）：累計移動量。長按切視角時手指按著不動也會送訊號，那不算拖動
            if (now - gestureAt > 600 || kind != gKind) { gPath = 0f; gLastA = a; gLastB = b; gKind = kind; gN = 0 }
            gPath += kotlin.math.abs(a - gLastA) + kotlin.math.abs(b - gLastB)
            gLastA = a; gLastB = b
            gestureAt = now
            if (gN++ < 3) log("手勢 kind=$kind a=$a b=$b 累計=$gPath")
            if (now - viewChangedAt < 2500) return          // 剛切視角（長按進來的）
            if (kind != 3 && gPath < 30f) return            // 沒真的拖動
            main.post {
                view?.get()?.suspend3d = true
                main.removeCallbacks(retrackCheck)
                main.postDelayed(retrackCheck, GESTURE_QUIET_MS)
            }
        } catch (_: Throwable) {}
    }

    /**
     * 拖動後：原廠 3D 的角度讀不到、影像比對在實車上也追不準 → 3D 標注藏到換視角為止
     * （原廠換視角會回到預設角度，那時對位又是對的），免得畫一個對不上的東西誤導人。
     */
    private val retrackCheck = object : Runnable {
        override fun run() {
            log("3D 角度被拖動過，3D 標注先隱藏，換視角後恢復")
        }
    }

    private fun retrack() {
        val key = lastViewKey; val av = view?.get(); val act = activity?.get(); val p = pipeline
        if (key == null || av == null || act == null || p == null) { av?.suspend3d = false; return }
        val prof = av.currentProfile()
        val p3 = prof.p3 ?: run { av.suspend3d = false; return }
        if (calibrating) { main.postDelayed(retrackCheck, 1000); return }
        calibrating = true
        p.requestBev { bev ->
            main.post {
                if (bev == null) { calibrating = false; return@post }
                FactoryScreen.capture(act, hide = ownViews()) { scr ->
                    if (scr == null) { calibrating = false; log("拖動後重新對位：擷取不到原廠畫面"); return@capture }
                    cal.post {
                        val t0 = SystemClock.elapsedRealtime()
                        val r = try { AutoCalibrator.retrack3d(scr, p3, bev) } catch (t: Throwable) { null }
                        calibrating = false
                        main.post {
                            if (lastViewKey != key || SystemClock.elapsedRealtime() - gestureAt < GESTURE_QUIET_MS) return@post
                            if (r != null) {
                                overrides[key] = prof.copy(p3 = r.params)
                                av.invalidateProfiles()
                                av.suspend3d = false
                                log("拖動後重新對位：%.2f（%.1f 秒）".format(r.score, (SystemClock.elapsedRealtime() - t0) / 1000f))
                            } else {
                                // 找不到：恢復原本的標注（不要讓 3D 就這樣消失）
                                av.suspend3d = false
                                log("拖動後重新對位失敗，先用原本的對位")
                            }
                            dumpCalibration(act, "retrack_$key", scr, bev, JSONObject()
                                .put("start", ViewProfile(null, p3).toJson()).put("result", r?.let { ViewProfile(null, it.params).toJson().put("score", it.score.toDouble()) } ?: JSONObject.NULL))
                        }
                    }
                }
            }
        }
    }

    /** 原廠切到還沒對位過的視角 → 等轉場動畫跑完，停著或很慢時自動對位一次。 */
    private fun maybeAutoCalibrate(key: String) {
        val ctx = activity?.get() ?: return
        val prefs = AppPrefs(ctx)
        if (!prefs.annotEnabled || prefs.viewCalMode(key).isNotEmpty()) return
        main.postDelayed({
            if (lastViewKey != key) return@postDelayed
            val spd = VehicleBus.signals.snapshot().speedKmh
            if (spd != null && spd > 3f) { log("自動對位：$key 等停車再做"); return@postDelayed }
            autoCalibrate(key) { msg -> try { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() } catch (_: Throwable) {} }
        }, 2500)
    }

    /**
     * 原廠切換視角時（注入在 nativesetBvState 前）：[name] 是 BV_state 列舉名稱（BV_LEFT、BV_REAR_3D…）。
     * 標注圖層改用那個視角存的對位。
     */
    @JvmStatic
    fun onViewState(value: Int, name: String) {
        try {
            if (lastBvName != name) { lastBvName = name; main.post { view?.get()?.invalidateProfiles() } }
            // 讀得到原廠目前場景時，以場景為準（側邊畫面、打燈畫面有些不走 setBvState）
            if (sceneReadable) return
            setViewKey(name)
        } catch (_: Throwable) {}
    }

    @Volatile private var lastBvName: String = ""

    private fun setViewKey(key: String) {
        if (lastViewKey == key) return
        // 換了視角：原廠會回到那個視角的預設角度 → 拖動後的角度只在原本那個視角有效
        lastViewKey?.let { overrides.remove(it) }
        viewChangedAt = SystemClock.elapsedRealtime()
        view?.get()?.let { it.suspend3d = false; it.invalidateProfiles() }
        lastViewKey = key
        log("原廠視角：$key" + if (lastBvName.isNotEmpty() && !key.contains(lastBvName)) "（$lastBvName）" else "")
        view?.get()?.viewKey = key
        main.post { maybeAutoCalibrate(key) }
    }

    // ── 直接問原廠原生繪圖現在畫哪個場景／版面（比 setBvState 可靠：打燈的側邊畫面、
    //    自動切回都不一定走 setBvState）。讀不到就退回 setBvState。
    private var sceneM: java.lang.reflect.Method? = null
    private var layoutM: java.lang.reflect.Method? = null
    @Volatile private var sceneReadable = false
    private var sceneTried = false

    private fun readSceneKey(): String? {
        if (!sceneTried) {
            sceneTried = true
            try {
                val k = Class.forName("com.baony.birdview.BVDisplayManager")
                sceneM = k.getMethod("nativegetCurrentScene")
                layoutM = try { k.getMethod("nativeGetLayout") } catch (_: Throwable) { null }
            } catch (t: Throwable) { log("讀不到原廠場景，改用 setBvState：$t") }
        }
        val sm = sceneM ?: return null
        return try {
            val sc = sm.invoke(null) as Int
            val ly = layoutM?.let { it.invoke(null) as Int }
            sceneReadable = true
            "SCENE_$sc" + (ly?.let { "_L$it" } ?: "")
        } catch (t: Throwable) {
            sceneM = null; sceneReadable = false
            log("讀原廠場景失敗，改用 setBvState：$t")
            null
        }
    }

    private val scenePoll = object : Runnable {
        override fun run() {
            if (view?.get()?.isAttachedToWindow == true) readSceneKey()?.let { setViewKey(it) }
            main.postDelayed(this, 400)
        }
    }
    private var scenePolling = false

    /**
     * 不靠原廠畫面也要跑的部分（車機訊號、影格接收、行車記錄器）。
     * 原廠相機開機就建好時（FrameTap 在相機建構時排程）呼叫，畫面 attach 時也會呼叫；重複呼叫沒事。
     */
    @JvmStatic
    fun startBackground(ctx: Context) {
        try {
            val app = ctx.applicationContext ?: ctx
            val prefs = AppPrefs(app)
            prefs.migrateBad3dOnce()
            FactoryVehicle.start(app)
            Thread({ FactoryViews.load(app); log(FactoryViews.status); main.post { view?.get()?.invalidateProfiles() } }, "factory-views").start()
            // 合併版：偏離車道、變道、路邊紅黃線都要車道偵測，沒選過就預設開（標準版 App 預設是關）
            if (!prefs.groundModeSet) prefs.groundMode = AppPrefs.GROUND_VIRTUAL
            if (!signalsAttached) {
                signalsAttached = true
                try { VehicleBus.signals.attach(HeadUnitSignalAdapter(app, bindEnabled = prefs.headUnitBind)) }
                catch (t: Throwable) { log("車機訊號接不上：$t") }
            }
            if (FrameShareHub.listener == null) FrameShareHub.listener = { img -> onFrame(app, prefs, img) }
            if (prefs.annotEnabled) Dashcam.start(app, prefs)
        } catch (t: Throwable) {
            Log.e(TAG, "startBackground 失敗（不影響原廠）", t)
        }
    }

    @JvmStatic
    fun attach(act: Activity) {
        try {
            val app = act.applicationContext
            val prefs = AppPrefs(app)
            crashGuard(act, prefs)
            startBackground(app)
            activity = WeakReference(act)
            addViews(act, prefs)
            if (!ticking) { ticking = true; main.post(statusTick) }
            if (!scenePolling) { scenePolling = true; main.post(scenePoll) }
            log("合併版已掛上 ${act.javaClass.simpleName}（ADAS ${if (prefs.annotEnabled) "開" else "關"}）")
        } catch (t: Throwable) {
            Log.e(TAG, "attach 失敗（不影響原廠）", t)
        }
    }

    /**
     * 崩潰保護：只算**真的閃退、而且堆疊裡有我們的程式**（[onCrash] 記的）。
     * 以前是數「啟動幾次」，但原廠一次啟動會建好幾個畫面（注入點在父類別和子類別都有），
     * 冷開機還會重建，一啟動就數到上限把 ADAS 關掉 —— 那不是閃退。
     */
    private var guardArmed = false
    private fun crashGuard(act: Activity, prefs: AppPrefs) {
        prefs.annotBoots = 0
        if (prefs.lastCrash.isNotEmpty()) log("上次閃退：" + prefs.lastCrash.lineSequence().take(2).joinToString(" / "))
        if (!prefs.annotEnabled) return
        val n = prefs.annotCrashes
        if (n >= MAX_OUR_CRASHES) {
            prefs.annotEnabled = false
            prefs.annotCrashes = 0
            log("我們的程式連續害原廠閃退 $n 次，ADAS 已自動停用")
            try { Toast.makeText(act, "ADAS 造成閃退，已自動停用（按「ADAS」可看原因、重新開啟）", Toast.LENGTH_LONG).show() }
            catch (_: Throwable) {}
            return
        }
        if (!guardArmed) {
            guardArmed = true
            main.postDelayed({ prefs.annotCrashes = 0 }, HEALTHY_MS)   // 撐過一分鐘就當作沒事
        }
    }

    /**
     * 原廠 process 要閃退了（FrameTap 裝的 UncaughtExceptionHandler 呼叫）：
     * 完整堆疊存到「下載/avm-adas/crash_*.txt」，摘要記在設定裡；堆疊有我們的程式才算進崩潰保護。
     */
    @JvmStatic
    fun onCrash(ctx: Context, trace: String) {
        try {
            val prefs = AppPrefs(ctx)
            val ours = "com.vito" in trace
            prefs.lastCrash = (if (ours) "【我們的程式】" else "【原廠程式】") + "\n" + trace.lineSequence().take(6).joinToString("\n")
            if (ours) prefs.annotCrashes = prefs.annotCrashes + 1
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            MediaSaver.save(ctx, "crash_$ts.txt", "text/plain", trace.toByteArray())
        } catch (_: Throwable) {}
    }

    private fun addViews(act: Activity, prefs: AppPrefs) {
        val decor = act.window?.decorView as? ViewGroup ?: return
        if (decor.findViewWithTag<View>(VIEW_TAG) != null) return
        val dp = act.resources.displayMetrics.density

        val av = AnnotationView(act).apply {
            tag = VIEW_TAG
            profileFor = { k -> overrides[k] ?: prefs.viewProfile(k, lastBvName) }
            onTapGround = { x, z -> pipeline?.tapSlot(x, z) ?: false }
            viewKey = lastViewKey
            drawRoad = prefs.annotDrawRoad
            heightScale = prefs.obj3dHeight
        }
        decor.addView(av, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view = WeakReference(av)

        val btn = TextView(act).apply {
            text = "ADAS"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 16 * dp
                setColor(Color.argb(170, 20, 30, 45))
                setStroke((1 * dp).toInt(), Color.argb(200, 0, 200, 255))
            }
            setOnClickListener { Engine360Settings.show(act, prefs, av) }
        }
        buttonRef = WeakReference(btn)
        decor.addView(btn, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = (8 * dp).toInt(); topMargin = (8 * dp).toInt()
        })
    }

    /** 影格執行緒。標注圖層不在畫面上（離開 360 畫面）時不跑偵測，省電也不搶原廠的 CPU。 */
    @Volatile private var latestPayload: JSONObject? = null

    /**
     * 影格執行緒。原廠 360 畫面開著（標注要畫）或行車記錄器在錄 → 跑偵測；都沒有就不跑，不搶原廠的 CPU。
     */
    private fun onFrame(ctx: Context, prefs: AppPrefs, img: Image) {
        val v = view?.get()
        val visible = v != null && v.isAttachedToWindow
        val recording = Dashcam.recorder?.running == true
        if (!prefs.annotEnabled || (!visible && !recording)) return
        try {
            val p = pipeline ?: AdasPipeline(ctx, prefs) { j -> latestPayload = j; view?.get()?.update(j) }.also {
                it.afterFrame = { pp -> Dashcam.onFrame(pp, latestPayload) }
                pipeline = it
            }
            p.onFrame(img)
        } catch (t: Throwable) {
            Log.e(TAG, "frame 失敗：${t.message}", t)
        }
    }

    /** 設定裡「匯出原廠車模檔」：背景找 .vmd 與原廠設定檔、匯出到「下載/avm-adas」。[done] 在主執行緒回報結果。 */
    fun exportCarModels(act: Activity, done: (String) -> Unit) {
        cal.post {
            val msg = try { CarModelExporter.export(act) } catch (t: Throwable) { "匯出失敗：$t" }
            log(msg.lineSequence().first())
            main.post { done(msg) }
        }
    }

    /** 設定裡「煞車訊號探測」：背景記錄 15 秒（使用者踩放煞車），[progress]／[done] 在主執行緒回報。 */
    fun probeBrake(ctx: Context, progress: (String) -> Unit, done: (String) -> Unit) {
        Thread {
            val msg = try { BrakeProbe.run(ctx) { s -> main.post { progress(s) } } } catch (t: Throwable) { "探測失敗：$t" }
            log(msg.lineSequence().first())
            main.post { done(msg) }
        }.start()
    }

    /** 設定裡「測試提示音」：依序播三種提示音（起步／轉綠、偏離車道、危險），確認有聲音、音量夠。 */
    fun testSounds(act: Activity) {
        val tg = try { android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 90) } catch (_: Throwable) {
            Toast.makeText(act, "這台車機開不了提示音", Toast.LENGTH_LONG).show(); return
        }
        val seq = listOf(
            "起步／轉綠" to { tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 300) },
            "偏離車道" to { tg.startTone(android.media.ToneGenerator.TONE_CDMA_PIP, 400) },
            "危險（來車、太近）" to { tg.startTone(android.media.ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 700) }
        )
        seq.forEachIndexed { i, (name, play) ->
            main.postDelayed({ try { play(); Toast.makeText(act, "提示音：$name", Toast.LENGTH_SHORT).show() } catch (_: Throwable) {} }, i * 1500L)
        }
        main.postDelayed({ tg.release() }, seq.size * 1500L + 1000L)
    }

    /** 設定裡「存原始影格」：下一幀存檔，完成後跳提示。沒在跑偵測時直接說明。 */
    fun saveRaw(act: Activity) {
        val p = pipeline
        if (p == null) {
            Toast.makeText(act, "還沒收到影格，存不了（看診斷文字）", Toast.LENGTH_LONG).show(); return
        }
        p.requestSaveRaw { msg ->
            main.post {
                try { Toast.makeText(act, msg, Toast.LENGTH_LONG).show() } catch (_: Throwable) {}
                // 同時存原廠畫面（自動對位對不準時，拿這兩張在電腦上重現）
                FactoryScreen.capture(act, maxW = 1920, hide = ownViews()) { scr ->
                    if (scr == null) { log("存原廠畫面失敗：" + FactoryScreen.lastError); return@capture }
                    cal.post {
                        try {
                            val bmp = android.graphics.Bitmap.createBitmap(scr.argb, scr.w, scr.h, android.graphics.Bitmap.Config.ARGB_8888)
                            val bos = java.io.ByteArrayOutputStream()
                            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
                            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                            MediaSaver.save(act, "screen_$ts.png", "image/png", bos.toByteArray())
                            val meta = org.json.JSONObject().put("view", lastViewKey ?: JSONObject.NULL)
                                .put("sx0", scr.sx0).put("sy0", scr.sy0).put("sw", scr.sw).put("sh", scr.sh)
                                .put("screenW", scr.screenW).put("screenH", scr.screenH)
                            MediaSaver.save(act, "screen_$ts.json", "application/json", meta.toString(2).toByteArray())
                        } catch (_: Throwable) {}
                    }
                }
            }
        }
    }

    /** 沒影格時把診斷文字顯示在俯視圖上，有影格就收掉。 */
    private val statusTick = object : Runnable {
        override fun run() {
            val v = view?.get()
            if (v != null) {
                val idle = SystemClock.elapsedRealtime() - FrameShareHub.lastFrameAt
                v.status = if (idle > 2000) FrameShareHub.status() else null
            }
            main.postDelayed(this, 1000)
        }
    }
}
