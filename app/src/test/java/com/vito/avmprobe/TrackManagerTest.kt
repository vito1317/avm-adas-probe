package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// =====================================================================================
// 情境模擬器（LaneChangeAssistTest 共用）：地面真值軌跡 → 四顆魚眼輪流偵測
// （側鏡頭頻率是前/後的兩倍、漏偵、誤報、距離越遠誤差越大且主要在視線方向、推論延遲、GPS 1 Hz）。
// 進階：物件互相遮擋、鏡頭校正偏差（接縫平移、俯角誤差）、GPS 斷訊/舊值、車道線輸入錯誤、倒車、變速。
// 世界座標：X 右、Z 前（直路，車道線 X = −5.25/−1.75/1.75/5.25）；自車沿 Z 前進（egoZ(t)），橫向 egoX(t)。
// =====================================================================================

/** pos(t) = 世界座標 (X, Z)。 */
internal class LcaActor(val t: String, val pos: (Double) -> DoubleArray) {
    val w = TrackManager.size(t)[0].toDouble()
    val l = TrackManager.size(t)[1].toDouble()
}

internal class LcaScenario(
    val name: String, val dur: Double, val egoV: Double, val actors: List<LcaActor>,
    val egoX: (Double) -> Double = { 0.0 },
    val turn: (Double) -> Char? = { null },
    val gps: Boolean = true,
    val lanes: DoubleArray = doubleArrayOf(-5.25, -1.75, 1.75, 5.25),
    /** 自車縱向位置（世界 Z）；null = 等速 egoV。 */
    val egoZ: ((Double) -> Double)? = null,
    /** 這一刻有沒有 GPS（斷訊測試）。 */
    val gpsUp: (Double) -> Boolean = { true },
    /** 斷訊時整合端仍送最後一筆舊值（而不是 null）。 */
    val gpsStale: Boolean = false,
    /** 整合端知道在倒車（排檔）→ 車速傳負值。 */
    val reverseKnown: Boolean = false,
    /** 車道線輸入故障：(t, [左, 右] 真值) → 送進去的值。 */
    val laneFault: ((Double, Array<Double?>) -> Array<Double?>)? = null,
    val cfg: LcaSimCfg? = null
)

internal class LcaSimCfg(
    val fpRate: Double = 0.06,       // 每次推論出現一個誤報的機率
    val crossType: Double = 0.01,    // 跨類別誤認（車→機車、行人→機車）
    val inType: Double = 0.10,       // 同類別誤認（轎車→箱型車/卡車）
    val latencyMs: Long = 60,        // 拍攝到結果出來
    val nearFace: Boolean = true,
    /** 物件互相遮擋（車隊、機車在車旁）：被擋住的偵測率大降。 */
    val occlusion: Boolean = false,
    /** 每顆鏡頭的校正偏差 [dx, dz, 俯角誤差°]：接縫兩邊同一台車差一段距離；俯角誤差讓距離誤差隨距離平方變大。 */
    val camBias: Map<String, DoubleArray> = emptyMap(),
    /** 鏡頭輪替順序（每格約 70 ms）。 */
    val slots: List<String> = LcaSim.SLOTS,
    /** 整合端每次都告訴追蹤器是哪顆鏡頭（沒偵測到東西也傳）。 */
    val camHint: Boolean = true
)

/** req[side] = [必須一級, 必須二級, 允許一級, 允許二級]（獨立的物理需求，不用 LaneChangeAssist 的邏輯）。 */
internal class LcaStep(
    val t: Double, val est: List<Track>, val gt: List<Track>, val lca: LcaState, val oracle: LcaState,
    val req: Array<BooleanArray>
)

internal class LcaSim(private val sc: LcaScenario, seed: Long, cfg0: LcaSimCfg = LcaSimCfg()) {
    class Cam(val name: String, val x: Double, val z: Double, val fx: Double, val fz: Double, val range: Double, val h: Double)

    companion object {
        val CAMS = mapOf(
            "front" to Cam("front", 0.0, 2.35, 0.0, 1.0, 15.0, 0.55),
            "rear" to Cam("rear", 0.0, -2.40, 0.0, -1.0, 15.0, 0.95),
            "left" to Cam("left", -1.0, 0.85, -1.0, 0.0, 12.0, 1.0),
            "right" to Cam("right", 1.0, 0.85, 1.0, 0.0, 12.0, 1.0)
        )
        /** 跟 QuadActivity.slots() 一樣：左右各兩次、前後各一次，再一輪紅綠燈。 */
        val SLOTS = listOf("left", "right", "front", "left", "right", "rear", "tl")
        const val HW = 0.925
        const val HL = 2.42
        val COS_FOV: Double = cos(Math.toRadians(95.0))

        fun segHitsRect(x0: Double, z0: Double, x1: Double, z1: Double, a: Double, b: Double): Boolean {
            if (a <= 0 || b <= 0) return false
            var t0 = 0.0
            var t1 = 1.0
            val dx = x1 - x0
            val dz = z1 - z0
            val p = doubleArrayOf(-dx, dx, -dz, dz)
            val q = doubleArrayOf(x0 + a, a - x0, z0 + b, b - z0)
            for (i in 0..3) {
                if (p[i] == 0.0) {
                    if (q[i] < 0) return false
                } else {
                    val r = q[i] / p[i]
                    if (p[i] < 0) { if (r > t1) return false; if (r > t0) t0 = r } else { if (r < t0) return false; if (r < t1) t1 = r }
                }
            }
            return t0 < t1 - 1e-9
        }
    }

    private val cfg = sc.cfg ?: cfg0
    private val rnd = Random(seed)
    val tm = TrackManager(nearFaceCorrection = cfg.nearFace)
    val lca = LaneChangeAssist()
    val oracle = LaneChangeAssist()
    private var gpsVal: Float? = null
    private var gpsAt = -10.0
    private val emaLane = arrayOfNulls<Double>(2)

    private fun g() = rnd.nextGaussian()

    fun egoZ(t: Double): Double = sc.egoZ?.invoke(t) ?: (sc.egoV * t)

    /** 自車縱向速度（m/s，倒車為負）。 */
    fun egoSpeed(t: Double): Double = (egoZ(t + 0.01) - egoZ(t - 0.01)) / 0.02

    fun egoHeading(t: Double): Double {
        val v = max(abs(egoSpeed(t)), 0.5)
        return atan2((sc.egoX(t + 0.01) - sc.egoX(t - 0.01)) / 0.02, v)
    }

    fun toVeh(t: Double, xw: Double, zw: Double): DoubleArray {
        val b = egoHeading(t)
        val dx = xw - sc.egoX(t)
        val dz = zw - egoZ(t)
        return doubleArrayOf(dx * cos(b) - dz * sin(b), dx * sin(b) + dz * cos(b))
    }

    fun actorVeh(a: LcaActor, t: Double): DoubleArray { val p = a.pos(t); return toVeh(t, p[0], p[1]) }

    private fun actorVel(a: LcaActor, t: Double): DoubleArray {
        val p0 = actorVeh(a, t - 0.005)
        val p1 = actorVeh(a, t + 0.005)
        return doubleArrayOf((p1[0] - p0[0]) / 0.01, (p1[1] - p0[1]) / 0.01)
    }

    private fun actorSog(a: LcaActor, t: Double): Double {
        val p0 = a.pos(t - 0.005)
        val p1 = a.pos(t + 0.005)
        return hypot(p1[0] - p0[0], p1[1] - p0[1]) / 0.01
    }

    /** 鏡頭看得到 → 回傳 [最近點 x, z, 距離]，否則 null（超出距離、超出 190° 視野、被自己車身擋住）。 */
    fun visible(c: Cam, x: Double, z: Double, hw: Double, hl: Double): DoubleArray? {
        val px = min(max(c.x, x - hw), x + hw)
        val pz = min(max(c.z, z - hl), z + hl)
        val dn = hypot(px - c.x, pz - c.z)
        if (dn < 0.05 || dn > c.range) return null
        if (((px - c.x) * c.fx + (pz - c.z) * c.fz) / dn < COS_FOV) return null
        if (segHitsRect(c.x, c.z, px, pz, HW - 0.1, HL - 0.1)) return null
        return doubleArrayOf(px, pz, dn)
    }

