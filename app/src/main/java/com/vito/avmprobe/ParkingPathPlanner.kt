package com.vito.avmprobe

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * 倒車入庫路徑（車身座標，公尺；x 右、z 前。θ 從 +z 往 +x 量，+ = 車頭偏右）。
 * points = 後軸中心的軌跡（每點 x, z），約每 0.25 m 一點，第一點 = 現在的後軸位置 (0, rearAxleZ)；
 * 索引 0 到 reverseFrom 是前進段、reverseFrom 到最後是倒車段（reverseFrom 那一點是換檔點，兩段共用，
 * 不用先往前開時 = 0）。turnRadius = 倒車弧用的後軸迴轉半徑（直接倒進去 = +∞）。
 * goal = 路徑終點的後軸姿態（x, z, θ）。ok = false 時 points 是空的，reason 說明為什麼停不進去。
 */
data class ParkPlan(
    val ok: Boolean,
    val reason: String?,
    val points: List<FloatArray>,
    val reverseFrom: Int,
    val turnRadius: Float,
    val goal: FloatArray
)

/**
 * 倒車入庫的路徑規劃：自車在原點、車頭朝 +z（θ = 0），把車倒進停車格並與格子軸線對齊、左右置中。
 *
 * 路徑三段（後軸中心）：前進直線 s0 → 倒車圓弧（半徑 R、轉角 = 目標朝向 θg）→ 倒車直線 s2。
 * 倒完弧航向就是 θg，所以航向約束只決定弧的轉角、不決定 R；剩下終點 x、z 兩條約束對 s0、R、s2 三個未知數，
 * 留下一個自由度：s0(R) = c0 + R·tan(|θg|/2) 遞增、s2(R) = m − R·tan(|θg|/2) 遞減，
 * 所以 R 越小越好進（前進距離最短、最後直線段最長），可行區間 = [max(Rmin, s0≥0 的下限), s2≥0 的上限]。
 * 取 R = 1.1 × Rmin（方向盤不打到底，留一點修正空間），超出區間就夾回區間端點。
 *
 * 只做 'p'（垂直）與 'a'（斜向）：'l'（平行／路邊）要兩段反向的弧（S 形）才進得去，這裡的三段式做不到，
 * 寧可回 null 也不給一條做不到的路徑。回 null = 格子資料不合理或型別不支援；
 * ok = false = 幾何算得出來但停不進去（reason 是給使用者看的）。
 *
 * 只有幾何：不看雷達、不看旁邊的車，也不檢查車身掃過的範圍會不會擦到格線／鄰車。
 */
object ParkingPathPlanner {

    const val DEEP_GAP = 0.3            // 後保險桿離深處邊留的餘裕（m）
    const val SIDE_GAP = 0.2            // 格寬至少要比車寬多這麼多（m）
    const val MIN_INSIDE = 1.0          // 後軸至少要進到入口邊內這麼深，否則等於沒停進去（m）
    const val STEP = 0.25               // 路徑取樣間距（m）
    const val R_MARGIN = 1.1            // 迴轉半徑比 Rmin 多留一成
    const val MAX_TURN_DEG = 120.0      // 一次倒車最多轉這麼多

    private const val TH_STRAIGHT = 0.3 * PI / 180.0   // 比這更正的格子當成在正後方
    private const val X_SNAP = 0.03                    // 正後方格子容許的橫向誤差（m）
    private const val ALIGN_DEG = 15.0                 // 失敗診斷：幾乎對正卻解不出來
    private const val EPS = 1e-6

