package com.vito.avmprobe

import android.content.Context
import android.os.SystemClock
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * 魚眼車道線分割模型（自己訓練的，tools/lane_label）：一顆鏡頭 480x272 RGB → 每個像素「是車道線」的機率。
 *
 * 四顆鏡頭輪流跑（每次一顆），結果留著；[bevProb] 用俯視圖查表把四顆的機率拼成俯視圖，
 * 交給 [RoadExtractor] 取代「用亮度找白漆」那一步（後面配對成線、虛實線、顏色、偏離警示都照舊）。
 *
 * 模型只用來畫線與提示，不拿去控制車子。
 */
class LaneSeg(ctx: Context, asset: String = ASSET) {
    companion object {
        const val ASSET = "lane_seg.tflite"
        const val QW = 960; const val QH = 540
        const val TTL_MS = 1500L
        fun available(ctx: Context): Boolean = try { ctx.assets.openFd(ASSET).close(); true } catch (_: Throwable) { false }
    }

    private val interp: Interpreter
    /** 模型輸入大小（從模型讀：480x272 或縮小版 224x128）；[SH] = 實際放影像的列數，下面補黑（訓練時 960x540 → 480x270 再補 2 列）。 */
    val W: Int; val H: Int; private val SH: Int
    private val inBuf: ByteBuffer
    private val outBuf: ByteBuffer   // 背景、車道線兩類分數
    private val argb: IntArray
    /** 每顆鏡頭（quad 0 前、1 後、2 左、3 右）最近一次的機率圖，與時間。 */
    val prob: Array<FloatArray>
    val probAt = LongArray(4)
    @Volatile var lastMs = 0L; private set
    private var next = 0

    init {
        val afd = ctx.assets.openFd(asset)
        val model = FileInputStream(afd.fileDescriptor).use { it.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength) }
        interp = Interpreter(model, Interpreter.Options().apply { setNumThreads(2); setUseXNNPACK(true) })
        val shp = interp.getInputTensor(0).shape()          // [1, H, W, 3]
        H = shp[1]; W = shp[2]; SH = Math.round(H * 270f / 272f)
        inBuf = ByteBuffer.allocateDirect(4 * W * H * 3).order(ByteOrder.nativeOrder())
        outBuf = ByteBuffer.allocateDirect(4 * W * H * 2).order(ByteOrder.nativeOrder())
        argb = IntArray(W * SH)
        prob = Array(4) { FloatArray(W * H) }
    }

    /** 影格執行緒上：把下一顆鏡頭的畫面取樣進來（幾毫秒）。回傳是哪顆鏡頭。 */
    fun grab(frame: YuvFrame): Int {
        val q = next; next = (next + 1) % 4
        val ox = (q % 2) * QW; val oy = (q / 2) * QH
        frame.cropArgb(ox, oy, ox + QW, oy + QH, W, SH, argb)
        inBuf.rewind()
        for (p in 0 until W * H) {
            if (p < W * SH) {
                val c = argb[p]
                inBuf.putFloat(((c shr 16) and 255) / 255f); inBuf.putFloat(((c shr 8) and 255) / 255f); inBuf.putFloat((c and 255) / 255f)
            } else { inBuf.putFloat(0f); inBuf.putFloat(0f); inBuf.putFloat(0f) }
        }
        return q
    }

    /** 背景執行緒上：跑模型（[grab] 之後呼叫）。 */
    fun infer(q: Int) {
        val t0 = SystemClock.elapsedRealtime()
        inBuf.rewind(); outBuf.rewind()
        interp.run(inBuf, outBuf)
        outBuf.rewind()
        val dst = prob[q]
        for (p in 0 until W * H) {
            val bg = outBuf.getFloat(); val ln = outBuf.getFloat()
            dst[p] = (1.0 / (1.0 + kotlin.math.exp((bg - ln).toDouble()))).toFloat()   // 兩類 softmax = sigmoid(差)
        }
        probAt[q] = SystemClock.elapsedRealtime()
        lastMs = probAt[q] - t0
    }

    private var bevIdx: IntArray? = null
    private var bevKey = 0L

    /**
     * 拼成俯視圖的機率（跟 [lut] 同大小；看不到、或那顆鏡頭的結果太舊 = 0）。
     * [lut] 是 [AvmGeometry.buildBevLut] 的結果（俯視圖像素 → 整幀像素索引），[frameW] 整幀寬。
     */
    fun bevProb(lut: IntArray, frameW: Int, out: FloatArray, now: Long) {
        val key = (lut.size.toLong() shl 20) xor frameW.toLong() xor System.identityHashCode(lut).toLong()
        var idx = bevIdx
        if (idx == null || idx.size != lut.size || bevKey != key) {
            idx = IntArray(lut.size)
            for (k in lut.indices) {
                val p = lut[k]
                if (p < 0) { idx[k] = -1; continue }
                val x = p % frameW; val y = p / frameW
                val q = (x / QW) + 2 * (y / QH)
                val u = ((x % QW) * W / QW).coerceAtMost(W - 1); val v = ((y % QH) * SH / QH).coerceAtMost(SH - 1)
                idx[k] = (q shl 24) or (v * W + u)
            }
            bevIdx = idx; bevKey = key
        }
        val fresh = BooleanArray(4) { now - probAt[it] < TTL_MS }
        for (k in idx.indices) {
            val e = idx[k]
            out[k] = if (e < 0) 0f else { val q = e ushr 24; if (fresh[q]) prob[q][e and 0xFFFFFF] else 0f }
        }
    }

    /** 四顆鏡頭都有夠新的結果。 */
    fun ready(now: Long): Boolean = (0 until 4).all { now - probAt[it] < TTL_MS }

    fun close() { try { interp.close() } catch (_: Throwable) {} }
}
