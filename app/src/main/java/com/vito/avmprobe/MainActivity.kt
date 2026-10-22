package com.vito.avmprobe

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
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
            toast("偵測已啟動，切到「全景360」看疊加框")
        } else {
            toast("使用者取消了螢幕擷取授權")
        }
    }

    private val notiLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 可忽略結果 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notiLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun buildUi(): android.view.View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        root.addView(TextView(this).apply {
            text = "AVM ADAS Probe"
            textSize = 24f
            setTextColor(Color.parseColor("#008577"))
        })
        root.addView(TextView(this).apply {
            text = "Phase 1：截「全景360」畫面 → YOLO 偵測 → 疊框量 FPS\n先授予「懸浮窗」權限，再按開始。"
            textSize = 14f
            setPadding(0, 12, 0, 24)
        })

        root.addView(btn("① 授予懸浮窗權限") { requestOverlay() })
        root.addView(btn("② 開始偵測（截螢幕）") { startDetection() })
        root.addView(btn("■ 停止") { stopDetection() })
        root.addView(btn("UVC 直讀偵測（免 root）") {
            startActivity(Intent(this, UvcActivity::class.java))
        })
        root.addView(btn("硬體探測（USB / v4l2）") { runProbe() })

        log = TextView(this).apply {
            text = "— 硬體探測結果會顯示在這裡 —"
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 24, 0, 0)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(log)
        }
        root.addView(scroll)
        return root
    }

    private fun btn(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        }

    private fun requestOverlay() {
        if (Settings.canDrawOverlays(this)) {
            toast("懸浮窗權限已開")
            return
        }
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
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
