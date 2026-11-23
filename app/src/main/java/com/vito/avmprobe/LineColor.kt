package com.vito.avmprobe

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 車道線白／黃：用「線上的顏色 vs 旁邊柏油的顏色」＋「線在哪」判斷（tools/lane_label/line_color_ds.py 取特徵、邏輯迴歸）。
 *
 * 以前是單一像素的固定 RGB 門檻（r≥90、g≥70、r−b≥45…），實測黃實線只認對 9～14%（晚上車燈／路燈把漆和柏油一起染色，
 * 黃漆看起來偏白）。3070 條審過的線、依路段切開驗證：
 *   舊門檻：黃 14%、白 100%、整體 72% → 這個：黃 83%、白 87%、整體 86%（晚上黃 77%、白天黃 95%）。
 * 位置的權重最大（台灣：國道最左邊的實線、雙向道路的中線是黃的）；多車道左側的白虛線仍有 96% 認成白。
 *
 * 取樣：沿線每 0.1 m、離最近的鏡頭 3.5 m 內的點；線上 3x3 平均，旁邊取垂直 ±0.30、±0.45 m 的中位數。純邏輯，可單元測試。
 */
object LineColor {
    private val MU = floatArrayOf(0.350976f, 0.3384f, 0.310624f, 0.343889f, 0.338268f, 0.317843f, 12.6732f, 7.03194f, 5.64129f, 0.0462734f, 25.033f, 0.0143055f, 148.178f, 0.716287f, -0.0751702f, 0.514984f, 0.543648f, 0.541694f)
    private val SD = floatArrayOf(0.0229696f, 0.0124171f, 0.027653f, 0.0159788f, 0.0125393f, 0.0220967f, 15.6361f, 10.2843f, 14.2242f, 0.10244f, 28.9367f, 0.0412186f, 43.9056f, 0.4508f, 2.03714f, 0.499776f, 0.498092f, 0.49826f)
    private val W = floatArrayOf(-0.0674479f, -0.00780923f, 0.0595357f, -0.0130181f, 0.337455f, -0.182084f, 0.356711f, -0.198239f, 0.535447f, 0.634634f, 0.0594602f, -0.170093f, -0.234944f, -0.557117f, -0.708378f, 1.44563f, 0.942389f, -0.097081f)
    private const val B = -2.5152690f

    /** 鏡頭大約的位置（車身座標 m）：前、後、左後照鏡、右後照鏡。 */
    private val CAMS = arrayOf(floatArrayOf(0f, 2.42f), floatArrayOf(0f, -2.42f), floatArrayOf(-0.93f, 0.9f), floatArrayOf(0.93f, 0.9f))

    /**
     * 線的顏色特徵（14 個：線 rgb 比例、旁邊 rgb 比例、黃度、比旁邊黃多少、亮度差…、晚上）；點太少回 null。
     * 線的參數跟 RoadExtractor 一樣：法向角 [phi]、法向距離 [off]、沿線範圍 [s0]..[s1]；俯視圖 [rgb]（0xRRGGBB）[w]x[h]、每像素 [m] 公尺、原點像素 ([ox], [oy])。
     */
    fun features(rgb: IntArray, w: Int, h: Int, m: Double, ox: Double, oy: Double,
                 phi: Double, off: Double, s0: Double, s1: Double, night: Boolean): FloatArray? {
        val c = cos(phi); val s = sin(phi)
        val onR = ArrayList<Float>(); val onG = ArrayList<Float>(); val onB = ArrayList<Float>()
        val bgR = ArrayList<Float>(); val bgG = ArrayList<Float>(); val bgB = ArrayList<Float>()
        val tmp = FloatArray(3); val bb = Array(4) { FloatArray(3) }
        var u = s0
        while (u <= s1) {
            val x = off * c + u * s; val z = -off * s + u * c
            u += 0.1
            if (abs(z) > 6.5) continue
            if (CAMS.none { hypot(x - it[0], z - it[1]) <= 3.5 }) continue
            if (!avg(rgb, w, h, m, ox, oy, x, z, tmp)) continue
            var nb = 0
            for (o in doubleArrayOf(0.30, 0.45, -0.30, -0.45)) if (avg(rgb, w, h, m, ox, oy, x + o * c, z - o * s, bb[nb])) nb++
            if (nb == 0) continue
            onR.add(tmp[0]); onG.add(tmp[1]); onB.add(tmp[2])
            bgR.add(median(FloatArray(nb) { bb[it][0] })); bgG.add(median(FloatArray(nb) { bb[it][1] })); bgB.add(median(FloatArray(nb) { bb[it][2] }))
        }
        if (onR.size < 5) return null
        val lr = median(onR.toFloatArray()); val lg = median(onG.toFloatArray()); val lb = median(onB.toFloatArray())
        val br = median(bgR.toFloatArray()); val bg = median(bgG.toFloatArray()); val bbv = median(bgB.toFloatArray())
        val ls = lr + lg + lb + 1e-6f; val bs = br + bg + bbv + 1e-6f
        val ly = (lr + lg) / 2 - lb; val by = (br + bg) / 2 - bbv
        val dyn = median(FloatArray(onR.size) { i ->
            (((onR[i] + onG[i]) / 2 - onB[i]) - ((bgR[i] + bgG[i]) / 2 - bgB[i])) / ((bgR[i] + bgG[i] + bgB[i]) / 3 + 10f)
        })
        return floatArrayOf(lr / ls, lg / ls, lb / ls, br / bs, bg / bs, bbv / bs, ly, by, ly - by, dyn, (ls - bs) / 3,
            (lr / ls - lb / ls) - (br / bs - bbv / bs), ls / 3, if (night) 1f else 0f)
    }

    /** 是黃線的機率：[color] = [features] 的結果、[x0] = 線在車子正旁邊（z = 0）的橫向位置、最左／最右（這一幀找到的白黃線裡）。 */
    fun yellowProb(color: FloatArray, x0: Double, leftmost: Boolean, rightmost: Boolean): Float {
        val f = FloatArray(18)
        System.arraycopy(color, 0, f, 0, 14)
        f[14] = x0.toFloat(); f[15] = if (x0 < 0) 1f else 0f; f[16] = if (leftmost) 1f else 0f; f[17] = if (rightmost) 1f else 0f
        var z = B
        for (i in 0 until 18) z += W[i] * (f[i] - MU[i]) / SD[i]
        return (1.0 / (1.0 + exp(-z.toDouble()))).toFloat()
    }

    private fun avg(rgb: IntArray, w: Int, h: Int, m: Double, ox: Double, oy: Double, x: Double, z: Double, out: FloatArray): Boolean {
        val i = floor(x / m + ox).toInt(); val j = floor(oy - z / m).toInt()
        if (i < 1 || j < 1 || i >= w - 1 || j >= h - 1) return false
        var r = 0; var g = 0; var b = 0; var n = 0
        for (dj in -1..1) for (di in -1..1) {
            val v = rgb[(j + dj) * w + i + di]
            if (v == 0) continue                     // 俯視圖沒畫到的地方（黑）
            r += (v shr 16) and 255; g += (v shr 8) and 255; b += v and 255; n++
        }
        if (n < 5) return false
        out[0] = r.toFloat() / n; out[1] = g.toFloat() / n; out[2] = b.toFloat() / n
        return true
    }

    private fun median(a: FloatArray): Float { a.sort(); return if (a.size % 2 == 1) a[a.size / 2] else (a[a.size / 2 - 1] + a[a.size / 2]) / 2 }
}
