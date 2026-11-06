package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 用真實俯視圖（實車窄巷那張）合成一張「原廠畫面」：左邊俯視圖、右邊 3D，參數已知；
 * 再讓自動對位去找，看找回來的投影跟真值差多少。
 * 原廠畫面的自車位置畫一塊白色車模、俯視圖以外的地方是雜訊，模擬原廠畫的車模與碗狀遠景。
 */
class AutoCalibratorTest {

    private val bev: AutoCalibrator.Bev = run {
        val img = PerceptionFixtures.image("quad_street.png")
        AutoCalibrator.Bev(img.argb, img.w, img.h, 0.04f, 150f, 175f)
    }
    private val sw = 1920; private val sh = 720

    private fun bevColor(x: Float, z: Float): Int? {
        val px = (bev.ox + x / bev.mpp).toInt(); val py = (bev.oy - z / bev.mpp).toInt()
        if (px !in 0 until bev.w || py !in 0 until bev.h) return null
        val c = bev.argb[py * bev.w + px]
        return if ((c and 0xFFFFFF) == 0) null else c
    }

    private fun noise(x: Int, y: Int): Int {
        val n = ((x * 73856093) xor (y * 19349663)) and 0x3F
        val g = 90 + n
        return (0xFF shl 24) or (g shl 16) or (g shl 8) or g
    }

    private fun synth(top: AnnotationMapper.Params?, p3: PerspectiveMapper.Params?): IntArray {
        val out = IntArray(sw * sh)
        val tm = top?.let { AnnotationMapper(it, sw, sh) }
        val pm = p3?.let { PerspectiveMapper(it, sw, sh) }
        for (y in 0 until sh) for (x in 0 until sw) {
            var c = noise(x, y)
            if (tm != null && x < tm.clipRight && x >= tm.clipLeft) {
                val wx = (x - tm.sx(0f, 0f)) / tm.pixelsPerMeter * (if (top.flip) -1 else 1)
                val wz = -(y - tm.sy(0f, 0f)) / tm.pixelsPerMeter * (if (top.flip) -1 else 1)
                c = if (abs(wx) < 1.0f && abs(wz) < 2.5f) 0xFFF0F0F0.toInt() else bevColor(wx, wz) ?: c
            } else if (pm != null && x >= pm.clipLeft && x < pm.clipRight) {
                val g = pm.unprojectToGround(x + 0.5f, y + 0.5f)
                c = if (g == null) 0xFFB0C0D0.toInt()
                else if (abs(g[0]) < 1.0f && abs(g[1]) < 2.5f) 0xFFF0F0F0.toInt()
                else bevColor(g[0], g[1]) ?: c
            }
            out[y * sw + x] = c
        }
        return out
    }

    private fun screen(argb: IntArray) = AutoCalibrator.Screen(argb, sw, sh, 0, 0, sw, sh, sw, sh)

    @Test
    fun findsTopView() {
        val truth = AnnotationMapper.Params(0f, 0f, 0.38f, 1f, cx = 0.19f, cy = 0.48f, scale = 0.085f, flip = false)
        val r = AutoCalibrator.calibrateTop(screen(synth(truth, null)), bev)
        assertNotNull("要找得到俯視圖", r)
        println("TOP score=${r!!.score} ${r.params}")
        assertEquals(truth.cx, r.params.cx, 0.006f)
        assertEquals(truth.cy, r.params.cy, 0.012f)
        assertEquals(truth.scale, r.params.scale, truth.scale * 0.04f)
        assertEquals(false, r.params.flip)
    }

    @Test
    fun findsFlippedTopView() {
        val truth = AnnotationMapper.Params(0f, 0f, 0.38f, 1f, cx = 0.2f, cy = 0.5f, scale = 0.07f, flip = true)
        val r = AutoCalibrator.calibrateTop(screen(synth(truth, null)), bev)!!
        assertEquals(true, r.params.flip)
        assertEquals(truth.cx, r.params.cx, 0.006f)
        assertEquals(truth.scale, r.params.scale, truth.scale * 0.04f)
    }

    /** 左邊 38.2% 俯視、右邊 3D：要切出俯視格的範圍，剩下的就是 3D 格。 */
    @Test
    fun findsTopPaneSplit() {
        val top = AnnotationMapper.Params(0f, 0f, 0.382f, 1f, 0.19f, 0.48f, 0.085f, false)
        val p3 = PerspectiveMapper.Params(0.382f, 0f, 1f, 1f, 32f, 20f, 9.5f, 52f, -2.2f, 3.5f)
        val scr = screen(synth(top, p3))
        val r = AutoCalibrator.calibrateTop(scr, bev)!!
        val pane = AutoCalibrator.topPane(scr, r.params, bev)
        println("PANE " + pane.joinToString { "%.3f".format(it) })
        assertEquals(0f, pane[0], 0.02f)
        assertEquals(0.382f, pane[2], 0.03f)
    }

