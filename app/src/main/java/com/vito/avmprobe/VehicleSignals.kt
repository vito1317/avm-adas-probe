package com.vito.avmprobe

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/*
 * 車身訊號：方向燈、檔位、方向盤角度、車速、前後雷達（PDC）。
 * CAN 解碼盒 → 車機的資料怎麼讀還在探測，所以這裡先做資料模型、各欄位獨立過期、可插拔來源、
 * 模擬來源，以及行車/倒車輔助線的單車模型（assets/bev3d/vehicle3d.js 用同一套公式，改一邊要改另一邊）。
 * 純 Kotlin，不依賴 android.*。
 *
 * 車身座標（v0.3 規格）：原點 = 車身投影中心的地面點，x 向右、z 向前，公尺。
 */

/**
 * 某一刻的車身訊號；null = 沒有資料（來源沒給或已過期）。
 * pdcFront / pdcRear：4 顆感測器由左到右，公尺；Float.POSITIVE_INFINITY = 無障礙；
 * NaN = 該顆感測器故障；整個 null = 雷達未啟動。
 * turnLit：方向燈這一刻是亮（true）還是暗（false）；只有來源會回報燈的暗期時才知道，否則 null。
 */
data class VehicleState(
    val turn: Char? = null,          // 'L' 'R' 'H'（雙黃燈）
    val gear: Char? = null,          // 'P' 'R' 'N' 'D'
    val steerDeg: Float? = null,     // 方向盤角度，+ = 向右
    val speedKmh: Float? = null,
    val pdcFront: FloatArray? = null,
    val pdcRear: FloatArray? = null,
    val atMs: Long = 0L,             // 這份狀態的時間（snapshot 的當下）
    val turnLit: Boolean? = null
) {
    /** 全部欄位都沒資料 */
    val isEmpty: Boolean
        get() = turn == null && gear == null && steerDeg == null && speedKmh == null &&
            pdcFront == null && pdcRear == null

    /** 前（front = true）或後雷達的最近距離（負值當 0）；雷達未啟動、全部無障礙或故障 = null */
    fun pdcMin(front: Boolean): Float? {
        val a = (if (front) pdcFront else pdcRear) ?: return null
        var m = Float.POSITIVE_INFINITY
        for (d in a) {
            if (d.isNaN() || d == Float.NEGATIVE_INFINITY) continue   // 故障
            val v = if (d < 0f) 0f else d
            if (v < m) m = v
        }
        return if (m.isFinite()) m else null
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VehicleState) return false
        return turn == other.turn && gear == other.gear && steerDeg == other.steerDeg &&
            speedKmh == other.speedKmh && atMs == other.atMs && turnLit == other.turnLit &&
            pdcFront.contentEquals(other.pdcFront) && pdcRear.contentEquals(other.pdcRear)
    }

    override fun hashCode(): Int {
        var h = turn.hashCode()
        h = 31 * h + gear.hashCode()
        h = 31 * h + steerDeg.hashCode()
        h = 31 * h + speedKmh.hashCode()
        h = 31 * h + pdcFront.contentHashCode()
        h = 31 * h + pdcRear.contentHashCode()
        h = 31 * h + atMs.hashCode()
        h = 31 * h + turnLit.hashCode()
        return h
    }

    override fun toString(): String =
        "VehicleState(turn=$turn, turnLit=$turnLit, gear=$gear, steerDeg=$steerDeg, speedKmh=$speedKmh, " +
            "pdcFront=${pdcFront?.contentToString()}, pdcRear=${pdcRear?.contentToString()}, atMs=$atMs)"
}

/** 各欄位多久沒更新就視為過期（毫秒）。 */
data class SignalTimeouts(
    val turnMs: Long = 1500,   // 方向燈會閃：熄滅期（約 0.4–0.7 s）要撐過去，所以從「最後一次亮」起算
    val gearMs: Long = 2000,
    val steerMs: Long = 2000,
    val speedMs: Long = 2000,
    val pdcMs: Long = 1000
)