    /** 鏡頭到 (px, pz) 的視線被其他物件（外形內縮 0.2 m）擋住。 */
    private fun blocked(c: Cam, self: LcaActor, px: Double, pz: Double, t: Double): Boolean {
        val dn = hypot(px - c.x, pz - c.z)
        for (b in sc.actors) {
            if (b === self) continue
            val q = actorVeh(b, t)
            if (hypot(q[0] - c.x, q[1] - c.z) - max(b.w, b.l) / 2 > dn) continue
            if (segHitsRect(c.x - q[0], c.z - q[1], px - q[0], pz - q[1], b.w / 2 - 0.2, b.l / 2 - 0.2)) return true
        }
        return false
    }

    private fun confuse(t: String): String {
        val u = rnd.nextDouble()
        if (u < cfg.crossType) return when (TrackManager.group(t)) {
            TrackManager.G_VEH -> "moto"
            TrackManager.G_TWO, TrackManager.G_PED -> if (t == "ped") "moto" else "ped"
            else -> t
        }
        if (u < cfg.crossType + cfg.inType) return when (t) {
            "car" -> if (rnd.nextBoolean()) "van" else "truck"
            "moto" -> "bigbike"
            else -> t
        }
        return t
    }

    fun detect(camName: String, t: Double): List<Det> {
        val c = CAMS.getValue(camName)
        val at = (t * 1000).toLong()
        val out = ArrayList<Det>()
        val bias = cfg.camBias[camName]
        for (a in sc.actors) {
            val p = actorVeh(a, t)
            val v = visible(c, p[0], p[1], a.w / 2, a.l / 2) ?: continue
            val dn = v[2]
            var pd = when {
                dn < 1.0 -> 0.5                                     // 太近：框被畫面切掉
                dn < 7.0 -> 0.92
                else -> 0.92 - 0.37 * (dn - 7) / (c.range - 7)      // 最遠處 0.55
            }
            val cosA = ((v[0] - c.x) * c.fx + (v[1] - c.z) * c.fz) / dn
            if (cosA < cos(Math.toRadians(75.0))) pd *= 0.75        // 魚眼邊緣變形
            if (a.t == "ped") pd *= 0.9
            if (cfg.occlusion) {
                val nearHidden = blocked(c, a, v[0], v[1], t)
                val midHidden = blocked(c, a, p[0], p[1], t)
                if (nearHidden && midHidden) pd *= 0.15 else if (nearHidden || midHidden) pd *= 0.6
            }
            if (rnd.nextDouble() > pd) continue
            // 框中心方位介於物件中心與最近點之間；距離 = 最近點 + 外推 radius + 誤差（主要在視線方向）
            val cd = hypot(p[0] - c.x, p[1] - c.z)
            var ux = 0.7 * (p[0] - c.x) / cd + 0.3 * (v[0] - c.x) / dn
            var uz = 0.7 * (p[1] - c.z) / cd + 0.3 * (v[1] - c.z) / dn
            val un = hypot(ux, uz)
            ux /= un; uz /= un
            val sr = 0.12 + 0.1 * dn
            val st = 0.08 + 0.025 * dn
            val nr = g() * sr * (if (rnd.nextDouble() < 0.03) 3.0 else 1.0)
            val nt = g() * st
            var range = dn + TrackManager.defaultRadius(a.t) + nr
            if (bias != null && bias.size >= 3) range += dn * dn / c.h * Math.toRadians(bias[2])
            val dx = c.x + ux * range - uz * nt + (bias?.get(0) ?: 0.0)
            val dz = c.z + uz * range + ux * nt + (bias?.get(1) ?: 0.0)
            out.add(Det(confuse(a.t), dx.toFloat(), dz.toFloat(), (0.35 + 0.55 * rnd.nextDouble() * pd).toFloat(), camName, at))
        }
        if (rnd.nextDouble() < cfg.fpRate) {
            val base = atan2(c.fx, c.fz)
            val ang = base + (rnd.nextDouble() * 2 - 1) * Math.toRadians(80.0)
            val r = 2 + rnd.nextDouble() * 10
            val x = c.x + sin(ang) * r
            val z = c.z + cos(ang) * r
            if (abs(x) > HW + 0.3 || abs(z) > HL + 0.3) {
                val t0 = arrayOf("car", "moto", "ped")[rnd.nextInt(3)]
                out.add(Det(t0, x.toFloat(), z.toFloat(), (0.25 + 0.2 * rnd.nextDouble()).toFloat(), camName, at))
            }
        }
        return out
    }

    /** 真值軌跡：只列出至少一顆鏡頭理論上看得到的物件（理想系統也只能知道這些）。 */
    fun truth(t: Double): List<Track> {
        val out = ArrayList<Track>()
        for ((i, a) in sc.actors.withIndex()) {
            val p = actorVeh(a, t)
            if (CAMS.values.none { visible(it, p[0], p[1], a.w / 2, a.l / 2) != null }) continue
            val v = actorVel(a, t)
            out.add(Track(1000 + i, a.t, p[0].toFloat(), p[1].toFloat(), v[0].toFloat(), v[1].toFloat(), 10_000, 99, true,
                a.w.toFloat(), a.l.toFloat(), if (sc.gps) actorSog(a, t).toFloat() else null))
        }
        return out
    }

    /** 車道線在車身座標（z = 0 處）的 x，由左到右。 */
    private fun lanesVeh(t: Double): List<Double> {
        val b = egoHeading(t)
        return sc.lanes.map { (it - sc.egoX(t)) / cos(b) }.sorted()
    }

    /** 最近的左 / 右車道線在 z=0 的 x（真值）。 */
    fun laneTruth(t: Double): Array<Double?> {
        var l: Double? = null
        var r: Double? = null
        for (x in lanesVeh(t)) {
            if (x < 0 && (l == null || x > l)) l = x
            if (x >= 0 && (r == null || x < r)) r = x
        }
        return arrayOf(l, r)
    }

    /** 模擬 RoadTracker 的輸出：雜訊 4 cm、EMA 0.35、5% 掉線；故障情境在這之前改掉真值。 */
    private fun laneObs(t: Double): Array<Float?> {
        val tr0 = laneTruth(t)
        val tr = sc.laneFault?.invoke(t, tr0) ?: tr0
        val out = arrayOfNulls<Float>(2)
        for (i in 0..1) {
            val v = tr[i]
            if (v == null) { emaLane[i] = null; continue }
            val o = v + g() * 0.04
            val e = emaLane[i]
            emaLane[i] = if (e == null || abs(e - o) > 1.5) o else e + (o - e) * 0.35
            if (rnd.nextDouble() > 0.05) out[i] = emaLane[i]!!.toFloat()
        }
        return out
    }

    /** GPS：1 Hz、晚 0.5 s、±0.3 m/s；只有速率（不分前進倒退），知道排檔時整合端才傳負值。 */
    private fun gps(t: Double): Float? {
        if (!sc.gps) return null
        if (!sc.gpsUp(t)) return if (sc.gpsStale) gpsVal else null
        if (t - gpsAt >= 1.0) {
            gpsAt = t
            val v = egoSpeed(t - 0.5)
            var s = max(0.0, abs(v) + g() * 0.3)
            if (sc.reverseKnown && v < -0.1) s = -s
            gpsVal = s.toFloat()
        }
        return gpsVal
    }

    private fun egoLatV(t: Double) = (sc.egoX(t + 0.01) - sc.egoX(t - 0.01)) / 0.02

