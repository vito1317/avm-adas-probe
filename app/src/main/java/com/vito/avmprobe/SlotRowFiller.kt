package com.vito.avmprobe

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 有車的停車格：分隔線大多被車身擋住，偵測常湊不出格子，停滿車的一排整排不見（純邏輯，可單元測試）。
 *
 * 用已經認出來的格子當「尺」：同一排的格子寬度一樣、方向一樣、一格接一格。停著的車剛好在這排的
 * 延長線上（縱深差不多），而且離某一格正好整數個格寬 → 在車的位置補一格「有車」的格子。
 * 補出來的格子 conf 低、occupied = true，只拿來顯示（不拿來規劃停車路徑）。
 */
object SlotRowFiller {
    const val MAX_STEPS = 6          // 最多往外推幾格
    const val ALIGN_FRAC = 0.35f     // 離整數格的偏差（格寬的比例）
    const val DEPTH_FRAC = 0.4f      // 縱深差（格深的比例）
    const val FILL_CONF = 0.3f

    class Car(val x: Float, val z: Float)

    fun fill(slots: List<ParkingSlot>, cars: List<Car>): List<ParkingSlot> {
        val out = ArrayList<ParkingSlot>()
        val all = ArrayList(slots)
        for (car in cars) {
            if (all.any { SlotSelection.contains(it, car.x, car.z) }) continue      // 已經在某一格裡
            var best: ParkingSlot? = null; var bestErr = Float.MAX_VALUE
            for (s in slots) {
                if (s.kind != 'p' && s.kind != 'a' && s.kind != 'l') continue
                val c = SlotSelection.center(s)
                // 格子的兩個方向：p0→p1（寬或深）、p1→p2
                val ax = s.p[2] - s.p[0]; val az = s.p[3] - s.p[1]
                val bx = s.p[4] - s.p[2]; val bz = s.p[5] - s.p[3]
                val la = hypot(ax, az); val lb = hypot(bx, bz)
                if (la < 1e-3f || lb < 1e-3f) continue
                // 短邊是格寬（一排的方向），長邊是格深
                val (wx, wz, w, dx, dz, d) = if (la <= lb) Six(ax / la, az / la, la, bx / lb, bz / lb, lb) else Six(bx / lb, bz / lb, lb, ax / la, az / la, la)
                val qx = car.x - c[0]; val qz = car.z - c[1]
                val t = qx * wx + qz * wz; val dep = qx * dx + qz * dz
                if (abs(dep) > d * DEPTH_FRAC) continue
                val k = (t / w).roundToInt()
                if (k == 0 || abs(k) > MAX_STEPS) continue
                val err = abs(t - k * w) / w
                if (err > ALIGN_FRAC) continue
                if (err < bestErr) { bestErr = err; best = s }
            }
            val s = best ?: continue
            val c = SlotSelection.center(s)
            val ax = s.p[2] - s.p[0]; val az = s.p[3] - s.p[1]; val bx = s.p[4] - s.p[2]; val bz = s.p[5] - s.p[3]
            val la = hypot(ax, az); val lb = hypot(bx, bz)
            val (wx, wz, w) = if (la <= lb) Triple(ax / la, az / la, la) else Triple(bx / lb, bz / lb, lb)
            val k = (((car.x - c[0]) * wx + (car.z - c[1]) * wz) / w).roundToInt()
            val ox = k * w * wx; val oz = k * w * wz
            val p = FloatArray(8) { if (it % 2 == 0) s.p[it] + ox else s.p[it] + oz }
            val ns = ParkingSlot(p, s.kind, true, FILL_CONF)
            val nc = SlotSelection.center(ns)
            if (all.any { val oc = SlotSelection.center(it); hypot(oc[0] - nc[0], oc[1] - nc[1]) < w * 0.5f }) continue
            all.add(ns); out.add(ns)
        }
        return out
    }

    private data class Six(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float)
}
