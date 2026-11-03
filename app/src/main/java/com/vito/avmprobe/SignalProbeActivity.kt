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
                "reverse|backcar|back_car|zhuan|steer|angle|wheel|track|guide|radar|pdc|park|ultrason|" +
                "sensor|distance|obstacle|leida|door|gear|speed|acc_",
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
                "（安全、停好的話）打倒車檔→方向盤左右轉到底各一次→請人站在車尾 1 m 內讓倒車雷達響幾秒" +
                "（前雷達也一樣，在車頭前）→回 P→按一下排檔旁的 PDC 按鈕，車頭前 1 m 內有東西讓前雷達響。" +
                "全景360 跳出來沒關係，" +
                "60 秒後回到這頁按「存檔」，把檔案傳給開發者。"
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
        row.addView(Button(this).apply {
            text = "匯出環景 App"; isAllCaps = false
            setOnClickListener { exportAvm() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row)
        col.addView(Button(this).apply {
            text = "車身訊號 20 秒（方向燈／倒車／方向盤／雷達）"; isAllCaps = false
            setOnClickListener { watchSignals() }
        })
        col.addView(Button(this).apply {
            text = "匯出環景校正檔（calibinfo / lens / sensor）"; isAllCaps = false
            setOnClickListener { exportCalib() }
        })
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

    /**
     * 直接看 HeadUnitSignalAdapter 的現況：AIDL 有沒有 bind 上、收到哪些廣播、
     * 以及 20 秒內讀到的原始值（方向盤 eps、雷達等級、IsCameraMode）。
     * 原始值才是關鍵：正負號、索引順序、語意都要靠實車數值才能定下來。
     */
    private fun watchSignals() {
        val ad = AvmApp.headUnit
        if (ad == null) { append("\n== 車身訊號：adapter 沒啟動（AvmApp 沒跑到？）"); return }
        append(
            "\n== 車身訊號 20 秒開始。請依序做：左燈 3 秒 → 右燈 3 秒 → 雙閃 3 秒 →" +
                "（停好）排 R → 方向盤左打到底 → 右打到底 → 回正 → 讓車尾 1 m 內有東西"
        )
        thread(name = "signal-watch") {
            val t0 = SystemClock.elapsedRealtime()
            var last = ""
            while (SystemClock.elapsedRealtime() - t0 < 20_000L) {
                val st = ad.status()
                val s = VehicleBus.signals.snapshot()
                val line = buildString {
                    append("bind=").append(if (st.bound) "OK" else "NO")
                    st.bindError?.let { append('(').append(it).append(')') }
                    append(" poll=").append(st.polls).append('/').append(st.pollErrors)
                    append(" | turn=").append(s.turn ?: '-')
                    append(" gear=").append(s.gear ?: '-')
                    append(" steer=").append(s.steerDeg?.let { "%.0f".format(it) } ?: "-")
                    append(" spd=").append(s.speedKmh?.let { "%.0f".format(it) } ?: "-")
                    append(" eps=").append(st.lastEps ?: "-")
                    append(" cam=").append(st.lastCameraMode ?: "-")
                    append("\n  fr=").append(st.lastFrRadar ?: "-")
                    append("\n  lr=").append(st.lastLrRadar ?: "-")
                    append("\n  pdcF=").append(s.pdcFront?.joinToString { fmtD(it) } ?: "-")
                    append(" pdcR=").append(s.pdcRear?.joinToString { fmtD(it) } ?: "-")
                }
                if (line != last) { append(line); last = line }
                Thread.sleep(200)
            }
            val st = ad.status()
            append("\n== 車身訊號結束。收到的廣播（${st.actionsSeen.size}）：")
            if (st.actionsSeen.isEmpty()) append("  （一個都沒收到）")
            else st.actionsSeen.forEach { append("  $it") }
            append("按「存檔」把結果傳回來。")
        }
    }

    private fun fmtD(d: Float): String = when {
        d.isNaN() -> "故障"
        d.isInfinite() || d >= VehicleSignals.PDC_CLEAR -> "空"
        else -> "%.2f".format(d)
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
        // 環景 App（全景360）可能是一般安裝的，不一定是系統 App：用名稱認
        val avmLabel = Regex("360|全景|環景|环景|avm|panoram|倒車|倒车", RegexOption.IGNORE_CASE)
        fun label(a: ApplicationInfo) = try { pm.getApplicationLabel(a).toString() } catch (_: Throwable) { "" }
        val vendor = apps.filter {
            val sys = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val pkg = it.packageName
            val vendorSys = sys && !pkg.startsWith("com.android.") && !pkg.startsWith("com.google.") &&
                pkg != "android" && !pkg.startsWith("androidx.")
            vendorSys || (pkg != packageName && (avmLabel.containsMatchIn(label(it)) || VENDOR_HINT.containsMatchIn(pkg)))
        }.sortedBy { it.packageName }
        val isAvm = { a: ApplicationInfo -> avmLabel.containsMatchIn(label(a)) || avmLabel.containsMatchIn(a.packageName) }
        sb.append("\n== 廠商 / 環景 App（${vendor.size}）\n")
        for (a in vendor) {
            val mark = when {
                isAvm(a) -> " ◎環景"
                VENDOR_HINT.containsMatchIn(a.packageName) -> " ★"
                else -> ""
            }
            val sys = if ((a.flags and ApplicationInfo.FLAG_SYSTEM) != 0) "" else "（使用者安裝）"
            sb.append("${a.packageName}  「${label(a)}」$sys$mark\n")
        }
        val avm = vendor.filter(isAvm)
        synchronized(avmApps) { avmApps.clear(); avmApps.addAll(avm) }
        for (a in avm) {
            // 用平台金鑰簽的 App 能用系統等級權限，那些管道我們就用不了
            val platform = try {
                pm.checkSignatures(a.packageName, "android") == PackageManager.SIGNATURE_MATCH
            } catch (_: Throwable) { false }
            val ver = try { pm.getPackageInfo(a.packageName, 0).versionName } catch (_: Throwable) { "?" }
            val size = (listOf(a.sourceDir) + (a.splitSourceDirs?.toList() ?: emptyList()))
                .sumOf { java.io.File(it).length() } / 1024 / 1024
            sb.setLength(0)
            sb.append("◎ 環景 App：${a.packageName} v$ver  ${size} MB  平台簽名=${if (platform) "是（系統等級）" else "否（一般 App）"}\n")
            sb.append("   ${a.sourceDir}\n")
            append(sb.toString())
        }

        // 掃程式碼字串：環景 App 最優先（它自己就在讀方向燈/轉角），再來是名稱有特徵的
        val ordered = vendor.sortedByDescending {
            (if (isAvm(it)) 2 else 0) + (if (VENDOR_HINT.containsMatchIn(it.packageName)) 1 else 0)
        }
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
        append(out.toString())
        if (found.size > 200) synchronized(log) {
            fullList = found.entries.joinToString("\n") { "${it.key}  ← ${it.value}" }
        }
        probeServices(vendor)
        append("\n掃描完成，可以按「開始監聽 60 秒」。")
    }

    /**
     * 廠商的 CAN / MCU 服務（例如 FYT 的 com.syu.ms）：試著綁定，記下 binder 介面名稱。
     * 方向盤轉角這類資料通常只從這種服務拿得到；有了介面名稱才查得到怎麼呼叫。
     */
    private fun probeServices(vendor: List<ApplicationInfo>) {
        val hint = Regex("can|toolkit|mcu|carinfo|canbus|syu|\\.ms\\.|vehicle|car", RegexOption.IGNORE_CASE)
        val sb = StringBuilder("\n== 廠商服務（名稱含 can/mcu/toolkit/car）\n")
        val targets = ArrayList<android.content.ComponentName>()
        for (a in vendor) {
            val svcs = try {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(a.packageName, PackageManager.GET_SERVICES).services
            } catch (_: Throwable) { null } ?: continue
            for (s in svcs) {
                if (!hint.containsMatchIn(s.name) && !hint.containsMatchIn(a.packageName)) continue
                sb.append("${s.name}  exported=${s.exported} perm=${s.permission ?: "-"}\n")
                if (s.exported && s.permission == null && targets.size < 12) {
                    targets.add(android.content.ComponentName(a.packageName, s.name))
                }
            }
        }
        append(sb.toString())
        main.post {
            for (cn in targets) {
                val conn = object : android.content.ServiceConnection {
                    override fun onServiceConnected(name: android.content.ComponentName, b: android.os.IBinder?) {
                        val desc = try { b?.interfaceDescriptor } catch (t: Throwable) { "讀取失敗：${t.message}" }
                        append("綁定成功 ${name.className} → 介面 ${desc ?: "（空）"}")
                        main.postDelayed({ try { unbindService(this) } catch (_: Throwable) {} }, 1500)
                    }
                    override fun onServiceDisconnected(name: android.content.ComponentName) {}
                }
                val ok = try {
                    bindService(Intent().setComponent(cn), conn, Context.BIND_AUTO_CREATE)
                } catch (t: Throwable) {
                    append("綁定 ${cn.className} 例外：${t.message}")
                    false
                }
                if (!ok) append("無法綁定 ${cn.className}")
            }
        }
    }

    // ---------------- 系統屬性變動（倒車、方向燈有些平台寫在 property 裡） ----------------

    @Volatile private var propsRunning = false

    private fun watchProps() {
        propsRunning = true
        thread(name = "prop-watch") {
            var prev = readProps()
            val changes = HashMap<String, Int>()
            val end = SystemClock.elapsedRealtime() + LISTEN_MS
            while (propsRunning && SystemClock.elapsedRealtime() < end) {
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                val cur = readProps()
                for ((k, v) in cur) {
                    val old = prev[k]
                    if (old == v) continue
                    val n = (changes[k] ?: 0) + 1
                    changes[k] = n
                    if (n <= 20) {   // 一直在變的（計數器之類）只記前 20 次
                        val t = (SystemClock.elapsedRealtime() - listenStart) / 1000.0
                        append("[%6.2fs] prop %s: %s → %s".format(t, k, old ?: "(無)", v))
                    }
                }
                prev = cur
            }
        }
    }

    private fun readProps(): Map<String, String> {
        val m = HashMap<String, String>()
        try {
            val p = Runtime.getRuntime().exec(arrayOf("getprop"))
            for (line in p.inputStream.bufferedReader().readLines()) {
                val i = line.indexOf("]: [")
                if (line.startsWith("[") && i > 0 && line.endsWith("]")) {
                    m[line.substring(1, i)] = line.substring(i + 4, line.length - 1)
                }
            }
        } catch (_: Throwable) {}
        return m
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
        watchProps()
        append("\n== 監聽 ${list.size} 個廣播＋系統屬性 60 秒：現在打方向燈 / 倒車 / 轉方向盤")
        sticky?.let { append("（黏著廣播）${it.action} ${it.extras?.keySet()}") }
        main.postDelayed({ stopListening(); append("== 監聽結束") }, LISTEN_MS)
    }

    private fun stopListening() {
        propsRunning = false
        receiver?.let { try { unregisterReceiver(it) } catch (_: Throwable) {} }
        receiver = null
        main.removeCallbacksAndMessages(null)
    }

    private val avmApps = ArrayList<ApplicationInfo>()

    /** 把環景 App 的 APK（含分割檔）複製到「下載/avm-adas」，給開發者分析它怎麼讀方向燈/轉角/校正。 */
    private fun exportAvm() {
        val list = synchronized(avmApps) { avmApps.toList() }
        if (list.isEmpty()) {
            append("\n（還沒掃完，或沒找到環景 App）")
            return
        }
        append("\n匯出中…")
        thread(name = "apk-export") {
            for (a in list) {
                val files = listOf(a.sourceDir) + (a.splitSourceDirs?.toList() ?: emptyList())
                for ((i, path) in files.withIndex()) {
                    val name = a.packageName + if (i == 0) ".apk" else "-split$i.apk"
                    try {
                        val out = MediaSaver.saveFile(this, name, "application/vnd.android.package-archive", java.io.File(path))
                        append("已匯出：$out")
                    } catch (t: Throwable) {
                        append("匯出 $name 失敗：${t.message}")
                    }
                }
            }
        }
    }

    /**
     * 全景360 的校正結果是純文字 Lua（calibinfo.lua），鏡頭畸變表在 lens.lua。
     * 路徑依平台而定（見 docs/head-unit-signals.md），所以候選全試一遍，讀得到就匯出。
     * /sdcard 下的 .Baios360/birdview/res 一般第三方讀得到；/cache、/lsecfs 多半要 root。
     */
    private fun exportCalib() {
        val romRes = "/storage/emulated/0/.Baios360/birdview/res"
        val candidates = listOf(
            "/cache/config/calibinfo.lua",
            "/lsecfs/calibinfo.lua",
            "/data/zq_factory/calibinfo.lua",
            "/storage/emulated/0/.Baios360/birdview/cali/calibinfo.lua",
            "$romRes/lens.lua",
            "$romRes/sensor.lua",
            "$romRes/cartype.lua",
            "$romRes/camera_module.lua",
        )
        append("\n== 匯出環景校正檔")
        thread(name = "calib-export") {
            var got = 0
            for (p in candidates) {
                val f = java.io.File(p)
                when {
                    !f.exists() -> append("  －  $p（不存在）")
                    !f.canRead() -> append("  ×  $p（存在但讀不到，需要 root）")
                    else -> try {
                        val out = MediaSaver.saveFile(this, f.name, "text/plain", f)
                        append("  ✓  $p  ${f.length()} bytes → $out")
                        got++
                    } catch (t: Throwable) {
                        append("  ×  $p 匯出失敗：${t.message}")
                    }
                }
            }
            // 平台判定用的屬性：決定 calibinfo.lua 到底在哪個目錄
            append("  平台屬性：")
            for (k in listOf("persist.vendor.reversing.type", "ro.fyt.platform", "car.hw_name",
                    "persist.xy.platform", "ro.forfan.avm.chiptype", "persist.avm.type")) {
                append("    $k = ${getProp(k)}")
            }
            // 列目錄，路徑猜錯時才知道該往哪找
            for (d in listOf(romRes, "/storage/emulated/0/.Baios360", "/storage/emulated/0/.Baios360/birdview")) {
                val dir = java.io.File(d)
                val ls = runCatching { dir.list()?.joinToString(" ") }.getOrNull()
                append("  目錄 $d → ${ls ?: if (dir.exists()) "（讀不到）" else "（不存在）"}")
            }
            append(if (got > 0) "  匯出 $got 個檔，請一起傳回來。" else "  一個都讀不到——把上面的目錄列表傳回來，我再找。")
        }
    }

    private fun getProp(key: String): String = try {
        Runtime.getRuntime().exec(arrayOf("getprop", key)).inputStream
            .bufferedReader().readText().trim().ifEmpty { "(空)" }
    } catch (t: Throwable) { "讀取失敗：${t.message}" }

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
