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
                    Contents = { { TransParam = Rotate3DParam.Rear } },
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
