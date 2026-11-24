package com.vito.avmphone

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 遠端 AI（MCP）：手機在車上當中繼——用 WebSocket 連到 Spark 上的 car-mcp relay（照 android-control 的架構），
 * relay 收到 AI 的工具呼叫就轉給這裡，這裡再去問車機（中控台，熱點區網）後回傳。
 * 轉發：車況、360 偵測、360 畫面、手機定位、搜尋目的地、開始／結束導航；車控（車窗、車鎖、尾箱、空調、氛圍燈、看板）
 * 只有手機上打開「允許 AI 遠端控制」、而且跟中控台用 PIN 配對過才會送，中控台還會再檢查白名單。發動、熄火、斷油一律沒有。
 * 前景服務＋通知（Android 規定），手機鎖螢幕也繼續連。
 */
class RelayService : Service() {
    companion object {
        const val DEFAULT_SERVER = "https://car-mcp.vito1317.com"
        private const val CH = "relay"
        @Volatile var state = "未啟動"; private set
        @Volatile var running = false; private set
        @Volatile var lastCallAt = 0L; private set
        @Volatile var lastCall = ""; private set

        fun prefs(ctx: Context) = ctx.getSharedPreferences("relay", Context.MODE_PRIVATE)
        fun paired(ctx: Context) = !prefs(ctx).getString("token", null).isNullOrEmpty()
        fun start(ctx: Context) {
            val i = Intent(ctx, RelayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, RelayService::class.java)) }