    /** [t−hold, t] 內一直往該側（0 左 1 右）橫移超過 thr。 */
    private fun driftTruth(t: Double, side: Int, thr: Double, hold: Double): Boolean {
        var u = t - hold
        while (u <= t + 1e-9) {
            val v = egoLatV(u)
            if (if (side == 0) v > -thr else v < thr) return false
            u += 0.05
        }
        return true
    }

    private fun turnTruth(t: Double, side: Int, hold: Double): Boolean {
        val ch = if (side == 0) 'L' else 'R'
        var u = t - hold
        while (u <= t + 1e-9) { if (sc.turn(u) == ch) return true; u += 0.04 }
        return false
    }

    /**
     * 獨立需求（ISO 17387 精神，跟 LaneChangeAssist 的實作無關）：
     * 必須一級 — 移動中的汽車/二輪，整台在自車側面以外、中心不超過相鄰車道外線，並且
     *   在車旁到後保桿後 3 m 之間，或從後方以 ≥ 1.5 m/s 接近且 TTC ≤ 3 s；被自車以 > 7 km/h 超越的不算；倒車時不算。
     * 必須二級 — 必須一級 + 往該側打燈/偏移，或 TTC ≤ 1.5 s 且接近 ≥ 9 km/h。
     * 允許一級/二級 — 寬鬆版（相鄰車道外線外 1.2 m 內、後方 13.5 m 內、TTC ≤ 4.5 s；靜止物只在自車慢速時允許）。
     */
    fun requirement(t: Double): Array<BooleanArray> {
        val out = Array(2) { BooleanArray(4) }
        val v = egoSpeed(t)
        val rev = v < -0.3
        val ln = lanesVeh(t)
        for (side in 0..1) {
            val sg = if (side == 0) -1.0 else 1.0
            val mine = ln.map { it * sg }.filter { it > 0 }.sorted()
            val own = mine.firstOrNull() ?: 1.75
            val outer = mine.firstOrNull { it > own + 0.5 } ?: (own + 3.5)
            val turnS = turnTruth(t, side, 1.0)
            val turnL = turnTruth(t, side, 1.6)
            val driftS = driftTruth(t, side, 0.25, 0.7)
            val driftL = driftTruth(t, side, 0.15, 0.3) || driftTruth(t - 0.5, side, 0.15, 0.3)
            for (a in sc.actors) {
                val g = TrackManager.group(a.t)
                if (g != TrackManager.G_VEH && g != TrackManager.G_TWO) continue
                val p = actorVeh(a, t)
                if (CAMS.values.none { visible(it, p[0], p[1], a.w / 2, a.l / 2) != null }) continue
                val vel = actorVel(a, t)
                val sog = actorSog(a, t)
                val sx = sg * p[0]
                val hw = a.w / 2
                val hl = a.l / 2
                val front = p[1] + hl
                val rear = p[1] - hl
                val range = -HL - front
                val vc = vel[1]
                val ttc = if (range > 0 && vc > 0.3) range / vc else Double.MAX_VALUE
                val latReq = sx - hw >= HW + 0.05 && sx <= outer - 0.3
                val lonReq = (front >= -HL - 3.0 && rear <= 0.0) || (range > 0 && vc >= 1.5 && ttc <= 3.0)
                if (!rev && latReq && lonReq && vc >= -2.0 && sog >= 1.0) {
                    out[side][0] = true
                    if (turnS || driftS || (ttc <= 1.5 && vc >= 2.5)) out[side][1] = true
                }
                val latMay = sx >= HW - 0.3 && sx <= outer + 1.2
                val lonMay = (front >= -HL - 13.5 && rear <= 2.5) || (range > 0 && vc >= 0.6 && ttc <= 4.5)
                if (latMay && lonMay && vc >= -4.5 && !(abs(v) > 5 && sog < 0.5) && !(rev && sog < 1.0)) {
                    out[side][2] = true
                    if (turnL || driftL || ttc <= 2.6) out[side][3] = true
                }
            }
        }
        return out
    }

    /** 除錯用：每一步的鏡頭與偵測。 */
    var onDets: ((Double, String, List<Det>) -> Unit)? = null

    fun run(): List<LcaStep> {
        val steps = ArrayList<LcaStep>()
        var t = 0.0
        var k = 0
        while (t < sc.dur) {
            val cam = cfg.slots[k % cfg.slots.size]
            k++
            val td = t + cfg.latencyMs / 1000.0
            val now = (td * 1000).toLong()
            val dets = if (cam == "tl") emptyList() else detect(cam, t)
            onDets?.invoke(td, cam, dets)
            val ego = gps(td)
            val est = tm.update(dets, now, ego, cam = if (cam == "tl" || !cfg.camHint) null else cam)
            val lo = laneObs(td)
            val turn = sc.turn(td)
            val vTrue = egoSpeed(td)
            val rev = sc.reverseKnown && vTrue < -0.1
            val s = lca.update(est, now, lo[0], lo[1], turn, null, ego, reverse = rev)
            val gt = truth(td)
            val lt = laneTruth(td)
            val vOr = if (sc.reverseKnown) vTrue else abs(vTrue)
            val o = oracle.update(gt, now, lt[0]?.toFloat(), lt[1]?.toFloat(), turn, null, if (sc.gps) vOr.toFloat() else null, reverse = rev)
            steps.add(LcaStep(td, est, gt, s, o, requirement(td)))
            t += 0.07 + (rnd.nextDouble() - 0.5) * 0.024
        }
        return steps
    }
}

/** 一次模擬的評估結果。 */
internal class LcaRunStats {
    var actors = 0
    var idSwitches = 0
    var visSteps = 0
    var matchedSteps = 0
    var velSq = 0.0
    var velN = 0
    var posSq = 0.0
    var posN = 0
    val lat1 = ArrayList<Double>()
    val lat2 = ArrayList<Double>()
    var onsets1 = 0
    var onsets2 = 0
    var miss1 = 0
    var miss2 = 0
    var faTime = 0.0
    var faEpisodes = 0
    var dur = 0.0
    var maxEst = 0
    var maxOracle = 0
    var intentLat = ArrayList<Double>()
    var intentMiss = 0
    var pathOkAgree = 0
    var pathSteps = 0

    // 獨立需求（index 1 = 一級、2 = 二級）
    val reqEp = IntArray(3)
    val reqMissEp = IntArray(3)
    val reqLat = arrayOf(ArrayList<Double>(), ArrayList(), ArrayList())
    val reqTime = DoubleArray(3)
    val reqMissTime = DoubleArray(3)
    val faReqTime = DoubleArray(3)
    val faReqEp = IntArray(3)
    /** 亮燈但前後 1.5 s 內都沒有「必須一級」的時間（含允許但非必要的提示，例如塞車時旁邊靜止的車）。 */
    var extraOnTime = 0.0
    /** 換道路徑顯示可行（綠）但該側正處於「必須二級」的時間。 */
    var badPathOkTime = 0.0
    /** 有換道意圖（有路徑）的時間、其中顯示可行的時間。 */
    var intentTime = 0.0
    var intentOkTime = 0.0