/**
 * 方向盤角度校正：CAN 原始值 → 本模組的約定（+ = 向右、置中 = 0）。
 * 多數 CAN 的方向盤角度採 ISO 8855（+ = 逆時針 = 向左）→ sign = -1；
 * offsetDeg = 方向盤擺正時讀到的原始值。探測完成前兩者都不確定，預設不轉換。
 */
data class SteerCalibration(val sign: Int = 1, val offsetDeg: Float = 0f) {
    init {
        require(sign == 1 || sign == -1) { "sign 只能是 +1 或 -1：$sign" }
    }

    fun apply(raw: Float): Float = sign * (raw - offsetDeg)

    companion object {
        /** ISO 8855：+ = 向左 */
        val ISO8855 = SteerCalibration(sign = -1)
    }
}

/**
 * 未來的實際資料來源（CAN 盒廣播、序列埠、廠商 App…）實作這個介面，
 * start() 之後在任何執行緒呼叫 sink.reportXxx() 回報解碼結果。
 *
 * 時間：回報時省略 atMs（由 sink 自己蓋時間），或傳 sink.now()。
 * 不可傳其他時間基準的值（CAN 時間戳、System.currentTimeMillis() 等）：
 * 車機多半沒有 RTC，開機後才用 GPS/NTP 對時，牆鐘會跳。
 * 方向盤原始值用 sink.reportSteerRaw()，由 sink.steerCal 換成 + = 向右。
 */
interface VehicleSignalAdapter {
    val name: String
    fun start(sink: VehicleSignals)
    fun stop()
}

/**
 * 執行緒安全的車身訊號狀態。來源回報各欄位，使用端用 snapshot() 取「現在」的狀態
 * （過期欄位為 null）。過期不會主動通知，畫面端每幀 snapshot() 即可。
 *
 * clock 必須單調遞增（預設 System.nanoTime；Android 端也可注入 SystemClock::elapsedRealtime）。
 * 防呆：時間往回跳超過 [CLOCK_SLACK_MS] 時，舊值一律視為過期，新回報照收，不會凍住。
 */
