package com.vito.avmprobe

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * 打雙閃、車還在慢慢開 → 在右側路邊找能停的空隙（路邊平行停車），找到後引導停進去。
 *
 * 做法：用車速累積行駛距離 s（倒車檔時往回減），把右側路邊看到的停放車輛（四輪、機車）、紅線（禁止臨時停車）
 * 記成「沿路邊的佔用區間」，同時記下右側實際看過的範圍；在看過、沒被佔用的範圍裡找長度 ≥ [NEED_M] 的空隙。
 * 座標：自車中心為原點，x 右、z 前（跟其他偵測一樣）；路邊的區間用 s + z 存，車子動了位置不變。
 *
 * 限制：只找右側（台灣靠右停）；假設車身跟路邊平行；沒看到的車（被擋住、太遠）會讓空隙看起來比實際大。
 * 純邏輯，可單元測試。
 */
class RoadsideSpaceFinder(private val geo: VehicleGeometry = VehicleGeometry()) {

    companion object {
        /** 要多長的空隙：車長 + 前後各約 0.6 m 進出空間。 */
        val NEED_M get() = VehicleGeometry().length + 1.2
        const val SEE_BACK = 5.0          // 右側看得清楚的範圍（相對自車中心）
        const val SEE_FRONT = 6.0
        const val SIDE_X_MIN = 1.3        // 停在路邊的東西：在自車右側這個範圍
        const val SIDE_X_MAX = 4.5
        const val MAX_KMH = 25f           // 雙閃 + 慢速才找
        const val KEEP_MS = 90_000L       // 找到之後（就算關掉雙閃、停車、倒車）保留這麼久
        const val DEFAULT_X = 2.6         // 沒有路緣、旁邊也沒停車時，空位中心的橫向位置
    }

    data class Obj(val t: String, val x: Float, val z: Float)

    /** 找到的路邊空位（自車座標）。 */
    data class Spot(val x: Float, val z0: Float, val z1: Float) {
        val len get() = z1 - z0
        fun corners(): FloatArray = floatArrayOf(x - 1.05f, z0, x + 1.05f, z0, x + 1.05f, z1, x - 1.05f, z1)
    }

    private var s = 0.0                    // 累積行駛距離（前進為正）
    private var lastAt = 0L
    private val occ = ArrayList<DoubleArray>()       // 佔用區間 [s0, s1]
    private var seen0 = Double.NaN; private var seen1 = Double.NaN
    private var laneX = Double.NaN                  // 路邊停車那一排的橫向位置（EMA）
    private var found: DoubleArray? = null           // [s0, s1, x]
    /** 空隙前後那兩台停著的車（s 座標，沒有是 NaN）：每幀看到它們就把位置對回去，不只靠車速累積。 */
    private var anchorRear = Double.NaN
    private var anchorFront = Double.NaN
    private var foundAt = 0L
    private var hazardAt = 0L

    fun reset() { occ.clear(); seen0 = Double.NaN; seen1 = Double.NaN; laneX = Double.NaN; found = null; anchorRear = Double.NaN; anchorFront = Double.NaN }

    /**
     * 用看到的路邊停車校正位置（使用者：移動中車位位置要即時更新）：車速是 GPS 的，延遲又不準，
     * 光靠累積行駛距離，空位會慢慢跑掉。看到空隙前後那台車 → 量到的位置跟記下的差多少，就把行駛距離修回來。
     */
    private fun anchor(objs: List<Obj>) {
        if (found == null) return
        for (o in objs) {
            if (ObjTypes.group(o.t) != "veh" || o.x < SIDE_X_MIN || o.x > SIDE_X_MAX) continue
            val sm = s + o.z
            for (a in doubleArrayOf(anchorRear, anchorFront)) {
                if (a.isNaN()) continue
                val err = sm - a
                if (abs(err) < 2.0) s -= 0.5 * err          // 量到的車在 a + err → 我們的 s 多算了 err
            }
        }
    }

