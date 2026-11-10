package com.vito.avmprobe

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.util.UUID

/**
 * 後擋 LED 看板（iPixel Color App，com.wifiled.ipixels 反編譯）：
 * - 服務 00FA、寫入 FA02。封包 = 2 bytes 長度（小端，含自己）+ 指令 + 子指令 + 資料。
 * - 播放存在看板裡的第 N 則：`07 00 08 80 01 00 N`（App 的節目清單送的就是這個，數量 1、編號 N）。
 * - 亮度 `05 00 04 80 亮度`、開關 `05 00 07 01 0/1`。
 * 中文字要自己算點陣圖再送，太重；改成你先用 App 把要顯示的字存進看板，車機只負責切換第幾則。
 */
object RearDisplayProtocol {
    fun play(slot: Int): ByteArray = playList(listOf(slot))

    /** 輪播好幾則（App 的節目清單：長度、08 80、數量、00、各則編號）。 */
    fun playList(slots: List<Int>): ByteArray {
        val s = slots.filter { it in 1..255 }.take(40)
        require(s.isNotEmpty())
        return byteArrayOf((6 + s.size).toByte(), 0, 8, 0x80.toByte(), s.size.toByte(), 0) + ByteArray(s.size) { s[it].toByte() }
    }

    /** "1,2,3" → [1,2,3]（逗號、空白、頓號都可以）。 */
    fun parseList(t: String): List<Int> = t.split(',', '，', '、', ' ').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..255 }
    fun brightness(level: Int): ByteArray = byteArrayOf(5, 0, 4, 0x80.toByte(), level.coerceIn(1, 100).toByte())
    fun power(on: Boolean): ByteArray = byteArrayOf(5, 0, 7, 1, if (on) 1 else 0)

    enum class Event(val label: String) { BRAKE("急煞／雙閃"), REVERSE("倒車"), LEFT("左轉燈"), RIGHT("右轉燈"), FOLLOW("跟車太近"), PARK("準備停車"), IDLE("平常") }

    /** 現在該顯示哪個情境（優先順序：急煞／雙閃 > 倒車 > 方向燈 > 跟車太近 > 停車 > 平常）。 */
    fun pick(hazardOrBrake: Boolean, reverse: Boolean, tooClose: Boolean, parking: Boolean, turn: Char? = null): Event = when {
        hazardOrBrake -> Event.BRAKE
        reverse -> Event.REVERSE
        turn == 'L' -> Event.LEFT
        turn == 'R' -> Event.RIGHT
        tooClose -> Event.FOLLOW
        parking -> Event.PARK
        else -> Event.IDLE
    }
}

object RearDisplay {
    private val SVC = UUID.fromString("000000fa-0000-1000-8000-00805f9b34fb")
    private val CH = UUID.fromString("0000fa02-0000-1000-8000-00805f9b34fb")
    private const val HOLD_MS = 3000L

    @Volatile var status = "未啟用"; private set
    private var ctx: Context? = null
    private var prefs: AppPrefs? = null
    private var h: Handler? = null
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var ch: BluetoothGattCharacteristic? = null
    @Volatile private var ready = false
    private var fails = 0
    private var rediscovered = false
    private var shown: RearDisplayProtocol.Event? = null
    private var shownAt = 0L

    fun start(c: Context, p: AppPrefs) {
        if (h != null) return
        ctx = c.applicationContext; prefs = p
        h = Handler(HandlerThread("rear-display").apply { start() }.looper)
        h!!.post(object : Runnable {
            override fun run() {
                try { if (p.rdOn && p.rdMac.isNotEmpty() && gatt == null) connect(p.rdMac) } catch (t: Throwable) { status = "連線失敗：$t" }
                h?.postDelayed(this, 15_000)
            }
        })
    }

    fun reconnect() { h?.post { close(); prefs?.let { if (it.rdMac.isNotEmpty()) connect(it.rdMac) } } }

    private fun close() { try { gatt?.disconnect(); gatt?.close() } catch (_: Throwable) {}; gatt = null; ch = null; ready = false }

    private fun connect(mac: String) {
        val ad = BluetoothAdapter.getDefaultAdapter() ?: run { status = "車機沒有藍牙"; return }
        if (!ad.isEnabled) { status = "車機藍牙是關的"; return }
        status = "連線中…"
        Crumbs.at(Crumbs.OTHER, "rd-connect")
        try { gatt?.close() } catch (_: Throwable) {}
        gatt = ad.getRemoteDevice(mac).connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
        val mine = gatt
        h?.postDelayed({
            if (!ready && gatt === mine && mine != null) { close(); fails++; status = "連線逾時（第 $fails 次），稍後再試；手機 App 連著看板的話車機連不上"; retry() }
        }, 15_000)
    }

