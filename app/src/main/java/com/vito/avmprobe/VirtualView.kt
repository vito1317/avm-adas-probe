package com.vito.avmprobe

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 去畸變的虛擬鏡頭：跟某顆魚眼鏡頭放在**同一個光學中心**，朝 [yawDeg]/[pitchDeg] 看、水平視角 [hfovDeg]，
 * 輸出 [size]×[size] 的一般透視影像（像原廠的前鏡頭畫面那樣）。
 *
 * 為什麼要這樣：魚眼原圖裡遠方的車、紅綠燈被壓得很小又變形，YOLO 是用一般照片訓練的，認不太出來。
 * 先去畸變（而且可以把視角收窄 → 等於放大遠處）再偵測，前方的車和紅綠燈會好認很多。
 * 光學中心一樣，所以這只是「旋轉＋重新投影」，是精確的，不是近似。
 *
 * 座標：車身 x 右、y 上、z 前。[pitchDeg] > 0 = 往下看，< 0 = 往上看（紅綠燈）；[yawDeg] > 0 = 往右。
 */
class VirtualView(
    val geo: AvmGeometry,
    val camName: String,
    val yawDeg: Double,
    val pitchDeg: Double,
    val hfovDeg: Double,
    val size: Int
) {
    val cam: CamParams = geo.cam(camName) ?: error("沒有 $camName 鏡頭")
    private val f = (size / 2.0) / tan(Math.toRadians(hfovDeg / 2.0))
    private val cp = cos(Math.toRadians(pitchDeg)); private val sp = sin(Math.toRadians(pitchDeg))
    private val cyw = cos(Math.toRadians(yawDeg)); private val syw = sin(Math.toRadians(yawDeg))

    private var lutKey = 0L
    private var lut: IntArray? = null

    /** 虛擬影像像素 (u, v)（連續座標）→ 車身座標的單位方向。 */
    fun rayAt(u: Double, v: Double): DoubleArray {
        // 虛擬鏡頭座標：x 右、y 上、z 前
        var x = (u - size / 2.0) / f
        var y = -(v - size / 2.0) / f
        var z = 1.0
        // 俯仰（繞 x 軸，往下為正）：前方 (0,0,1) → (0, −sin p, cos p)
        val y1 = y * cp - z * sp
        val z1 = y * sp + z * cp
        y = y1; z = z1
        // 偏航（繞 y 軸，往右為正）：前方 (0,0,1) → (sin ψ, 0, cos ψ)
        val x2 = x * cyw + z * syw
        val z2 = -x * syw + z * cyw
        x = x2; z = z2
        val n = sqrt(x * x + y * y + z * z)
        return doubleArrayOf(x / n, y / n, z / n)
    }

    /** 虛擬影像像素 → 地面點 (x, z)；射線沒往下 → null。 */
    fun groundAt(u: Double, v: Double): DoubleArray? {
        val r = rayAt(u, v)
        if (r[1] > -1e-4) return null
        val t = cam.py / -r[1]
        return doubleArrayOf(cam.px + t * r[0], cam.pz + t * r[2])
    }

    /**
     * 查表：虛擬影像每個像素（row-major）→ 原始 2x2 整幀的像素索引，看不到 = -1。
     * 只依整幀尺寸變，算一次留著。
     */
    fun lut(frameW: Int, frameH: Int): IntArray {
        val key = (frameW.toLong() shl 32) or frameH.toLong()
        lut?.let { if (lutKey == key) return it }
        val qw = frameW / 2; val qh = frameH / 2
        val qx0 = (cam.quad % 2) * qw; val qy0 = (cam.quad / 2) * qh
        val out = IntArray(size * size)
        for (j in 0 until size) for (i in 0 until size) {
            val r = rayAt(i + 0.5, j + 0.5)
            val px = geo.dirToPixel(cam, r[0], r[1], r[2], qw, qh)
            out[j * size + i] = if (px == null) -1
            else (qy0 + px[1].toInt()) * frameW + qx0 + px[0].toInt()
        }
        lut = out; lutKey = key
        return out
    }
}
