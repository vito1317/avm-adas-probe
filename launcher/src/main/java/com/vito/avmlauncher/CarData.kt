package com.vito.avmlauncher

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * 車況來源：全部由 360 裡的 ADAS 引擎轉送（com.vito.adas.CAR，每 0.3 秒）。
 * 中控台**不自己去讀車機主程式的 CAN 服務**：兩個程式同時呼叫，主程式的 CAN 原生程式會撞在一起閃退
 * （實車：「MainApp 已停止運作」，之後兩邊都讀不到 CAN）。只讓引擎一個人讀，安全。
 * 另外引擎每幀廣播偵測結果（周圍的車、車道線、警示）→ 道路畫面用。
 * CAN 盒不一定每個欄位都給：看過非 0 才算有（永久記住），一直是 0 的欄位不顯示。
 */
class CarData(private val ctx: Context) {
    companion object {
        private const val DESCRIPTOR = "com.ts.can.carinfo.ICarInfoService"
        const val ACTION_STATE = "com.vito.adas.STATE"
        const val ACTION_CMD = "com.vito.adas.LAUNCHER_CMD"
        const val ACTION_ACC = "com.vito.adas.ACC"
        const val ACTION_CAR = "com.vito.adas.CAR"
        const val ENGINE_PKG = "com.baony.avm360"
    }

