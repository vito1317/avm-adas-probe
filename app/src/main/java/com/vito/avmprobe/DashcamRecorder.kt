package com.vito.avmprobe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 行車記錄器：把合成好的畫面（由 [draw] 畫到 Canvas）硬體編碼成 H.264，每 [SEG_MS] 一段 mp4，
 * 同名 .srt 字幕記錄每秒的車速、方向燈、偵測結果等（VLC 這類播放器會自動疊上去）。
 *
 * - 循環錄影：總量超過上限或剩餘空間太少，刪最舊的（locked/ 裡的不刪）。
 * - [lock]：把正在錄的這段和上一段標成鎖定，關檔時搬進 locked/。
 * - 編碼器輸入是 Surface，用 lockHardwareCanvas 畫（GPU，不用自己轉 YUV）。
 * - 全部在自己的執行緒；呼叫端只要在每一幀呼叫 [frame]。
 */
class DashcamRecorder(private val ctx: Context, private val quotaBytes: () -> Long) {

    companion object {
        private const val TAG = "Dashcam"
        const val W = 1280
        const val H = 720
        const val FPS = 15
        private const val BITRATE = 4_000_000
        const val SEG_MS = 60_000L
        private const val MIN_FREE = 1_500L * 1024 * 1024    // 至少留 1.5 GB 給系統
    }

