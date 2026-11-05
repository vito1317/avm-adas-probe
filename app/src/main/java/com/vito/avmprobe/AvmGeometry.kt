package com.vito.avmprobe

import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

// 純 Kotlin（不碰 android.*）：四路魚眼 ↔ 地面幾何、BEV 查表。
// 模型與 tools/avm_geometry.py 完全相同，改公式時兩邊一起改並重產 geometry_fixtures.json。
//
// 車身座標：x 右、y 上、z 前（m），原點 = 車身投影中心的地面點。
// 相機座標：x 右、y 下、z 光軸。等距魚眼 rd = θ(1 + k1θ² + k2θ⁴)。

private const val DEG = PI / 180.0

/** 投地假設的入射仰角下限（度）。低於此角度，一個原始像素攤成一大片地面，立起來的東西會拉成拖痕。 */
const val IPM_MIN_ELEV_DEG = 15.0
private const val HORIZON_EPS = 1e-6   // 射線 y 分量要 < -1e-6 才算打到地面
private const val EGO_MARGIN = 0.05    // 車身外擴 5 cm 在 BEV 裡留黑
private const val NEWTON_ITERS = 10

/**
 * 一顆魚眼相機（avm_calib.json 的 cams.*）。
 * cx/cy 以象限寬/高為單位；fx/fy 都以象限「寬度」為單位；角度單位為度。
 * quad：0 左上=前、1 右上=後、2 左下=左、3 右下=右。
 */
data class CamParams(
    val name: String, val quad: Int, val mirror: Boolean,
    val cx: Double, val cy: Double, val fx: Double, val fy: Double,
    val k1: Double, val k2: Double, val maxDeg: Double,
    val px: Double, val py: Double, val pz: Double,
    val yaw: Double, val pitch: Double, val roll: Double   // degrees
) {
    // 相機 → 車身：Ryaw·Rpitch·Rroll·B（row-major 3x3），建構時算一次
    internal val rot: DoubleArray = camRotation(yaw, pitch, roll)
    internal val maxRad: Double = maxDeg * DEG
}

/** BEV 影像規格：row 0 = 前、column 0 = 左，每像素 mpp 公尺。 */
data class BevSpec(val xMin: Double, val xMax: Double, val zMin: Double, val zMax: Double, val mpp: Double) {
    val width: Int = floor((xMax - xMin) / mpp + 0.5).toInt()
    val height: Int = floor((zMax - zMin) / mpp + 0.5).toInt()
    /** 車身原點在 BEV 的像素位置（x = (i + 0.5 - originX)·mpp，z = (originY - (j + 0.5))·mpp）。 */
    val originX: Double = -xMin / mpp
    val originY: Double = zMax / mpp
}

class AvmGeometry(val cams: List<CamParams>, val bev: BevSpec, val carLength: Double, val carWidth: Double) {

    /**
     * 「投地假設站得住腳」的區域（以入射仰角 ≥ [minElevDeg] 為準），長度 = bev.width * bev.height。
     *
     * 俯視圖是把畫面投影到 y = 0 的平面拼出來的。鏡頭看地面的入射角越平，一個原始像素就攤成
     * 越大一片地面，而且**立起來的東西會被拉成放射狀的長條拖痕** —— 那些拖痕又長又亮，
     * top-hat + Hough 一抓一個準，全都是假線（實車窄巷的擷取裡，外圈幾乎全是店面招牌和
     * 路邊機車的拖痕）。
     *
     * 15° 對應鏡頭高 0.84 m 時離鏡頭約 3.1 m。實測把這圈切掉之後：
     * 假線大幅減少，而且**彎道才開始抓得到** —— 以前二次擬合被外圈的拖痕拉著，
     * 怎麼擬都是直線。
     *
     * 算一次就好（只跟校正有關），結果快取在 [ipmCache]。
     */
    fun ipmValidMask(minElevDeg: Double = IPM_MIN_ELEV_DEG): BooleanArray {
        ipmCache?.let { if (ipmCacheDeg == minElevDeg) return it }
        val w = bev.width
        val h = bev.height
        val out = BooleanArray(w * h)
        val tanMin = kotlin.math.tan(minElevDeg * DEG)
        for (j in 0 until h) {
            val z = bev.zMax - (j + 0.5) * bev.mpp
            val row = j * w
            for (i in 0 until w) {
                val x = bev.xMin + (i + 0.5) * bev.mpp
                var ok = false
                for (c in cams) {
                    val horiz = kotlin.math.hypot(x - c.px, z - c.pz)
                    if (c.py >= horiz * tanMin) { ok = true; break }
                }
                out[row + i] = ok
            }
        }
        ipmCache = out
        ipmCacheDeg = minElevDeg
        return out
    }

