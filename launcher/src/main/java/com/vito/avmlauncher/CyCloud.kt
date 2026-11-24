package com.vito.avmlauncher

import android.content.SharedPreferences
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * 亮波（hyll / cyhy）雲端遠端控制——中控台版。
 *
 * 車上的遠端模組（CY103）有車聯網（SIM），指令走 cyhy 雲端直達，車熄火、模組不在藍牙範圍也能發。
 * 協定由官方 App 反組譯：TCP（6050，明文）送 `%08d`(長度)+JSON+8 碼；JSON 用 ssk/uid/tid 認。
 *
 * 憑證（ssk/uid/tid）＝車鑰匙：存在本機 [AppPrefs]（使用者自己在設定輸入），不寫死在 APK、不外傳。
 * 只收白名單指令碼；發動／熄火由上層（車控面板）確認 ＋ 車速攔截後才呼叫。
 */
object CyCloud {
    val CODES = mapOf(
        "lock" to "303111", "unlock" to "303117",
        "windows_up" to "303132", "windows_down" to "303133",
        "trunk_open" to "303130", "trunk_close" to "303131",
        "start_engine" to "303120", "stop_engine" to "303125",
    )

    private var seq = SecureRandom().nextInt(9000)

    private fun g(p: SharedPreferences, k: String) = p.getString(k, "") ?: ""
    fun configured(p: SharedPreferences) = g(p, "cyhySsk").isNotEmpty() && g(p, "cyhyUid").isNotEmpty() && g(p, "cyhyTid").isNotEmpty()

    private fun ssn(uid: String): String {
        val d = java.util.Calendar.getInstance()
        fun z(n: Int) = "%02d".format(n)
        val t = "%02d%s%s%s%s%s".format(d.get(java.util.Calendar.YEAR) % 100, z(d.get(java.util.Calendar.MONTH) + 1),
            z(d.get(java.util.Calendar.DAY_OF_MONTH)), z(d.get(java.util.Calendar.HOUR_OF_DAY)),
            z(d.get(java.util.Calendar.MINUTE)), z(d.get(java.util.Calendar.SECOND)))
        seq = (seq + 1) % 1000000
        return "$uid$t%04d".format(seq)
    }

    private fun frame(p: SharedPreferences, code: String): ByteArray {
        val d = java.util.Calendar.getInstance()
        fun z(n: Int) = "%02d".format(n)
        val date = "%d%s%s".format(d.get(java.util.Calendar.YEAR), z(d.get(java.util.Calendar.MONTH) + 1), z(d.get(java.util.Calendar.DAY_OF_MONTH)))
        val time = "%s%s%s".format(z(d.get(java.util.Calendar.HOUR_OF_DAY)), z(d.get(java.util.Calendar.MINUTE)), z(d.get(java.util.Calendar.SECOND)))
        val o = JSONObject()
        o.put("sys_head", JSONObject().put("ver", "1.0").put("code", code).put("consumer_ssn", ssn(g(p, "cyhyUid")))
            .put("trans_date", date).put("trans_time", time).put("chnl", "android")
            .put("build_make", "head-unit").put("build_model", "E60").put("build_release", "")
            .put("distribute", "cyhy2").put("aver", "1.1.15").put("caver", "").put("skin", "").put("csver", "").put("lver", ""))
        o.put("app_head", JSONObject().put("lang", "zh").put("ssk", g(p, "cyhySsk")).put("uid", g(p, "cyhyUid"))
            .put("timezone", "8").put("devcfg", "").put("appcfg", "").put("skin", ""))
        o.put("body", JSONObject().put("tid", g(p, "cyhyTid")))
        val json = o.toString()
        val sig = g(p, "cyhyTxnkey").takeIf { it.isNotEmpty() }?.let {
            val mac = javax.crypto.Mac.getInstance("HmacSHA1")
            mac.init(javax.crypto.spec.SecretKeySpec(it.toByteArray(), "HmacSHA1"))
            mac.doFinal(json.toByteArray()).joinToString("") { b -> "%02X".format(b.toInt() and 255) }.substring(0, 8)
        } ?: "00000000"
        return (json + sig).toByteArray(Charsets.UTF_8)
    }

    /** 送一個白名單動作。回傳 (ok, 訊息)。要在背景執行緒呼叫。 */
    fun send(p: SharedPreferences, action: String): Pair<Boolean, String> {
        val code = CODES[action] ?: return false to "不允許的指令"
        if (!configured(p)) return false to "還沒設定亮波憑證（設定 → 遠端模組雲端）"
        val body = frame(p, code)
        val payload = ("%08d".format(body.size)).toByteArray() + body
        val hosts = (g(p, "cyhyHosts").ifEmpty { "s1.cyhycdd.com,s2.cyhycdd.com" }).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        var lastErr = "連不上雲端"
        for (h in hosts) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(h, p.getInt("cyhyPort", 6050)), 4000); s.soTimeout = 6000
                    s.getOutputStream().apply { write(payload); flush() }
                    val ins = s.getInputStream()
                    val hdr = ByteArray(8); var r = 0; while (r < 8) { val n = ins.read(hdr, r, 8 - r); if (n < 0) break; r += n }
                    val need = String(hdr).trim().toIntOrNull() ?: 0
                    val buf = ByteArray(need.coerceIn(0, 100000)); r = 0; while (r < buf.size) { val n = ins.read(buf, r, buf.size - r); if (n < 0) break; r += n }
                    val j = try { JSONObject(String(buf, 0, r, Charsets.UTF_8)).optJSONObject("sys_head") } catch (_: Throwable) { null }
                    val rc = j?.optString("ret_code") ?: ""
                    return (rc == "000000") to (j?.optString("ret_msg")?.ifEmpty { rc } ?: rc)
                }
            } catch (e: Throwable) { lastErr = e.message ?: e.javaClass.simpleName }
        }
        return false to lastErr
    }
}