class VehicleSignals(
    val timeouts: SignalTimeouts = SignalTimeouts(),
    private val clock: () -> Long = MONOTONIC_MS
) {
    fun interface Listener {
        fun onVehicleState(state: VehicleState)
    }

    private class Field<T> {
        var value: T? = null
        var at: Long = NEVER

        /** 比目前紀錄稍舊的回報（亂序）不採用；舊超過 CLOCK_SLACK_MS 視為時間基準往回跳，照收 */
        fun set(v: T?, atMs: Long): Boolean {
            if (at != NEVER && atMs < at && atMs >= at - CLOCK_SLACK_MS) return false
            value = v
            at = atMs
            return true
        }

        fun get(now: Long, timeoutMs: Long): T? {
            val v = value ?: return null
            val age = now - at
            return if (age <= timeoutMs && age >= -CLOCK_SLACK_MS) v else null
        }

        fun clear() { value = null; at = NEVER }
    }

    private val lock = Any()
    private val turn = Field<Char>()
    private val lit = Field<Boolean>()      // 燈這一刻亮 / 暗
    private val dark = Field<Boolean>()     // 最後一次回報「燈暗」的時間（有就代表來源會回報暗期）
    private val gear = Field<Char>()
    private val steer = Field<Float>()
    private val speed = Field<Float>()
    private val pdcFront = Field<FloatArray>()
    private val pdcRear = Field<FloatArray>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private var adapter: VehicleSignalAdapter? = null

    /** reportSteerRaw() 用的方向盤校正 */
    @Volatile
    var steerCal: SteerCalibration = SteerCalibration()

    /** sink 的時間（毫秒，單調遞增）；adapter 自己蓋時間時用這個 */
    fun now(): Long = clock()

    /** 目前接上的來源名稱（沒有 = null） */
    val sourceName: String? get() = synchronized(lock) { adapter?.name }

    /** 換資料來源：先停掉舊的再啟動新的。 */
    fun attach(a: VehicleSignalAdapter) {
        val old = synchronized(lock) { adapter.also { adapter = a } }
        old?.stop()
        a.start(this)
    }

    fun detach() {
        val old = synchronized(lock) { adapter.also { adapter = null } }
        old?.stop()
    }

    fun addListener(l: Listener) { listeners.addIfAbsent(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    /**
     * 方向燈。'L' 'R' 'H' = 燈亮；null = 這一刻燈是暗的（閃爍的熄滅期），
     * 會維持上一個方向，直到 turnMs 內都沒再亮才清掉。
     */
    fun reportTurn(turn: Char?, atMs: Long = clock()) {
        require(turn == null || turn == 'L' || turn == 'R' || turn == 'H') { "turn 只能是 L/R/H/null：$turn" }
        change(atMs) { lamp(turn, atMs) }
    }

    /** 左右燈分開讀到時用：兩邊同時亮 = 雙黃燈。 */
    fun reportLamps(left: Boolean, right: Boolean, atMs: Long = clock()) =
        reportTurn(if (left && right) 'H' else if (left) 'L' else if (right) 'R' else null, atMs)

    /** 確定方向燈已關（例如直接讀到撥桿回正）：立即清掉，不等過期。 */
    fun clearTurn(atMs: Long = clock()) = change(atMs) { turn.set(null, atMs) }

    /** 檔位；null = 不明 */
    fun reportGear(gear: Char?, atMs: Long = clock()) {
        require(gear == null || gear in "PRND") { "gear 只能是 P/R/N/D/null：$gear" }
        change(atMs) { this.gear.set(gear, atMs) }
    }

    /** 方向盤角度（度，+ = 向右、置中 = 0）；null 或 NaN = 不明 */
    fun reportSteer(deg: Float?, atMs: Long = clock()) =
        change(atMs) { steer.set(deg?.takeIf { !it.isNaN() }, atMs) }

    /** CAN 原始方向盤值：先經 steerCal 轉成 + = 向右 */
    fun reportSteerRaw(raw: Float?, atMs: Long = clock()) =
        reportSteer(raw?.takeIf { !it.isNaN() }?.let { steerCal.apply(it) }, atMs)

    fun reportSpeed(kmh: Float?, atMs: Long = clock()) =
        change(atMs) { speed.set(kmh?.takeIf { !it.isNaN() }, atMs) }

    /** 前雷達 4 顆（左→右，公尺，POSITIVE_INFINITY = 無障礙，NaN = 故障）；null = 雷達未啟動 */
    fun reportPdcFront(d: FloatArray?, atMs: Long = clock()) =
        change(atMs) { pdcFront.set(d?.copyOf(), atMs) }

    fun reportPdcRear(d: FloatArray?, atMs: Long = clock()) =
        change(atMs) { pdcRear.set(d?.copyOf(), atMs) }

    fun reportPdc(front: FloatArray?, rear: FloatArray?, atMs: Long = clock()) = change(atMs) {
        pdcFront.set(front?.copyOf(), atMs) or pdcRear.set(rear?.copyOf(), atMs)
    }

    /**
     * 來源一次給完整狀態（模擬、或一包就有全部欄位的協定）。時間用 s.atMs（要用 sink.now() 的時間基準）。
     * turn = null 當作燈暗（照樣保持）；其他欄位 null = 不明 / 雷達未啟動。s.turnLit 不採用（由 turn 推得）。
     */
    fun reportAll(s: VehicleState) {
        val t = s.atMs
        require(s.turn == null || s.turn == 'L' || s.turn == 'R' || s.turn == 'H') { "turn 只能是 L/R/H/null：${s.turn}" }
        require(s.gear == null || s.gear in "PRND") { "gear 只能是 P/R/N/D/null：${s.gear}" }
        change(t) {
            var ch = lamp(s.turn, t)
            ch = gear.set(s.gear, t) or ch
            ch = steer.set(s.steerDeg?.takeIf { !it.isNaN() }, t) or ch
            ch = speed.set(s.speedKmh?.takeIf { !it.isNaN() }, t) or ch
            ch = pdcFront.set(s.pdcFront?.copyOf(), t) or ch
            pdcRear.set(s.pdcRear?.copyOf(), t) or ch
        }
    }

    /**
     * 取 nowMs 當下的狀態，過期欄位為 null。陣列是複本，可以隨意改。
     * nowMs 要跟 clock 同一個時間基準（通常直接用預設值）。
     */
    fun snapshot(nowMs: Long = clock()): VehicleState = synchronized(lock) { snap(nowMs) }

    fun reset() = synchronized(lock) {
        turn.clear(); lit.clear(); dark.clear(); gear.clear(); steer.clear(); speed.clear()
        pdcFront.clear(); pdcRear.clear()
    }

    /** 燈的亮暗（呼叫端已持有 lock） */
    private fun lamp(t: Char?, atMs: Long): Boolean {
        val ch = lit.set(t != null, atMs)
        return if (t != null) turn.set(t, atMs) or ch else dark.set(true, atMs) or ch
    }

    private fun snap(now: Long): VehicleState {
        val t = turn.get(now, timeouts.turnMs)
        // 來源最近有回報過暗期，亮/暗才可信；否則交給畫面端自己閃
        val litNow = if (t != null && dark.get(now, timeouts.turnMs) != null) lit.get(now, timeouts.turnMs) else null
        return VehicleState(
            turn = t,
            gear = gear.get(now, timeouts.gearMs),
            steerDeg = steer.get(now, timeouts.steerMs),
            speedKmh = speed.get(now, timeouts.speedMs),
            pdcFront = pdcFront.get(now, timeouts.pdcMs)?.copyOf(),
            pdcRear = pdcRear.get(now, timeouts.pdcMs)?.copyOf(),
            atMs = now,
            turnLit = litNow
        )
    }

    /** block 回傳是否有欄位被採用；有才通知 listener（亂序被拒的回報不通知） */
    private inline fun change(atMs: Long, block: () -> Boolean) {
        val s = synchronized(lock) {
            val changed = block()
            if (!changed || listeners.isEmpty()) null else snap(atMs)
        } ?: return
        for (l in listeners) {
            try {
                l.onVehicleState(s)
            } catch (e: RuntimeException) {
                // 單一 listener 出錯不影響其他人與來源執行緒
            }
        }
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE / 4

        /**
         * 時鐘容許的倒退量（毫秒）：回報比現有紀錄早超過這麼多 → 視為時間基準往回跳而照收；
         * 查詢時間比紀錄早超過這麼多 → 該欄位視為過期。
         */
        const val CLOCK_SLACK_MS = 1000L

        /** 預設時鐘：單調遞增的毫秒，不受 GPS/NTP 對時影響 */
        val MONOTONIC_MS: () -> Long = { System.nanoTime() / 1_000_000 }

        /** JSON 裡雷達「無障礙」用 99 表示 */
        const val PDC_CLEAR = 99.0

        /**
         * 3D 面板 payload 的 veh 欄位：
         * { turn, lit, gear, steer, speed, pdc: { f: [..]|null, r: [..]|null } }
         * 雷達每顆：公尺、99 = 無障礙、null = 故障。lit = 燈此刻亮/暗（不明 = null）。
         */
        fun toJson(s: VehicleState): JSONObject = JSONObject()
            .put("turn", s.turn?.toString() ?: JSONObject.NULL)
            .put("lit", s.turnLit ?: JSONObject.NULL)
            .put("gear", s.gear?.toString() ?: JSONObject.NULL)
            .put("steer", s.steerDeg?.let { r(it.toDouble(), 10.0) } ?: JSONObject.NULL)
            .put("speed", s.speedKmh?.let { r(it.toDouble(), 10.0) } ?: JSONObject.NULL)
            .put("pdc", JSONObject().put("f", pdcJson(s.pdcFront)).put("r", pdcJson(s.pdcRear)))

        private fun pdcJson(a: FloatArray?): Any {
            if (a == null) return JSONObject.NULL
            val arr = JSONArray()
            for (d in a) arr.put(
                when {
                    d.isNaN() || d == Float.NEGATIVE_INFINITY -> JSONObject.NULL   // 故障
                    d >= PDC_CLEAR -> PDC_CLEAR                                     // 含 +∞
                    else -> r(max(d.toDouble(), 0.0), 100.0)
                }
            )
            return arr
        }

        private fun r(v: Double, k: Double) = round(v * k) / k
    }
}

/**
 * 模擬來源：40 秒循環的劇本（前進左轉 → 換檔 → 倒車入庫：方向盤來回打、後方牆面接近、
 * 後段前左雷達故障 → 雙黃燈 → 右轉（先高速、再低速）→ 訊號中斷）。
 * 方向燈照真實情況一亮一暗，靠 VehicleSignals 撐過暗的那段。vehicle-demo.html 用同一份劇本。
 * clock 只用來算劇本進度；送進 sink 的時間一律是 sink.now()。
 */
class SimulatedSource(
    private val periodMs: Long = 50L,
    private val clock: () -> Long = VehicleSignals.MONOTONIC_MS
) : VehicleSignalAdapter {
    override val name: String = "模擬"
    private var worker: Thread? = null

    override fun start(sink: VehicleSignals) = synchronized(this) {
        worker?.interrupt()
        val t0 = clock()
        worker = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    stateAt(clock() - t0)?.let { sink.reportAll(it.copy(atMs = sink.now())) }
                    Thread.sleep(periodMs)
                }
            } catch (_: InterruptedException) {
            }
        }, "VehicleSim").apply { isDaemon = true; start() }
    }

    override fun stop() = synchronized(this) {
        worker?.interrupt()
        worker = null
    }

    companion object {
        const val CYCLE_MS = 40_000L
        const val BLINK_MS = 667L                                 // 1.5 Hz
        val SENSOR_X = floatArrayOf(-0.75f, -0.3f, 0.3f, 0.75f)    // 感測器大約的橫向位置（m）
        const val FAULT_FROM_MS = 17_000L                          // 倒車後段：前左雷達故障（NaN）

        /** 不開執行緒，直接把劇本 tMs 時的狀態以 atMs 送進 sink（測試用） */
        fun pump(sink: VehicleSignals, tMs: Long, atMs: Long = tMs) {
            stateAt(tMs)?.let { sink.reportAll(it.copy(atMs = atMs)) }
        }

        fun phaseName(tMs: Long): String {
            val t = Math.floorMod(tMs, CYCLE_MS)
            return when {
                t < 7_000 -> "前進左轉"
                t < 9_000 -> "換檔"
                t < 25_000 -> "倒車入庫"
                t < 31_000 -> "雙黃燈"
                t < 37_000 -> "右轉"
                else -> "訊號中斷"
            }
        }

        /** 劇本 tMs 時「原始」的訊號（方向燈會一亮一暗）；null = 這段沒有任何訊號 */
        fun stateAt(tMs: Long): VehicleState? {
            val t = Math.floorMod(tMs, CYCLE_MS)
            val s = t / 1000.0
            fun blink(c: Char, start: Long): Char? = if ((t - start) % BLINK_MS < BLINK_MS / 2) c else null
            return when {
                t < 7_000 -> VehicleState(turn = blink('L', 0), gear = 'D',
                    steerDeg = (-240.0 * sin(PI * s / 7.0)).toFloat(), speedKmh = 8f)
                t < 9_000 -> VehicleState(gear = 'N', steerDeg = 0f, speedKmh = 0f)
                t < 25_000 -> {
                    val u = (s - 9.0) / 16.0
                    val b = 2.2 - 2.05 * u                              // 後方牆面 2.2 m → 0.15 m，稍微斜的
                    val rear = FloatArray(4) { i ->
                        val d = b + 0.2 * SENSOR_X[i]
                        if (d >= 2.0) Float.POSITIVE_INFINITY else max(d, 0.05).toFloat()
                    }
                    val inf = Float.POSITIVE_INFINITY
                    val fl = if (t >= FAULT_FROM_MS) Float.NaN else inf
                    VehicleState(gear = 'R', steerDeg = (480.0 * sin(2.0 * PI * (s - 9.0) / 8.0)).toFloat(),
                        speedKmh = 4f, pdcFront = floatArrayOf(fl, inf, inf, 1.05f), pdcRear = rear)
                }
                t < 31_000 -> VehicleState(turn = blink('H', 25_000), gear = 'P', steerDeg = 0f, speedKmh = 0f)
                t < 37_000 -> VehicleState(turn = blink('R', 31_000), gear = 'D', steerDeg = 120f,
                    speedKmh = if (t < 34_000) 22f else 10f)
                else -> null
            }
        }
    }
}

