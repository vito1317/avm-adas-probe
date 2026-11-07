package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 原廠 Lua 視角設定的解析與換算（期望值由另一份獨立實作原廠 FormatView 公式算出）。 */
class FactoryViewsTest {

    // 只取跟原廠設定檔格式相同的片段（數值為測試用）
    private val setting = """
        Common =
        {
            SvBv = 0,
            FOV = 35,
        }
        Side3DParam =
        {
            Left =
            {
                Yaw=30,
                Pitch=15,
                EyeDist=7500,
                Mirror=false,
                CenPos={0, 500, -500},
                CarOffset={0, 0, 0},
                FOV = 32,
            },
        }
        Rotate3DParam =
        {
            Left =
            {
                Yaw=145,
                Pitch=16,
                EyeDist=8000,
                Mirror=true,
                CenPos={0, 200, -1200},
                FOV = 32,
            },
            Rear =
            {
                Pitch = 30, Yaw = 0, EyeDist = 8000, CenPos = { 0, 0, 0,},
            },
        }
    """.trimIndent()

    private val scene = """
        function DefineScene()
            local scene = {
                [5] = -- middle speed left
                {
                    Viewport = 2,
                    Contents = { { Views = { 1 }, }, { TransParam = Side3DParam.Left, }, },
                },
                [25] = -- low speed left
                {
                    Viewport = 2,
                    Contents = { { Views = { 34 }, }, },
                },
                [35] = {
                    Viewport = 2,
                    Contents = { { Views = { 1 } }, { TransParam = Rotate3DParam.Left } },
                },
                [37] = {
                    Viewport = 2,
                    Contents = { { Views = { 1 } }, { TransParam = Rotate3DParam.Rear } },
                },
            }
        end
    """.trimIndent()

    private fun near(exp: Float, act: Float, tol: Float = 1f) = assertEquals(exp, act, tol)

    @Test
    fun parsesSceneMapping() {
        val m = FactoryViews.parse(setting, scene)
        assertEquals(setOf(5, 35, 37), m.keys)
        val l = m.getValue(5)
        near(-30f, l.azDeg, 0.01f); near(15f, l.elDeg, 0.01f); near(7.5f, l.dist, 0.001f); near(32f, l.fovDeg, 0.01f)
        near(0.348f, l.clipL, 0.001f); near(0.87f, l.clipB, 0.001f)
        assertTrue(!l.mirror)
        assertTrue(m.getValue(35).mirror)
        near(35f, m.getValue(37).fovDeg, 0.01f)   // 沒寫 FOV → Common.FOV
        assertNull(m[25])                         // 用鏡頭畫面（不是 3D 車模）的場景不給
    }

    @Test
    fun projectionMatchesFactoryFormula() {
        val m = FactoryViews.parse(setting, scene)
        val pl = PerspectiveMapper(m.getValue(5), 1920, 720)
        fun chk(pm: PerspectiveMapper, x: Float, y: Float, z: Float, ex: Float, ey: Float) {
            val q = assertNotNull(pm.project(x, y, z)).let { pm.project(x, y, z)!! }
            near(ex, q[0], 1.5f); near(ey, q[1], 1.5f)
        }
        chk(pl, -2f, 0f, 3f, 964.1f, 323.6f)
        chk(pl, -3.5f, 0f, -1f, 848.0f, 571.7f)
        chk(pl, 0.9f, 1.4f, 2.3f, 1309.0f, 142.5f)
        // 高速往後看（鏡像）
        val pr = PerspectiveMapper(m.getValue(35), 1920, 720)
        chk(pr, -2f, 0f, -3f, 756.3f, 294.2f)
        chk(pr, -3.5f, 0f, -8f, 529.8f, 220.2f)
    }

    @Test
    fun mirroredUnprojectRoundTrips() {
        val pm = PerspectiveMapper(FactoryViews.parse(setting, scene).getValue(35), 1920, 720)
        val q = pm.project(-2.5f, 0f, -6f)!!
        val g = pm.unprojectToGround(q[0], q[1])!!
        near(-2.5f, g[0], 0.02f); near(-6f, g[1], 0.02f)
    }