    companion object {
        /** 必須亮燈的情境，給系統多少時間反應（確認軌跡、遲滯）才開始算漏報。 */
        const val ALLOW = 1.0

        fun eval(steps: List<LcaStep>): LcaRunStats {
            val r = LcaRunStats()
            r.dur = steps.last().t - steps.first().t
            fun dt(i: Int) = if (i + 1 < steps.size) steps[i + 1].t - steps[i].t else 0.07
            // --- 追蹤：每一步把真值配到最近的同類確認軌跡（3 m 內） ---
            val lastId = HashMap<Int, Int>()
            val firstVis = HashMap<Int, Double>()
            val lastVis = HashMap<Int, Double>()
            for (s in steps) {
                // 離開所有鏡頭視野 1.5 s 以上再回來 → 重新取得，不算 id 切換
                for (g in s.gt) {
                    val lv = lastVis[g.id]
                    if (lv != null && s.t - lv > 1.5) { lastId.remove(g.id); firstVis[g.id] = s.t }
                    lastVis[g.id] = s.t
                }
                val used = HashSet<Int>()
                val pairs = ArrayList<Triple<Track, Track, Double>>()
                for (g in s.gt) for (e in s.est) {
                    if (!e.confirmed || TrackManager.group(e.t) != TrackManager.group(g.t)) continue
                    val d = hypot((e.x - g.x).toDouble(), (e.z - g.z).toDouble())
                    if (d < 3.0) pairs.add(Triple(g, e, d))
                }
                pairs.sortBy { it.third }
                val matched = HashMap<Int, Track>()
                for ((g, e, _) in pairs) {
                    if (g.id in matched || e.id in used) continue
                    matched[g.id] = e
                    used.add(e.id)
                }
                for (g in s.gt) {
                    val f = firstVis.getOrPut(g.id) { s.t }
                    val e = matched[g.id]
                    if (s.t - f >= 1.5) {
                        r.visSteps++
                        if (e != null) r.matchedSteps++
                    }
                    if (e == null) continue
                    val prev = lastId[g.id]
                    if (prev != null && prev != e.id) r.idSwitches++
                    lastId[g.id] = e.id
                    if (e.ageMs >= 1500) {
                        r.velSq += (e.vx - g.vx).let { it * it } + (e.vz - g.vz).let { it * it }
                        r.velN++
                        r.posSq += (e.x - g.x).let { it * it } + (e.z - g.z).let { it * it }
                        r.posN++
                    }
                }
            }
            r.actors = firstVis.size
            // --- 預警：延遲、漏報、誤報（以同一套邏輯吃真值的「理想系統」為準） ---
            for (side in 0..1) {
                fun est(s: LcaStep) = if (side == 0) s.lca.L else s.lca.R
                fun ora(s: LcaStep) = if (side == 0) s.oracle.L else s.oracle.R
                for (k in 1..2) {
                    for (i in steps.indices) {
                        val on = ora(steps[i]) >= k && (i == 0 || ora(steps[i - 1]) < k)
                        if (!on) continue
                        val t0 = steps[i].t
                        if (k == 1) r.onsets1++ else r.onsets2++
                        val hit = steps.firstOrNull { it.t >= t0 - 0.3 && it.t <= t0 + 3.0 && est(it) >= k }
                        if (hit == null) { if (k == 1) r.miss1++ else r.miss2++ } else {
                            val l = max(0.0, hit.t - t0)
                            if (k == 1) r.lat1.add(l) else r.lat2.add(l)
                        }
                    }
                }
                var inFa = false
                for (i in steps.indices) {
                    val s = steps[i]
                    r.maxEst = max(r.maxEst, est(s))
                    r.maxOracle = max(r.maxOracle, ora(s))
                    val e = est(s)
                    var fa = false
                    if (e > ora(s)) {
                        // 理想系統在 [t−1.0, t+0.5] 內有到這個等級 → 算延遲/提早，不算誤報
                        val ok = steps.any { it.t >= s.t - 1.0 && it.t <= s.t + 0.5 && ora(it) >= e }
                        fa = !ok
                    }
                    if (fa) {
                        r.faTime += dt(i)
                        if (!inFa) r.faEpisodes++
                    }
                    inFa = fa
                }

                // --- 獨立需求 ---
                for (k in 1..2) {
                    fun req(i: Int) = steps[i].req[side][k - 1]
                    fun may(i: Int) = steps[i].req[side][k + 1]
                    var i = 0
                    while (i < steps.size) {
                        if (!req(i)) { i++; continue }
                        var j = i
                        while (j + 1 < steps.size && req(j + 1)) j++
                        val t0 = steps[i].t
                        val t1 = steps[j].t
                        if (t1 - t0 >= 0.3) {
                            r.reqEp[k]++
                            val hit = (i until steps.size).firstOrNull { steps[it].t <= t1 + 0.3 && est(steps[it]) >= k }
                            if (hit != null) r.reqLat[k].add(max(0.0, steps[hit].t - t0))
                            else if (t1 - t0 >= ALLOW) r.reqMissEp[k]++
                            for (m in i..j) if (steps[m].t - t0 >= ALLOW) {
                                r.reqTime[k] += dt(m)
                                if (est(steps[m]) < k) r.reqMissTime[k] += dt(m)
                            }
                        }
                        i = j + 1
                    }
                    var inReqFa = false
                    for (m in steps.indices) {
                        var fa = false
                        if (est(steps[m]) >= k) {
                            val t = steps[m].t
                            fa = steps.indices.none { steps[it].t >= t - 1.2 && steps[it].t <= t + 0.3 && may(it) }
                        }
                        if (fa) { r.faReqTime[k] += dt(m); if (!inReqFa) r.faReqEp[k]++ }
                        inReqFa = fa
                    }
                }
                for (m in steps.indices) {
                    if (est(steps[m]) < 1) continue
                    val t = steps[m].t
                    if (steps.none { it.t >= t - 1.5 && it.t <= t + 1.5 && it.req[side][0] }) r.extraOnTime += dt(m)
                }
            }
            // 路徑可行但其實危險
            for (m in steps.indices) {
                val s = steps[m]
                val side = when (s.lca.intent) { 'L' -> 0; 'R' -> 1; else -> -1 }
                if (side >= 0) { r.intentTime += dt(m); if (s.lca.pathOk) r.intentOkTime += dt(m) }
                if (side >= 0 && s.lca.pathOk && s.req[side][1]) {
                    val t = s.t
                    // 跟必須二級一樣給 ALLOW 的反應時間
                    if (steps.all { it.t < t - ALLOW || it.t > t || it.req[side][1] }) r.badPathOkTime += dt(m)
                }
            }
            // --- 換道意圖 ---
            for (i in steps.indices) {
                val o = steps[i].oracle.intent
                val on = o != null && (i == 0 || steps[i - 1].oracle.intent != o)
                if (on) {
                    val t0 = steps[i].t
                    val hit = steps.firstOrNull { it.t >= t0 - 0.3 && it.t <= t0 + 3.0 && it.lca.intent == o }
                    if (hit == null) r.intentMiss++ else r.intentLat.add(max(0.0, hit.t - t0))
                }
                if (o != null && steps[i].lca.intent == o) {
                    r.pathSteps++
                    if (steps[i].lca.pathOk == steps[i].oracle.pathOk) r.pathOkAgree++
                }
            }
            return r
        }
    }
}

/** 多個亂數種子的彙總。 */
internal class LcaAgg(val name: String) {
    val runs = ArrayList<LcaRunStats>()
    fun add(r: LcaRunStats) = runs.add(r)
    val idSwitchPerActor get() = runs.sumOf { it.idSwitches }.toDouble() / max(1, runs.sumOf { it.actors })
    val maxIdSwitch get() = runs.maxOf { it.idSwitches }
    val continuity get() = runs.sumOf { it.matchedSteps }.toDouble() / max(1, runs.sumOf { it.visSteps })
    val velRms get() = sqrt(runs.sumOf { it.velSq } / max(1, runs.sumOf { it.velN }))
    val posRms get() = sqrt(runs.sumOf { it.posSq } / max(1, runs.sumOf { it.posN }))
    private fun all(f: (LcaRunStats) -> List<Double>) = runs.flatMap(f).sorted()
    fun latMean(k: Int) = all { if (k == 1) it.lat1 else it.lat2 }.let { if (it.isEmpty()) Double.NaN else it.average() }
    fun latP90(k: Int) = all { if (k == 1) it.lat1 else it.lat2 }.let { if (it.isEmpty()) Double.NaN else it[((it.size - 1) * 0.9).toInt()] }
    fun onsets(k: Int) = runs.sumOf { if (k == 1) it.onsets1 else it.onsets2 }
    fun misses(k: Int) = runs.sumOf { if (k == 1) it.miss1 else it.miss2 }
    /** 誤報時間佔比（左右兩側合計 / 兩倍總時間）。 */
    val faRate get() = runs.sumOf { it.faTime } / max(1e-9, 2 * runs.sumOf { it.dur })
    val faPerMin get() = runs.sumOf { it.faEpisodes } / max(1e-9, runs.sumOf { it.dur } / 60)
    val intentLat get() = all { it.intentLat }.let { if (it.isEmpty()) Double.NaN else it.average() }
    val intentMiss get() = runs.sumOf { it.intentMiss }
    val pathOkAgree get() = runs.sumOf { it.pathOkAgree }.toDouble() / max(1, runs.sumOf { it.pathSteps })

