package com.vito.avmprobe

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tan

/**
 * 路口轉彎的預判路徑：打了方向燈、方向盤也開始轉的時候，畫出車子接下來會掃過哪裡。
 *
 * 跟另外兩個不一樣的東西：
 * - `vehicle3d.js` 的方向盤軌跡是「**現在**輪子指向哪」，只畫 5 m，停車對位用的
 * - [LaneChangeAssist] 的 path 是**變道**用的，而且它偵測到在轉彎時會刻意不給路徑
 *   （`intent != null && !turning`）—— 路口轉彎本來就不該用變道那條線
 *
 * 這裡畫的是 [LEN_M] 公尺的轉彎走廊，而且**外側車頭角**和**內側後輪**分開畫 ——
 * 兩條之間的差距就是內輪差。台灣路口轉彎最常出事的就是這個：
 * 後輪比前輪走內側，捲進去的往往是旁邊的機車。
 *
 * ### 什麼時候才畫
 * 要**方向燈**和**方向盤**同時成立：
 * - 方向燈打了（[Signal] 方向要跟轉彎方向一致）
 * - 方向盤已經轉過 [MIN_STEER_DEG]
 * - 車速低於 [MAX_SPEED_KMH]（高速打燈是變道，不是路口轉彎）
 *
 * **打了燈但方向盤還沒轉就不畫。** 那時候「轉彎路徑」要畫在哪只能用猜的 ——
 * 路口在幾公尺外、要用多大的半徑，都不知道。猜一條畫得煞有其事比不畫更糟。
 * 方向盤一轉就會出現，而那時候的弧線是從實際轉角算出來的，不是猜的。
 */
object TurnPathPlanner {

    const val MIN_STEER_DEG = 30f      // 方向盤轉過這個角度才算真的在轉
    const val MAX_SPEED_KMH = 40f      // 高於此速度打燈是變道
    const val LEN_M = 16.0             // 路徑長度（沿著弧）
    const val STEP_M = 0.6             // 取樣間距
    const val MAX_SWEEP_DEG = 150.0    // 最多畫到轉這麼多度（免得繞成一圈）
    const val CLEAR_M = 0.25           // 走廊外再留這麼多才算安全

    /**
     * @param pts 外側車頭角掃過的路徑（車身座標公尺）
     * @param inner 內側後輪掃過的路徑 —— 兩條之間就是內輪差
     * @param dir 'L' 或 'R'
     * @param radius 後軸中心的轉彎半徑（m）
     * @param innerFront 內側車頭角的路徑。畫掃過範圍時兩端要沿車身收口
     *   （內側後輪 → 內側車頭角 → 外側車頭角），不然直接連會斜切過車身變成三角形
     */
    data class TurnPath(
        val pts: List<FloatArray>,
        val inner: List<FloatArray>,
        val dir: Char,
        val radius: Float,
        val innerFront: List<FloatArray> = emptyList()
    ) {
        /**
         * 車身掃過的範圍（閉合多邊形）：外側車頭角往前 → 終點車身前緣 → 內側後輪往回 → 起點車身內側。
         * 兩端都沿著車身輪廓收口。
         */
        fun sweptPolygon(): List<FloatArray> {
            if (pts.size < 2 || inner.size < 2) return emptyList()
            val out = ArrayList<FloatArray>(pts.size + inner.size + 2)
            out.addAll(pts)
            innerFront.lastOrNull()?.let { out.add(it) }
            out.addAll(inner.asReversed())
            innerFront.firstOrNull()?.let { out.add(it) }
            return out
        }
    }

