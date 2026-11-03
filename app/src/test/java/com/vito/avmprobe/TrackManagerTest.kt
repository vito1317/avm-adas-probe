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
// 世界座標：X 右、Z 前（直路，車道線 X = −5.25/−1.75/1.75/5.25）；自車沿 Z 以 egoV 前進，橫向 egoX(t)。
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
    val lanes: DoubleArray = doubleArrayOf(-5.25, -1.75, 1.75, 5.25)
)

internal class LcaSimCfg(
    val fpRate: Double = 0.06,       // 每次推論出現一個誤報的機率
    val crossType: Double = 0.01,    // 跨類別誤認（車→機車、行人→機車）
    val inType: Double = 0.10,       // 同類別誤認（轎車→箱型車/卡車）
    val latencyMs: Long = 60,        // 拍攝到結果出來
    val nearFace: Boolean = true
)

internal class LcaStep(val t: Double, val est: List<Track>, val gt: List<Track>, val lca: LcaState, val oracle: LcaState)

internal class LcaSim(private val sc: LcaScenario, seed: Long, private val cfg: LcaSimCfg = LcaSimCfg()) {
    class Cam(val name: String, val x: Double, val z: Double, val fx: Double, val fz: Double, val range: Double)

    companion object {
        val CAMS = mapOf(
            "front" to Cam("front", 0.0, 2.35, 0.0, 1.0, 15.0),
            "rear" to Cam("rear", 0.0, -2.40, 0.0, -1.0, 15.0),
            "left" to Cam("left", -1.0, 0.85, -1.0, 0.0, 12.0),
            "right" to Cam("right", 1.0, 0.85, 1.0, 0.0, 12.0)
        )
        /** 跟 QuadActivity.slots() 一樣：左右各兩次、前後各一次，再一輪紅綠燈。 */
        val SLOTS = listOf("left", "right", "front", "left", "right", "rear", "tl")
        const val HW = 0.925
        const val HL = 2.42
        val COS_FOV: Double = cos(Math.toRadians(95.0))

        fun segHitsRect(x0: Double, z0: Double, x1: Double, z1: Double, a: Double, b: Double): Boolean {
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

    private val rnd = Random(seed)
    val tm = TrackManager(nearFaceCorrection = cfg.nearFace)
    val lca = LaneChangeAssist()
    val oracle = LaneChangeAssist()
    private var gpsVal: Float? = null
    private var gpsAt = -10.0
    private val emaLane = arrayOfNulls<Double>(2)

    private fun g() = rnd.nextGaussian()

    fun egoHeading(t: Double): Double {
        val v = max(sc.egoV, 0.5)
        return atan2((sc.egoX(t + 0.01) - sc.egoX(t - 0.01)) / 0.02, v)
    }

    fun toVeh(t: Double, xw: Double, zw: Double): DoubleArray {
        val b = egoHeading(t)
        val dx = xw - sc.egoX(t)
        val dz = zw - sc.egoV * t
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
            val range = dn + TrackManager.defaultRadius(a.t) + nr
            val dx = c.x + ux * range - uz * nt
            val dz = c.z + uz * range + ux * nt
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

    /** 最近的左 / 右車道線在 z=0 的 x（真值）。 */
    fun laneTruth(t: Double): Array<Double?> {
        val b = egoHeading(t)
        var l: Double? = null
        var r: Double? = null
        for (xl in sc.lanes) {
            val x = (xl - sc.egoX(t)) / cos(b)
            if (x < 0 && (l == null || x > l)) l = x
            if (x >= 0 && (r == null || x < r)) r = x
        }
        return arrayOf(l, r)
    }

    /** 模擬 RoadTracker 的輸出：雜訊 4 cm、EMA 0.35、5% 掉線。 */
    private fun laneObs(t: Double): Array<Float?> {
        val tr = laneTruth(t)
        val out = arrayOfNulls<Float>(2)
        for (i in 0..1) {
            val v = tr[i] ?: continue
            val o = v + g() * 0.04
            val e = emaLane[i]
            emaLane[i] = if (e == null || abs(e - o) > 1.5) o else e + (o - e) * 0.35
            if (rnd.nextDouble() > 0.05) out[i] = emaLane[i]!!.toFloat()
        }
        return out
    }

    private fun gps(t: Double): Float? {
        if (!sc.gps) return null
        if (t - gpsAt >= 1.0) { gpsAt = t; gpsVal = max(0.0, sc.egoV + g() * 0.3).toFloat() }
        return gpsVal
    }

    fun run(): List<LcaStep> {
        val steps = ArrayList<LcaStep>()
        var t = 0.0
        var k = 0
        while (t < sc.dur) {
            val cam = SLOTS[k % SLOTS.size]
            k++
            val td = t + cfg.latencyMs / 1000.0
            val now = (td * 1000).toLong()
            val dets = if (cam == "tl") emptyList() else detect(cam, t)
            val ego = gps(td)
            val est = tm.update(dets, now, ego)
            val lo = laneObs(td)
            val turn = sc.turn(td)
            val s = lca.update(est, now, lo[0], lo[1], turn, null, ego)
            val gt = truth(td)
            val lt = laneTruth(td)
            val o = oracle.update(gt, now, lt[0]?.toFloat(), lt[1]?.toFloat(), turn, null, sc.egoV.toFloat())
            steps.add(LcaStep(td, est, gt, s, o))
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

    companion object {
        fun eval(steps: List<LcaStep>): LcaRunStats {
            val r = LcaRunStats()
            r.dur = steps.last().t - steps.first().t
            // --- 追蹤：每一步把真值配到最近的同類確認軌跡（3 m 內） ---
            val lastId = HashMap<Int, Int>()
            val firstVis = HashMap<Int, Double>()
            for (s in steps) {
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
                        val dt = if (i + 1 < steps.size) steps[i + 1].t - s.t else 0.07
                        r.faTime += dt
                        if (!inFa) r.faEpisodes++
                    }
                    inFa = fa
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

    fun row(): String = String.format(
        "%-22s id切換/車 %.2f(最多%d) 連續 %3.0f%% 速度RMS %.2f 位置RMS %.2f | 一級延遲 %.2f/%.2fs 漏%d/%d | " +
            "二級延遲 %.2f/%.2fs 漏%d/%d | 誤報 %.1f%% %.2f次/分 | 意圖延遲 %.2fs 漏%d pathOk一致 %3.0f%%",
        name, idSwitchPerActor, maxIdSwitch, continuity * 100, velRms, posRms,
        latMean(1), latP90(1), misses(1), onsets(1), latMean(2), latP90(2), misses(2), onsets(2),
        faRate * 100, faPerMin, intentLat, intentMiss, pathOkAgree * 100
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