    /**
     * 每一幀呼叫。[curbX] 右側路緣（沒有傳 null），[redRight] 右側路邊是否是紅線（禁止停車）。
     * 回傳目前的路邊空位（自車座標），沒有回 null。
     */
    fun update(now: Long, speedKmh: Float?, turn: Char?, gear: Char?, objs: List<Obj>, curbX: Float?, redRight: Boolean): Spot? {
        val dt = if (lastAt == 0L) 0.0 else ((now - lastAt).coerceIn(0, 500)) / 1000.0
        lastAt = now
        val v = (speedKmh ?: 0f) / 3.6
        // 行駛距離：看得到路邊停著的車時，直接用它們往後退了多少（停著的車不會動，比 GPS 車速準）；
        // 看不到才用車速
        val ds = parkedMotion(objs)
        s += ds ?: ((if (gear == 'R') -v else v) * dt)
        val hazard = turn == 'H'
        if (hazard) hazardAt = now
        anchor(objs)
        val f = found
        // 太久沒打雙閃、也沒在用找到的空位 → 清掉
        if (!hazard && (f == null || now - foundAt > KEEP_MS)) { if (now - hazardAt > 5000) reset(); return f?.let { toSpot(it) } }
        // 已經找到了：停車、倒車中只更新位置，不再找新的
        if (f != null && (!hazard || (speedKmh ?: 0f) < 2f || gear == 'R')) return toSpot(f)
        if (!hazard || speedKmh == null || speedKmh > MAX_KMH || gear == 'R') return f?.let { toSpot(it) }

        // 右側看過的範圍
        val a = s - SEE_BACK; val b = s + SEE_FRONT
        if (seen0.isNaN() || a > seen1 + 1.0) { seen0 = a; seen1 = b } else { seen0 = minOf(seen0, a); seen1 = maxOf(seen1, b) }
        // 停放的車／機車 → 佔用
        for (o in objs) {
            val g = ObjTypes.group(o.t)
            if (g != "veh" && g != "two") continue
            if (o.x < SIDE_X_MIN || o.x > SIDE_X_MAX || o.z < -SEE_BACK || o.z > SEE_FRONT) continue
            val hl = if (g == "veh") 2.4 else 1.0
            add(s + o.z - hl, s + o.z + hl)
            laneX = if (laneX.isNaN()) o.x.toDouble() else laneX + 0.2 * (o.x - laneX)
        }
        // 紅線：自車旁邊這一段不能停
        if (redRight) add(s - geo.length / 2, s + geo.length / 2)
        // 找空隙：看過的範圍扣掉佔用，取最近一個夠長、而且已經在自車旁邊或後面（開過去了，可以倒車進去）的
        val need = NEED_M
        val free = freeIntervals()
        val gap = free.filter { it[1] - it[0] >= need && it[1] <= s + geo.length / 2 + 1.0 }.maxByOrNull { it[1] }
        if (gap != null) {
            val x = curbX?.let { (it - 1.15).toDouble() } ?: laneX.takeIf { !it.isNaN() } ?: DEFAULT_X
            // 空隙太長就只取靠前那一段（停在前車後面）
            val s1 = gap[1]; val s0 = maxOf(gap[0], s1 - need - 1.0)
            found = doubleArrayOf(s0, s1, x); foundAt = now
            // 空隙前後的車（佔用區間的端點往外半個車長）
            anchorRear = occ.lastOrNull { it[1] <= gap[0] + 0.3 }?.let { (it[0] + it[1]) / 2 } ?: Double.NaN
            anchorFront = occ.firstOrNull { it[0] >= gap[1] - 0.3 }?.let { (it[0] + it[1]) / 2 } ?: Double.NaN
        }
        return found?.let { toSpot(it) }
    }

    private var prevSide: List<Obj> = emptyList()

    /** 右側停放物這一幀往後退了多少（中位數，m）；配不到回 null。 */
    private fun parkedMotion(objs: List<Obj>): Double? {
        val side = objs.filter { (ObjTypes.group(it.t) == "veh" || ObjTypes.group(it.t) == "two") && it.x in SIDE_X_MIN..SIDE_X_MAX }
        val d = ArrayList<Double>()
        for (o in side) {
            val p = prevSide.filter { abs(it.x - o.x) < 0.7f && it.z - o.z in -0.6f..3.0f }.minByOrNull { abs(it.z - o.z) } ?: continue
            d.add((p.z - o.z).toDouble())
        }
        prevSide = side
        if (d.isEmpty()) return null
        d.sort()
        return d[d.size / 2]
    }

