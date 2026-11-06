package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

class VirtualViewTest {

    private val geo: AvmGeometry = AvmGeometry.fromJson(
        listOf("src/main/assets/avm_calib.json", "app/src/main/assets/avm_calib.json")
            .map { File(it) }.first { it.exists() }.readText()
    )

    @Test
    fun centerLooksWhereAimed() {
        val v = VirtualView(geo, "front", 0.0, 0.0, 60.0, 320)
        val r = v.rayAt(160.0, 160.0)
        assertEquals(0.0, r[0], 1e-9); assertEquals(0.0, r[1], 1e-9); assertEquals(1.0, r[2], 1e-9)
        val down = VirtualView(geo, "front", 0.0, 10.0, 60.0, 320).rayAt(160.0, 160.0)
        assertTrue("往下看 → 中心射線 y < 0", down[1] < 0)
        val up = VirtualView(geo, "front", 0.0, -15.0, 40.0, 320).rayAt(160.0, 160.0)
        assertTrue("往上看（紅綠燈）→ 中心射線 y > 0", up[1] > 0)
        val right = VirtualView(geo, "front", 30.0, 0.0, 60.0, 320).rayAt(160.0, 160.0)
        assertTrue("往右看 → x > 0", right[0] > 0)
    }

    @Test
    fun imageRightIsWorldRightAndImageDownIsWorldDown() {
        val v = VirtualView(geo, "front", 0.0, 0.0, 60.0, 320)
        assertTrue(v.rayAt(300.0, 160.0)[0] > 0)
        assertTrue(v.rayAt(160.0, 300.0)[1] < 0)
    }

    /** 去畸變後的地面點，跟直接從魚眼原圖算的地面點要是同一點（同一個光學中心、純旋轉重投影）。 */
    @Test
    fun groundPointAgreesWithFisheye() {
        val v = VirtualView(geo, "front", 0.0, 5.0, 70.0, 320)
        val fw = 1920; val fh = 1080; val qw = fw / 2; val qh = fh / 2
        var checked = 0
        for ((u, w) in listOf(160.0 to 250.0, 100.0 to 280.0, 230.0 to 300.0, 160.0 to 200.0)) {
            val g = v.groundAt(u, w) ?: continue
            val r = v.rayAt(u, w)
            val px = geo.dirToPixel(v.cam, r[0], r[1], r[2], qw, qh) ?: continue
            val g2 = geo.pixelToGround(v.cam, px[0], px[1], qw, qh) ?: continue
            assertEquals("x 不一致 @($u,$w)", g[0], g2[0], 0.02 * hypot(g[0], g[1]) + 0.02)
            assertEquals("z 不一致 @($u,$w)", g[1], g2[1], 0.02 * hypot(g[0], g[1]) + 0.02)
            checked++
        }
        assertTrue("至少要驗到 3 點，實際 $checked", checked >= 3)
    }

    @Test
    fun aboveHorizonHasNoGroundPoint() {
        val v = VirtualView(geo, "front", 0.0, 0.0, 60.0, 320)
        assertNull(v.groundAt(160.0, 20.0))
        assertNotNull(v.groundAt(160.0, 310.0))
    }

    @Test
    fun lutCoversMostOfTheForwardView() {
        val v = VirtualView(geo, "front", 0.0, 5.0, 70.0, 160)
        val lut = v.lut(1920, 1080)
        val seen = lut.count { it >= 0 }
        assertTrue("前方 70° 視角大部分都該看得到，實際 ${seen * 100 / lut.size}%", seen > lut.size * 0.9)
        // 都落在左上（前鏡頭）象限
        for (p in lut) if (p >= 0) { assertTrue(p % 1920 < 960 && p / 1920 < 540) }
        assertTrue(abs(lut[80 * 160 + 80]) >= 0)
    }
}