    // 獨立需求
    fun reqEp(k: Int) = runs.sumOf { it.reqEp[k] }
    fun reqMissEp(k: Int) = runs.sumOf { it.reqMissEp[k] }
    /** 必須亮燈（扣掉 1 s 反應時間）卻沒亮的時間比例。 */
    fun reqMissRate(k: Int) = runs.sumOf { it.reqMissTime[k] } / max(1e-9, runs.sumOf { it.reqTime[k] })
    fun reqLatMean(k: Int) = all { it.reqLat[k] }.let { if (it.isEmpty()) Double.NaN else it.average() }
    fun reqLatP90(k: Int) = all { it.reqLat[k] }.let { if (it.isEmpty()) Double.NaN else it[((it.size - 1) * 0.9).toInt()] }
    /** 不允許亮燈時亮了（左右兩側合計 / 兩倍總時間）。 */
    fun faReq(k: Int) = runs.sumOf { it.faReqTime[k] } / max(1e-9, 2 * runs.sumOf { it.dur })
    fun faReqPerMin(k: Int) = runs.sumOf { it.faReqEp[k] } / max(1e-9, runs.sumOf { it.dur } / 60)
    val extraOn get() = runs.sumOf { it.extraOnTime } / max(1e-9, 2 * runs.sumOf { it.dur })
    val badPathOk get() = runs.sumOf { it.badPathOkTime } / max(1e-9, runs.sumOf { it.dur })
    /** 有換道意圖時，路徑顯示可行的比例。 */
    val pathOkShare get() = runs.sumOf { it.intentOkTime } / max(1e-9, runs.sumOf { it.intentTime })

    fun row(): String = String.format(
        "%-22s id切換/車 %.2f(最多%d) 連續 %3.0f%% 速度RMS %.2f 位置RMS %.2f | 一級延遲 %.2f/%.2fs 漏%d/%d | " +
            "二級延遲 %.2f/%.2fs 漏%d/%d | 誤報 %.1f%% %.2f次/分 | 意圖延遲 %.2fs 漏%d pathOk一致 %3.0f%%",
        name, idSwitchPerActor, maxIdSwitch, continuity * 100, velRms, posRms,
        latMean(1), latP90(1), misses(1), onsets(1), latMean(2), latP90(2), misses(2), onsets(2),
        faRate * 100, faPerMin, intentLat, intentMiss, pathOkAgree * 100
    )

    /** 獨立需求的結果：漏報（危險）與不該亮卻亮（擾人）。 */
    fun reqRow(): String = String.format(
        "%-24s 必須一級: 漏段 %d/%d 漏時 %4.1f%% 延遲 %.2f/%.2fs | 必須二級: 漏段 %d/%d 漏時 %4.1f%% 延遲 %.2f/%.2fs | " +
            "不該亮: 一級 %.2f%% %.2f次/分 二級 %.2f%% %.2f次/分 | 多餘提示 %4.1f%% | 危險卻顯示可行 %.1f%% 有意圖時可行 %3.0f%% | id切換/車 %.2f 速度RMS %.2f",
        name, reqMissEp(1), reqEp(1), reqMissRate(1) * 100, reqLatMean(1), reqLatP90(1),
        reqMissEp(2), reqEp(2), reqMissRate(2) * 100, reqLatMean(2), reqLatP90(2),
        faReq(1) * 100, faReqPerMin(1), faReq(2) * 100, faReqPerMin(2), extraOn * 100, badPathOk * 100, pathOkShare * 100,
        idSwitchPerActor, velRms
    )
}

internal object LcaScenarios {
    private const val KMH = 1 / 3.6

    /** 方向燈閃爍：亮 0.4 s、暗 0.4 s。 */
    fun blink(side: Char, from: Double, to: Double): (Double) -> Char? =
        { t -> if (t in from..to && ((t * 1000).toLong() % 800) < 400) side else null }

    /** 往左偏 0.4 m/s 持續 2.5 s 再修正回來。 */
    fun drift(t: Double): Double = when {
        t < 4.0 -> 0.0
        t < 6.5 -> -0.4 * (t - 4.0)
        t < 9.0 -> -1.0 + 0.4 * (t - 6.5)
        else -> 0.0
    }

    fun all(): List<LcaScenario> {
        val v60 = 60 * KMH
        return listOf(
            LcaScenario("左後來車 +10km/h", 16.0, v60, listOf(LcaActor("car") { t -> doubleArrayOf(-3.5, v60 * t - 30 + 10 * KMH * t) })),
            LcaScenario("左後來車 +30km/h", 7.0, v60, listOf(LcaActor("car") { t -> doubleArrayOf(-3.5, v60 * t - 30 + 30 * KMH * t) })),
            LcaScenario("左後來車 +10 無GPS", 16.0, v60, listOf(LcaActor("car") { t -> doubleArrayOf(-3.5, v60 * t - 30 + 10 * KMH * t) }), gps = false),
            LcaScenario("右側路邊停車 30km/h", 6.0, 30 * KMH, listOf(LcaActor("car") { _ -> doubleArrayOf(3.4, 30.0) })),
            LcaScenario("超越慢車 60vs45", 8.0, v60, listOf(LcaActor("car") { t -> doubleArrayOf(-3.5, v60 * t + 15 - 15 * KMH * t) })),
            LcaScenario("緩慢超越 60vs55", 14.0, v60, listOf(LcaActor("car") { t -> doubleArrayOf(-3.5, v60 * t + 8 - 5 * KMH * t) })),
            LcaScenario("右側切入", 12.0, 50 * KMH, listOf(LcaActor("car") { t ->
                val x = when { t < 7.2 -> 3.5; t < 10.2 -> 3.5 - 3.5 * smooth((t - 7.2) / 3.0); else -> 0.0 }
                doubleArrayOf(x, 50 * KMH * t - 14 + 10 * KMH * t)
            })),
            LcaScenario("行人穿越車後", 10.0, 0.0, listOf(LcaActor("ped") { t -> doubleArrayOf(-7 + 1.4 * t, -4.5) })),
            LcaScenario("後→側鏡頭交接 +5", 18.0, 40 * KMH, listOf(LcaActor("car") { t -> doubleArrayOf(-3.4, 40 * KMH * t - 20 + 5 * KMH * t) })),
            LcaScenario("打左燈+盲點有車", 10.0, v60, listOf(LcaActor("car") { t -> doubleArrayOf(-3.5, v60 * t - 5) }), turn = blink('L', 3.0, 8.0)),
            LcaScenario("打左燈 無來車", 9.0, v60, listOf(LcaActor("car") { t -> doubleArrayOf(3.5, v60 * t + 12) }), turn = blink('L', 2.0, 7.0)),
            LcaScenario("未打燈左偏+盲點有車", 11.0, v60, listOf(LcaActor("car") { t -> doubleArrayOf(-3.5, v60 * t - 4) }), egoX = ::drift),
            LcaScenario("未打燈左偏 無來車", 11.0, v60, emptyList(), egoX = ::drift)
        )
    }

    /** 偽亂數（依時間格點）：車道線故障的閃爍。 */
    private fun flick(t: Double, pct: Int): Boolean = (((t * 1000).toLong() / 70) * 2654435761L).let { ((it ushr 7) % 100).toInt() } < pct

