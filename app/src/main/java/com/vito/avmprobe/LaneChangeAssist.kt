package com.vito.avmprobe

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.tan

/**
 * 變換車道輔助狀態。L/R：0 無、1 注意（盲點有車或後方來車，琥珀色）、2 危險（紅色）。
 * intent：想往哪邊換（方向燈或偏移），path：預測換道路徑 [x, z]（車身座標，每 1 m 一點）。
 * pathOk：該側沒有危險。threat：最急迫的後方來車與其 TTC（s）。zoneL/zoneR：[xMin, zMin, xMax, zMax]。
 */
data class LcaState(
    val L: Int, val R: Int, val intent: Char?, val path: List<FloatArray>?, val pathOk: Boolean,
    val threatId: Int?, val threatTtc: Float?, val zoneL: FloatArray, val zoneR: FloatArray,
    /** 自車相對車道的橫向速度（m/s，+ = 往右）；沒有車道線為 null。 */
    val latVel: Float? = null,
    val laneW: Float = LaneChangeAssist.LANE_W
)

/**
 * 盲點偵測 + 後方來車預警 + 換道路徑預測（純 Kotlin）。
 *
 * 盲點區：相鄰車道（有車道線就用，否則以自車為中心的 3.5 m 車道），縱向從前車門旁 +1 m 到後保桿後 10 m。
 * 後方來車：相鄰車道、在後保桿後方、接近速度 > 1 m/s 且 TTC < 3.5 s。
 * 等級 2：等級 1 的條件 + 往該側的意圖（方向燈、往該側車道線偏移 > 0.25 m/s 持續 0.5 s、方向盤往該側），
 * 或 TTC < 2 s。每側等級連續 2 次更新才亮、清除 0.8 s 後才熄。
 * 跟 ISO 17387 / 一般 BSM 一樣不警示靜止物和自車正在明顯超越（相對速度 > 10 km/h）的車。
 *
 * 感測現實（每顆鏡頭 2–4 Hz、遠處 1–2 m 誤差）的對策：TTC 用的接近速度扣掉軌跡速度不確定度超出
 * 穩定值的部分（新軌跡不會因為兩點估錯速度就亮紅），盲點佔用要 3 次命中（兩個誤報湊成的軌跡不算）。
 *
 * 方向盤角 steerDeg：+ = 向右。laneAngleDeg（選用）：車道線在車身座標的角度，+ = 越往前越偏右。
 * 建議每次 TrackManager.update 之後呼叫一次。
 */
class LaneChangeAssist {

    companion object {
        const val EGO_HL = 2.42f
        const val LANE_W = 3.5f
        const val ZONE_FRONT = 1.0f        // 盲點區前緣（前車門旁）
        const val ZONE_BACK = 10f          // 後保桿後 10 m
        const val CLOSE_V = 1.0f           // 接近速度門檻 m/s
        const val TTC_WARN = 3.5f
        const val TTC_DANGER = 2.0f
        const val DRIFT_V = 0.25f          // 往車道線偏移速度 m/s
        const val DRIFT_MS = 500L
        const val STEER_DEG = 8f           // 方向盤往該側超過這個角度算有意圖
        const val OVERTAKE_V = 2.8f        // 自車以 > 10 km/h 超越的車不警示
        const val STILL_V = 1.0f           // 對地速度低於此 = 靜止物
        const val CROSS_V = 3.0f           // 橫向相對速度確定超過此 = 橫越車流，不列入
        const val SV_FREE = 0.6            // 穩定軌跡的速度標準差，不扣
        const val MIN_HITS = 3             // 盲點佔用：只有兩次命中的軌跡常是誤報湊成的（來車 TTC 另有速度不確定度把關）
        const val ON_COUNT = 2
        const val OFF_MS = 800L
        const val TURN_HOLD_MS = 1200L     // 方向燈閃爍的暗相也算打著
        const val LANE_HOLD_MS = 1500L
        private const val LANE_Q = 0.8 * 0.8     // 橫向加速度變異（車道濾波）
        private const val LANE_R = 0.05 * 0.05   // 車道線位置量測變異
        private val LEVEL_TYPES = setOf(TrackManager.G_VEH, TrackManager.G_TWO)
    }

    private class Side {
        val cnt = IntArray(3)
        val lastOn = LongArray(3)
        val active = BooleanArray(3)
        fun level(raw: Int, now: Long): Int {
            for (k in 1..2) {
                if (raw >= k) { cnt[k]++; lastOn[k] = now } else cnt[k] = 0
                active[k] = if (active[k]) now - lastOn[k] <= OFF_MS else cnt[k] >= ON_COUNT
            }
            return if (active[2]) 2 else if (active[1]) 1 else 0
        }
        fun reset() { cnt.fill(0); lastOn.fill(0); active.fill(false) }
    }

