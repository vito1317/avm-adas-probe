package com.vito.avmprobe

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 用原廠 3D 視角的虛擬相機參數（[PerspectiveMapper.Params]，從原廠設定檔讀到的方位、俯角、距離、視角）
 * 自己把四顆魚眼貼到「碗」上重建同一個畫面，給行車記錄器右半邊用（不錄螢幕，不吃效能）。
 *
 * 碗：自車附近是平的地面，半徑 [FLAT_R] 外往上彎成碗壁（高度 = k·(r − FLAT_R)²），遠處的車、樓才不會被壓扁在地上。
 * 每個輸出像素：從虛擬相機打一條光線 → 跟碗求交點 → 看得最正的那顆實體鏡頭 → 整幀的像素索引。
 * 查表只在視角改變時算一次（約數十 ms），之後每幀就是查表取色，跟原本的前方畫面一樣便宜。
 * 自車那塊（光線打到自車範圍）留 -1，畫車模用。
 */
class BowlView(private val geo: AvmGeometry, val params: PerspectiveMapper.Params, val size: Int) {
    companion object {
        const val FLAT_R = 5.0
        const val BOWL_K = 0.15
        const val MAX_R = 25.0
    }

    private var lut: IntArray? = null
    private var lutKey = 0L

    /** 虛擬相機的位置與三軸（跟 PerspectiveMapper 一樣的定義）。 */
    private val cam = DoubleArray(3); private val f = DoubleArray(3); private val r = DoubleArray(3); private val u = DoubleArray(3)
    private val focal: Double

    init {
        val p = params
        val az = Math.toRadians(p.azDeg.toDouble()); val el = Math.toRadians(p.elDeg.toDouble())
        val horiz = p.dist * cos(el)
        cam[0] = p.tx + horiz * sin(az); cam[1] = p.ty + p.dist * sin(el); cam[2] = p.tz - horiz * cos(az)
        var ax = p.tx - cam[0]; var ay = p.ty - cam[1]; var az2 = p.tz - cam[2]
        var n = sqrt(ax * ax + ay * ay + az2 * az2)
        f[0] = ax / n; f[1] = ay / n; f[2] = az2 / n
        ax = f[2]; ay = 0.0; az2 = -f[0]; n = sqrt(ax * ax + az2 * az2).coerceAtLeast(1e-6)
        r[0] = ax / n; r[1] = ay; r[2] = az2 / n
        u[0] = f[1] * r[2] - f[2] * r[1]; u[1] = f[2] * r[0] - f[0] * r[2]; u[2] = f[0] * r[1] - f[1] * r[0]
        focal = (size / 2.0) / tan(Math.toRadians(p.fovDeg / 2.0))
    }

    /**
     * 自車中心（地面原點）投影到輸出畫面的哪裡、每公尺幾像素：貼 E60 車模用。
     * 回傳 (x, y, 每公尺像素)；在鏡頭後面回 null。
     */
    fun projectOrigin(): FloatArray? {
        val vx = -cam[0]; val vy = -cam[1]; val vz = -cam[2]
        val d = vx * f[0] + vy * f[1] + vz * f[2]
        if (d < 0.3) return null
        val xr = (vx * r[0] + vy * r[1] + vz * r[2]) / d; val yu = (vx * u[0] + vy * u[1] + vz * u[2]) / d
        val half = size / 2.0
        val px = if (params.mirror) half - xr * focal else half + xr * focal
        return floatArrayOf(px.toFloat(), (half - yu * focal).toFloat(), (focal / d).toFloat())
    }

    /**
     * 地面點 (x, z) → 輸出畫面像素；在鏡頭後面回 null（動態輔助線、轉彎路徑用）。
     * 碗面重建時，5 m 外的地面被貼到碗壁上：實體鏡頭看那個地面點的光線跟碗壁的交點才是它在畫面上出現的地方，
     * 所以遠的點先沿「看得最正的鏡頭 → 地面點」的光線找到碗壁上的位置再投影，線才會貼在畫面裡的路面上。
     */
    fun projectGround(x: Double, z: Double): FloatArray? {
        if (hypot(x, z) <= FLAT_R) return project(x, 0.0, z)
        var best: CamParams? = null; var bestAng = Double.MAX_VALUE
        for (c in geo.cams) {
            val vx = x - c.px; val vy = -c.py; val vz = z - c.pz; val m = c.rot
            val cz = m[2] * vx + m[5] * vy + m[8] * vz
            val cr = sqrt((m[0] * vx + m[3] * vy + m[6] * vz).let { it * it } + (m[1] * vx + m[4] * vy + m[7] * vz).let { it * it })
            val ang = atan2(cr, cz)
            if (ang < bestAng) { bestAng = ang; best = c }
        }
        val c = best ?: return project(x, 0.0, z)
        // 從鏡頭往地面點走，找第一個「高度 ≤ 碗面高度」的位置（二分）
        fun below(t: Double): Boolean {
            val px = c.px + t * (x - c.px); val py = c.py + t * (0.0 - c.py); val pz = c.pz + t * (z - c.pz)
            val rr = hypot(px, pz); val surf = if (rr <= FLAT_R) 0.0 else BOWL_K * (rr - FLAT_R) * (rr - FLAT_R)
            return py <= surf
        }
        var lo = 0.0; var hi = 1.0
        repeat(20) { val m = (lo + hi) / 2; if (below(m)) hi = m else lo = m }
        return project(c.px + hi * (x - c.px), c.py + hi * (0.0 - c.py), c.pz + hi * (z - c.pz))
    }

