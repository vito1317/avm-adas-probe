package com.vito.avmprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import org.json.JSONObject

/**
 * 跟車機桌面（中控台，com.vito.navilauncher）連動：
 * - 桌面開著時每 2 秒送「我在看」（ALIVE）→ 引擎在背景也繼續跑偵測（平常只有 360 畫面開著或行車記錄器在錄才跑）。
 * - 每一幀偵測結果（跟 3D 面板同一個格式）每秒最多 5 次廣播給桌面，桌面的道路畫面直接拿去畫。
 * - 桌面送來的指令：氛圍燈顏色、後擋看板試播。只接受這些非敏感的指令（遠端模組的鎖車、車窗不經過這裡）。
 */
object LauncherLink {
    const val PKG = "com.vito.navilauncher"   // 中控台（套件名稱含 navi 才不會被車機擋成「行車中請勿觀看影片」）
    const val ACTION_STATE = "com.vito.adas.STATE"
    const val ACTION_CMD = "com.vito.adas.LAUNCHER_CMD"
    private const val ALIVE_MS = 6000L
    private const val MIN_GAP_MS = 200L

    @Volatile private var aliveAt = 0L
    @Volatile private var lastSent = 0L
    private var registered = false
    private var app: Context? = null

    /** 桌面最近 6 秒內有送心跳。 */
    fun watching(): Boolean = SystemClock.elapsedRealtime() - aliveAt < ALIVE_MS