    @Test
    fun defaultLeftIsFactorySide3dLeft() {
        val d = PerspectiveMapper.Params.DEFAULT_LEFT
        near(-30f, d.azDeg, 0.01f); near(15f, d.elDeg, 0.01f); near(32f, d.fovDeg, 0.01f)
        near(-0.368f, d.tx, 0.005f); near(0.483f, d.ty, 0.005f); near(0.362f, d.tz, 0.005f)
    }

    @Test
    fun keyLookupUsesSceneNumber() {
        assertNull(FactoryViews.forKey("BV_LEFT"))
    }
}

class FactoryFixViewTest {
    private val setting = "Common = { SvBv = 0, FOV = 35, }"
    private val fix = """
        function DefineFixViews()
            local fixviews = {
                [10] = { Pitch = 38, Mirror = false, Yaw = -270, CarOffset = { 0, 0, 0, }, EyeDist = 7500, CenPos = { 0, 200, 0, }, },
                [51] = { Pitch = 38, Mirror = false, Yaw = 0, CarOffset = { 0, 0, 0, }, EyeDist = 7500, CenPos = { 0, 200, 0, }, },
                [31] = SingleViewParam[1],
            }
            return fixviews
        end
    """.trimIndent()
    private val scene = """
        local scene = {
            [4] = {
                Viewport = 8,
                Contents = { { Views = { 10, 51 }, Mesh = 2, Stream = true, Model = { WheelMode = true }, }, },
            },
            [56] = {
                Viewport = 1,
                Contents = { { Views = { 31 }, }, },
            },
        }
    """.trimIndent()

    @Test
    fun fullScreen3dUsesLastFixView() {
        val (m, noTop) = FactoryViews.parseAll(setting, scene, fix)
        val p = m.getValue(4)
        org.junit.Assert.assertEquals(0f, p.azDeg, 0.01f)       // 動畫的最後一個（51）
        org.junit.Assert.assertEquals(38f, p.elDeg, 0.01f)
        org.junit.Assert.assertEquals(35f, p.fovDeg, 0.01f)     // Common.FOV
        org.junit.Assert.assertEquals(0.87f, p.clipB, 0.001f)
        org.junit.Assert.assertEquals(1f, p.clipR, 0.001f)
        org.junit.Assert.assertTrue(4 in noTop)
        org.junit.Assert.assertNull(m[56])                      // 鏡頭畫面不是繞車鏡頭
    }
}

class FactoryCameraViewTest {
    @Test
    fun frontCameraViewMatchesFittedModel() {
        val cam = floatArrayOf(0.02f, 0.298f, 2.778f)
        val p = FactoryViews.cameraParams(cam, rear = false)
        val m = PerspectiveMapper(p, 1920, 720)
        // 鏡頭正前方 3 m 的地面點：在水平線下 atan(0.298/3) = 5.67°，畫面俯角 17.17° → 在中心上方 11.5°
        val q = m.project(0.02f, 0f, 2.778f + 3f)!!
        val H = 0.87f * 720f; val W = 0.652f * 1920f
        val f = (H / 2f) / kotlin.math.tan(Math.toRadians(97.31 / 2)).toFloat()
        val ang = Math.toRadians(17.17 - Math.toDegrees(kotlin.math.atan(0.298 / 3.0)))
        org.junit.Assert.assertEquals(H / 2f - 0.092f * H / 2f - f * kotlin.math.tan(ang).toFloat(), q[1], 1.0f)
        org.junit.Assert.assertEquals(0.348f * 1920f + W / 2f, q[0], 1.0f)
    }

    @Test
    fun rearCameraIsMirroredAndLooksBack() {
        val p = FactoryViews.cameraParams(floatArrayOf(0.02f, 0.82f, -2.38f), rear = true)
        val m = PerspectiveMapper(p, 1920, 720)
        val cx = (0.348f + 1f) / 2f * 1920f
        // 車後右邊（x > 0）的點：鏡像畫面裡在右邊（跟照後鏡／倒車畫面一樣）
        val q = m.project(1.5f, 0f, -6f)!!
        org.junit.Assert.assertTrue(q[0] > cx)
        org.junit.Assert.assertNull("車前方的點看不到", m.project(0f, 0f, 5f))
    }