/** 自車幾何，預設 BMW 525i E60。 */
data class VehicleGeometry(
    val length: Double = 4.841,
    val width: Double = 1.846,
    val wheelbase: Double = 2.888,
    val rearOverhang: Double = 1.12,
    val track: Double = 1.56,
    val steerRatio: Double = 15.0,     // 方向盤角 / 前輪角（2.9 圈 lock-to-lock ≈ 15:1，假設值）
    val maxSteerDeg: Double = 522.0    // 單邊打到底的方向盤角（2.9 圈的一半，假設值）
) {
    val frontOverhang: Double get() = length - wheelbase - rearOverhang
    val rearAxleZ: Double get() = -length / 2 + rearOverhang
    val maxWheelDeg: Double get() = maxSteerDeg / steerRatio

    /**
     * 用實測值校正：方向盤單邊打到底的角度 lockSteerDeg（度）+ 打到底時的迴轉直徑 turningCircleM（外側前輪，m）
     * → 反推 steerRatio，maxSteerDeg = lockSteerDeg。
     */
    fun calibratedToLock(lockSteerDeg: Double, turningCircleM: Double): VehicleGeometry {
        val wheel = BicycleModel.wheelAngleForTurningCircle(turningCircleM, this)
        require(lockSteerDeg > 0) { "lockSteerDeg 要 > 0：$lockSteerDeg" }
        return copy(steerRatio = lockSteerDeg / wheel, maxSteerDeg = lockSteerDeg)
    }

    /** AVM_VEH.init() 的 opts（幾何部分），讓 vehicle3d.js 跟這裡用同一組數值 */
    fun jsOpts(): JSONObject = JSONObject()
        .put("length", length).put("width", width).put("wheelbase", wheelbase)
        .put("rearOverhang", rearOverhang).put("track", track)
        .put("steerRatio", steerRatio).put("maxSteerDeg", maxSteerDeg)
}