    private val thread = HandlerThread("dashcam").apply { start() }
    private val h = Handler(thread.looper)
    private val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    val dir: File = run {
        val dcim = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "AVM-ADAS")
        if ((dcim.exists() || dcim.mkdirs()) && dcim.canWrite()) dcim
        else File(ctx.getExternalFilesDir(null), "dashcam").apply { mkdirs() }
    }
    private val lockedDir = File(dir, "locked").apply { mkdirs() }

    private var codec: MediaCodec? = null
    private var input: Surface? = null
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var muxStarted = false
    private var segFile: File? = null
    private var segStart = 0L
    private var srt: FileWriter? = null
    private var srtIdx = 0
    private var lastSrtSec = -1L
    private var lastFrameAt = 0L
    private var ptsBase = 0L
    private var pendingRotate = false
    @Volatile private var lockCurrent = false
    private var prevSeg: File? = null
    @Volatile var running = false; private set
    @Volatile var status = "未啟動"; private set
    @Volatile private var busy = false

    fun start() = h.post {
        if (running) return@post
        try { openSegment(); running = true } catch (t: Throwable) { status = "啟動失敗：$t"; Log.e(TAG, "start", t) }
    }

    fun stop() = h.post { closeSegment(); running = false; status = "已停止" }

    /** 鎖住這段（和上一段）：撞擊或使用者按鎖定。 */
    fun lock() { lockCurrent = true; h.post { prevSeg?.let { moveLocked(it) }; prevSeg = null } }

    /**
     * 每一幀呼叫（任何執行緒）。限速到 [FPS]；上一幀還在畫就丟掉這一幀。
     * [draw] 在錄影執行緒上把畫面畫到 W×H 的 Canvas；[subtitle] 是這一刻的字幕文字（每秒取一次）。
     */
    fun frame(draw: (Canvas) -> Unit, subtitle: () -> String) {
        if (!running || busy) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameAt < 1000L / FPS) return
        lastFrameAt = now
        busy = true
        h.post {
            try {
                val s = input ?: return@post
                Crumbs.at(Crumbs.DASH, "dash-lock")
                val c = s.lockHardwareCanvas()
                Crumbs.at(Crumbs.DASH, "dash-draw")
                try { draw(c) } finally { Crumbs.at(Crumbs.DASH, "dash-post"); s.unlockCanvasAndPost(c) }
                Crumbs.at(Crumbs.DASH, "dash-drain")
                drain(false)
                Crumbs.at(Crumbs.DASH, "dash:ok")
                val sec = (now - segStart) / 1000
                if (sec != lastSrtSec) { lastSrtSec = sec; writeSrt(sec, subtitle()) }
                if (now - segStart >= SEG_MS && !pendingRotate) {
                    // 要下一個關鍵影格才能切段
                    pendingRotate = true
                    codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                }
            } catch (t: Throwable) {
                status = "錄影錯誤：$t"; Log.e(TAG, "frame", t)
            } finally { busy = false }
        }
    }

    private fun openSegment() {
        val name = "AVM_" + fmt.format(Date())
        val f = File(dir, "$name.mp4")
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        input = c.createInputSurface()
        c.start()
        codec = c
        muxer = MediaMuxer(f.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        track = -1; muxStarted = false
        segFile = f; segStart = SystemClock.elapsedRealtime(); ptsBase = -1
        srt = FileWriter(File(dir, "$name.srt")); srtIdx = 0; lastSrtSec = -1
        status = "錄影中：${f.name}"
        enforceQuota()
    }

    private fun closeSegment() {
        try {
            codec?.signalEndOfInputStream()
            drain(true)
        } catch (_: Throwable) {}
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        try { input?.release() } catch (_: Throwable) {}
        try { if (muxStarted) muxer?.stop() } catch (_: Throwable) {}
        try { muxer?.release() } catch (_: Throwable) {}
        try { srt?.close() } catch (_: Throwable) {}
        codec = null; input = null; muxer = null; srt = null
        val f = segFile
        segFile = null
        if (f != null) {
            if (lockCurrent) { moveLocked(f); lockCurrent = false; prevSeg = null } else prevSeg = f
        }
    }

    /** 把編碼好的資料寫進 mp4；碰到關鍵影格且該切段了 → 換新的一段。 */
    private fun drain(eos: Boolean) {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = c.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!eos) return else return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer!!.addTrack(c.outputFormat); muxer!!.start(); muxStarted = true
                }
                idx >= 0 -> {
                    val buf = c.getOutputBuffer(idx)
                    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    if (pendingRotate && key && !eos) {
                        // 這個關鍵影格開始屬於下一段：先關舊段（不含這格），再開新段，把這格寫進新段
                        c.releaseOutputBuffer(idx, false)
                        pendingRotate = false
                        closeSegment(); openSegment()
                        return
                    }
                    if (buf != null && info.size > 0 && muxStarted && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        if (ptsBase < 0) ptsBase = info.presentationTimeUs
                        info.presentationTimeUs -= ptsBase
                        muxer!!.writeSampleData(track, buf, info)
                    }
                    c.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun writeSrt(sec: Long, text: String) {
        val w = srt ?: return
        fun tc(s: Long) = "%02d:%02d:%02d,000".format(s / 3600, (s / 60) % 60, s % 60)
        srtIdx++
        w.write("$srtIdx\n${tc(sec)} --> ${tc(sec + 1)}\n${clock.format(Date())}  $text\n\n")
        w.flush()
    }

    private fun moveLocked(f: File) {
        for (ext in listOf(".mp4", ".srt")) {
            val src = File(f.parentFile, f.nameWithoutExtension + ext)
            if (src.exists()) src.renameTo(File(lockedDir, src.name))
        }
        Log.i(TAG, "鎖定 ${f.name}")
    }

    /** 循環錄影：超過上限或剩餘空間太少就刪最舊的（不刪鎖定的、不刪正在錄的）。 */
    private fun enforceQuota() {
        try {
            val files = (dir.listFiles() ?: return).filter { it.isFile && (it.name.endsWith(".mp4") || it.name.endsWith(".srt")) }
                .filter { it != segFile }.sortedBy { it.name }
            var total = files.sumOf { it.length() } + (lockedDir.listFiles()?.sumOf { it.length() } ?: 0L)
            var free = StatFs(dir.absolutePath).availableBytes
            for (f in files) {
                if (total <= quotaBytes() && free >= MIN_FREE) break
                val len = f.length()
                if (f.delete()) { total -= len; free += len }
            }
        } catch (t: Throwable) { Log.e(TAG, "quota", t) }
    }

    /** 給設定頁看的摘要。 */
    fun summary(): String {
        val files = dir.listFiles()?.filter { it.name.endsWith(".mp4") } ?: emptyList()
        val locked = lockedDir.listFiles()?.filter { it.name.endsWith(".mp4") } ?: emptyList()
        val used = (files + locked).sumOf { it.length() } / (1024 * 1024)
        val free = try { StatFs(dir.absolutePath).availableBytes / (1024 * 1024 * 1024) } catch (_: Throwable) { -1 }
        return "$status\n位置：${dir.absolutePath}\n影片 ${files.size} 段、鎖定 ${locked.size} 段，共 $used MB；剩餘空間 $free GB"
    }
}
