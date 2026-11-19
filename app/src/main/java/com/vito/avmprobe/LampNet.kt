package com.vito.avmprobe

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp

/**
 * 車燈分類器（tools/det_label/lamp_train.py）：車框裁切 96x96 → 煞車燈、畫面左側方向燈、畫面右側方向燈「亮不亮」（各自 sigmoid）。
 * 輸入做法跟訓練一樣：車框四周各加 15%，等比縮到放得進 96x96、置中，四周補灰 114。
 * 第二版加了車燈事件時存的自己魚眼車框：煞車燈在自己的驗證上準很多；方向燈的訓練資料還太少，不拿來用（方向燈仍用 [LeadLightDetector] 的閃爍規則）。
 * MobileNetV3-Small、96x96，一台約 2～3 ms；只看正前方那台車。只用來顯示提示，不拿去控制車子。
 */
class LampNet(ctx: Context) {
    companion object {
        const val ASSET = "lamp.tflite"
        const val S = 96
        fun available(ctx: Context): Boolean = try { ctx.assets.openFd(ASSET).close(); true } catch (_: Throwable) { false }
    }

    private val interp: Interpreter
    private val inBuf = ByteBuffer.allocateDirect(4 * S * S * 3).order(ByteOrder.nativeOrder())
    private val out = Array(1) { FloatArray(3) }

    init {
        val afd = ctx.assets.openFd(ASSET)
        val model = FileInputStream(afd.fileDescriptor).use { it.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength) }
        interp = Interpreter(model, Interpreter.Options().apply { setNumThreads(1); setUseXNNPACK(true) })
    }

    /** [argb] 是 size×size 的畫面，車框 [l]..[b]（像素）→ (煞車、左方向燈、右方向燈) 的機率。 */
    fun classify(argb: IntArray, size: Int, l: Float, t: Float, r: Float, b: Float): FloatArray {
        val pw = (r - l) * 0.15f; val ph = (b - t) * 0.15f
        val x0 = (l - pw).coerceAtLeast(0f); val y0 = (t - ph).coerceAtLeast(0f)
        val x1 = (r + pw).coerceAtMost(size.toFloat()); val y1 = (b + ph).coerceAtMost(size.toFloat())
        val cw = (x1 - x0).coerceAtLeast(1f); val ch = (y1 - y0).coerceAtLeast(1f)
        val sc = minOf(S / cw, S / ch)
        val w = Math.round(cw * sc).coerceIn(1, S); val h = Math.round(ch * sc).coerceIn(1, S)
        val ox = (S - w) / 2; val oy = (S - h) / 2
        val g = 114f / 255f
        inBuf.rewind()
        for (j in 0 until S) for (i in 0 until S) {
            val ii = i - ox; val jj = j - oy
            if (ii in 0 until w && jj in 0 until h) {
                // 最近鄰取樣（縮小時取格子中心）
                val sx = (x0 + (ii + 0.5f) / sc).toInt().coerceIn(0, size - 1)
                val sy = (y0 + (jj + 0.5f) / sc).toInt().coerceIn(0, size - 1)
                val c = argb[sy * size + sx]
                inBuf.putFloat(((c shr 16) and 255) / 255f); inBuf.putFloat(((c shr 8) and 255) / 255f); inBuf.putFloat((c and 255) / 255f)
            } else { inBuf.putFloat(g); inBuf.putFloat(g); inBuf.putFloat(g) }
        }
        inBuf.rewind()
        interp.run(inBuf, out)
        return FloatArray(3) { (1.0 / (1.0 + exp(-out[0][it].toDouble()))).toFloat() }
    }

    fun close() { try { interp.close() } catch (_: Throwable) {} }
}
