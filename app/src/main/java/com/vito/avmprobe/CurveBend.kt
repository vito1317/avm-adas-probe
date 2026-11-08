package com.vito.avmprobe

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 把「直路座標」下的路徑彎成沿著彎道：直路座標裡 z = 沿路前進距離 s、x = 離車道中心線的橫向距離 d，
 * 彎道（曲率 k，1/m，+ = 往右彎）上對應的點 = 中心線上弧長 s 的點 + d × 該點往右的法向量。
 * 變道走廊、目標車道都這樣彎，預測線就會照著彎道畫（實車需求：彎道上打燈，預測線要跟著彎）。
 */
object CurveBend {
    /** 比這個更直就不彎（半徑 > 400 m）。 */
    const val MIN_K = 1.0 / 400.0

    fun point(x: Float, z: Float, k: Double): FloatArray {
        if (abs(k) < MIN_K) return floatArrayOf(x, z)
        val ks = k * z
        val cx = (1 - cos(ks)) / k; val cz = sin(ks) / k
        return floatArrayOf((cx + x * cos(ks)).toFloat(), (cz - x * sin(ks)).toFloat())
    }

    fun bend(pts: List<FloatArray>, k: Double?): List<FloatArray> =
        if (k == null || abs(k) < MIN_K) pts else pts.map { point(it[0], it[1], k) }

    /** 直的邊（從 z0 到 z1）切細再彎，彎完才會是平滑的弧。 */
    fun densify(x: Float, z0: Float, z1: Float, step: Float = 1f): List<FloatArray> {
        val n = maxOf(1, (abs(z1 - z0) / step).toInt())
        return (0..n).map { floatArrayOf(x, z0 + (z1 - z0) * it / n) }
    }
}