    /**
     * @param slot 停車格（p 的四點、kind 見 [ParkingSlot]）
     * @return 路徑；null = 不支援的格子種類或格子資料不合理（四點退化、NaN、順序不對）
     */
    fun plan(slot: ParkingSlot, geo: VehicleGeometry = VehicleGeometry()): ParkPlan? {
        val p = slot.p
        if (p.size < 8) return null
        for (v in p) if (!v.isFinite()) return null
        if (slot.kind != 'p' && slot.kind != 'a') return null
        val x1 = p[0].toDouble(); val z1 = p[1].toDouble()
        val x2 = p[2].toDouble(); val z2 = p[3].toDouble()
        val x3 = p[4].toDouble(); val z3 = p[5].toDouble()
        val x4 = p[6].toDouble(); val z4 = p[7].toDouble()
        val ex = x2 - x1; val ez = z2 - z1                      // 入口邊 p1→p2
        val wSlot = hypot(ex, ez)
        val emx = 0.5 * (x1 + x2); val emz = 0.5 * (z1 + z2)    // 入口邊中點 E
        val fmx = 0.5 * (x3 + x4); val fmz = 0.5 * (z3 + z4)    // 深處邊中點 F
        val depth = hypot(fmx - emx, fmz - emz)
        if (wSlot < 0.5 || depth < 0.5) return null
        val ax = (fmx - emx) / depth; val az = (fmz - emz) / depth
        // 四點逆時針：格內在 p1→p2 的左手邊。反了代表 p1/p2（或 p3/p4）對調，入口方向不可信 → 不給路徑
        if (ex * az - ez * ax <= 0.0) return null
        if ((x3 - x4) * ex + (z3 - z4) * ez <= 0.0) return null

        val zra = geo.rearAxleZ
        val back = DEEP_GAP + geo.rearOverhang                  // 後軸離深處邊
        val thg = atan2(-ax, -az)                               // 目標朝向：車頭朝格外
        val gx = fmx - back * ax
        val gz = fmz - back * az
        val goal = floatArrayOf(gx.toFloat(), gz.toFloat(), thg.toFloat())
        if (slot.occupied) return bad("停車格裡有車", goal)
        if (wSlot < geo.width + SIDE_GAP)
            return bad("格寬 ${f2(wSlot)} m 不夠（車寬 ${f2(geo.width)} m，至少要 ${f2(geo.width + SIDE_GAP)} m）", goal)
        if (depth - back < MIN_INSIDE)
            return bad("格深 ${f2(depth)} m 太淺，倒到底後軸還在入口附近", goal)
        if (abs(thg) > MAX_TURN_DEG * PI / 180.0)
            return bad("格子軸線與車身夾 ${f0(abs(thg) * 180.0 / PI)}°，一次倒車轉不過來（要先調頭）", goal)

        val rMin = abs(BicycleModel.turningRadius(geo.maxSteerDeg, geo))
        // 幾乎在正後方：轉角太小，單弧補不了橫向偏差 → 當成正後方直接倒（goal 跟著修到路徑真正的終點）
        if (abs(thg) < TH_STRAIGHT) {
            if (abs(gx) > X_SNAP)
                return bad("格子在正後方但橫向差 ${f2(abs(gx))} m，單弧倒車補不回來，請先把車對正", goal)
            val s2 = zra - gz
            if (s2 < 0.0) return bad("格子在車身底下，倒不進去", goal)
            return ParkPlan(true, null, straight(zra, s2), 0, Float.POSITIVE_INFINITY,
                floatArrayOf(0f, gz.toFloat(), 0f))
        }

        val sg = if (thg > 0) 1.0 else -1.0                     // + = 格子在左（倒車時往左彎）
        val sTh = abs(sin(thg)); val cTh = cos(thg)
        val k1 = (1 - cTh) / sTh                                // tan(|θg|/2)
        val m = -sg * gx / sTh                                  // s2 = m − R·k1
        val c0 = gz - zra - sg * gx * cTh / sTh                 // s0 = c0 + R·k1
        val rHi = m / k1                                        // s2 ≥ 0 的上限
        val rLo = max(rMin, -c0 / k1)                           // s0 ≥ 0、且不小於最小迴轉半徑
        if (rHi < rLo - EPS) return bad(why(thg, gx, sg, sTh, k1, c0, rMin, rHi), goal)
        val r = (R_MARGIN * rMin).coerceIn(rLo, max(rLo, rHi))
        val s0 = max(0.0, c0 + r * k1)
        val s2 = max(0.0, m - r * k1)

        val pts = ArrayList<FloatArray>()
        pts.add(floatArrayOf(0f, zra.toFloat()))
        if (s0 > EPS) {
            val n = max(1, ceil(s0 / STEP - EPS).toInt())
            for (i in 1..n) pts.add(floatArrayOf(0f, (zra + s0 * i / n).toFloat()))
        }
        val rev = pts.size - 1
        val az0 = zra + s0                                      // 弧起點（後軸），航向 0
        val k = -sg / r                                         // 曲率（+ = 向右彎）：倒車 Δθ = k·(−弧長) = θg
        val arc = r * abs(thg)
        val n1 = max(1, ceil(arc / STEP - EPS).toInt())
        val q = DoubleArray(4)
        for (i in 1..n1) {
            BicycleModel.pose(arc * i / n1, k, -1.0, 0.0, q)
            pts.add(floatArrayOf(q[0].toFloat(), (az0 + q[1]).toFloat()))
        }
        if (s2 > EPS) {
            val bx = q[0]; val bz = az0 + q[1]
            val ux = -sin(thg); val uz = -cos(thg)              // 倒車方向
            val n2 = max(1, ceil(s2 / STEP - EPS).toInt())
            for (i in 1..n2) pts.add(floatArrayOf((bx + ux * s2 * i / n2).toFloat(), (bz + uz * s2 * i / n2).toFloat()))
        }
        return ParkPlan(true, null, pts, rev, r.toFloat(), goal)
    }

    /** 解不出來時的原因與建議。 */
    private fun why(
        thg: Double, gx: Double, sg: Double, sTh: Double, k1: Double,
        c0: Double, rMin: Double, rHi: Double
    ): String = when {
        abs(thg) < ALIGN_DEG * PI / 180.0 ->
            "格子幾乎在正後方（軸向差 ${f0(abs(thg) * 180.0 / PI)}°、橫向差 ${f2(abs(gx))} m），" +
                "單弧路徑對不準，請先把車對正"
        rHi < rMin ->
            "車離停車格太近（橫向還差 ${f1((rMin - rHi) * k1 * sTh)} m），先往${if (sg < 0) "左" else "右"}移開再倒"
        else ->
            "車已經開過停車格約 ${f1(-(c0 + rHi * k1))} m，請先往後退一點再倒"
    }

    /** 正後方的格子：直接往後倒 s2。 */
    private fun straight(zra: Double, s2: Double): List<FloatArray> {
        if (s2 <= EPS) return listOf(floatArrayOf(0f, zra.toFloat()))
        val n = max(1, ceil(s2 / STEP - EPS).toInt())
        return ArrayList<FloatArray>(n + 1).apply {
            for (i in 0..n) add(floatArrayOf(0f, (zra - s2 * i / n).toFloat()))
        }
    }

    private fun bad(reason: String, goal: FloatArray) = ParkPlan(false, reason, emptyList(), 0, 0f, goal)

    private fun f0(v: Double) = String.format(Locale.US, "%.0f", v)
    private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun f2(v: Double) = String.format(Locale.US, "%.2f", v)
}
