package com.vito.avmphone

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import kotlin.concurrent.thread

/**
 * 找車機、跟車機講話（車機連手機熱點，兩邊在同一個區網）：
 * - 聽車機每 2 秒廣播的「我在這」（UDP 8766）→ 記下車機的 IP；也可以手動輸入。
 * - HTTP（埠 8765）：GET /status、POST /nav、POST /nav/stop、POST /loc。
 * 只有導航與狀態，不碰任何車控。
 */
object CarLink {
    const val BEACON_PORT = 8766
    @Volatile var ip: String? = null; private set
    @Volatile var port = 8765; private set
    @Volatile var name: String = ""; private set
    @Volatile var ver: String = ""; private set
    @Volatile var seenAt = 0L; private set
    @Volatile private var listening = false
    private var lock: WifiManager.MulticastLock? = null

    fun manual(ipText: String) {
        val v = ipText.trim()
        if (v.isNotEmpty()) { ip = v; seenAt = System.currentTimeMillis() }
        prefs?.edit()?.putString("ip", v)?.apply()
    }

    private var prefs: android.content.SharedPreferences? = null

    fun start(ctx: Context) {
        prefs = ctx.getSharedPreferences("car", Context.MODE_PRIVATE)
        if (ip == null) prefs?.getString("ip", null)?.takeIf { it.isNotEmpty() }?.let { ip = it }
        if (listening) return
        listening = true
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wm.createMulticastLock("avm-beacon").apply { setReferenceCounted(false); acquire() }
        } catch (_: Throwable) {}
        thread(name = "beacon", isDaemon = true) {
            try {
                DatagramSocket(null).use { ds ->
                    ds.reuseAddress = true; ds.broadcast = true
                    ds.bind(InetSocketAddress(BEACON_PORT))
                    val buf = ByteArray(1024)
                    while (listening) {
                        val pk = DatagramPacket(buf, buf.size)
                        try { ds.receive(pk) } catch (_: Throwable) { continue }
                        try {
                            val j = JSONObject(String(pk.data, 0, pk.length, Charsets.UTF_8))
                            if (j.optString("svc") != "avm") continue
                            ip = pk.address.hostAddress; port = j.optInt("port", 8765)
                            name = j.optString("name"); ver = j.optString("ver"); seenAt = System.currentTimeMillis()
                            prefs?.edit()?.putString("ip", ip)?.apply()
                        } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) { listening = false }
        }
    }

    /** 最近 6 秒內有收到車機的廣播（或手動設定後連得上）。 */
    fun seen(): Boolean = System.currentTimeMillis() - seenAt < 6000

    fun get(path: String): String? = req("GET", path, null)
    fun post(path: String, body: String): String? = req("POST", path, body)

    private fun req(method: String, path: String, body: String?): String? = raw(method, path, body, null)?.takeIf { it.first in 200..299 }?.second

    /** 回傳 (HTTP 狀態碼, 內容)；連不上 null。 */
    private fun raw(method: String, path: String, body: String?, token: String?): Pair<Int, String>? {
        val h = ip ?: return null
        return try {
            val c = URL("http://$h:$port$path").openConnection() as HttpURLConnection
            c.connectTimeout = 1500; c.readTimeout = 3000; c.requestMethod = method
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val t = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code in 200..299) seenAt = System.currentTimeMillis()
            code to t
        } catch (_: Throwable) { null }
    }

    // ───── 遠端車控（要先用中控台顯示的 PIN 配對）─────
    fun controlPaired(): Boolean = !prefs?.getString("ctok", null).isNullOrEmpty()
    fun controlUnpair() { prefs?.edit()?.remove("ctok")?.apply() }
    /** 用 PIN 配對；成功回 null，失敗回錯誤訊息。 */
    fun controlPair(pin: String, name: String): String? {
        val r = raw("POST", "/pair", JSONObject().put("pin", pin.trim()).put("name", name).toString(), null) ?: return "連不上車機（手機要連著車機、中控台 2.1 以上）"
        if (r.first !in 200..299) return try { JSONObject(r.second).optString("err", "HTTP ${r.first}") } catch (_: Throwable) { "HTTP ${r.first}" }
        prefs?.edit()?.putString("ctok", JSONObject(r.second).getString("token"))?.apply()
        return null
    }
    /** 送車控；成功回 null，失敗回錯誤訊息。 */
    fun control(cmd: String, arg: String): String? {
        val tok = prefs?.getString("ctok", null) ?: return "還沒跟中控台配對車控（中控台 → 車控 → 配對手機）"
        val r = raw("POST", "/control", JSONObject().put("cmd", cmd).put("arg", arg).toString(), tok) ?: return "連不上車機"
        if (r.first == 401) { controlUnpair(); return "中控台已取消配對，請重新配對" }
        if (r.first !in 200..299) return try { JSONObject(r.second).optString("err", "HTTP ${r.first}") } catch (_: Throwable) { "HTTP ${r.first}" }
        return null
    }
}
