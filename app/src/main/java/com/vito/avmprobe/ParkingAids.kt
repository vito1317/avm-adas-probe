package com.vito.avmprobe

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 停車輔助（純邏輯，可單元測試）：
 * - 方向盤指示：停車路徑（後軸軌跡，[reverseFrom] 之後是倒車段）現在該打多少方向盤，跟原廠方向盤比較。
 *   倒車時車尾要往哪邊去，方向盤就往哪邊打。
 * - 停正提醒：停在格子裡時，車身左右各離格線多少、車身跟格子差幾度。
 * - 窄路通過：前方兩側都有東西時，中間寬度扣掉車寬還剩多少。
 * - 停車位置記錄：倒車入庫後停好 [SNAP_AFTER_MS]，要存一張俯視圖（回傳 true 一次）。
 */
class ParkingAids(private val geo: VehicleGeometry = VehicleGeometry()) {

    companion object {
        const val SNAP_AFTER_MS = 8000L
        const val STEER_OK_DEG = 30f
    }

    data class Msg(val key: String, val text: String, val level: Int)

    // ───────── 方向盤指示 ─────────
    /** [pts] 後軸軌跡（自車座標），[rev] 倒車段開始的索引；[steerDeg] 目前方向盤（+ 右）。 */
    fun steering(pts: List<FloatArray>, rev: Int, gear: Char?, steerDeg: Float?, turnRadius: Float): Msg? {
        if (pts.size < 3) return null
        if (gear != 'R') {
            // 還沒倒車：先往前開到倒車起點
            val fwd = if (rev in 1 until pts.size) hypot(pts[rev][0] - pts[0][0], pts[rev][1] - pts[0][1]) else 0f
            return if (fwd > 0.4f) Msg("pstep", "先往前開 %.1f m，停下打 R 檔".format(fwd), 0)
            else Msg("pstep", "打 R 檔倒車", 0)
        }
        val st = steerDeg ?: return null
        // 倒車段開頭 3 m 內是不是在轉、往哪邊轉（倒車時軌跡往右彎 → 方向盤往右）
        val i0 = rev.coerceIn(0, pts.size - 1)
        var i1 = i0
        while (i1 < pts.size - 1 && hypot(pts[i1][0] - pts[i0][0], pts[i1][1] - pts[i0][1]) < 3f) i1++
        if (i1 - i0 < 2) return Msg("psteer", "方向盤回正，直直倒", 0)
        val im = (i0 + i1) / 2
        val h0 = atan2((pts[im][0] - pts[i0][0]).toDouble(), (pts[i0][1] - pts[im][1]).toDouble())   // 往後走的方向
        val h1 = atan2((pts[i1][0] - pts[im][0]).toDouble(), (pts[im][1] - pts[i1][1]).toDouble())
        var dh = h1 - h0
        while (dh > Math.PI) dh -= 2 * Math.PI; while (dh < -Math.PI) dh += 2 * Math.PI
        val want = if (abs(dh) < Math.toRadians(4.0)) 0f else {
            val wheel = Math.toDegrees(atan(geo.wheelbase / turnRadius.toDouble().coerceAtLeast(geo.wheelbase))).toFloat()
            // h 是「往後走」的方向（0 = 正後方，車尾往右 = h 變大）；倒車時車尾要往右，方向盤就往右
            (if (dh > 0) 1f else -1f) * (wheel * geo.steerRatio.toFloat()).coerceAtMost(geo.maxSteerDeg.toFloat())
        }
        val diff = want - st
        return when {
            want == 0f && abs(st) < STEER_OK_DEG -> Msg("psteer", "方向盤 OK（回正），慢慢倒", 0)
            want == 0f -> Msg("psteer", "方向盤回正（往${if (st > 0) "左" else "右"}轉 %.1f 圈）".format(abs(st) / 360f), 1)
            abs(diff) < STEER_OK_DEG -> Msg("psteer", "方向盤 OK，慢慢倒", 0)
            else -> Msg("psteer", "方向盤往${if (diff > 0) "右" else "左"}再打 %.1f 圈".format(abs(diff) / 360f), 1)
        }
    }

