package com.vito.avmprobe

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 車頭朝向分類器（[OrientNet]，8 個方向、相對於鏡頭）→ 世界座標的車頭方向，和每台車的平滑。純邏輯，可單元測試。
 *
 * 類別順序（跟 tools/det_label/orient_train.py 的 BINS 一樣）：F FR R BR B BL L FL，每 45°，
 * 「從鏡頭看過去」車頭朝哪：F = 正對鏡頭、B = 背對（朝遠方）、R = 朝畫面右邊……
 * 相對角（從「鏡頭→車」那條射線順時針量，跟 yaw 一樣 + = 往右）：B = 0、R = +90°、F = 180°、L = −90°。
 * 世界方向 = 射線方位 + 相對角。yaw 的定義跟追蹤一樣：atan2(x, z)，0 = 跟自車同方向（往前）、+ = 往右。
 *
 * 不用 argmax：把 8 個方向的機率當向量加權平均 → 連續的角度＋信心（向量長度；左右各一半時接近 0，不可信）。
 */
object OrientHeading {
    const val BINS = 8
    /** 加權平均後的向量長度低於這個就不給方向（機率很散、或前後對半：分不出車頭車尾）。 */
    const val MIN_CONF = 0.45f

    /** 第 i 類的相對角（rad）。 */
    fun relAngle(i: Int): Double = PI - i * (PI / 4)

    /** 原始分數 → (相對角 rad, 信心 0..1)。 */
    fun relFromLogits(logits: FloatArray): Pair<Double, Float> {
        var mx = Float.NEGATIVE_INFINITY
        for (v in logits) if (v > mx) mx = v
        var sum = 0.0; var sx = 0.0; var sy = 0.0
        for (i in 0 until BINS) {
            val p = exp((logits[i] - mx).toDouble()); sum += p
            sx += p * sin(relAngle(i)); sy += p * cos(relAngle(i))
        }
        sx /= sum; sy /= sum
        return Pair(atan2(sx, sy), hypot(sx, sy).toFloat())
    }

    /** 鏡頭在 (camX, camZ)，車在 (x, z)，相對角 rel → 世界 yaw（−π..π）。 */
    fun worldYaw(camX: Float, camZ: Float, x: Float, z: Float, rel: Double): Float {
        val az = atan2((x - camX).toDouble(), (z - camZ).toDouble())
        return wrap(az + rel).toFloat()
    }

    fun wrap(a: Double): Double {
        var r = a
        while (r > PI) r -= 2 * PI
        while (r < -PI) r += 2 * PI
        return r
    }

    /**
     * 每台車（追蹤編號）把每次看到的方向平滑起來：方向向量 × 信心的指數平均。
     * 停著的車方向不會變，平均越久越穩；太久沒更新就丟掉。
     */
    class Smoother(private val alpha: Float = 0.2f, private val ttlMs: Long = 4000L) {
        private class S(var sx: Double, var sy: Double, var at: Long)
        private val m = HashMap<Int, S>()

        fun update(id: Int, yaw: Float, conf: Float, now: Long): Float? {
            val wx = sin(yaw.toDouble()) * conf; val wy = cos(yaw.toDouble()) * conf
            val s = m[id]
            if (s == null || now - s.at > ttlMs) m[id] = S(wx, wy, now)
            else { s.sx += alpha * (wx - s.sx); s.sy += alpha * (wy - s.sy); s.at = now }
            return get(id, now)
        }

        /** 目前的方向；信心（向量長度）太低就 null。 */
        fun get(id: Int, now: Long): Float? {
            val s = m[id] ?: return null
            if (now - s.at > ttlMs) return null
            if (hypot(s.sx, s.sy) < MIN_CONF * 0.8) return null
            return atan2(s.sx, s.sy).toFloat()
        }

        fun prune(now: Long) { m.entries.removeAll { now - it.value.at > ttlMs } }
    }
}