        /** 用網頁控制台給的配對碼換裝置 token（存起來）。回傳錯誤訊息，成功回 null。 */
        fun pair(ctx: Context, server: String, code: String, name: String): String? {
            val base = server.trim().trimEnd('/').ifEmpty { DEFAULT_SERVER }
            if (!base.startsWith("https://")) return "伺服器要用 https://"
            return try {
                val c = URL("$base/api/device-pair").openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 10000; c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(JSONObject().put("code", code.trim()).put("device_name", name.trim().ifEmpty { "E60" }).toString().toByteArray()) }
                val ok = c.responseCode in 200..299
                val body = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                if (!ok) return try { JSONObject(body).optString("error", "HTTP ${c.responseCode}") } catch (_: Throwable) { "HTTP ${c.responseCode}" }
                val j = JSONObject(body)
                prefs(ctx).edit().putString("server", base).putString("token", j.getString("device_token"))
                    .putString("name", j.optString("device_name", name)).putString("user", j.optString("user")).putBoolean("on", true).apply()
                null
            } catch (t: Throwable) { "連不上伺服器：${t.message ?: t.javaClass.simpleName}" }
        }
        fun unpair(ctx: Context) { stop(ctx); prefs(ctx).edit().remove("token").putBoolean("on", false).apply() }
    }

    private val http = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val work = Executors.newFixedThreadPool(3)
    private lateinit var h: Handler
    private var ws: WebSocket? = null
    private var backoff = 2000L
    @Volatile private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "遠端 AI 連線", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(android.R.drawable.stat_sys_upload_done).setContentTitle("遠端 AI 可以查看車況")
            .setContentText("透過 car-mcp 轉發：車況、360、導航（不能控制車子）").setContentIntent(pi).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(2, n)
        if (!running) {
            running = true; stopped = false
            CarLink.start(this)
            h = Handler(HandlerThread("relay").apply { start() }.looper)
            h.post { connect() }
        }
        return START_STICKY
    }

    private fun connect() {
        if (stopped) return
        val p = prefs(this)
        val token = p.getString("token", null)
        if (token.isNullOrEmpty()) { state = "還沒配對"; return }
        val base = p.getString("server", DEFAULT_SERVER)!!
        state = "連線中…"
        val req = Request.Builder().url(base.replaceFirst("https://", "wss://") + "/device").header("Authorization", "Bearer $token").build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                backoff = 2000L; state = "已連線"
                hello(webSocket)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = try { JSONObject(text) } catch (_: Throwable) { return }
                if (!m.has("id")) return
                work.execute { handle(webSocket, m) }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { retry(code, reason) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                retry(response?.code ?: 0, t.message ?: t.javaClass.simpleName)
            }
        })
    }

    private fun retry(code: Int, why: String) {
        if (stopped) return
        state = when (code) { 401 -> "配對失效，請重新配對"; 4001 -> "已被撤銷，請重新配對"; 4000 -> "同名裝置在別處登入"; else -> "斷線，${backoff / 1000} 秒後重連（$why）" }
        if (code == 401 || code == 4001) return
        h.postDelayed({ connect() }, backoff)
        backoff = (backoff * 2).coerceAtMost(60_000L)
    }

    private fun hello(w: WebSocket) {
        val info = JSONObject().put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("app", try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Throwable) { "" })
            .put("carIp", if (CarLink.seen()) CarLink.ip else JSONObject.NULL).put("carName", CarLink.name)
        w.send(JSONObject().put("type", "hello").put("info", info).toString())
        h.postDelayed({ if (ws === w && state == "已連線") hello(w) }, 30_000)
    }

    private fun handle(w: WebSocket, m: JSONObject) {
        val id = m.get("id"); val method = m.optString("method"); val p = m.optJSONObject("params") ?: JSONObject()
        lastCallAt = System.currentTimeMillis(); lastCall = method
        val out = JSONObject().put("id", id)
        try { out.put("result", call(method, p)) } catch (t: Throwable) { out.put("error", t.message ?: t.javaClass.simpleName) }
        w.send(out.toString())
    }

    private fun carJson(path: String): Any {
        val t = CarLink.get(path) ?: throw IllegalStateException("車機沒有回應（手機要連著車機的熱點、中控台 2.0 以上要開著）")
        return if (t.trim() == "null") JSONObject.NULL else if (t.trimStart().startsWith("[")) JSONArray(t) else JSONObject(t)
    }

    @SuppressLint("MissingPermission")
    private fun lastLoc(): android.location.Location? = try {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        listOf("gps", "network", "passive").mapNotNull { pv -> try { lm.getLastKnownLocation(pv) } catch (_: Throwable) { null } }.maxByOrNull { it.time }
    } catch (_: Throwable) { null }

    private fun call(method: String, p: JSONObject): Any = when (method) {
        "status" -> carJson("/status")
        "scene" -> carJson("/scene")
        "route" -> carJson("/route")
        "camera" -> {
            // 第一次要：車機收到請求才開始產生影像 → 最多等 8 秒
            val view = p.optString("view", "top")
            val q = listOfNotNull(if (view != "top") "real=1" else null, if (view != "panorama") "ground=1" else null).joinToString("&")
            val until = SystemClock.elapsedRealtime() + 8000
            var res = JSONObject()
            while (true) {
                val sc = carJson("/scene?$q") as? JSONObject ?: JSONObject()
                val g = sc.optString("ground").takeIf { it.startsWith("data:image") }
                val b = sc.optString("bowl").takeIf { it.startsWith("data:image") }
                res = JSONObject().put("ground", g ?: JSONObject.NULL).put("bowl", b ?: JSONObject.NULL)
                val done = (view == "top" && g != null) || (view == "panorama" && b != null) || (view == "both" && g != null && b != null)
                if (done || SystemClock.elapsedRealtime() > until) break
                Thread.sleep(700)
            }
            res
        }
        "location" -> {
            val l = lastLoc() ?: throw IllegalStateException("手機沒有定位（要允許「位置」權限）")
            JSONObject().put("lat", l.latitude).put("lon", l.longitude).put("acc", l.accuracy.toDouble())
                .put("age", (System.currentTimeMillis() - l.time) / 1000.0)
                .put("spd", if (l.hasSpeed()) l.speed.toDouble() else JSONObject.NULL)
                .put("hdg", if (l.hasBearing()) l.bearing.toDouble() else JSONObject.NULL)
        }
        "search" -> {
            val q = p.optString("query").trim()
            if (q.isEmpty()) throw IllegalArgumentException("query required")
            val l = lastLoc()
            val (list, street) = Geocode.search(q, l?.latitude, l?.longitude)
            val a = JSONArray()
            for (pl in list) {
                val r = JSONObject().put("name", pl.name).put("addr", if (street) "（找不到門牌，這條路）" + pl.addr else pl.addr).put("lat", pl.lat).put("lon", pl.lon)
                if (l != null) { val d = FloatArray(1); android.location.Location.distanceBetween(l.latitude, l.longitude, pl.lat, pl.lon, d); r.put("dist", d[0].toDouble()) }
                a.put(r)
            }
            // 近的排前面（同樣好的結果）
            val sorted = (0 until a.length()).map { a.getJSONObject(it) }.sortedBy { it.optDouble("dist", 1e12) }
            JSONArray(sorted)
        }
        "nav_to" -> {
            val body = JSONObject().put("name", p.optString("name", "目的地")).put("lat", p.getDouble("lat")).put("lon", p.getDouble("lon"))
            CarLink.post("/nav", body.toString()) ?: throw IllegalStateException("送不到車機")
            JSONObject().put("ok", true)
        }
        "control" -> {
            // AI 遠端車控：手機上要另外打開「允許 AI 控制」，而且要先跟中控台配對
            if (!prefs(this).getBoolean("allowControl", false)) throw IllegalStateException("手機 App 沒有開「允許 AI 遠端控制」")
            CarLink.control(p.optString("cmd"), p.optString("arg"))?.let { throw IllegalStateException(it) }
            JSONObject().put("ok", true)
        }
        "nav_stop" -> { CarLink.post("/nav/stop", "{}") ?: throw IllegalStateException("送不到車機"); JSONObject().put("ok", true) }
        else -> throw IllegalArgumentException("unknown method $method")
    }

    override fun onDestroy() {
        stopped = true; running = false; state = "已關閉"
        try { ws?.close(1000, "bye") } catch (_: Throwable) {}
        try { h.looper.quitSafely() } catch (_: Throwable) {}
        work.shutdownNow()
        super.onDestroy()
    }
}