    /** 每條軌跡的遲滯狀態：在哪一側（-1 都不是、0 左、1 右）、是否被抑制（靜止物 / 正被超越）。 */
    private class Mem(var side: Int = -1, var supp: Boolean = false, var seen: Long = 0L)

    // 車道濾波：車道中心在車身座標的 x（c）與其變化率（cv）
    private var laneInit = false
    private var c = 0.0
    private var cv = 0.0
    private var p00 = 1.0
    private var p01 = 0.0
    private var p11 = 1.0
    private var laneW = LANE_W.toDouble()
    private var laneAt = Long.MIN_VALUE / 2
    private var lastObsL: Float? = null
    private var lastObsR: Float? = null
    private var lastMs = Long.MIN_VALUE

    private var driftLAt = -1L
    private var driftRAt = -1L
    private var turnLAt = Long.MIN_VALUE / 2
    private var turnRAt = Long.MIN_VALUE / 2
    private val sideL = Side()
    private val sideR = Side()
    private val mem = HashMap<Int, Mem>()

    fun reset() {
        laneInit = false
        laneAt = Long.MIN_VALUE / 2
        lastObsL = null; lastObsR = null
        lastMs = Long.MIN_VALUE
        driftLAt = -1L; driftRAt = -1L
        turnLAt = Long.MIN_VALUE / 2; turnRAt = Long.MIN_VALUE / 2
        sideL.reset(); sideR.reset()
        mem.clear()
        laneW = LANE_W.toDouble()
    }

