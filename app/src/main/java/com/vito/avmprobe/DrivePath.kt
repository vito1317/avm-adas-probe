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
}
