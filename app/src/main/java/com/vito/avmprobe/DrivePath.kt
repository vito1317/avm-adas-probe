package com.vito.avmprobe

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * 開車路徑預估線（像特斯拉畫面上那條藍色路徑）：車子接下來大概會走哪裡。
 *
 * - 車頭附近：照現在的方向盤角度（腳踏車模型：曲率 = tan(前輪角) / 軸距）走弧線；
 * - 往前漸漸併到自己車道的中線（[LaneRibbon] 的左右線中間），所以會順著前方的路彎；
 * - 打方向燈、方向盤打很大（曲率 > [TURN_K]，約半徑 25 m 以內）、或看不到車道線 → 只照方向盤；
 * - 長度跟車速走：約 3 秒會走到的距離，介於 [MIN_M]～[MAX_M]。倒車往後畫。
 * 點是 (x 右, z 前)，車身座標 m；起點在車頭（倒車在車尾）。只用來顯示，不拿去控制車子。
 */
object DrivePath {
    const val MIN_M = 8.0
    const val MAX_M = 40.0
    const val TURN_K = 1.0 / 25.0
    private val G = VehicleGeometry()

    fun predict(lane: LaneRibbon.Lane?, steerDeg: Float?, speedKmh: Float?, turn: Char?, reverse: Boolean): List<FloatArray> {
        val v = (speedKmh ?: 0f) / 3.6
        val len = (v * 3.0).coerceIn(MIN_M, MAX_M)
        val wheel = Math.toRadians(((steerDeg ?: 0f) / G.steerRatio).toDouble().coerceIn(-G.maxWheelDeg, G.maxWheelDeg).let { it })
        val k = tan(wheel) / G.wheelbase                         // + = 往右
        val dir = if (reverse) -1.0 else 1.0
        val z0 = if (reverse) G.rearAxleZ - G.rearOverhang else G.length / 2
        val rearZ = G.rearAxleZ
        val useLane = lane != null && !reverse && turn != 'L' && turn != 'R' && abs(k) < TURN_K
        val pts = ArrayList<FloatArray>()
        val n = 24
        for (i in 0..n) {
            val s = len * i / n                                  // 沿路徑走了多遠
            // 弧線（以後軸為中心轉）：走 s 公尺後，車頭位置
            val sa = s + (z0 - rearZ) * dir                      // 從後軸算起的弧長
            val (xk, zk) = if (abs(k) < 1e-4) Pair(0.0, rearZ + sa * dir) else {
                val r = 1.0 / k; val a = sa / r
                Pair(r * (1 - cos(a)), rearZ + r * sin(a) * dir)
            }
            var x = xk; val z = zk
            if (useLane) {
                val c = laneCenter(lane!!, z.toFloat())
                val w = smooth((s / (len * 0.6)).coerceIn(0.0, 1.0))   // 越遠越照車道中線
                x = xk * (1 - w) + c * w
            }
            pts.add(floatArrayOf(x.toFloat(), z.toFloat()))
        }
        return pts
    }

    private fun laneCenter(l: LaneRibbon.Lane, z: Float): Double {
        // 車道帶是每 1 m 一點：找最接近的那點（左右中間）
        var best = 0; var bd = Float.MAX_VALUE
        for (i in l.left.indices) { val d = abs(l.left[i][1] - z); if (d < bd) { bd = d; best = i } }
        if (z > l.left.last()[1]) {                              // 超過車道帶：照最後兩點的方向延伸
            val a = l.left.size - 2; val b = l.left.size - 1
            val ca = (l.left[a][0] + l.right[a][0]) / 2; val cb = (l.left[b][0] + l.right[b][0]) / 2
            val dz = l.left[b][1] - l.left[a][1]
            return (cb + (cb - ca) / dz * (z - l.left[b][1])).toDouble()
        }
        return ((l.left[best][0] + l.right[best][0]) / 2).toDouble()
    }

    private fun smooth(t: Double) = t * t * (3 - 2 * t)

    // ───────────── 避開障礙物 ─────────────

    /** 障礙物：位置 (x, z)、半寬 [r]、在動（同車道前方在動的 = 前車，跟著不繞）。 */
    class Obs(val x: Double, val z: Double, val r: Double, val moving: Boolean, val ped: Boolean = false)
    /** 結果：路徑點、是不是被擋住過不去（路徑停在障礙物前、畫成橘紅）。 */
    class Result(val pts: List<FloatArray>, val blocked: Boolean, val following: Boolean)

