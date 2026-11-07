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
 * - 看向目標點 T = ([Params.tx], [Params.ty], [Params.tz])（自車中心附近，ty 是離地高度）
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
        val tx: Float, val tz: Float,
        /** 看向的點離地多高（m）：整個畫面上下移。 */
        val ty: Float = 0f,
        /** 原廠畫面左右翻轉（高速打方向燈的往後看視角，像照後鏡）。 */
        val mirror: Boolean = false,
        /** 投影中心偏離格子正中央多少（半寬／半高的比例，+ = 右／下）。鏡頭展開畫面用。 */
        val cxOff: Float = 0f, val cyOff: Float = 0f,
        /** 畫面繞光軸轉了幾度（+ = 逆時針；側鏡頭裝得歪）。 */
        val rollDeg: Float = 0f
    ) {
        /** 左右鏡像（右燈視角的預設值 = 左燈視角的鏡像）。 */
        fun mirrored() = copy(azDeg = -azDeg, tx = -tx)

        companion object {
            /**
             * 打左燈（中速）的原廠 3D 視角：原廠 setting.lua 的 Side3DParam.Left
             * （Yaw 30、Pitch 15、EyeDist 7500、CenPos {0, 500, −500}、FOV 32），換算見 [FactoryViews.toParams]。
             * 3D 格子在螢幕 x 34.8%～100%、上方 87%（viewport.lua 的 BVSV 版面）。
             * 車上讀得到原廠設定檔時用 [FactoryViews] 讀到的值（含使用者在原廠設定改過的）。
             */
            val DEFAULT_LEFT = FactoryViews.toParams(
                FactoryViews.View3D(30f, 15f, 7500f, floatArrayOf(0f, 500f, -500f), 32f, false),
                32f, floatArrayOf(0.348f, 0f, 1f, 0.87f)
            )
            val DEFAULT_RIGHT = DEFAULT_LEFT.mirrored()
        }
    }

    private val ox: Float
    private val oy: Float
    private val rc = kotlin.math.cos(Math.toRadians(p.rollDeg.toDouble())).toFloat()
    private val rs = kotlin.math.sin(Math.toRadians(p.rollDeg.toDouble())).toFloat()
    private val focal: Float
    // 鏡頭位置與三軸
    private val cx: Float; private val cy: Float; private val cz: Float
    private val fx: Float; private val fy: Float; private val fz: Float
    private val rx: Float; private val ry: Float; private val rz: Float
    private val ux: Float; private val uy: Float; private val uz: Float

    init {
        val l = p.clipL * screenW; val r = p.clipR * screenW
        val t = p.clipT * screenH; val b = p.clipB * screenH
        ox = (l + r) / 2f + p.cxOff * (r - l) / 2f
        oy = (t + b) / 2f + p.cyOff * (b - t) / 2f
        focal = ((b - t) / 2f) / tan(Math.toRadians(p.fovDeg / 2.0)).toFloat()

        val az = Math.toRadians(p.azDeg.toDouble()); val el = Math.toRadians(p.elDeg.toDouble())
        val horiz = p.dist * cos(el).toFloat()
        cx = p.tx + horiz * sin(az).toFloat()
        cy = p.ty + p.dist * sin(el).toFloat()
        cz = p.tz - horiz * cos(az).toFloat()

        // 前方 f = 目標 − 鏡頭
        var ax = p.tx - cx; var ay = p.ty - cy; var az2 = p.tz - cz
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

    /**
     * 反投影：螢幕像素 → 地面（y = 0）上的點 (x, z)。射線沒往下（看到地平線以上）回 null。
     * 自動對位用：拿原廠畫面的像素去對我們俯視圖上同一個地面點。
     */
    fun unprojectToGround(sx: Float, sy: Float): FloatArray? {
        val x0 = (if (p.mirror) ox - sx else sx - ox) / focal
        val y0 = -(sy - oy) / focal
        // 畫面上的座標轉回沒有傾斜的鏡頭座標
        val xc = rc * x0 - rs * y0
        val yc = rs * x0 + rc * y0
        // 世界方向 = f + xc·r + yc·u
        val dx = fx + xc * rx + yc * ux
        val dy = fy + xc * ry + yc * uy
        val dz = fz + xc * rz + yc * uz
        if (dy > -1e-4f) return null
        val t = -cy / dy
        return floatArrayOf(cx + t * dx, cz + t * dz)
    }

    /** 點在鏡頭前方多遠（沿光軸，m）；負的 = 在鏡頭後面。 */
    fun depth(x: Float, y: Float, z: Float): Float = (x - cx) * fx + (y - cy) * fy + (z - cz) * fz

    /**
     * 地面多邊形投影成螢幕多邊形：先把鏡頭後面（深度 < [NEAR]）的部分切掉（Sutherland–Hodgman），
     * 不是只丟掉看不到的角點（那樣剩下的點會連成錯的形狀；實車：側鏡頭看隔壁格只看得到前半段）。
     */
    fun projectGroundPolygon(poly: List<FloatArray>): List<FloatArray> {
        if (poly.size < 3) return emptyList()
        val near = 0.3f
        val clipped = ArrayList<FloatArray>()
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[(i + 1) % poly.size]
            val da = depth(a[0], 0f, a[1]) - near; val db = depth(b[0], 0f, b[1]) - near
            if (da >= 0f) clipped.add(a)
            if ((da >= 0f) != (db >= 0f)) {
                val t = da / (da - db)
                clipped.add(floatArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
            }
        }
        return clipped.mapNotNull { project(it[0], 0f, it[1]) }
    }

    /** 投影；在鏡頭後面（或太貼近鏡頭）回 null。結果 [0] = x、[1] = y（螢幕像素）。 */
    fun project(x: Float, y: Float, z: Float): FloatArray? {
        val dx = x - cx; val dy = y - cy; val dz = z - cz
        val zc = dx * fx + dy * fy + dz * fz
        if (zc < 0.2f) return null
        val xu = dx * rx + dy * ry + dz * rz
        val yu = dx * ux + dy * uy + dz * uz
        // 鏡頭傾斜：沒有傾斜的座標轉到畫面上
        val xc = rc * xu + rs * yu
        val yc = -rs * xu + rc * yu
        val px = xc * focal / zc
        return floatArrayOf(if (p.mirror) ox - px else ox + px, oy - yc * focal / zc)
    }
}
