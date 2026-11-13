package com.vito.avmprobe

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * 車頭朝向分類器（tools/det_label/orient_train.py）：車框裁切 64x64 → 8 個方向的分數（見 [OrientHeading]）。
 * 輸入做法跟訓練一樣：車框四周各加 15%，等比縮到放得進 64x64、置中，四周補灰 114。
 * MobileNetV3-Small、64x64，一台車約 1～2 ms；只在偵測執行緒上、每次最多跑 [MAX_PER_CALL] 台。
 * 只用來畫 3D 車子的方向，不拿去控制車子。
 */
class OrientNet(ctx: Context) {
    companion object {
        const val ASSET = "orient.tflite"
        const val S = 64
        const val MAX_PER_CALL = 4
        fun available(ctx: Context): Boolean = try { ctx.assets.openFd(ASSET).close(); true } catch (_: Throwable) { false }
    }

    private val interp: Interpreter
    private val inBuf = ByteBuffer.allocateDirect(4 * S * S * 3).order(ByteOrder.nativeOrder())
    private val out = Array(1) { FloatArray(OrientHeading.BINS) }
    private val argb = IntArray(S * S)

    init {
        val afd = ctx.assets.openFd(ASSET)
        val model = FileInputStream(afd.fileDescriptor).use { it.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength) }
        interp = Interpreter(model, Interpreter.Options().apply { setNumThreads(1); setUseXNNPACK(true) })
    }

    /** 整幀座標的車框 → (相對角 rad, 信心)。 */
    fun classify(frame: YuvFrame, l: Float, t: Float, r: Float, b: Float): Pair<Double, Float> {
        val pw = (r - l) * 0.15f; val ph = (b - t) * 0.15f
        val x0 = (l - pw).coerceAtLeast(0f); val y0 = (t - ph).coerceAtLeast(0f)
        val x1 = (r + pw).coerceAtMost(frame.width.toFloat()); val y1 = (b + ph).coerceAtMost(frame.height.toFloat())
        val cw = (x1 - x0).coerceAtLeast(1f); val ch = (y1 - y0).coerceAtLeast(1f)
        val sc = minOf(S / cw, S / ch)
        val w = Math.round(cw * sc).coerceIn(1, S); val h = Math.round(ch * sc).coerceIn(1, S)
        frame.cropArgb(x0.toInt(), y0.toInt(), x1.toInt(), y1.toInt(), w, h, argb)
        val ox = (S - w) / 2; val oy = (S - h) / 2
        val g = 114f / 255f
        inBuf.rewind()
        for (j in 0 until S) for (i in 0 until S) {
            val ii = i - ox; val jj = j - oy
            if (ii in 0 until w && jj in 0 until h) {
                val c = argb[jj * w + ii]
                inBuf.putFloat(((c shr 16) and 255) / 255f); inBuf.putFloat(((c shr 8) and 255) / 255f); inBuf.putFloat((c and 255) / 255f)
            } else { inBuf.putFloat(g); inBuf.putFloat(g); inBuf.putFloat(g) }
        }
        inBuf.rewind()
        interp.run(inBuf, out)
        return OrientHeading.relFromLogits(out[0])
    }
}
