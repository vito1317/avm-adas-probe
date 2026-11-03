package com.vito.avmprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.concurrent.thread

/**
 * 車機訊號探測：找出方向燈 / 倒車 / CAN 訊號是怎麼送進 Android 的。
 * 1. 認車機平台（FYT、TS…）與廠商系統 App
 * 2. 掃廠商 App 程式碼裡像「廣播名稱」的字串（含 turn / lamp / can / reverse…）
 * 3. 動態註冊這些廣播，監聽 60 秒：使用者打左右方向燈、倒車檔，收到什麼全部記下來
 */
class SignalProbeActivity : AppCompatActivity() {

    companion object {
        private const val LISTEN_MS = 60_000L
        private const val MAX_ACTIONS = 600
        private val KEYWORDS = Regex(
            "turn|lamp|light|signal|blink|flash|indicat|canbus|can_|\\.can\\.|carinfo|car_info|mcu|" +
                "reverse|backcar|back_car|zhuan|steer|angle|radar|door|gear|acc_",
            RegexOption.IGNORE_CASE
        )
        private val ACTION_LIKE = Regex("^[A-Za-z][\\w]*(\\.[\\w]+){2,}$")
        // 常見廠商平台的套件名稱特徵
        private val VENDOR_HINT = Regex(
            "syu|fyt|canbus|microntek|\\.ts\\.|\\.tw\\.|mcu|carsetting|car\\.|vehicle|avm|360|" +
                "zxw|joying|ownice|dasaita|hct|qf|xy|nwd|roadrover|kld|bt|radio",
            RegexOption.IGNORE_CASE
        )
    }

