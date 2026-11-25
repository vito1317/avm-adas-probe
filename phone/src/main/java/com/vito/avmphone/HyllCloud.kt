package com.vito.avmphone

import android.content.Context
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 亮波（hyll / cyhy）雲端——手機端直接登入＋控車＋看車況。車上遠端模組（CY103）有車聯網，走 cyhy 雲端直達，
 * 車熄火、不在藍牙範圍也能用。協定由官方 App 封包反組譯（明文 TCP 6050）：
 *   `%08d`(長度) + JSON + 8 碼。登入碼 10001003（body 帶 name＋SHA-1(密碼)），回 ssk/uid/txnkey/srvauth；
 *   控制 303xxx；車況 203101／202110。憑證只存本機 prefs，可過期自動重登。
 */
object HyllCloud {
    private const val PREF = "hyll"
    @Volatile var hosts = listOf("s1.cyhycdd.com", "s2.cyhycdd.com", "120.55.52.103")
    @Volatile var port = 6050

    val CODES = mapOf(
        "lock" to "303111", "unlock" to "303117", "windows_up" to "303132", "windows_down" to "303133",
        "trunk_open" to "303130", "trunk_close" to "303131", "start_engine" to "303120", "stop_engine" to "303125")

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    fun configured(ctx: Context) = p(ctx).getString("ssk", "").let { !it.isNullOrEmpty() } &&
        !p(ctx).getString("uid", "").isNullOrEmpty() && !p(ctx).getString("tid", "").isNullOrEmpty()
    fun account(ctx: Context): String = p(ctx).getString("account", "") ?: ""
    /** 帳號密碼存著（密碼存 SHA-1，不存明文），用來自動重登。 */
    fun hasAuth(ctx: Context) = account(ctx).isNotEmpty() && !p(ctx).getString("pwsha", "").isNullOrEmpty()

