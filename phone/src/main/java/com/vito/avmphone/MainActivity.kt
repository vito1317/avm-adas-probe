package com.vito.avmphone

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * 手機 App 主畫面（WebView ＋ assets/index.html）：連線狀態、車況、導航（搜尋目的地送給車機、結束導航）、手機 GPS 分享。
 * JS 介面「App」：carInfo()、status()、navTo()、navStop()、setIp()、shareGps()、sharing()。
 */
class MainActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CarLink.start(this)
        web = WebView(this).apply {
            setBackgroundColor(0xFF07090C.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            @Suppress("DEPRECATION") settings.allowFileAccessFromFileURLs = true   // 3D 畫面讀 assets 裡的 .glb 模型
            webChromeClient = WebChromeClient(); webViewClient = WebViewClient()
            addJavascriptInterface(Bridge(), "App")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(web)
        // 當儀表用：開著就不要熄螢幕；狀態列、導覽列跟背景同色
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION") run { window.statusBarColor = 0xFF07090C.toInt(); window.navigationBarColor = 0xFF07090C.toInt() }
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
        // 遠端 AI 開著就接著連（重開 App、手機重開後打開 App）
        if (RelayService.paired(this) && RelayService.prefs(this).getBoolean("on", false)) RelayService.start(this)
    }

    private val busy = java.util.Collections.synchronizedSet(HashSet<String>())
    private fun reply(id: Int, text: String?) {
        val js = "window.__res && window.__res($id, " + JSONObject.quote(text ?: "") + ")"
        runOnUiThread { try { web.evaluateJavascript(js, null) } catch (_: Throwable) {} }
    }

    inner class Bridge {
        /** {"ip","seen","name","ver"} */
        @JavascriptInterface fun carInfo(): String = JSONObject().put("ip", CarLink.ip ?: JSONObject.NULL).put("seen", CarLink.seen())
            .put("name", CarLink.name).put("ver", CarLink.ver).toString()
        /** 車機的 /status（連不上回 ""）。 */
        @JavascriptInterface fun status(): String = CarLink.get("/status") ?: ""
        /**
         * 非同步請求（不卡畫面）：kind = status / scene / search；結果呼叫 window.__res(id, text)（失敗 text = ""）。
         * 同一種請求還沒回來就不再送（車機慢的時候不會越堆越多）。
         */
        @JavascriptInterface fun req(id: Int, kind: String, arg: String) {
            if (!busy.add(kind)) { reply(id, null); return }
            kotlin.concurrent.thread {
                val r = try { when (kind) { "status" -> CarLink.get("/status"); "scene" -> CarLink.get("/scene" + (if (arg.isNotEmpty()) "?$arg" else "")); "route" -> CarLink.get("/route"); "search" -> search(arg)
                    "carpair" -> (CarLink.controlPair(arg, "${android.os.Build.MODEL}")?.let { e -> JSONObject().put("err", e) } ?: JSONObject().put("ok", true)).toString()
                    "control" -> JSONObject(arg).let { a -> (CarLink.control(a.optString("cmd"), a.optString("arg"))?.let { e -> JSONObject().put("err", e) } ?: JSONObject().put("ok", true)).toString() }
                    "pair" -> JSONObject(arg).let { a -> RelayService.pair(this@MainActivity, a.optString("server"), a.optString("code"), a.optString("name"))
                        ?.let { e -> JSONObject().put("err", e).toString() } ?: JSONObject().put("ok", true).toString().also { RelayService.start(this@MainActivity) } }
                    else -> null } }
                    catch (_: Throwable) { null } finally { busy.remove(kind) }
                reply(id, r)
            }
        }
        @JavascriptInterface fun navTo(name: String, lat: Double, lon: Double): Boolean {
            return CarLink.post("/nav", JSONObject().put("name", name).put("lat", lat).put("lon", lon).toString()) != null
        }
        @JavascriptInterface fun navStop(): Boolean = CarLink.post("/nav/stop", "{}") != null
        @JavascriptInterface fun setIp(ip: String) { CarLink.manual(ip) }
        @JavascriptInterface fun shareGps(on: Boolean) {
            if (on) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    runOnUiThread { requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 2) }; return
                }
                GpsShareService.start(this@MainActivity)
            } else GpsShareService.stop(this@MainActivity)
            getSharedPreferences("car", MODE_PRIVATE).edit().putBoolean("share", on).apply()
        }
        @JavascriptInterface fun sharing(): String = JSONObject().put("on", GpsShareService.running)
            .put("age", if (GpsShareService.lastSentAt == 0L) -1 else (System.currentTimeMillis() - GpsShareService.lastSentAt) / 1000).toString()
        /** 搜尋目的地（OpenStreetMap Nominatim，台灣、靠近手機目前位置的優先）→ JSON 陣列 [{name,addr,lat,lon}]。 */
        @SuppressLint("MissingPermission")
        @JavascriptInterface fun search(q: String): String {
            val loc = try {
                val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
                listOf("gps", "network", "passive").mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Throwable) { null } }.maxByOrNull { it.time }
            } catch (_: Throwable) { null }
            return try {
                val (list, street) = Geocode.search(q, loc?.latitude, loc?.longitude)
                val out = org.json.JSONArray()
                for (p in list) {
                    val r = JSONObject().put("name", p.name).put("addr", if (street) "（找不到門牌，先到這條路）" + p.addr else p.addr).put("lat", p.lat).put("lon", p.lon)
                    if (loc != null) { val d = FloatArray(1); android.location.Location.distanceBetween(loc.latitude, loc.longitude, p.lat, p.lon, d); r.put("dist", d[0].toDouble()) }
                    out.put(r)
                }
                out.toString()
            } catch (t: Throwable) { JSONObject().put("err", t.javaClass.simpleName + ": " + (t.message ?: "")).toString() }
        }
        /** 遠端 AI（MCP）狀態：{paired, on, running, state, server, name, user, lastCall, lastCallAgo} */
        @JavascriptInterface fun relayInfo(): String {
            val p = RelayService.prefs(this@MainActivity)
            return JSONObject().put("paired", RelayService.paired(this@MainActivity)).put("on", p.getBoolean("on", false))
                .put("running", RelayService.running).put("state", RelayService.state)
                .put("server", p.getString("server", RelayService.DEFAULT_SERVER)).put("name", p.getString("name", "E60")).put("user", p.getString("user", ""))
                .put("lastCall", RelayService.lastCall).put("lastCallAgo", if (RelayService.lastCallAt == 0L) -1 else (System.currentTimeMillis() - RelayService.lastCallAt) / 1000).toString()
        }
        @JavascriptInterface fun relaySet(on: Boolean) {
            RelayService.prefs(this@MainActivity).edit().putBoolean("on", on).apply()
            if (on && RelayService.paired(this@MainActivity)) RelayService.start(this@MainActivity) else RelayService.stop(this@MainActivity)
        }
        @JavascriptInterface fun relayUnpair() { RelayService.unpair(this@MainActivity) }
        /** 車控：{paired, aiAllowed} */
        @JavascriptInterface fun ctlInfo(): String = JSONObject().put("paired", CarLink.controlPaired())
            .put("aiAllowed", RelayService.prefs(this@MainActivity).getBoolean("allowControl", false)).toString()
        @JavascriptInterface fun ctlUnpair() { CarLink.controlUnpair() }
        @JavascriptInterface fun setAiControl(on: Boolean) { RelayService.prefs(this@MainActivity).edit().putBoolean("allowControl", on).apply() }
        @JavascriptInterface fun wantShare(): Boolean = getSharedPreferences("car", MODE_PRIVATE).getBoolean("share", true)

        // ── 亮波雲端（手機自己登入取憑證，直接控車／看車況；車熄火也能用）──
        @JavascriptInterface fun hyllInfo(): String = JSONObject()
            .put("configured", HyllCloud.configured(this@MainActivity)).put("account", HyllCloud.account(this@MainActivity)).toString()
        /** 登入；非同步，結果回 window.__res(id, '{"ok":true}' 或 '{"err":...}')。成功順便把憑證推給 relay。 */
        @JavascriptInterface fun hyllLogin(id: Int, account: String, password: String) {
            kotlin.concurrent.thread {
                val err = HyllCloud.login(this@MainActivity, account, password)
                if (err == null) pushCredsToRelay()
                reply(id, (if (err == null) JSONObject().put("ok", true) else JSONObject().put("err", err)).toString())
            }
        }
        @JavascriptInterface fun hyllControl(id: Int, action: String) {
            kotlin.concurrent.thread {
                val (ok, msg) = HyllCloud.control(this@MainActivity, action)
                if (ok) pushCredsToRelay()   // 重登過的話把新憑證也推給 relay
                reply(id, (if (ok) JSONObject().put("ok", true).put("msg", msg) else JSONObject().put("err", msg)).toString())
            }
        }
        @JavascriptInterface fun hyllStatus(id: Int) {
            kotlin.concurrent.thread { reply(id, HyllCloud.status(this@MainActivity) ?: "") }
        }
        @JavascriptInterface fun hyllLogout() { HyllCloud.logout(this@MainActivity) }
    }

    /** 登入後把 cyhy 憑證推給 Spark relay（讓 MCP／中控台也新鮮）。走 relay 的裝置 token 認證。 */
    private fun pushCredsToRelay() {
        try {
            val creds = HyllCloud.creds(this) ?: return
            val rp = RelayService.prefs(this)
            val tok = rp.getString("token", null) ?: return
            val base = rp.getString("server", RelayService.DEFAULT_SERVER) ?: return
            val c = (java.net.URL("$base/api/cyhy").openConnection() as java.net.HttpURLConnection)
            c.connectTimeout = 6000; c.readTimeout = 8000; c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer $tok"); c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(creds.toString().toByteArray()) }
            c.inputStream.use { it.read() }
        } catch (_: Throwable) {}
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        web.evaluateJavascript("window.onPerm && window.onPerm()", null)
    }
}