    private lateinit var logView: TextView
    private val log = StringBuilder()
    private val main = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    private val actions = LinkedHashSet<String>()
    private var listenStart = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }
        col.addView(TextView(this).apply {
            text = "車機訊號探測（方向燈／倒車／CAN）"
            textSize = 20f
            setTextColor(Color.parseColor("#008577"))
        })
        col.addView(TextView(this).apply {
            text = "按「開始監聽」後 60 秒內：左轉燈打 5 秒→關，右轉燈打 5 秒→關，" +
                "（安全的話）打倒車檔→回 P。結束後按「存檔」，把檔案傳給開發者。"
            textSize = 14f
            setPadding(0, 8, 0, 8)
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "開始監聽 60 秒"; isAllCaps = false
            setOnClickListener { startListening() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "存檔"; isAllCaps = false
            setOnClickListener { save() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row)
        logView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        col.addView(logView)
        setContentView(ScrollView(this).apply { addView(col) })

        append("掃描中（讀廠商 App，約 10–60 秒）…")
        thread(name = "signal-scan") { scan() }
    }

    private fun append(s: String) = main.post {
        log.append(s).append('\n')
        logView.text = log
    }

    // ---------------- 1+2：平台、廠商 App、候選廣播 ----------------

    private fun scan() {
        val sb = StringBuilder()
        sb.append("== 車機\n")
        sb.append("${Build.MANUFACTURER} / ${Build.MODEL} / board=${Build.BOARD} / hw=${Build.HARDWARE}\n")
        sb.append("display=${Build.DISPLAY}\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")

        sb.append("\n== 系統屬性（篩選）\n")
        try {
            val p = Runtime.getRuntime().exec(arrayOf("getprop"))
            p.inputStream.bufferedReader().readLines()
                .filter { Regex("fyt|syu|\\bts\\.|can|mcu|car|vendor\\.|customer|platform|board|ui_|launcher|avm", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                .take(150).forEach { sb.append(it).append('\n') }
        } catch (t: Throwable) {
            sb.append("getprop 失敗：${t.message}\n")
        }

        val pm = packageManager
        @Suppress("DEPRECATION")
        val apps = try { pm.getInstalledApplications(PackageManager.GET_META_DATA) } catch (_: Throwable) { emptyList() }
        val vendor = apps.filter {
            val sys = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val pkg = it.packageName
            sys && !pkg.startsWith("com.android.") && !pkg.startsWith("com.google.") &&
                pkg != "android" && !pkg.startsWith("androidx.")
        }.sortedBy { it.packageName }
        sb.append("\n== 廠商系統 App（${vendor.size}）\n")
        for (a in vendor) {
            val hint = if (VENDOR_HINT.containsMatchIn(a.packageName)) " ★" else ""
            sb.append("${a.packageName}$hint\n")
        }
        append(sb.toString())

        // 掃程式碼字串：先掃名稱有特徵的，再掃其他廠商 App
        val ordered = vendor.sortedByDescending { VENDOR_HINT.containsMatchIn(it.packageName) }
        var scanned = 0
        var noDex = 0
        val found = LinkedHashMap<String, String>()
        for (a in ordered) {
            if (found.size >= MAX_ACTIONS) break
            val strs = try { dexStrings(a.sourceDir) } catch (_: Throwable) { null }
            if (strs == null) { noDex++; continue }
            scanned++
            for (s in strs) {
                if (s.length in 10..120 && ACTION_LIKE.matches(s) && KEYWORDS.containsMatchIn(s) &&
                    !s.startsWith("android.") && !s.startsWith("java.") && !s.startsWith("kotlin.")) {
                    if (!found.containsKey(s)) found[s] = a.packageName
                }
            }
        }
        // 平台常見的公開廣播也一起聽
        for (s in listOf(
            "android.intent.action.ACC_ON", "android.intent.action.ACC_OFF",
            "android.intent.action.REVERSE", "com.android.action.REVERSE"
        )) found.putIfAbsent(s, "(常見)")
        synchronized(actions) { actions.clear(); actions.addAll(found.keys.take(MAX_ACTIONS)) }

        val out = StringBuilder("\n== 候選廣播（掃了 $scanned 個 App，$noDex 個沒有 dex）：${found.size}\n")
        for ((s, pkg) in found.entries.take(200)) out.append("$s  ← $pkg\n")
        if (found.size > 200) out.append("…（共 ${found.size} 個，存檔裡有完整清單）\n")
        out.append("\n掃描完成，可以按「開始監聽 60 秒」。")
        append(out.toString())
        if (found.size > 200) synchronized(log) {
            fullList = found.entries.joinToString("\n") { "${it.key}  ← ${it.value}" }
        }
    }

    private var fullList: String? = null

    /** 讀 APK 裡所有 classes*.dex 的字串表。 */
    private fun dexStrings(apk: String): List<String>? {
        val out = ArrayList<String>()
        ZipFile(apk).use { zip ->
            val dexes = zip.entries().toList().filter { it.name.matches(Regex("classes\\d*\\.dex")) }
            if (dexes.isEmpty()) return null
            for (e in dexes) {
                val bytes = zip.getInputStream(e).use { it.readBytes() }
                parseDexStrings(bytes, out)
            }
        }
        return out
    }

    private fun parseDexStrings(dex: ByteArray, out: MutableList<String>) {
        val b = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)
        if (dex.size < 0x70) return
        val n = b.getInt(0x38)
        val off = b.getInt(0x3C)
        if (n <= 0 || off <= 0 || off + n * 4 > dex.size) return
        for (i in 0 until n) {
            var p = b.getInt(off + i * 4)
            if (p <= 0 || p >= dex.size) continue
            // uleb128 長度（UTF-16 單位），之後是 MUTF-8、以 0 結尾
            var len = 0
            var shift = 0
            while (p < dex.size) {
                val v = dex[p++].toInt() and 0xFF
                len = len or ((v and 0x7F) shl shift)
                if (v and 0x80 == 0) break
                shift += 7
            }
            if (len > 200) continue
            var end = p
            while (end < dex.size && dex[end] != 0.toByte()) end++
            out.add(String(dex, p, end - p, Charsets.UTF_8))
        }
    }

    // ---------------- 3：監聽 ----------------

    private fun startListening() {
        stopListening()
        val list = synchronized(actions) { actions.toList() }
        if (list.isEmpty()) {
            append("\n（還沒掃完或沒有候選廣播，先等掃描完成）")
            return
        }
        val filter = IntentFilter()
        for (a in list) filter.addAction(a)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val t = (SystemClock.elapsedRealtime() - listenStart) / 1000.0
                val ex = i.extras?.let { b ->
                    b.keySet().joinToString(", ") { k ->
                        @Suppress("DEPRECATION")
                        "$k=${b.get(k)}"
                    }
                } ?: ""
                append("[%6.2fs] %s %s".format(t, i.action, ex))
            }
        }
        listenStart = SystemClock.elapsedRealtime()
        val sticky = try {
            ContextCompat.registerReceiver(this, r, filter, ContextCompat.RECEIVER_EXPORTED)
        } catch (t: Throwable) {
            append("註冊失敗：${t.message}")
            return
        }
        receiver = r
        append("\n== 監聽 ${list.size} 個廣播 60 秒：現在打方向燈 / 倒車")
        sticky?.let { append("（黏著廣播）${it.action} ${it.extras?.keySet()}") }
        main.postDelayed({ stopListening(); append("== 監聽結束") }, LISTEN_MS)
    }

    private fun stopListening() {
        receiver?.let { try { unregisterReceiver(it) } catch (_: Throwable) {} }
        receiver = null
        main.removeCallbacksAndMessages(null)
    }

    private fun save() {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val text = log.toString() + (fullList?.let { "\n\n== 完整候選清單\n$it" } ?: "")
        try {
            val path = MediaSaver.save(this, "signals_$ts.txt", "text/plain", text.toByteArray())
            append("\n已存：$path")
        } catch (t: Throwable) {
            append("\n存檔失敗：${t.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
    }
}