    /**
     * @param turn 方向燈 'L'/'R'/'H'，沒打傳 null
     * @param steerDeg 方向盤角度（+ = 右），拿不到傳 null
     */
    fun plan(
        turn: Char?,
        steerDeg: Float?,
        speedKmh: Float?,
        geo: VehicleGeometry = VehicleGeometry()
    ): TurnPath? {
        if (turn != 'L' && turn != 'R') return null
        val st = steerDeg ?: return null                       // 沒有方向盤角度就畫不出來
        if (abs(st) < MIN_STEER_DEG) return null                // 還沒開始轉：不猜
        if (speedKmh != null && speedKmh > MAX_SPEED_KMH) return null
        // 方向盤轉的方向要跟方向燈一致，不然那是在閃避或回正，不是這個燈要的轉彎
        if ((st > 0f && turn != 'R') || (st < 0f && turn != 'L')) return null

        val wheel = (st / geo.steerRatio).toDouble()
            .coerceIn(-geo.maxWheelDeg, geo.maxWheelDeg)
        val k = tan(wheel * Math.PI / 180.0) / geo.wheelbase    // 後軸中心的曲率
        if (abs(k) < 1e-6) return null
        val r = 1.0 / abs(k)
        val sgn = sign(k)                                       // +1 = 向右

        // 幾何：後軸中心起點 (0, zra)、朝 +z，轉彎中心在它的正側邊 r 處。
        // 走過弧角 t 之後（θ = sgn·t 是車身轉過的角度）：
        //   後軸 x = sgn·r·(1 − cos t)、z = zra + r·sin t，切線方向 = (sin θ, cos θ)
        val zra = geo.rearAxleZ
        val hw = geo.width / 2
        val frontZ = geo.length / 2 - zra            // 車頭離後軸多遠

        val sweepMax = Math.toRadians(MAX_SWEEP_DEG)
        val total = minOf(LEN_M / r, sweepMax)       // 要走的弧角
        val n = maxOf(2, Math.ceil(r * total / STEP_M).toInt())
        val outer = ArrayList<FloatArray>(n + 1)
        val innerPts = ArrayList<FloatArray>(n + 1)
        val innerFront = ArrayList<FloatArray>(n + 1)
        for (i in 0..n) {
            val t = total * i / n
            val yaw = sgn * t
            val bx = sgn * r * (1.0 - cos(t))
            val bz = zra + r * sin(t)
            // 轉彎中心在後軸的「轉向那一側」（右轉在右邊）：離中心最遠的是**另一側**的車頭角，
            // 最近的是轉向那一側的後輪。右轉 → 外側 = 左前角、內側 = 右後輪。
            outer.add(body(bx, bz, yaw, -sgn * hw, frontZ))      // 外側車頭角
            innerPts.add(body(bx, bz, yaw, sgn * hw, 0.0))       // 內側後輪（就在後軸上）
            innerFront.add(body(bx, bz, yaw, sgn * hw, frontZ))  // 內側車頭角（收口用）
        }
        return TurnPath(outer, innerPts, if (sgn > 0) 'R' else 'L', r.toFloat(), innerFront)
    }

    /**
     * 車上的點 (ox 右、oz 從後軸往前) 在「後軸位於 (bx, bz)、車身轉過 yaw」時的位置。
     * 車身朝向 = (sin yaw, cos yaw)，右方 = (cos yaw, −sin yaw)。
     */
    private fun body(bx: Double, bz: Double, yaw: Double, ox: Double, oz: Double): FloatArray {
        val c = cos(yaw)
        val s = sin(yaw)
        return floatArrayOf((bx + ox * c + oz * s).toFloat(), (bz - ox * s + oz * c).toFloat())
    }

    /**
     * 有沒有東西落在轉彎走廊裡（內輪差捲入）。
     * 走廊 = 外側路徑與內側路徑之間，再往外放 [CLEAR_M]。
     * 回傳被掃到的那些物件（依距離近到遠）。
     */
    fun conflicts(path: TurnPath, objs: List<StartAssist.Obj>): List<StartAssist.Obj> {
        if (path.pts.isEmpty()) return emptyList()
        val hit = ArrayList<StartAssist.Obj>()
        for (o in objs) {
            var near = false
            for (i in path.pts.indices) {
                val a = path.pts[i]
                val b = path.inner.getOrNull(i) ?: continue
                // 點到「外側點—內側點」這條橫向線段的距離
                if (segDist(o.x, o.z, a[0], a[1], b[0], b[1]) <= CLEAR_M) { near = true; break }
            }
            if (near) hit.add(o)
        }
        return hit.sortedBy { hypot(it.x, it.z) }
    }

    private fun segDist(px: Float, pz: Float, ax: Float, az: Float, bx: Float, bz: Float): Float {
        val vx = bx - ax
        val vz = bz - az
        val len2 = vx * vx + vz * vz
        val t = if (len2 < 1e-9f) 0f else (((px - ax) * vx + (pz - az) * vz) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + vx * t), pz - (az + vz * t))
    }
}
