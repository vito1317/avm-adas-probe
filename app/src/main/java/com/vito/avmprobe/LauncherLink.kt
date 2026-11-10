package com.vito.avmprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import org.json.JSONObject

/**
 * 跟車機桌面（com.vito.avmlauncher）連動：
 * - 桌面開著時每 2 秒送「我在看」（ALIVE）→ 引擎在背景也繼續跑偵測（平常只有 360 畫面開著或行車記錄器在錄才跑）。
 * - 每一幀偵測結果（跟 3D 面板同一個格式）每秒最多 5 次廣播給桌面，桌面的道路畫面直接拿去畫。
 * - 桌面送來的指令：氛圍燈顏色、後擋看板試播。只接受這些非敏感的指令（遠端模組的鎖車、車窗不經過這裡）。
 */
object LauncherLink {
    const val PKG = "com.vito.avmlauncher"
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
            "alive" -> aliveAt = SystemClock.elapsedRealtime()
            // 氛圍燈：全部變成某個顏色 2 秒（測試用）
            "ambient" -> arg.toIntOrNull(16)?.let { AmbientLight.test(AmbientProtocol.ZONE_ALL, it) }
            // 後擋看板：播第幾則
            "rear" -> arg.toIntOrNull()?.let { RearDisplay.test(it) }
            // 桌面的「道路畫面」卡片：打開 360 並切到道路畫面
            "roadview" -> Engine360.showRoad(true)
            else -> {}
        }
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
