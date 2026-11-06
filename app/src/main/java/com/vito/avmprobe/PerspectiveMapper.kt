package com.vito.avmprobe

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 世界座標（公尺；x 右、y 上、z 前，自車中心地面為原點）→ 原廠 360 **3D 畫面**上的像素。
 *
 * 原廠打方向燈時，右側會切到一個固定的 3D 視角（左燈、右燈各一個）。原廠的 3D 是原生程式畫的，
 * 拿不到它的虛擬鏡頭參數，所以用一個「繞著自車轉的鏡頭」模型讓使用者對位：
 * 把我們畫的立體自車線框疊到原廠的 3D 車模上就對準了。
 *
 * 鏡頭模型（orbit camera）：
 * - 看向目標點 T = ([Params.tx], 0, [Params.tz])（自車中心附近的地面）
 * - [Params.azDeg]：鏡頭繞自車的方位角。0 = 在車正後方往前看，+ = 往右繞（從右後方看）
 * - [Params.elDeg]：俯角（鏡頭在目標點上方幾度）
 * - [Params.dist]：鏡頭到目標點的距離（公尺）
 * - [Params.fovDeg]：垂直視角
 * - [Params.clip*]：原廠 3D 那一格在螢幕上的範圍（螢幕比例），投影中心在這格的正中央
 */
class PerspectiveMapper(val p: Params, val screenW: Int, val screenH: Int) {

    data class Params(
        val clipL: Float, val clipT: Float, val clipR: Float, val clipB: Float,
        val azDeg: Float, val elDeg: Float, val dist: Float, val fovDeg: Float,
        val tx: Float, val tz: Float
    ) {
        /** 左右鏡像（右燈視角的預設值 = 左燈視角的鏡像）。 */
        fun mirrored() = copy(azDeg = -azDeg, tx = -tx)

        companion object {
            /**
             * 打左燈時的初值（依實車照片估）：原廠鏡頭在**右後上方**、往**左前方**看，
             * 車模在 3D 格子的右半邊，3D 格子約佔螢幕右側 57%。實際一定要在車上對位。
             */
            val DEFAULT_LEFT = Params(
                clipL = 0.43f, clipT = 0f, clipR = 1f, clipB = 0.86f,
                azDeg = 30f, elDeg = 18f, dist = 10f, fovDeg = 50f, tx = -2.5f, tz = 4f
            )
            val DEFAULT_RIGHT = DEFAULT_LEFT.mirrored()
        }
    }

    private val ox: Float
    private val oy: Float
    private val focal: Float
    // 鏡頭位置與三軸
    private val cx: Float; private val cy: Float; private val cz: Float
    private val fx: Float; private val fy: Float; private val fz: Float
    private val rx: Float; private val ry: Float; private val rz: Float
    private val ux: Float; private val uy: Float; private val uz: Float

    init {
        val l = p.clipL * screenW; val r = p.clipR * screenW
        val t = p.clipT * screenH; val b = p.clipB * screenH
        ox = (l + r) / 2f
        oy = (t + b) / 2f
        focal = ((b - t) / 2f) / tan(Math.toRadians(p.fovDeg / 2.0)).toFloat()

        val az = Math.toRadians(p.azDeg.toDouble()); val el = Math.toRadians(p.elDeg.toDouble())
        val horiz = p.dist * cos(el).toFloat()
        cx = p.tx + horiz * sin(az).toFloat()
        cy = p.dist * sin(el).toFloat()
        cz = p.tz - horiz * cos(az).toFloat()

        // 前方 f = 目標 − 鏡頭
        var ax = p.tx - cx; var ay = 0f - cy; var az2 = p.tz - cz
        var n = sqrt(ax * ax + ay * ay + az2 * az2)
        fx = ax / n; fy = ay / n; fz = az2 / n
        // 右方 r = up × f（up = +y）：(1·fz − 0, 0, 0 − 1·fx) = (fz, 0, −fx)
        ax = fz; ay = 0f; az2 = -fx
        n = sqrt(ax * ax + az2 * az2).coerceAtLeast(1e-6f)
        rx = ax / n; ry = ay; rz = az2 / n
        // 上方 u = f × r
        ux = fy * rz - fz * ry
        uy = fz * rx - fx * rz
        uz = fx * ry - fy * rx
    }

    val clipLeft get() = p.clipL * screenW
    val clipTop get() = p.clipT * screenH
    val clipRight get() = p.clipR * screenW
    val clipBottom get() = p.clipB * screenH

    /** 投影；在鏡頭後面（或太貼近鏡頭）回 null。結果 [0] = x、[1] = y（螢幕像素）。 */
    fun project(x: Float, y: Float, z: Float): FloatArray? {
        val dx = x - cx; val dy = y - cy; val dz = z - cz
        val zc = dx * fx + dy * fy + dz * fz
        if (zc < 0.2f) return null
        val xc = dx * rx + dy * ry + dz * rz
        val yc = dx * ux + dy * uy + dz * uz
        return floatArrayOf(ox + xc * focal / zc, oy - yc * focal / zc)
    }
}