    // ───────── 停正提醒 ─────────
    /** 自車在格子裡（格子包含原點）時，回傳左右餘量與歪斜。 */
    fun alignment(slots: List<ParkingSlot>, speedKmh: Float?): Msg? {
        if ((speedKmh ?: 0f) > 1.5f) return null
        val s = slots.firstOrNull { SlotSelection.contains(it, 0f, 0f) } ?: return null
        val p = s.p
        val e1 = floatArrayOf(p[2] - p[0], p[3] - p[1]); val e2 = floatArrayOf(p[4] - p[2], p[5] - p[3])
        val long = if (hypot(e1[0], e1[1]) >= hypot(e2[0], e2[1])) e1 else e2
        val ln = hypot(long[0], long[1]).coerceAtLeast(1e-3f)
        val ux = long[0] / ln; val uz = long[1] / ln
        val nx = uz; val nz = -ux                            // 格子的橫向
        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
        for (k in 0 until 4) { val d = p[k * 2] * nx + p[k * 2 + 1] * nz; mn = minOf(mn, d); mx = maxOf(mx, d) }
        // 法向量可能朝左或朝右：統一成「右邊為正」
        val sgn = if (nx >= 0) 1f else -1f
        val right = (if (sgn > 0) mx else -mn) - geo.width.toFloat() / 2
        val left = (if (sgn > 0) -mn else mx) - geo.width.toFloat() / 2
        var ang = Math.toDegrees(atan2(ux.toDouble(), uz.toDouble())).toFloat()
        if (ang > 90f) ang -= 180f; if (ang < -90f) ang += 180f
        val off = (right - left) / 2                         // + = 車偏左（右邊空得多）
        val txt = "停正：左 %.2f m｜右 %.2f m".format(left, right) +
            (if (abs(ang) >= 2f) "｜歪 %.0f°".format(abs(ang)) else "") +
            (if (abs(off) >= 0.15f) "（偏${if (off > 0) "左" else "右"} %.0f cm）".format(abs(off) * 100) else "（置中）")
        return Msg("align", txt, if (abs(off) >= 0.25f || abs(ang) >= 5f || minOf(left, right) < 0.2f) 1 else 0)
    }

    // ───────── 窄路通過 ─────────
    data class Thing(val x: Float, val z: Float, val halfW: Float)

    fun narrowGap(things: List<Thing>, speedKmh: Float?, gear: Char?): Msg? {
        // 停著不動不提醒（停車場兩邊本來就有車）；慢速往前開才看
        if (gear == 'R' || (speedKmh ?: 0f) > 20f || (speedKmh ?: 0f) < 3f) return null
        var leftEdge = -Float.MAX_VALUE; var rightEdge = Float.MAX_VALUE
        for (t in things) {
            if (t.z < 2.5f || t.z > 10f || abs(t.x) > 4.5f) continue
            if (t.x < 0f) leftEdge = maxOf(leftEdge, t.x + t.halfW) else rightEdge = minOf(rightEdge, t.x - t.halfW)
        }
        if (leftEdge == -Float.MAX_VALUE || rightEdge == Float.MAX_VALUE) return null
        val gap = rightEdge - leftEdge
        if (gap > 3.4f) return null                          // 不算窄
        val spare = gap - geo.width.toFloat()
        return when {
            spare < 0f -> Msg("narrow", "前面過不去（寬 %.1f m，車寬 %.1f m）".format(gap, geo.width), 2)
            spare < 0.4f -> Msg("narrow", "前面很窄，兩側各只剩 %.0f cm".format(spare * 50), 1)
            else -> Msg("narrow", "前面可以通過（兩側各餘 %.0f cm）".format(spare * 50), 0)
        }
    }

    // ───────── 停車位置記錄 ─────────
    private var lastReverseAt = -1L
    private var stoppedAt = -1L
    private var snapped = false

    fun shouldSnapshot(now: Long, speedKmh: Float?, gear: Char?): Boolean {
        if (gear == 'R') { lastReverseAt = now; snapped = false }
        val stopped = (speedKmh ?: 0f) < 0.5f
        if (!stopped) { stoppedAt = -1L; return false }
        if (stoppedAt < 0L) stoppedAt = now
        val recentlyParked = lastReverseAt >= 0L && now - lastReverseAt < 3 * 60_000L
        if (!snapped && recentlyParked && now - stoppedAt >= SNAP_AFTER_MS) { snapped = true; return true }
        return false
    }
}
