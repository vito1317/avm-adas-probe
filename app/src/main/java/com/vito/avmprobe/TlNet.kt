package com.vito.avmprobe

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp

/**
 * 紅綠燈燈號模型（tools/det_label/tl_train.py，LISA 訓練、直式＋轉 90° 的橫式）：燈頭裁切 48x48 → 燈色＋箭頭。
 * LISA 驗證：燈色 99.2%（原本的顏色規則 83.1%）、箭頭 95%。
 * 只拿來顯示與提示（紅燈停等、綠燈起步），不控制車子。熄燈仍用 [TrafficLightState] 的暗燈規則判。
 */
class TlNet(ctx: Context) {
    companion object {
        const val ASSET = "tl_state.tflite"
        const val S = 48
        private val COLORS = arrayOf("red", "yellow", "green")
        private val ARROWS = arrayOf<String?>(null, "left", "forward")
        fun available(ctx: Context): Boolean = try { ctx.assets.openFd(ASSET).close(); true } catch (_: Throwable) { false }
    }

    data class Result(val color: String, val colorProb: Float, val arrow: String?)

    private val interp: Interpreter
    private val inBuf = ByteBuffer.allocateDirect(4 * S * S * 3).order(ByteOrder.nativeOrder())
    private val out = Array(1) { FloatArray(6) }

    init {
        val afd = ctx.assets.openFd(ASSET)
        val model = FileInputStream(afd.fileDescriptor).use { it.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength) }
        interp = Interpreter(model, Interpreter.Options().apply { setNumThreads(1); setUseXNNPACK(true) })
    }

    /** [argb] 是燈頭框的裁切（w×h，框四周已稍微放大）；等比縮進 48x48、四周補黑（跟訓練一樣）。 */
    fun classify(argb: IntArray, w: Int, h: Int): Result {
        val sc = minOf(S.toFloat() / w, S.toFloat() / h)
        val nw = (w * sc).toInt().coerceIn(1, S); val nh = (h * sc).toInt().coerceIn(1, S)
        val ox = (S - nw) / 2; val oy = (S - nh) / 2
        inBuf.rewind()
        for (j in 0 until S) for (i in 0 until S) {
            val ii = i - ox; val jj = j - oy
            if (ii in 0 until nw && jj in 0 until nh) {
                val sx = (ii * w / nw).coerceIn(0, w - 1); val sy = (jj * h / nh).coerceIn(0, h - 1)
                val c = argb[sy * w + sx]
                inBuf.putFloat(((c shr 16) and 255) / 255f); inBuf.putFloat(((c shr 8) and 255) / 255f); inBuf.putFloat((c and 255) / 255f)
            } else { inBuf.putFloat(0f); inBuf.putFloat(0f); inBuf.putFloat(0f) }
        }
        inBuf.rewind()
        interp.run(inBuf, out)
        val o = out[0]
        fun soft(a: Int, b: Int): Pair<Int, Float> {
            var mx = Float.NEGATIVE_INFINITY; var arg = a
            for (k in a until b) if (o[k] > mx) { mx = o[k]; arg = k }
            var s = 0.0; for (k in a until b) s += exp((o[k] - mx).toDouble())
            return Pair(arg - a, (1.0 / s).toFloat())
        }
        val (ci, cp) = soft(0, 3); val (ai, ap) = soft(3, 6)
        return Result(COLORS[ci], cp, if (ap >= 0.6f) ARROWS[ai] else null)
    }
}