    /** 安全審查加的困難情境。 */
    fun hard(): List<LcaScenario> {
        val v60 = 60 * KMH
        val v20 = 20 * KMH
        val occ = LcaSimCfg(occlusion = true)
        return listOf(
            // --- 多台、遮擋 ---
            LcaScenario("左車道兩台 盲點+來車+打燈", 14.0, v60, listOf(
                LcaActor("car") { t -> doubleArrayOf(-3.5, v60 * t - 2 + 2.0 * max(0.0, t - 5)) },
                LcaActor("car") { t -> doubleArrayOf(-3.5, v60 * t + min(-26 + 12 * KMH * t, -9 + 2.0 * max(0.0, t - 5))) }
            ), turn = blink('L', 8.0, 12.0), cfg = occ),
            LcaScenario("左車道車隊三台 +10", 18.0, v60, listOf(
                LcaActor("car") { t -> doubleArrayOf(-3.5, v60 * t - 18 + 10 * KMH * t) },
                LcaActor("car") { t -> doubleArrayOf(-3.5, v60 * t - 27 + 10 * KMH * t) },
                LcaActor("van") { t -> doubleArrayOf(-3.5, v60 * t - 36.5 + 10 * KMH * t) }
            ), cfg = occ),
            // --- 機車鑽車縫（台灣常見） ---
            LcaScenario("機車鑽縫 左 車流20 打燈", 8.0, v20, listOf(
                LcaActor("car") { t -> doubleArrayOf(-3.7, v20 * t - 7) },
                LcaActor("moto") { t -> doubleArrayOf(-1.45, v20 * t - 22 + 20 * KMH * t) }
            ), turn = blink('L', 2.0, 6.0), cfg = occ),
            LcaScenario("機車鑽縫 左 自車靠右", 8.0, v20, listOf(
                LcaActor("moto") { t -> doubleArrayOf(-1.0, v20 * t - 22 + 25 * KMH * t) }
            ), egoX = { 0.7 }, turn = blink('L', 2.0, 7.0)),
            LcaScenario("機車鑽縫 右 不打燈", 8.0, v20, listOf(
                LcaActor("moto") { t -> doubleArrayOf(1.5, v20 * t - 20 + 20 * KMH * t) }
            )),
            LcaScenario("停滯車陣 機車鑽縫", 14.0, 0.0, listOf(
                LcaActor("car") { _ -> doubleArrayOf(-3.5, -9.0) }, LcaActor("car") { _ -> doubleArrayOf(-3.5, -2.5) },
                LcaActor("car") { _ -> doubleArrayOf(3.5, -6.0) }, LcaActor("car") { _ -> doubleArrayOf(3.5, 0.5) },
                LcaActor("moto") { t -> doubleArrayOf(-1.7, -24 + 5.0 * max(0.0, t - 1)) },
                LcaActor("moto") { t -> doubleArrayOf(1.7, -24 + 4.0 * max(0.0, t - 7)) }
            ), cfg = occ),
            LcaScenario("停滯車陣 打左燈 機車來", 12.0, 0.0, listOf(
                LcaActor("car") { _ -> doubleArrayOf(-3.5, -9.0) }, LcaActor("car") { _ -> doubleArrayOf(-3.5, -2.5) },
                LcaActor("moto") { t -> doubleArrayOf(-1.7, -24 + 5.0 * max(0.0, t - 3)) }
            ), turn = blink('L', 1.0, 11.0), cfg = occ),
            LcaScenario("本車道後方機車跟車", 12.0, v20, listOf(
                LcaActor("moto") { t -> doubleArrayOf(-0.4, v20 * t + min(-8.0 + 5 * KMH * t, -4.6)) }
            )),
            // --- 不該警示 ---
            LcaScenario("隔一車道來車/卡車", 12.0, v60, listOf(
                LcaActor("car") { t -> doubleArrayOf(-7.0, v60 * t - 25 + 20 * KMH * t) },
                LcaActor("truck") { t -> doubleArrayOf(7.2, v60 * t - 28 + 15 * KMH * t) }
            ), lanes = doubleArrayOf(-8.75, -5.25, -1.75, 1.75, 5.25, 8.75)),
            LcaScenario("左偏+右側有車", 11.0, v60, listOf(LcaActor("car") { t -> doubleArrayOf(3.5, v60 * t - 3) }), egoX = ::drift),
            LcaScenario("倒車經過路邊停車 不知排檔", 10.0, -1.5, listOf(
                LcaActor("car") { _ -> doubleArrayOf(2.7, -6.0) }, LcaActor("car") { _ -> doubleArrayOf(2.7, -12.0) },
                LcaActor("car") { _ -> doubleArrayOf(2.7, 3.0) }, LcaActor("moto") { _ -> doubleArrayOf(2.3, -17.0) }
            ), cfg = occ),
            LcaScenario("倒車經過路邊停車 知道排檔", 10.0, -1.5, listOf(
                LcaActor("car") { _ -> doubleArrayOf(2.7, -6.0) }, LcaActor("car") { _ -> doubleArrayOf(2.7, -12.0) },
                LcaActor("car") { _ -> doubleArrayOf(2.7, 3.0) }, LcaActor("moto") { _ -> doubleArrayOf(2.3, -17.0) }
            ), reverseKnown = true, cfg = occ),
            // --- 鏡頭校正偏差 ---
            LcaScenario("接縫偏1m 交接 後前移", 18.0, 40 * KMH, listOf(LcaActor("car") { t -> doubleArrayOf(-3.4, 40 * KMH * t - 20 + 5 * KMH * t) }),
                cfg = LcaSimCfg(camBias = mapOf("rear" to doubleArrayOf(0.0, 0.5, 0.0), "left" to doubleArrayOf(0.0, -0.5, 0.0)))),
            LcaScenario("接縫偏1m 交接 後後移", 18.0, 40 * KMH, listOf(LcaActor("car") { t -> doubleArrayOf(-3.4, 40 * KMH * t - 20 + 5 * KMH * t) }),
                cfg = LcaSimCfg(camBias = mapOf("rear" to doubleArrayOf(0.0, -0.5, 0.0), "left" to doubleArrayOf(0.0, 0.5, 0.0)))),
            LcaScenario("接縫偏1m 橫向 +10", 16.0, v60, listOf(LcaActor("car") { t -> doubleArrayOf(-3.5, v60 * t - 30 + 10 * KMH * t) }),
                cfg = LcaSimCfg(camBias = mapOf("rear" to doubleArrayOf(0.5, 0.0, 0.0), "left" to doubleArrayOf(-0.5, 0.0, 0.0)))),
            LcaScenario("後鏡頭俯角差1° +10", 16.0, v60, listOf(LcaActor("car") { t -> doubleArrayOf(-3.5, v60 * t - 30 + 10 * KMH * t) }),
                cfg = LcaSimCfg(camBias = mapOf("rear" to doubleArrayOf(0.0, 0.0, 1.0)))),
            // --- GPS ---
            LcaScenario("GPS斷續 +10來車", 16.0, v60, listOf(LcaActor("car") { t -> doubleArrayOf(-3.5, v60 * t - 30 + 10 * KMH * t) }),
                gpsUp = { t -> (t % 5.0) < 2.0 }),
            LcaScenario("GPS斷續 路邊停車30", 6.0, 30 * KMH, listOf(LcaActor("car") { _ -> doubleArrayOf(3.4, 30.0) }, LcaActor("moto") { _ -> doubleArrayOf(2.6, 14.0) }),
                gpsUp = { t -> (t % 2.5) < 1.0 }),
            LcaScenario("自車煞車 GPS舊值 左車45", 10.0, 0.0, listOf(LcaActor("car") { t -> doubleArrayOf(-3.5, -12 + 45 * KMH * t) }),
                egoZ = { t -> if (t < 2) v60 * t else { val u = min(t - 2, 4.0); v60 * 2 + v60 * u - 0.5 * 2.8 * u * u + (v60 - 2.8 * 4) * max(0.0, t - 6) } },
                gpsUp = { t -> t < 2.5 }, gpsStale = true),
            // --- 車道線輸入錯誤 ---
            LcaScenario("車道線 漏近線報遠線(3m道)", 16.0, v60, listOf(LcaActor("car") { t -> doubleArrayOf(-3.0, v60 * t - 30 + 10 * KMH * t) }),
                lanes = doubleArrayOf(-7.5, -4.5, -1.5, 1.5, 4.5, 7.5),
                laneFault = { t, lr -> if (t in 3.0..12.0) arrayOf(lr[0]?.minus(3.0), lr[1]) else lr }),
            LcaScenario("車道線 假線貼車身閃爍", 16.0, v60, listOf(LcaActor("car") { t -> doubleArrayOf(-3.5, v60 * t - 30 + 10 * KMH * t) }),
                laneFault = { t, lr -> if (t in 2.0..14.0 && flick(t, 40)) arrayOf(-0.95, lr[1]) else lr }),
            LcaScenario("車道線 左線被擋右線報遠線", 16.0, v60, listOf(
                LcaActor("car") { t -> doubleArrayOf(3.5, v60 * t - 30 + 10 * KMH * t) },
                LcaActor("car") { t -> doubleArrayOf(0.0, v60 * t - 10) }
            ), laneFault = { t, lr -> if (t in 4.0..13.0) arrayOf(null, lr[1]?.plus(3.5)) else lr }),
            // --- 換道路徑：目標車道前方（盲點區外）有車 ---
            LcaScenario("打左燈 左前方並行車", 9.0, v60, listOf(LcaActor("car") { t -> doubleArrayOf(-3.5, v60 * t + 4.5) }),
                turn = blink('L', 2.0, 7.0)),
            LcaScenario("打左燈 左前方遠車", 9.0, v60, listOf(LcaActor("car") { t -> doubleArrayOf(-3.5, v60 * t + 14) }),
                turn = blink('L', 2.0, 7.0)),
            // --- 其他 ---
            LcaScenario("大客車超越 +10", 16.0, v60, listOf(LcaActor("bus") { t -> doubleArrayOf(-3.6, v60 * t - 27 + 10 * KMH * t) })),
            LcaScenario("高速來車 +50km/h", 6.0, 80 * KMH, listOf(LcaActor("car") { t -> doubleArrayOf(-3.5, 80 * KMH * t - 40 + 50 * KMH * t) }))
        )
    }

