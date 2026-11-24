package com.vito.avmlauncher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 車機桌面：整個畫面是一個 WebView（web/index.html：3D 車子＋中控台＋道路畫面）。
 * 鏡頭畫面只在原廠 360 程式裡（它獨佔相機）；桌面只拿 ADAS 引擎廣播的偵測結果來畫道路畫面。
 * 打 R、打方向燈時原廠 360 會自己跳到前面，桌面從不主動把自己叫回前面。
 */
class MainActivity : Activity(), PhoneLink.Host {
    private lateinit var web: WebView
    private lateinit var car: CarData
    private val main = Handler(Looper.getMainLooper())
    private var resumed = false

    // 桌面開著的時候每 2 秒跟引擎說「我在看」→ 引擎在背景也繼續偵測、送道路資料
    private val alive = object : Runnable {
        override fun run() { if (resumed) { car.engineCmd("alive", ""); main.postDelayed(this, 2000) } }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        car = CarData(this).also { it.start() }
        web = WebView(this).apply {
            setBackgroundColor(0xFF05070B.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            @Suppress("DEPRECATION") settings.allowFileAccessFromFileURLs = true   // 讀 assets 裡的 .glb
            @Suppress("DEPRECATION") settings.allowUniversalAccessFromFileURLs = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            addJavascriptInterface(Bridge(), "Car")
            loadUrl("file:///android_asset/web/index.html")
        }
        setContentView(web)
        hideBars()
        startLocation()
        phone.start("E60", try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Throwable) { "" })
    }

    // ── 手機 App 互聯（[PhoneLink]）──
    private val phone = PhoneLink(this)
    @Volatile private var phoneLoc: Location? = null
    @Volatile private var phoneAt = 0L
    @Volatile private var navGuide: String = ""

