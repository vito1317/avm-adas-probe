package com.vito.avmprobe

import android.media.Image
import java.nio.ByteBuffer

/**
 * 相機 YUV_420_888 的一幀：三個平面複製進可重用的 ByteArray（隨機取樣比 ByteBuffer.get 快很多），
 * 之後 Image 就能馬上 close。只在處理該幀的執行緒使用。
 * 色彩換算用 JFIF（BT.601 全範圍），Android 相機 YUV 輸出的慣例。
 */
class YuvFrame {
    var width = 0; private set
    var height = 0; private set
    private var y = ByteArray(0)
    private var u = ByteArray(0)
    private var v = ByteArray(0)
    private var yRow = 0
    private var yPix = 1
    private var uvRow = 0
    private var uvPix = 1

    fun load(img: Image) {
        width = img.width
        height = img.height
        val p = img.planes
        y = copy(p[0].buffer, y)
        u = copy(p[1].buffer, u)
        v = copy(p[2].buffer, v)
        yRow = p[0].rowStride; yPix = p[0].pixelStride
        uvRow = p[1].rowStride; uvPix = p[1].pixelStride   // U/V 平面保證 stride 相同
    }

    private fun copy(b: ByteBuffer, dst: ByteArray): ByteArray {
        b.rewind()
        val n = b.remaining()
        val out = if (dst.size >= n) dst else ByteArray(n)
        b.get(out, 0, n)
        return out
    }

    fun lumaAt(x: Int, yy: Int): Int = y[yy * yRow + x * yPix].toInt() and 0xFF

    fun argbAt(x: Int, yy: Int): Int {
        val ci = (yy shr 1) * uvRow + (x shr 1) * uvPix
        return yuvToArgb(lumaAt(x, yy), u[ci].toInt() and 0xFF, v[ci].toInt() and 0xFF)
    }

    /**
     * 把來源矩形 (x0,y0,sw,sh)（整幀座標）letterbox 進模型輸入。Y 取 2×2 平均稍微抗鋸齒，色度取最近點。
     */
    fun fillModelInput(
        x0: Int, y0: Int, sw: Int, sh: Int,
        buf: ByteBuffer, nchw: Boolean, size: Int, lb: Letterbox
    ) {
        val gray = 114f / 255f
        val plane = size * size
        val xMax = width - 1
        val yMax = height - 1
        if (colMap.size < size) colMap = IntArray(size)
        for (i in 0 until size) {
            val s = ((i + 0.5f - lb.padX) / lb.scale).toInt()
            colMap[i] = if (i + 0.5f < lb.padX || s >= sw) -1 else x0 + s
        }
        for (j in 0 until size) {
            val s = ((j + 0.5f - lb.padY) / lb.scale).toInt()
            val sy = if (j + 0.5f < lb.padY || s >= sh) -1 else y0 + s
            val rowBase = j * size
            for (i in 0 until size) {
                val sx = colMap[i]
                var r = gray; var g = gray; var b = gray
                if (sx >= 0 && sy >= 0) {
                    val x1 = if (sx < xMax) sx + 1 else sx
                    val y1 = if (sy < yMax) sy + 1 else sy
                    val yv = (lumaAt(sx, sy) + lumaAt(x1, sy) + lumaAt(sx, y1) + lumaAt(x1, y1) + 2) shr 2
                    val ci = (sy shr 1) * uvRow + (sx shr 1) * uvPix
                    val p = yuvToArgb(yv, u[ci].toInt() and 0xFF, v[ci].toInt() and 0xFF)
                    r = ((p shr 16) and 0xFF) / 255f
                    g = ((p shr 8) and 0xFF) / 255f
                    b = (p and 0xFF) / 255f
                }
                val k = rowBase + i
                if (nchw) {
                    buf.putFloat(k * 4, r)
                    buf.putFloat((plane + k) * 4, g)
                    buf.putFloat((2 * plane + k) * 4, b)
                } else {
                    buf.putFloat(k * 12, r)
                    buf.putFloat(k * 12 + 4, g)
                    buf.putFloat(k * 12 + 8, b)
                }
            }
        }
    }

    private var colMap = IntArray(0)

    /** 依查表取樣成 ARGB（俯視圖拼接）。lut 值 = 整幀像素索引 y*width+x，-1 = 無畫面（黑）。 */
    fun sampleLut(lut: IntArray, out: IntArray) {
        val w = width
        for (k in lut.indices) {
            val p = lut[k]
            out[k] = if (p < 0) BLACK else argbAt(p % w, p / w)
        }
    }

    /** 把整幀矩形縮放取樣成 outW×outH 的 ARGB（紅綠燈燈色判斷用）。 */
    fun cropArgb(x0: Int, y0: Int, x1: Int, y1: Int, outW: Int, outH: Int, out: IntArray) {
        val sw = (x1 - x0).coerceAtLeast(1)
        val sh = (y1 - y0).coerceAtLeast(1)
        for (j in 0 until outH) {
            val sy = (y0 + (j + 0.5f) * sh / outH).toInt().coerceIn(0, height - 1)
            for (i in 0 until outW) {
                val sx = (x0 + (i + 0.5f) * sw / outW).toInt().coerceIn(0, width - 1)
                out[j * outW + i] = argbAt(sx, sy)
            }
        }
    }

    /** 整幀每 step 點取一點的 ARGB（預覽退路：雙串流不支援時自己畫畫面）。 */
    fun downsampleArgb(step: Int, out: IntArray) {
        val ow = width / step
        val oh = height / step
        for (j in 0 until oh) for (i in 0 until ow) out[j * ow + i] = argbAt(i * step, j * step)
    }

    /** 轉成 NV21（YuvImage 存 JPEG 用）。 */
    fun toNv21(): ByteArray {
        val w = width
        val h = height
        val out = ByteArray(w * h * 3 / 2)
        var k = 0
        for (yy in 0 until h) {
            val base = yy * yRow
            for (x in 0 until w) out[k++] = y[base + x * yPix]
        }
        for (yy in 0 until h / 2) {
            for (x in 0 until w / 2) {
                val ci = yy * uvRow + x * uvPix
                out[k++] = v[ci]
                out[k++] = u[ci]
            }
        }
        return out
    }

    companion object {
        const val BLACK = 0xFF000000.toInt()

        /** BT.601 全範圍 YCbCr → ARGB，定點運算。 */
        fun yuvToArgb(yv: Int, cb: Int, cr: Int): Int {
            val d = cb - 128
            val e = cr - 128
            var r = yv + ((91881 * e) shr 16)
            var g = yv - ((22554 * d + 46802 * e) shr 16)
            var b = yv + ((116130 * d) shr 16)
            r = if (r < 0) 0 else if (r > 255) 255 else r
            g = if (g < 0) 0 else if (g > 255) 255 else g
            b = if (b < 0) 0 else if (b > 255) 255 else b
            return BLACK or (r shl 16) or (g shl 8) or b
        }
    }
}
