package com.vito.avmprobe

import android.content.Context
import android.media.AudioAttributes
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 語音提示：用車機內建的文字轉語音念警示（「左後來車」「前車起步」「壓線行駛」），不用看畫面就知道是什麼事。
 * 同一句 [REPEAT_MS] 內不重念；括號裡的細節、箭頭不念。走導航提示聲道（跟提示音一樣，沒播音樂也聽得到）。
 */
object Speech {
    private const val REPEAT_MS = 8000L
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val spoken = HashMap<String, Long>()
    @Volatile var enabled = true

    fun init(ctx: Context) {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { st ->
            Crumbs.at(Crumbs.TTS, "tts-ready")
            if (st == TextToSpeech.SUCCESS) {
                val t = tts ?: return@TextToSpeech Crumbs.at(Crumbs.TTS, "tts-ready:ok")
                val r = t.setLanguage(Locale.TAIWAN)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) t.setLanguage(Locale.CHINESE)
                try {
                    t.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                } catch (_: Throwable) {}
                ready = true
                Engine360.log("語音提示可用")
            } else Engine360.log("車機沒有文字轉語音，語音提示不可用")
            Crumbs.at(Crumbs.TTS, "tts-ready:ok")
        }
    }

    /** 念一句（同一句 8 秒內不重念）。 */
    fun say(text: String, urgent: Boolean = false) {
        if (!enabled || !ready) return
        val clean = text.replace(Regex("[◀▶]"), "").replace(Regex("（[^）]*）"), "").replace(Regex("[0-9.]+ ?(m|cm|秒|°|km/h)"), "").trim()
        if (clean.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        synchronized(spoken) {
            val last = spoken[clean]
            // 距離過近這類持續的狀態念一次就好，30 秒內不重複
            val gap = if ("距離" in clean || "跟車" in clean || "停正" in clean || "窄" in clean) 30_000L else REPEAT_MS
            if (last != null && now - last < gap) return
            spoken[clean] = now
            if (spoken.size > 50) spoken.entries.removeIf { now - it.value > 30_000L }
        }
        if (Crumbs.off("speak")) return
        Crumbs.at(Crumbs.TTS, "speak|$clean")
        try { tts?.speak(clean, if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, clean) } catch (_: Throwable) {}
        Crumbs.at(Crumbs.TTS, "speak:ok")
    }
}
