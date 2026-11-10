package com.vito.avmprobe

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.util.UUID

/**
 * 藍牙氛圍燈協定（使用者的「AmbientLight」App，com.lomo.ambientlight，反編譯 com.lm.sdk）：
 * - 服務 FFE0；寫入 FFE2（不回應寫入）；通知 FFE1。
 * - 指令是 ASCII 十六進位字串，前後包 `<` `>`，例如 `<06FF0000>`。沒有加密（App 的 token 只是「連上了」的旗標）。
 * - 連上後先送 `0A01`；燈會回狀態 `0x12`：[4..6] 第 1 組顏色、[7..9] 第 2 組顏色、[10][11] 亮度。
 * - 顏色：`06RRGGBB` 全部、`10RRGGBB` 第 1 組、`09RRGGBB` 第 2 組。
 */
object AmbientProtocol {
    const val ZONE_ALL = "06"
    const val ZONE_1 = "10"
    const val ZONE_2 = "09"

    fun frame(hex: String): ByteArray = ("<" + hex.uppercase() + ">").toByteArray(Charsets.US_ASCII)

    fun color(zone: String, rgb: Int): ByteArray = frame(zone + "%06X".format(rgb and 0xFFFFFF))

    /** 通知內容可能一次有好幾段 `<..><..>`：每段的十六進位字串轉成 bytes。 */
    fun parse(raw: ByteArray): List<ByteArray> {
        val s = String(raw, Charsets.US_ASCII)
        val out = ArrayList<ByteArray>()
        var i = 0
        while (true) {
            val a = s.indexOf('<', i); if (a < 0) break
            val b = s.indexOf('>', a + 1); if (b < 0) break
            val hex = s.substring(a + 1, b)
            if (hex.length % 2 == 0 && hex.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' })
                out.add(ByteArray(hex.length / 2) { k -> hex.substring(2 * k, 2 * k + 2).toInt(16).toByte() })
            i = b + 1
        }
        return out
    }

    /** 狀態回覆（0x12）裡兩組的顏色；不是狀態回覆回 null。 */
    fun statusColors(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 15 || b[0].toInt() != 0x12) return null
        fun rgb(o: Int) = ((b[o].toInt() and 255) shl 16) or ((b[o + 1].toInt() and 255) shl 8) or (b[o + 2].toInt() and 255)
        return rgb(4) to rgb(7)
    }
}

/**
 * 警示 → 燈的顏色（純邏輯）：危險（碰撞、來車、轉彎掃到）紅色閃；注意（偏離、壓線）橘色；
 * 前車起步／轉綠燈綠色閃一下；沒事回 null（恢復原本的顏色）。[side] 'L'／'R' 時只動那一側（如果設定了哪組在左邊）。
 */
object AmbientRules {
    data class Want(val rgb: Int, val side: Char?)

    const val RED = 0xFF0000
    const val DIM_RED = 0x300000
    const val ORANGE = 0xFF5000
    const val GREEN = 0x00FF20

    fun want(danger: Boolean, dangerSide: Char?, warn: Boolean, go: Boolean, now: Long): Want? = when {
        danger -> Want(if ((now / 500) % 2 == 0L) RED else DIM_RED, dangerSide)   // 一秒閃一次（太快會把控制器塞爆斷線）
        warn -> Want(ORANGE, null)
        go -> Want(if ((now / 300) % 2 == 0L) GREEN else 0x002008, null)
        else -> null
    }
}

/** 藍牙連線與送指令（一個背景執行緒；斷線每 15 秒重連）。 */
object AmbientLight {
    private val SERVICE = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val TX = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb")
    private val RX = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    private val CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val MIN_GAP_MS = 250L
    private var fails = 0

    @Volatile var status = "未啟用"; private set
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var tx: BluetoothGattCharacteristic? = null
    @Volatile private var ready = false
    /** 燈原本的顏色（連上時燈回報的），警示結束後恢復。 */
    @Volatile var base: Pair<Int, Int>? = null; private set
    private var ctx: Context? = null
    private var prefs: AppPrefs? = null
    private var h: Handler? = null
    private var shown: AmbientRules.Want? = null
    private var lastWrite = 0L
    @Volatile private var paused = false

    fun start(c: Context, p: AppPrefs) {
        if (h != null) return
        ctx = c.applicationContext; prefs = p
        h = Handler(HandlerThread("ambient-light").apply { start() }.looper)
        h!!.post(object : Runnable {
            override fun run() {
                try { if (!paused && p.ambientOn && p.ambientMac.isNotEmpty() && gatt == null) connect(p.ambientMac) } catch (t: Throwable) { status = "連線失敗：$t" }
                h?.postDelayed(this, 15_000)
            }
        })
    }

