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
            setBackgroundColor(0xFF0B0F14.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webChromeClient = WebChromeClient(); webViewClient = WebViewClient()
            addJavascriptInterface(Bridge(), "App")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(web)
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    inner class Bridge {
        /** {"ip","seen","name","ver"} */
        @JavascriptInterface fun carInfo(): String = JSONObject().put("ip", CarLink.ip ?: JSONObject.NULL).put("seen", CarLink.seen())
            .put("name", CarLink.name).put("ver", CarLink.ver).toString()
        /** 車機的 /status（連不上回 ""）。 */
        @JavascriptInterface fun status(): String = CarLink.get("/status") ?: ""
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
            var u = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=10&accept-language=zh-TW&countrycodes=tw&q=" + java.net.URLEncoder.encode(q, "UTF-8")
            if (loc != null) u += "&viewbox=${loc.longitude - 0.5},${loc.latitude + 0.5},${loc.longitude + 0.5},${loc.latitude - 0.5}"
            return try {
                val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 8000; c.setRequestProperty("User-Agent", "E60Phone/1.0 (personal car companion)")
                val arr = org.json.JSONArray(c.inputStream.bufferedReader().use { it.readText() })
                val out = org.json.JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i); val dn = o.optString("display_name")
                    val lat = o.getString("lat").toDouble(); val lon = o.getString("lon").toDouble()
                    val r = JSONObject().put("name", o.optString("name").ifEmpty { dn.substringBefore(',') }).put("addr", dn).put("lat", lat).put("lon", lon)
                    if (loc != null) { val d = FloatArray(1); android.location.Location.distanceBetween(loc.latitude, loc.longitude, lat, lon, d); r.put("dist", d[0].toDouble()) }
                    out.put(r)
                }
                out.toString()
            } catch (t: Throwable) { JSONObject().put("err", t.javaClass.simpleName + ": " + (t.message ?: "")).toString() }
        }
        @JavascriptInterface fun wantShare(): Boolean = getSharedPreferences("car", MODE_PRIVATE).getBoolean("share", true)
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        web.evaluateJavascript("window.onPerm && window.onPerm()", null)
    }
}
