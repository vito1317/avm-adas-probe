package com.vito.avmprobe

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat

/**
 * 四分割環景鏡頭（Camera2）。兩路輸出：TextureView 預覽（順）＋ YUV ImageReader（偵測用）；
 * 硬體不支援雙串流時退回只開 YUV，previewFromYuv=true 由呼叫端自己畫預覽。
 *
 * 鏡頭被別的程式拿走（例如倒車時全景360 接手）會自動等它釋放再重連；stop() 立刻釋放鏡頭。
 */
class CameraQuadSource(
    context: Context,
    private val camId: String,
    private val listener: Listener
) {
    interface Listener {
        /** 狀態文字（任何執行緒）。running=true 表示畫面正在進來。 */
        fun onState(text: String, running: Boolean)
        /** 新的一幀（frame 執行緒）。可以自行提早 close，回傳後也會被 close。 */
        fun onFrame(img: Image)
    }

    companion object { private const val TAG = "CameraQuad" }

    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val camThread = HandlerThread("cam").apply { start() }
    private val cam = Handler(camThread.looper)
    private val frameThread = HandlerThread("frames").apply { start() }
    /** 處理影像的執行緒；呼叫端的模型也應該在這裡建立/釋放。 */
    val frames = Handler(frameThread.looper)

    // 以下只在 cam 執行緒存取
    private var started = false
    private var opening = false
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewTexture: SurfaceTexture? = null
    private var dualOk = true

    @Volatile var size: Size? = null; private set
    @Volatile var previewFromYuv = false; private set

    fun setPreview(st: SurfaceTexture?) = cam.post {
        previewTexture = st
        if (device != null) restartSession()
    }

    fun start() = cam.post {
        if (started) return@post
        started = true
        cm.registerAvailabilityCallback(availability, cam)
        openIfNeeded()
    }

    /** 立刻釋放鏡頭（畫面離開前景時一定要呼叫，倒車時全景360 才拿得到）。 */
    fun stop() = cam.post {
        started = false
        try { cm.unregisterAvailabilityCallback(availability) } catch (_: Throwable) {}
        closeAll()
    }

    fun release() {
        stop()
        cam.post { camThread.quitSafely() }
        frames.post { frameThread.quitSafely() }
    }

    private val availability = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(id: String) {
            if (id == camId && started && device == null && !opening) {
                cam.postDelayed({ openIfNeeded() }, 400)
            }
        }
    }

    private fun openIfNeeded() {
        if (!started || device != null || opening) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            listener.onState("需要相機權限", false)
            return
        }
        val chosen = try {
            pickSize()
        } catch (t: Throwable) {
            listener.onState("❌ 找不到鏡頭 $camId：${t.message}", false)
            return
        }
        size = chosen
        opening = true
        listener.onState("開啟鏡頭 $camId（${chosen.width}x${chosen.height}）…", false)
        try {
            cm.openCamera(camId, stateCallback, cam)
        } catch (t: Throwable) {
            opening = false
            listener.onState("❌ 開啟鏡頭失敗：${t.message}", false)
        }
    }

    /** 16:9 才能切成四個象限；優先 1920x1080。 */
    private fun pickSize(): Size {
        val map = cm.getCameraCharacteristics(camId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("沒有串流設定")
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        if (sizes.isEmpty()) throw IllegalStateException("不支援 YUV 輸出")
        sizes.firstOrNull { it.width == 1920 && it.height == 1080 }?.let { return it }
        val wide = sizes.filter { it.width * 9 == it.height * 16 && it.width <= 1920 }
        return wide.maxByOrNull { it.width } ?: sizes.maxByOrNull { it.width * it.height }!!
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(d: CameraDevice) {
            opening = false
            if (!started) { d.close(); return }
            device = d
            createSession()
        }

        override fun onDisconnected(d: CameraDevice) {
            opening = false
            d.close()
            if (device === d) closeAll()
            listener.onState("鏡頭被其他程式拿走（例如倒車時的全景360），釋放後會自動重連", false)
        }

        override fun onError(d: CameraDevice, error: Int) {
            opening = false
            d.close()
            if (device === d) closeAll()
            val why = when (error) {
                ERROR_CAMERA_IN_USE, ERROR_MAX_CAMERAS_IN_USE -> "被占用（全景360 正在用）"
                ERROR_CAMERA_DISABLED -> "系統停用"
                ERROR_CAMERA_DEVICE -> "鏡頭硬體錯誤"
                else -> "相機服務錯誤"
            }
            listener.onState("鏡頭 $camId $why，等待釋放後自動重連", false)
        }
    }

    private fun createSession() {
        val d = device ?: return
        val sz = size ?: return
        val r = ImageReader.newInstance(sz.width, sz.height, ImageFormat.YUV_420_888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = try { rd.acquireLatestImage() } catch (_: Throwable) { null } ?: return@setOnImageAvailableListener
            try {
                listener.onFrame(img)
            } catch (t: Throwable) {
                Log.e(TAG, "frame 處理失敗：${t.message}", t)
            } finally {
                img.close()
            }
        }, frames)
        reader = r

        val targets = ArrayList<Surface>()
        targets.add(r.surface)
        val st = previewTexture
        val preview = if (dualOk && st != null) {
            st.setDefaultBufferSize(sz.width, sz.height)
            Surface(st).also { targets.add(it) }
        } else null
        previewFromYuv = preview == null

        try {
            @Suppress("DEPRECATION")
            d.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (device !== d) { s.close(); return }
                    session = s
                    try {
                        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        for (t in targets) req.addTarget(t)
                        s.setRepeatingRequest(req.build(), null, cam)
                        listener.onState(
                            "鏡頭 $camId ${sz.width}x${sz.height}" + if (preview == null) "（單串流）" else "",
                            true
                        )
                    } catch (t: Throwable) {
                        listener.onState("❌ 啟動串流失敗：${t.message}", false)
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (preview != null) {
                        // 不支援預覽＋YUV 同時開：退回單串流，自己從 YUV 畫預覽
                        dualOk = false
                        listener.onState("不支援雙串流，改單串流", false)
                        restartSession()
                    } else {
                        listener.onState("❌ 設定鏡頭串流失敗", false)
                    }
                }
            }, cam)
        } catch (t: Throwable) {
            listener.onState("❌ 建立串流失敗：${t.message}", false)
        }
    }

    private fun restartSession() {
        try { session?.close() } catch (_: Throwable) {}
        session = null
        closeReader()
        createSession()
    }

    /** reader 要在 frames 執行緒關，才不會跟正在處理的那一幀打架。 */
    private fun closeReader() {
        val r = reader ?: return
        reader = null
        frames.post { try { r.close() } catch (_: Throwable) {} }
    }

    private fun closeAll() {
        try { session?.close() } catch (_: Throwable) {}
        session = null
        try { device?.close() } catch (_: Throwable) {}
        device = null
        closeReader()
    }
}