    private fun toSpot(f: DoubleArray) = Spot(f[2].toFloat(), (f[0] - s).toFloat(), (f[1] - s).toFloat())

    private fun add(a: Double, b: Double) {
        occ.add(doubleArrayOf(a, b))
        occ.sortBy { it[0] }
        val merged = ArrayList<DoubleArray>()
        for (iv in occ) { val l = merged.lastOrNull(); if (l != null && iv[0] <= l[1]) l[1] = maxOf(l[1], iv[1]) else merged.add(iv.copyOf()) }
        occ.clear(); occ.addAll(merged)
        while (occ.size > 64) occ.removeAt(0)
    }

    private fun freeIntervals(): List<DoubleArray> {
        if (seen0.isNaN()) return emptyList()
        val out = ArrayList<DoubleArray>()
        var cur = seen0
        for (iv in occ) {
            if (iv[1] <= cur) continue
            if (iv[0] > seen1) break
            if (iv[0] > cur) out.add(doubleArrayOf(cur, minOf(iv[0], seen1)))
            cur = maxOf(cur, iv[1])
        }
        if (cur < seen1) out.add(doubleArrayOf(cur, seen1))
        return out
    }

    // ───────────────────────── 路邊平行停車路徑 ─────────────────────────

    data class Plan(val points: List<FloatArray>, val reverseFrom: Int, val stopAheadM: Float, val radius: Float, val goal: FloatArray)

    /**
     * 經典兩段弧倒車入位：先往前開到起點（後軸在空位後端往前 L 處），倒車時方向盤往右打、再往左打，
     * 兩段等半徑 R 的反向弧把車橫移到空位中心。橫移 d = 2R(1 − cos θ)，縱向 L = 2R sin θ。
     * 點是後軸中心的軌跡（自車座標），[reverseFrom] 之後是倒車段。
     */
    fun plan(spot: Spot): Plan? {
        val rmin = geo.wheelbase / tan(Math.toRadians(geo.maxWheelDeg))
        val r = rmin * 1.1
        val d = spot.x.toDouble()                   // 要橫移的量（右為正）
        if (d <= 0.3 || d > 4.0 * r) return null
        val th = acos(1.0 - d / (2 * r))
        val l = 2 * r * sin(th)
        // 停好時後軸位置：後保險桿離空位後端 0.4 m
        val goalZ = spot.z0 + 0.4 + geo.rearOverhang
        val startZ = goalZ + l
        val axle0 = geo.rearAxleZ
        val pts = ArrayList<FloatArray>()
        // 1. 直線前進（或後退）到起點
        val n0 = maxOf(1, (abs(startZ - axle0) / 0.5).toInt())
        for (i in 0..n0) pts.add(floatArrayOf(0f, (axle0 + (startZ - axle0) * i / n0).toFloat()))
        val rev = pts.size - 1
        // 2. 倒車第一段：方向盤往右（右轉圓心在右邊 (r, startZ)），後軸往右後方
        val n = 12
        for (i in 1..n) {
            val t = th * i / n
            pts.add(floatArrayOf((r * (1 - cos(t))).toFloat(), (startZ - r * sin(t)).toFloat()))
        }
        // 3. 倒車第二段：方向盤往左，回正到跟路邊平行
        val mx = r * (1 - cos(th)); val mz = startZ - r * sin(th)
        for (i in 1..n) {
            val t = th * (1 - i.toDouble() / n)
            pts.add(floatArrayOf((mx + r * (cos(t) - cos(th))).toFloat(), (mz - r * (sin(th) - sin(t))).toFloat()))
        }
        return Plan(pts, rev, (startZ - axle0).toFloat(), r.toFloat(), floatArrayOf(d.toFloat(), goalZ.toFloat(), 0f))
    }
}
