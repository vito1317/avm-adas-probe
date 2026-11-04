package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

class ParkingPathPlannerTest {

    private val geo = VehicleGeometry()
    private val rMin = abs(BicycleModel.turningRadius(geo.maxSteerDeg, geo))
    private val back = ParkingPathPlanner.DEEP_GAP + geo.rearOverhang

    /**
     * 停車格：入口邊中點 (ex, ez)、格軸（入口 → 深處）方向 degA（從 +x 往 +z 量）、格寬 w、格深 d。
     * 四點逆時針：入口邊方向 = 格軸轉 −90°。flip = p1/p2 對調（入口邊方向相反）。
     */
    private fun slot(
        ex: Double, ez: Double, degA: Double, w: Double, d: Double,
        kind: Char = 'p', occupied: Boolean = false, flip: Boolean = false
    ): ParkingSlot {
        val a = degA * PI / 180.0
        val ax = cos(a); val az = sin(a)
        val ux = az; val uz = -ax
        val p1x = ex - w / 2 * ux; val p1z = ez - w / 2 * uz
        val p2x = ex + w / 2 * ux; val p2z = ez + w / 2 * uz
        val p3x = p2x + d * ax; val p3z = p2z + d * az
        val p4x = p1x + d * ax; val p4z = p1z + d * az
        val q = if (flip) doubleArrayOf(p2x, p2z, p1x, p1z, p3x, p3z, p4x, p4z)
        else doubleArrayOf(p1x, p1z, p2x, p2z, p3x, p3z, p4x, p4z)
        return ParkingSlot(FloatArray(8) { q[it].toFloat() }, kind, occupied, 0.9f)
    }

    /**
     * 自己的運動學積分器：把路徑每兩點之間當成一段圓弧（弦與航向的夾角 = 轉角的一半），
     * 用單車模型從起點姿態走過去 → [x, z, θ, 最大曲率, 前進段最大曲率]。
     * 順便檢查取樣密度、不可原地折返、積分出來的位置要落在路徑點上。
     */
    private fun replay(plan: ParkPlan): DoubleArray {
        val pts = plan.points
        assertTrue("路徑不可是空的", pts.isNotEmpty())
        assertEquals("起點 = 現在的後軸位置", 0.0, pts[0][0].toDouble(), 1e-6)
        assertEquals(geo.rearAxleZ, pts[0][1].toDouble(), 1e-6)
        var x = pts[0][0].toDouble(); var z = pts[0][1].toDouble(); var th = 0.0
        var kMax = 0.0; var kFwd = 0.0
        for (i in 1 until pts.size) {
            val dir = if (i <= plan.reverseFrom) 1.0 else -1.0
            val cx = pts[i][0] - x; val cz = pts[i][1] - z
            val len = hypot(cx, cz)
            assertTrue("點不可重複", len > 1e-6)
            assertTrue("取樣間距 ≤ ${ParkingPathPlanner.STEP} m", len <= ParkingPathPlanner.STEP + 1e-3)
            val hx = dir * sin(th); val hz = dir * cos(th)          // 行進方向
            val phi = atan2(-(hx * cz - hz * cx), hx * cx + hz * cz)
            assertTrue("同一段裡不可折返", abs(phi) < PI / 2)
            val arc = if (abs(phi) < 1e-9) len else len * phi / sin(phi)
            val u = dir * arc
            val k = 2 * phi / u
            kMax = max(kMax, abs(k))
            if (dir > 0) kFwd = max(kFwd, abs(k))
            if (abs(k) < 1e-9) { x += u * sin(th); z += u * cos(th) } else {
                x += (cos(th) - cos(th + k * u)) / k
                z += (sin(th + k * u) - sin(th)) / k
            }
            th += k * u
            assertEquals("積分結果要落在路徑點上", pts[i][0].toDouble(), x, 1e-3)
            assertEquals("積分結果要落在路徑點上", pts[i][1].toDouble(), z, 1e-3)
        }
        return doubleArrayOf(x, z, th, kMax, kFwd)
    }

    /** 終點姿態（位置 5 cm、角度 1°）與曲率上限。 */
    private fun checkEnd(plan: ParkPlan) {
        val e = replay(plan)
        assertEquals("終點 x", plan.goal[0].toDouble(), e[0], 0.05)
        assertEquals("終點 z", plan.goal[1].toDouble(), e[1], 0.05)
        assertEquals("終點 θ", plan.goal[2].toDouble(), e[2], PI / 180.0)
        assertTrue("曲率不超過車輛極限", e[3] <= 1.0 / rMin + 1e-6)
        assertTrue("前進段是直的", e[4] < 1e-3)
        assertTrue("迴轉半徑 ≥ Rmin", plan.turnRadius >= rMin - 1e-4)
    }