/** 輔助線取樣設定：沿弧線 0..lengthM，每 stepM 一點；markersM = 距離標記；swingM = 倒車時車頭甩出弧的長度（0 = 不算）。 */
data class GuideConfig(
    val lengthM: Double = 5.0,
    val stepM: Double = 0.1,
    val markersM: List<Double> = listOf(0.5, 1.0, 2.0, 3.0),
    val swingM: Double = 2.5
)

data class PointXZ(val x: Double, val z: Double)

/** 保險桿中心沿路徑走 distM 時，左右車身邊緣的位置 */
data class GuideMarker(val distM: Double, val left: PointXZ, val right: PointXZ)

/**
 * dist[i] = 保險桿中心沿弧線走的距離；left/right[i] = 當時左右保險桿角的位置（車身座標）。
 * inner[i] = 當時內側車身在後軸處（整台車離轉彎中心最近的點，內輪差的邊界）的位置；
 *   innerSide = +1 右 / -1 左；直行時 inner = null、innerSide = 0。
 * swing = 倒車時外側前保險桿角的甩出弧（dist ≤ swingM 那幾點）；前進或直行 = null。
 */
data class GuideLines(
    val reverse: Boolean,
    val dist: List<Double>,
    val left: List<PointXZ>,
    val right: List<PointXZ>,
    val markers: List<GuideMarker>,
    val innerSide: Int = 0,
    val inner: List<PointXZ>? = null,
    val swing: List<PointXZ>? = null
)

