package com.vito.avmlauncher

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 反向連線：這台車機的 ROM 不讓 app 綁監聽埠（PhoneLink 綁不起來），所以改成**車機主動連到手機**。
 * 手機（[com.vito.avmphone] 的 CarServer）在埠 8765 聽；車機連進去、送 hello，之後手機「要」車況／360／導航、
 * 推手機定位，車機用同一套 [PhoneLink.Host] 回應。手機位址＝熱點閘道（手機當熱點就是閘道）或手動設定。
 *
 * 封包：`%08d`(長度)+JSON。手機要資料：{"id":n,"m":方法,"p":參數}；車機回 {"id":n,"r":字串} 或 {"id":n,"e":訊息,"c":碼}。
 * 推送（定位）：{"push":"loc","p":{...}}。中控台在前景時才連（背景會被系統回收，避免跟 ADAS 搶資源）。
 */
class PhoneClient(private val ctx: Context, private val host: PhoneLink.Host) {
    companion object { const val PORT = 8765; private const val TAG = "PhoneClient" }

    @Volatile private var running = false
    @Volatile var status = "未連線"; private set
    private var sock: Socket? = null

    /** 手動指定手機 IP（中控台設定填）；空＝自動用熱點閘道。 */
    private fun manualIp() = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString("phoneIp", "") ?: ""

    private fun gatewayIp(): String? = try {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION") val g = wm.dhcpInfo?.gateway ?: 0
        if (g == 0) null else "%d.%d.%d.%d".format(g and 0xff, (g shr 8) and 0xff, (g shr 16) and 0xff, (g shr 24) and 0xff)
    } catch (_: Throwable) { null }

    fun start() {
        if (running) return
        running = true
        thread(name = "phone-client", isDaemon = true) {
            while (running) {
                val ip = manualIp().ifEmpty { gatewayIp() ?: "" }
                if (ip.isEmpty()) { status = "找不到手機位址（連上手機熱點了嗎？）"; sleep(3000); continue }
                try { connect(ip) } catch (_: Throwable) {}
                if (running) { status = "斷線，重連中（$ip）"; sleep(2500) }
            }
        }
    }

    fun stop() { running = false; try { sock?.close() } catch (_: Throwable) {} }

    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (_: Throwable) {}

    private fun connect(ip: String) {
        Socket().use { s ->
            sock = s; s.connect(InetSocketAddress(ip, PORT), 3000); s.soTimeout = 0; s.tcpNoDelay = true
            status = "已連上手機 $ip"
            val out = s.getOutputStream()
            fun send(o: JSONObject) { val b = o.toString().toByteArray(Charsets.UTF_8); synchronized(out) { out.write("%08d".format(b.size).toByteArray()); out.write(b); out.flush() } }
            val ver = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (_: Throwable) { "" }
            send(JSONObject().put("hello", JSONObject().put("name", "E60").put("ver", ver)))
            val ins = java.io.BufferedInputStream(s.getInputStream())
            while (running) {
                val j = readFrame(ins) ?: break
                if (j.has("push")) { handlePush(j.optString("push"), j.optJSONObject("p") ?: JSONObject()); continue }
                if (!j.has("id")) continue
                val id = j.getInt("id")
                // 每個請求丟到工作執行緒，才不會卡住讀取（camera 可能慢）
                thread(isDaemon = true) {
                    val resp = JSONObject().put("id", id)
                    try { resp.put("r", dispatch(j.optString("m"), j.optJSONObject("p") ?: JSONObject())) }
                    catch (e: Throwable) { resp.put("e", e.message ?: e.javaClass.simpleName).put("c", if (e is Err) e.code else 500) }
                    try { send(resp) } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun handlePush(kind: String, p: JSONObject) {
        if (kind == "loc") host.phoneFix(p.getDouble("lat"), p.getDouble("lon"),
            p.optDouble("hdg").takeIf { !it.isNaN() }, p.optDouble("acc").takeIf { !it.isNaN() }, p.optDouble("spd").takeIf { !it.isNaN() })
    }

    /** 回傳 result 字串；錯誤丟例外（PhoneClientException 帶狀態碼）。 */
    private fun dispatch(m: String, p: JSONObject): String = when (m) {
        "status" -> host.statusJson()
        "scene" -> host.sceneJson(listOfNotNull(if (p.optInt("real") == 1) "real=1" else null, if (p.optInt("ground") == 1) "ground=1" else null).joinToString("&"))
        "route" -> host.routeJson()
        "nav_to" -> { host.navTo(p.optString("name", "目的地"), p.getDouble("lat"), p.getDouble("lon")); "{\"ok\":true}" }
        "nav_stop" -> { host.navStop(); "{\"ok\":true}" }
        "pair" -> host.pairPhone(p.optString("pin"), p.optString("name", "手機"))?.let { JSONObject().put("token", it).toString() }
            ?: throw err(403, "PIN 錯誤或過期（中控台 → 車控 → 配對手機）")
        "control" -> host.phoneControl(p.optString("token"), p.optString("cmd"), p.optString("arg"))?.let { throw err(it.first, it.second) } ?: "{\"ok\":true}"
        else -> throw err(400, "unknown method $m")
    }

    private class Err(val code: Int, msg: String) : RuntimeException(msg)
    private fun err(code: Int, msg: String) = Err(code, msg)

    private fun readFrame(ins: java.io.InputStream): JSONObject? {
        val hdr = ByteArray(8); var r = 0
        while (r < 8) { val n = ins.read(hdr, r, 8 - r); if (n < 0) return null; r += n }
        val len = String(hdr).trim().toIntOrNull() ?: return null
        if (len !in 0..65536) return null
        val buf = ByteArray(len); r = 0
        while (r < len) { val n = ins.read(buf, r, len - r); if (n < 0) return null; r += n }
        return try { JSONObject(String(buf, 0, len, Charsets.UTF_8)) } catch (_: Throwable) { null }
    }
}
