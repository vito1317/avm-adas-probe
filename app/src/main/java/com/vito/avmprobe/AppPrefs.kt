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
        /** 正常版面的俯視對位（俯視圖在左邊那格）：自動／手動對位只有這種才當全域預設。 */
        fun normalTopLayout(t: AnnotationMapper.Params) =
            t.clipL < 0.05f && t.clipR in 0.28f..0.42f && t.scale in 0.07f..0.13f && t.cx in 0.10f..0.26f

        /** 明顯錯誤的全域俯視對位（實車：別的畫面自動對位對出中心在最右邊、比例尺小一半）。 */
        fun garbageTop(t: AnnotationMapper.Params) = t.cx > 0.45f || t.cx < 0f || t.scale < 0.05f || t.scale > 0.16f
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

    /**
     * 3D 畫面上物件立體框的高度倍率。原廠的 3D 是把鏡頭影像貼在碗狀曲面上，旁邊的車會被「壓扁」在
     * 地面／碗壁上，照真實車高畫會比畫面上的車高很多 → 預設 0.25。0 = 只畫地面外框。
     */
    var obj3dHeight: Float
        get() = sp.getFloat("obj3dH", 0.25f)
        set(v) = sp.edit().putFloat("obj3dH", v).apply()

    /** 行車記錄器（合併版：發動就循環錄影）。 */
    var dashcamOn: Boolean
        get() = sp.getBoolean("dashcamOn", true)
        set(v) = sp.edit().putBoolean("dashcamOn", v).apply()

    /** 遠端模組（CY103）藍牙金鑰（32 個十六進位字）與模組位址（見 [CyRemote]）。 */
    var cyKey: String
        get() = sp.getString("cyKey", "") ?: ""
        set(v) = sp.edit().putString("cyKey", v).apply()
    var cyMac: String
        get() = sp.getString("cyMac", "") ?: ""
        set(v) = sp.edit().putString("cyMac", v).apply()
    var cyName: String
        get() = sp.getString("cyName", "") ?: ""
        set(v) = sp.edit().putString("cyName", v).apply()
    /** 停好車（P 檔＋手煞車）問要不要升窗、鎖車。 */
    var cyParkPrompt: Boolean
        get() = sp.getBoolean("cyParkPrompt", true)
        set(v) = sp.edit().putBoolean("cyParkPrompt", v).apply()

    /** 後擋 LED 看板（iPixel，見 [RearDisplay]）：每個情境要播看板裡的第幾則（0 = 不切換）。 */
    var rdOn: Boolean
        get() = sp.getBoolean("rdOn", false)
        set(v) = sp.edit().putBoolean("rdOn", v).apply()
    var rdMac: String
        get() = sp.getString("rdMac", "") ?: ""
        set(v) = sp.edit().putString("rdMac", v).apply()
    var rdName: String
        get() = sp.getString("rdName", "") ?: ""
        set(v) = sp.edit().putString("rdName", v).apply()
    var rdSlotBrake: Int
        get() = sp.getInt("rdSlotBrake", 0)
        set(v) = sp.edit().putInt("rdSlotBrake", v).apply()
    var rdSlotReverse: Int
        get() = sp.getInt("rdSlotReverse", 0)
        set(v) = sp.edit().putInt("rdSlotReverse", v).apply()
    var rdSlotStart: Int
        get() = sp.getInt("rdSlotStart", 0)
        set(v) = sp.edit().putInt("rdSlotStart", v).apply()
    var rdSlotWait: Int
        get() = sp.getInt("rdSlotWait", 0)
        set(v) = sp.edit().putInt("rdSlotWait", v).apply()
    var rdSlotLeft: Int
        get() = sp.getInt("rdSlotLeft", 0)
        set(v) = sp.edit().putInt("rdSlotLeft", v).apply()
    var rdSlotRight: Int
        get() = sp.getInt("rdSlotRight", 0)
        set(v) = sp.edit().putInt("rdSlotRight", v).apply()
    var rdSlotFollow: Int
        get() = sp.getInt("rdSlotFollow", 0)
        set(v) = sp.edit().putInt("rdSlotFollow", v).apply()
    var rdSlotPark: Int
        get() = sp.getInt("rdSlotPark", 0)
        set(v) = sp.edit().putInt("rdSlotPark", v).apply()
    /** 平常輪播的幾則，例如 "1,2,3"（空的就用 [rdSlotIdle]）。 */
    var rdIdleList: String
        get() = sp.getString("rdIdleList", "") ?: ""
        set(v) = sp.edit().putString("rdIdleList", v).apply()
    var rdSlotIdle: Int
        get() = sp.getInt("rdSlotIdle", 0)
        set(v) = sp.edit().putInt("rdSlotIdle", v).apply()

    /** 藍牙氛圍燈連動（見 [AmbientLight]）。 */
    var ambientOn: Boolean
        get() = sp.getBoolean("ambientOn", false)
        set(v) = sp.edit().putBoolean("ambientOn", v).apply()
    var ambientMac: String
        get() = sp.getString("ambientMac", "") ?: ""
        set(v) = sp.edit().putString("ambientMac", v).apply()
    var ambientName: String
        get() = sp.getString("ambientName", "") ?: ""
        set(v) = sp.edit().putString("ambientName", v).apply()
    /** 中控台開著時 360 在背景也跑偵測（中控台的 3D 道路畫面才有資料）。實驗：實車會讓 360 閃退，預設關。 */
    /** 用自己訓練的車道線模型找線（有模型檔才有效）；關掉 = 原本的亮度規則。 */
    var laneModel: Boolean
        get() = sp.getBoolean("laneModel", true)
        set(v) = sp.edit().putBoolean("laneModel", v).apply()
    /** 前／後鏡頭展開畫面跟著方向盤轉（原廠動態輔助線）時，標注也跟著轉。 */
    var fpvFollowWheel: Boolean
        get() = sp.getBoolean("fpvFollowWheel", true)
        set(v) = sp.edit().putBoolean("fpvFollowWheel", v).apply()
    /** 上面那個轉的方向相反（實車驗證用）。 */
    var fpvWheelFlip: Boolean
        get() = sp.getBoolean("fpvWheelFlip", false)
        set(v) = sp.edit().putBoolean("fpvWheelFlip", v).apply()
    var trainCollect: Boolean
        get() = sp.getBoolean("trainCollect", false)
        set(v) = sp.edit().putBoolean("trainCollect", v).apply()
    var launcherBgDetect: Boolean
        get() = sp.getBoolean("launcherBgDetect", false)
        set(v) = sp.edit().putBoolean("launcherBgDetect", v).apply()
    /** 氛圍燈目前的動態模式（空字串 = 單色）；警示結束後恢復成這個。 */
    var ambientMode: String
        get() = sp.getString("ambientMode", "") ?: ""
        set(v) = sp.edit().putString("ambientMode", v).apply()
    /** 哪一組燈在左邊：0 = 不分側（全部一起）、1 = 第 1 組、2 = 第 2 組。 */
    var ambientLeftZone: Int
        get() = sp.getInt("ambientLeftZone", 0)
        set(v) = sp.edit().putInt("ambientLeftZone", v).apply()

    /** 行車記錄器最多用多少空間（GB），超過刪最舊的。 */
    var dashcamQuotaGb: Int
        get() = sp.getInt("dashcamQuota", 8)
        set(v) = sp.edit().putInt("dashcamQuota", v).apply()

    /** 原廠方向盤（前輪角）的正負號：+1 = 正值右轉、-1 = 正值左轉（行駛中跟畫面量的轉向比對自動學）。 */
    var factorySteerSign: Int
        get() = sp.getInt("facSteerSign", 1)
        set(v) = sp.edit().putInt("facSteerSign", v).apply()

    /** 語音提示（念出警示）。 */
    var voiceAlerts: Boolean
        get() = sp.getBoolean("voiceAlerts", true)
        set(v) = sp.edit().putBoolean("voiceAlerts", v).apply()

    /** 車道偏移／壓線提醒的最低車速（km/h）。 */
    var ldwMinKmh: Int
        get() = sp.getInt("ldwMinKmh", 55)
        set(v) = sp.edit().putInt("ldwMinKmh", v).apply()

    /** 提示音（起步、偏離車道、變道來車、轉彎路徑有人車、移動中太近）。 */
    /** 提示音走哪個聲道：0 導航提示（預設）、1 媒體、2 通知、3 鬧鐘（[AlertTone.CHANNELS]）。 */
    var alertChannel: Int
        get() = sp.getInt("alertChannel", 0)
        set(v) = sp.edit().putInt("alertChannel", v).apply()

    var alertSound: Boolean
        get() = sp.getBoolean("alertSound", true)
        set(v) = sp.edit().putBoolean("alertSound", v).apply()

    /** 合併版：在俯視圖上畫出偵測到的車道線（除錯用，預設關）。 */
    var annotDrawRoad: Boolean
        get() = sp.getBoolean("annotRoad", false)
        set(v) = sp.edit().putBoolean("annotRoad", v).apply()

    /** 合併版：原廠某個視角（BV_state 名稱）存的標注設定；沒存過給 [ViewProfile.defaultFor]。 */
    fun viewProfile(key: String, bvName: String = ""): ViewProfile {
        val raw = sp.getString("view_$key", null)
        if (raw != null) try {
            var prof = ViewProfile.fromJson(org.json.JSONObject(raw))
            // 讀得到原廠這個場景的 3D 參數 → 一律用原廠的（之前自動／估的對位作廢）；使用者手動調過的除外
            if (viewCalMode(key) != "manual") {
                FactoryViews.forKey(key, bvName)?.let { prof = prof.copy(p3 = it) }
                FactoryViews.hazardViews(key)?.let { (l, r) -> prof = prof.copy(top = FactoryViews.centerTop(annotParams), p3 = l, p3b = r) }
                if (FactoryViews.noTopFor(key)) prof = prof.copy(top = null)
            }
            // 存的沒有 3D（只對位過俯視、或被清掉）→ 用實車校準的左右燈預設；使用者手動關掉 3D 的不補
            if (prof.p3 == null && viewCalMode(key) != "manual")
                return prof.copy(p3 = ViewProfile.defaultFor(key, annotParams, bvName).p3)
            return prof
        } catch (_: Throwable) {}
        // 舊版（m-v3 以前）只有左右燈兩組 3D 設定：沿用
        val legacy = when (key) {
            ViewProfile.TURN_L -> 'L'; ViewProfile.TURN_R -> 'R'; else -> null
        }
        if (legacy != null && sp.contains("a3${legacy}az")) return ViewProfile(annotParams, annot3d(legacy))
        return ViewProfile.defaultFor(key, annotParams, bvName)
    }

    fun setViewProfile(key: String, v: ViewProfile) {
        sp.edit().putString("view_$key", v.toJson().toString()).apply()
    }

    /**
     * 一次性：以前的版本 3D 自動對位在實車上會套錯參數 → 把自動對位存的 3D 拿掉（俯視保留），
     * 手動調過的不動。之後沒存 3D 的視角就用實車校準好的預設值。
     */
    fun migrateBad3dOnce() {
        if (sp.getBoolean("mig3dV2", false)) return
        val e = sp.edit()
        for ((k, v) in sp.all) {
            if (!k.startsWith("view_") || v !is String) continue
            val key = k.removePrefix("view_")
            if (viewCalMode(key) == "manual") continue
            try {
                val prof = ViewProfile.fromJson(org.json.JSONObject(v))
                e.putString(k, ViewProfile(prof.top, null).toJson().toString())
            } catch (_: Throwable) { e.remove(k) }
        }
        // 舊的全域俯視對位也換成實車校準的預設
        e.remove("anL").remove("anT").remove("anR").remove("anB").remove("anCx").remove("anCy").remove("anS").remove("anF")
        e.putBoolean("mig3dV2", true).apply()
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