    fun smooth(u: Double): Double { val x = u.coerceIn(0.0, 1.0); return x * x * x * (10 - 15 * x + 6 * x * x) }

    fun evaluate(sc: LcaScenario, seeds: Int = 25, cfg: LcaSimCfg = LcaSimCfg()): LcaAgg {
        val a = LcaAgg(sc.name)
        for (s in 0 until seeds) a.add(LcaRunStats.eval(LcaSim(sc, 1000L + s * 7919L, cfg).run()))
        return a
    }
}

class TrackManagerTest {

    private fun det(t: String, x: Float, z: Float, cam: String = "left", c: Float = 0.7f) = Det(t, x, z, c, cam)

    @Test
    fun birthNeedsTwoHitsAndIdIsStable() {
        val tm = TrackManager()
        var out = tm.update(listOf(det("car", -3.5f, -6f)), 0, null)
        assertEquals(1, out.size)
        assertFalse(out[0].confirmed)
        val id = out[0].id
        out = tm.update(listOf(det("car", -3.45f, -5.9f)), 250, null)
        assertEquals(1, out.size)
        assertTrue(out[0].confirmed)
        assertEquals(id, out[0].id)
        out = tm.update(listOf(det("car", -3.5f, -5.8f, "rear")), 500, null)
        assertEquals(id, out.single().id)
        assertEquals(3, out.single().hits)
    }

    @Test
    fun tentativeDiesAndConfirmedCoasts() {
        val tm = TrackManager()
        tm.update(listOf(det("car", -3.5f, -14f, "rear")), 0, null)
        assertTrue(tm.update(emptyList(), 900, null).isNotEmpty())
        assertTrue("候選軌跡 1 s 內沒第二次命中就丟", tm.update(emptyList(), 1100, null).isEmpty())

        // 中距離（離車身 3.5–8 m）：1.2 s 沒看到就刪
        val mid = TrackManager()
        for (i in 0..3) mid.update(listOf(det("car", -3.5f, -8f + i * 0.01f, "rear")), i * 300L, null)
        assertEquals(1, mid.update(emptyList(), 900 + 1100, null).size)
        assertTrue(mid.update(emptyList(), 900 + 1300, null).isEmpty())

        // 8 m 外只有 2 Hz 的後鏡頭、偵測率低：給到 1.8 s
        val far = TrackManager()
        for (i in 0..3) far.update(listOf(det("car", -3.5f, -14f + i * 0.01f, "rear")), i * 300L, null)
        assertEquals(1, far.update(emptyList(), 900 + 1700, null).size)
        assertTrue(far.update(emptyList(), 900 + 1900, null).isEmpty())

        // 車身旁（鏡頭交界 / 太近框不完整）：可以滑行到 2.5 s
        val near = TrackManager()
        for (i in 0..3) near.update(listOf(det("car", -3.4f, -1f + i * 0.01f)), i * 300L, null)
        assertEquals(1, near.update(emptyList(), 900 + 2000, null).size)
        assertTrue(near.update(emptyList(), 900 + 2600, null).isEmpty())
    }

    @Test
    fun typeCompatibility() {
        val tm = TrackManager()
        var out = tm.update(listOf(det("car", -3.5f, -3f), det("ped", -3.4f, -3.2f)), 0, null)
        assertEquals("汽車與行人不互相關聯", 2, out.size)
        out = tm.update(listOf(det("van", -3.5f, -3.0f), det("ped", -3.4f, -3.1f)), 250, null)
        assertEquals(2, out.size)
        assertTrue(out.all { it.confirmed })
        out = tm.update(listOf(det("truck", -3.5f, -2.9f, c = 0.3f), det("bigbike", -3.4f, -3.1f)), 500, null)
        // 卡車併入汽車軌跡（種類投票仍是轎車/箱型車），重機不會併入行人 → 新的候選軌跡
        assertEquals(3, out.size)
        assertTrue(out.any { it.t == "car" || it.t == "van" })
        assertTrue(out.any { it.t == "bigbike" && !it.confirmed })
    }

    @Test
    fun staleResendIsIgnored() {
        val tm = TrackManager()
        val l = listOf(det("car", -3.5f, -6f))
        tm.update(l, 0, null)
        tm.update(listOf(det("car", -3.5f, -5.9f)), 250, null)
        val a = tm.update(l, 500, null).single()
        val b = tm.update(l, 600, null).single()
        assertEquals(3, a.hits)
        assertEquals("同一批結果重送不算新的命中", 3, b.hits)
    }

    @Test
    fun velocityConvergesAtLowRate() {
        val rnd = Random(3)
        val tm = TrackManager()
        var last: Track? = null
        for (i in 0..16) {   // 4 Hz、3%~ 誤差：等速 +3 m/s 從後方接近
            val t = i * 0.25
            val z = -12 + 3 * t
            val out = tm.update(listOf(det("car", (-3.5 + rnd.nextGaussian() * 0.15).toFloat(), (z + rnd.nextGaussian() * 0.25).toFloat(), "x")),
                (t * 1000).toLong(), null)
            last = out.single()
        }
        assertNotNull(last)
        assertEquals(3.0, last!!.vz.toDouble(), 0.4)
        assertEquals(0.0, last.vx.toDouble(), 0.3)
    }