    @Test
    fun perpendicularRight() {
        val plan = ParkingPathPlanner.plan(slot(1.8, 0.0, 0.0, 2.6, 5.4), geo)!!
        assertTrue(plan.reason ?: "", plan.ok)
        // 目標：格子中線上、車頭朝格外（往 −x）、後保險桿離深處邊 0.3 m
        assertEquals(-PI / 2, plan.goal[2].toDouble(), 1e-6)
        assertEquals(1.8 + 5.4 - back, plan.goal[0].toDouble(), 1e-5)
        assertEquals(0.0, plan.goal[1].toDouble(), 1e-5)
        assertTrue("要先往前開", plan.reverseFrom > 0)
        checkEnd(plan)
    }

    /** 左右鏡像：路徑只差 x 的正負號。 */
    @Test
    fun perpendicularLeftMirrorsRight() {
        val r = ParkingPathPlanner.plan(slot(1.8, 0.0, 0.0, 2.6, 5.4), geo)!!
        val l = ParkingPathPlanner.plan(slot(-1.8, 0.0, 180.0, 2.6, 5.4), geo)!!
        assertTrue(l.ok)
        assertEquals(PI / 2, l.goal[2].toDouble(), 1e-6)
        assertEquals(-(1.8 + 5.4 - back), l.goal[0].toDouble(), 1e-5)
        checkEnd(l)
        assertEquals(r.points.size, l.points.size)
        assertEquals(r.reverseFrom, l.reverseFrom)
        assertEquals(r.turnRadius, l.turnRadius, 1e-5f)
        for (i in r.points.indices) {
            assertEquals(-r.points[i][0].toDouble(), l.points[i][0].toDouble(), 1e-3)
            assertEquals(r.points[i][1].toDouble(), l.points[i][1].toDouble(), 1e-3)
        }
    }

    /** 斜向格（軸線往右後 45°）：轉角小，比垂直格好倒。 */
    @Test
    fun angledSlot() {
        val plan = ParkingPathPlanner.plan(slot(1.2, 0.0, -45.0, 2.8, 5.0, kind = 'a'), geo)!!
        assertTrue(plan.reason ?: "", plan.ok)
        assertEquals(-PI / 4, plan.goal[2].toDouble(), 1e-6)
        checkEnd(plan)
    }

    /** 正後方的格子：不用轉，直接倒。 */
    @Test
    fun straightBehind() {
        val plan = ParkingPathPlanner.plan(slot(0.0, -2.0, -90.0, 2.6, 5.4), geo)!!
        assertTrue(plan.reason ?: "", plan.ok)
        assertEquals(0, plan.reverseFrom)
        assertEquals(0.0, plan.goal[2].toDouble(), 1e-6)
        assertEquals(0.0, plan.goal[0].toDouble(), 1e-6)
        assertEquals(-2.0 - 5.4 + back, plan.goal[1].toDouble(), 1e-5)
        assertTrue(plan.turnRadius.isInfinite())
        checkEnd(plan)
    }

    @Test
    fun narrowSlotRejected() {
        val plan = ParkingPathPlanner.plan(slot(1.8, 0.0, 0.0, geo.width + 0.1, 5.4), geo)!!
        assertFalse(plan.ok)
        assertTrue(plan.reason!!.isNotEmpty())
        assertTrue(plan.points.isEmpty())
        // 剛好夠寬就可以
        assertTrue(ParkingPathPlanner.plan(slot(1.8, 0.0, 0.0, geo.width + 0.21, 5.4), geo)!!.ok)
    }

    /** 格子太近（淺格、橫向距離不到最小迴轉半徑）：不給路徑，要說出還差多少。 */
    @Test
    fun tooCloseRejected() {
        val plan = ParkingPathPlanner.plan(slot(0.6, 0.0, 0.0, 2.6, 3.2), geo)!!
        assertFalse(plan.ok)
        assertTrue(plan.reason!!.contains("橫向"))
        assertTrue(plan.points.isEmpty())
    }

    /** 已經開過頭的格子：不給路徑。 */
    @Test
    fun slotBehindRejected() {
        val plan = ParkingPathPlanner.plan(slot(1.8, -12.0, 0.0, 2.6, 5.4), geo)!!
        assertFalse(plan.ok)
        assertTrue(plan.reason!!.contains("開過"))
    }