/**
 * 單車模型（後軸中心為參考點，前輪角 = 方向盤角 / 轉向比，夾在打到底的角度內）。
 * 輔助線：前進從前保險桿、倒車從後保險桿的左右兩角出發，以「保險桿中心沿弧線走的距離」取樣；
 * 另外算內側車身（後軸處）的軌跡與倒車時的車頭甩出弧，參數同一個。
 * vehicle3d.js 的 guides() 是同一套算式（含運算順序），兩邊一致性由 VehicleSignalsTest 檢查。
 */
object BicycleModel {
    private const val STRAIGHT_K = 1e-12

    /** 前輪角（度，+ = 向右）；方向盤角不明 = 0 */
    fun wheelAngleDeg(steerDeg: Double?, g: VehicleGeometry = VehicleGeometry()): Double {
        if (steerDeg == null || !steerDeg.isFinite()) return 0.0
        val maxW = g.maxSteerDeg / g.steerRatio
        return (steerDeg / g.steerRatio).coerceIn(-maxW, maxW)
    }

    /** 後軸中心軌跡曲率（1/m，+ = 向右彎） */
    fun curvature(steerDeg: Double?, g: VehicleGeometry = VehicleGeometry()): Double =
        tan(wheelAngleDeg(steerDeg, g) * PI / 180.0) / g.wheelbase

