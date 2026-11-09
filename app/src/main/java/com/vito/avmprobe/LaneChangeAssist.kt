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
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
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
    val laneW: Float = LaneChangeAssist.LANE_W,
    /** 車道線資訊目前可用（否則盲點區是以自車為中心的預設 3.5 m 車道）。 */
    val laneOk: Boolean = false,
    /** 倒車中：不警示（盲點／來車預警只在前進時有意義）。 */
    val reversing: Boolean = false,
    /** 打燈是「路口轉彎」而非「變道」：不畫變道路徑，但警告照常。 */
    val turning: Boolean = false
)

/**
 * 盲點偵測 + 後方來車預警 + 換道路徑預測（純 Kotlin）。
 *
 * 盲點區：相鄰車道（有車道線就用，否則以自車為中心的 3.5 m 車道），縱向從前車門旁 +1 m 到後保桿後 10 m。
 * 車道線只用來「微調」：內緣一定落在自車側面外 0.55–0.85 m（車道線錯一條也不會把真正的相鄰車道漏掉）。
 * 二輪另有一條「鑽車縫」帶：自車側面外 0.2 m 到車道線之間（並排與後保桿後 3 m，或從後方接近）。
 * 後方來車：在後保桿後方、接近速度 > 1 m/s 且 TTC < 3.5 s。
 * 等級 2：等級 1 的條件 + 往該側的意圖（方向燈、往該側車道線偏移 > 0.25 m/s 持續 0.5 s、方向盤往該側），
 * 或 TTC < 2 s 且接近速度 ≥ 7 km/h（更慢的只亮琥珀）。每側等級連續 2 次更新才亮、清除 0.8 s 後才熄。
 *
 * 不警示（ISO 17387 / 一般 BSM）：自車 > 11 km/h 時的靜止物、自車以 > 10 km/h 超越的車。
 * 自車慢速（≤ 11 km/h，例如塞車）時旁邊靜止的車、以及正被超越但還在車旁的車，平常不亮琥珀燈，
 * 但有往該側的意圖時仍亮紅燈。抑制都用「保守」的速度（扣掉軌跡速度不確定度），不確定就不抑制。
 *
 * 感測現實（每顆鏡頭 2–4 Hz、遠處 1–2 m 誤差）的對策：TTC 用的接近速度扣掉軌跡速度不確定度超出
 * 穩定值的部分（新軌跡不會因為兩點估錯速度就亮紅），盲點佔用要 3 次命中（兩個誤報湊成的軌跡不算）。
 *
 * egoSpeedMps：車速，可帶正負號（負 = 倒車）；reverse = 排檔在 R（知道時傳）。倒車時全部不警示。
 * 方向盤角 steerDeg：+ = 向右。laneAngleDeg（選用）：車道線在車身座標的角度，+ = 越往前越偏右。
 * 建議每次 TrackManager.update 之後呼叫一次。
 */
class LaneChangeAssist {

