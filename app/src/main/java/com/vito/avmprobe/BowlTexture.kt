package com.vito.avmprobe

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 中控台 3D 道路畫面「實景模式」用的碗面貼圖：把四顆鏡頭的畫面貼到自車周圍的一個碗上，拼成一張小圖傳給中控台。
 *
 * 碗（跟中控台 bev3d/real3d.js 用同一組參數建網格）：貼圖橫向 = 繞車一圈的方位角 θ（第 0 欄 = 正前方，順時針），
 * 縱向 = 從中心往外（ρ 0→1）：ρ < [RF] 是平的地面（半徑 0 → [FLAT_R]）；之後是碗壁（半徑 [FLAT_R] → [R_MAX]、
 * 高度 = [K]·(r − [FLAT_R])²），遠處的車、樓才不會被壓扁在地上。
 * 每個貼圖像素：碗上的 3D 點 → 看得最正的實體鏡頭 → 整幀像素索引（查表只在校正改變時算一次；每幀只是查表取色）。
 * 自車範圍內 = -1（黑，中控台會畫 E60 蓋住）。只用來顯示，不拿去控制車子。
 */
class BowlTexture(private val geo: AvmGeometry) {
    companion object {
        const val W = 640
        const val H = 240
        const val RF = 0.5          // 前半段是地面
        const val FLAT_R = 5.0
        const val R_MAX = 14.0
        const val K = 0.15

        /** 貼圖 (i, j) 對應的碗上 3D 點（車身座標：x 右、y 上、z 前）。 */
        fun point(i: Int, j: Int): DoubleArray {
            val th = 2 * Math.PI * (i + 0.5) / W
            val rho = (j + 0.5) / H
            val r: Double; val y: Double
            if (rho < RF) { r = rho / RF * FLAT_R; y = 0.0 }
            else { r = FLAT_R + (rho - RF) / (1 - RF) * (R_MAX - FLAT_R); y = K * (r - FLAT_R) * (r - FLAT_R) }
            return doubleArrayOf(r * sin(th), y, r * cos(th))
        }
    }

    private var lut: IntArray? = null
    private var key = 0L

    /** 查表：貼圖像素（row-major，W×H）→ 整幀像素索引；看不到或在自車範圍 = -1。 */
    fun lut(frameW: Int, frameH: Int): IntArray {
        val k = (frameW.toLong() shl 32) or frameH.toLong()
        lut?.let { if (key == k) return it }
        val qw = frameW / 2; val qh = frameH / 2
        val hw = geo.carWidth / 2; val hl = geo.carLength / 2
        val out = IntArray(W * H) { -1 }
        for (j in 0 until H) for (i in 0 until W) {
            val p = point(i, j)
            if (p[1] < 0.05 && kotlin.math.abs(p[0]) < hw && kotlin.math.abs(p[2]) < hl) continue
            var best = -1; var bestAng = Double.MAX_VALUE
            for (c in geo.cams) {
                val vx = p[0] - c.px; val vy = p[1] - c.py; val vz = p[2] - c.pz
                val px = geo.dirToPixel(c, vx, vy, vz, qw, qh) ?: continue
                val m = c.rot
                val cz = m[2] * vx + m[5] * vy + m[8] * vz
                val cr = sqrt((m[0] * vx + m[3] * vy + m[6] * vz).let { it * it } + (m[1] * vx + m[4] * vy + m[7] * vz).let { it * it })
                val ang = atan2(cr, cz)
                if (ang < bestAng) { bestAng = ang; best = ((c.quad / 2) * qh + px[1].toInt()) * frameW + (c.quad % 2) * qw + px[0].toInt() }
            }
            out[j * W + i] = best
        }
        lut = out; key = k
        return out
    }
}
