package com.vito.avmprobe

import android.content.Context
import android.os.Environment
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * 直接讀原廠 360 的 3D 視角設定（Lua），不用再校準。
 *
 * 原廠 3D 是 Lua 腳本控制的：
 * - `cfg/setting.lua` 的 `Side3DParam`／`Rotate3DParam`（Front／Right／Rear／Left）寫著每個 3D 視角的
 *   Yaw、Pitch、EyeDist（mm）、CenPos（mm）、FOV、Mirror；`Common.FOV` 是預設 FOV，`Common.SvBv` 決定俯視圖在左或右。
 * - `scene/scene1.lua` 寫著每個場景（nativegetCurrentScene 回傳的編號）用哪組參數，例如
 *   5 = 中速打左燈（Side3DParam.Left）、3 = 中速打右燈、35／33 = 高速打左／右燈（Rotate3DParam，鏡像往後看）、
 *   62／67 = 長按前／後。
 * - 鏡頭公式在 `scripts/view_animate.lua` 的 FormatView：原廠座標 x 前、y 上、z 右（mm），
 *   鏡頭 = 繞 z 轉 −Pitch、再繞 y 轉 −Yaw 的 (−EyeDist, 0, 0)，加上同樣轉過的 CenPos。
 *
 * 車機上的檔案在 /sdcard/.Baios360/birdview/（使用者在原廠設定改過的值也在這），讀不到就讀 APK 裡附的原始檔。
 */
object FactoryViews {

    data class View3D(
        val yaw: Float, val pitch: Float, val eyeDist: Float,
        val cen: FloatArray, val fov: Float?, val mirror: Boolean
    )

    /** 場景編號 → 3D 那一格的參數。 */
    @Volatile var scenes: Map<Int, PerspectiveMapper.Params> = emptyMap()
        private set
    @Volatile var status: String = "還沒讀"
        private set

    fun forKey(key: String): PerspectiveMapper.Params? =
        Regex("^SCENE_(\\d+)").find(key)?.groupValues?.get(1)?.toIntOrNull()?.let { scenes[it] }

    fun load(ctx: Context) {
        try {
            val base = File(Environment.getExternalStorageDirectory(), ".Baios360/birdview")
            fun read(dev: String, vararg assets: String): String? {
                File(base, dev).takeIf { it.canRead() }?.let { return it.readText() }
                for (a in assets) try { return ctx.assets.open(a).bufferedReader().readText() } catch (_: Throwable) {}
                return null
            }
            val setting = read("cfg/setting.lua", "application/birdview/cfg/setting.lua", "birdview/cfg/setting.lua")
            val scene = read("scene/scene1.lua", "birdview/scene/scene1.lua", "application/birdview/scene/scene1.lua")
            if (setting == null || scene == null) { status = "讀不到原廠設定檔"; return }
            scenes = parse(setting, scene)
            status = "已讀取原廠 3D 視角 ${scenes.size} 個（場景 ${scenes.keys.sorted().joinToString("、")}）"
        } catch (t: Throwable) { status = "讀取原廠視角失敗：$t" }
    }

