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
        /** 通知列「關閉疊圖」與 App 關掉 B 模式時用：停掉服務、收掉懸浮窗。 */
        const val ACTION_STOP = "com.vito.avmprobe.OVERLAY_STOP"
        /** 影格停了這麼久（離開 360）→ 收起面板；影格回來再出現。 */
        private const val HIDE_AFTER_IDLE_MS = 5000L
        /** 一直沒收到影格時，診斷面板最多顯示這麼久就收起。 */
        private const val DIAG_MAX_MS = 60_000L
        private const val CH_ID = "avm_overlay"
        private const val NOTI_ID = 42
    }

    // Binder：原廠 process 來拿 Surface。只回傳 Surface，不接受任何指令，不碰原廠的東西。
    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == TRANSACT_GET_SURFACE) {
                val s = FrameShareHub.surface()
                FrameShareHub.log("[360] 經由 Binder 拿 Surface")
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

    // ── frames 執行緒 ──
    // 影格在 FrameShareHub 的執行緒上進來；整條偵測管線也跑在那條執行緒
    private val cam = FrameShareHub.handler
    private val diag = Handler(android.os.Looper.getMainLooper())
    private var startedAt = 0L
    /** 使用者按了關閉：面板不再出現、偵測暫停，直到下次進 360 由掛鉤重新叫起。 */
    @Volatile private var closed = false
    private val diagTick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val idle = now - FrameShareHub.lastFrameAt
            val hadFrames = FrameShareHub.lastFrameAt != 0L
            // 離開 360（影格停了）就收起面板；還沒收到過影格時先顯示診斷，最多 DIAG_MAX_MS。
            // 只收面板、不停服務：原廠相機 session 可能還掛著我們的 Surface，process 要留著。
            val wantPanel = if (hadFrames) idle < HIDE_AFTER_IDLE_MS else now - startedAt < DIAG_MAX_MS
            if (wantPanel && !closed) showPanel() else hidePanel()
            if (wantPanel && !closed && idle > 2000) panel?.setWait(FrameShareHub.status())
            diag.postDelayed(this, 1000)
        }
    }
    private var pipeline: AdasPipeline? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTI_ID, buildNotification())

        FrameShareHub.onStop = { diag.post { closeByUser() } }
        FrameShareHub.listener = { img ->
            if (!closed) try {
                val p = pipeline ?: AdasPipeline(this, prefs) { j -> panel?.push(j.toString()) }.also { pipeline = it }
                p.onFrame(img)
            } catch (t: Throwable) { Log.e(TAG, "frame 失敗：${t.message}", t) }
        }
        FrameShareHub.log("OverlayService 啟動")
        startedAt = SystemClock.elapsedRealtime()
        showPanel()
        diag.post(diagTick)
        Log.i(TAG, "OverlayService 起來了，等原廠相機把影格餵進我們的 Surface")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            closeByUser()
            return START_NOT_STICKY
        }
        // 重新叫起（又進 360）→ 解除關閉、重新給診斷時間
        if (closed) startForeground(NOTI_ID, buildNotification())
        closed = false
        startedAt = SystemClock.elapsedRealtime()
        return START_NOT_STICKY   // 被關掉就不要自己復活，等下次進 360 再由掛鉤叫起
    }

    /**
     * 關閉疊圖。原廠 360 綁著這個服務，stopSelf 不一定真的停得掉，所以自己把能收的都收掉：
     * 面板、前景通知、偵測。ImageReader 留著（原廠相機 session 可能還掛著它的 Surface）。
     */
    private fun closeByUser() {
        if (closed) return
        closed = true
        FrameShareHub.log("使用者關閉疊圖")
        hidePanel()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
        stopSelf()
    }

    private fun hidePanel() {
        panel?.hide(); panel = null
    }

    /**
     * 3D 面板只佔畫面的一側（沿用「3D 面板位置」設定，預設右半），另一側留給原廠 bowl。
     *
     * 不能開全螢幕：Bev3DPanel 是不透明的（OPAQUE、深色背景）而且吃觸控，
     * 全螢幕會把原廠 bowl 整個蓋掉、原廠的按鈕也按不到 —— v0.5.0 就是這樣。
     * 也不做「透明疊在 bowl 上」：我們的 3D 是獨立重建，跟原廠 bowl 的投影對不齊，
     * 疊上去反而是兩套對不起來的畫面。並排才是兩個都看得清楚。
     */
    private fun showPanel() {
        if (panel != null) return
        val dm = resources.displayMetrics
        val r = prefs.panelRect() ?: android.graphics.RectF(0.5f, 0f, 1f, 1f)   // 「關閉」時也給右半
        val px = Rect(
            (r.left * dm.widthPixels).toInt(), (r.top * dm.heightPixels).toInt(),
            (r.right * dm.widthPixels).toInt(), (r.bottom * dm.heightPixels).toInt()
        )
        panel = Bev3DPanel(this, windowManager, onSaveCapture = {}, onClose = { closeByUser() })
            .also { it.show(px) }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(NotificationChannel(CH_ID, "AVM Overlay", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CH_ID)
            .setContentTitle("AVM 偵測疊圖執行中")
            .setContentText("跟原廠 360 共用影格")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(Notification.Action.Builder(null, "關閉疊圖",
                android.app.PendingIntent.getService(this, 0,
                    Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
                    android.app.PendingIntent.FLAG_IMMUTABLE)).build())
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        panel?.hide(); panel = null
        diag.removeCallbacks(diagTick)
        FrameShareHub.listener = null
        FrameShareHub.onStop = null
        val p = pipeline; pipeline = null
        cam.post { p?.close() }
    }
}