    private var ipmCache: BooleanArray? = null
    private var ipmCacheDeg = Double.NaN

    companion object {
        /** 讀 avm_calib.json 的內容。cams 依 quad 排序。 */
        fun fromJson(text: String): AvmGeometry {
            val root = JSONObject(text)
            val car = root.optJSONObject("car")
            val length = car?.optDouble("length", 4.84) ?: 4.84
            val width = car?.optDouble("width", 1.85) ?: 1.85
            val cs = root.getJSONObject("cams")
            val cams = ArrayList<CamParams>()
            for (name in cs.keys()) {
                val o = cs.getJSONObject(name)
                val pos = o.getJSONArray("pos")
                cams += CamParams(
                    name = name, quad = o.getInt("quad"), mirror = o.optBoolean("mirror", false),
                    cx = o.optDouble("cx", 0.5), cy = o.optDouble("cy", 0.5),
                    fx = o.optDouble("fx", 0.265), fy = o.optDouble("fy", 0.265),
                    k1 = o.optDouble("k1", 0.0), k2 = o.optDouble("k2", 0.0),
                    maxDeg = o.optDouble("maxDeg", 95.0),
                    px = pos.getDouble(0), py = pos.getDouble(1), pz = pos.getDouble(2),
                    yaw = o.optDouble("yaw", 0.0), pitch = o.optDouble("pitch", 0.0),
                    roll = o.optDouble("roll", 0.0)
                )
            }
            cams.sortBy { it.quad }
            val b = root.optJSONObject("bev") ?: JSONObject()
            val bev = BevSpec(
                b.optDouble("xMin", -6.0), b.optDouble("xMax", 6.0),
                b.optDouble("zMin", -7.0), b.optDouble("zMax", 7.0), b.optDouble("mpp", 0.04)
            )
            return AvmGeometry(cams, bev, length, width)
        }
    }

    fun cam(name: String): CamParams? = cams.firstOrNull { it.name == name }

    fun camForQuad(q: Int): CamParams? = cams.firstOrNull { it.quad == q }

    /** 象限內像素 (u, v)（連續座標，整數像素 k 的中心是 k + 0.5）→ 車身座標單位射線 (x, y, z)。 */
    fun pixelToRay(c: CamParams, u: Double, v: Double, qw: Int, qh: Int): DoubleArray {
        val out = DoubleArray(3)
        rayInto(c, u, v, qw, qh, out)
        return out
    }

    /** 像素 → 地面點 (x, z)；射線沒往下（|y| ≤ 1e-6 或朝上）→ null。 */
    fun pixelToGround(c: CamParams, u: Double, v: Double, qw: Int, qh: Int): DoubleArray? {
        val r = DoubleArray(3)
        rayInto(c, u, v, qw, qh, r)
        if (!(r[1] < -HORIZON_EPS)) return null
        val t = -c.py / r[1]
        return doubleArrayOf(c.px + t * r[0], c.pz + t * r[2])
    }

    /** 地面點 (gx, gz) → 象限內像素 (u, v)；超出 maxDeg 或落在象限外 → null。 */
    fun groundToPixel(c: CamParams, gx: Double, gz: Double, qw: Int, qh: Int): DoubleArray? {
        val out = DoubleArray(3)
        return if (project(c, gx, gz, qw, qh, out)) doubleArrayOf(out[0], out[1]) else null
    }

    /**
     * BEV 查表：每個 BEV 像素（row-major，bev.width × bev.height）→ 原始 frameW×frameH 2x2 畫面的像素索引，
     * 車身範圍或沒有鏡頭看得到 = -1。多顆都看得到時取 θ 最小（最靠光軸）的那顆，同 θ 取 quad 小的。
     */
    fun buildBevLut(frameW: Int, frameH: Int): IntArray {
        val qw = frameW / 2
        val qh = frameH / 2
        val bw = bev.width
        val bh = bev.height
        val order = cams.sortedBy { it.quad }
        val n = order.size
        val qx0 = IntArray(n) { (order[it].quad % 2) * qw }
        val qy0 = IntArray(n) { (order[it].quad / 2) * qh }
        val hw = carWidth / 2 + EGO_MARGIN
        val hl = carLength / 2 + EGO_MARGIN
        val tmp = DoubleArray(3)
        val lut = IntArray(bw * bh)
        for (j in 0 until bh) {
            val gz = bev.zMax - (j + 0.5) * bev.mpp
            val inZ = abs(gz) <= hl
            val row = j * bw
            for (i in 0 until bw) {
                val gx = bev.xMin + (i + 0.5) * bev.mpp
                var best = -1
                if (!(inZ && abs(gx) <= hw)) {
                    var bestTh = Double.POSITIVE_INFINITY
                    for (k in 0 until n) {
                        if (project(order[k], gx, gz, qw, qh, tmp) && tmp[2] < bestTh) {
                            bestTh = tmp[2]
                            best = (qy0[k] + floor(tmp[1]).toInt()) * frameW + qx0[k] + floor(tmp[0]).toInt()
                        }
                    }
                }
                lut[row + i] = best
            }
        }
        return lut
    }

