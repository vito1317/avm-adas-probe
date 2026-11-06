package com.vito.avmprobe

import android.content.Context
import android.content.SharedPreferences
import android.graphics.RectF

/**
 * 校正與設定。數值以「螢幕比例 0..1」存，跟解析度無關。
 * 偵測服務每幀讀一次（SharedPreferences 有記憶體快取，很便宜），所以改了立刻生效。
 */
class AppPrefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("avm_probe", Context.MODE_PRIVATE)

    companion object {
        /** 列出 assets 裡所有 model_*.tflite（顯示名稱, 檔名）：一般 COCO 模型小的在前，車種細分放最後。 */
        fun models(context: Context): List<Pair<String, String>> =
            (context.assets.list("")?.toList() ?: emptyList())
                .filter { it.startsWith("model_") && it.endsWith(".tflite") }
                .sortedWith(compareBy({ it.contains("o365") }, { inputSizeOf(it) }, { it }))
                .map { displayName(it) to it }

        private fun inputSizeOf(file: String): Int =
            Regex("_(\\d{3})(_int8)?\\.tflite$").find(file)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        private fun displayName(file: String): String {
            val size = inputSizeOf(file)
            return when {
                file.contains("o365") -> "車種細分 $size"          // Objects365：多箱型車、三角錐，較慢
                file.contains("int8") -> "$size int8"
                else -> file.removePrefix("model_").removeSuffix(".tflite")
            }
        }

        const val PANEL_RIGHT = "right"
        const val PANEL_LEFT = "left"
        const val PANEL_OFF = "off"

        const val GROUND_REAL = "real"
        const val GROUND_VIRTUAL = "virtual"
        const val GROUND_OFF = "off"
    }

    var model: String
        get() = sp.getString("model", "model_320.tflite")!!
        set(v) = sp.edit().putString("model", v).apply()

    /** true=GPU delegate；false=CPU 4 執行緒（XNNPACK，int8 模型常較快）。 */
    var useGpu: Boolean
        get() = sp.getBoolean("useGpu", true)
        set(v) = sp.edit().putBoolean("useGpu", v).apply()

    /** 全景360 俯視圖（BEV）在螢幕上的範圍。預設＝左半、扣掉底部工具列。 */
    var roi: RectF
        get() = RectF(
            sp.getFloat("roiL", 0.03f), sp.getFloat("roiT", 0.0f),
            sp.getFloat("roiR", 0.48f), sp.getFloat("roiB", 0.86f)
        )
        set(r) = sp.edit()
            .putFloat("roiL", r.left).putFloat("roiT", r.top)
            .putFloat("roiR", r.right).putFloat("roiB", r.bottom).apply()

    /** 俯視圖橫向代表幾公尺（用來把像素換成公尺）。 */
    var bevMetersW: Float
        get() = sp.getFloat("bevMetersW", 8f)
        set(v) = sp.edit().putFloat("bevMetersW", v).apply()

    /** 俯視圖車頭朝下時打開（上下左右翻轉）。 */
    var bevFlip: Boolean
        get() = sp.getBoolean("bevFlip", false)
        set(v) = sp.edit().putBoolean("bevFlip", v).apply()

    /** 只偵測俯視區（較準、較快）；關掉則偵測全螢幕。 */
    var roiOnly: Boolean
        get() = sp.getBoolean("roiOnly", true)
        set(v) = sp.edit().putBoolean("roiOnly", v).apply()

    /** 3D 地面貼上即時俯視畫面。（舊設定，改用 groundMode） */
    var realGround: Boolean
        get() = sp.getBoolean("realGround", true)
        set(v) = sp.edit().putBoolean("realGround", v).apply()

    /** 3D 地面：real＝實景貼圖、virtual＝辨識出的虛擬道路、off＝不畫。 */
    /** 使用者有沒有選過地面模式（合併版沒選過就預設開車道偵測）。 */
    val groundModeSet: Boolean get() = sp.contains("groundMode")

    var groundMode: String
        get() = sp.getString("groundMode", null) ?: if (realGround) GROUND_REAL else GROUND_OFF
        set(v) {
            sp.edit().putString("groundMode", v).putBoolean("realGround", v == GROUND_REAL).apply()
        }

    // ---------- 鏡頭直讀（Camera2） ----------

    /** 四分割鏡頭的 Camera2 id（實測：2）。 */
    var camId: String
        get() = sp.getString("camId", "2")!!
        set(v) = sp.edit().putString("camId", v).apply()

    /** 要偵測的鏡頭：F 前 / B 後 / L 左 / R 右。 */
    var camDetect: String
        get() = sp.getString("camDetect", "FBLR")!!
        set(v) = sp.edit().putString("camDetect", v).apply()

    /**
     * 障礙物偵測（柱子、牆、路緣、別人的車身）：四顆鏡頭重疊區的垂直平面掃描，不靠模型。
     *
     * **預設關閉**：實車驗證後發現它大部分時候是在報「接縫沒對齊」而不是障礙物。
     * 平坦的地下室地面只有 48.5 % 的格子投給 h=0（應該要 >90 %），可判定區有 17–20 %
     * 超過門檻，而且超標區明顯沿著四條對角接縫分布 —— 空的柏油路和草地上也一樣。
     * 合成測試抓不到這個問題：合成畫面是用偵測器假設的同一組幾何算出來的，天生完美對齊。
     * 詳見 docs/obstacle-detection.md。留著開關讓人實驗，但不要預設開。
     */
    var camObstacle: Boolean
        get() = sp.getBoolean("camObstacle", false)
        set(v) = sp.edit().putBoolean("camObstacle", v).apply()

    /**
     * 影格共用（B 模式）：注入原廠 360 的掛鉤，把我們的 ImageReader 加進它的相機 session，
     * 一次開鏡頭、原廠 bowl 與我們的偵測 overlay 同時拿同一份影格。
     *
     * **預設關閉——這是安全開關**。關閉時注入的掛鉤完全不碰原廠相機，第一次燒進去
     * 保證不會害原廠沒畫面。確認原廠一切正常後，再從這裡打開，掛鉤才會去加我們的 surface。
     *
     * 注入在原廠 process 裡的 FrameTap 透過 [FrameShareProvider] 來問這個值
     * （跨 App 沒辦法共用 SharedPreferences；寫 /sdcard 旗標檔又沒儲存權限）。
     */
    var frameShare: Boolean
        get() = sp.getBoolean("frameShare", false)
        // commit（同步寫入）：FrameTap 隨時可能透過 FrameShareProvider 來問，要馬上讀得到新值
        set(v) { sp.edit().putBoolean("frameShare", v).commit() }

    /** 鏡頭直讀的版面：0 = 左右分割、1 = 3D 全螢幕、2 = 鏡頭全螢幕。 */
    var fullScreen: Int
        get() = sp.getInt("fullScreen", 0).coerceIn(0, 2)
        set(v) = sp.edit().putInt("fullScreen", v.coerceIn(0, 2)).apply()

    /** 自建俯視圖（拼接四顆鏡頭）→ 實景地面、道路、三角錐。 */
    var camBev: Boolean
        get() = sp.getBoolean("camBev", true)
        set(v) = sp.edit().putBoolean("camBev", v).apply()

    /**
     * 綁車機的車身訊號服務（com.ts.MainUI 的 ITsSpeechCar）並輪詢。
     *
     * 本來預設關閉，因為以為是它害車機重啟的；使用者後來確認**重啟是「監聽廣播」造成的**，
     * 不是綁定 —— 那一頁以前每收一筆廣播就把整份 log 重畫一次，又每 500 ms fork 一次 getprop，
     * 主執行緒卡死就被看門狗重開（已修，見 SignalProbeActivity）。
     *
     * 所以改回**預設開啟**：方向盤角度、PDC 雷達只有這條路拿得到，車速也多一個可靠來源
     * （廣播多半是有變化才送，停著不動時會過期）。
     * 呼叫前仍然會先驗介面名稱、連續失敗 5 次就解綁；那份介面裡有車窗、天窗、空調，
     * transaction code 對錯就慘了，所以那道檢查不能拿掉。開關保留。
     */
    var headUnitBind: Boolean
        get() = sp.getBoolean("headUnitBind", true)
        set(v) = sp.edit().putBoolean("headUnitBind", v).apply()

    /** 前鏡頭上半部多跑一輪，專找紅綠燈。 */
    var camTrafficLight: Boolean
        get() = sp.getBoolean("camTl", true)
        set(v) = sp.edit().putBoolean("camTl", v).apply()

    var panel: String
        get() = sp.getString("panel", PANEL_RIGHT)!!
        set(v) = sp.edit().putString("panel", v).apply()

    // ───────── 合併版（引擎塞進原廠 360）：直接標注在原廠俯視圖上 ─────────

    /** 合併版的總開關。預設開；連續啟動失敗時 [Engine360] 會自動關掉它。 */
    var annotEnabled: Boolean
        get() = sp.getBoolean("annotOn", true)
        set(v) { sp.edit().putBoolean("annotOn", v).commit() }

    /** 連續「啟動後沒撐過健康檢查」的次數（舊的崩潰保護，已不用；留著讓設定頁歸零）。 */
    var annotBoots: Int
        get() = sp.getInt("annotBoots", 0)
        set(v) { sp.edit().putInt("annotBoots", v).commit() }

    /** 我們的程式害原廠閃退的次數（堆疊裡有 com.vito）；撐過健康檢查就歸零。 */
    var annotCrashes: Int
        get() = sp.getInt("annotCrashes", 0)
        set(v) { sp.edit().putInt("annotCrashes", v).commit() }

    /** 上一次閃退的摘要（顯示在 ADAS 設定最上面），空字串 = 沒有。 */
    var lastCrash: String
        get() = sp.getString("lastCrash", "")!!
        set(v) { sp.edit().putString("lastCrash", v).commit() }

    /** 原廠俯視圖 → 螢幕的對應（[AnnotationMapper.Params]）。 */
    var annotParams: AnnotationMapper.Params
        get() = AnnotationMapper.Params(
            clipL = sp.getFloat("anL", AnnotationMapper.Params.DEFAULT.clipL),
            clipT = sp.getFloat("anT", AnnotationMapper.Params.DEFAULT.clipT),
            clipR = sp.getFloat("anR", AnnotationMapper.Params.DEFAULT.clipR),
            clipB = sp.getFloat("anB", AnnotationMapper.Params.DEFAULT.clipB),
            cx = sp.getFloat("anCx", AnnotationMapper.Params.DEFAULT.cx),
            cy = sp.getFloat("anCy", AnnotationMapper.Params.DEFAULT.cy),
            scale = sp.getFloat("anS", AnnotationMapper.Params.DEFAULT.scale),
            flip = sp.getBoolean("anF", AnnotationMapper.Params.DEFAULT.flip)
        )
        set(v) = sp.edit()
            .putFloat("anL", v.clipL).putFloat("anT", v.clipT).putFloat("anR", v.clipR).putFloat("anB", v.clipB)
            .putFloat("anCx", v.cx).putFloat("anCy", v.cy).putFloat("anS", v.scale).putBoolean("anF", v.flip)
            .apply()

    /** 行車記錄器（合併版：發動就循環錄影）。 */
    var dashcamOn: Boolean
        get() = sp.getBoolean("dashcamOn", true)
        set(v) = sp.edit().putBoolean("dashcamOn", v).apply()

    /** 行車記錄器最多用多少空間（GB），超過刪最舊的。 */
    var dashcamQuotaGb: Int
        get() = sp.getInt("dashcamQuota", 8)
        set(v) = sp.edit().putInt("dashcamQuota", v).apply()

    /** 提示音（起步、偏離車道、變道來車、轉彎路徑有人車、移動中太近）。 */
    var alertSound: Boolean
        get() = sp.getBoolean("alertSound", true)
        set(v) = sp.edit().putBoolean("alertSound", v).apply()

    /** 合併版：在俯視圖上畫出偵測到的車道線（除錯用，預設關）。 */
    var annotDrawRoad: Boolean
        get() = sp.getBoolean("annotRoad", false)
        set(v) = sp.edit().putBoolean("annotRoad", v).apply()

    /** 合併版：原廠某個視角（BV_state 名稱）存的標注設定；沒存過給 [ViewProfile.defaultFor]。 */
    fun viewProfile(key: String): ViewProfile {
        val raw = sp.getString("view_$key", null)
        if (raw != null) try { return ViewProfile.fromJson(org.json.JSONObject(raw)) } catch (_: Throwable) {}
        // 舊版（m-v3 以前）只有左右燈兩組 3D 設定：沿用
        val legacy = when (key) {
            ViewProfile.TURN_L -> 'L'; ViewProfile.TURN_R -> 'R'; else -> null
        }
        if (legacy != null && sp.contains("a3${legacy}az")) return ViewProfile(annotParams, annot3d(legacy))
        return ViewProfile.defaultFor(key, annotParams)
    }

    fun setViewProfile(key: String, v: ViewProfile) {
        sp.edit().putString("view_$key", v.toJson().toString()).apply()
    }

    fun resetViewProfile(key: String) { sp.edit().remove("view_$key").remove("viewCal_$key").apply() }

    /** 這個視角怎麼對位的："" 還沒、"auto" 自動對位、"manual" 使用者手動調過（自動不會蓋掉）。 */
    fun viewCalMode(key: String): String = sp.getString("viewCal_$key", "")!!
    fun setViewCalMode(key: String, mode: String) { sp.edit().putString("viewCal_$key", mode).apply() }

    /** 原廠打方向燈時右側 3D 視角 → 螢幕的對應。[side] = 'L' 或 'R'，兩邊各存一組。 */
    fun annot3d(side: Char): PerspectiveMapper.Params {
        val d = if (side == 'R') PerspectiveMapper.Params.DEFAULT_RIGHT else PerspectiveMapper.Params.DEFAULT_LEFT
        val k = "a3$side"
        return PerspectiveMapper.Params(
            clipL = sp.getFloat("${k}L", d.clipL), clipT = sp.getFloat("${k}T", d.clipT),
            clipR = sp.getFloat("${k}R", d.clipR), clipB = sp.getFloat("${k}B", d.clipB),
            azDeg = sp.getFloat("${k}az", d.azDeg), elDeg = sp.getFloat("${k}el", d.elDeg),
            dist = sp.getFloat("${k}d", d.dist), fovDeg = sp.getFloat("${k}fov", d.fovDeg),
            tx = sp.getFloat("${k}tx", d.tx), tz = sp.getFloat("${k}tz", d.tz)
        )
    }

    fun setAnnot3d(side: Char, v: PerspectiveMapper.Params) {
        val k = "a3$side"
        sp.edit()
            .putFloat("${k}L", v.clipL).putFloat("${k}T", v.clipT).putFloat("${k}R", v.clipR).putFloat("${k}B", v.clipB)
            .putFloat("${k}az", v.azDeg).putFloat("${k}el", v.elDeg).putFloat("${k}d", v.dist).putFloat("${k}fov", v.fovDeg)
            .putFloat("${k}tx", v.tx).putFloat("${k}tz", v.tz)
            .apply()
    }

    /** 3D 面板範圍（螢幕比例）。避開俯視區，否則截圖會截到自己。 */
    fun panelRect(): RectF? = when (panel) {
        PANEL_RIGHT -> RectF(0.5f, 0f, 1f, 0.86f)
        PANEL_LEFT -> RectF(0f, 0f, 0.5f, 0.86f)
        else -> null
    }
}