    override fun statusJson(): String {
        val o = try { org.json.JSONObject(car.json()) } catch (_: Throwable) { org.json.JSONObject() }
        val adas = o.optJSONObject("adas"); o.remove("adas")
        adas?.optJSONObject("veh")?.let { o.put("veh", it) }
        adas?.optJSONObject("map")?.let { m -> o.put("road", org.json.JSONObject().put("name", m.opt("name")).put("maxspeed", m.opt("maxspeed"))) }
        o.put("nav", try { if (navGuide.isNotEmpty()) org.json.JSONObject(navGuide) else org.json.JSONObject.NULL } catch (_: Throwable) { org.json.JSONObject.NULL })
        val r = getSharedPreferences("launcher", Context.MODE_PRIVATE).getString("navRoute", "") ?: ""
        o.put("dest", try { if (r.isNotEmpty()) org.json.JSONObject(r).optJSONObject("dest") ?: org.json.JSONObject.NULL else org.json.JSONObject.NULL } catch (_: Throwable) { org.json.JSONObject.NULL })
        o.put("phoneGps", System.currentTimeMillis() - phoneAt < 5000)
        o.put("ver", try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Throwable) { "" })
        return o.toString()
    }
    @Volatile private var sceneAliveAt = 0L
    @Volatile private var sceneImgAt = 0L
    override fun sceneJson(query: String): String {
        // 手機在看 360 狀態：中控台就算不在前景也要叫引擎繼續送偵測結果（引擎只在有人看時才送）
        val now = System.currentTimeMillis()
        if (now - sceneAliveAt > 1500) { sceneAliveAt = now; car.engineCmd("alive", "") }
        // 手機開實景：叫引擎送鏡頭拼成的碗面貼圖／俯視地面影像（只在手機與車機之間，不存檔）
        val q = query.split('&').toSet()
        if (now - sceneImgAt > 3000 && ("real=1" in q || "ground=1" in q)) {
            sceneImgAt = now
            if ("real=1" in q) car.engineCmd("realview", "1")
            if ("ground=1" in q) car.engineCmd("groundimg", "1")
        }
        return car.sceneJson()
    }
    // ── 手機遠端車控（PIN 配對；金鑰只存 SHA-256）──
    @Volatile private var pairPin: String? = null
    @Volatile private var pairPinExp = 0L
    @Volatile private var pairFails = 0
    private fun sha(t: String) = java.security.MessageDigest.getInstance("SHA-256").digest(t.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun lp() = getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private fun phoneTokens(): org.json.JSONArray = try { org.json.JSONArray(lp().getString("phoneTokens", "[]")) } catch (_: Throwable) { org.json.JSONArray() }
    private val REMOTE_OK = setOf("LOCK", "UNLOCK", "TRUNK_OPEN", "TRUNK_CLOSE", "WINDOW_UP", "WINDOW_DOWN", "AC_ON", "AC_OFF")
    private val REMOTE_LABEL = mapOf("LOCK" to "鎖車", "UNLOCK" to "開鎖", "TRUNK_OPEN" to "開尾箱", "TRUNK_CLOSE" to "關尾箱",
        "WINDOW_UP" to "升窗", "WINDOW_DOWN" to "降窗", "AC_ON" to "開空調", "AC_OFF" to "關空調")
    private val CYHY_LABEL = mapOf("lock" to "鎖車", "unlock" to "開鎖", "windows_up" to "升窗", "windows_down" to "降窗",
        "trunk_open" to "開尾箱", "trunk_close" to "關尾箱", "start_engine" to "發動", "stop_engine" to "熄火")
    private fun cloudRes(id: Int, ok: Boolean, msg: String) {
        main.post { web.evaluateJavascript("window.__cloudRes&&window.__cloudRes($id,$ok,${org.json.JSONObject.quote(msg)})", null) }
    }
    @Synchronized override fun pairPhone(pin: String, name: String): String? {
        val p = pairPin
        if (p == null || System.currentTimeMillis() > pairPinExp) return null
        if (pin.trim() != p) { if (++pairFails >= 5) pairPin = null; return null }   // 猜 5 次錯就作廢
        pairPin = null
        val tok = "ct_" + java.util.UUID.randomUUID().toString().replace("-", "") + java.util.UUID.randomUUID().toString().replace("-", "")
        val a = phoneTokens(); a.put(org.json.JSONObject().put("h", sha(tok)).put("name", name.take(40)).put("at", System.currentTimeMillis()))
        lp().edit().putString("phoneTokens", a.toString()).apply()
        main.post { android.widget.Toast.makeText(this, "已配對手機：$name（可以遠端車控）", android.widget.Toast.LENGTH_LONG).show()
            web.evaluateJavascript("window.onPhonePaired && window.onPhonePaired()", null) }
        return tok
    }
    override fun phoneControl(token: String, cmd: String, arg: String): Pair<Int, String>? {
        val h = sha(token); val a = phoneTokens()
        val who = (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("h") == h } ?: return 401 to "沒有配對或已取消配對"
        val label = when (cmd) {
            "remote" -> when {
                arg in REMOTE_OK -> REMOTE_LABEL[arg]!!
                (arg == "START_ENGINE" || arg == "STOP_ENGINE") -> {
                    if (!lp().getBoolean("allowEngine", false)) return 403 to "引擎遠端發動／熄火沒有開啟（中控台 → 車控 → 手機遠端控制 → 允許引擎）"
                    val st = try { org.json.JSONObject(car.json()) } catch (_: Throwable) { org.json.JSONObject() }
                    val spd = st.optDouble("speed", Double.NaN); val gear = st.optString("gear")
                    // 行駛中不熄火；非 P 檔不發動（擋不到就寧可不送）
                    if (arg == "STOP_ENGINE" && !(spd.isNaN() || spd < 1.0)) return 409 to "車子正在移動（${Math.round(spd)} km/h），不熄火"
                    if (arg == "START_ENGINE" && gear.isNotEmpty() && gear != "P") return 409 to "不是 P 檔（目前 $gear），不發動"
                    if (arg == "START_ENGINE" && st.has("handbrake") && st.opt("handbrake") == false) return 409 to "手煞車沒拉，不發動"
                    REMOTE_LABEL[arg]!!
                }
                else -> return 400 to "不允許的指令"
            }
            "ambient_power" -> if (arg == "1" || arg == "0") "氛圍燈${if (arg == "1") "開" else "關"}" else return 400 to "arg 要 0 或 1"
            "ambient_color" -> if (Regex("^[0-9a-fA-F]{6}$").matches(arg)) "氛圍燈顏色 #$arg" else return 400 to "顏色要 6 碼 hex"
            "ambient_mode" -> if (Regex("^[0-9a-fA-F]{4}$").matches(arg)) "氛圍燈模式" else return 400 to "模式碼不對"
            "ambient_bright" -> if ((arg.toIntOrNull() ?: -1) in 5..100) "氛圍燈亮度 $arg" else return 400 to "亮度 5–100"
            "rear_play" -> if ((arg.toIntOrNull() ?: -1) in 0..99) "後擋看板第 $arg 則" else return 400 to "看板編號不對"
            "rear_idle" -> "後擋看板回到自動"
            else -> return 400 to "不允許的指令"
        }
        CmdProvider.push(org.json.JSONObject().put("cmd", cmd).put("arg", arg).toString())
        car.engineCmd("poll", "")
        main.post { android.widget.Toast.makeText(this, "手機遠端（${who.optString("name")}）：$label", android.widget.Toast.LENGTH_LONG).show() }
        return null
    }
    override fun routeJson(): String =
        (getSharedPreferences("launcher", Context.MODE_PRIVATE).getString("navRoute", "") ?: "").ifEmpty { "null" }
    override fun navTo(name: String, lat: Double, lon: Double) {
        main.post { web.evaluateJavascript("window.navTo && window.navTo(" + org.json.JSONObject.quote(name) + ", $lat, $lon)", null) }
    }
    override fun navStop() { main.post { web.evaluateJavascript("window.navStopExt && window.navStopExt()", null) } }
    override fun phoneFix(lat: Double, lon: Double, hdg: Double?, acc: Double?, spd: Double?) {
        phoneLoc = Location("phone").apply {
            latitude = lat; longitude = lon; time = System.currentTimeMillis()
            hdg?.let { bearing = it.toFloat() }; acc?.let { accuracy = it.toFloat() }; spd?.let { speed = it.toFloat() }
        }
        phoneAt = System.currentTimeMillis()
        // 也轉給 360 引擎（地圖輔助：路名、速限、360 上的導航）
        fun f(d: Double?) = d?.let { "%.7f".format(java.util.Locale.US, it) } ?: ""
        car.engineCmd("loc", listOf(f(lat), f(lon), f(hdg), f(acc), f(spd)).joinToString(","))
    }

    // ── 定位（導航用；360 有送定位就優先用 360 的）──
    @Volatile private var fix: Location? = null
    private val locL = object : LocationListener {
        override fun onLocationChanged(l: Location) { val f = fix; if (f == null || l.provider == LocationManager.GPS_PROVIDER || f.provider != LocationManager.GPS_PROVIDER || l.time - f.time > 5000) fix = l }
        @Deprecated("") override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }
    private fun hasLoc() = android.os.Build.VERSION.SDK_INT < 23 ||
        checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!hasLoc()) {
            if (android.os.Build.VERSION.SDK_INT >= 23) requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION), 7)
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) try {
            lm.getLastKnownLocation(p)?.let { if (fix == null) fix = it }
            lm.requestLocationUpdates(p, 1000L, 0f, locL, Looper.getMainLooper())
        } catch (_: Throwable) {}
    }
    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (rc == 7) startLocation()
    }

    override fun onResume() { super.onResume(); resumed = true; hideBars(); main.removeCallbacks(alive); main.post(alive); web.onResume() }
    override fun onPause() { resumed = false; web.onPause(); super.onPause() }
    override fun onDestroy() { car.stop(); web.destroy(); super.onDestroy() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideBars() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { web.evaluateJavascript("window.onBack && window.onBack()", null) }   // 桌面不關閉

    @Suppress("DEPRECATION")
    private fun hideBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    private fun start(i: Intent?): Boolean = try {
        if (i == null) false else { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
    } catch (_: Throwable) { false }

    inner class Bridge {
        private val prefs = getSharedPreferences("launcher", Context.MODE_PRIVATE)

        @JavascriptInterface fun state(): String = car.json()
        /** 中控台自己的定位（360 沒給的時候用）：{"lat","lon","hdg","acc","age"}；沒有回 ""。 */
        /** 道路畫面的導航導引（給手機 App 看）。 */
        @JavascriptInterface fun navState(j: String) { navGuide = j }
        @JavascriptInterface fun location(): String {
            // 手機 GPS（5 秒內）優先：比車上的準
            val ph = phoneLoc?.takeIf { System.currentTimeMillis() - phoneAt < 5000 }
            val l = ph ?: fix ?: return ""
            return org.json.JSONObject().put("lat", l.latitude).put("lon", l.longitude)
                .put("hdg", if (l.hasBearing()) l.bearing.toDouble() else org.json.JSONObject.NULL)
                .put("acc", l.accuracy.toDouble()).put("age", (System.currentTimeMillis() - l.time) / 1000)
                .put("src", if (ph != null) "phone" else "unit").toString()
        }
        @JavascriptInterface fun apps(): String = AppList.json(this@MainActivity)
        @JavascriptInterface fun icon(pkg: String): String = AppList.icon(this@MainActivity, pkg)
        @JavascriptInterface fun launch(pkg: String): Boolean = start(packageManager.getLaunchIntentForPackage(pkg))

        /** 常用入口：360、導航、音樂、電話、設定、藍牙。 */
        @JavascriptInterface fun open(kind: String): Boolean = when (kind) {
            "360" -> start(packageManager.getLaunchIntentForPackage(CarData.ENGINE_PKG))
            "nav" -> start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))) || start(packageManager.getLaunchIntentForPackage("com.google.android.apps.maps"))
            "music" -> start(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC))
            "phone" -> start(Intent(Intent.ACTION_DIAL))
            "settings" -> start(Intent(Settings.ACTION_SETTINGS))
            "bt" -> start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            "trip" -> start(Intent(Intent.ACTION_MAIN).setClassName("com.ts.MainUI", "com.ts.main.bmw.DrivingComputeActivity"))
            else -> false
        }

        /** 媒體鍵：play／next／prev。 */
        @JavascriptInterface fun media(action: String) {
            val code = when (action) { "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS; else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE }
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }

        @JavascriptInterface fun volume(dir: Int) {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (dir > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        }

        /** 送給 ADAS 引擎（氛圍燈、後擋看板）。 */
        @JavascriptInterface fun engine(cmd: String, arg: String) { if (cmd == "ambient" || cmd == "rear" || cmd == "realview" || cmd == "groundimg" || cmd == "nav") car.engineCmd(cmd, arg) }

        /**
         * 道路畫面卡片：打開原廠 360，叫引擎切到 Tesla 式道路畫面。
         * 360 可能還沒啟動（引擎還沒收得到廣播）→ 開了之後 1.5 秒、4 秒、8 秒各再送一次（畫面還沒建好時引擎會先記著）。
         */
        @JavascriptInterface fun roadView(): Boolean {
            car.engineCmd("roadview", "")
            val ok = start(packageManager.getLaunchIntentForPackage(CarData.ENGINE_PKG))
            for (d in longArrayOf(1500, 4000, 8000)) main.postDelayed({ car.engineCmd("roadview", "") }, d)
            return ok
        }

        /**
         * 車控（車窗、車鎖、尾箱、空調、氛圍燈、看板）：排進 CmdProvider，再通知 360 引擎來拿。
         * 引擎會先確認 provider 的擁有者與簽章，別的 App 送廣播沒用。
         */
        @JavascriptInterface fun control(cmd: String, arg: String) {
            CmdProvider.push(org.json.JSONObject().put("cmd", cmd).put("arg", arg).toString())
            car.engineCmd("poll", "")
        }

        // ── 遠端模組雲端控制（cyhy）：車熄火、模組不在藍牙範圍也能發。非同步，結果呼叫 window.__cloudRes(id, ok, msg) ──
        @JavascriptInterface fun cloudReady(): Boolean = CyCloud.configured(lp())
        @JavascriptInterface fun cloudSaved(): String = org.json.JSONObject()
            .put("ready", CyCloud.configured(lp())).put("uid", lp().getString("cyhyUid", "")).put("tid", lp().getString("cyhyTid", "")).toString()
        @JavascriptInterface fun setCyhy(ssk: String, uid: String, tid: String, txnkey: String, hosts: String, port: Int) {
            lp().edit().putString("cyhySsk", ssk.trim()).putString("cyhyUid", uid.trim()).putString("cyhyTid", tid.trim())
                .putString("cyhyTxnkey", txnkey.trim()).putString("cyhyHosts", hosts.trim()).putInt("cyhyPort", if (port in 1..65535) port else 6050).apply()
        }
        @JavascriptInterface fun cloudClear() { lp().edit().remove("cyhySsk").remove("cyhyUid").remove("cyhyTid").remove("cyhyTxnkey").apply() }
        @JavascriptInterface fun cloudControl(id: Int, action: String) {
            Thread {
                val label = CYHY_LABEL[action] ?: run { cloudRes(id, false, "不允許的指令"); return@Thread }
                // 安全攔截：移動中不熄火；非 P 檔／手煞車沒拉不發動（讀得到車況才擋）
                val st = try { org.json.JSONObject(car.json()) } catch (_: Throwable) { org.json.JSONObject() }
                val spd = st.optDouble("speed", Double.NaN); val gear = st.optString("gear")
                if (action == "stop_engine" && !(spd.isNaN() || spd < 1.0)) { cloudRes(id, false, "車子正在移動（${Math.round(spd)} km/h），不熄火"); return@Thread }
                if (action == "start_engine" && gear.isNotEmpty() && gear != "P") { cloudRes(id, false, "不是 P 檔（目前 $gear），不發動"); return@Thread }
                if (action == "start_engine" && st.has("handbrake") && st.opt("handbrake") == false) { cloudRes(id, false, "手煞車沒拉，不發動"); return@Thread }
                val (ok, msg) = CyCloud.send(lp(), action)
                if (ok) main.post { android.widget.Toast.makeText(this@MainActivity, "雲端$label：已送出", android.widget.Toast.LENGTH_SHORT).show() }
                cloudRes(id, ok, if (ok) label else msg)
            }.start()
        }

        /** 車控面板「配對手機」：產生 6 位數 PIN（5 分鐘有效）。 */
        @JavascriptInterface fun phonePairPin(): String {
            val pin = (100000 + java.security.SecureRandom().nextInt(900000)).toString()
            pairPin = pin; pairPinExp = System.currentTimeMillis() + 5 * 60_000; pairFails = 0
            return pin
        }
        @JavascriptInterface fun phonePaired(): String = phoneTokens().toString().let { t ->
            org.json.JSONArray().also { out -> val a = org.json.JSONArray(t); for (i in 0 until a.length()) out.put(a.getJSONObject(i).optString("name")) }.toString() }
        @JavascriptInterface fun phoneUnpairAll() { prefs.edit().putString("phoneTokens", "[]").apply() }
        @JavascriptInterface fun pref(k: String): String = prefs.getString(k, "") ?: ""
        @JavascriptInterface fun setPref(k: String, v: String) { prefs.edit().putString(k, v).apply() }
    }
}
