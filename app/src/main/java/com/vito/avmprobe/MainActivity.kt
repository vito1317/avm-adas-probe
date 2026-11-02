package com.vito.avmprobe

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: AppPrefs
    private lateinit var log: TextView

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val svc = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, svc)
            toast("偵測已啟動，切到「全景360」並開俯視畫面")
        } else {
            toast("使用者取消了螢幕擷取授權")
        }
    }

    private val notiLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notiLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 32, 40, 32)
        }

        col.addView(TextView(this).apply {
            text = "AVM ADAS Probe  v${packageManager.getPackageInfo(packageName, 0).versionName}"
            textSize = 24f
            setTextColor(Color.parseColor("#008577"))
        })
        col.addView(TextView(this).apply {
            text = "截「全景360」俯視畫面 → YOLO 偵測 → 右側 3D 面板即時重建周遭物件。\n" +
                "改了模型／引擎／面板位置，要按「停止」再「開始」才生效；俯視區校正在 3D 面板右上角「校正」。"
            textSize = 14f
            setPadding(0, 10, 0, 18)
        })

        col.addView(section("模型（輸入解析度，小＝快；int8＝量化版）"))
        col.addView(segRow(AppPrefs.models(this), { prefs.model }) { prefs.model = it })

        col.addView(section("推論引擎"))
        col.addView(segRow(
            listOf("GPU" to "gpu", "CPU 4 核" to "cpu"),
            { if (prefs.useGpu) "gpu" else "cpu" }
        ) { prefs.useGpu = it == "gpu" })

        col.addView(section("3D 面板位置（不要擋住俯視圖那一側）"))
        col.addView(segRow(
            listOf("右側" to AppPrefs.PANEL_RIGHT, "左側" to AppPrefs.PANEL_LEFT, "關閉" to AppPrefs.PANEL_OFF),
            { prefs.panel }
        ) { prefs.panel = it })

        col.addView(section("執行"))
        col.addView(btn("① 授予懸浮窗權限") { requestOverlay() })
        col.addView(btn("② 開始偵測（截螢幕）") { startDetection() })
        col.addView(btn("■ 停止") { stopDetection() })

        col.addView(section("硬體測試"))
        col.addView(btn("鏡頭直開測試（Camera2，免 root）") {
            startActivity(Intent(this, CameraTestActivity::class.java))
        })
        col.addView(btn("UVC 直讀偵測（USB 鏡頭用）") {
            startActivity(Intent(this, UvcActivity::class.java))
        })
        col.addView(btn("硬體探測（Camera2 / USB / v4l2）") { runProbe() })

        log = TextView(this).apply {
            text = "— 硬體探測結果會顯示在這裡 —"
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(0, 20, 0, 0)
            setTextIsSelectable(true)
        }
        col.addView(log)

        return ScrollView(this).apply { addView(col) }
    }

    private fun section(title: String) = TextView(this).apply {
        text = title
        textSize = 13f
        setTextColor(Color.parseColor("#7a8b99"))
        setPadding(0, 22, 0, 6)
    }

    /** 單選按鈕列：選中的前面加 ●。 */
    private fun segRow(
        options: List<Pair<String, String>>,
        current: () -> String,
        onPick: (String) -> Unit
    ): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val buttons = ArrayList<Pair<Button, Pair<String, String>>>()
        fun refresh() {
            val cur = current()
            for ((b, opt) in buttons) b.text = if (opt.second == cur) "● ${opt.first}" else opt.first
        }
        for (opt in options) {
            val b = Button(this).apply {
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    onPick(opt.second)
                    refresh()
                }
            }
            buttons.add(b to opt)
            row.addView(b)
        }
        refresh()
        return row
    }

    private fun btn(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        }

    private fun requestOverlay() {
        if (Settings.canDrawOverlays(this)) {
            toast("懸浮窗權限已開")
            return
        }
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun startDetection() {
        if (!Settings.canDrawOverlays(this)) {
            toast("請先授予懸浮窗權限（按①）")
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun stopDetection() {
        startService(Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        })
        toast("已停止")
    }

    private fun runProbe() {
        log.text = "探測中…\n\n" + UsbProbe.report(this)
    }

    private fun toast(s: String) =
        Toast.makeText(this, s, Toast.LENGTH_SHORT).apply { setGravity(Gravity.CENTER, 0, 0) }.show()
}
