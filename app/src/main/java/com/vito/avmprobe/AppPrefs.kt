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
     * 只在停車速度下跑且降頻，但仍然會跟 YOLO 搶 CPU，所以留一個開關。
     */
    var camObstacle: Boolean
        get() = sp.getBoolean("camObstacle", true)
        set(v) = sp.edit().putBoolean("camObstacle", v).apply()

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
     * **預設關閉**：那組 transaction code 是從別的 App 內含的介面版本還原的，
     * 車機上的版本若方法順序不同就會呼叫到別的方法（那份介面裡有車窗、天窗、空調），
     * 實測也出現過整台車機重啟。方向盤角度與雷達只有這條路拿得到，所以保留開關讓使用者自己決定。
     * 廣播（倒車、車速、方向燈聯動）是被動接收，不受這個開關影響，一律開著。
     */
    var headUnitBind: Boolean
        get() = sp.getBoolean("headUnitBind", false)
        set(v) = sp.edit().putBoolean("headUnitBind", v).apply()

    /** 前鏡頭上半部多跑一輪，專找紅綠燈。 */
    var camTrafficLight: Boolean
        get() = sp.getBoolean("camTl", true)
        set(v) = sp.edit().putBoolean("camTl", v).apply()

    var panel: String
        get() = sp.getString("panel", PANEL_RIGHT)!!
        set(v) = sp.edit().putString("panel", v).apply()

    /** 3D 面板範圍（螢幕比例）。避開俯視區，否則截圖會截到自己。 */
    fun panelRect(): RectF? = when (panel) {
        PANEL_RIGHT -> RectF(0.5f, 0f, 1f, 0.86f)
        PANEL_LEFT -> RectF(0f, 0f, 0.5f, 0.86f)
        else -> null
    }
}
