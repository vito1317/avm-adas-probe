package com.vito.avmprobe

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * 停著不動的東西（停車場裡停好的車）在畫面上不要飄（純邏輯，可單元測試）。
 *
 * 實車：停車場開道路模式，旁邊停著的車一直飄、一直轉。原因：
 * - 畫的位置是每次偵測的單眼估距（被擋住一半、換鏡頭時差很多），沒有平滑；
 * - 方向用「移動方向」算，自己在動時靜止的車量到一點假速度，方向就亂轉，在門檻上下又跟格子方向來回切換。
 *
 * 做法：判定沒在動（對地速度 < [MOVING_MPS]）就「釘在地上」：
 * - 之後照自車的移動（[egoMotion]：前進距離、轉角）推算它該在哪；
 * - 新的偵測只用小比例拉過去，差太多的當雜訊（連續 [RESET_AFTER] 次差太多才相信它真的換位置）；
 * - 方向鎖住：有格子方向就用格子的，否則沿用上次的。
 * 一動起來（對地速度超過門檻）就放掉，照原本的即時位置畫。
 */
class StaticStabilizer {
    companion object {
        const val MOVING_MPS = 1.2f
        const val GAIN = 0.12f          // 穩定後每次往新偵測拉多少
        const val GAIN_NEW = 0.45f      // 剛釘住的前幾次拉多一點（還沒收斂）
        const val NEW_N = 4
        const val GATE_M = 1.2f         // 差超過這個（再加距離的 15%）當雜訊
        const val RESET_AFTER = 3
        const val FORGET_MS = 3000L
    }

    private class A(var x: Float, var z: Float, var yaw: Float?, var seen: Long, var n: Int, var miss: Int)

    private val anchors = HashMap<Int, A>()

    /** 自車往前走了 [dz] 公尺、轉了 [dyaw] 弧度（同 [SlotSelection.egoMotion] 的座標換算）。 */
    fun egoMotion(dz: Float, dyaw: Float) {
        if (dz == 0f && dyaw == 0f) return
        val c = cos(dyaw); val s = sin(dyaw)
        for (a in anchors.values) {
            val x = a.x; val z = a.z - dz
            a.x = x * c - z * s; a.z = x * s + z * c
            a.yaw = a.yaw?.let { it - dyaw }
        }
    }

    /**
     * 一個有追蹤編號 [id] 的物件：這次偵測在 ([x], [z])、對地速度 [groundSpeed]（m/s）、可用的方向提示 [yawHint]（例如所在格子的方向）。
     * 回傳要畫的 (x, z, yaw)；yaw = null 表示不知道。
     */
    fun apply(id: Int, x: Float, z: Float, groundSpeed: Float, yawHint: Float?, now: Long): Triple<Float, Float, Float?> {
        if (groundSpeed > MOVING_MPS) { anchors.remove(id); return Triple(x, z, yawHint) }
        val a = anchors[id]
        if (a == null) {
            anchors[id] = A(x, z, yawHint, now, 1, 0)
            return Triple(x, z, yawHint)
        }
        a.seen = now
        val d = hypot(x - a.x, z - a.z)
        val gate = GATE_M + 0.15f * hypot(a.x, a.z)
        if (d > gate) {
            a.miss++
            if (a.miss >= RESET_AFTER) { a.x = x; a.z = z; a.n = 1; a.miss = 0 }
        } else {
            a.miss = 0
            val k = if (a.n < NEW_N) GAIN_NEW else GAIN
            a.x += (x - a.x) * k; a.z += (z - a.z) * k; a.n++
        }
        if (yawHint != null && (a.yaw == null || angDiff(yawHint, a.yaw!!) > 0.35f)) a.yaw = yawHint
        else if (yawHint != null) a.yaw = a.yaw!! + wrap(yawHint - a.yaw!!) * 0.2f
        return Triple(a.x, a.z, a.yaw)
    }

    /** 一段時間沒看到的就忘掉（追蹤編號可能被別台車拿去用）。 */
    fun prune(now: Long) { anchors.values.removeAll { now - it.seen > FORGET_MS } }

    private fun wrap(a: Float): Float { var v = a; while (v > Math.PI) v -= (2 * Math.PI).toFloat(); while (v < -Math.PI) v += (2 * Math.PI).toFloat(); return v }
    /** 車的方向前後對稱（停車格方向沒分頭尾）：差 180° 算同一個方向。 */
    private fun angDiff(a: Float, b: Float): Float { val d = abs(wrap(a - b)); return max(0f, minOf(d, (Math.PI - d).toFloat())) }
    @Suppress("unused") private fun heading(x: Float, z: Float) = atan2(x, z)
}
