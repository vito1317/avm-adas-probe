package com.vito.avmprobe

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.UVCCamera
import java.nio.ByteBuffer

/**
 * 來源 ②：UVC 直讀（免 root）。
 * 直接顯示採集盒送來的原始鏡頭畫面，並在上面疊 YOLO 偵測框。
 * 不需要 MediaProjection / 系統懸浮窗 —— 因為這是我們自己的畫面。
 *
 * 依賴：com.herohan:UVCAndroid（內含預編 native lib）。
 * 前提：採集盒必須是 UVC class 裝置（先用主畫面「硬體探測」確認有 VIDEO/UVC ★）。
 */
class UvcActivity : AppCompatActivity() {

    companion object { private const val TAG = "UvcActivity" }

    private var cameraHelper: ICameraHelper? = null
    private lateinit var surfaceView: SurfaceView
    private lateinit var overlay: OverlayView

    private var yolo: Yolo? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile private var busy = false
    private var frameW = 0
    private var frameH = 0
    private var reuseBmp: Bitmap? = null

    private var emaInfer = 0f
    private var emaFps = 0f
    private var lastNs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        surfaceView = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        overlay = OverlayView(this)
        root.addView(surfaceView)
        root.addView(overlay)
        setContentView(root)

        try {
            yolo = Yolo(this)
        } catch (t: Throwable) {
            Log.e(TAG, "模型載入失敗：${t.message}", t)
            overlay.update(emptyList(), "模型載入失敗：\n確認 assets/model.tflite\n${t.message}")
        }

        worker = HandlerThread("uvc-infer").also { it.start() }
        handler = Handler(worker!!.looper)
    }

    override fun onStart() {
        super.onStart()
        cameraHelper = CameraHelper().apply { setStateCallback(stateListener) }
        overlay.update(emptyList(), "等待 UVC 裝置…\n（插上採集盒並允許 USB 權限）")
    }

    override fun onStop() {
        super.onStop()
        cameraHelper?.release()
        cameraHelper = null
    }

    override fun onDestroy() {
        super.onDestroy()
        worker?.quitSafely()
        yolo?.close()
        reuseBmp?.recycle()
    }

    private val stateListener = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
            // 選第一個偵測到的 UVC 裝置
            cameraHelper?.selectDevice(device)
        }

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            cameraHelper?.openCamera()
        }

        override fun onCameraOpen(device: UsbDevice) {
            val helper = cameraHelper ?: return
            // 要先設 frame callback 再 startPreview
            helper.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_RGB565)
            helper.startPreview()
            helper.getPreviewSize()?.let {
                frameW = it.width
                frameH = it.height
                Log.i(TAG, "preview = ${frameW}x$frameH")
            }
            helper.addSurface(surfaceView.holder.surface, false)
        }

        override fun onCameraClose(device: UsbDevice) {
            cameraHelper?.removeSurface(surfaceView.holder.surface)
        }

        override fun onDeviceClose(device: UsbDevice) {}
        override fun onDetach(device: UsbDevice) {
            runOnUiThread { toast("UVC 裝置已拔除") }
        }
        override fun onCancel(device: UsbDevice) {
            runOnUiThread { toast("使用者取消 USB 授權") }
        }
    }

    private val frameCallback = IFrameCallback { frame ->
        if (busy || frameW == 0 || frameH == 0 || yolo == null) return@IFrameCallback
        busy = true
        try {
            val bmp = reuseBmp ?: Bitmap.createBitmap(frameW, frameH, Bitmap.Config.RGB_565)
                .also { reuseBmp = it }
            frame.rewind()
            bmp.copyPixelsFromBuffer(frame)
            handler?.post { runInference(bmp) } ?: run { busy = false }
        } catch (t: Throwable) {
            Log.e(TAG, "frame 轉 bitmap 失敗：${t.message}")
            busy = false
        }
    }

    private fun runInference(bmp: Bitmap) {
        try {
            val (dets, inferMs) = yolo!!.detect(bmp)

            val vw = overlay.width.coerceAtLeast(1)
            val vh = overlay.height.coerceAtLeast(1)
            val sx = vw.toFloat() / frameW
            val sy = vh.toFloat() / frameH
            val boxes = dets.map {
                RectF(it.rect.left * sx, it.rect.top * sy, it.rect.right * sx, it.rect.bottom * sy) to
                    "${it.label} ${(it.score * 100).toInt()}%"
            }

            val now = System.nanoTime()
            if (lastNs != 0L) {
                val fps = 1_000_000_000f / (now - lastNs)
                emaFps = if (emaFps == 0f) fps else emaFps * 0.8f + fps * 0.2f
            }
            lastNs = now
            emaInfer = if (emaInfer == 0f) inferMs.toFloat() else emaInfer * 0.8f + inferMs * 0.2f

            val stats = "來源 UVC 直讀  引擎 ${yolo!!.engineName}\n" +
                "${frameW}x$frameH  推論 ${emaInfer.toInt()}ms\n" +
                "FPS ${"%.1f".format(emaFps)}  偵測 ${dets.size} 台"
            overlay.update(boxes, stats)
        } catch (t: Throwable) {
            Log.e(TAG, "推論失敗：${t.message}", t)
        } finally {
            busy = false
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
