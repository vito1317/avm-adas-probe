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
        /** 列出 assets 裡所有 model_*.tflite（顯示名稱, 檔名），小的在前。 */
        fun models(context: Context): List<Pair<String, String>> =
            (context.assets.list("")?.toList() ?: emptyList())
                .filter { it.startsWith("model_") && it.endsWith(".tflite") }
                .sortedWith(compareBy({ it.filter(Char::isDigit).take(3).toIntOrNull() ?: 0 }, { it }))
                .map { it.removePrefix("model_").removeSuffix(".tflite") to it }

        const val PANEL_RIGHT = "right"
        const val PANEL_LEFT = "left"
        const val PANEL_OFF = "off"
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

    /** 3D 地面貼上即時俯視畫面。 */
    var realGround: Boolean
        get() = sp.getBoolean("realGround", true)
        set(v) = sp.edit().putBoolean("realGround", v).apply()

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
