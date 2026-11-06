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
            /** 依實車照片估的初值：左邊約 3 成是俯視圖、車模約佔那格高度的 6 成。實際要在車上微調。 */
            val DEFAULT = Params(
                clipL = 0f, clipT = 0f, clipR = 0.31f, clipB = 0.88f,
                cx = 0.155f, cy = 0.45f, scale = 0.105f, flip = false
            )
        }
    }

    companion object {
        const val EGO_HW = 0.925f
        const val EGO_HL = 2.42f
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