    @Test
    fun noTopViewInPureNoise() {
        val noiseOnly = IntArray(sw * sh) { noise(it % sw, it / sw) }
        assertTrue(AutoCalibrator.calibrateTop(screen(noiseOnly), bev) == null)
    }

    private fun check3d(truth: PerspectiveMapper.Params, tag: String) {
        val top = AnnotationMapper.Params(0f, 0f, 0.38f, 1f, 0.19f, 0.48f, 0.085f, false)
        val r = AutoCalibrator.calibrate3d(screen(synth(top, truth)), floatArrayOf(truth.clipL, truth.clipT, truth.clipR, truth.clipB), bev)
        assertNotNull("$tag：要找得到", r)
        val mt = PerspectiveMapper(truth, sw, sh); val mr = PerspectiveMapper(r!!.params, sw, sh)
        var n = 0; var sum = 0f; var worst = 0f
        for (x in listOf(-4f, -2.5f, -1.5f, 1.5f, 2.5f, 4f)) for (z in listOf(-5f, -3f, -1f, 1f, 3f, 5f)) {
            if (abs(x) < 1.2f && abs(z) < 2.7f) continue
            val a = mt.project(x, 0f, z) ?: continue
            if (a[0] < truth.clipL * sw || a[0] > truth.clipR * sw || a[1] < 0 || a[1] > sh) continue
            val b = mr.project(x, 0f, z) ?: continue
            val e = hypot(a[0] - b[0], a[1] - b[1]); sum += e; worst = maxOf(worst, e); n++
        }
        println("$tag score=${r.score} ${r.params} 平均 ${"%.1f".format(sum / n)} px 最大 ${"%.1f".format(worst)} px（$n 點）")
        assertTrue("$tag 點太少：$n", n >= 5)
        assertTrue("$tag 平均誤差 ${sum / n}", sum / n < sh * 0.02f)
    }

    /** 倒車 3D：從車後上方往前看。 */
    @Test
    fun findsRear3dView() = check3d(PerspectiveMapper.Params(0.38f, 0f, 1f, 1f, azDeg = 175f, elDeg = 30f, dist = 8f, fovDeg = 60f, tx = 0f, tz = -1.5f), "倒車3D")

    /**
     * 側邊展開（左後照鏡下的鏡頭往外、往下看）：鏡頭在 (−1.0, 1.0, 0.9)，看向外側 1.2 m 的地面，廣角。
     * 換成繞車鏡頭的參數：看向點 (−2.2, 0.9)、鏡頭在它右邊（方位 +90°）、俯角 40°、距離 1.56 m。
     */
    @Test
    fun findsSideCameraView() = check3d(PerspectiveMapper.Params(0.38f, 0f, 1f, 1f, azDeg = 90f, elDeg = 40f, dist = 1.56f, fovDeg = 100f, tx = -2.2f, tz = 0.9f), "側邊展開")

    /** 3D：參數可能有等效解（距離與視角互換），所以比的是「地面點投影到螢幕的位置」。 */
    @Test
    fun finds3dView() {
        val top = AnnotationMapper.Params(0f, 0f, 0.38f, 1f, 0.19f, 0.48f, 0.085f, false)
        val truth = PerspectiveMapper.Params(0.38f, 0f, 1f, 1f, azDeg = 32f, elDeg = 20f, dist = 9.5f, fovDeg = 52f, tx = -2.2f, tz = 3.5f)
        val r = AutoCalibrator.calibrate3d(screen(synth(top, truth)), floatArrayOf(0.38f, 0f, 1f, 1f), bev)
        assertNotNull("要找得到 3D 視角", r)
        println("3D score=${r!!.score} ${r.params}")
        val mt = PerspectiveMapper(truth, sw, sh); val mr = PerspectiveMapper(r.params, sw, sh)
        var worst = 0f; var n = 0; var sum = 0f
        for (x in listOf(-3f, -1.5f, 1.5f, 3f)) for (z in listOf(-4f, -1f, 2f, 5f)) {
            val a = mt.project(x, 0f, z) ?: continue
            if (a[0] < 0.38f * sw || a[0] > sw || a[1] < 0 || a[1] > sh) continue
            val b = mr.project(x, 0f, z) ?: run { worst = Float.MAX_VALUE; null } ?: continue
            val e = hypot(a[0] - b[0], a[1] - b[1]); worst = maxOf(worst, e); sum += e; n++
        }
        println("3D 重投影誤差 平均 ${"%.1f".format(sum / n)} px、最大 ${"%.1f".format(worst)} px（$n 點）")
        assertTrue(n >= 6)
        assertTrue("平均誤差要小於 2% 畫面高，實際 ${sum / n}", sum / n < sh * 0.02f)
        assertTrue("最大誤差（最遠、畫面邊緣的點）要小於 4% 畫面高，實際 $worst", worst < sh * 0.04f)
    }
}
