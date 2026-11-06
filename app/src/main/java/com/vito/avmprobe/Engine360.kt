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

    /**
     * 原廠切換視角時（注入在 nativesetBvState 前）：[name] 是 BV_state 列舉名稱（BV_LEFT、BV_REAR_3D…）。
     * 標注圖層改用那個視角存的對位。
     */
    @JvmStatic
    fun onViewState(value: Int, name: String) {
        try {
            if (lastViewKey == name) return
            lastViewKey = name
            log("原廠視角：$name（$value）")
            view?.get()?.viewKey = name
        } catch (_: Throwable) {}
    }

    @JvmStatic
    fun attach(act: Activity) {
        try {
            val app = act.applicationContext
            val prefs = AppPrefs(app)
            // 合併版：偏離車道、變道、路邊紅黃線都要車道偵測，沒選過就預設開（標準版 App 預設是關）
            if (!prefs.groundModeSet) prefs.groundMode = AppPrefs.GROUND_VIRTUAL
            crashGuard(act, prefs)
            if (!signalsAttached) {
                signalsAttached = true
                try { VehicleBus.signals.attach(HeadUnitSignalAdapter(app, bindEnabled = prefs.headUnitBind)) }
                catch (t: Throwable) { log("車機訊號接不上：$t") }
            }
            addViews(act, prefs)
            FrameShareHub.listener = { img -> onFrame(app, prefs, img) }
            if (!ticking) { ticking = true; main.post(statusTick) }
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
            profileFor = { k -> prefs.viewProfile(k) }
            viewKey = lastViewKey
            drawRoad = prefs.annotDrawRoad
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
        decor.addView(btn, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = (8 * dp).toInt(); topMargin = (8 * dp).toInt()
        })
    }

    /** 影格執行緒。標注圖層不在畫面上（離開 360 畫面）時不跑偵測，省電也不搶原廠的 CPU。 */
    private fun onFrame(ctx: Context, prefs: AppPrefs, img: Image) {
        val v = view?.get()
        if (v == null || !v.isAttachedToWindow || !prefs.annotEnabled) return
        try {
            val p = pipeline ?: AdasPipeline(ctx, prefs) { j -> view?.get()?.update(j) }.also { pipeline = it }
            p.onFrame(img)
        } catch (t: Throwable) {
            Log.e(TAG, "frame 失敗：${t.message}", t)
        }
    }

    /** 設定裡「存原始影格」：下一幀存檔，完成後跳提示。沒在跑偵測時直接說明。 */
    fun saveRaw(act: Activity) {
        val p = pipeline
        if (p == null) {
            Toast.makeText(act, "還沒收到影格，存不了（看診斷文字）", Toast.LENGTH_LONG).show(); return
        }
        p.requestSaveRaw { msg -> main.post { try { Toast.makeText(act, msg, Toast.LENGTH_LONG).show() } catch (_: Throwable) {} } }
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