    /** 角度刁鑽：轉角太大（135°）與幾乎對正卻橫向差一點，都要明講。 */
    @Test
    fun awkwardAngleRejected() {
        val big = ParkingPathPlanner.plan(slot(2.0, 0.0, 45.0, 2.8, 5.0, kind = 'a'), geo)!!
        assertFalse(big.ok)
        assertTrue(big.reason!!.contains("135"))

        // 幾乎對正（差 5°）但橫向偏了：單弧對不準 → 要說「先把車對正」，不是硬給一條路徑
        val near = ParkingPathPlanner.plan(slot(0.6, -2.0, -95.0, 2.6, 5.4), geo)!!
        assertFalse(near.ok)
        assertTrue(near.reason!!.contains("對正"))
        assertTrue(near.points.isEmpty())

        // 差 0.2°：當成正後方，但橫向差 0.59 m 補不回來
        val snap = ParkingPathPlanner.plan(slot(0.6, -2.0, -90.2, 2.6, 5.4), geo)!!
        assertFalse(snap.ok)
        assertTrue(snap.reason!!.isNotEmpty())
    }

    /** 入口邊方向相反（p1/p2 對調）：不可默默算出反方向的路徑。 */
    @Test
    fun flippedEntryRejected() {
        assertNotNull(ParkingPathPlanner.plan(slot(1.8, 0.0, 0.0, 2.6, 5.4), geo))
        assertNull(ParkingPathPlanner.plan(slot(1.8, 0.0, 0.0, 2.6, 5.4, flip = true), geo))
        assertNull(ParkingPathPlanner.plan(slot(-1.8, 0.0, 180.0, 2.6, 5.4, flip = true), geo))
    }

    /** 平行格（S 形路徑）這裡做不到 → 明確回 null。 */
    @Test
    fun parallelSlotUnsupported() {
        assertNull(ParkingPathPlanner.plan(slot(1.8, 0.0, 0.0, 2.6, 5.4, kind = 'l'), geo))
        assertNull(ParkingPathPlanner.plan(slot(1.8, 0.0, 0.0, 2.6, 5.4, kind = '?'), geo))
    }

    @Test
    fun occupiedSlotRejected() {
        val plan = ParkingPathPlanner.plan(slot(1.8, 0.0, 0.0, 2.6, 5.4, occupied = true), geo)!!
        assertFalse(plan.ok)
        assertTrue(plan.reason!!.isNotEmpty())
    }

    /** 退化輸入：不可崩潰，也不可給出做不到的路徑。 */
    @Test
    fun degenerateInput() {
        assertNull(ParkingPathPlanner.plan(ParkingSlot(FloatArray(8), 'p', false, 0.5f), geo))
        assertNull(ParkingPathPlanner.plan(ParkingSlot(FloatArray(8) { Float.NaN }, 'p', false, 0.5f), geo))
        assertNull(ParkingPathPlanner.plan(ParkingSlot(FloatArray(4), 'p', false, 0.5f), geo))
        val inf = slot(1.8, 0.0, 0.0, 2.6, 5.4).p.copyOf().also { it[3] = Float.POSITIVE_INFINITY }
        assertNull(ParkingPathPlanner.plan(ParkingSlot(inf, 'p', false, 0.5f), geo))
        // 四點重疊、格子在車身底下、格子太淺
        assertNull(ParkingPathPlanner.plan(slot(1.0, 0.0, 0.0, 0.2, 0.2), geo))
        for (s in listOf(slot(-1.0, 0.0, 0.0, 2.6, 2.5), slot(-2.0, 0.0, 0.0, 2.6, 5.0), slot(0.0, 0.0, 0.0, 2.6, 1.5))) {
            val plan = ParkingPathPlanner.plan(s, geo)
            if (plan != null && plan.ok) checkEnd(plan) else assertTrue(plan == null || plan.reason!!.isNotEmpty())
        }
    }

    /** 一排垂直格：能倒的都要真的倒得進去，不能倒的要有原因。 */
    @Test
    fun sweepRowOfSlots() {
        var okCount = 0
        for (i in 0..24) {
            val z = -6.0 + 0.5 * i
            for (sideDeg in listOf(0.0, 180.0)) {
                val ex = if (sideDeg == 0.0) 2.0 else -2.0
                val plan = ParkingPathPlanner.plan(slot(ex, z, sideDeg, 2.6, 5.5), geo)!!
                if (plan.ok) { okCount++; checkEnd(plan) } else assertTrue(plan.reason!!.isNotEmpty())
            }
        }
        assertTrue("一排格子裡要有一大半可行", okCount > 30)
    }
}