    const val EGO_HW = 0.95          // 自車半寬（含後照鏡一點點）
    const val MARGIN = 0.3           // 側向安全距離
    const val OVER_LINE = 0.4        // 繞的時候最多壓過車道線多少
    const val FREE_HW = 1.6          // 看不到車道線時，左右最多偏多少
    const val RAMP_IN = 6.0          // 偏過去用多長（m）
    const val RAMP_OUT = 4.0         // 繞過後回來用多長

    /**
     * 把 [path]（[predict] 的結果）照障礙物修改：停著的東西擋到（側向距離不到 自車半寬＋對方半寬＋[MARGIN]）→
     * 往空間小一點就能過的那側平順偏過去（不超出車道 [OVER_LINE]），過了再回來；同車道前方在動的車 → 跟著，路徑畫到它後面 2 m；
     * 空間不夠過不去 → 路徑停在障礙物前、[Result.blocked]。只用來顯示，不拿去控制車子。
     */
    fun avoid(path: List<FloatArray>, obs: List<Obs>, lane: LaneRibbon.Lane?): Result {
        val n = path.size
        if (n < 3 || obs.isEmpty()) return Result(path, false, false)
        // 弧長、法向（往右）
        val sArr = DoubleArray(n); val nx = DoubleArray(n); val nz = DoubleArray(n)
        for (i in 0 until n) {
            if (i > 0) sArr[i] = sArr[i - 1] + kotlin.math.hypot((path[i][0] - path[i - 1][0]).toDouble(), (path[i][1] - path[i - 1][1]).toDouble())
            val a = path[maxOf(0, i - 1)]; val b = path[minOf(n - 1, i + 1)]
            val tx = (b[0] - a[0]).toDouble(); val tz = (b[1] - a[1]).toDouble(); val l = kotlin.math.hypot(tx, tz).coerceAtLeast(1e-6)
            nx[i] = tz / l; nz[i] = -tx / l
        }
        val off = DoubleArray(n)
        var stopAt = Double.MAX_VALUE; var blocked = false; var following = false
        for (o in obs) {
            var bi = -1; var bd = Double.MAX_VALUE
            for (i in 0 until n) { val d = kotlin.math.hypot(o.x - path[i][0], o.z - path[i][1]); if (d < bd) { bd = d; bi = i } }
            if (bi <= 0 || bi >= n - 1 && bd > 3) continue                  // 在車頭後面、或遠在路徑盡頭外
            val d = (o.x - path[bi][0]) * nx[bi] + (o.z - path[bi][1]) * nz[bi]    // 在路徑右邊為正
            val need = EGO_HW + o.r + MARGIN
            if (kotlin.math.abs(d) >= need) continue
            val sStar = sArr[bi]
            if (o.moving && !o.ped) {                                         // 前車：跟著
                val st = sStar - o.r - 2.0
                if (st < stopAt) { stopAt = st; following = true; blocked = false }
                continue
            }
            // 兩個方向都試：偏到障礙物左邊（off = d − need，負）或右邊（off = d + need，正），挑幅度小又不出車道的
            val lim = limits(lane, path[bi])
            val cands = listOf(d - need, d + need).filter { it >= lim[0] && it <= lim[1] }.sortedBy { kotlin.math.abs(it) }
            if (cands.isEmpty()) {                                            // 過不去：停在它前面
                val st = sStar - o.r - 1.0
                if (st < stopAt) { stopAt = st; blocked = true; following = false }
                continue
            }
            val want = cands[0]
            for (i in 0 until n) {
                val ds = sArr[i] - sStar
                val rin = minOf(RAMP_IN, sStar - 0.5).coerceAtLeast(0.5)
                val w = when {
                    ds < -o.r - rin -> 0.0
                    ds < -o.r -> smooth((ds + o.r + rin) / rin)
                    ds <= o.r -> 1.0
                    ds < o.r + RAMP_OUT -> smooth(1 - (ds - o.r) / RAMP_OUT)
                    else -> 0.0
                }
                val v = want * w
                if (off[i] != 0.0 && kotlin.math.sign(off[i]) != kotlin.math.sign(v) && kotlin.math.abs(off[i]) > 0.2 && kotlin.math.abs(v) > 0.2) {
                    // 兩邊各有東西要往相反方向閃 → 中間過不去
                    val st = sStar - o.r - 1.0
                    if (st < stopAt) { stopAt = st; blocked = true; following = false }
                }
                if (kotlin.math.abs(v) > kotlin.math.abs(off[i])) off[i] = v
            }
        }
        val out = ArrayList<FloatArray>(n)
        for (i in 0 until n) {
            if (sArr[i] > stopAt) break
            out.add(floatArrayOf((path[i][0] + off[i] * nx[i]).toFloat(), (path[i][1] + off[i] * nz[i]).toFloat()))
        }
        if (out.size < 2) out.clear()
        return Result(out, blocked, following)
    }