    companion object {
        const val EGO_HW = 0.925f
        const val EGO_HL = 2.42f
        const val LANE_W = 3.5f
        const val ZONE_FRONT = 1.0f        // 盲點區前緣（前車門旁）
        const val ZONE_BACK = 10f          // 後保桿後 10 m
        const val SQUEEZE_BACK = 3f        // 鑽車縫帶：後保桿後 3 m（再後面要靠接近速度）
        const val CLOSE_V = 1.0f           // 接近速度門檻 m/s
        const val CLOSE_V2 = 2.0           // 只靠 TTC 升紅燈的接近速度門檻（7 km/h）
        const val TTC_WARN = 3.5f
        const val TTC_DANGER = 2.0f
        const val DRIFT_V = 0.25f          // 往車道線偏移速度 m/s
        const val DRIFT_MS = 500L
        const val STEER_DEG = 8f           // 方向盤往該側超過這個角度算有意圖
        const val TURN_SPEED = 7.0         // m/s（25 km/h）以下打燈多半是路口轉彎，不是變道
        const val TURN_STEER_DEG = 90f     // 方向盤打超過這個角度不可能是變道
        const val OVERTAKE_V = 2.8f        // 自車以 > 10 km/h 超越的車不警示
        const val STILL_V = 1.0f           // 對地速度低於此 = 靜止物
        const val MOVING_V = 3.0           // 自車高於此（11 km/h）才完全不理靜止物
        const val CROSS_V = 3.0f           // 橫向相對速度確定超過此 = 橫越車流，不列入
        const val SV_FREE = 0.6            // 穩定軌跡的速度標準差，不扣
        const val MIN_HITS = 3             // 盲點佔用：只有兩次命中的軌跡常是誤報湊成的（來車 TTC 另有速度不確定度把關）
        const val ON_COUNT = 2
        const val OFF_MS = 800L
        const val TURN_HOLD_MS = 1200L     // 方向燈閃爍的暗相也算打著
        const val LANE_HOLD_MS = 1500L
        const val CROSS_HOLD_MS = 6000L    // 跨線後幾秒內（燈還亮著）路徑目標是剛進入的車道
        private const val LANE_Q = 0.8 * 0.8     // 橫向加速度變異（車道濾波）
        private const val LANE_R = 0.05 * 0.05   // 車道線位置量測變異
        private const val LANE_W1 = 0.15 * 0.15  // 只有單線時多加的變異（車道寬度不確定）
        private const val LANE_OUT_N = 4         // 連續這麼多筆跟濾波不合、且超過 LANE_OUT_MS 才重新初始化
        private const val LANE_OUT_MS = 600L
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

    /** 每條軌跡的遲滯狀態：在哪些側帶、是否被抑制（靜止物 / 正被超越）。 */
    private class Mem(var seen: Long = 0L) {
        val band = IntArray(2)        // 每側：0 不在、1 相鄰車道、2 鑽車縫帶
        var overtaken = false
        var still = false
    }

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
    private var laneOut = 0
    private var laneOutAt = 0L
    private var lastAccepted = false
    private var widthKnown = false
    private var crossAt = Long.MIN_VALUE / 2
    private var crossDir = 0              // −1 往左跨線、+1 往右
    private var lastMs = Long.MIN_VALUE

    private var driftLAt = -1L
    private var driftRAt = -1L
    private var turnLAt = Long.MIN_VALUE / 2
    private var turnRAt = Long.MIN_VALUE / 2
    private val sideL = Side()
    private val sideR = Side()
    private val mem = HashMap<Int, Mem>()
    private val squeezeAt = LongArray(2) { Long.MIN_VALUE / 2 }

    fun reset() {
        laneInit = false
        laneAt = Long.MIN_VALUE / 2
        lastObsL = null; lastObsR = null
        laneOut = 0
        lastAccepted = false
        widthKnown = false
        crossAt = Long.MIN_VALUE / 2; crossDir = 0
        lastMs = Long.MIN_VALUE
        driftLAt = -1L; driftRAt = -1L
        turnLAt = Long.MIN_VALUE / 2; turnRAt = Long.MIN_VALUE / 2
        sideL.reset(); sideR.reset()
        mem.clear()
        squeezeAt.fill(Long.MIN_VALUE / 2)
        laneW = LANE_W.toDouble()
    }

    /**
     * tracks：TrackManager 輸出（只用確認過的汽車與二輪）。laneLeftX/laneRightX：最近的左/右車道線在
     * z = 0 處的橫向位置（m，左負右正），沒有就 null。turn：方向燈 'L'/'R'/null（閃爍沒關係）。
     */
    fun update(
        tracks: List<Track>, nowMs: Long, laneLeftX: Float?, laneRightX: Float?, turn: Char?,
        steerDeg: Float?, egoSpeedMps: Float?, laneAngleDeg: Float? = null, reverse: Boolean = false
    ): LcaState {
        val dt = if (lastMs == Long.MIN_VALUE) 0.0 else (nowMs - lastMs).coerceAtLeast(0L) / 1000.0
        lastMs = if (lastMs == Long.MIN_VALUE) nowMs else max(lastMs, nowMs)
        val egoSigned = egoSpeedMps?.takeIf { it.isFinite() }?.toDouble()
        val reversing = reverse || (egoSigned != null && egoSigned < -0.3)
        val ego = egoSigned?.let { abs(it) }
        val laneOk = updateLane(laneLeftX, laneRightX, nowMs, dt)

        // 盲點區幾何
        val cx = if (laneOk) c else 0.0
        val w = if (laneOk) laneW else LANE_W.toDouble()
        val lLine = cx - w / 2
        val rLine = cx + w / 2
        val zMin = (-EGO_HL - ZONE_BACK).toDouble()
        val zMax = ZONE_FRONT.toDouble()
        val zoneL = floatArrayOf((lLine - w).toFloat(), zMin.toFloat(), lLine.toFloat(), zMax.toFloat())
        val zoneR = floatArrayOf(rLine.toFloat(), zMin.toFloat(), (rLine + w).toFloat(), zMax.toFloat())

        if (reversing) {
            // 倒車：盲點／來車預警關閉（不然路邊停的車全都像「後方來車」），狀態清掉，換回前進時重新累積
            sideL.reset(); sideR.reset()
            driftLAt = -1L; driftRAt = -1L
            turnLAt = Long.MIN_VALUE / 2; turnRAt = Long.MIN_VALUE / 2
            mem.clear()
            return LcaState(0, 0, null, null, true, null, null, zoneL, zoneR, null, w.toFloat(), laneOk, true)
        }

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
        val toward = booleanArrayOf(tL || driftL || steerL, tR || driftR || steerR)

        // 判斷用的側帶（往外為正）：車道線決定的內緣夾在自車側面外 0.55–0.85 m 之間
        val own = doubleArrayOf(max(0.0, -lLine), max(0.0, rLine))
        val innerCar = DoubleArray(2) { (own[it] - 0.3).coerceIn(EGO_HW + 0.55, EGO_HW + 0.85) }
        val innerTwo = EGO_HW + 0.2
        val outer = DoubleArray(2) { max(own[it], EGO_HW.toDouble()) + w + 0.6 }

        val raw = IntArray(2)
        val squeeze = BooleanArray(2)
        var threatId: Int? = null
        var threatTtc = Double.MAX_VALUE
        for (tr in tracks) {
            val g = TrackManager.group(tr.t)
            if (!tr.confirmed || g !in LEVEL_TYPES) continue
            val m = mem.getOrPut(tr.id) { Mem() }
            m.seen = nowMs
            if (abs(tr.vx) - tr.svx > CROSS_V) continue   // 橫越車流 / 不可信的軌跡，跟換道無關
            val vz = tr.vz.toDouble()
            val exz = max(0.0, tr.svz - SV_FREE)
            val exx = max(0.0, tr.svx - SV_FREE)

            // 抑制用「最不利」的估計：不確定就不抑制
            val vzHi = vz + exz
            m.overtaken = if (m.overtaken) vzHi < -OVERTAKE_V + 0.5 else vzHi < -OVERTAKE_V - 0.3
            if (ego != null) {
                val sogLo = hypot(max(0.0, abs(tr.vx.toDouble()) - exx), max(0.0, abs(vz + ego) - exz))
                val sogHi = hypot(abs(tr.vx.toDouble()) + exx, abs(vz + ego) + exz)
                m.still = if (m.still) sogLo < STILL_V + 0.6 else sogHi < STILL_V
            } else m.still = false
            val moving = ego != null && ego > MOVING_V
            if (m.still && moving) continue               // 自車在走、對方靜止（路邊停車）：不理
            // 對向車：對地速度往後（跟自車反方向）→ 不是後方來車（實車：對向車也會提醒「後方來車」）
            if (ego != null && moving && vz + ego + exz < -1.5) continue
            // 車速是 GPS 的（延遲、不準）：對地速度很小的也當成停著的，不只靠上面那個嚴格的靜止判斷
            if (ego != null && moving && abs(vz + ego) < 2.5 + exz && abs(tr.vx) < 1.0) continue
            val latent = m.still || m.overtaken           // 只在有意圖時（紅燈）才算

            val hl = (if (tr.l > 0f) tr.l else TrackManager.size(tr.t)[1]) / 2.0
            val front = tr.z + hl
            val rear = tr.z - hl
            val range = -EGO_HL - front
            // 接近速度取保守值：剛出現的遠處軌跡速度誤差 2–4 m/s，扣掉超出穩定軌跡（~0.6 m/s）的不確定度
            val vc = vz - exz
            var ttc = Double.MAX_VALUE
            if (!latent && range > 0 && vc > CLOSE_V) {
                val t = range / vc
                if (t < TTC_WARN) ttc = t
            }
            // 只靠 TTC 升紅燈：接近速度（保守值）≥ 7 km/h。慢慢靠近的車維持琥珀——靜止車的速度雜訊（±1 m/s）
            // 加上很小的距離會算出假的小 TTC。只有兩次命中的快速來車（估計 ≥ 14 km/h、不確定度還很大）也算
            val sure = vc >= CLOSE_V2 || (vz >= 2 * CLOSE_V2 && vc > CLOSE_V)

            for (s in 0..1) {
                val sx = if (s == 0) -tr.x.toDouble() else tr.x.toDouble()
                // 二輪貼著車身時，內緣判斷給「位置不確定度」的寬限（後鏡頭遠處橫向誤差 0.3–0.5 m，不能因此漏掉）
                val sxIn = if (g == TrackManager.G_TWO) sx + min(0.3, 0.5 * tr.sd) else sx
                val innerAdj = if (g == TrackManager.G_TWO) max(innerTwo, min(own[s] - 0.3, innerCar[s])) else innerCar[s]
                val b0 = when {
                    sx > outer[s] -> 0
                    sxIn >= innerAdj -> 1
                    g == TrackManager.G_TWO && sxIn >= innerTwo -> 2
                    else -> 0
                }
                // 遲滯 0.3 m：在帶子邊緣抖動時維持原來的帶子
                val cur = m.band[s]
                m.band[s] = when {
                    cur == 0 || b0 == cur -> b0
                    cur == 1 && sxIn >= innerAdj - 0.3 && sx <= outer[s] + 0.3 -> 1
                    cur == 2 && sxIn >= innerTwo - 0.3 && sx < innerAdj + 0.3 -> 2
                    else -> b0
                }
                if (m.band[s] == 0) continue
                val back = if (m.band[s] == 1) zMin else (-EGO_HL - SQUEEZE_BACK).toDouble()
                val inZone = front >= back && rear <= zMax && tr.hits >= MIN_HITS
                var lvl = 0
                if (!latent && (inZone || ttc < TTC_WARN)) lvl = 1
                // 正被超越／慢速時的靜止車：只看並排的那一段
                val alongside = inZone && rear <= zMax && front >= -EGO_HL - 2.0
                if (toward[s] && (lvl == 1 || (latent && alongside))) lvl = 2
                if (lvl == 1 && ttc < TTC_DANGER && sure) lvl = 2
                if (lvl == 0) continue
                raw[s] = max(raw[s], lvl)
                if (m.band[s] == 2) squeeze[s] = true
                if (ttc < threatTtc) { threatTtc = ttc; threatId = tr.id }
            }
        }
        mem.entries.removeAll { nowMs - it.value.seen > 3000 }

        val lv = sideL.level(raw[0], nowMs)
        val rv = sideR.level(raw[1], nowMs)
        // 鑽車縫的二輪觸發：畫出來的盲點區往內延伸到自車側面（不然亮的是隔壁車道，機車卻在自己車道裡）
        for (s in 0..1) if (squeeze[s]) squeezeAt[s] = nowMs
        if (lv > 0 && nowMs - squeezeAt[0] <= OFF_MS) zoneL[2] = max(zoneL[2], -innerTwo.toFloat())
        if (rv > 0 && nowMs - squeezeAt[1] <= OFF_MS) zoneR[0] = min(zoneR[0], innerTwo.toFloat())

        // 打方向燈不一定是要變道，也可能是路口要轉彎（或路邊停車）。兩者差很多：
        // 轉彎時畫一條切進隔壁車道的變道路徑是錯的，「目標車道有車」的說法也不成立。
        // 只靠速度與方向盤分（地圖不可靠：車機 GPS 會飄、停車場根本收不到）：
        // 慢速多半是路口轉彎；方向盤打超過 90° 絕不可能是變道。
        // 注意：這裡只收掉「畫錯的路徑」，L/R 等級與 threat 一律照送——
        // 轉彎時盲點反而更危險（右轉遇到右側鑽上來的機車），警告不能因此消失。
        val turning = (ego != null && ego < TURN_SPEED) || (st != null && abs(st) > TURN_STEER_DEG)

        var path: List<FloatArray>? = null
        var conflict = false
        if (intent != null && !turning) {
            val dir = if (intent == 'L') -1 else 1
            // 剛跨過線（燈還亮著）：目標是剛進入的這條車道，不是再下一條
            val target = if (laneOk && crossDir == dir && nowMs - crossAt <= CROSS_HOLD_MS) cx else cx + dir * w
            val phi = when {
                ang != null -> ang
                latVel != null && ego != null && ego > 2.0 -> -asin((latVel / ego).coerceIn(-0.3, 0.3))
                else -> 0.0
            }.coerceIn(-0.3, 0.3)
            val len = max(15.0, (ego ?: 0.0) * 4.0)
            path = lanePath(target, phi, len)
            conflict = pathConflict(tracks, if (dir < 0) 0 else 1, target, len, ego, innerCar, innerTwo)
        }
        // 換道路徑可行：該側沒有危險，而且沿路徑走的 3 s 內不會跟目標車道上的車（含前車門以前、盲點區外的）擦撞
        val pathOk = !conflict && when (intent) { 'L' -> lv < 2; 'R' -> rv < 2; else -> true }
        return LcaState(
            L = lv, R = rv, intent = intent, path = path, pathOk = pathOk,
            threatId = threatId, threatTtc = if (threatId != null) threatTtc.toFloat() else null,
            zoneL = zoneL, zoneR = zoneR, latVel = latVel?.toFloat(), laneW = w.toFloat(), laneOk = laneOk,
            turning = intent != null && turning
        )
    }

    /**
     * 沿換道路徑往前推 3 s（自車照目前車速、橫向照五次多項式；其他車等速）：跟換道那一側的車重疊 → 衝突。
     * 縱向留 1.5 m + 0.3 s × 接近速度的空間（切到前車屁股後面太近、或後車趕上來都算）。
     * 只看已經在那一側（自車側面以外）的車：自己車道正前方的車是跟車距離的問題，不算換道衝突。
     */
    private fun pathConflict(
        tracks: List<Track>, side: Int, target: Double, len: Double, ego: Double?,
        innerCar: DoubleArray, innerTwo: Double
    ): Boolean {
        val v = max(ego ?: 0.0, 3.0)                      // 不知道車速就當 11 km/h 估換道時間
        val tTot = len / v
        val sg = if (side == 0) -1.0 else 1.0
        for (tr in tracks) {
            val g = TrackManager.group(tr.t)
            if (!tr.confirmed || g !in LEVEL_TYPES || tr.hits < MIN_HITS) continue
            if (abs(tr.vx) - tr.svx > CROSS_V) continue
            val sx = sg * tr.x
            if (sx < (if (g == TrackManager.G_TWO) innerTwo else innerCar[side])) continue
            val hw = (if (tr.w > 0f) tr.w else TrackManager.size(tr.t)[0]) / 2.0
            val hl = (if (tr.l > 0f) tr.l else TrackManager.size(tr.t)[1]) / 2.0
            val vz = tr.vz.toDouble()
            val gapZ = EGO_HL + hl + 1.5 + 0.3 * abs(vz)
            val gapX = EGO_HW + hw + 0.3
            var t = 0.0
            while (t <= 3.0 + 1e-9) {
                val u = min(1.0, t / tTot)
                val d = target * u * u * u * (10 - 15 * u + 6 * u * u)
                if (abs(tr.x + tr.vx * t - d) < gapX && abs(tr.z + vz * t) < gapZ) return true
                t += 0.25
            }
        }
        return false
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

    /**
     * 車道中心 / 寬度濾波。回傳車道資訊是否可用。
     * 防呆（RoadTracker 偶爾給錯線：最近的線被旁邊的車擋住而報成再外一條、地上的補丁/輪胎痕當成線）：
     * - 自車一定在自己的車道裡：推得的車道中心離自車超過半個車道（+0.45 m）就不採用；
     * - 跟濾波差太多的量測不採用；只有自車貼著線時，差一整條車道才當成「跨線」平移；
     * - 不合的量測連續 4 筆且超過 0.6 s 才重新初始化；1.5 s 沒有可用量測 → 改用預設車道。
     */
    private fun updateLane(lx0: Float?, rx0: Float?, now: Long, dt: Double): Boolean {
        val lx = lx0?.takeIf { it.isFinite() && it in -6f..0.4f }?.toDouble()
        val rx = rx0?.takeIf { it.isFinite() && it in -0.4f..6f }?.toDouble()
        if (laneInit && dt > 0) {
            c += cv * dt
            p00 += 2 * dt * p01 + dt * dt * p11 + LANE_Q * dt * dt * dt * dt / 4
            p01 += dt * p11 + LANE_Q * dt * dt * dt / 2
            p11 += LANE_Q * dt * dt
        }
        if (lx == null && rx == null) return holdLane(now)
        val same = lx0 == lastObsL && rx0 == lastObsR
        lastObsL = lx0
        lastObsR = rx0
        if (same && laneInit) {
            // 同一筆車道線重送：只預測，不當新量測（否則會把速度拉向 0）；上一筆有採用就算車道還在
            if (lastAccepted) laneAt = now
            return holdLane(now)
        }

        // 候選車道中心：[中心, 寬度（兩條線都有才有，否則 NaN）, 左線, 右線]
        val w = laneW
        val cand = ArrayList<DoubleArray>(3)
        if (lx != null && rx != null && rx - lx in 2.5..4.8) cand.add(doubleArrayOf((lx + rx) / 2, rx - lx, lx, rx))
        if (lx != null) cand.add(doubleArrayOf(lx + w / 2, Double.NaN, lx, Double.NaN))
        if (rx != null) cand.add(doubleArrayOf(rx - w / 2, Double.NaN, Double.NaN, rx))
        cand.removeAll { abs(it[0]) > (if (it[1].isNaN()) w else it[1]) / 2 + 0.45 }   // 自車要在自己的車道裡

        if (!laneInit) {
            lastAccepted = false
            val b = cand.firstOrNull() ?: return false
            initLane(b, now)
            return true
        }
        // 跟預測最合的候選（兩條線都對得上優先）；自車貼線時允許差一整條車道（跨線）
        val gate = max(0.45, 3 * sqrt(p00 + LANE_R))
        val nearLine = abs(c) > w / 2 - 0.8
        val shift = -sign(c) * w
        var best: DoubleArray? = null
        var bestR = 0.0
        var bestShift = 0.0
        var bestScore = Double.MAX_VALUE
        for (k in cand) {
            for (sh in if (nearLine) doubleArrayOf(0.0, shift) else doubleArrayOf(0.0)) {
                val cc = c + sh
                val tol = if (sh == 0.0) gate else 0.7
                // 兩條線各自要對得上預測的線位置（地上補丁貼著車身被當成線 → 那一條對不上 → 只用另一條）；
                // 寬度還不知道時預測的線位置本身就可能差 0.5 m 以上，放寬
                val lineTol = tol + 0.15 + (if (widthKnown) 0.0 else 0.6)
                if (!k[1].isNaN() && (abs(k[2] - (cc - w / 2)) > lineTol || abs(k[3] - (cc + w / 2)) > lineTol)) continue
                val r = k[0] - cc
                if (abs(r) > tol) continue
                // 寬度還不知道（從單線初始化）：兩條線優先，才學得到寬度（不然換邊時中心會跳）；
                // 寬度已知：看「最對不上的那一條線」，一條假線就不會把整對拖進來
                val pair = !k[1].isNaN()
                val lineErr = if (pair) max(abs(k[2] - (cc - w / 2)), abs(k[3] - (cc + w / 2))) else abs(r)
                val score = (if (widthKnown) lineErr + (if (pair) 0.0 else 0.05) else abs(r) + (if (pair) 0.0 else 0.5)) +
                    (if (sh != 0.0) 0.2 else 0.0)
                if (score < bestScore) { bestScore = score; best = k; bestR = r; bestShift = sh }
            }
        }
        val b = best
        if (b == null) {
            lastAccepted = false
            if (laneOut == 0) laneOutAt = now
            laneOut++
            val first = cand.firstOrNull()
            if (first != null && laneOut >= LANE_OUT_N && now - laneOutAt >= LANE_OUT_MS) {
                initLane(first, now)   // 持續不合：車道真的變了（變寬、併線…），重來
                return true
            }
            return holdLane(now)
        }
        lastAccepted = true
        laneOut = 0
        laneAt = now
        if (bestShift != 0.0) {
            c += bestShift   // 跨過車道線：最近的線換了一條，狀態整條平移
            crossDir = if (bestShift < 0) -1 else 1
            crossAt = now
        }
        if (!b[1].isNaN()) {
            laneW = if (widthKnown) laneW + (b[1] - laneW) * 0.2 else b[1]
            widthKnown = true
        }
        // 只有一條線：中心 = 線 ± 半個車道寬，寬度本身的誤差（~0.15 m）也要算進去，左右線輪流掉時才不會跳出假的偏移速度
        val s = p00 + LANE_R + (if (b[1].isNaN()) LANE_W1 else 0.0)
        val k0 = p00 / s
        val k1 = p01 / s
        c += k0 * bestR
        cv += k1 * bestR
        p11 -= k1 * p01
        p01 *= 1 - k0
        p00 *= 1 - k0
        return true
    }

    private fun initLane(b: DoubleArray, now: Long) {
        c = b[0]; cv = 0.0; p00 = LANE_R; p01 = 0.0; p11 = 0.5 * 0.5
        widthKnown = !b[1].isNaN()
        if (widthKnown) laneW = b[1]
        laneInit = true
        laneAt = now
        laneOut = 0
        lastAccepted = true
    }

    private fun holdLane(now: Long): Boolean {
        if (now - laneAt > LANE_HOLD_MS) laneInit = false
        return laneInit
    }

    fun toJson(s: LcaState): JSONObject {
        val o = JSONObject()
            .put("L", s.L).put("R", s.R)
            .put("intent", s.intent?.toString() ?: JSONObject.NULL)
            .put("pathOk", s.pathOk)
            .put("turning", s.turning)   // 路口轉彎：面板的用詞要跟「變道」分開
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
