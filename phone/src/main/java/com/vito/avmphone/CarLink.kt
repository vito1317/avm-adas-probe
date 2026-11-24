package com.vito.avmphone

import android.content.Context
import org.json.JSONObject

/**
 * 跟車機（中控台）互聯——**反向連線**版：車機 ROM 不讓 app 綁監聽埠，所以改成車機主動連到手機（[CarServer]），
 * 手機這邊只要透過 CarServer 這條已建立的連線「要」資料、送指令即可。公開介面維持不變，上層（[RelayService]、
 * [GpsShareService]、[MainActivity]）不用改。只有導航與狀態＋已配對的車控，不碰別的。
 */
object CarLink {
    private var prefs: android.content.SharedPreferences? = null

    fun start(ctx: Context) {
        prefs = ctx.getSharedPreferences("car", Context.MODE_PRIVATE)
        CarServer.start()
    }

    /** 車機有連進來就算連上。 */
    fun seen(): Boolean = CarServer.connected()
    val name: String get() = CarServer.carName
    val ver: String get() = CarServer.carVer
    /** 反向連線沒有「車機 IP」概念（是車機連進來）；連上時給個標示。 */
    val ip: String? get() = if (CarServer.connected()) "車機已連入" else null

    private fun req(method: String, p: JSONObject = JSONObject()): String? {
        val r = CarServer.request(method, p) ?: return null
        return if (r.first) r.second else null
    }

    /** 相容舊呼叫：path 轉成 method。 */
    fun get(path: String): String? {
        val q = path.substringAfter('?', "")
        return when (path.substringBefore('?')) {
            "/status" -> req("status")
            "/route" -> req("route")
            "/scene" -> req("scene", JSONObject().put("real", if ("real=1" in q) 1 else 0).put("ground", if ("ground=1" in q) 1 else 0))
            else -> null
        }
    }

    fun post(path: String, body: String): String? {
        val b = try { JSONObject(body) } catch (_: Throwable) { JSONObject() }
        return when (path) {
            "/nav" -> req("nav_to", b)
            "/nav/stop" -> req("nav_stop")
            "/loc" -> { if (!CarServer.connected()) null else { CarServer.push("loc", b); "{\"ok\":true}" } }
            else -> null
        }
    }

    // ───── 遠端車控（要先用中控台顯示的 PIN 配對）─────
    /** 反向連線不需要手機知道車機 IP（車機主動連進來），保留介面但無作用。 */
    fun manual(ip: String) {}

    fun controlPaired(): Boolean = !prefs?.getString("ctok", null).isNullOrEmpty()
    fun controlUnpair() { prefs?.edit()?.remove("ctok")?.apply() }

    fun controlPair(pin: String, name: String): String? {
        val r = CarServer.request("pair", JSONObject().put("pin", pin.trim()).put("name", name)) ?: return "車機還沒連上（確認車機連到這支手機的熱點、中控台 2.6 以上在前景）"
        if (!r.first) return r.second.ifEmpty { "配對失敗" }
        val tok = try { JSONObject(r.second).optString("token") } catch (_: Throwable) { "" }
        if (tok.isEmpty()) return "配對回應異常"
        prefs?.edit()?.putString("ctok", tok)?.apply()
        return null
    }

    fun control(cmd: String, arg: String): String? {
        val tok = prefs?.getString("ctok", null) ?: return "還沒跟中控台配對車控（中控台 → 車控 → 配對手機）"
        val r = CarServer.request("control", JSONObject().put("token", tok).put("cmd", cmd).put("arg", arg)) ?: return "車機沒連上"
        if (r.first) return null
        if (r.third == 401) { controlUnpair(); return "中控台已取消配對，請重新配對" }
        return r.second.ifEmpty { "指令失敗" }
    }
}
