package com.vito.avmlauncher

import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 跟手機 App 互聯（車機連手機熱點，兩邊在同一個區網）：
 * - UDP 廣播（埠 [BEACON_PORT]，每 2 秒）：「我在這」——手機 App 收到就知道車機的 IP，不用自己輸入。
 * - 小 HTTP 伺服器（埠 [HTTP_PORT]）：
 *     GET  /status    車況、導航進度、配件狀態、版本
 *     GET  /route     目前的導航路線（OSRM 規劃結果；沒在導航 = null）
 *     GET  /scene     360 環景狀態（周圍物件、車道線、警示）；?real=1 加碗面實景貼圖、?ground=1 加俯視地面影像
 *     POST /nav       {"name","lat","lon"}：設定目的地（中控台規劃路線、開始導航）
 *     POST /nav/stop  結束導航
 *     POST /loc       {"lat","lon","hdg","acc","spd"}：手機的 GPS（比車上的準，新的優先用）
 * 只接受區網內的裝置（私人 IP）；只有導航與狀態，不碰任何車控（車窗、車鎖、遠端模組都不經過這裡）。
 */
class PhoneLink(private val host: Host) {
    interface Host {
        fun statusJson(): String
        /** [query]：手機要不要實景影像（real=1 碗面貼圖、ground=1 俯視地面影像）。 */
        fun sceneJson(query: String): String
        fun routeJson(): String
        fun navTo(name: String, lat: Double, lon: Double)
        fun navStop()
        fun phoneFix(lat: Double, lon: Double, hdg: Double?, acc: Double?, spd: Double?)
    }

    companion object {
        const val HTTP_PORT = 8765
        const val BEACON_PORT = 8766
        private const val TAG = "PhoneLink"
    }

    @Volatile private var running = false
    private var server: ServerSocket? = null

    fun start(name: String, ver: String) {
        if (running) return
        running = true
        thread(name = "phone-http", isDaemon = true) {
            try {
                val ss = ServerSocket(HTTP_PORT).also { server = it }
                while (running) {
                    val s = try { ss.accept() } catch (_: Throwable) { break }
                    thread(name = "phone-req", isDaemon = true) { try { serve(s) } catch (t: Throwable) { Log.w(TAG, "req", t) } finally { try { s.close() } catch (_: Throwable) {} } }
                }
            } catch (t: Throwable) { Log.w(TAG, "server", t) }
        }
        thread(name = "phone-beacon", isDaemon = true) {
            try {
                DatagramSocket().use { ds ->
                    ds.broadcast = true
                    val msg = JSONObject().put("svc", "avm").put("name", name).put("port", HTTP_PORT).put("ver", ver).toString().toByteArray()
                    while (running) {
                        for (a in broadcastAddrs()) try { ds.send(DatagramPacket(msg, msg.size, a, BEACON_PORT)) } catch (_: Throwable) {}
                        Thread.sleep(2000)
                    }
                }
            } catch (t: Throwable) { Log.w(TAG, "beacon", t) }
        }
    }

    fun stop() { running = false; try { server?.close() } catch (_: Throwable) {} }

    /** 每張網卡的子網廣播位址 ＋ 255.255.255.255。 */
    private fun broadcastAddrs(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) if (ia.address is Inet4Address) ia.broadcast?.let { out.add(it) }
            }
        } catch (_: Throwable) {}
        out.add(InetAddress.getByName("255.255.255.255"))
        return out
    }

    private fun serve(s: Socket) {
        val ra = s.inetAddress
        if (!(ra.isSiteLocalAddress || ra.isLinkLocalAddress || ra.isLoopbackAddress)) { reply(s, 403, "{\"err\":\"forbidden\"}"); return }
        s.soTimeout = 5000
        val inp = BufferedInputStream(s.getInputStream())
        val head = readHead(inp) ?: return
        val lines = head.split("\r\n")
        val req = lines[0].split(" ")
        if (req.size < 2) return
        val method = req[0]; val path = req[1].substringBefore('?'); val query = req[1].substringAfter('?', "")
        val len = lines.firstOrNull { it.lowercase().startsWith("content-length:") }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        val body = if (len in 1..65536) ByteArray(len).also { b -> var r = 0; while (r < len) { val n = inp.read(b, r, len - r); if (n < 0) break; r += n } }.toString(Charsets.UTF_8) else ""
        when {
            method == "OPTIONS" -> reply(s, 204, "")
            method == "GET" && path == "/status" -> reply(s, 200, host.statusJson())
            method == "GET" && path == "/scene" -> reply(s, 200, host.sceneJson(query))
            method == "GET" && path == "/route" -> reply(s, 200, host.routeJson())
            method == "POST" && path == "/nav" -> {
                val j = JSONObject(body)
                host.navTo(j.optString("name", "目的地"), j.getDouble("lat"), j.getDouble("lon")); reply(s, 200, "{\"ok\":true}")
            }
            method == "POST" && path == "/nav/stop" -> { host.navStop(); reply(s, 200, "{\"ok\":true}") }
            method == "POST" && path == "/loc" -> {
                val j = JSONObject(body)
                host.phoneFix(j.getDouble("lat"), j.getDouble("lon"), j.optDouble("hdg").takeIf { !it.isNaN() },
                    j.optDouble("acc").takeIf { !it.isNaN() }, j.optDouble("spd").takeIf { !it.isNaN() })
                reply(s, 200, "{\"ok\":true}")
            }
            else -> reply(s, 404, "{\"err\":\"not found\"}")
        }
    }

    private fun readHead(inp: BufferedInputStream): String? {
        val buf = ByteArrayOutputStream(); var last4 = 0
        while (buf.size() < 16384) {
            val b = inp.read(); if (b < 0) return null
            buf.write(b); last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return buf.toString("UTF-8")
        }
        return null
    }

    private fun reply(s: Socket, code: Int, body: String) {
        val b = body.toByteArray(Charsets.UTF_8)
        val msg = when (code) { 200 -> "OK"; 204 -> "No Content"; 403 -> "Forbidden"; else -> "Not Found" }
        val h = "HTTP/1.1 $code $msg\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${b.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: Content-Type\r\nConnection: close\r\n\r\n"
        s.getOutputStream().apply { write(h.toByteArray()); write(b); flush() }
    }
}