    private fun connect(mac: String) {
        val ad = BluetoothAdapter.getDefaultAdapter() ?: run { status = "車機沒有藍牙"; return }
        if (!ad.isEnabled) { status = "車機藍牙是關的"; return }
        status = "連線中（$mac）…"
        Crumbs.at(Crumbs.OTHER, "ambient-connect")
        try { gatt?.close() } catch (_: Throwable) {}
        // 一定要指定 BLE：車機的藍牙晶片預設可能走傳統藍牙，連上很容易斷、斷了就連不回來（錯誤 133）
        gatt = ad.getRemoteDevice(mac).connectGatt(ctx, false, cb, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, ns: Int) {
            if (ns == BluetoothProfile.STATE_CONNECTED) { status = "已連線，找服務…"; g.discoverServices() }
            else {
                ready = false; tx = null
                try { g.close() } catch (_: Throwable) {}
                if (gatt === g) gatt = null
                if (paused) { status = "已斷開（讓手機 App 用）"; return }
                // 斷線：2 秒、5 秒、15 秒後重連（先完全釋放舊連線）
                fails++
                val d = when (fails) { 1 -> 2_000L; 2 -> 5_000L; else -> 15_000L }
                status = "斷線（錯誤 $st），${d / 1000} 秒後重連（第 $fails 次）；手機 App 在背景連著的話車機會連不上"
                h?.postDelayed({ val p = prefs; if (!paused && gatt == null && p != null && p.ambientOn && p.ambientMac.isNotEmpty()) connect(p.ambientMac) }, d)
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            val s = g.getService(SERVICE) ?: run { status = "這個裝置沒有 FFE0 服務（不是這款氛圍燈？）"; return }
            tx = s.getCharacteristic(TX)
            val rx = s.getCharacteristic(RX)
            if (tx == null) { status = "找不到寫入通道 FFE2"; return }
            if (rx != null) {
                g.setCharacteristicNotification(rx, true)
                rx.getDescriptor(CCC)?.let { d -> d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
            }
            ready = true; fails = 0
            status = "已連線"
            h?.postDelayed({ write(AmbientProtocol.frame("0A01")) }, 600)   // 跟 App 一樣：連上先送 0A01，燈會回狀態
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            for (b in AmbientProtocol.parse(c.value ?: return)) AmbientProtocol.statusColors(b)?.let { col ->
                // 警示中燈回報的是我們設的顏色，不當成原本的顏色
                if (shown == null) { base = col; status = "已連線（原本顏色 #%06X／#%06X）".format(col.first, col.second) }
            }
        }
    }

    @Synchronized
    private fun write(bytes: ByteArray): Boolean {
        val g = gatt ?: return false; val t = tx ?: return false
        if (!ready) return false
        return try {
            Crumbs.at(Crumbs.OTHER, "ambient-write")
            t.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            t.value = bytes
            g.writeCharacteristic(t).also { lastWrite = SystemClock.elapsedRealtime() }
        } catch (_: Throwable) { false }
    }

    /** 每輪偵測呼叫：依警示改顏色；沒警示恢復原本的顏色。 */
    fun onAlerts(danger: Boolean, dangerSide: Char?, warn: Boolean, go: Boolean) {
        val p = prefs ?: return
        if (!p.ambientOn || paused || !ready) return
        val now = SystemClock.elapsedRealtime()
        val w = AmbientRules.want(danger, dangerSide, warn, go, now)
        if (w == shown) return
        if (now - lastWrite < MIN_GAP_MS) return
        h?.post { apply(w, p) }
        shown = w
    }

    private fun apply(w: AmbientRules.Want?, p: AppPrefs) {
        if (w == null) {
            val b = base
            if (b != null) { write(AmbientProtocol.color(AmbientProtocol.ZONE_1, b.first)); SystemClock.sleep(MIN_GAP_MS); write(AmbientProtocol.color(AmbientProtocol.ZONE_2, b.second)) }
            else write(AmbientProtocol.frame("0A01"))   // 不知道原本顏色：至少讓燈回報一次
            return
        }
        // 分區是前座／後座：前方的危險亮前座、後方的危險亮後座（設定了哪一組是前座才分，不然全部）
        val frontZone = p.ambientLeftZone
        // 後座燈接大燈電源：大燈沒開（白天）後座不會亮 → 後方危險改成前後一起亮
        val rearDark = w.side == 'B' && !(CanVehicle.fresh() && CanVehicle.info.lightsOn == true)
        val zone = when {
            w.side == null || frontZone == 0 || rearDark -> AmbientProtocol.ZONE_ALL
            (w.side == 'F') == (frontZone == 1) -> AmbientProtocol.ZONE_1
            else -> AmbientProtocol.ZONE_2
        }
        write(AmbientProtocol.color(zone, w.rgb))
    }

    /** 設定裡的測試：[zone] 顏色 [rgb] 亮 2 秒再恢復。 */
    fun test(zone: String, rgb: Int) {
        h?.post {
            if (!ready) { status = "還沒連上，沒辦法測試"; return@post }
            write(AmbientProtocol.color(zone, rgb))
            h?.postDelayed({ shown = null; prefs?.let { apply(null, it) } }, 2000)
        }
    }

    /** 斷開，讓手機 App 可以連（燈一次只能被一台連）。 */
    fun pause(on: Boolean) {
        paused = on
        h?.post {
            if (on) { try { gatt?.disconnect() } catch (_: Throwable) {} }
            else prefs?.let { p -> if (p.ambientMac.isNotEmpty() && gatt == null) connect(p.ambientMac) }
        }
    }

    fun reconnect() { h?.post { try { gatt?.disconnect(); gatt?.close() } catch (_: Throwable) {}; gatt = null; ready = false; prefs?.let { if (it.ambientMac.isNotEmpty()) connect(it.ambientMac) } } }
}
