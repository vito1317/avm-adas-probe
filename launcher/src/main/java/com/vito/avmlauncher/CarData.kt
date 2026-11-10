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
 * 車況來源：
 * 1. 車機主程式的 CAN 服務（com.ts.can.carinfo.CarInfoService，唯讀）：交易碼 6 = 車況（82 個欄位）、4 = 車門。
 *    跟 ADAS 引擎用同一個服務（反編譯確認過，只用這兩個唯讀的交易碼）。
 * 2. ADAS 引擎（在原廠 360 程式裡）每秒廣播的偵測結果（周圍的車、車道線、警示）→ 道路畫面用。
 * CAN 盒不一定每個欄位都給：看過非 0 才算有（永久記住），一直是 0 的欄位不顯示。
 */
class CarData(private val ctx: Context) {
    companion object {
        private const val DESCRIPTOR = "com.ts.can.carinfo.ICarInfoService"
        const val ACTION_STATE = "com.vito.adas.STATE"
        const val ACTION_CMD = "com.vito.adas.LAUNCHER_CMD"
        const val ENGINE_PKG = "com.baony.avm360"
    }

    @Volatile private var binder: IBinder? = null
    @Volatile private var base: IntArray? = null
    @Volatile private var doors: IntArray? = null
    @Volatile private var baseAt = 0L
    @Volatile private var adas: JSONObject? = null
    @Volatile private var adasAt = 0L
    @Volatile var canStatus = "連線中"; private set
    private val sp = ctx.getSharedPreferences("car_fields", Context.MODE_PRIVATE)
    private val seen = HashSet<Int>().apply { (sp.getString("seen", "") ?: "").split(',').mapNotNull { it.toIntOrNull() }.forEach { add(it) } }
    private val h = Handler(HandlerThread("car-data").apply { start() }.looper)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            val d = try { b?.interfaceDescriptor } catch (_: Throwable) { null }
            if (d == DESCRIPTOR) { binder = b; canStatus = "已連上車機 CAN" } else canStatus = "介面不符"
        }
        override fun onServiceDisconnected(n: ComponentName?) { binder = null; canStatus = "斷線" }
    }

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val s = i?.getStringExtra("json") ?: return
            try { adas = JSONObject(s); adasAt = SystemClock.elapsedRealtime() } catch (_: Throwable) {}
        }
    }

    fun start() {
        try {
            ctx.bindService(Intent("com.ts.can.carinfo.CarInfoService").setPackage("com.ts.MainUI"), conn, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) { canStatus = "連不上：$t" }
        ctx.registerReceiver(rx, IntentFilter(ACTION_STATE))
        h.post(object : Runnable {
            override fun run() { poll(); h.postDelayed(this, 200) }
        })
    }

    private fun call(code: Int): IntArray? {
        val b = binder ?: return null
        val d = Parcel.obtain(); val r = Parcel.obtain()
        return try {
            d.writeInterfaceToken(DESCRIPTOR)
            b.transact(code, d, r, 0)
            r.readException()
            r.createIntArray()
        } catch (_: Throwable) { null } finally { d.recycle(); r.recycle() }
    }

    private var doorTick = 0
    private fun poll() {
        call(6)?.let { a ->
            base = a; baseAt = SystemClock.elapsedRealtime()
            var changed = false
            for (i in a.indices) if (a[i] != 0 && seen.add(i)) changed = true
            if (changed) sp.edit().putString("seen", seen.joinToString(",")).apply()
        }
        if (++doorTick % 3 == 0) call(4)?.let { doors = it }
    }

    /** 給網頁的車況 JSON。 */
    fun json(): String {
        val o = JSONObject()
        val a = base
        val fresh = a != null && SystemClock.elapsedRealtime() - baseAt < 2000
        o.put("can", fresh); o.put("canStatus", canStatus)
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
        doors?.takeIf { it.size >= 7 && it[0] == 1 }?.let { d ->
            o.put("doors", JSONObject().put("fl", d[1] != 0).put("fr", d[2] != 0).put("rl", d[3] != 0).put("rr", d[4] != 0).put("trunk", d[5] != 0).put("hood", d[6] != 0))
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
        return o.toString()
    }

    /** 送指令給 ADAS 引擎（氛圍燈顏色、後擋看板）。只送非敏感的（不包含遠端模組）。 */
    fun engineCmd(cmd: String, arg: String) {
        try { ctx.sendBroadcast(Intent(ACTION_CMD).setPackage(ENGINE_PKG).putExtra("cmd", cmd).putExtra("arg", arg)) } catch (_: Throwable) {}
    }

    fun stop() { try { ctx.unbindService(conn); ctx.unregisterReceiver(rx) } catch (_: Throwable) {} }
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
