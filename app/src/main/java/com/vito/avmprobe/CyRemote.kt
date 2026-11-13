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
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 亮波紅月遠端模組（CY103）藍牙協定，照 App（com.hyll.cyhy2）的 com.hyll.ble.BleCY02 反編譯：
 * - 封包：`FF55` + LL + `30` + 模式 + AES-ECB(內容) + 檢查碼 + `00`。模式 `10` 用藍牙金鑰（btkey），`30` 用握手拿到的亂數。
 * - 內容：type + id + 序號 + 長度 + 補零數 + 資料（補零到 16 的倍數）+ 檢查碼。
 * - 握手：送 27/01（要亂數，btkey 加密）→ 模組回 6701 帶亂數 → 送 27/02（亂數用 btkey 加密後回傳）→ 回 6702 `01` = 通過，
 *   之後指令都用亂數當金鑰。
 *
 * 安全：只允許下面 [Cmd] 白名單（鎖車、開鎖、尾箱、車窗、空調）。遠端啟動（01）、熄火（02）、修理廠模式（0E/0F）
 * 等其他代碼一律丟例外，送不出去。
 */
object CyProtocol {
    enum class Cmd(val label: String, val id: String, val data: String) {
        LOCK("鎖車", "03", ""), UNLOCK("開鎖", "04", ""),
        TRUNK_OPEN("開尾箱", "05", ""), TRUNK_CLOSE("關尾箱", "06", ""),
        WINDOW_UP("升窗", "08", "02"), WINDOW_DOWN("降窗", "09", "02"),
        AC_ON("開空調", "22", "01"), AC_OFF("關空調", "22", "00")
    }

    /** 允許送出的 (type, id)：握手兩個 + 白名單指令。 */
    private val ALLOWED = setOf("2701", "2702") + Cmd.values().map { "2F" + it.id }

    data class Msg(val type: String, val id: String, val msg: String) { val trcd get() = type + id }

    fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it.toInt() and 255) }
    fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun sum(h: String) = unhex(h).sumOf { it.toInt() and 255 } % 256

    private fun aes(key: ByteArray, data: ByteArray, enc: Boolean): ByteArray {
        val k = key.copyOf(16)                       // App：金鑰不足 16 bytes 補 0
        return Cipher.getInstance("AES/ECB/NoPadding").run {
            init(if (enc) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES")); doFinal(data)
        }
    }

    fun encrypt(key: ByteArray, data: ByteArray) = aes(key, data, true)

    /** [useRand] false = 用 btkey（模式 10），true = 用握手亂數（模式 30）。 */
    fun build(type: String, id: String, data: String, useRand: Boolean, key: ByteArray, seq: Int): ByteArray {
        require((type + id).uppercase() in ALLOWED) { "不允許的遠端指令 $type$id" }
        val n = data.length / 2
        var p = (n + 6) % 16
        if (p > 0) p = 16 - p
        val dp = data + "00".repeat(p)
        val i = n + p + 1
        val i2 = i + 8
        val mode = if (useRand) "30" else "10"
        val head = "%02X%02X%02X".format(seq and 255, i, p)
        val c1 = sum(mode + type + id + head + dp)
        val inner = type + id + head + dp + "%02X".format(c1)
        val str5 = "%02X30%s".format(i2, mode) + hex(aes(key, unhex(inner), true))
        val c2 = sum(str5)
        return unhex("FF55" + str5 + "%02X".format(c2) + "00")
    }

    /** 一個完整封包（十六進位）→ 內容。[btkey]／[rand] 依封包的模式選。 */
    fun parse(frame: String, btkey: ByteArray?, rand: ByteArray?): Msg? {
        if (frame.length < 26 || !frame.startsWith("FF55")) return null
        var s = frame
        val m = frame[8]
        if (m == '1' || m == '2' || m == '3') {
            val enc = frame.substring(10, frame.length - 4)
            if (enc.length % 32 != 0) return null
            val key = (if (m == '1') btkey else rand) ?: return null
            s = frame.substring(0, 10) + hex(aes(key, unhex(enc), false)) + frame.substring(frame.length - 4)
        }
        return try {
            val flen = s.substring(18, 20).toInt(16)
            Msg(s.substring(10, 12), s.substring(12, 14), s.substring(20, s.length - 6 - flen * 2))
        } catch (_: Exception) { null }
    }

    /** 收到的資料累積在 [buf]（十六進位），切出完整封包。 */
    fun split(buf: StringBuilder): List<String> {
        val out = ArrayList<String>()
        while (true) {
            val a = buf.indexOf("FF55")
            if (a < 0) { if (buf.length > 4) buf.delete(0, buf.length - 4); break }
            if (a > 0) buf.delete(0, a)
            if (buf.length < 6) break
            val len = (buf.substring(4, 6).toInt(16) + 4) * 2
            if (buf.length < len) break
            out.add(buf.substring(0, len)); buf.delete(0, len)
        }
        return out
    }
}

