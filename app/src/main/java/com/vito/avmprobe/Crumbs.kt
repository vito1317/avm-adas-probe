package com.vito.avmprobe

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * 閃退足跡：每個執行緒現在跑到哪一段，寫進記憶體映射檔（程式被原生層崩潰整個砍掉也留得住，
 * 系統會把映射的頁面寫回檔案）。實車：一開 8 秒就閃退，沒有 crash／adas_error 檔，不知道是哪一段。
 *
 * 下次啟動時讀上一次的足跡：還沒撐過 [HEALTHY_MS] 就結束、而且某一段「開始了沒結束」→ 就是閃退的地方。
 * 那一段是可以關掉的新功能（[SKIPPABLE]）就自動停用，設定裡顯示是哪一段、可以重新開啟。
 */
object Crumbs {
    const val HEALTHY_MS = 60_000L
    /** 足跡裡標明是哪一版（跟發佈的版號一致）。 */
    const val BUILD = "m-v60"
    private const val SLOT = 96
    private const val SLOTS = 7
    private const val HEAD = 16

    // 執行緒欄位
    const val MAIN = 0; const val FRAME = 1; const val GROUND = 2; const val TTS = 3; const val VEH = 4; const val OTHER = 5; const val DASH = 6

    /** 出事可以自動停用的功能（名稱 → 說明）。 */
    val SKIPPABLE = linkedMapOf(
        "tts" to "語音提示（初始化）", "speak" to "語音提示（念）", "slotbev" to "停車格大範圍俯視圖",
        "aids" to "駕駛／停車輔助", "turnopen" to "轉彎轉進空地", "vshift" to "周圍車輛判斷變道",
        "vyaw" to "前方畫面轉向", "cutin" to "切入偵測", "lead" to "前車燈號", "door" to "車門狀態",
        "evlock" to "事件自動存影片", "parkshot" to "停車位置記錄", "canspeed" to "車機 CAN 車速", "dashcam" to "行車記錄器"
    )

    @Volatile private var buf: MappedByteBuffer? = null
    @Volatile private var off: Set<String> = emptySet()
    @Volatile var report: String = ""; private set
    private var prefsCtx: Context? = null

    /** 啟動時呼叫一次：讀上一次的足跡、決定要停用什麼，再開始記這一次。 */
    @Synchronized
    fun begin(ctx: Context) {
        if (buf != null) return
        try {
            val app = ctx.applicationContext ?: ctx
            prefsCtx = app
            val sp = app.getSharedPreferences("adas_crumbs", Context.MODE_PRIVATE)
            off = sp.getStringSet("off", emptySet())!!.toSet()
            val f = File(app.filesDir, "adas_crumbs.bin")
            val raf = RandomAccessFile(f, "rw")
            raf.setLength((HEAD + SLOT * SLOTS).toLong())
            val b = raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, (HEAD + SLOT * SLOTS).toLong())
            // 上一次
            val healthy = b.get(0).toInt() == 1
            var worstName: String? = null; var worstAt = -1L
            val lines = ArrayList<String>()
            for (s in 0 until SLOTS) {
                val (at, name) = readSlot(b, s)
                if (name.isEmpty()) continue
                lines.add("${slotName(s)}：$name")
                if (!name.endsWith(":ok") && at > worstAt) { worstAt = at; worstName = name }
            }
            if (!healthy && worstName != null) {
                val key = worstName.substringBefore('|').let { if (it.startsWith("dash")) "dashcam" else it }
                val desc = SKIPPABLE[key]
                val disabled = desc != null && key !in off
                if (disabled) { off = off + key; sp.edit().putStringSet("off", off).commit() }
                val ts = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.US).format(java.util.Date())
                report = "【$ts 記錄，$BUILD】上次 ADAS 啟動 1 分鐘內就結束，當時正在跑：$worstName" +
                    (if (desc != null) "（$desc）" else "") +
                    (if (disabled) "\n→ 已自動停用「$desc」，看這次還會不會閃退" else "") +
                    "\n各執行緒最後一步：\n" + lines.joinToString("\n")
                sp.edit().putString("lastReport", report).commit()
                Engine360.log(report.lineSequence().first())
            } else report = sp.getString("lastReport", "") ?: ""
            // 這一次：清空
            for (i in 0 until HEAD + SLOT * SLOTS) b.put(i, 0)
            b.putLong(8, SystemClock.elapsedRealtime())
            buf = b
            Engine360.main.postDelayed({ try { buf?.put(0, 1) } catch (_: Throwable) {} }, HEALTHY_MS)
        } catch (t: Throwable) { Engine360.log("足跡記錄不能用：$t") }
    }

    private fun readSlot(b: MappedByteBuffer, s: Int): Pair<Long, String> {
        val o = HEAD + s * SLOT
        val at = b.getLong(o)
        val n = (b.get(o + 8).toInt() and 0xff).coerceAtMost(SLOT - 9)
        val bytes = ByteArray(n); for (i in 0 until n) bytes[i] = b.get(o + 9 + i)
        return at to String(bytes, Charsets.UTF_8)
    }

    private fun slotName(s: Int) = when (s) { MAIN -> "畫面"; FRAME -> "偵測"; GROUND -> "俯視"; TTS -> "語音"; VEH -> "車輛訊號"; DASH -> "行車記錄器"; else -> "其他" }

    /** 這個執行緒現在跑到 [name]。 */
    fun at(slot: Int, name: String) {
        val b = buf ?: return
        try {
            val bytes = name.toByteArray(Charsets.UTF_8)
            val n = minOf(bytes.size, SLOT - 9)
            val o = HEAD + slot * SLOT
            synchronized(b) {
                b.putLong(o, SystemClock.elapsedRealtime())
                for (i in 0 until n) b.put(o + 9 + i, bytes[i])
                b.put(o + 8, n.toByte())
            }
        } catch (_: Throwable) {}
    }

    /** 包住一段：開始寫 name、結束寫 name:ok。 */
    inline fun <T> step(slot: Int, name: String, block: () -> T): T {
        at(slot, name)
        val r = block()
        at(slot, "$name:ok")
        return r
    }

    /** 這個功能被自動停用了。 */
    fun off(key: String): Boolean = key in off

    fun disabledText(): String = off.joinToString("、") { SKIPPABLE[it] ?: it }

    /** 設定裡「重新開啟被停用的功能」。 */
    fun enableAll() {
        off = emptySet()
        try { prefsCtx?.getSharedPreferences("adas_crumbs", Context.MODE_PRIVATE)?.edit()?.remove("off")?.remove("lastReport")?.commit() } catch (_: Throwable) {}
        report = ""
    }
}
