package com.vito.avmprobe

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.abs
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
 * pdcFront / pdcRear：4 顆感測器由左到右，公尺；Float.POSITIVE_INFINITY = 無障礙；整個 null = 雷達未啟動。
 */
data class VehicleState(
    val turn: Char? = null,          // 'L' 'R' 'H'（雙黃燈）
    val gear: Char? = null,          // 'P' 'R' 'N' 'D'
    val steerDeg: Float? = null,     // 方向盤角度，+ = 向右
    val speedKmh: Float? = null,
    val pdcFront: FloatArray? = null,
    val pdcRear: FloatArray? = null,
    val atMs: Long = 0L              // 這份狀態的時間（snapshot 的當下）
) {
    /** 全部欄位都沒資料 */
    val isEmpty: Boolean
        get() = turn == null && gear == null && steerDeg == null && speedKmh == null &&
            pdcFront == null && pdcRear == null

    /** 前（front = true）或後雷達的最近距離；雷達未啟動或全部無障礙 = null */
    fun pdcMin(front: Boolean): Float? {
        val a = (if (front) pdcFront else pdcRear) ?: return null
        var m = Float.POSITIVE_INFINITY
        for (d in a) if (d < m) m = d
        return if (m.isFinite()) m else null
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VehicleState) return false
        return turn == other.turn && gear == other.gear && steerDeg == other.steerDeg &&
            speedKmh == other.speedKmh && atMs == other.atMs &&
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
        return h
    }

    override fun toString(): String =
        "VehicleState(turn=$turn, gear=$gear, steerDeg=$steerDeg, speedKmh=$speedKmh, " +
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
 * 未來的實際資料來源（CAN 盒廣播、序列埠、廠商 App…）實作這個介面，
 * start() 之後在任何執行緒呼叫 sink.reportXxx() 回報解碼結果。
 */
interface VehicleSignalAdapter {
    val name: String
    fun start(sink: VehicleSignals)
    fun stop()
}

/**
 * 執行緒安全的車身訊號狀態。來源回報各欄位，使用端用 snapshot() 取「現在」的狀態
 * （過期欄位為 null）。過期不會主動通知，畫面端每幀 snapshot() 即可。
 */