    /**
     * tracks：TrackManager 輸出（只用確認過的汽車與二輪）。laneLeftX/laneRightX：最近的左/右車道線在
     * z = 0 處的橫向位置（m，左負右正），沒有就 null。turn：方向燈 'L'/'R'/null（閃爍沒關係）。
     */
    fun update(
        tracks: List<Track>, nowMs: Long, laneLeftX: Float?, laneRightX: Float?, turn: Char?,
        steerDeg: Float?, egoSpeedMps: Float?, laneAngleDeg: Float? = null
    ): LcaState {
        val dt = if (lastMs == Long.MIN_VALUE) 0.0 else (nowMs - lastMs).coerceAtLeast(0L) / 1000.0
        lastMs = if (lastMs == Long.MIN_VALUE) nowMs else max(lastMs, nowMs)
        val ego = egoSpeedMps?.takeIf { it.isFinite() && it >= 0f }?.toDouble()
        val laneOk = updateLane(laneLeftX, laneRightX, nowMs, dt)

        // 橫向速度：車道中心往右移 = 自車往左
        var latVel: Double? = if (laneOk) -cv else null
        val ang = laneAngleDeg?.takeIf { it.isFinite() && abs(it) < 30f }?.let { Math.toRadians(it.toDouble()) }
        if (ang != null && ego != null && ego > 3.0) {
            val va = -ego * sin(ang)
            latVel = latVel?.let { (it + va) / 2 } ?: va
        }

        // 偏移意圖：往該側 > 0.25 m/s 持續 0.5 s
        if (latVel != null && latVel < -DRIFT_V) { if (driftLAt < 0) driftLAt = nowMs } else driftLAt = -1L
        if (latVel != null && latVel > DRIFT_V) { if (driftRAt < 0) driftRAt = nowMs } else driftRAt = -1L
        val driftL = driftLAt >= 0 && nowMs - driftLAt >= DRIFT_MS
        val driftR = driftRAt >= 0 && nowMs - driftRAt >= DRIFT_MS

        if (turn == 'L' || turn == 'l') turnLAt = nowMs
        if (turn == 'R' || turn == 'r') turnRAt = nowMs
        val tL = nowMs - turnLAt <= TURN_HOLD_MS
        val tR = nowMs - turnRAt <= TURN_HOLD_MS
        val st = steerDeg?.takeIf { it.isFinite() }
        val steerL = st != null && st <= -STEER_DEG
        val steerR = st != null && st >= STEER_DEG
        val intent: Char? = when {
            tL && tR -> if (turnLAt >= turnRAt) 'L' else 'R'
            tL -> 'L'
            tR -> 'R'
            driftL && !driftR -> 'L'
            driftR && !driftL -> 'R'
            else -> null
        }
        val towardL = tL || driftL || steerL
        val towardR = tR || driftR || steerR

        // 盲點區幾何
        val cx = if (laneOk) c else 0.0
        val w = if (laneOk) laneW else LANE_W.toDouble()
        val lLine = cx - w / 2
        val rLine = cx + w / 2
        val zMin = (-EGO_HL - ZONE_BACK).toDouble()
        val zMax = ZONE_FRONT.toDouble()
        val zoneL = floatArrayOf((lLine - w).toFloat(), zMin.toFloat(), lLine.toFloat(), zMax.toFloat())
        val zoneR = floatArrayOf(rLine.toFloat(), zMin.toFloat(), (rLine + w).toFloat(), zMax.toFloat())

        var rawL = 0
        var rawR = 0
        var threatId: Int? = null
        var threatTtc = Double.MAX_VALUE
        for (tr in tracks) {
            if (!tr.confirmed || TrackManager.group(tr.t) !in LEVEL_TYPES) continue
            val m = mem.getOrPut(tr.id) { Mem() }
            m.seen = nowMs
            if (abs(tr.vx) - tr.svx > CROSS_V) continue   // 橫越車流 / 不可信的軌跡，跟換道無關
            val x = tr.x.toDouble()
            fun inL(e: Double) = x <= lLine + 0.3 + e && x >= lLine - w - 0.6 - e
            fun inR(e: Double) = x >= rLine - 0.3 - e && x <= rLine + w + 0.6 + e
            m.side = when {
                m.side == 0 && inL(0.3) -> 0
                m.side == 1 && inR(0.3) -> 1
                inL(0.0) -> 0
                inR(0.0) -> 1
                else -> -1
            }
            val vz = tr.vz.toDouble()
            val sog = if (ego != null) (tr.sog?.toDouble() ?: hypot(tr.vx.toDouble(), vz + ego)) else null
            val moving = ego != null && ego > 3.0
            m.supp = if (m.supp) {
                vz < -OVERTAKE_V + 0.5 || (moving && sog!! < STILL_V + 0.6)
            } else {
                vz < -OVERTAKE_V - 0.3 || (moving && sog!! < STILL_V)
            }
            if (m.side < 0) continue

            val hl = (if (tr.l > 0f) tr.l else TrackManager.size(tr.t)[1]) / 2.0
            val front = tr.z + hl
            val rear = tr.z - hl
            var lvl = if (front >= zMin && rear <= zMax && !m.supp && tr.hits >= MIN_HITS) 1 else 0
            // 接近速度取保守值：剛出現的遠處軌跡速度誤差 2–4 m/s，扣掉超出穩定軌跡（~0.6 m/s）的不確定度
            val range = -EGO_HL - front
            val vc = vz - max(0.0, tr.svz - SV_FREE)
            var ttc = Double.MAX_VALUE
            if (range > 0 && vc > CLOSE_V) {
                ttc = range / vc
                if (ttc < TTC_WARN) lvl = 1 else ttc = Double.MAX_VALUE
            }
            if (lvl == 0) continue
            val toward = if (m.side == 0) towardL else towardR
            if (toward || ttc < TTC_DANGER) lvl = 2
            if (m.side == 0) rawL = max(rawL, lvl) else rawR = max(rawR, lvl)
            if (ttc < threatTtc) { threatTtc = ttc; threatId = tr.id }
        }
        mem.entries.removeAll { nowMs - it.value.seen > 3000 }

        val lv = sideL.level(rawL, nowMs)
        val rv = sideR.level(rawR, nowMs)

        var path: List<FloatArray>? = null
        if (intent != null) {
            val target = if (intent == 'L') cx - w else cx + w
            val phi = when {
                ang != null -> ang
                latVel != null && ego != null && ego > 2.0 -> -asin((latVel / ego).coerceIn(-0.3, 0.3))
                else -> 0.0
            }.coerceIn(-0.3, 0.3)
            path = lanePath(target, phi, max(15.0, (ego ?: 0.0) * 4.0))
        }
        val pathOk = when (intent) { 'L' -> lv < 2; 'R' -> rv < 2; else -> true }
        return LcaState(
            L = lv, R = rv, intent = intent, path = path, pathOk = pathOk,
            threatId = threatId, threatTtc = if (threatId != null) threatTtc.toFloat() else null,
            zoneL = zoneL, zoneR = zoneR, latVel = latVel?.toFloat(), laneW = w.toFloat()
        )
    }

