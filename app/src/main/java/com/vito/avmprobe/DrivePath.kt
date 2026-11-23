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
}