    private fun rayInto(c: CamParams, u0: Double, v: Double, qw: Int, qh: Int, out: DoubleArray) {
        val u = if (c.mirror) qw - u0 else u0
        val dx = (u - c.cx * qw) / (c.fx * qw)
        val dy = (v - c.cy * qh) / (c.fy * qw)
        val rd = sqrt(dx * dx + dy * dy)
        val th = solveTheta(rd, c.k1, c.k2)
        val st = sin(th)
        val ct = cos(th)
        val ca: Double
        val sa: Double
        if (rd > 0.0) { ca = dx / rd; sa = dy / rd } else { ca = 1.0; sa = 0.0 }
        val x = st * ca
        val y = st * sa
        val m = c.rot
        out[0] = m[0] * x + m[1] * y + m[2] * ct
        out[1] = m[3] * x + m[4] * y + m[5] * ct
        out[2] = m[6] * x + m[7] * y + m[8] * ct
    }

    /**
     * 地面點投影；out = (u, v, θ)，不論有效與否都會填。回傳是否有效（θ ≤ maxDeg 且落在象限內）。
     * θ 用 atan2(√(x²+y²), z)，與 acos(z/|r|) 相同但在光軸附近不失精度。
     */
    internal fun project(c: CamParams, gx: Double, gz: Double, qw: Int, qh: Int, out: DoubleArray): Boolean {
        val m = c.rot
        val dx = gx - c.px
        val dy = -c.py
        val dz = gz - c.pz
        // rc = rotᵀ · d
        val x = m[0] * dx + m[3] * dy + m[6] * dz
        val y = m[1] * dx + m[4] * dy + m[7] * dz
        val z = m[2] * dx + m[5] * dy + m[8] * dz
        val r = sqrt(x * x + y * y)
        val th = atan2(r, z)
        val t2 = th * th
        val rd = th * (1.0 + c.k1 * t2 + c.k2 * t2 * t2)
        val ca: Double
        val sa: Double
        if (r > 0.0) { ca = x / r; sa = y / r } else { ca = 1.0; sa = 0.0 }
        var u = c.cx * qw + c.fx * qw * rd * ca
        val v = c.cy * qh + c.fy * qw * rd * sa
        if (c.mirror) u = qw - u
        out[0] = u
        out[1] = v
        out[2] = th
        return th <= c.maxRad && u >= 0.0 && u < qw && v >= 0.0 && v < qh
    }
}

/** 解 rd = θ(1 + k1θ² + k2θ⁴)：從 θ = rd 起做 10 次 Newton，θ ≥ 0。 */
internal fun solveTheta(rd: Double, k1: Double, k2: Double): Double {
    if (k1 == 0.0 && k2 == 0.0) return rd
    var t = rd
    repeat(NEWTON_ITERS) {
        val t2 = t * t
        val f = t * (1.0 + k1 * t2 + k2 * t2 * t2) - rd
        val fp = 1.0 + 3.0 * k1 * t2 + 5.0 * k2 * t2 * t2
        if (abs(fp) >= 1e-12) t -= f / fp
        if (t < 0.0) t = 0.0
    }
    return t
}

private fun mul3(a: DoubleArray, b: DoubleArray): DoubleArray {
    val m = DoubleArray(9)
    for (i in 0 until 3) for (j in 0 until 3) {
        m[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j]
    }
    return m
}

private fun camRotation(yawDeg: Double, pitchDeg: Double, rollDeg: Double): DoubleArray {
    val cy = cos(yawDeg * DEG); val sy = sin(yawDeg * DEG)
    val cp = cos(pitchDeg * DEG); val sp = sin(pitchDeg * DEG)
    val cr = cos(rollDeg * DEG); val sr = sin(rollDeg * DEG)
    val ryaw = doubleArrayOf(cy, 0.0, sy, 0.0, 1.0, 0.0, -sy, 0.0, cy)          // ψ=90 → 朝 +x（右）
    val rpitch = doubleArrayOf(1.0, 0.0, 0.0, 0.0, cp, -sp, 0.0, sp, cp)        // φ>0 → 光軸往下
    val rroll = doubleArrayOf(cr, -sr, 0.0, sr, cr, 0.0, 0.0, 0.0, 1.0)
    val b = doubleArrayOf(1.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, 1.0)         // 相機 y 下 → 車身 y 上
    return mul3(mul3(mul3(ryaw, rpitch), rroll), b)
}
