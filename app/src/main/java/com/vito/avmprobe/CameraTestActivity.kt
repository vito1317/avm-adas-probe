package com.vito.avmprobe

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 鏡頭直開測試：用標準 Camera2 開每一顆鏡頭看能不能拿到原始畫面（免 root、免截螢幕）。
 * 若全景360 正在用同一顆，可能顯示「被占用」——先關掉全景360 再試。
 */
class CameraTestActivity : AppCompatActivity() {

    private lateinit var texture: TextureView
    private lateinit var status: TextView
    private lateinit var row: LinearLayout

    private val cm by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private val bgThread = HandlerThread("camtest").apply { start() }
    private val bg = Handler(bgThread.looper)

    private var cam: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var curId: String? = null
    private var curSize: Size? = null
    private var frames = 0
    private var t0 = 0L

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok -> if (ok) listCams() else status.text = "需要相機權限才能測試" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        texture = TextureView(this)
        root.addView(texture, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        status = TextView(this).apply {
            setTextColor(Color.parseColor("#00FF88"))
            setBackgroundColor(Color.parseColor("#AA000000"))
            textSize = 16f
            setPadding(20, 14, 20, 14)
            text = "鏡頭直開測試（Camera2）"
        }
        root.addView(status, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START))

        row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scroll = HorizontalScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#AA000000"))
            addView(row)
        }
        root.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM))
        setContentView(root)

        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                frames++
                val dt = (System.nanoTime() - t0) / 1e9
                if (dt >= 1.0) {
                    val fps = frames / dt
                    frames = 0
                    t0 = System.nanoTime()
                    status.text = "✅ 鏡頭 $curId 有畫面  ${curSize?.width}x${curSize?.height}  " +
                        "FPS %.1f".format(fps)
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) listCams()
        else permLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun listCams() {
        row.removeAllViews()
        val ids = try { cm.cameraIdList.toList() } catch (t: Throwable) {
            status.text = "讀取鏡頭清單失敗：${t.message}"; return
        }
        if (ids.isEmpty()) {
            status.text = "系統沒有公開任何 Camera2 鏡頭 → 全景360 走的是廠商私有介面"
            return
        }
        status.text = "找到 ${ids.size} 顆鏡頭，點下方按鈕開啟"
        for (id in ids) {
            row.addView(Button(this).apply {
                text = "鏡頭 $id"
                setOnClickListener { open(id) }
            })
        }
    }

    private fun open(id: String) {
        closeCam()
        status.text = "開啟鏡頭 $id…"
        try {
            val map = cm.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
            val size = sizes.filter { it.width <= 1920 && it.height <= 1080 }
                .maxByOrNull { it.width * it.height } ?: sizes.firstOrNull() ?: Size(1280, 720)
            curId = id
            curSize = size
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    cam = d
                    startPreview(d, size)
                }
                override fun onDisconnected(d: CameraDevice) {
                    d.close()
                    ui("鏡頭 $id 斷線（可能被全景360 搶回去）")
                }
                override fun onError(d: CameraDevice, error: Int) {
                    d.close()
                    ui("❌ 鏡頭 $id 錯誤：${errName(error)}")
                }
            }, bg)
        } catch (t: Throwable) {
            status.text = "❌ 開啟鏡頭 $id 失敗：${t.message}"
        }
    }

    @Suppress("DEPRECATION")
    private fun startPreview(d: CameraDevice, size: Size) {
        val st = texture.surfaceTexture ?: return ui("預覽面尚未就緒，再按一次")
        st.setDefaultBufferSize(size.width, size.height)
        val surface = Surface(st)
        try {
            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }
            d.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        s.setRepeatingRequest(req.build(), null, bg)
                        frames = 0
                        t0 = System.nanoTime()
                        ui("鏡頭 ${d.id} 已開啟 ${size.width}x${size.height}，等待畫面…")
                    } catch (t: Throwable) {
                        ui("❌ 啟動預覽失敗：${t.message}")
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    ui("❌ 設定預覽失敗")
                }
            }, bg)
        } catch (t: Throwable) {
            ui("❌ 建立預覽失敗：${t.message}")
        }
    }

    private fun errName(e: Int) = when (e) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "被占用（先關掉全景360 再試）"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "同時開啟的鏡頭數已達上限"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "系統停用此鏡頭"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "鏡頭硬體錯誤"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "相機服務錯誤"
        else -> "code=$e"
    }

    private fun ui(s: String) = runOnUiThread { status.text = s }

    private fun closeCam() {
        try { session?.close() } catch (_: Throwable) {}
        try { cam?.close() } catch (_: Throwable) {}
        session = null
        cam = null
    }

    override fun onPause() {
        super.onPause()
        closeCam()
    }

    override fun onDestroy() {
        super.onDestroy()
        bgThread.quitSafely()
    }
}
