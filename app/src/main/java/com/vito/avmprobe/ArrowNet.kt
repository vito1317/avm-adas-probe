package com.vito.avmprobe

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * 路面箭頭分類（tools/lane_label/arrow_train.py）：前／後鏡頭投影出的路面條（跟 ground_patch.py 一樣的範圍）→ 8 類。
 *
 * 路面範圍：x ∈ [-5.4, 5.4] m 切三條（每條 3.6 m，左／中／右），前方 z ∈ [2.6, 10.6] m；後鏡頭 z ∈ [-10.6, -2.6] m，
 * 一樣「行進方向朝上」（上 = 離車近）。每條取樣成 64x128（寬x高）。只用來顯示車道用途，不拿去控制車子。
 */
class ArrowNet(ctx: Context, private val geo: AvmGeometry) {
    companion object {
        const val ASSET = "arrow.tflite"
        const val IW = 64; const val IH = 128
        const val X = 5.4; const val Z0 = 2.6; const val Z1 = 10.6
        val KINDS: Array<LaneArrowMemory.Kind?> = arrayOf(null, LaneArrowMemory.Kind.STRAIGHT, LaneArrowMemory.Kind.LEFT, LaneArrowMemory.Kind.RIGHT,
            LaneArrowMemory.Kind.STRAIGHT_LEFT, LaneArrowMemory.Kind.STRAIGHT_RIGHT, LaneArrowMemory.Kind.UTURN, LaneArrowMemory.Kind.LEFT_RIGHT)
        /** 三條路面的中心橫向位置（m）。 */
        val BAND_X = doubleArrayOf(-3.6, 0.0, 3.6)
        fun available(ctx: Context): Boolean = try { ctx.assets.openFd(ASSET).close(); true } catch (_: Throwable) { false }
    }

    data class Hit(val kind: LaneArrowMemory.Kind, val x: Float, val z: Float, val prob: Float)

    private val interp: Interpreter
    private val inBuf = ByteBuffer.allocateDirect(4 * IW * IH * 3).order(ByteOrder.nativeOrder())
    private val out = Array(1) { FloatArray(8) }
    private val argb = IntArray(IW * IH)
    // 查表：[鏡頭 0 前／1 後][條 0..2] → 整幀像素索引
    private var luts: Array<Array<IntArray>>? = null
    private var lutKey = 0L

    init {
        val afd = ctx.assets.openFd(ASSET)
        val model = FileInputStream(afd.fileDescriptor).use { it.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength) }
        interp = Interpreter(model, Interpreter.Options().apply { setNumThreads(1); setUseXNNPACK(true) })
    }

    private fun buildLuts(fw: Int, fh: Int): Array<Array<IntArray>> {
        val qw = fw / 2; val qh = fh / 2
        return Array(2) { ci ->
            val rear = ci == 1
            val cam = geo.cam(if (rear) "rear" else "front")
            Array(3) { b ->
                val lut = IntArray(IW * IH) { -1 }
                if (cam != null) {
                    val qx0 = (cam.quad % 2) * qw; val qy0 = (cam.quad / 2) * qh
                    val x0 = -X + b * (2 * X / 3)
                    for (j in 0 until IH) for (i in 0 until IW) {
                        val gx = x0 + (i + 0.5) * (2 * X / 3) / IW
                        val gz = if (rear) -(Z0 + (j + 0.5) * (Z1 - Z0) / IH) else Z1 - (j + 0.5) * (Z1 - Z0) / IH
                        val px = geo.groundToPixel(cam, gx, gz, qw, qh) ?: continue
                        lut[j * IW + i] = (qy0 + px[1].toInt()) * fw + qx0 + px[0].toInt()
                    }
                }
                lut
            }
        }
    }

    /** 跑前、後各三條（6 次，每次約 1～2 ms）；回傳夠有把握的箭頭（[minProb] 以上）。 */
    fun detect(frame: YuvFrame, minProb: Float): List<Hit> {
        val key = (frame.width.toLong() shl 32) or frame.height.toLong()
        val l = luts?.takeIf { lutKey == key } ?: buildLuts(frame.width, frame.height).also { luts = it; lutKey = key }
        val hits = ArrayList<Hit>()
        for (ci in 0..1) for (b in 0..2) {
            frame.sampleLut(l[ci][b], argb)
            inBuf.rewind()
            for (p in argb) { inBuf.putFloat(((p shr 16) and 255) / 255f); inBuf.putFloat(((p shr 8) and 255) / 255f); inBuf.putFloat((p and 255) / 255f) }
            inBuf.rewind()
            interp.run(inBuf, out)
            val lg = out[0]; var mx = Float.NEGATIVE_INFINITY; var arg = 0
            for (k in lg.indices) if (lg[k] > mx) { mx = lg[k]; arg = k }
            var sum = 0.0; for (v in lg) sum += Math.exp((v - mx).toDouble())
            val prob = (1.0 / sum).toFloat()
            val kind = KINDS[arg] ?: continue
            if (prob < minProb) continue
            val z = ((Z0 + Z1) / 2 * (if (ci == 1) -1 else 1)).toFloat()
            hits.add(Hit(kind, BAND_X[b].toFloat(), z, prob))
        }
        return hits
    }
}
