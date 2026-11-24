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

    /**
     * 找「跟車機同一個網段」的本機位址——手機當熱點時，車機在 192.168.x.x，這張卡才通到車機。
     * 綁到它，連線就不會被系統丟去走行動數據（有行動數據時連內網失敗的經典坑）。
     */
    private fun localBindFor(target: String): java.net.InetAddress? = try {
        val t = java.net.InetAddress.getByName(target).address
        if (t.size != 4) null else {
            var best: java.net.InetAddress? = null
            for (ni in java.net.NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address
                    if (a !is java.net.Inet4Address) continue
                    val la = a.address; val p = ia.networkPrefixLength.toInt()
                    if (sameSubnet(la, t, p)) { best = a }
                }
            }
            best
        }
    } catch (_: Throwable) { null }

    private fun sameSubnet(a: ByteArray, b: ByteArray, prefix: Int): Boolean {
        var bits = prefix
        for (i in 0 until 4) {
            if (bits <= 0) break
            val m = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((a[i].toInt() and m) != (b[i].toInt() and m)) return false
            bits -= 8
        }
        return true
    }

    /** 回傳 (HTTP 狀態碼, 內容)；連不上 null。用原生 socket＋綁本機網卡，手動 HTTP（繞開多網路路由問題）。 */
    private fun raw(method: String, path: String, body: String?, token: String?): Pair<Int, String>? {
        val h = ip ?: return null
        val b = body?.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append("$method $path HTTP/1.1\r\nHost: $h:$port\r\n")
        if (token != null) sb.append("Authorization: Bearer $token\r\n")
        if (b != null) sb.append("Content-Type: application/json\r\nContent-Length: ${b.size}\r\n")
        sb.append("Connection: close\r\n\r\n")
        return try {
            java.net.Socket().use { s ->
                localBindFor(h)?.let { try { s.bind(java.net.InetSocketAddress(it, 0)) } catch (_: Throwable) {} }
                s.connect(java.net.InetSocketAddress(h, port), 2000); s.soTimeout = 5000
                val out = s.getOutputStream()
                out.write(sb.toString().toByteArray(Charsets.US_ASCII)); if (b != null) out.write(b); out.flush()
                val ins = java.io.BufferedInputStream(s.getInputStream())
                // 讀 header
                val head = java.io.ByteArrayOutputStream(); var last4 = 0
                while (true) { val c = ins.read(); if (c < 0) break; head.write(c); last4 = (last4 shl 8) or c; if (last4 == 0x0D0A0D0A) break }
                val lines = head.toString("UTF-8").split("\r\n")
                val code = lines.firstOrNull()?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: return null
                val len = lines.firstOrNull { it.lowercase().startsWith("content-length:") }?.substringAfter(':')?.trim()?.toIntOrNull()
                val bodyBuf = java.io.ByteArrayOutputStream()
                if (len != null) { val buf = ByteArray(8192); var got = 0; while (got < len) { val n = ins.read(buf, 0, minOf(buf.size, len - got)); if (n < 0) break; bodyBuf.write(buf, 0, n); got += n } }
                else { val buf = ByteArray(8192); while (true) { val n = ins.read(buf); if (n < 0) break; bodyBuf.write(buf, 0, n) } }
                if (code in 200..299) seenAt = System.currentTimeMillis()
                code to bodyBuf.toString("UTF-8")
            }
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
