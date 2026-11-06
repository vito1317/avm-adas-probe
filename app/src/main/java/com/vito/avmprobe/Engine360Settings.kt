package com.vito.avmprobe

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * 合併版的設定（原廠 360 畫面左上的「ADAS」鈕）。全部用 Android 原生元件、程式產生，
 * 不依賴我們的資源檔（合併進原廠 APK 時沒有我們的 resources）。
 *
 * 對位：打開「對位模式」會在原廠俯視圖上畫出自車外框與 1 m 格線，
 * 調滑桿讓紫色外框跟原廠車模重疊即可。
 */
object Engine360Settings {

    fun show(act: Activity, prefs: AppPrefs, av: AnnotationView) {
        val dp = act.resources.displayMetrics.density
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
        }
        fun section(t: String) = col.addView(TextView(act).apply {
            text = t; textSize = 13f; setTextColor(Color.parseColor("#4fb3d9")); setPadding(0, (14 * dp).toInt(), 0, (4 * dp).toInt())
        })
        fun check(label: String, get: () -> Boolean, set: (Boolean) -> Unit) = col.addView(CheckBox(act).apply {
            text = label; isChecked = get(); setOnCheckedChangeListener { _, v -> set(v) }
        })

        col.addView(TextView(act).apply {
            text = FrameShareHub.status()
            textSize = 11f; typeface = Typeface.MONOSPACE
        })

        section("ADAS")
        check("啟用偵測與標注（關掉後下次進 360 起不再接相機）", { prefs.annotEnabled }) {
            prefs.annotEnabled = it; prefs.annotBoots = 0
        }
        check("道路標線", { prefs.groundMode == AppPrefs.GROUND_VIRTUAL }) {
            prefs.groundMode = if (it) AppPrefs.GROUND_VIRTUAL else AppPrefs.GROUND_OFF
        }
        check("停車格／三角錐（俯視辨識）", { prefs.camBev }) { prefs.camBev = it }
        check("紅綠燈", { prefs.camTrafficLight }) { prefs.camTrafficLight = it }
        check("障礙物（實驗，誤報多）", { prefs.camObstacle }) { prefs.camObstacle = it }
        check("讀取車機訊號（車速／方向燈）", { prefs.headUnitBind }) { prefs.headUnitBind = it }

        val models = AppPrefs.models(act)
        if (models.isNotEmpty()) {
            val b = Button(act).apply { isAllCaps = false }
            fun refresh() { b.text = "模型：" + (models.firstOrNull { it.second == prefs.model }?.first ?: prefs.model) + "（下次進 360 生效）" }
            b.setOnClickListener {
                val i = models.indexOfFirst { it.second == prefs.model }
                prefs.model = models[(i + 1).mod(models.size)].second
                refresh()
            }
            refresh()
            col.addView(b)
        }

        section("對位（讓紫色外框疊到原廠車模上）")
        check("對位模式（顯示自車外框與 1 m 格線）", { av.calibrating }) { av.calibrating = it }
        var p = prefs.annotParams
        fun apply(np: AnnotationMapper.Params) { p = np; prefs.annotParams = np; av.params = np }
        val sliders = ArrayList<Pair<SeekBar, () -> Float>>()
        fun slider(label: String, lo: Float, hi: Float, get: () -> Float, set: (Float) -> AnnotationMapper.Params) {
            val tv = TextView(act).apply { textSize = 12f }
            val sb = SeekBar(act).apply { max = 1000 }
            fun show(v: Float) { tv.text = "$label：${"%.3f".format(v)}" }
            sb.progress = (((get() - lo) / (hi - lo)) * 1000).toInt().coerceIn(0, 1000)
            show(get())
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, prog: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = lo + (hi - lo) * prog / 1000f
                    apply(set(v)); show(v)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
            sliders.add(sb to { (((get() - lo) / (hi - lo)) * 1000) })
            col.addView(tv); col.addView(sb)
        }
        slider("自車中心 左右", 0f, 1f, { p.cx }) { p.copy(cx = it) }
        slider("自車中心 上下", 0f, 1f, { p.cy }) { p.copy(cy = it) }
        slider("比例（1 公尺佔螢幕高度）", 0.02f, 0.30f, { p.scale }) { p.copy(scale = it) }
        slider("俯視圖範圍 左緣", 0f, 1f, { p.clipL }) { p.copy(clipL = it) }
        slider("俯視圖範圍 右緣", 0f, 1f, { p.clipR }) { p.copy(clipR = it) }
        slider("俯視圖範圍 上緣", 0f, 1f, { p.clipT }) { p.copy(clipT = it) }
        slider("俯視圖範圍 下緣", 0f, 1f, { p.clipB }) { p.copy(clipB = it) }
        check("原廠俯視圖車頭朝下", { p.flip }) { apply(p.copy(flip = it)) }
        col.addView(Button(act).apply {
            text = "對位重設為預設值"; isAllCaps = false
            setOnClickListener {
                apply(AnnotationMapper.Params.DEFAULT)
                for ((sb, pos) in sliders) sb.progress = pos().toInt().coerceIn(0, 1000)
            }
        })

        AlertDialog.Builder(act)
            .setTitle("ADAS 設定")
            .setView(ScrollView(act).apply { addView(col) })
            .setPositiveButton("完成") { _, _ -> av.calibrating = false }
            .setOnCancelListener { av.calibrating = false }
            .show()
            .window?.let { w ->
                // 對位時要看得到後面的原廠畫面：對話框不要變暗背景
                // 而且靠右放，左邊的原廠俯視圖要露出來對位
                w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                w.setGravity(android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL)
                val dm = act.resources.displayMetrics
                w.setLayout((dm.widthPixels * 0.45f).toInt(), (dm.heightPixels * 0.92f).toInt())
            }
    }
}
