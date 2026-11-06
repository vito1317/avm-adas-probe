package com.vito.avmprobe

/**
 * 世界座標（公尺；x 向右、z 向前，自車中心為原點）→ 原廠 360 俯視圖在螢幕上的像素。
 *
 * 原廠俯視圖的比例尺與位置我們拿不到，只能讓使用者對：把我們畫的自車外框（[EGO_HW]×[EGO_HL]）
 * 調到跟原廠的車模重疊，就對準了。參數都用螢幕比例，換解析度不用重調。
 *
 * - [Params.cx]/[Params.cy]：自車中心在螢幕的位置（寬／高的比例）。
 * - [Params.scale]：1 公尺 = 螢幕高度的多少比例。
 * - [Params.clip*]：俯視圖那一格的範圍（只畫在裡面，不要畫到原廠的鏡頭畫面或按鈕上）。
 * - [Params.flip]：原廠俯視圖車頭朝下時打開。
 */
class AnnotationMapper(val p: Params, val screenW: Int, val screenH: Int) {

    data class Params(
        val clipL: Float, val clipT: Float, val clipR: Float, val clipB: Float,
        val cx: Float, val cy: Float, val scale: Float, val flip: Boolean
    ) {
        companion object {
            /**
             * 原廠俯視圖（BVSV 版面）：用實車截圖自動對位找出來的（3 張都一致），
             * 俯視格在螢幕 x 0～34.8%、下緣到工具列上方（86.8%）。
             */
            val DEFAULT = Params(
                clipL = 0f, clipT = 0f, clipR = 0.348f, clipB = 0.868f,
                cx = 0.177f, cy = 0.434f, scale = 0.097f, flip = false
            )
        }
    }

    companion object {
        const val EGO_HW = 0.925f
        const val EGO_HL = 2.42f
        /** 後軸中心到車身中心的距離（車長 4.84、後懸 1.12 → 後軸在 z = -1.30）。 */
        const val AXLE_TO_CENTER = 1.30f

        /**
         * 走廊外框：沿中心線（世界座標 [x, z]）往左右各推 [halfWidth] 公尺。
         * 回傳一個閉合多邊形的頂點：左緣由頭到尾、再接右緣由尾到頭。少於 2 點回傳空。
         * 法向量用前後兩點的切線（端點用單邊），轉彎處不會折斷。
         */
        fun corridor(center: List<FloatArray>, halfWidth: Float): List<FloatArray> {
            val pts = center.filter { it.size >= 2 }
            if (pts.size < 2) return emptyList()
            val left = ArrayList<FloatArray>(pts.size)
            val right = ArrayList<FloatArray>(pts.size)
            for (i in pts.indices) {
                val a = pts[maxOf(i - 1, 0)]; val b = pts[minOf(i + 1, pts.size - 1)]
                var tx = b[0] - a[0]; var tz = b[1] - a[1]
                val len = kotlin.math.hypot(tx, tz)
                if (len < 1e-6f) { tx = 0f; tz = 1f } else { tx /= len; tz /= len }
                // 切線 (tx, tz) 的左側法向量：x 右、z 前 → 左 = (-tz, tx)
                val nx = -tz; val nz = tx
                val p = pts[i]
                left.add(floatArrayOf(p[0] + nx * halfWidth, p[1] + nz * halfWidth))
                right.add(floatArrayOf(p[0] - nx * halfWidth, p[1] - nz * halfWidth))
            }
            return left + right.asReversed()
        }
    }

    private val ppm = p.scale * screenH
    private val ox = p.cx * screenW
    private val oy = p.cy * screenH
    private val sign = if (p.flip) -1f else 1f

    val clipLeft get() = p.clipL * screenW
    val clipTop get() = p.clipT * screenH
    val clipRight get() = p.clipR * screenW
    val clipBottom get() = p.clipB * screenH

    /** 每公尺幾個像素。 */
    val pixelsPerMeter: Float get() = ppm

    fun sx(x: Float, z: Float): Float = ox + sign * x * ppm
    fun sy(x: Float, z: Float): Float = oy - sign * z * ppm

    /** 世界座標的方向角（atan2(x, z)，0 = 正前方）→ 螢幕上的旋轉角（度，順時針為正）。 */
    fun screenAngleDeg(yaw: Float): Float = Math.toDegrees(yaw.toDouble()).toFloat() + if (p.flip) 180f else 0f

    fun inside(px: Float, py: Float): Boolean =
        px in clipLeft..clipRight && py in clipTop..clipBottom
}