    @Test
    fun nearFaceCorrectionRecoversCentre() {
        // 正後方 10 m 的轎車：後鏡頭看到的是車頭（z = −7.75），QuadActivity 再外推 0.9 m → −8.65
        val tm = TrackManager()
        val out = tm.update(listOf(det("car", 0f, -8.65f, "rear")), 0, null).single()
        assertEquals(-10.0, out.z.toDouble(), 0.05)
        assertEquals(0.0, out.x.toDouble(), 0.01)
        // 正左方的車：側面離鏡頭最近，外推 0.9 m 已經是中心，不再移動
        val tm2 = TrackManager()
        val o2 = tm2.update(listOf(det("car", -3.5f, 0.85f, "left")), 0, null).single()
        assertEquals(-3.5, o2.x.toDouble(), 0.05)
        // 關掉修正就照原樣
        val tm3 = TrackManager(nearFaceCorrection = false)
        assertEquals(-8.65, tm3.update(listOf(det("car", 0f, -8.65f, "rear")), 0, null).single().z.toDouble(), 1e-4)
    }

    @Test
    fun coneIsStaticWithEgoSpeed() {
        val tm = TrackManager()
        var o: Track? = null
        for (i in 0..6) o = tm.update(listOf(det("cone", 2.5f, (8 - 10 * i * 0.2).toFloat(), "front")), i * 200L, 10f).single()
        assertEquals(-10.0, o!!.vz.toDouble(), 0.5)
        assertEquals(0.0, o.sog!!.toDouble(), 0.6)
    }

    @Test
    fun yawRateRotatesTracks() {
        val tm = TrackManager()
        tm.update(listOf(det("cone", 0f, 10f, "front")), 0, null)
        // 自車左轉 0.2 rad/s 持續 0.5 s → 正前方的點看起來往右 ~1 m
        val o = tm.update(emptyList(), 500, null, egoYawRate = 0.2f).single()
        assertEquals(10 * sin(0.1), o.x.toDouble(), 0.05)
        assertNull(o.sog)
    }

    @Test
    fun latencyCompensation() {
        // 物件 +4 m/s，量測都晚 100 ms：補償後位置應該是「現在」的位置
        val tm = TrackManager()
        var o: Track? = null
        for (i in 0..12) {
            val now = i * 250L
            val cap = now - 100
            val z = -12 + 4 * cap / 1000.0
            o = tm.update(listOf(Det("car", -3.5f, z.toFloat(), 0.7f, "x", cap)), now, null).single()
        }
        val zNow = -12 + 4 * 3.0
        assertEquals(zNow, o!!.z.toDouble(), 0.15)
    }

    @Test
    fun ghostDiesWhenTheCameraKeepsSeeingNothing() {
        // 兩個誤報剛好湊成一條確認軌跡（2 次命中）：左鏡頭接下來 3 次都說「沒東西」→ 刪掉
        val tm = TrackManager()
        tm.update(listOf(det("car", -3.0f, -2.0f)), 0, null, cam = "left")
        assertTrue(tm.update(listOf(det("car", -3.1f, -2.1f)), 250, null, cam = "left").single().confirmed)
        tm.update(emptyList(), 500, null, cam = "left")
        tm.update(emptyList(), 750, null, cam = "left")
        assertTrue("鏡頭該看到卻連續沒看到 → 刪", tm.update(emptyList(), 1000, null, cam = "left").isEmpty())
        // 沒告訴追蹤器是哪顆鏡頭：只能靠逾時（1.2 s 以上）
        val noHint = TrackManager()
        noHint.update(listOf(det("car", -3.0f, -2.0f)), 0, null)
        noHint.update(listOf(det("car", -3.1f, -2.1f)), 250, null)
        assertEquals(1, noHint.update(emptyList(), 1000, null).size)
        // 證據多的軌跡（例如被旁邊的車擋住）不會因為幾次沒看到就被刪
        val real = TrackManager()
        for (i in 0..9) real.update(listOf(det("car", -3.0f + i * 0.001f, -2.0f)), i * 250L, null, cam = "left")   // 每次略有不同（完全相同會被當成重送）
        for (i in 10..14) real.update(emptyList(), i * 250L, null, cam = "left")
        assertEquals(1, real.update(emptyList(), 15 * 250L, null, cam = "left").size)
        // 鏡頭看不到的位置（右鏡頭被自己車身擋住）不算 miss
        val hidden = TrackManager()
        hidden.update(listOf(det("car", -3.0f, -2.0f)), 0, null, cam = "left")
        hidden.update(listOf(det("car", -3.1f, -2.1f)), 250, null, cam = "left")
        for (i in 2..4) hidden.update(emptyList(), i * 250L, null, cam = "right")
        assertEquals(1, hidden.update(emptyList(), 1100, null, cam = "right").size)
    }

    @Test
    fun reversingEgoSpeedIsSigned() {
        // 倒車 2 m/s（整合端知道排檔，車速傳負值）：靜止的三角錐相對自車往前移 +2 m/s，對地速度 0
        val tm = TrackManager()
        var o: Track? = null
        for (i in 0..6) o = tm.update(listOf(det("cone", 2.5f, (-8 + 2 * i * 0.2).toFloat(), "rear")), i * 200L, -2f).single()
        assertEquals(2.0, o!!.vz.toDouble(), 0.4)
        assertEquals(0.0, o.sog!!.toDouble(), 0.5)
    }

    @Test
    fun longVehicleSeenByTwoCamerasStaysOneTrack() {
        // 11 m 的公車在左側並排：左鏡頭看到車身中段，後鏡頭看到車頭角落 → 框中心差 4 m，仍是同一條軌跡
        val tm = TrackManager()
        for (i in 0..3) tm.update(listOf(det("bus", -3.6f, -1.0f + i * 0.001f, "left")), i * 250L, null, cam = "left")
        val out = tm.update(listOf(det("bus", -3.4f, -4.8f, "rear")), 1000, null, cam = "rear")
        assertEquals("不另外生一條公車軌跡", 1, out.size)
    }

    @Test
    fun outlierDoesNotYankConvergedTrack() {
        // 已收斂的軌跡（靜止、側鏡頭 4 Hz，4 m 處距離 σ ≈ 0.7 m），一次差 2.5 m（~2.8σ）的偵測：
        // 一般卡爾曼會拉 ~0.95 m，穩健更新把量測誤差放大後只拉 ~0.6 m
        val tm = TrackManager()
        var o: Track? = null
        for (i in 0..11) o = tm.update(listOf(det("car", -5.0f + i * 0.001f, 0.85f)), i * 250L, null, cam = "left").single()
        val x0 = o!!.x
        o = tm.update(listOf(det("car", -2.5f, 0.85f)), 12 * 250L, null, cam = "left").single()
        assertTrue("被拉了 ${o.x - x0} m", abs(o.x - x0) < 0.75f)
    }

    @Test
    fun scenarioTrackingMetrics() {
        println("\n=== 追蹤＋換道輔助 情境評估（每情境 25 個亂數種子） ===")
        val res = LcaScenarios.all().map { LcaScenarios.evaluate(it) }
        for (a in res) println(a.row())
        val byName = res.associateBy { it.name }
        // 交接情境：同一台車從後鏡頭進到側鏡頭，id 幾乎不換
        val ho = byName.getValue("後→側鏡頭交接 +5")
        assertTrue("交接 id 切換 ${ho.idSwitchPerActor}", ho.idSwitchPerActor <= 0.2)
        assertTrue("交接連續率 ${ho.continuity}", ho.continuity >= 0.85)
        for (a in res) {
            if (a.runs.sumOf { it.velN } == 0) continue
            assertTrue("${a.name} 速度 RMS ${a.velRms}", a.velRms < 2.0)
            assertTrue("${a.name} id 切換 ${a.idSwitchPerActor}", a.idSwitchPerActor <= 0.6)
        }

        println("\n--- 對照：關掉車身外形修正（nearFaceCorrection=false） ---")
        for (name in listOf("後→側鏡頭交接 +5", "左後來車 +10km/h")) {
            val sc = LcaScenarios.all().first { it.name == name }
            println(LcaScenarios.evaluate(sc, cfg = LcaSimCfg(nearFace = false)).row())
        }
    }
}