    private var seq = (Math.random() * 9000).toInt()
    private fun sha1hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-1"); val b = md.digest(s.toByteArray(Charsets.ISO_8859_1))
        return b.joinToString("") { "%02X".format(it.toInt() and 255) }
    }
    private fun ssn(uid: String): String {
        val d = java.util.Calendar.getInstance(); fun z(n: Int) = "%02d".format(n)
        val t = "%02d%s%s%s%s%s".format(d.get(java.util.Calendar.YEAR) % 100, z(d.get(java.util.Calendar.MONTH) + 1), z(d.get(java.util.Calendar.DAY_OF_MONTH)),
            z(d.get(java.util.Calendar.HOUR_OF_DAY)), z(d.get(java.util.Calendar.MINUTE)), z(d.get(java.util.Calendar.SECOND)))
        seq = (seq + 1) % 10000
        return "${uid.ifEmpty { "0" }}$t%04d".format(seq)
    }

    private fun sysHead(code: String, uid: String): JSONObject {
        val d = java.util.Calendar.getInstance(); fun z(n: Int) = "%02d".format(n)
        return JSONObject().put("ver", "1.0").put("code", code).put("consumer_ssn", ssn(uid))
            .put("trans_date", "%d%s%s".format(d.get(java.util.Calendar.YEAR), z(d.get(java.util.Calendar.MONTH) + 1), z(d.get(java.util.Calendar.DAY_OF_MONTH))))
            .put("trans_time", "%s%s%s".format(z(d.get(java.util.Calendar.HOUR_OF_DAY)), z(d.get(java.util.Calendar.MINUTE)), z(d.get(java.util.Calendar.SECOND))))
            .put("chnl", "android").put("build_make", "Google").put("build_model", android.os.Build.MODEL).put("build_release", android.os.Build.VERSION.RELEASE)
            .put("aver", "1.1.15").put("caver", "").put("skin", "").put("csver", "").put("lver", "").put("distribute", "cyhy2")
    }

    /** 送一包到雲端，回應的 JSONObject（含 sys_head/body）或丟錯。 */
    private fun send(obj: JSONObject, txnkey: String): JSONObject {
        val json = obj.toString()
        val sig = if (txnkey.isEmpty()) "FFFFFFFF" else try {
            val mac = Mac.getInstance("HmacSHA1"); mac.init(SecretKeySpec(txnkey.toByteArray(), "HmacSHA1"))
            mac.doFinal(json.toByteArray()).joinToString("") { "%02X".format(it.toInt() and 255) }.let { if (it.length == 40) it.substring(0, 8) else "FFFFFFFF" }
        } catch (_: Throwable) { "FFFFFFFF" }
        val body = (json + sig).toByteArray(Charsets.UTF_8)
        val payload = ("%08d".format(body.size)).toByteArray() + body
        var last = "連不上雲端"
        for (h in hosts) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(h, port), 5000); s.soTimeout = 8000
                    s.getOutputStream().apply { write(payload); flush() }
                    val ins = s.getInputStream()
                    val hdr = ByteArray(8); var r = 0; while (r < 8) { val n = ins.read(hdr, r, 8 - r); if (n < 0) break; r += n }
                    val need = String(hdr).trim().toIntOrNull() ?: throw IllegalStateException("回應異常")
                    val buf = ByteArray(need.coerceIn(0, 2_000_000)); r = 0; while (r < buf.size) { val n = ins.read(buf, r, buf.size - r); if (n < 0) break; r += n }
                    return JSONObject(String(buf, 0, r, Charsets.UTF_8))
                }
            } catch (e: Throwable) { last = e.message ?: e.javaClass.simpleName }
        }
        throw IllegalStateException(last)
    }

    /** 登入；成功存憑證回 null，失敗回錯誤訊息。[remember] 存帳號與 SHA-1 密碼供自動重登。 */
    fun login(ctx: Context, account: String, password: String, remember: Boolean = true): String? {
        return try {
            val pwsha = sha1hex(password)
            val req = JSONObject().put("sys_head", sysHead("10001003", ""))
                .put("app_head", JSONObject().put("uid", "").put("ssk", "").put("tid", "").put("lang", "en").put("timezone", "28800"))
                .put("body", JSONObject().put("name", account.trim()).put("paswd", pwsha).put("agreement", "1")
                    .put("timezone", "28800").put("pushid", "").put("pushchnl", ""))
            val resp = send(req, "")
            val sh = resp.optJSONObject("sys_head"); val b = resp.optJSONObject("body")
            if (sh?.optString("ret_code") != "000000" || b == null) return sh?.optString("ret_msg")?.ifEmpty { "登入失敗" } ?: "登入失敗（帳號或密碼錯？）"
            val sa = b.optJSONObject("srvauth")
            val tid = b.optJSONArray("dev")?.optJSONObject(0)?.optString("tid")?.takeIf { it.isNotEmpty() } ?: b.optString("uname")
            val e = p(ctx).edit()
                .putString("ssk", b.optString("ssk")).putString("uid", b.optString("uid")).putString("tid", tid)
                .putString("txnkey", b.optString("txnkey")).putLong("at", System.currentTimeMillis())
            sa?.let { e.putString("ip", it.optString("ip")).putString("domain", it.optString("domain")).putInt("portcmd", it.optInt("portcmd", 6050)) }
            if (remember) e.putString("account", account.trim()).putString("pwsha", pwsha)
            e.apply()
            sa?.let { hosts = listOfNotNull(it.optString("domain").ifEmpty { null }, it.optString("domain2").ifEmpty { null }, it.optString("ip").ifEmpty { null }).ifEmpty { hosts }; port = it.optInt("portcmd", 6050) }
            null
        } catch (t: Throwable) { t.message ?: "登入失敗" }
    }

    /** 憑證過期時用記住的帳密自動重登。回傳是否成功。 */
    private fun relogin(ctx: Context): Boolean {
        val acc = account(ctx); val pwsha = p(ctx).getString("pwsha", "") ?: ""
        if (acc.isEmpty() || pwsha.isEmpty()) return false
        // 用已存的 SHA-1 密碼直接登入（login() 會再 SHA-1 一次，所以這裡自己組）
        return try {
            val req = JSONObject().put("sys_head", sysHead("10001003", ""))
                .put("app_head", JSONObject().put("uid", "").put("ssk", "").put("tid", "").put("lang", "en").put("timezone", "28800"))
                .put("body", JSONObject().put("name", acc).put("paswd", pwsha).put("agreement", "1").put("timezone", "28800").put("pushid", "").put("pushchnl", ""))
            val resp = send(req, ""); val b = resp.optJSONObject("body") ?: return false
            if (resp.optJSONObject("sys_head")?.optString("ret_code") != "000000") return false
            val sa = b.optJSONObject("srvauth")
            val tid = b.optJSONArray("dev")?.optJSONObject(0)?.optString("tid")?.takeIf { it.isNotEmpty() } ?: b.optString("uname")
            p(ctx).edit().putString("ssk", b.optString("ssk")).putString("uid", b.optString("uid")).putString("tid", tid).putString("txnkey", b.optString("txnkey")).putLong("at", System.currentTimeMillis()).apply()
            true
        } catch (_: Throwable) { false }
    }

    /** 送一個指令碼（帶 ssk/uid/tid）；回 (ok, 訊息)。憑證過期自動重登一次再試。 */
    private fun cmd(ctx: Context, code: String, extraBody: JSONObject = JSONObject(), retry: Boolean = true): Pair<Boolean, String> {
        val pr = p(ctx); val ssk = pr.getString("ssk", "") ?: ""; val uid = pr.getString("uid", "") ?: ""; val tid = pr.getString("tid", "") ?: ""; val txn = pr.getString("txnkey", "") ?: ""
        if (ssk.isEmpty() || uid.isEmpty() || tid.isEmpty()) return false to "還沒登入亮波"
        val body = JSONObject(extraBody.toString()).put("tid", tid)
        val req = JSONObject().put("sys_head", sysHead(code, uid))
            .put("app_head", JSONObject().put("uid", uid).put("ssk", ssk).put("lang", "en").put("timezone", "28800").put("devcfg", "").put("appcfg", "").put("skin", ""))
            .put("body", body)
        return try {
            val resp = send(req, txn); val sh = resp.optJSONObject("sys_head")
            val rc = sh?.optString("ret_code") ?: ""
            if (rc == "000000") true to (sh?.optString("ret_msg")?.ifEmpty { "成功" } ?: "成功")
            else {
                // 憑證失效（SExxxx / 驗證類）→ 自動重登一次再試
                if (retry && rc.startsWith("SE") && relogin(ctx)) cmd(ctx, code, extraBody, retry = false)
                else false to (sh?.optString("ret_msg")?.ifEmpty { rc } ?: rc)
            }
        } catch (t: Throwable) { false to (t.message ?: "送出失敗") }
    }

    fun control(ctx: Context, action: String): Pair<Boolean, String> {
        val code = CODES[action] ?: return false to "不允許的指令"
        return cmd(ctx, code, retry = true)
    }

    /** 車況（碼 203101）；回解析後的 JSON 字串，或 null。 */
    fun status(ctx: Context): String? {
        val pr = p(ctx); val ssk = pr.getString("ssk", "") ?: ""; val uid = pr.getString("uid", "") ?: ""; val tid = pr.getString("tid", "") ?: ""; val txn = pr.getString("txnkey", "") ?: ""
        if (ssk.isEmpty()) return null
        val req = JSONObject().put("sys_head", sysHead("203101", uid))
            .put("app_head", JSONObject().put("uid", uid).put("ssk", ssk).put("lang", "en").put("timezone", "28800").put("devcfg", "").put("appcfg", "").put("skin", ""))
            .put("body", JSONObject().put("tid", tid).put("rows", org.json.JSONArray().put(JSONObject().put("tid", tid))))
        return try {
            val resp = send(req, txn)
            if (resp.optJSONObject("sys_head")?.optString("ret_code") != "000000") { if (relogin(ctx)) return status(ctx) else return null }
            val row = resp.optJSONObject("body")?.optJSONArray("rows")?.optJSONObject(0) ?: resp.optJSONObject("body")
            row?.let {
                JSONObject().put("speed", it.optDouble("speed", 0.0)).put("volt", it.optString("volt")).put("temp", it.optString("temp"))
                    .put("fuel", it.optString("fuel_avg").ifEmpty { it.optString("fuel") }).put("mileage", it.optString("mileage"))
                    .put("lat", it.optDouble("lat", 0.0)).put("lng", it.optDouble("lng", 0.0))
                    .put("gsmol", it.optString("gsmol")).put("gpsol", it.optString("gpsol")).put("vst", it.optString("vst")).toString()
            }
        } catch (_: Throwable) { null }
    }

    fun logout(ctx: Context) { p(ctx).edit().clear().apply() }

    /** 給 relay 用的憑證（登入後推過去，讓 MCP／中控台也新鮮）。 */
    fun creds(ctx: Context): JSONObject? {
        if (!configured(ctx)) return null
        val pr = p(ctx)
        return JSONObject().put("ssk", pr.getString("ssk", "")).put("uid", pr.getString("uid", "")).put("tid", pr.getString("tid", "")).put("txnkey", pr.getString("txnkey", ""))
    }
}
