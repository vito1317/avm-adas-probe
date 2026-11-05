package com.vito.avmprobe

import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * B 模式在我們 process 裡的共用點：
 *  - 持有交給原廠相機的 [ImageReader]。由 [FrameShareProvider] 在原廠建 session 的當下**同步**建立，
 *    不必等 OverlayService 綁定（以前要等綁定，原廠 session 早就建好了，影格永遠進不來）。
 *  - 收影格、轉給 [OverlayService]（服務還沒起來時先丟掉，但照樣計數）。
 *  - 記一份診斷 log（原廠那邊的 FrameTap 也透過 provider 寫進來），顯示在 3D 面板與 App 主頁，
 *    因為車機常常接不上 adb。
 */
object FrameShareHub {
    private const val TAG = "FrameShareHub"
    const val W = 1920
    const val H = 1080
    private const val MAX_LOG = 14

    private val thread by lazy { HandlerThread("frameshare-frames").apply { start() } }
    val handler: Handler by lazy { Handler(thread.looper) }

    private var reader: ImageReader? = null
    @Volatile var listener: ((Image) -> Unit)? = null
    /** OverlayService 掛上來；App 主頁關掉 B 模式時呼叫 [stopOverlay] 收掉疊圖。 */
    @Volatile var onStop: (() -> Unit)? = null

    fun stopOverlay() { onStop?.invoke() }
    @Volatile var frames = 0L; private set
    @Volatile var lastFrameAt = 0L; private set
    @Volatile var surfaceRequests = 0; private set

    private val logLines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** 交給原廠相機的 Surface；第一次呼叫時建立 ImageReader。 */
    @Synchronized
    fun surface(): Surface? {
        surfaceRequests++
        val r = reader ?: try {
            ImageReader.newInstance(W, H, ImageFormat.YUV_420_888, 3).also { rd ->
                rd.setOnImageAvailableListener({ src ->
                    val img = try { src.acquireLatestImage() } catch (_: Throwable) { null }
                        ?: return@setOnImageAvailableListener
                    try {
                        if (frames == 0L) log("收到第一幀影格 ${img.width}x${img.height}")
                        frames++
                        lastFrameAt = SystemClock.elapsedRealtime()
                        listener?.invoke(img)
                    } catch (t: Throwable) {
                        Log.e(TAG, "frame 失敗：${t.message}", t)
                    } finally {
                        img.close()
                    }
                }, handler)
                reader = rd
                log("建立 ImageReader ${W}x${H}")
            }
        } catch (t: Throwable) {
            log("建立 ImageReader 失敗：$t")
            return null
        }
        return r.surface
    }

    @Synchronized
    fun log(msg: String) {
        Log.i(TAG, msg)
        logLines.addLast("${fmt.format(Date())} $msg")
        while (logLines.size > MAX_LOG) logLines.removeFirst()
    }

    /** 給面板與主頁看的診斷文字。第一行是摘要。 */
    @Synchronized
    fun status(): String {
        val now = SystemClock.elapsedRealtime()
        val head = when {
            frames == 0L && surfaceRequests == 0 -> "B 模式：原廠 360 還沒來拿 Surface"
            frames == 0L -> "B 模式：Surface 已交給原廠（$surfaceRequests 次），但還沒收到影格"
            now - lastFrameAt > 2000 -> "B 模式：影格停了 ${(now - lastFrameAt) / 1000} 秒（共 $frames 幀）"
            else -> "B 模式：影格進來中（共 $frames 幀）"
        }
        return if (logLines.isEmpty()) head else head + "\n" + logLines.joinToString("\n")
    }
}