    /** 後軸中心的轉彎半徑（m，+ = 向右；直行 = +∞）。轉彎中心在 (R, rearAxleZ)。 */
    fun turningRadius(steerDeg: Double?, g: VehicleGeometry = VehicleGeometry()): Double {
        val k = curvature(steerDeg, g)
        return if (abs(k) < STRAIGHT_K) Double.POSITIVE_INFINITY else 1.0 / k
    }

    /** 迴轉直徑（外側前輪軌跡，curb-to-curb，m）；直行 = +∞ */
    fun turningCircle(steerDeg: Double?, g: VehicleGeometry = VehicleGeometry()): Double {
        val r = abs(turningRadius(steerDeg, g))
        if (r.isInfinite()) return Double.POSITIVE_INFINITY
        val a = r + g.track / 2
        return 2.0 * sqrt(a * a + g.wheelbase * g.wheelbase)
    }

    /** turningCircle 的反函數：迴轉直徑 D → 前輪角（度）。δ = atan(wb / (√((D/2)² − wb²) − track/2)) */
    fun wheelAngleForTurningCircle(turningCircleM: Double, g: VehicleGeometry = VehicleGeometry()): Double {
        val h = turningCircleM / 2
        require(h > g.wheelbase) { "迴轉直徑太小：$turningCircleM" }
        val r = sqrt(h * h - g.wheelbase * g.wheelbase) - g.track / 2
        require(r > 0) { "迴轉直徑太小：$turningCircleM" }
        return atan(g.wheelbase / r) * 180.0 / PI
    }

