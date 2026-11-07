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
    /** 沒有俯視圖格子的場景（全螢幕 3D 等）：俯視標注不能畫。 */
    @Volatile var noTop: Set<Int> = emptySet()
        private set
    @Volatile var status: String = "還沒讀"
        private set

    private fun sceneOf(key: String) = Regex("^SCENE_(\\d+)").find(key)?.groupValues?.get(1)?.toIntOrNull()

    fun forKey(key: String, bvName: String = ""): PerspectiveMapper.Params? =
        sceneOf(key)?.let { scenes[it] } ?: cameraView(bvName)

    // ───── 鏡頭展開畫面（第一人稱）：右邊那格直接顯示前／後鏡頭去畸變的影像 ─────
    // 原廠是 SingleViewParam（從鏡頭位置看出去），參數語意（XBias、BottomAlign…）讀不懂，
    // 所以用實車的原始魚眼影格重現原廠畫面、比對找出的值（2026-11-07，相關係數 0.79／0.77）：
    //   前：正前方、俯角 17.2°、垂直 FOV 97.3°、中心往上 0.092 半高
    //   後：正後方、俯角 17.6°、垂直 FOV 91.2°、中心往下 0.02 半高、左右翻轉
    // 場景編號分不出前後（兩個都出現過場景 7），用原廠的 BV 狀態名稱（BV_FRONT／BV_BACK）分。

    @Volatile private var camFront: FloatArray? = null
    @Volatile private var camRear: FloatArray? = null

    fun setCameras(front: FloatArray?, rear: FloatArray?) { camFront = front; camRear = rear }

    fun cameraView(bvName: String): PerspectiveMapper.Params? {
        val n = bvName.uppercase()
        val rear = n == "BV_BACK" || n == "BV_REAR" || n == "BV_MW_REAR"
        val front = n == "BV_FRONT" || n == "BV_MW_FRONT"
        if (!rear && !front) return null
        val c = (if (rear) camRear else camFront) ?: return null
        return cameraParams(c, rear)
    }

    /** 鏡頭位置 [c]（我們的座標，m）→ 鏡頭展開畫面的投影參數。 */
    fun cameraParams(c: FloatArray, rear: Boolean): PerspectiveMapper.Params {
        val el = if (rear) 17.55f else 17.17f
        val d = 5f
        val e = Math.toRadians(el.toDouble())
        val dirZ = (if (rear) -1.0 else 1.0) * kotlin.math.cos(e)
        return PerspectiveMapper.Params(
            clipL = 0.348f, clipT = 0f, clipR = 1f, clipB = 0.87f,
            azDeg = if (rear) 180f else 0f, elDeg = el, dist = d, fovDeg = if (rear) 91.25f else 97.31f,
            tx = c[0], tz = (c[2] + d * dirZ).toFloat(), ty = (c[1] - d * kotlin.math.sin(e)).toFloat(),
            mirror = rear, cyOff = if (rear) 0.02f else -0.092f
        )
    }

    /** 這個場景確定沒有俯視圖（全 3D 模式）。 */
    fun noTopFor(key: String): Boolean = sceneOf(key)?.let { it in noTop } ?: false

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
            val fix = read("scene/fixview.lua", "birdview/scene/fixview.lua", "application/birdview/scene/fixview.lua") ?: ""
            if (setting == null || scene == null) { status = "讀不到原廠設定檔"; return }
            val r = parseAll(setting, scene, fix)
            scenes = r.first; noTop = r.second
            status = "已讀取原廠 3D 視角 ${scenes.size} 個（場景 ${scenes.keys.sorted().joinToString("、")}）"
        } catch (t: Throwable) { status = "讀取原廠視角失敗：$t" }
    }

    fun parse(settingLua: String, sceneLua: String, fixviewLua: String = ""): Map<Int, PerspectiveMapper.Params> =
        parseAll(settingLua, sceneLua, fixviewLua).first

    /**
     * 解析 setting.lua、scene1.lua、fixview.lua → (場景編號 → 3D 參數, 沒有俯視圖的場景)。
     * 3D 內容可以是 TransParam（Side3DParam／Rotate3DParam）或 Views（fixview.lua 的固定視角，
     * 有動畫的取最後一個），只認繞車鏡頭（有 EyeDist）的，鏡頭畫面（SingleViewParam）不算。
     */
    fun parseAll(settingLua: String, sceneLua: String, fixviewLua: String): Pair<Map<Int, PerspectiveMapper.Params>, Set<Int>> {
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
        // fixview.lua：[n] = { Pitch, Yaw, EyeDist, CenPos, Mirror }
        val fixes = HashMap<Int, View3D>()
        val fv = stripComments(fixviewLua)
        for (m in Regex("\\[(\\d+)\\]\\s*=\\s*\\{").findAll(fv)) {
            val b = braced(fv, m.range.last) ?: continue
            if (Regex("\\[\\d+\\]\\s*=").containsMatchIn(b)) continue   // 外層（DefineFixViews 的整張表）
            fixes[m.groupValues[1].toInt()] = view3d(b) ?: continue
        }
        val out = HashMap<Int, PerspectiveMapper.Params>()
        val noTop = HashSet<Int>()
        val scene = stripComments(sceneLua)
        val marks = Regex("\\[(\\d+)\\]\\s*=\\s*\\{").findAll(scene).toList()
        for (m in marks) {
            val body = braced(scene, m.range.last) ?: continue
            if (!body.contains("Contents")) continue
            val vp = Regex("Viewport\\s*=\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val n = m.groupValues[1].toInt()
            val contents = block(body, "Contents")?.let { items(it) } ?: continue
            // fixview 1 就是原廠俯視圖：場景裡有它才有俯視格
            fun views(c: String) = Regex("Views\\s*=\\s*\\{([^}]*)\\}").find(c)?.groupValues?.get(1)
                ?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
            if (contents.none { views(it) == listOf(1) }) noTop.add(n)
            val clip = paneFor(vp, svbv) ?: continue
            // 俯視 + 3D 版面：3D 是第二格；其他版面：第一格
            val c = contents.getOrNull(if (vp == 2) 1 else 0) ?: continue
            if (views(c) == listOf(1)) continue
            val v = Regex("TransParam\\s*=\\s*((?:Side3DParam|Rotate3DParam)\\.\\w+)").find(c)?.groupValues?.get(1)?.let { groups[it] }
                ?: Regex("Views\\s*=\\s*\\{([^}]*)\\}").find(c)?.groupValues?.get(1)
                    ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.lastOrNull()?.let { fixes[it] }
                ?: continue
            out[n] = toParams(v, v.fov ?: defFov, clip)
        }
        return out to noTop
    }

    private fun view3d(s: String): View3D? {
        val cen = Regex("CenPos\\s*=\\s*\\{([^}]*)\\}").find(s)?.groupValues?.get(1)?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }
        return View3D(
            yaw = num(s, "Yaw") ?: 0f, pitch = num(s, "Pitch") ?: return null, eyeDist = num(s, "EyeDist") ?: return null,
            cen = if (cen != null && cen.size >= 3) floatArrayOf(cen[0], cen[1], cen[2]) else floatArrayOf(0f, 0f, 0f),
            fov = num(s, "FOV"), mirror = Regex("Mirror\\s*=\\s*true").containsMatchIn(s)
        )
    }

    /** 從 [open]（'{' 的位置）開始配對到對應的 '}'，回傳中間的文字。 */
    private fun braced(s: String, open: Int): String? {
        var depth = 0
        for (i in open until s.length) {
            when (s[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return s.substring(open + 1, i) }
            }
        }
        return null
    }

    /** 大括號清單裡每一個頂層 { ... } 項目。 */
    private fun items(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            if (s[i] == '{') { val b = braced(s, i) ?: break; out.add(b); i += b.length + 2 } else i++
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
        7 -> floatArrayOf(0f, 0f, 0.40f, 1f)
        8 -> floatArrayOf(0f, 0f, 1f, 0.87f)   // 全 3D：整個寬、工具列上方
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