    /** 解析 setting.lua 與 scene1.lua，回傳場景編號 → 3D 參數。 */
    fun parse(settingLua: String, sceneLua: String): Map<Int, PerspectiveMapper.Params> {
        val setting = stripComments(settingLua)
        val common = block(setting, "Common")
        val defFov = common?.let { num(it, "FOV") } ?: 35f
        val svbv = common?.let { num(it, "SvBv") } ?: 0f
        val groups = HashMap<String, View3D>()
        for (g in listOf("Side3DParam", "Rotate3DParam")) {
            val b = block(setting, g) ?: continue
            for (side in listOf("Front", "Right", "Rear", "Left")) {
                val s = block(b, side) ?: continue
                val cen = Regex("CenPos\\s*=\\s*\\{([^}]*)\\}").find(s)?.groupValues?.get(1)
                    ?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }
                val v = View3D(
                    yaw = num(s, "Yaw") ?: 0f, pitch = num(s, "Pitch") ?: continue,
                    eyeDist = num(s, "EyeDist") ?: continue,
                    cen = if (cen != null && cen.size >= 3) floatArrayOf(cen[0], cen[1], cen[2]) else floatArrayOf(0f, 0f, 0f),
                    fov = num(s, "FOV"), mirror = Regex("Mirror\\s*=\\s*true").containsMatchIn(s)
                )
                groups["$g.$side"] = v
            }
        }
        val out = HashMap<Int, PerspectiveMapper.Params>()
        val scene = stripComments(sceneLua)
        val marks = Regex("\\[(\\d+)\\]\\s*=\\s*\\{").findAll(scene).toList()
        for ((i, m) in marks.withIndex()) {
            val body = scene.substring(m.range.last, if (i + 1 < marks.size) marks[i + 1].range.first else scene.length)
            val tp = Regex("TransParam\\s*=\\s*((?:Side3DParam|Rotate3DParam)\\.\\w+)").find(body)?.groupValues?.get(1) ?: continue
            val v = groups[tp] ?: continue
            val vp = Regex("Viewport\\s*=\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val clip = paneFor(vp, svbv) ?: continue
            out[m.groupValues[1].toInt()] = toParams(v, v.fov ?: defFov, clip)
        }
        return out
    }

    /**
     * 3D 那一格在螢幕上的範圍（l, t, r, b，螢幕比例、y 往下）。原廠 viewport.lua 是 OpenGL 座標（y 往上）：
     * 1 = 全螢幕；2 = 俯視圖 {0.652·SvBv, 0.13, 0.348, 0.87} + 3D {0.348·(1−SvBv), 0.13, 0.652, 0.87}。
     */
    fun paneFor(viewport: Int, svbv: Float): FloatArray? = when (viewport) {
        1 -> floatArrayOf(0f, 0f, 1f, 1f)
        2 -> { val x = 0.348f * (1f - svbv); floatArrayOf(x, 0f, x + 0.652f, 0.87f) }
        else -> null
    }

    /** 原廠鏡頭（FormatView）→ 我們的繞車鏡頭參數。 */
    fun toParams(v: View3D, fov: Float, clip: FloatArray): PerspectiveMapper.Params {
        // 原廠座標 (x 前, y 上, z 右)：CenPos 先繞 z 轉 −Pitch，再繞 y 轉 −Yaw
        val p = Math.toRadians(-v.pitch.toDouble()); val y = Math.toRadians(-v.yaw.toDouble())
        val x1 = v.cen[0] * cos(p) - v.cen[1] * sin(p)
        val y1 = v.cen[0] * sin(p) + v.cen[1] * cos(p)
        val z1 = v.cen[2].toDouble()
        val x2 = x1 * cos(y) + z1 * sin(y)
        val z2 = -x1 * sin(y) + z1 * cos(y)
        // 我們：x 右 = 原廠 z、y 上、z 前 = 原廠 x；公尺。方位角 = −Yaw（Yaw + 是鏡頭往左繞）
        var az = -v.yaw
        while (az > 180f) az -= 360f
        while (az <= -180f) az += 360f
        return PerspectiveMapper.Params(
            clipL = clip[0], clipT = clip[1], clipR = clip[2], clipB = clip[3],
            azDeg = az, elDeg = v.pitch.coerceAtMost(89.5f), dist = v.eyeDist / 1000f, fovDeg = fov,
            tx = (z2 / 1000.0).toFloat(), tz = (x2 / 1000.0).toFloat(), ty = (y1 / 1000.0).toFloat(),
            mirror = v.mirror
        )
    }

    private fun stripComments(s: String) = s.replace(Regex("--[^\\n]*"), "")

    private fun num(s: String, key: String): Float? =
        Regex("(?<![A-Za-z_])$key\\s*=\\s*(-?[0-9.]+)").find(s)?.groupValues?.get(1)?.toFloatOrNull()

    /** `name = { ... }` 大括號內的文字（配對括號）。 */
    private fun block(s: String, name: String): String? {
        val m = Regex("(?<![A-Za-z_.])$name\\s*=\\s*\\{").find(s) ?: return null
        var depth = 0
        for (i in m.range.last until s.length) {
            when (s[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return s.substring(m.range.last + 1, i) }
            }
        }
        return null
    }
}