    private fun retry() {
        val d = when (fails) { 1 -> 2_000L; 2 -> 5_000L; else -> 15_000L }
        h?.postDelayed({ val p = prefs; if (gatt == null && p != null && p.rdOn && p.rdMac.isNotEmpty()) connect(p.rdMac) }, d)
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, ns: Int) {
            h?.post {
                if (ns == BluetoothProfile.STATE_CONNECTED) { status = "已連線，找服務…"; rediscovered = false; g.discoverServices() }
                else {
                    try { g.close() } catch (_: Throwable) {}
                    if (gatt === g) { gatt = null; ch = null; ready = false; fails++; status = "斷線（錯誤 $st），稍後重連"; retry() }
                }
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            h?.post {
                val c = g.getService(SVC)?.getCharacteristic(CH)
                if (c == null) {
                    if (!rediscovered) { rediscovered = true; status = "找不到 FA02，清快取重找…"; GattUtil.refresh(g); h?.postDelayed({ try { g.discoverServices() } catch (_: Throwable) {} }, 800); return@post }
                    status = "找不到 FA02（狀態 $st）。這個裝置的服務：" + GattUtil.describe(g); return@post
                }
                ch = c; ready = true; fails = 0; shown = null
                status = "已連線"
            }
        }
    }

    private fun write(b: ByteArray): Boolean {
        val g = gatt ?: return false; val c = ch ?: return false
        if (!ready) return false
        return try {
            Crumbs.at(Crumbs.OTHER, "rd-write")
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = b
            g.writeCharacteristic(c)
        } catch (_: Throwable) { false }
    }

    private fun slotOf(p: AppPrefs, e: RearDisplayProtocol.Event): Int = when (e) {
        RearDisplayProtocol.Event.BRAKE -> p.rdSlotBrake
        RearDisplayProtocol.Event.REVERSE -> p.rdSlotReverse
        RearDisplayProtocol.Event.LEFT -> p.rdSlotLeft
        RearDisplayProtocol.Event.RIGHT -> p.rdSlotRight
        RearDisplayProtocol.Event.FOLLOW -> p.rdSlotFollow
        RearDisplayProtocol.Event.PARK -> p.rdSlotPark
        RearDisplayProtocol.Event.IDLE -> p.rdSlotIdle
    }

    /** 每輪偵測呼叫：情境變了（而且舊的至少顯示 3 秒）就切換到那一則；沒設定的情境不動。 */
    fun onState(hazardOrBrake: Boolean, reverse: Boolean, tooClose: Boolean, parking: Boolean, turn: Char? = null) {
        val p = prefs ?: return
        if (!p.rdOn || !ready) return
        val e = RearDisplayProtocol.pick(hazardOrBrake, reverse, tooClose, parking, turn)
        if (e == shown) return
        val now = SystemClock.elapsedRealtime()
        // 急煞、方向燈馬上切；其他情境至少維持 3 秒，避免一直跳
        val instant = e == RearDisplayProtocol.Event.BRAKE || e == RearDisplayProtocol.Event.LEFT || e == RearDisplayProtocol.Event.RIGHT
        if (!instant && now - shownAt < HOLD_MS) return
        shown = e; shownAt = now
        // 平常可以是好幾則輪播
        val list = if (e == RearDisplayProtocol.Event.IDLE) RearDisplayProtocol.parseList(p.rdIdleList).ifEmpty { listOfNotNull(p.rdSlotIdle.takeIf { it > 0 }) }
                   else listOfNotNull(slotOf(p, e).takeIf { it > 0 })
        if (list.isEmpty()) return
        h?.post { if (write(RearDisplayProtocol.playList(list))) status = "已連線：顯示第 ${list.joinToString("、")} 則（${e.label}）" }
    }

    /** 設定裡測試：播放第 [slot] 則。 */
    fun testList(list: List<Int>) {
        h?.post {
            if (!ready) { status = "還沒連上，沒辦法測試"; return@post }
            if (list.isEmpty()) return@post
            status = if (write(RearDisplayProtocol.playList(list))) "測試：輪播第 ${list.joinToString("、")} 則" else "送不出去"
            shown = null
        }
    }

    fun test(slot: Int) {
        h?.post {
            if (!ready) { status = "還沒連上，沒辦法測試"; return@post }
            status = if (write(RearDisplayProtocol.play(slot))) "測試：顯示第 $slot 則" else "送不出去"
            shown = null
        }
    }
}