    /** 在這一點，路徑最多往左（負）／右（正）偏多少：不讓車身超出車道線 [OVER_LINE]；看不到車道線 ±[FREE_HW]。 */
    private fun limits(lane: LaneRibbon.Lane?, p: FloatArray): DoubleArray {
        if (lane == null) return doubleArrayOf(-FREE_HW, FREE_HW)
        var best = 0; var bd = Float.MAX_VALUE
        for (i in lane.left.indices) { val d = kotlin.math.abs(lane.left[i][1] - p[1]); if (d < bd) { bd = d; best = i } }
        val xl = lane.left[best][0].toDouble(); val xr = lane.right[best][0].toDouble()
        val lo = (xl + EGO_HW - OVER_LINE) - p[0]; val hi = (xr - EGO_HW + OVER_LINE) - p[0]
        return doubleArrayOf(minOf(lo, 0.0), maxOf(hi, 0.0))
    }

    // ───────────── 智慧規劃（網格＋動態規劃） ─────────────

    /** 規劃用的障礙物：位置、對地速度（m/s，x 右 z 前）、半寬、半長、是不是人。 */
    class Agent(val x: Double, val z: Double, val vx: Double, val vz: Double, val hw: Double, val hl: Double, val ped: Boolean)
    /** 車道線：兩端點、白／黃、虛線。 */
    class Line(val x1: Double, val z1: Double, val x2: Double, val z2: Double, val kind: Char, val dashed: Boolean)

    const val DS = 1.5               // 站距（m）
    val OFFS = DoubleArray(17) { -2.0 + it * 0.25 }   // 候選橫向偏移 −2.0～+2.0 m

    /**
     * 網格規劃：沿 [base] 每 [DS] 公尺一站、每站 17 個橫向位置，動態規劃找總成本最低的一條。
     * 成本：碰撞（照每台車／人的速度推算「我到那裡時它在哪」，撞到 = 不行、太近加成本）、
     * 壓線（白虛線小、白實線大、黃實線很大、路緣不行）、偏離（越偏越貴）、急轉（橫向變化平方）、
     * 跟上一刻的選擇差多少（不會左右亂跳）。同車道前方在動的車 → 跟車，路徑畫到它後面；
     * 每條都過不去 → 停在擋住的地方前、[Result.blocked]。只用來顯示，不拿去控制車子。
     */
    class Planner {
        private var prev: DoubleArray? = null       // 上一刻每站的偏移