    /** 3D 點 → 輸出畫面像素；在鏡頭後面回 null。 */
    fun project(x: Double, y: Double, z: Double): FloatArray? {
        val vx = x - cam[0]; val vy = y - cam[1]; val vz = z - cam[2]
        val d = vx * f[0] + vy * f[1] + vz * f[2]
        if (d < 0.3) return null
        val xr = (vx * r[0] + vy * r[1] + vz * r[2]) / d; val yu = (vx * u[0] + vy * u[1] + vz * u[2]) / d
        val half = size / 2.0
        return floatArrayOf((if (params.mirror) half - xr * focal else half + xr * focal).toFloat(), (half - yu * focal).toFloat())
    }

    /** 光線（起點 cam、方向 d）跟碗的交點；打不到回 null。 */
    private fun hitBowl(dx: Double, dy: Double, dz: Double): DoubleArray? {
        // 先試平的地面
        if (dy < -1e-6) {
            val t = -cam[1] / dy
            val x = cam[0] + t * dx; val z = cam[2] + t * dz
            if (hypot(x, z) <= FLAT_R) return doubleArrayOf(x, 0.0, z)
        }
        // 碗壁：沿光線往前走，找第一個「高度 ≤ 碗面高度」的點（二分法細化）
        var lo = 0.0; var hi = -1.0
        var t = 0.5
        while (t < 80.0) {
            val x = cam[0] + t * dx; val y = cam[1] + t * dy; val z = cam[2] + t * dz
            val rr = hypot(x, z)
            if (rr > MAX_R) break
            val surf = if (rr <= FLAT_R) 0.0 else BOWL_K * (rr - FLAT_R) * (rr - FLAT_R)
            if (y <= surf) { hi = t; break }
            lo = t; t += 0.5
        }
        if (hi < 0) return null
        repeat(12) {
            val m = (lo + hi) / 2
            val x = cam[0] + m * dx; val y = cam[1] + m * dy; val z = cam[2] + m * dz
            val rr = hypot(x, z); val surf = if (rr <= FLAT_R) 0.0 else BOWL_K * (rr - FLAT_R) * (rr - FLAT_R)
            if (y <= surf) hi = m else lo = m
        }
        return doubleArrayOf(cam[0] + hi * dx, cam[1] + hi * dy, cam[2] + hi * dz)
    }

    /** 查表：輸出像素（row-major）→ 整幀像素索引；看不到或是自車 = -1。 */
    fun lut(frameW: Int, frameH: Int): IntArray {
        val key = (frameW.toLong() shl 32) or frameH.toLong()
        lut?.let { if (lutKey == key) return it }
        val qw = frameW / 2; val qh = frameH / 2
        val cams = geo.cams
        val hw = geo.carWidth / 2; val hl = geo.carLength / 2
        val out = IntArray(size * size) { -1 }
        val half = size / 2.0
        for (j in 0 until size) for (i in 0 until size) {
            val x0 = (if (params.mirror) half - (i + 0.5) else (i + 0.5) - half) / focal
            val y0 = -((j + 0.5) - half) / focal
            val dx = f[0] + x0 * r[0] + y0 * u[0]; val dy = f[1] + x0 * r[1] + y0 * u[1]; val dz = f[2] + x0 * r[2] + y0 * u[2]
            val pt = hitBowl(dx, dy, dz) ?: continue
            if (pt[1] < 0.05 && kotlin.math.abs(pt[0]) < hw && kotlin.math.abs(pt[2]) < hl) continue   // 自車
            // 看得最正的鏡頭
            var best = -1; var bestAng = Double.MAX_VALUE
            for (c in cams) {
                val vx = pt[0] - c.px; val vy = pt[1] - c.py; val vz = pt[2] - c.pz
                val px = geo.dirToPixel(c, vx, vy, vz, qw, qh) ?: continue
                val m = c.rot
                val cz = m[2] * vx + m[5] * vy + m[8] * vz
                val cr = sqrt((m[0] * vx + m[3] * vy + m[6] * vz).let { it * it } + (m[1] * vx + m[4] * vy + m[7] * vz).let { it * it })
                val ang = atan2(cr, cz)
                if (ang < bestAng) {
                    bestAng = ang
                    best = ((c.quad / 2) * qh + px[1].toInt()) * frameW + (c.quad % 2) * qw + px[0].toInt()
                }
            }
            out[j * size + i] = best
        }
        lut = out; lutKey = key
        return out
    }
}