    fun start(ctx: Context) {
        if (registered) return
        registered = true
        app = ctx.applicationContext
        // 車況轉送：中控台開著時每 0.3 秒送一次（中控台不自己去讀車機 CAN——兩邊同時呼叫主程式的 CAN 原生程式會讓主程式閃退）
        val h = android.os.Handler(android.os.HandlerThread("launcher-car").apply { start() }.looper)
        h.post(object : Runnable {
            override fun run() {
                if (watching()) sendCar()
                h.postDelayed(this, 300)
            }
        })
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val cmd = i?.getStringExtra("cmd") ?: return
                val arg = i.getStringExtra("arg") ?: ""
                try { handle(cmd, arg) } catch (t: Throwable) { Engine360.log("桌面指令失敗：$t") }
            }
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) app!!.registerReceiver(rx, IntentFilter(ACTION_CMD), Context.RECEIVER_EXPORTED)
            else app!!.registerReceiver(rx, IntentFilter(ACTION_CMD))
        } catch (t: Throwable) { Engine360.log("桌面連動接不上：$t") }
    }

    private fun handle(cmd: String, arg: String) {
        when (cmd) {
            "alive" -> { aliveAt = SystemClock.elapsedRealtime(); sendAcc() }
            // 中控台有車控指令：驗過中控台的身分（套件名＋簽章）才去它的待辦清單拿
            "poll" -> pollCommands()
            // 氛圍燈：全部變成某個顏色 2 秒（測試用）
            "ambient" -> arg.toIntOrNull(16)?.let { AmbientLight.test(AmbientProtocol.ZONE_ALL, it) }
            // 後擋看板：播第幾則
            "rear" -> arg.toIntOrNull()?.let { RearDisplay.test(it) }
            // 桌面的「道路畫面」卡片：打開 360 並切到道路畫面
            "roadview" -> Engine360.showRoad(true)
            // 中控台 3D 道路畫面點了停車格（只是選格子、畫路徑，不碰車）
            "slot_tap" -> arg.split(',').mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 2 }?.let { Engine360.tapSlot(it[0], it[1]) }
            else -> {}
        }
    }

    // ───── 車控指令（車窗、車鎖、氛圍燈、看板）─────
    private const val AUTHORITY = "com.vito.navilauncher.cmd"
    /** 中控台 APK 的簽章（SHA-256）。只有用這把金鑰簽的中控台送來的車控指令才執行。 */
    private const val LAUNCHER_CERT = "fa5736a4d084e599e2b5bb75d0b5b5e916c3264283065274a06673394c206e92"

    private fun trustedLauncher(c: Context): Boolean = try {
        val pm = c.packageManager
        val owner = pm.resolveContentProvider(AUTHORITY, 0)?.packageName
        if (owner != PKG) false else {
            @Suppress("DEPRECATION")
            val sigs = if (android.os.Build.VERSION.SDK_INT >= 28)
                pm.getPackageInfo(PKG, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
            else pm.getPackageInfo(PKG, android.content.pm.PackageManager.GET_SIGNATURES).signatures
            val md = java.security.MessageDigest.getInstance("SHA-256")
            sigs?.any { s -> md.digest(s.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) } == LAUNCHER_CERT } == true
        }
    } catch (_: Throwable) { false }

    private fun pollCommands() {
        val c = app ?: return
        Thread {
            if (!trustedLauncher(c)) { Engine360.log("車控指令：中控台身分不符，不執行"); return@Thread }
            val list = try {
                org.json.JSONArray(c.contentResolver.call(android.net.Uri.parse("content://$AUTHORITY"), "take", null, null)?.getString("cmds") ?: "[]")
            } catch (t: Throwable) { Engine360.log("拿不到車控指令：$t"); return@Thread }
            for (k in 0 until list.length()) {
                try {
                    val j = JSONObject(list.getString(k))
                    runControl(j.optString("cmd"), j.optString("arg"))
                } catch (t: Throwable) { Engine360.log("車控指令失敗：$t") }
            }
            sendAcc()
        }.start()
    }

    /** 只接受這些：遠端模組白名單（CyProtocol.Cmd，本來就送不出發動、斷油）、氛圍燈、看板、道路畫面。 */
    private fun runControl(cmd: String, arg: String) {
        when (cmd) {
            "remote" -> CyProtocol.Cmd.values().firstOrNull { it.name == arg }?.let { CyRemote.send(it) }
            "ambient_color" -> arg.toIntOrNull(16)?.let { AmbientLight.setColor(it) }
            "ambient_power" -> AmbientLight.power(arg == "1")
            "ambient_mode" -> AmbientLight.setMode(arg)
            "ambient_bright" -> arg.toIntOrNull()?.let { AmbientLight.brightness(it) }
            "rear_play" -> arg.toIntOrNull()?.let { RearDisplay.test(it) }
            "rear_idle" -> RearDisplay.resume()
            "roadview" -> Engine360.showRoad(true)
        }
        Engine360.log("中控台車控：$cmd $arg")
    }

    /** 車況：車機 CAN 的整個陣列（中控台用同一套解碼）、原廠車門、方向燈、檔位、車速。 */
    private fun sendCar() {
        val c = app ?: return
        try {
            val j = JSONObject()
            CanCarInfo.last?.takeIf { CanCarInfo.fresh() || CanCarInfo.status.startsWith("CAN") }?.let { a ->
                j.put("can", org.json.JSONArray().apply { for (v in a) put(v) })
            }
            FactoryVehicle.doorArray()?.let { d -> j.put("doors", org.json.JSONArray().apply { for (v in d) put(v) }) }
            val vs = VehicleBus.signals.snapshot()
            vs.speedKmh?.let { j.put("speed", it.toDouble()) }
            vs.turn?.let { j.put("turn", it.toString()) }
            (CanVehicle.info.gear ?: vs.gear)?.let { j.put("gear", it.toString()) }
            j.put("canStatus", CanCarInfo.status)
            c.sendBroadcast(Intent("com.vito.adas.CAR").setPackage(PKG).putExtra("json", j.toString()))
        } catch (_: Throwable) {}
    }

    /** 配件狀態回報給中控台。 */
    private fun sendAcc() {
        val c = app ?: return
        try {
            val p = AppPrefs(c)
            val j = JSONObject()
                .put("remote", CyRemote.status).put("remoteReady", p.cyKey.length == 32 && p.cyMac.isNotEmpty())
                .put("ambient", AmbientLight.status).put("ambientOn", p.ambientOn)
                .put("rear", RearDisplay.status).put("rearOn", p.rdOn)
            c.sendBroadcast(Intent("com.vito.adas.ACC").setPackage(PKG).putExtra("json", j.toString()))
        } catch (_: Throwable) {}
    }

    /** 每幀偵測結果：桌面在看才送，每秒最多 5 次。 */
    fun onPayload(p: JSONObject) {
        if (!watching()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSent < MIN_GAP_MS) return
        lastSent = now
        val c = app ?: return
        try {
            val vs = VehicleBus.signals.snapshot()
            val j = JSONObject(p.toString())           // 複製一份，加上車身訊號（桌面沒有 CAN 時用）
            vs.speedKmh?.let { j.put("speed", it.toDouble()) }
            vs.gear?.let { j.put("gear", it.toString()) }
            vs.turn?.let { j.put("turn", it.toString()) }
            c.sendBroadcast(Intent(ACTION_STATE).setPackage(PKG).putExtra("json", j.toString()))
        } catch (t: Throwable) { Engine360.log("送給桌面失敗：$t") }
    }
}