class VehicleSignals(
    val timeouts: SignalTimeouts = SignalTimeouts(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    fun interface Listener {
        fun onVehicleState(state: VehicleState)
    }

    private class Field<T> {
        var value: T? = null
        var at: Long = NEVER

        /** 比目前紀錄還舊的回報（亂序）不採用 */
        fun set(v: T?, atMs: Long): Boolean {
            if (at != NEVER && atMs < at) return false
            value = v
            at = atMs
            return true
        }

        fun get(now: Long, timeoutMs: Long): T? {
            val v = value ?: return null
            return if (now - at <= timeoutMs) v else null
        }

        fun clear() { value = null; at = NEVER }
    }

    private val lock = Any()
    private val turn = Field<Char>()
    private val gear = Field<Char>()
    private val steer = Field<Float>()
    private val speed = Field<Float>()
    private val pdcFront = Field<FloatArray>()
    private val pdcRear = Field<FloatArray>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private var adapter: VehicleSignalAdapter? = null

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
        if (turn == null) return
        change(atMs) { this.turn.set(turn, atMs) }
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

    /** 方向盤角度（度，+ = 向右）；null 或 NaN = 不明 */
    fun reportSteer(deg: Float?, atMs: Long = clock()) =
        change(atMs) { steer.set(deg?.takeIf { !it.isNaN() }, atMs) }

    fun reportSpeed(kmh: Float?, atMs: Long = clock()) =
        change(atMs) { speed.set(kmh?.takeIf { !it.isNaN() }, atMs) }

    /** 前雷達 4 顆（左→右，公尺，POSITIVE_INFINITY = 無障礙）；null = 雷達未啟動 */
    fun reportPdcFront(d: FloatArray?, atMs: Long = clock()) =
        change(atMs) { pdcFront.set(d?.copyOf(), atMs) }

    fun reportPdcRear(d: FloatArray?, atMs: Long = clock()) =
        change(atMs) { pdcRear.set(d?.copyOf(), atMs) }

    fun reportPdc(front: FloatArray?, rear: FloatArray?, atMs: Long = clock()) = change(atMs) {
        pdcFront.set(front?.copyOf(), atMs)
        pdcRear.set(rear?.copyOf(), atMs)
    }

    /**
     * 來源一次給完整狀態（模擬、或一包就有全部欄位的協定）。時間用 s.atMs。
     * turn = null 當作燈暗（照樣保持）；其他欄位 null = 不明 / 雷達未啟動。
     */
    fun reportAll(s: VehicleState) {
        val t = s.atMs
        require(s.turn == null || s.turn == 'L' || s.turn == 'R' || s.turn == 'H') { "turn 只能是 L/R/H/null：${s.turn}" }
        require(s.gear == null || s.gear in "PRND") { "gear 只能是 P/R/N/D/null：${s.gear}" }
        change(t) {
            if (s.turn != null) turn.set(s.turn, t)
            gear.set(s.gear, t)
            steer.set(s.steerDeg?.takeIf { !it.isNaN() }, t)
            speed.set(s.speedKmh?.takeIf { !it.isNaN() }, t)
            pdcFront.set(s.pdcFront?.copyOf(), t)
            pdcRear.set(s.pdcRear?.copyOf(), t)
        }
    }

    /** 取 nowMs 當下的狀態，過期欄位為 null。陣列是複本，可以隨意改。 */
    fun snapshot(nowMs: Long = clock()): VehicleState = synchronized(lock) { snap(nowMs) }

    fun reset() = synchronized(lock) {
        turn.clear(); gear.clear(); steer.clear(); speed.clear(); pdcFront.clear(); pdcRear.clear()
    }

    private fun snap(now: Long) = VehicleState(
        turn = turn.get(now, timeouts.turnMs),
        gear = gear.get(now, timeouts.gearMs),
        steerDeg = steer.get(now, timeouts.steerMs),
        speedKmh = speed.get(now, timeouts.speedMs),
        pdcFront = pdcFront.get(now, timeouts.pdcMs)?.copyOf(),
        pdcRear = pdcRear.get(now, timeouts.pdcMs)?.copyOf(),
        atMs = now
    )

    private inline fun change(atMs: Long, block: () -> Unit) {
        val s = synchronized(lock) {
            block()
            if (listeners.isEmpty()) null else snap(max(atMs, latestAt()))
        } ?: return
        for (l in listeners) {
            try {
                l.onVehicleState(s)
            } catch (e: RuntimeException) {
                // 單一 listener 出錯不影響其他人與來源執行緒
            }
        }
    }

    private fun latestAt(): Long =
        maxOf(turn.at, gear.at, steer.at, speed.at, max(pdcFront.at, pdcRear.at))

    companion object {
        private const val NEVER = Long.MIN_VALUE / 4

        /** JSON 裡雷達「無障礙」用 99 表示 */
        const val PDC_CLEAR = 99.0

        /** 3D 面板 payload 的 veh 欄位：{ turn, gear, steer, speed, pdc: { f: [..]|null, r: [..]|null } } */
        fun toJson(s: VehicleState): JSONObject = JSONObject()
            .put("turn", s.turn?.toString() ?: JSONObject.NULL)
            .put("gear", s.gear?.toString() ?: JSONObject.NULL)
            .put("steer", s.steerDeg?.let { r(it.toDouble(), 10.0) } ?: JSONObject.NULL)
            .put("speed", s.speedKmh?.let { r(it.toDouble(), 10.0) } ?: JSONObject.NULL)
            .put("pdc", JSONObject().put("f", pdcJson(s.pdcFront)).put("r", pdcJson(s.pdcRear)))

        private fun pdcJson(a: FloatArray?): Any {
            if (a == null) return JSONObject.NULL
            val arr = JSONArray()
            for (d in a) arr.put(if (!d.isFinite() || d >= PDC_CLEAR) PDC_CLEAR else r(max(d.toDouble(), 0.0), 100.0))
            return arr
        }

        private fun r(v: Double, k: Double) = round(v * k) / k
    }
}

/**
 * 模擬來源：40 秒循環的劇本（前進左轉 → 換檔 → 倒車入庫：方向盤來回打、後方牆面接近 → 雙黃燈 →
 * 右轉（先高速、再低速）→ 訊號中斷）。方向燈照真實情況一亮一暗，靠 VehicleSignals 撐過暗的那段。
 * vehicle-demo.html 用同一份劇本。
 */
class SimulatedSource(
    private val periodMs: Long = 50L,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : VehicleSignalAdapter {
    override val name: String = "模擬"
    private var worker: Thread? = null

    override fun start(sink: VehicleSignals) = synchronized(this) {
        worker?.interrupt()
        val t0 = clock()
        worker = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val now = clock()
                    stateAt(now - t0)?.let { sink.reportAll(it.copy(atMs = now)) }
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
                    VehicleState(gear = 'R', steerDeg = (480.0 * sin(2.0 * PI * (s - 9.0) / 8.0)).toFloat(),
                        speedKmh = 4f, pdcFront = floatArrayOf(inf, inf, inf, 1.05f), pdcRear = rear)
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
    val steerRatio: Double = 15.0,     // 方向盤角 / 前輪角（2.9 圈 lock-to-lock ≈ 15:1）
    val maxSteerDeg: Double = 522.0    // 單邊打到底的方向盤角（2.9 圈的一半）
) {
    val frontOverhang: Double get() = length - wheelbase - rearOverhang
    val rearAxleZ: Double get() = -length / 2 + rearOverhang
    val maxWheelDeg: Double get() = maxSteerDeg / steerRatio
}

/** 輔助線取樣設定：沿弧線 0..lengthM，每 stepM 一點；markersM = 距離標記。 */
data class GuideConfig(
    val lengthM: Double = 5.0,
    val stepM: Double = 0.1,
    val markersM: List<Double> = listOf(0.5, 1.0, 2.0, 3.0)
)

data class PointXZ(val x: Double, val z: Double)

/** 保險桿中心沿路徑走 distM 時，左右車身邊緣的位置 */
data class GuideMarker(val distM: Double, val left: PointXZ, val right: PointXZ)

/** dist[i] = 保險桿中心沿弧線走的距離；left/right[i] = 當時左右保險桿角的位置（車身座標）。 */
data class GuideLines(
    val reverse: Boolean,
    val dist: List<Double>,
    val left: List<PointXZ>,
    val right: List<PointXZ>,
    val markers: List<GuideMarker>
)

/**
 * 單車模型（後軸中心為參考點，前輪角 = 方向盤角 / 轉向比，夾在打到底的角度內）。
 * 輔助線：前進從前保險桿、倒車從後保險桿的左右兩角出發，以「保險桿中心沿弧線走的距離」取樣。
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
        val hw = g.width / 2
        val len = max(cfg.lengthM, 0.0)
        val n = max(1, ceil(len / cfg.stepM - 1e-9).toInt())
        val dist = ArrayList<Double>(n + 1)
        val left = ArrayList<PointXZ>(n + 1)
        val right = ArrayList<PointXZ>(n + 1)
        val tmp = DoubleArray(4)
        for (i in 0..n) {
            val s = len * i / n
            corners(s, k, dir, bz, zra, hw, tmp)
            dist.add(s)
            left.add(PointXZ(tmp[0], tmp[1]))
            right.add(PointXZ(tmp[2], tmp[3]))
        }
        val markers = ArrayList<GuideMarker>()
        for (m in cfg.markersM) {
            if (m < 0 || m > len + 1e-9) continue
            corners(m, k, dir, bz, zra, hw, tmp)
            markers.add(GuideMarker(m, PointXZ(tmp[0], tmp[1]), PointXZ(tmp[2], tmp[3])))
        }
        return GuideLines(reverse, dist, left, right, markers)
    }

    /**
     * 保險桿中心沿弧線走 s 後，左右保險桿角的位置 → out = [lx, lz, rx, rz]。
     * 後軸中心走 sA = s / √(1 + (bz·k)²)（帶方向），航向 ψ = k·sA，
     * 後軸位移 (2 sin²(ψ/2) / k, sin ψ / k)，車身點 (bx, bz) 轉 ψ 後疊上去。
     */
    internal fun corners(s: Double, k: Double, dir: Double, bz: Double, zra: Double, hw: Double, out: DoubleArray) {
        val q = bz * k
        val sA = dir * s / sqrt(1 + q * q)
        val psi = k * sA
        val ax: Double
        val az: Double
        if (abs(k) < STRAIGHT_K) {
            ax = 0.0
            az = sA
        } else {
            val h = sin(psi / 2)
            ax = 2 * h * h / k
            az = sin(psi) / k
        }
        val c = cos(psi)
        val sn = sin(psi)
        val lx = -hw
        out[0] = ax + lx * c + bz * sn
        out[1] = zra + az - lx * sn + bz * c
        out[2] = ax + hw * c + bz * sn
        out[3] = zra + az - hw * sn + bz * c
    }
}
