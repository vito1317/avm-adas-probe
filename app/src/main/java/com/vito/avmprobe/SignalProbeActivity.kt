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
        private const val UI_MS = 250L          // 畫面最多這麼頻繁更新一次
        private const val UI_TAIL = 8000        // 畫面只顯示末端這麼多字
        private const val FLUSH_MS = 1000L      // 邊收邊寫的落盤間隔
        private const val PER_ACTION_MAX = 25   // 同一個 action 最多詳記幾筆，之後只計數
        private const val BATCH = 40            // 一次只掛這麼多 action（重啟時才知道是哪一批）
        private const val MAX_ACTIONS = 600
        private val KEYWORDS = Regex(
            "turn|lamp|light|signal|blink|flash|indicat|canbus|can_|\\.can\\.|carinfo|car_info|mcu|" +
                "reverse|backcar|back_car|zhuan|steer|angle|wheel|track|guide|radar|pdc|park|ultrason|" +
                "sensor|distance|obstacle|leida|door|gear|speed|acc_",
            RegexOption.IGNORE_CASE
        )
        private val ACTION_LIKE = Regex("^[A-Za-z][\\w]*(\\.[\\w]+){2,}$")
        // 除了環景 App，這些也要匯出回來分析（車身訊號服務、行駛中限制的白名單都在裡面）
        private val EXPORT_PKGS = setOf("com.ts.MainUI", "com.ts.tsspeechlib", "com.ts.MainUI2")
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
    private var uiPending = false
    private var liveOut: java.io.BufferedWriter? = null
    private var liveFile: java.io.File? = null
    private var lastFlush = 0L
    private val hitCount = HashMap<String, Int>()
    private var listenBatch = 0

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
            text = "監聽一次只掛一批（40 個），按一次聽一批；重啟過也沒關係，" +
                "紀錄是邊收邊寫進 Android/data/com.vito.avmprobe/files/signals/ 的，重開機後還在。\n\n" +
                "60 秒內：左轉燈打 5 秒→關，右轉燈打 5 秒→關，（安全、停好的話）打倒車檔→" +
                "方向盤左右轉到底各一次→請人站在車尾 1 m 內讓倒車雷達響幾秒（前雷達也一樣，在車頭前）→" +
                "回 P→按一下排檔旁的 PDC 按鈕。能開一小段再停下來最好（才看得到車速）。" +
                "全景360 跳出來沒關係，60 秒後回這頁按「存檔」。"
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
        val prefs = AppPrefs(this)
        col.addView(Button(this).apply {
            fun label() = "車機服務（方向盤／雷達）：" +
                if (prefs.headUnitBind) "已啟用 ⚠ 可能讓車機重啟" else "關閉（安全）"
            text = label(); isAllCaps = false
            setOnClickListener {
                prefs.headUnitBind = !prefs.headUnitBind
                text = label()
                (application as? AvmApp)?.attach(prefs.headUnitBind)
                append(
                    if (prefs.headUnitBind)
                        "\n⚠ 已啟用車機服務。方向盤角度與雷達只有這條路拿得到，但：\n" +
                            "  - 介面編號是從別的 App 還原的，車機上的版本若不同會呼叫到別的方法\n" +
                            "  - 實測出現過整台車機重啟\n" +
                            "  請停好車再測。連續失敗 5 次會自動停止並解綁。"
                    else "\n已關閉車機服務，只剩廣播（倒車／車速／方向燈聯動），不會再碰系統服務。"
                )
            }
        })
        col.addView(Button(this).apply {
            text = "匯出環景校正檔（calibinfo / lens / sensor）"; isAllCaps = false
            setOnClickListener { exportCalib() }
        })
        col.addView(Button(this).apply {
            text = "查上次重啟的原因"; isAllCaps = false
            setOnClickListener { bootDiag() }
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

    /**
     * 記一行。**同時寫進檔案**（見 [liveFile]）——監聽中車機重啟過，記憶體裡的東西全沒了，
     * 所以不能等使用者按存檔。
     *
     * 畫面更新是**批次**的：以前每來一筆就 `logView.text = log`，等於把整份字串重畫一次。
     * 車機的 CAN 廣播可能每秒好幾十筆，那是 O(n²) 的主執行緒工作，卡住就會被看門狗重啟 ——
     * 這很可能就是「監聽會讓車機重啟」的原因。現在最多每 [UI_MS] 更新一次，而且只顯示末端。
     */
    private fun append(s: String) {
        synchronized(log) {
            log.append(s).append('\n')
            liveWrite(s)
        }
        scheduleUi()
    }

    private fun scheduleUi() {
        if (uiPending) return
        uiPending = true
        main.postDelayed({
            uiPending = false
            val t = synchronized(log) {
                if (log.length <= UI_TAIL) log.toString()
                else "…（前面省略，完整內容在存檔裡）\n" + log.substring(log.length - UI_TAIL)
            }
            logView.text = t
        }, UI_MS)
    }

    /** 監聽期間邊收邊寫的檔案；重啟也留得住。 */
    private fun liveOpen(tag: String) {
        liveClose()
        try {
            val dir = java.io.File(getExternalFilesDir(null), "signals")
            dir.mkdirs()
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val f = java.io.File(dir, "live_${'$'}{tag}_${'$'}ts.txt")
            liveFile = f
            liveOut = java.io.BufferedWriter(java.io.FileWriter(f, true))
            append("（邊收邊寫：${'$'}{f.absolutePath}）")
        } catch (t: Throwable) {
            liveOut = null
            append("（邊收邊寫開檔失敗：${'$'}{t.message}）")
        }
    }

    private fun liveWrite(s: String) {
        val o = liveOut ?: return
        try {
            o.write(s); o.write("\n")
            // 每 [FLUSH_MS] 真的落盤一次：重啟時最多只掉這段時間的資料
            val now = SystemClock.elapsedRealtime()
            if (now - lastFlush >= FLUSH_MS) { o.flush(); lastFlush = now }
        } catch (_: Throwable) {}
    }

    private fun liveClose() {
        try { liveOut?.flush(); liveOut?.close() } catch (_: Throwable) {}
        liveOut = null
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
        // 車機主程式也一起匯出：車身訊號的 AIDL 服務在它裡面（能不能 bind 要看它的 manifest），
        // 「行駛中禁止觀看」的白名單多半也寫在那裡。
        val wanted = vendor.filter { isAvm(it) || it.packageName in EXPORT_PKGS }
        synchronized(avmApps) { avmApps.clear(); avmApps.addAll(wanted) }
        val avm = vendor.filter(isAvm)
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
        // 分批：一次全掛 170 個 action，萬一還是會重啟就不知道是哪一個害的。
        // 每按一次只掛一批，重啟之後看 live 檔的開頭就知道卡在第幾批。
        val batch = listenBatch
        val from = batch * BATCH
        if (from >= list.size) {
            listenBatch = 0
            append("\n（所有批次都聽過了，從第 1 批重來）")
            return
        }
        val sub = list.subList(from, minOf(from + BATCH, list.size))
        listenBatch = batch + 1
        liveOpen("b${'$'}{batch + 1}")
        append("\n== 第 ${'$'}{batch + 1} / ${'$'}{(list.size + BATCH - 1) / BATCH} 批（${'$'}{sub.size} 個 action）")
        val filter = IntentFilter()
        for (a in sub) filter.addAction(a)
        hitCount.clear()
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val a = i.action ?: return
                // 同一個 action 只詳記前 PER_ACTION_MAX 筆：車機的 CAN 廣播一秒可能好幾十筆，
                // 全部記下來會把真正有用的東西淹掉，也是把主執行緒卡死的主因之一
                val n = (hitCount[a] ?: 0) + 1
                hitCount[a] = n
                if (n > PER_ACTION_MAX) return
                val t = (SystemClock.elapsedRealtime() - listenStart) / 1000.0
                val ex = try {
                    i.extras?.let { b ->
                        b.keySet().joinToString(", ") { k ->
                            @Suppress("DEPRECATION")
                            "$k=${b.get(k)}"
                        }
                    } ?: ""
                } catch (t2: Throwable) { "(extras 讀取失敗 ${'$'}{t2.javaClass.simpleName})" }
                append("[%6.2fs] %s %s%s".format(t, a, ex, if (n == PER_ACTION_MAX) "  ←之後這個只計數" else ""))
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
        propsBefore = readProps()
        append("== 監聽 ${'$'}{sub.size} 個廣播 60 秒：現在打方向燈 / 倒車 / 轉方向盤 / 開一小段再停")
        sticky?.let { append("（黏著廣播）${it.action} ${it.extras?.keySet()}") }
        main.postDelayed({ stopListening(); append("== 監聽結束") }, LISTEN_MS)
    }

    private fun stopListening() {
        propsRunning = false
        val had = receiver != null
        receiver?.let { try { unregisterReceiver(it) } catch (_: Throwable) {} }
        receiver = null
        main.removeCallbacksAndMessages(null)
        uiPending = false
        if (had) {
            // 系統屬性只在頭尾各讀一次就好。以前是每 500 ms fork 一次 getprop，
            // 60 秒就是 120 次，在車機上太重了（而且那正好是會重啟的那段時間）。
            val after = readProps()
            val before = propsBefore
            if (before != null) {
                var n = 0
                for ((k, v) in after) {
                    val old = before[k]
                    if (old == v) continue
                    if (++n > 60) { append("…（屬性變動太多，只列前 60 個）"); break }
                    append("prop %s: %s → %s".format(k, old ?: "(無)", v))
                }
                if (n == 0) append("（系統屬性沒有變化）")
            }
            propsBefore = null
            val hits = synchronized(log) { hitCount.toList() }
            if (hits.isNotEmpty()) {
                append("\n== 各 action 收到幾筆")
                for ((a, c2) in hits.sortedByDescending { it.second }) append("  %5d  %s".format(c2, a))
            } else {
                append("\n（整段 60 秒一筆廣播都沒收到）")
            }
        }
        liveClose()
    }

    private var propsBefore: Map<String, String>? = null

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
        // 全景360 依平台決定校正檔位置（反編譯 LuaParamsConstant 的 switch）。
        // 這台是 alps spm8667 → 平台 8（MTK8667）→ /data/flyconfig/，但其他平台的也一起試，
        // 讀不到至少能從「存在但沒權限」分辨出是路徑錯還是權限不足。
        val candidates = listOf(
            "/data/flyconfig/calibinfo.lua",        // MTK8667（這台最可能）
            "/avm/calibinfo.lua",                   // AutoChips
            "/mnt/avm/calibinfo.lua",
            "/custom/calibinfo.lua",
            "/sttoem/avm/calibinfo.lua",            // MTK8666
            "/data/bvparam/calibinfo.lua",          // Allwinner
            "/cache/config/calibinfo.lua",          // HZX
            "/lsecfs/calibinfo.lua",                // FYT 7862
            "/data/zq_factory/calibinfo.lua",
            "/sdcard/.Baios360/calibinfo.lua",      // Qualcomm XY
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

    /**
     * 車機重啟後找原因。重啟不是我們這邊看得到的事，但系統會留下痕跡：
     * 開機原因屬性區分「正常開機 / 核心panic / 看門狗 / 掉電」，pstore 則留著上次當掉前的核心訊息。
     * 掉電重啟代表是電源或負載問題，跟我們呼叫哪個服務無關。
     */
    private fun bootDiag() {
        append("\n== 上次重啟的原因")
        thread(name = "boot-diag") {
            for (k in listOf("sys.boot.reason", "sys.boot.reason.last", "ro.boot.bootreason",
                    "ro.boot.boot_reason", "persist.sys.boot.reason", "gsm.version.baseband")) {
                append("  $k = ${getProp(k)}")
            }
            // 上次當掉前的核心訊息（有 panic / watchdog 才會有東西）
            for (p in listOf("/sys/fs/pstore/console-ramoops-0", "/sys/fs/pstore/console-ramoops",
                    "/proc/last_kmsg", "/sys/fs/pstore/dmesg-ramoops-0")) {
                val f = java.io.File(p)
                when {
                    !f.exists() -> append("  －  $p（不存在）")
                    !f.canRead() -> append("  ×  $p（存在但讀不到，需要 root）")
                    else -> {
                        val txt = runCatching { f.readText() }.getOrNull() ?: ""
                        append("  ✓  $p  ${txt.length} 字元")
                        // 只留關鍵字附近，整份太大
                        val hit = txt.lineSequence().filter {
                            Regex("panic|watchdog|WDT|oops|Unable to handle|reboot|reset|low batt|undervolt",
                                RegexOption.IGNORE_CASE).containsMatchIn(it)
                        }.take(40).toList()
                        if (hit.isEmpty()) append("    （沒有 panic / watchdog 關鍵字）")
                        else hit.forEach { append("    $it") }
                        runCatching {
                            append("    已存：" + MediaSaver.save(this, "lastkmsg_${f.name}.txt", "text/plain", txt.toByteArray()))
                        }
                    }
                }
            }
            append("  做完了。如果看到 panic / watchdog，是軟體把系統打掛；" +
                "如果是 power / undervolt 之類，就是電源或負載問題，跟呼叫哪個服務無關。")
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