    @Volatile private var base: IntArray? = null
    @Volatile private var doors: IntArray? = null
    @Volatile private var baseAt = 0L
    @Volatile private var adas: JSONObject? = null
    @Volatile private var adasAt = 0L
    @Volatile var canStatus = "連線中"; private set
    @Volatile private var acc: JSONObject? = null
    @Volatile private var accAt = 0L
    private val sp = ctx.getSharedPreferences("car_fields", Context.MODE_PRIVATE)
    private val seen = HashSet<Int>().apply { (sp.getString("seen", "") ?: "").split(',').mapNotNull { it.toIntOrNull() }.forEach { add(it) } }
    private val h = Handler(HandlerThread("car-data").apply { start() }.looper)

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == ACTION_CAR) { i.getStringExtra("json")?.let { onCar(it) }; return }
            if (i?.action == ACTION_ACC) { i.getStringExtra("json")?.let { try { acc = JSONObject(it); accAt = SystemClock.elapsedRealtime() } catch (_: Throwable) {} }; return }
            val s = i?.getStringExtra("json") ?: return
            try { adas = JSONObject(s); adasAt = SystemClock.elapsedRealtime() } catch (_: Throwable) {}
        }
    }

    fun start() {
        ctx.registerReceiver(rx, IntentFilter(ACTION_STATE).apply { addAction(ACTION_ACC); addAction(ACTION_CAR) })
    }

    @Volatile private var carSpeed: Double? = null
    @Volatile private var carTurn: String? = null
    @Volatile private var carGear: String? = null

    /** 引擎轉送的車況：CAN 陣列（跟引擎同一個來源）、原廠車門、車速、方向燈、檔位。 */
    private fun onCar(js: String) {
        try {
            val j = JSONObject(js)
            j.optJSONArray("can")?.let { arr ->
                val a = IntArray(arr.length()) { arr.optInt(it) }
                base = a; baseAt = SystemClock.elapsedRealtime()
                var changed = false
                for (i in a.indices) if (a[i] != 0 && seen.add(i)) changed = true
                if (changed) sp.edit().putString("seen", seen.joinToString(",")).apply()
            }
            doors = j.optJSONArray("doors")?.let { d -> IntArray(d.length()) { d.optInt(it) } }
            carSpeed = if (j.has("speed")) j.optDouble("speed") else null
            carTurn = j.optString("turn").takeIf { it.isNotEmpty() }
            carGear = j.optString("gear").takeIf { it.isNotEmpty() }
            canStatus = j.optString("canStatus", canStatus)
            carAt = SystemClock.elapsedRealtime()
        } catch (_: Throwable) {}
    }
    @Volatile private var carAt = 0L

    /** 給網頁的車況 JSON。 */
    fun json(): String {
        val o = JSONObject()
        val a = base
        val fresh = a != null && SystemClock.elapsedRealtime() - baseAt < 2000
        o.put("can", fresh); o.put("canStatus", if (SystemClock.elapsedRealtime() - carAt < 3000) canStatus else "等 360 送車況（開過 360 一次）")
        if (fresh && a != null) {
            fun g(i: Int) = if (i < a.size) a[i] else 0
            fun flag(i: Int): Any = if (i in seen) g(i) == 1 else JSONObject.NULL
            o.put("speed", g(2)); o.put("rpm", g(3))
            if (24 in seen) o.put("gear", when (g(24)) { 0 -> "P"; 1 -> "R"; 2, 3 -> "N"; 4 -> "D"; 5 -> "S"; 6 -> "M"; else -> "-" })
            o.put("brake", flag(22)); o.put("handbrake", flag(23)); o.put("belt", flag(19))
            o.put("lowBeam", flag(21)); o.put("highBeam", flag(20)); o.put("ill", flag(25))
            o.put("fogF", flag(27)); o.put("fogR", flag(28))
            o.put("turnL", g(17) == 1); o.put("turnR", g(18) == 1); o.put("hazard", g(26) == 1)
            if (g(13) in 1..2000) o.put("range", g(13))
            if (14 in seen) o.put("odo", g(14))
            if (4 in seen) o.put("water", g(4))
            if (68 in seen) o.put("oil", g(68))
            if (67 in seen) g(67).let { v -> o.put("out", when (v) { in -40..60 -> v.toDouble(); in 0..200 -> (v - 80) / 2.0; else -> JSONObject.NULL }) }
            g(15).let { v -> when (v) { in 9..17 -> o.put("volt", v); in 90..170 -> o.put("volt", v / 10.0); else -> {} } }
            if (30 in seen) o.put("fuel", g(30))
            if (12 in seen) o.put("instFuel", g(12))
            if (11 in seen) o.put("avgFuel", g(11))
            if (60 in seen) o.put("wiper", g(60))
        }
        // 原廠 360 的車門陣列（CAN 來的）：前 4 個當左前、右前、左後、右後，第 5、6 個當後車廂、引擎蓋
        doors?.takeIf { it.isNotEmpty() }?.let { d ->
            fun at(i: Int) = i < d.size && d[i] != 0
            o.put("doors", JSONObject().put("fl", at(0)).put("fr", at(1)).put("rl", at(2)).put("rr", at(3)).put("trunk", at(4)).put("hood", at(5)))
        }
        if (SystemClock.elapsedRealtime() - carAt < 2000) {
            if (!o.has("speed")) carSpeed?.let { o.put("speed", it) }
            if (!o.has("gear")) carGear?.let { o.put("gear", it) }
            when (carTurn) { "L" -> o.put("turnL", true); "R" -> o.put("turnR", true); "H" -> o.put("hazard", true) }
        }
        // ADAS 引擎（原廠 360 程式）廣播的偵測結果
        adas?.takeIf { SystemClock.elapsedRealtime() - adasAt < 3000 }?.let { j ->
            o.put("adas", j)
            // CAN 沒給車速時用引擎的（它可能是 GPS）
            if (!o.has("speed") && j.has("speed")) o.put("speed", j.optDouble("speed"))
            if (!o.has("gear") && j.has("gear")) o.put("gear", j.optString("gear"))
            if (j.optString("turn") == "L") o.put("turnL", true)
            if (j.optString("turn") == "R") o.put("turnR", true)
            if (j.optString("turn") == "H") o.put("hazard", true)
        }
        // 配件狀態（遠端模組、氛圍燈、看板）：引擎每次收到心跳／指令就回報
        acc?.takeIf { SystemClock.elapsedRealtime() - accAt < 10_000 }?.let { o.put("acc", it) }
        return o.toString()
    }

    /** 送指令給 ADAS 引擎（氛圍燈顏色、後擋看板）。只送非敏感的（不包含遠端模組）。 */
    fun engineCmd(cmd: String, arg: String) {
        try { ctx.sendBroadcast(Intent(ACTION_CMD).setPackage(ENGINE_PKG).putExtra("cmd", cmd).putExtra("arg", arg)) } catch (_: Throwable) {}
    }

    fun stop() { try { ctx.unregisterReceiver(rx) } catch (_: Throwable) {} }
}

/** 網頁用的已安裝 App 清單。 */
object AppList {
    fun json(ctx: Context): String {
        val pm = ctx.packageManager
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }.sortedBy { it.first }
        val a = JSONArray()
        for ((label, pkg) in list) a.put(JSONObject().put("label", label).put("pkg", pkg))
        return a.toString()
    }

    fun icon(ctx: Context, pkg: String): String = try {
        val d = ctx.packageManager.getApplicationIcon(pkg)
        val bmp = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp); d.setBounds(0, 0, 96, 96); d.draw(c)
        val bos = java.io.ByteArrayOutputStream(); bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
        "data:image/png;base64," + android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
    } catch (_: Throwable) { "" }
}