    fun guideLines(
        steerDeg: Double?,
        reverse: Boolean,
        g: VehicleGeometry = VehicleGeometry(),
        cfg: GuideConfig = GuideConfig()
    ): GuideLines {
        val k = curvature(steerDeg, g)
        val dir = if (reverse) -1.0 else 1.0
        val zra = g.rearAxleZ
        val bz = (if (reverse) -g.length / 2 else g.length / 2) - zra   // 保險桿相對後軸
        val fz = g.length / 2 - zra                                      // 前保險桿相對後軸
        val hw = g.width / 2
        val side = if (abs(k) < STRAIGHT_K) 0 else if (k > 0) 1 else -1  // 轉彎中心在哪一側 = 內側
        val len = max(cfg.lengthM, 0.0)
        val n = max(1, ceil(len / cfg.stepM - 1e-9).toInt())
        val dist = ArrayList<Double>(n + 1)
        val left = ArrayList<PointXZ>(n + 1)
        val right = ArrayList<PointXZ>(n + 1)
        val inner = if (side != 0) ArrayList<PointXZ>(n + 1) else null
        val swing = if (side != 0 && reverse && cfg.swingM > 0) ArrayList<PointXZ>() else null
        val p = DoubleArray(4)
        val tmp = DoubleArray(2)
        for (i in 0..n) {
            val s = len * i / n
            pose(s, k, dir, bz, p)
            dist.add(s)
            bodyPoint(p, zra, -hw, bz, tmp); left.add(PointXZ(tmp[0], tmp[1]))
            bodyPoint(p, zra, hw, bz, tmp); right.add(PointXZ(tmp[0], tmp[1]))
            if (inner != null) { bodyPoint(p, zra, side * hw, 0.0, tmp); inner.add(PointXZ(tmp[0], tmp[1])) }
            if (swing != null && s <= cfg.swingM + 1e-9) {
                bodyPoint(p, zra, -side * hw, fz, tmp); swing.add(PointXZ(tmp[0], tmp[1]))
            }
        }
        val markers = ArrayList<GuideMarker>()
        for (m in cfg.markersM) {
            if (m < 0 || m > len + 1e-9) continue
            pose(m, k, dir, bz, p)
            bodyPoint(p, zra, -hw, bz, tmp); val l = PointXZ(tmp[0], tmp[1])
            bodyPoint(p, zra, hw, bz, tmp)
            markers.add(GuideMarker(m, l, PointXZ(tmp[0], tmp[1])))
        }
        return GuideLines(reverse, dist, left, right, markers, side, inner, swing)
    }

    /**
     * 保險桿中心（相對後軸 z = bz）沿弧線走 s 後的車身姿態 → out = [ax, az, cos ψ, sin ψ]：
     * 後軸中心走 sA = s / √(1 + (bz·k)²)（帶方向），航向 ψ = k·sA，後軸位移 (2 sin²(ψ/2) / k, sin ψ / k)。
     */
    internal fun pose(s: Double, k: Double, dir: Double, bz: Double, out: DoubleArray) {
        val q = bz * k
        val sA = dir * s / sqrt(1 + q * q)
        val psi = k * sA
        if (abs(k) < STRAIGHT_K) {
            out[0] = 0.0
            out[1] = sA
        } else {
            val h = sin(psi / 2)
            out[0] = 2 * h * h / k
            out[1] = sin(psi) / k
        }
        out[2] = cos(psi)
        out[3] = sin(psi)
    }

    /** 車身點（px 右、pz 相對後軸往前）在姿態 p 下的位置 → out = [x, z]（車身座標） */
    internal fun bodyPoint(p: DoubleArray, zra: Double, px: Double, pz: Double, out: DoubleArray) {
        out[0] = p[0] + px * p[2] + pz * p[3]
        out[1] = zra + p[1] - px * p[3] + pz * p[2]
    }
}
