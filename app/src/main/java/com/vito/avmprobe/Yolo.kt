package com.vito.avmprobe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/** 一筆偵測結果。rect 為「被偵測的原始點陣圖」座標系；type 為 ObjTypes 的物件種類。 */
data class Detection(
    val rect: RectF,
    val label: String,
    val classId: Int,
    val score: Float,
    val type: String
)

/** letterbox 參數：來源座標 = (input 座標 - pad) / scale。 */
data class Letterbox(val scale: Float, val padX: Float, val padY: Float)

/**
 * YOLO11 / YOLOv8 TFLite 偵測器。
 * 輸入來源與本類別無關 —— 餵進來的只是一張 Bitmap（截螢幕 / UVC / v4l2 都行）。
 */
class Yolo(
    context: Context,
    modelAsset: String = "model_320.tflite",
    labelAsset: String? = null,
    private val confThreshold: Float = 0.30f,
    private val iouThreshold: Float = 0.45f,
    useGpu: Boolean = true
) {
    companion object {
        private const val TAG = "Yolo"

        /** model_x.tflite 旁邊有 model_x.labels.txt 就用它，否則是 COCO 模型。 */
        fun labelAssetFor(context: Context, modelAsset: String): String {
            val own = modelAsset.removeSuffix(".tflite") + ".labels.txt"
            return if (context.assets.list("")?.contains(own) == true) own else "coco_labels.txt"
        }
    }

    private val interpreter: Interpreter
    private val labels: List<String>
    private val types: Array<String?>   // 每個類別對應的物件種類；null = 不關心
    private var gpuDelegate: GpuDelegate? = null

    val engineName: String
    val inputSize: Int
    private val chFirst: Boolean   // true: NCHW [1,3,H,W]；false: NHWC [1,H,W,3]

    // 輸出張量形狀（[1, A, B]）
    private val featCount: Int
    private val boxCount: Int
    private val transposed: Boolean   // true: [1, 84, 8400]；false: [1, 8400, 84]

    // 重用緩衝
    private val inputBuffer: ByteBuffer
    private val letterboxBmp: Bitmap
    private val letterboxCanvas: Canvas
    private val letterboxPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels: IntArray
    private val output: Array<Array<FloatArray>>   // 重用，避免每幀配置數 MB

    init {
        labels = context.assets.open(labelAsset ?: labelAssetFor(context, modelAsset))
            .bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val hasScooter = labels.any { it.equals("scooter", true) || it.equals("moped", true) }
        types = Array(labels.size) { ObjTypes.typeOf(labels[it], hasScooter) }

        val model = loadModel(context, modelAsset)

        var eng = "CPU"
        var delegate: GpuDelegate? = null
        val options = Interpreter.Options()
        try {
            val compat = CompatibilityList()
            if (useGpu && compat.isDelegateSupportedOnThisDevice) {
                val gpuOpts = compat.bestOptionsForThisDevice
                gpuOpts.setPrecisionLossAllowed(true)   // fp16 運算，Mali 上較快
                gpuOpts.setInferencePreference(GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
                delegate = GpuDelegate(gpuOpts)
                options.addDelegate(delegate)
                eng = "GPU"
            } else {
                val n = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
                options.setNumThreads(n)
                options.setUseXNNPACK(true)   // CPU 走 XNNPACK（int8 模型也吃）
                eng = "CPU(x$n)"
            }
        } catch (t: Throwable) {
            Log.w(TAG, "GPU delegate 初始化失敗，改用 CPU：${t.message}")
            delegate?.close()
            delegate = null
            val n = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
            options.setNumThreads(n)
            eng = "CPU(x$n)"
        }
        gpuDelegate = delegate

        interpreter = try {
            Interpreter(model, options)
        } catch (t: Throwable) {
            // GPU 建 interpreter 也可能失敗，徹底退回 CPU
            Log.w(TAG, "GPU interpreter 建立失敗，退回 CPU：${t.message}")
            gpuDelegate?.close()
            gpuDelegate = null
            eng = "CPU(fallback)"
            Interpreter(model, Interpreter.Options().apply { setNumThreads(4) })
        }
        engineName = eng

        val inShape = interpreter.getInputTensor(0).shape() // NCHW [1,3,H,W] 或 NHWC [1,H,W,3]
        chFirst = inShape[1] == 3
        inputSize = if (chFirst) inShape[2] else inShape[1]

        val outShape = interpreter.getOutputTensor(0).shape() // [1, A, B]
        val a = outShape[1]
        val b = outShape[2]
        featCount = min(a, b)       // 84（= 4 + 80）
        boxCount = max(a, b)        // 8400
        transposed = (a == featCount)

        inputBuffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4)
            .order(ByteOrder.nativeOrder())
        letterboxBmp = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        letterboxCanvas = Canvas(letterboxBmp)
        pixels = IntArray(inputSize * inputSize)
        output = Array(1) { Array(a) { FloatArray(b) } }

        Log.i(TAG, "engine=$engineName input=$inputSize out=[$a,$b] transposed=$transposed")
    }

    private fun loadModel(context: Context, asset: String): MappedByteBuffer {
        val afd = context.assets.openFd(asset)
        FileInputStream(afd.fileDescriptor).use { fis ->
            return fis.channel.map(
                FileChannel.MapMode.READ_ONLY,
                afd.startOffset,
                afd.declaredLength
            )
        }
    }

    /**
     * 回傳偵測結果（座標在原始 bitmap 空間），以及本次純推論耗時（ms）。
     * roi 不為 null 時只偵測該區域（直接從原圖裁切縮放，不額外配置 bitmap）。
     */
    fun detect(bitmap: Bitmap, roi: Rect? = null): Pair<List<Detection>, Long> {
        val srcW = roi?.width() ?: bitmap.width
        val srcH = roi?.height() ?: bitmap.height
        return detectWith(srcW, srcH, (roi?.left ?: 0).toFloat(), (roi?.top ?: 0).toFloat()) { buf, nchw, size, lb ->
            letterboxCanvas.drawColor(Color.rgb(114, 114, 114))
            val dst = RectF(lb.padX, lb.padY, lb.padX + srcW * lb.scale, lb.padY + srcH * lb.scale)
            letterboxCanvas.drawBitmap(bitmap, roi, dst, letterboxPaint)

            letterboxBmp.getPixels(pixels, 0, size, 0, 0, size, size)
            if (nchw) {
                // NCHW：先全部 R，再全部 G，再全部 B
                for (p in pixels) buf.putFloat(((p shr 16) and 0xFF) / 255f)
                for (p in pixels) buf.putFloat(((p shr 8) and 0xFF) / 255f)
                for (p in pixels) buf.putFloat((p and 0xFF) / 255f)
            } else {
                // NHWC：每像素 RGB 交錯
                for (p in pixels) {
                    buf.putFloat(((p shr 16) and 0xFF) / 255f)
                    buf.putFloat(((p shr 8) and 0xFF) / 255f)
                    buf.putFloat((p and 0xFF) / 255f)
                }
            }
        }
    }

    /**
     * 由呼叫端自己填輸入張量（例如直接從相機 YUV 取樣，不經 Bitmap）。
     * srcW×srcH 是被偵測區域的大小，結果座標再加上 (offX, offY)。
     * fill 收到已 rewind 的 buffer、是否 NCHW、輸入邊長、letterbox 參數；未填的地方要自己補灰 (114/255)。
     */
    fun detectWith(
        srcW: Int, srcH: Int, offX: Float, offY: Float,
        fill: (buf: ByteBuffer, nchw: Boolean, size: Int, lb: Letterbox) -> Unit
    ): Pair<List<Detection>, Long> {
        // letterbox：等比縮放至 inputSize，四周填灰(114)
        val scale = min(inputSize / srcW.toFloat(), inputSize / srcH.toFloat())
        val padX = (inputSize - srcW * scale) / 2f
        val padY = (inputSize - srcH * scale) / 2f

        inputBuffer.rewind()
        fill(inputBuffer, chFirst, inputSize, Letterbox(scale, padX, padY))
        inputBuffer.rewind()

        val t0 = System.nanoTime()
        interpreter.run(inputBuffer, output)
        val inferMs = (System.nanoTime() - t0) / 1_000_000

        val out = output[0]
        val numClasses = featCount - 4
        val candidates = ArrayList<Detection>()

        for (i in 0 until boxCount) {
            fun feat(f: Int): Float = if (transposed) out[f][i] else out[i][f]

            // 找最高分類別
            var bestCls = -1
            var bestScore = 0f
            for (c in 0 until numClasses) {
                val s = feat(4 + c)
                if (s > bestScore) { bestScore = s; bestCls = c }
            }
            if (bestScore < confThreshold) continue
            val type = types.getOrNull(bestCls) ?: continue

            // 座標：相容「像素(0..inputSize)」與「正規化(0..1)」兩種輸出
            var cx = feat(0); var cy = feat(1); var w = feat(2); var h = feat(3)
            if (w <= 1.5f && h <= 1.5f) {
                cx *= inputSize; cy *= inputSize; w *= inputSize; h *= inputSize
            }

            // input 空間 xyxy → 去 letterbox → 原圖空間
            var x1 = (cx - w / 2f - padX) / scale
            var y1 = (cy - h / 2f - padY) / scale
            var x2 = (cx + w / 2f - padX) / scale
            var y2 = (cy + h / 2f - padY) / scale
            x1 = x1.coerceIn(0f, srcW.toFloat())
            y1 = y1.coerceIn(0f, srcH.toFloat())
            x2 = x2.coerceIn(0f, srcW.toFloat())
            y2 = y2.coerceIn(0f, srcH.toFloat())
            if (x2 - x1 < 1f || y2 - y1 < 1f) continue

            val label = labels.getOrElse(bestCls) { "cls$bestCls" }
            candidates.add(Detection(
                RectF(x1 + offX, y1 + offY, x2 + offX, y2 + offY), label, bestCls, bestScore, type
            ))
        }

        return Pair(nms(candidates), inferMs)
    }

    private fun nms(dets: List<Detection>): List<Detection> {
        val sorted = dets.sortedByDescending { it.score }.toMutableList()
        val kept = ArrayList<Detection>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept.add(best)
            val it = sorted.iterator()
            while (it.hasNext()) {
                val d = it.next()
                // 同一群（四輪車 / 兩輪車 / 其他各自）互相抑制：同一台車常同時被標成 car 和 truck
                if (ObjTypes.group(d.type) == ObjTypes.group(best.type) && iou(best.rect, d.rect) > iouThreshold) {
                    it.remove()
                }
            }
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val x1 = max(a.left, b.left)
        val y1 = max(a.top, b.top)
        val x2 = min(a.right, b.right)
        val y2 = min(a.bottom, b.bottom)
        val inter = max(0f, x2 - x1) * max(0f, y2 - y1)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun close() {
        try { interpreter.close() } catch (_: Throwable) {}
        try { gpuDelegate?.close() } catch (_: Throwable) {}
    }
}
