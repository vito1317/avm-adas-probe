package com.vito.avmphone

import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 反向連線：車機（中控台）的 ROM 不讓 app 綁監聽埠，所以改成**車機主動連到手機**，手機當伺服器。
 * 手機（一般手機，綁得了埠）在 [PORT] 聽；車機連進來後，手機用這條連線「要」車況／360／導航、推手機定位。
 *
 * 封包：`%08d`(長度) + JSON。車機連上先送 {"hello":{name,ver}}。
 * 手機要資料：{"id":n,"m":方法,"p":參數}；車機回 {"id":n,"r":字串} 或 {"id":n,"e":訊息,"c":碼}。
 * 手機推送（定位，不用回）：{"push":"loc","p":{...}}。
 */
object CarServer {
    const val PORT = 8765
    @Volatile private var running = false
    @Volatile private var sock: Socket? = null
    @Volatile var carName = ""; private set
    @Volatile var carVer = ""; private set
    @Volatile var connectedAt = 0L; private set
    private var out: java.io.OutputStream? = null
    private val pending = HashMap<Int, java.util.concurrent.ArrayBlockingQueue<JSONObject>>()
    private var seq = 0
    private val lock = Any()

    fun connected(): Boolean = sock?.isConnected == true && sock?.isClosed == false

    fun start() {
        if (running) return
        running = true
        thread(name = "car-server", isDaemon = true) {
            while (running) {
                try {
                    ServerSocket(PORT).use { ss ->
                        while (running) {
                            val s = try { ss.accept() } catch (_: Throwable) { break }
                            thread(isDaemon = true) { serve(s) }
                        }
                    }
                } catch (_: Throwable) { Thread.sleep(1500) }
            }
        }
    }

    private fun serve(s: Socket) {
        // 只保留最新一條車機連線
        try { sock?.close() } catch (_: Throwable) {}
        sock = s; out = s.getOutputStream()
        try {
            s.tcpNoDelay = true
            val ins = java.io.BufferedInputStream(s.getInputStream())
            while (running) {
                val j = readFrame(ins) ?: break
                if (j.has("hello")) {
                    val h = j.getJSONObject("hello"); carName = h.optString("name"); carVer = h.optString("ver"); connectedAt = System.currentTimeMillis(); continue
                }
                if (j.has("id")) {
                    val id = j.getInt("id")
                    synchronized(lock) { pending.remove(id) }?.offer(j)
                }
            }
        } catch (_: Throwable) {
        } finally {
            if (sock === s) { sock = null; out = null }
            try { s.close() } catch (_: Throwable) {}
        }
    }

    private fun readFrame(ins: java.io.InputStream): JSONObject? {
        val hdr = ByteArray(8); var r = 0
        while (r < 8) { val n = ins.read(hdr, r, 8 - r); if (n < 0) return null; r += n }
        val len = String(hdr).trim().toIntOrNull() ?: return null
        if (len !in 0..33_554_432) return null
        val buf = ByteArray(len); r = 0
        while (r < len) { val n = ins.read(buf, r, len - r); if (n < 0) return null; r += n }
        return try { JSONObject(String(buf, 0, len, Charsets.UTF_8)) } catch (_: Throwable) { null }
    }

    private fun send(o: JSONObject): Boolean {
        val os = out ?: return false
        return try {
            val b = o.toString().toByteArray(Charsets.UTF_8)
            synchronized(os) { os.write("%08d".format(b.size).toByteArray()); os.write(b); os.flush() }
            true
        } catch (_: Throwable) { false }
    }

    /** 跟車機要資料；回 (ok, body)。車機沒連上或逾時回 null。 */
    fun request(method: String, params: JSONObject = JSONObject(), timeoutMs: Long = 8000): Triple<Boolean, String, Int>? {
        if (!connected()) return null
        val id: Int; val q = java.util.concurrent.ArrayBlockingQueue<JSONObject>(1)
        synchronized(lock) { id = ++seq; pending[id] = q }
        if (!send(JSONObject().put("id", id).put("m", method).put("p", params))) { synchronized(lock) { pending.remove(id) }; return null }
        val resp = try { q.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Throwable) { null }
        synchronized(lock) { pending.remove(id) }
        if (resp == null) return null
        return if (resp.has("e")) Triple(false, resp.optString("e"), resp.optInt("c", 400))
        else Triple(true, resp.optString("r"), 200)
    }

    fun push(kind: String, data: JSONObject) { send(JSONObject().put("push", kind).put("p", data)) }
}