/** 藍牙連線：要用才連，握手、送指令，閒置 15 秒就斷開（讓手機 App 可以用）。 */
object CyRemote {
    private const val GATE = "cyremote"
    private val SVC = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val CH = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    private val CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val IDLE_MS = 15_000L
    private const val HANDSHAKE_MS = 20_000L
    private var linked = false
    private var rediscovered = false

    @Volatile var status = "未設定"; private set
    private val logLines = ArrayDeque<String>()
    fun log(): String = synchronized(logLines) { logLines.joinToString("\n") }
    private fun note(s: String) {
        status = s
        synchronized(logLines) { logLines.addLast(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " + s); while (logLines.size > 12) logLines.removeFirst() }
        Engine360.log("遠端模組：$s")
    }

    private var ctx: Context? = null
    private var prefs: AppPrefs? = null
    private val h: Handler by lazy { Handler(HandlerThread("cy-remote").apply { start() }.looper) }
    private var gatt: BluetoothGatt? = null
    private var ch: BluetoothGattCharacteristic? = null
    private var rand: ByteArray? = null
    private var ready = false
    private var seq = 0
    private val buf = StringBuilder()
    private val queue = ArrayDeque<CyProtocol.Cmd>()
    private var handshakeAt = 0L

    fun init(c: Context, p: AppPrefs) { ctx = c.applicationContext; prefs = p; if (p.cyKey.length == 32) status = if (p.cyMac.isEmpty()) "還沒選模組" else "待命（要用才連線）" }

    private fun key(): ByteArray? = prefs?.cyKey?.takeIf { it.length == 32 }?.let { CyProtocol.unhex(it) }

    /** 送一個（白名單內的）指令；沒連就先連、握手。 */
    fun send(cmd: CyProtocol.Cmd) {
        h.post {
            val p = prefs ?: return@post
            if (key() == null) { note("還沒輸入藍牙金鑰"); return@post }
            if (p.cyMac.isEmpty()) { note("還沒選模組"); return@post }
            queue.addLast(cmd)
            if (ready) flush() else if (gatt == null && scanCb == null) { attempt = 0; connect(p.cyMac) }
            h.removeCallbacks(idle); h.postDelayed(idle, IDLE_MS)
        }
    }

    private val idle = Runnable { if (queue.isEmpty()) { close(); note("閒置，已斷開（手機 App 可以連）") } }

    private const val NAME_HINT = "6600121332"
    private const val SCAN_MS = 10_000L
    private var scanCb: android.bluetooth.le.ScanCallback? = null
    private var attempt = 0

    /**
     * 先掃描找模組再連：這種模組常用隨機位址（直接拿 MAC 連會用錯位址類型，悶著連不上），
     * 而且手機 App 連著時模組不廣播。掃到廣播（MAC 一樣，或名稱含 6600121332）才連，用掃到的那個裝置物件連。
     */
    /** 排隊（[BleGate]）：使用者按了才連，插隊排第一個；掃描＋建立連線這段一次只讓一個裝置做。 */
    private fun connect(mac: String) {
        note("排隊等藍牙（${BleGate.status()}）")
        BleGate.request(GATE, urgent = true) { h.post { connectNow(mac) } }
    }

    private fun connectNow(mac: String) {
        val ad = BluetoothAdapter.getDefaultAdapter() ?: run { note("車機沒有藍牙"); BleGate.done(GATE); return }
        if (!ad.isEnabled) { note("車機藍牙是關的"); BleGate.done(GATE); return }
        val sc = ad.bluetoothLeScanner ?: run { connectTo(ad.getRemoteDevice(mac)); return }
        ctx?.let { BleDiag.ensureLocation(it) }
        stopScan()
        note("掃描模組中…")
        Crumbs.at(Crumbs.OTHER, "cy-scan")
        val cbk = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(type: Int, r: android.bluetooth.le.ScanResult) {
                val d = r.device ?: return
                val name = r.scanRecord?.deviceName ?: try { d.name } catch (_: Throwable) { null } ?: ""
                if (!d.address.equals(mac, true) && !name.contains(NAME_HINT)) return
                h.post {
                    if (scanCb == null) return@post
                    stopScan()
                    val p = prefs
                    if (p != null && !d.address.equals(p.cyMac, true)) { note("模組位址換了：${p.cyMac} → ${d.address}"); p.cyMac = d.address; if (name.isNotEmpty()) p.cyName = name }
                    note("找到模組（訊號 ${r.rssi} dBm），連線中…")
                    attempt = 0
                    connectTo(d)
                }
            }
            override fun onScanFailed(code: Int) { h.post { stopScan(); note("掃描失敗（$code），直接連…"); connectTo(ad.getRemoteDevice(mac)) } }
        }
        scanCb = cbk
        try { sc.startScan(null, BleDiag.lowLatency(), cbk) } catch (t: Throwable) { scanCb = null; note("不能掃描（${t.message}），直接連…"); connectTo(ad.getRemoteDevice(mac)); return }
        h.postDelayed({
            if (scanCb === cbk) {
                stopScan()
                BleGate.done(GATE)
                note("掃不到模組的廣播：手機 App（亮波）連著時模組不廣播，請把手機藍牙關掉或把 App 完全關閉；也可能熄火後模組休眠")
                queue.clear()
            }
        }, SCAN_MS)
    }

    private fun stopScan() {
        val c = scanCb ?: return
        scanCb = null
        try { BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner?.stopScan(c) } catch (_: Throwable) {}
    }

    private fun connectTo(dev: android.bluetooth.BluetoothDevice) {
        Crumbs.at(Crumbs.OTHER, "cy-connect")
        buf.setLength(0); rand = null; ready = false
        linked = false; rediscovered = false
        val g = dev.connectGatt(ctx, false, cb, android.bluetooth.BluetoothDevice.TRANSPORT_LE)   // 指定 BLE（車機預設可能走傳統藍牙）
        gatt = g
        h.postDelayed({
            if (gatt === g) BleGate.done(GATE)
            if (!ready && gatt === g) {
                note(if (!linked) "模組有廣播但連不上（藍牙一直沒建立連線；試著把車機藍牙關掉再打開）" else "連上了但握手沒回應（金鑰不對，或協定不同）")
                queue.clear(); close()
            }
        }, HANDSHAKE_MS)
    }

    private fun close() {
        stopScan()
        try { gatt?.disconnect(); gatt?.close() } catch (_: Throwable) {}
        gatt = null; ch = null; ready = false; rand = null
    }

    private fun write(frame: ByteArray) {
        val g = gatt ?: return; val c = ch ?: return
        Crumbs.at(Crumbs.OTHER, "cy-write")
        // App 一樣每 20 bytes 一段、間隔 50 ms
        var o = 0
        while (o < frame.size) {
            val part = frame.copyOfRange(o, minOf(frame.size, o + 20))
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = part
            g.writeCharacteristic(c)
            o += 20
            if (o < frame.size) SystemClock.sleep(50)
        }
    }

    private fun nextSeq(): Int { seq = (seq + 1) and 255; return seq }

    private fun flush() {
        val r = rand ?: return
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            note("送出：${c.label}")
            write(CyProtocol.build("2F", c.id, c.data, true, r, nextSeq()))
            SystemClock.sleep(300)
        }
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, ns: Int) {
            BleGate.done(GATE)
            h.post {
                if (ns == BluetoothProfile.STATE_CONNECTED) { linked = true; note("已連線，找服務…"); g.discoverServices() }
                else {
                    val mine = gatt === g
                    if (mine) { gatt = null; ch = null; ready = false }
                    try { g.close() } catch (_: Throwable) {}
                    if (st != 0) note("斷線（錯誤 $st）")
                    // 133 之類的連線錯誤：還沒連上就斷，重試 2 次（車機藍牙常第一次失敗）
                    if (mine && !linked && st != 0 && attempt < 2 && queue.isNotEmpty()) {
                        attempt++
                        note("重試連線（第 $attempt 次）…")
                        h.postDelayed({ if (gatt == null) connectTo(g.device) }, 1200)
                    }
                }
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            h.post {
                val c = g.getService(SVC)?.getCharacteristic(CH)
                    ?: g.services.firstNotNullOfOrNull { it.getCharacteristic(CH) }
                if (c == null) {
                    // 車機常留著舊的服務快取：清掉再找一次；還是沒有就把裝置實際有的服務列出來
                    if (!rediscovered) {
                        rediscovered = true
                        note("找不到 FFE1（狀態 $st），清快取重找…")
                        GattUtil.refresh(g); h.postDelayed({ try { g.discoverServices() } catch (_: Throwable) {} }, 800)
                        return@post
                    }
                    note("找不到 FFE1（狀態 $st）。這個裝置的服務：" + GattUtil.describe(g)); close(); return@post
                }
                ch = c
                g.setCharacteristicNotification(c, true)
                val d = c.getDescriptor(CCC)
                if (d != null) { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) } else startHandshake()
            }
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) { h.post { startHandshake() } }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: return
            h.post { buf.append(CyProtocol.hex(v)); for (f in CyProtocol.split(buf)) onFrame(f) }
        }
    }

    private fun startHandshake() {
        val k = key() ?: return
        handshakeAt = SystemClock.elapsedRealtime()
        note("握手：要亂數")
        write(CyProtocol.build("27", "01", "0110", false, k, nextSeq()))
    }

    private fun onFrame(f: String) {
        val k = key() ?: return
        val m = CyProtocol.parse(f, k, rand) ?: run { note("看不懂的回應 ${f.take(24)}…"); return }
        when {
            m.trcd == "6701" -> {
                if (!m.msg.startsWith("01") || m.msg.length < 8) { note("模組拒絕握手（金鑰不對？）"); close(); return }
                val r = CyProtocol.unhex(m.msg.substring(6))
                rand = r
                note("握手：送回加密亂數")
                write(CyProtocol.build("27", "02", "0110" + CyProtocol.hex(CyProtocol.encrypt(k, r)), false, k, nextSeq()))
            }
            m.trcd == "6702" -> {
                if (m.msg.startsWith("01")) { ready = true; note("握手成功"); flush() }
                else { note("握手失敗（模組不接受，金鑰可能不對）"); close() }
            }
            else -> note("模組回應 ${m.trcd} ${m.msg.take(16)}")
        }
    }

    // ───────── 停好車提醒 ─────────
    private var drove = false
    private var asked = false

    /** 每輪偵測呼叫：開過車（> 15 km/h）之後打 P 檔＋拉手煞車 → 問一次要不要升窗、鎖車。 */
    fun checkParked(speedKmh: Float?) {
        val p = prefs ?: return
        if (!p.cyParkPrompt || key() == null || p.cyMac.isEmpty()) return
        val v = speedKmh ?: return
        val i = CanVehicle.info
        if (v > 15f) { drove = true; asked = false; return }
        if (!drove || asked || !CanVehicle.fresh()) return
        // 手煞車欄位 CAN 盒有給才看；沒給就只看 P 檔
        if (i.gear == 'P' && i.handbrake != false) { asked = true; Engine360.askParkedRemote() }
    }
}
