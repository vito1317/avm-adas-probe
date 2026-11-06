package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class TurnPathPlannerTest {

    private val geo = VehicleGeometry()

    /** 打右燈、方向盤往右 → 路徑要往右前方掃出去。 */
    @Test
    fun rightTurnGoesRight() {
        val p = TurnPathPlanner.plan('R', 180f, 10f, geo)
        assertNotNull("打右燈又轉方向盤，應該要有路徑", p)
        assertEquals('R', p!!.dir)
        val last = p.pts.last()
        assertTrue("終點要在右前方，實際 (${last[0]}, ${last[1]})", last[0] > 1.0f && last[1] > 0f)
        // 右轉的外側是**左**前角（離轉彎中心最遠）：起點貼著車身左前
        val first = p.pts.first()
        assertEquals(-geo.width.toFloat() / 2, first[0], 0.05f)
        assertEquals(geo.length.toFloat() / 2, first[1], 0.05f)
        // 內側後輪是**右**後輪
        assertEquals(geo.width.toFloat() / 2, p.inner.first()[0], 0.05f)
    }

    /** 左右要對稱。 */
    @Test
    fun leftIsMirrorOfRight() {
        val r = TurnPathPlanner.plan('R', 200f, 10f, geo)!!
        val l = TurnPathPlanner.plan('L', -200f, 10f, geo)!!
        assertEquals(r.pts.size, l.pts.size)
        assertEquals(r.radius, l.radius, 1e-3f)
        for (i in r.pts.indices) {
            assertEquals("第 $i 點的 x 要鏡像", -r.pts[i][0], l.pts[i][0], 1e-3f)
            assertEquals("第 $i 點的 z 要一樣", r.pts[i][1], l.pts[i][1], 1e-3f)
        }
    }

    /**
     * 內輪差：內側後輪一路都比外側車頭角更靠近轉彎中心。
     * 轉彎中心在後軸那條線上、轉向那一側 r 處：右轉 = (r, rearAxleZ)。
     */
    @Test
    fun innerWheelCutsInside() {
        val p = TurnPathPlanner.plan('R', 250f, 8f, geo)!!
        val cx = p.radius; val cz = geo.rearAxleZ.toFloat()
        var checked = 0
        for (i in p.pts.indices) {
            val o = p.pts[i]
            val inn = p.inner[i]
            val ro = hypot(o[0] - cx, o[1] - cz)
            val ri = hypot(inn[0] - cx, inn[1] - cz)
            assertTrue("第 $i 點內側後輪沒有比外側車頭角靠近中心：外 $ro 內 $ri", ri < ro)
            // 內側後輪就在後軸、轉向那一側：半徑 = r − 半車寬（剛體繞中心轉，一路不變）
            assertEquals(p.radius - geo.width.toFloat() / 2, ri, 0.02f)
            checked++
        }
        assertTrue(checked > 5)
        // 轉到後段，內外差距（內輪差）要明顯拉開
        val k = p.pts.size - 1
        val gap = hypot(p.pts[k][0] - p.inner[k][0], p.pts[k][1] - p.inner[k][1])
        assertTrue("內輪差應該明顯，實際 $gap m", gap > 1.0f)
    }

    /** 方向盤打得越大，轉彎半徑越小。 */
    @Test
    fun sharperSteerSmallerRadius() {
        val a = TurnPathPlanner.plan('R', 100f, 10f, geo)!!
        val b = TurnPathPlanner.plan('R', 300f, 10f, geo)!!
        assertTrue("打更大應該半徑更小：${a.radius} vs ${b.radius}", b.radius < a.radius)
    }

    /** 打了燈但方向盤還沒轉 → 不畫（那時候要畫在哪只能用猜的）。 */
    @Test
    fun noPathBeforeSteering() {
        assertNull(TurnPathPlanner.plan('R', 5f, 10f, geo))
        assertNull(TurnPathPlanner.plan('R', 0f, 10f, geo))
    }

    /** 沒打方向燈就不是這條線要處理的情況。 */
    @Test
    fun needsTurnSignal() {
        assertNull(TurnPathPlanner.plan(null, 200f, 10f, geo))
        assertNull(TurnPathPlanner.plan('H', 200f, 10f, geo))
    }

    /** 拿不到方向盤角度就畫不出來（不要猜）。 */
    @Test
    fun needsSteerAngle() {
        assertNull(TurnPathPlanner.plan('R', null, 10f, geo))
    }

    /** 方向盤跟方向燈反向 → 那是閃避或回正，不是這個燈要的轉彎。 */
    @Test
    fun steerMustMatchSignal() {
        assertNull(TurnPathPlanner.plan('R', -200f, 10f, geo))
        assertNull(TurnPathPlanner.plan('L', 200f, 10f, geo))
    }

    /** 高速打燈是變道，不是路口轉彎。 */
    @Test
    fun notAtHighSpeed() {
        assertNull(TurnPathPlanner.plan('R', 200f, 80f, geo))
        assertNotNull("沒有車速訊號時還是要畫", TurnPathPlanner.plan('R', 200f, null, geo))
    }

    /** 走廊裡的機車要被抓出來（內輪差捲入）。 */
    @Test
    fun findsScooterInCorridor() {
        val p = TurnPathPlanner.plan('R', 250f, 8f, geo)!!
        // 取路徑中段內外側的中點，那一定在走廊裡
        val k = p.pts.size / 2
        val mx = (p.pts[k][0] + p.inner[k][0]) / 2
        val mz = (p.pts[k][1] + p.inner[k][1]) / 2
        val inside = StartAssist.Obj("moto", mx, mz)
        val outside = StartAssist.Obj("moto", -8f, -8f)
        val hit = TurnPathPlanner.conflicts(p, listOf(outside, inside))
        assertEquals("只有走廊裡那台要被抓到", 1, hit.size)
        assertEquals(mx, hit[0].x, 1e-3f)
    }

    /** 路徑長度要接近設定值（不是算到爆或只畫一點點）。 */
    @Test
    fun pathLengthIsSane() {
        val p = TurnPathPlanner.plan('R', 200f, 10f, geo)!!
        var len = 0.0
        for (i in 1 until p.inner.size) {
            len += hypot(
                (p.inner[i][0] - p.inner[i - 1][0]).toDouble(),
                (p.inner[i][1] - p.inner[i - 1][1]).toDouble()
            )
        }
        assertTrue("內側路徑長 $len m 不合理", len > 4.0 && len < TurnPathPlanner.LEN_M * 1.5)
        assertTrue("點數不合理：${p.pts.size}", p.pts.size in 4..200)
        for (q in p.pts) assertTrue("座標不是有限值", q[0].isFinite() && q[1].isFinite())
    }

    /** 轉彎半徑要跟自行車模型一致。 */
    @Test
    fun radiusMatchesBicycleModel() {
        val steer = 200f
        val p = TurnPathPlanner.plan('R', steer, 10f, geo)!!
        val wheel = steer / geo.steerRatio
        val expect = geo.wheelbase / kotlin.math.tan(Math.toRadians(wheel))
        assertEquals(expect.toFloat(), p.radius, 0.01f)
        assertTrue("半徑要是正的", p.radius > 0f)
        assertTrue("打 200° 的半徑應該在合理範圍，實際 ${p.radius}", abs(p.radius) in 3f..40f)
    }
}

class TurnSweptPolygonTest {

    @org.junit.Test
    fun endsCloseAlongTheCarBodyNotDiagonally() {
        val tp = TurnPathPlanner.plan('R', 200f, 10f)!!
        val poly = tp.sweptPolygon()
        org.junit.Assert.assertEquals(tp.pts.size + tp.inner.size + 2, poly.size)
        // 起點收口：內側後輪 → 內側車頭角 → 外側車頭角，三點都在現在的車身上
        val geo = VehicleGeometry()
        val start = listOf(poly.last(), poly[0], tp.inner[0])   // 內側車頭角、外側車頭角、內側後輪
        for (q in start) {
            org.junit.Assert.assertTrue(kotlin.math.abs(q[0]) <= geo.width / 2 + 1e-3)
            org.junit.Assert.assertTrue(q[1] <= geo.length / 2 + 1e-3 && q[1] >= geo.rearAxleZ - 1e-3)
        }
        // 內側車頭角跟內側後輪在同一側（右轉的內側是右邊：x > 0）
        org.junit.Assert.assertTrue(poly.last()[0] > 0f && tp.inner[0][0] > 0f && poly[0][0] < 0f)
    }
}