        fun plan(base: List<FloatArray>, agents: List<Agent>, lines: List<Line>, edgeL: Double?, edgeR: Double?, egoMps: Double): Result {
            if (base.size < 3) return Result(base, false, false)
            // 沿 base 等距取站（弧長）
            val cum = DoubleArray(base.size)
            for (i in 1 until base.size) cum[i] = cum[i - 1] + kotlin.math.hypot((base[i][0] - base[i - 1][0]).toDouble(), (base[i][1] - base[i - 1][1]).toDouble())
            val total = cum.last()
            val n = (total / DS).toInt() + 1
            if (n < 3) return Result(base, false, false)
            val px = DoubleArray(n); val pz = DoubleArray(n); val nx = DoubleArray(n); val nz = DoubleArray(n)
            var k = 0
            for (i in 0 until n) {
                val s = i * DS
                while (k < base.size - 2 && cum[k + 1] < s) k++
                val f = ((s - cum[k]) / (cum[k + 1] - cum[k]).coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
                px[i] = base[k][0] + (base[k + 1][0] - base[k][0]) * f; pz[i] = base[k][1] + (base[k + 1][1] - base[k][1]) * f
                val tx = (base[k + 1][0] - base[k][0]).toDouble(); val tz = (base[k + 1][1] - base[k][1]).toDouble(); val l = kotlin.math.hypot(tx, tz).coerceAtLeast(1e-6)
                nx[i] = tz / l; nz[i] = -tx / l
            }
            val L = OFFS.size; val INF = 1e18
            // 跟車：同車道前方在動的車（對地 > 1.5 m/s、跟 base 橫向 < 1.2 m、在前方）→ 規劃只到它後面 2 m
            var horizon = n
            var following = false
            for (a in agents) {
                if (a.ped || kotlin.math.hypot(a.vx, a.vz) < 1.5 || a.z <= 0) continue
                var bi = 0; var bd = Double.MAX_VALUE
                for (i in 0 until n) { val d = kotlin.math.hypot(a.x - px[i], a.z - pz[i]); if (d < bd) { bd = d; bi = i } }
                val lat = (a.x - px[bi]) * nx[bi] + (a.z - pz[bi]) * nz[bi]
                if (kotlin.math.abs(lat) < 1.2 && a.vz > 0) {
                    val h = ((bi * DS - a.hl - 2.0) / DS).toInt().coerceAtLeast(1)
                    if (h < horizon) { horizon = h; following = true }
                }
            }
            val v = maxOf(egoMps, 2.0)
            // 每站每個偏移的「節點成本」
            val node = Array(horizon) { DoubleArray(L) }
            for (i in 0 until horizon) {
                val t = i * DS / v
                for (j in 0 until L) {
                    val x = px[i] + OFFS[j] * nx[i]; val z = pz[i] + OFFS[j] * nz[i]
                    var c = 0.4 * kotlin.math.abs(OFFS[j])                                      // 偏離
                    // 碰撞（前方才算；自車附近的東西交給警示）
                    for (a in agents) {
                        val ax = a.x + a.vx * t; val az = a.z + a.vz * t
                        if (az < 0.5 && a.z < 0.5) continue
                        val dLong = kotlin.math.abs(az - z) - (a.hl + 2.4)
                        val dLat = kotlin.math.abs(ax - x) - (a.hw + EGO_HW)
                        if (dLong < 0 && dLat < 0) { c = INF; break }
                        if (dLong < 1.0 && dLat < 2 * MARGIN) c += (2 * MARGIN - dLat) * 30
                    }
                    if (c >= INF) { node[i][j] = INF; continue }
                    // 路緣
                    if (edgeL != null && x - EGO_HW < edgeL + 0.2) { node[i][j] = INF; continue }
                    if (edgeR != null && x + EGO_HW > edgeR - 0.2) { node[i][j] = INF; continue }
                    // 壓線：車身左右緣之間有線
                    for (ln in lines) {
                        val dz = ln.z2 - ln.z1; if (kotlin.math.abs(dz) < 0.5) continue
                        val lx = ln.x1 + (ln.x2 - ln.x1) * (z - ln.z1) / dz
                        if (z < minOf(ln.z1, ln.z2) - 20 || z > maxOf(ln.z1, ln.z2) + 20) continue
                        if (lx > x - EGO_HW && lx < x + EGO_HW) c += when {
                            ln.kind == 'y' && !ln.dashed -> 60.0
                            ln.kind == 'y' -> 20.0
                            !ln.dashed -> 10.0
                            else -> 2.0
                        }
                    }
                    prev?.let { pv -> if (i < pv.size) c += 0.6 * kotlin.math.abs(OFFS[j] - pv[i]) }
                    node[i][j] = c
                }
            }
            // 動態規劃：第 0 站固定在 0 偏移（車子現在的位置）
            val cost = Array(horizon) { DoubleArray(L) { INF } }; val from = Array(horizon) { IntArray(L) { -1 } }
            val j0 = L / 2
            cost[0][j0] = 0.0
            for (i in 1 until horizon) for (j in 0 until L) {
                if (node[i][j] >= INF) continue
                for (q in maxOf(0, j - 3)..minOf(L - 1, j + 3)) {           // 每站最多橫移 0.75 m
                    if (cost[i - 1][q] >= INF) continue
                    val dd = OFFS[j] - OFFS[q]
                    val c = cost[i - 1][q] + node[i][j] + 8.0 * dd * dd
                    if (c < cost[i][j]) { cost[i][j] = c; from[i][j] = q }
                }
            }
            // 能走到最遠的那站
            var last = horizon - 1
            while (last > 0 && cost[last].all { it >= INF }) last--
            val blocked = last < horizon - 1
            var bj = (0 until L).minByOrNull { cost[last][it] } ?: j0
            val offs = DoubleArray(last + 1)
            for (i in last downTo 0) { offs[i] = OFFS[bj]; if (i > 0) bj = from[i][bj].coerceAtLeast(0) }
            prev = offs
            // 被擋：停在擋住的地方前 1 站
            val keep = if (blocked) maxOf(1, last - 1) else last
            val out = ArrayList<FloatArray>(keep + 1)
            for (i in 0..keep) out.add(floatArrayOf((px[i] + offs[i] * nx[i]).toFloat(), (pz[i] + offs[i] * nz[i]).toFloat()))
            return Result(out, blocked && !following, following)
        }
    }
}
