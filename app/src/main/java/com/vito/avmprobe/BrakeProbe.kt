package com.vito.avmprobe

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 找車機的煞車訊號在哪：車機「行駛中請勿觀看影片」要踩煞車才能退出 → 系統一定讀得到煞車，
 * 但車機的 ITsSpeechCar 介面沒有。這裡每 [STEP_MS] 抓一次所有系統屬性（getprop）與
 * Settings（system／global／secure），共 [DURATION_MS]，使用者在這期間踩放煞車幾次；
 * 結束後列出「來回變了 2 次以上」的項目，煞車就在裡面。
 *
 * 合併版跑在原廠 360（system uid）裡，讀得到全部屬性與設定。
 */
object BrakeProbe {
    const val DURATION_MS = 15_000L
    const val STEP_MS = 300L
    /** 一直在變、跟煞車無關的（時間、電量、網路計數…）。 */
    private val NOISE = listOf("time", "clock", "battery", "rssi", "signal", "net.", "wifi", "dhcp", "uptime",
        "boottime", "ril.", "gsm.", "location", "audio", "volume", "brightness", "screen_off", "cpu", "thermal",
        "debug.", "init.svc", "bootanim", "log.", "vold", "sys.usb", "gps")

    /** 跑完回傳摘要（在背景執行緒呼叫，會阻塞 [DURATION_MS]）。 */
    fun run(ctx: Context, progress: (String) -> Unit): String {
        val hist = HashMap<String, MutableList<Pair<Long, String>>>()
        val t0 = SystemClock.elapsedRealtime()
        var n = 0
        while (SystemClock.elapsedRealtime() - t0 < DURATION_MS) {
            val at = SystemClock.elapsedRealtime() - t0
            for ((k, v) in snapshot(ctx)) {
                val h = hist.getOrPut(k) { ArrayList() }
                if (h.isEmpty() || h.last().second != v) h.add(at to v)
            }
            n++
            if (n % 5 == 0) progress("記錄中… %.0f 秒（請踩放煞車 3 次）".format((DURATION_MS - at) / 1000.0))
            SystemClock.sleep(STEP_MS)
        }
        // 來回變過 2 次以上（第一筆是初值，所以 ≥ 3 筆）的項目
        val cands = hist.filter { (k, h) -> h.size >= 3 && NOISE.none { k.contains(it, ignoreCase = true) } }
            .toList().sortedBy { it.second.size }
        val sb = StringBuilder()
        sb.append("煞車訊號探測（${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}）\n")
        sb.append("每 ${STEP_MS} ms 記一次、共 ${DURATION_MS / 1000} 秒；以下是來回變過 2 次以上的項目：\n\n")
        if (cands.isEmpty()) sb.append("（沒有。可能煞車不在系統屬性／設定裡，或這段時間沒踩到）\n")
        for ((k, h) in cands) {
            sb.append(k).append("\n")
            sb.append("   ").append(h.joinToString("  ") { (t, v) -> "%.1fs=%s".format(t / 1000.0, v.take(24)) }).append("\n")
        }
        val name = "brake_probe_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
        val saved = try { MediaSaver.save(ctx, name, "text/plain", sb.toString().toByteArray()) } catch (t: Throwable) { "存檔失敗：$t" }
        return "找到 ${cands.size} 個會來回變的項目（$saved）：\n" +
            cands.take(8).joinToString("\n") { (k, h) -> "• $k：" + h.joinToString("→") { it.second.take(10) } }
    }

    private fun snapshot(ctx: Context): Map<String, String> {
        val m = HashMap<String, String>(1024)
        // 系統屬性
        try {
            val p = Runtime.getRuntime().exec(arrayOf("getprop"))
            p.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val i = line.indexOf("]: [")
                    if (line.startsWith("[") && i > 0) m["prop:" + line.substring(1, i)] = line.substring(i + 4).removeSuffix("]")
                }
            }
            p.waitFor()
        } catch (_: Throwable) {}
        // Settings
        for ((tag, uri) in listOf("system" to Settings.System.CONTENT_URI, "global" to Settings.Global.CONTENT_URI, "secure" to Settings.Secure.CONTENT_URI)) {
            try {
                ctx.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { c ->
                    while (c.moveToNext()) m["$tag:" + c.getString(0)] = c.getString(1) ?: ""
                }
            } catch (_: Throwable) {}
        }
        return m
    }
}