    @Test
    fun bvNameSelectsCamera() {
        FactoryViews.setCameras(floatArrayOf(0f, 0.3f, 2.8f), floatArrayOf(0f, 0.8f, -2.4f))
        org.junit.Assert.assertNotNull(FactoryViews.cameraView("BV_FRONT"))
        org.junit.Assert.assertTrue(FactoryViews.cameraView("BV_BACK")!!.mirror)
        org.junit.Assert.assertNull(FactoryViews.cameraView("BV_LEFT"))
    }
}

class FactoryHazardViewTest {
    @Test
    fun hazardScenesGiveTwoSideCameras() {
        FactoryViews.setCameras(floatArrayOf(0f, 0.3f, 2.8f), floatArrayOf(0f, 0.8f, -2.4f), floatArrayOf(-1.02f, 0.84f, 0.96f), floatArrayOf(1.07f, 0.83f, 0.90f))
        val (l, r) = FactoryViews.hazardViews("SCENE_49_L1")!!
        org.junit.Assert.assertEquals(0.325f, l.clipR, 1e-4f)
        org.junit.Assert.assertEquals(0.675f, r.clipL, 1e-4f)
        org.junit.Assert.assertNull(FactoryViews.hazardViews("SCENE_5_L0"))
        // 沿左鏡頭光軸的點 → 投影在偏移後的畫面中心（傾斜不影響中心）
        val m = PerspectiveMapper(l, 1920, 720)
        val a = Math.toRadians(-1.6); val e = Math.toRadians(41.7)
        val t = 1.0
        val q = m.project((-1.02 + t * kotlin.math.sin(a) * kotlin.math.cos(e)).toFloat(), (0.84 - t * kotlin.math.sin(e)).toFloat(),
            (0.96 + t * kotlin.math.cos(a) * kotlin.math.cos(e)).toFloat())!!
        val W = 0.325f * 1920f; val H = 0.87f * 720f
        org.junit.Assert.assertEquals(W / 2f + 0.754f * W / 2f, q[0], 1f)
        org.junit.Assert.assertEquals(H / 2f - 0.243f * H / 2f, q[1], 1f)
    }

    @Test
    fun rolledViewRoundTrips() {
        FactoryViews.setCameras(null, null, floatArrayOf(-1.02f, 0.84f, 0.96f), floatArrayOf(1.07f, 0.83f, 0.90f))
        val (_, r) = FactoryViews.hazardViews("SCENE_49_L0")!!
        val m = PerspectiveMapper(r, 1920, 720)
        val q = m.project(2.2f, 0f, 2.5f)!!
        val g = m.unprojectToGround(q[0], q[1])!!
        org.junit.Assert.assertEquals(2.2f, g[0], 0.02f); org.junit.Assert.assertEquals(2.5f, g[1], 0.02f)
    }

    @Test
    fun centerTopMovesToMiddlePane() {
        // 全域對位被蓋成怪值（實車：開機動畫畫面對出來的）也不影響中間那格
        val bad = AnnotationMapper.Params(0.9375f, 0f, 1f, 1f, 0.9953f, 0.4792f, 0.0569f, false)
        val c = FactoryViews.centerTop(bad)
        org.junit.Assert.assertEquals(0.503f, c.cx, 1e-4f)
        org.junit.Assert.assertEquals(0.097f, c.scale, 1e-4f)
        org.junit.Assert.assertEquals(0.325f, c.clipL, 1e-4f)
    }
}

class PolygonClipTest {
    @Test
    fun slotHalfBehindSideCameraStillDraws() {
        FactoryViews.setCameras(null, null, floatArrayOf(-1.02f, 0.84f, 0.96f), floatArrayOf(1.07f, 0.83f, 0.90f))
        val (l, _) = FactoryViews.hazardViews("SCENE_49_L0")!!
        val m = PerspectiveMapper(l, 1920, 720)
        // 隔壁格（左邊），一大半在鏡頭後面
        val slot = listOf(floatArrayOf(-3.75f, -2.6f), floatArrayOf(-1.25f, -2.6f), floatArrayOf(-1.25f, 2.6f), floatArrayOf(-3.75f, 2.6f))
        val pts = m.projectGroundPolygon(slot)
        org.junit.Assert.assertTrue("切完還是一個面：${pts.size}", pts.size >= 3)
        // 白線（x = −1.25）那一邊要延伸到畫面下半部（靠近鏡頭的地方）
        org.junit.Assert.assertTrue(pts.maxOf { it[1] } > 0.87f * 720f * 0.6f)
    }
}