    /**
     * 五次多項式換道路徑：在車道座標（s 沿車道、d 往右）從 d=0、目前斜率、曲率 0，到 d=目標車道中心、
     * 斜率 0、曲率 0，長度 len；每 1 m 取一點轉回車身座標。phi：車道方向在車身座標的角度（+ = 往右偏）。
     */
    fun lanePath(targetX: Double, phi: Double, len: Double): List<FloatArray> {
        val sn = sin(phi)
        val cs = cos(phi)
        val dT = targetX * cs
        val m0 = -tan(phi)
        val delta = dT - m0 * len
        val n = ceil(len - 1e-6).toInt()
        val out = ArrayList<FloatArray>(n + 1)
        for (i in 0..n) {
            val s = min(i.toDouble(), len)
            val u = s / len
            val u3 = u * u * u
            val h = u3 * (10 - 15 * u + 6 * u * u)       // h(0)=0, h(1)=1, h'=h''=0 兩端
            val g = u3 * (-4 + 7 * u - 3 * u * u)        // g(1)=0, g'(1)=1, 其餘為 0
            val d = m0 * s + delta * h - m0 * len * g
            out.add(floatArrayOf((s * sn + d * cs).toFloat(), (s * cs - d * sn).toFloat()))
        }
        return out
    }

    /** 車道中心 / 寬度濾波。回傳車道資訊是否可用。 */
    private fun updateLane(lx0: Float?, rx0: Float?, now: Long, dt: Double): Boolean {
        val lx = lx0?.takeIf { it.isFinite() && it in -5f..0.2f }?.toDouble()
        val rx = rx0?.takeIf { it.isFinite() && it in -0.2f..5f }?.toDouble()
        if (laneInit && dt > 0) {
            c += cv * dt
            p00 += 2 * dt * p01 + dt * dt * p11 + LANE_Q * dt * dt * dt * dt / 4
            p01 += dt * p11 + LANE_Q * dt * dt * dt / 2
            p11 += LANE_Q * dt * dt
        }
        if (lx == null && rx == null) {
            if (now - laneAt > LANE_HOLD_MS) laneInit = false
            return laneInit
        }
        laneAt = now
        val same = lx0 == lastObsL && rx0 == lastObsR
        lastObsL = lx0
        lastObsR = rx0
        if (lx != null && rx != null && rx - lx in 2.5..4.8) {
            laneW = if (laneInit) laneW + (rx - lx - laneW) * 0.2 else rx - lx
        }
        val obs = when {
            lx != null && rx != null && rx - lx in 2.5..4.8 -> (lx + rx) / 2
            lx != null -> lx + laneW / 2
            else -> rx!! - laneW / 2
        }
        if (!laneInit) {
            c = obs; cv = 0.0; p00 = LANE_R; p01 = 0.0; p11 = 0.5 * 0.5
            laneInit = true
            return true
        }
        if (same) return true   // 同一筆車道線重送：只預測，不當新量測（否則會把速度拉向 0）
        var r = obs - c
        if (abs(r) > 0.45 * laneW) {
            val k = round(r / laneW)
            if (k != 0.0 && abs(r - k * laneW) < 0.7) {
                c += k * laneW   // 跨過車道線：最近的線換了一條，狀態整條平移
                r = obs - c
            } else {
                c = obs; cv = 0.0; p00 = LANE_R; p01 = 0.0; p11 = 0.5 * 0.5
                return true
            }
        }
        val s = p00 + LANE_R
        val k0 = p00 / s
        val k1 = p01 / s
        c += k0 * r
        cv += k1 * r
        p11 -= k1 * p01
        p01 *= 1 - k0
        p00 *= 1 - k0
        return true
    }

    fun toJson(s: LcaState): JSONObject {
        val o = JSONObject()
            .put("L", s.L).put("R", s.R)
            .put("intent", s.intent?.toString() ?: JSONObject.NULL)
            .put("pathOk", s.pathOk)
        val p = s.path
        if (p == null) o.put("path", JSONObject.NULL) else {
            val a = JSONArray()
            for (pt in p) a.put(JSONArray().put(r2(pt[0])).put(r2(pt[1])))
            o.put("path", a)
        }
        val id = s.threatId
        val ttc = s.threatTtc
        o.put("threat", if (id != null && ttc != null) JSONObject().put("id", id).put("ttc", r2(ttc)) else JSONObject.NULL)
        o.put("zones", JSONObject().put("L", arr(s.zoneL)).put("R", arr(s.zoneR)))
        return o
    }

    private fun arr(f: FloatArray): JSONArray {
        val a = JSONArray()
        for (v in f) a.put(r2(v))
        return a
    }

    private fun r2(f: Float): Double = Math.round(f * 100.0) / 100.0
}
