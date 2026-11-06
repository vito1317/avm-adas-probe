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
        if (prefs.lastCrash.isNotEmpty()) {
            col.addView(TextView(act).apply {
                text = "上次閃退（完整內容在「下載/avm-adas/crash_*.txt」）：\n" + prefs.lastCrash
                textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(Color.rgb(255, 120, 100))
            })
            col.addView(Button(act).apply {
                text = "清除閃退紀錄"; isAllCaps = false
                setOnClickListener { prefs.lastCrash = ""; prefs.annotCrashes = 0; text = "已清除" }
            })
        }

        section("ADAS")
        check("啟用偵測與標注（關掉後下次進 360 起不再接相機）", { prefs.annotEnabled }) {
            prefs.annotEnabled = it; prefs.annotBoots = 0; prefs.annotCrashes = 0
        }
        check("車道偵測（偏離車道警示、變道輔助要用）", { prefs.groundMode == AppPrefs.GROUND_VIRTUAL }) {
            prefs.groundMode = if (it) AppPrefs.GROUND_VIRTUAL else AppPrefs.GROUND_OFF
        }
        check("在俯視圖上畫出偵測到的車道線（除錯用）", { prefs.annotDrawRoad }) {
            prefs.annotDrawRoad = it; av.drawRoad = it
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

        col.addView(Button(act).apply {
            text = "存一張原始影格（停車格認錯、偵測怪怪的時候按，給開發者分析）"; isAllCaps = false
            setOnClickListener { Engine360.saveRaw(act) }
        })

        // ───────── 對位：針對「原廠現在顯示的視角」─────────
        // 原廠每個視角（俯視＋側邊 3D、倒車、全螢幕…）版面不同，各存一組。原廠切到哪個視角就套用哪組。
        val key = av.currentKey()
        var prof = av.currentProfile()
        var win: android.view.Window? = null
        fun save(np: ViewProfile) {
            prof = np
            prefs.setViewProfile(key, np)
            np.top?.let { prefs.annotParams = it }   // 最新的俯視圖對位也當作新視角的預設
            av.invalidateProfiles()
        }

        section("對位：目前視角 $key")
        col.addView(TextView(act).apply {
            textSize = 12f
            text = "這組設定只套用在原廠的「$key」視角。要調別的視角（倒車、打方向燈、手動切換的模式），" +
                "先按完成、在原廠切到那個視角，再打開這裡。打方向燈時原廠會自動切側邊 3D，可以打著燈調。"
        })
        check("對位模式（畫出紫色自車外框／立體線框與格線）", { av.calibrating }) { av.calibrating = it }
        var onRight = true
        col.addView(Button(act).apply {
            text = "設定視窗移到另一邊（露出要對位的那格）"; isAllCaps = false
            setOnClickListener {
                onRight = !onRight
                win?.setGravity((if (onRight) android.view.Gravity.END else android.view.Gravity.START) or android.view.Gravity.CENTER_VERTICAL)
            }
        })

        // 通用滑桿：get 讀目前值、set 產生新的 profile
        val refreshers = ArrayList<() -> Unit>()
        fun slider(label: String, lo: Float, hi: Float, fmt: String, get: () -> Float?, set: (Float) -> ViewProfile?) {
            val tv = TextView(act).apply { textSize = 12f }
            val sb = SeekBar(act).apply { max = 1000 }
            val r = {
                val v = get()
                sb.isEnabled = v != null
                if (v != null) {
                    sb.progress = (((v - lo) / (hi - lo)) * 1000).toInt().coerceIn(0, 1000)
                    tv.text = "$label：${fmt.format(v)}"
                } else tv.text = "$label：—"
            }
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, prog: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = lo + (hi - lo) * prog / 1000f
                    set(v)?.let { save(it) }
                    tv.text = "$label：${fmt.format(v)}"
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
            refreshers.add(r)
            col.addView(tv); col.addView(sb)
        }
        fun refreshAll() { for (r in refreshers) r() }

        // 俯視圖
        section("俯視圖標注（紫框疊到原廠俯視圖的車模上）")
        col.addView(CheckBox(act).apply {
            text = "這個視角有俯視圖"; isChecked = prof.top != null
            setOnCheckedChangeListener { _, v ->
                save(prof.copy(top = if (v) (prof.top ?: prefs.annotParams) else null)); refreshAll()
            }
        })
        fun top(f: (AnnotationMapper.Params) -> AnnotationMapper.Params): ViewProfile? = prof.top?.let { prof.copy(top = f(it)) }
        slider("自車中心 左右", 0f, 1f, "%.3f", { prof.top?.cx }) { v -> top { it.copy(cx = v) } }
        slider("自車中心 上下", 0f, 1f, "%.3f", { prof.top?.cy }) { v -> top { it.copy(cy = v) } }
        slider("比例（1 公尺佔螢幕高度）", 0.02f, 0.30f, "%.3f", { prof.top?.scale }) { v -> top { it.copy(scale = v) } }
        slider("範圍 左緣", 0f, 1f, "%.3f", { prof.top?.clipL }) { v -> top { it.copy(clipL = v) } }
        slider("範圍 右緣", 0f, 1f, "%.3f", { prof.top?.clipR }) { v -> top { it.copy(clipR = v) } }
        slider("範圍 上緣", 0f, 1f, "%.3f", { prof.top?.clipT }) { v -> top { it.copy(clipT = v) } }
        slider("範圍 下緣", 0f, 1f, "%.3f", { prof.top?.clipB }) { v -> top { it.copy(clipB = v) } }
        col.addView(CheckBox(act).apply {
            text = "原廠俯視圖車頭朝下"; isChecked = prof.top?.flip == true
            setOnCheckedChangeListener { _, v -> top { it.copy(flip = v) }?.let { save(it) } }
        })

        // 3D／鏡頭畫面
        section("3D／鏡頭畫面標注（立體紫框疊到原廠 3D 車模上，粗線那面是車頭）")
        col.addView(CheckBox(act).apply {
            text = "這個視角有 3D 或鏡頭畫面"; isChecked = prof.p3 != null
            setOnCheckedChangeListener { _, v ->
                val def = ViewProfile.defaultFor(key, prefs.annotParams).p3 ?: PerspectiveMapper.Params.DEFAULT_LEFT
                save(prof.copy(p3 = if (v) (prof.p3 ?: def) else null)); refreshAll()
                // 3D 那格多在右邊：調 3D 時把設定視窗移到左邊，別擋住
                win?.setGravity((if (v) android.view.Gravity.START else android.view.Gravity.END) or android.view.Gravity.CENTER_VERTICAL)
            }
        })
        fun p3(f: (PerspectiveMapper.Params) -> PerspectiveMapper.Params): ViewProfile? = prof.p3?.let { prof.copy(p3 = f(it)) }
        slider("鏡頭方位（0 正後、± 往右／左繞、180 正前）", -180f, 180f, "%.0f°", { prof.p3?.azDeg }) { v -> p3 { it.copy(azDeg = v) } }
        slider("鏡頭俯角", 0f, 89f, "%.0f°", { prof.p3?.elDeg }) { v -> p3 { it.copy(elDeg = v) } }
        slider("鏡頭距離", 1f, 30f, "%.1f m", { prof.p3?.dist }) { v -> p3 { it.copy(dist = v) } }
        slider("視野（越大東西越小）", 15f, 150f, "%.0f°", { prof.p3?.fovDeg }) { v -> p3 { it.copy(fovDeg = v) } }
        slider("看向的點 左右（− 左）", -10f, 10f, "%.1f m", { prof.p3?.tx }) { v -> p3 { it.copy(tx = v) } }
        slider("看向的點 前後（+ 前）", -15f, 15f, "%.1f m", { prof.p3?.tz }) { v -> p3 { it.copy(tz = v) } }
        slider("範圍 左緣", 0f, 1f, "%.3f", { prof.p3?.clipL }) { v -> p3 { it.copy(clipL = v) } }
        slider("範圍 右緣", 0f, 1f, "%.3f", { prof.p3?.clipR }) { v -> p3 { it.copy(clipR = v) } }
        slider("範圍 上緣", 0f, 1f, "%.3f", { prof.p3?.clipT }) { v -> p3 { it.copy(clipT = v) } }
        slider("範圍 下緣", 0f, 1f, "%.3f", { prof.p3?.clipB }) { v -> p3 { it.copy(clipB = v) } }

        ViewProfile.counterpart(key)?.let { other ->
            col.addView(Button(act).apply {
                text = "把這個視角鏡像複製到 $other"; isAllCaps = false
                setOnClickListener {
                    // 版面（範圍）不鏡像，只鏡像視角
                    prefs.setViewProfile(other, ViewProfile(prof.top, prof.p3?.let { it.mirrored().copy(clipL = it.clipL, clipR = it.clipR) }))
                    av.invalidateProfiles()
                    android.widget.Toast.makeText(act, "已複製到 $other", android.widget.Toast.LENGTH_SHORT).show()
                }
            })
        }
        col.addView(Button(act).apply {
            text = "這個視角重設為預設值"; isAllCaps = false
            setOnClickListener {
                prefs.resetViewProfile(key); av.invalidateProfiles()
                prof = av.currentProfile(); refreshAll()
            }
        })
        refreshAll()

        AlertDialog.Builder(act)
            .setTitle("ADAS 設定")
            .setView(ScrollView(act).apply { addView(col) })
            .setPositiveButton("完成") { _, _ -> av.calibrating = false }
            .setOnCancelListener { av.calibrating = false }
            .show()
            .window?.let { w ->
                win = w
                // 對位時要看得到後面的原廠畫面：對話框不要變暗背景
                // 而且靠右放，左邊的原廠俯視圖要露出來對位
                w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                w.setGravity(android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL)
                val dm = act.resources.displayMetrics
                w.setLayout((dm.widthPixels * 0.45f).toInt(), (dm.heightPixels * 0.92f).toInt())
            }
    }
}
