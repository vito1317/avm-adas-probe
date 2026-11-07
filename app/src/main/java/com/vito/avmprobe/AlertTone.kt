package com.vito.avmprobe

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import kotlin.math.PI
import kotlin.math.sin

/**
 * 提示音：自己合成波形，用 AudioTrack 播。
 *
 * 車機的擴大機通常只有媒體在播時才開：以前用 ToneGenerator 走媒體聲道，沒播音樂就沒聲音。
 * 現在跟導航語音一樣：先要求「暫時插播」音訊焦點（車機會打開聲道、正在播的音樂會自動壓低），
 * 聲道預設是導航提示（USAGE_ASSISTANCE_NAVIGATION_GUIDANCE），前面墊一段靜音讓擴大機開起來，播完放掉焦點。
 * 聲道可在設定切換（[AppPrefs.alertChannel]）：0 導航、1 媒體、2 通知、3 鬧鐘。
 */
object AlertTone {
    private const val RATE = 22050
    private const val LEAD_MS = 250        // 前面墊的靜音：等擴大機開
    @Volatile private var busyUntil = 0L

    val CHANNELS = listOf("導航提示", "媒體", "通知", "鬧鐘")

    private fun usage(ch: Int) = when (ch) {
        1 -> AudioAttributes.USAGE_MEDIA
        2 -> AudioAttributes.USAGE_NOTIFICATION
        3 -> AudioAttributes.USAGE_ALARM
        else -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    }

    /** 嗶聲樣式：(頻率 Hz, 長度 ms) 一段一段，頻率 0 = 靜音。 */
    private fun pattern(k: AlertSounder.Kind): List<Pair<Int, Int>> = when (k) {
        AlertSounder.Kind.INFO -> listOf(880 to 120, 0 to 60, 1175 to 160)
        AlertSounder.Kind.LANE -> listOf(988 to 110, 0 to 70, 988 to 110, 0 to 70, 988 to 110)
        AlertSounder.Kind.DANGER -> listOf(1568 to 140, 0 to 50, 1568 to 140, 0 to 50, 1568 to 140, 0 to 50, 1568 to 220)
    }

    fun play(ctx: Context, k: AlertSounder.Kind, channel: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < busyUntil) return
        val pat = pattern(k)
        val totalMs = LEAD_MS + pat.sumOf { it.second } + 80
        busyUntil = now + totalMs
        Thread({
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val attrs = AudioAttributes.Builder().setUsage(usage(channel))
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            var focus: AudioFocusRequest? = null
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(attrs).build()
                    am.requestAudioFocus(focus)
                } else {
                    @Suppress("DEPRECATION")
                    am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                }
                val pcm = synth(pat)
                val track = AudioTrack.Builder().setAudioAttributes(attrs)
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(totalMs.toLong())
                track.stop(); track.release()
            } catch (t: Throwable) {
                Engine360.log("提示音播放失敗：$t")
            } finally {
                try {
                    if (Build.VERSION.SDK_INT >= 26) focus?.let { am.abandonAudioFocusRequest(it) }
                    else @Suppress("DEPRECATION") am.abandonAudioFocus(null)
                } catch (_: Throwable) {}
            }
        }, "alert-tone").start()
    }

    private fun synth(pat: List<Pair<Int, Int>>): ShortArray {
        val lead = RATE * LEAD_MS / 1000
        val n = lead + pat.sumOf { RATE * it.second / 1000 }
        val out = ShortArray(n)
        var i = lead
        for ((f, ms) in pat) {
            val len = RATE * ms / 1000
            for (j in 0 until len) {
                if (f > 0) {
                    // 前後 8 ms 淡入淡出，免得爆音
                    val env = minOf(1.0, j / (RATE * 0.008), (len - j) / (RATE * 0.008))
                    out[i + j] = (sin(2 * PI * f * j / RATE) * env * 0.7 * Short.MAX_VALUE).toInt().toShort()
                }
            }
            i += len
        }
        return out
    }
}
