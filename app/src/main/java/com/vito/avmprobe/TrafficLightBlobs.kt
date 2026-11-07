package com.vito.avmprobe

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 不靠模型找紅綠燈：遠處的燈頭在魚眼影像裡只有幾個像素，物件辨識抓不到。
 * 在前上方的高解析去畸變畫面裡找「又亮又飽和的小圓點、周圍是暗的燈箱」，依色相分紅／黃／綠；
 * 同一個位置（±[TRACK_PX]）最近 5 次至少 3 次看到才算（車尾燈、招牌、反光不會穩定待在畫面上方同一點）。
 * 純邏輯（ARGB 陣列），可單元測試。
 */
class TrafficLightBlobs {

    data class Light(val state: String, val x: Float, val y: Float, val r: Float, val conf: Float)

    companion object {
        const val V_MIN = 190          // 亮度
        const val S_MIN = 0.45f        // 飽和度
        const val MIN_AREA = 3
        const val MAX_AREA = 400
        const val RING_DARK = 110      // 外圈平均亮度要低於這個（暗的燈箱）
        const val TOP_FRAC = 0.75f     // 只看畫面上面這麼多（路面、車尾燈在下面）
        const val TRACK_PX = 12f
    }

    private class Track(var x: Float, var y: Float, var state: String, var hist: Int, var conf: Float)
    private val tracks = ArrayList<Track>()

    private fun hueClass(c: Int): Char {
        val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        if (mx < V_MIN || mx == 0) return '.'
        val s = (mx - mn).toFloat() / mx
        if (s < S_MIN) return '.'
        val d = (mx - mn).toFloat()
        var h = when (mx) { r -> 60f * (((g - b) / d) % 6f); g -> 60f * ((b - r) / d + 2f); else -> 60f * ((r - g) / d + 4f) }
        if (h < 0) h += 360f
        return when {
            h < 18f || h > 340f -> 'r'
            h in 25f..55f -> 'y'
            h in 120f..200f -> 'g'     // LED 綠燈偏青
            else -> '.'
        }
    }

    /** 找這一幀的候選燈點（不含時間穩定）。 */
    // 暫存陣列重複使用（以前每幀配置好幾 MB，原廠 process 記憶體吃緊）
    private var cls = CharArray(0)
    private var seen = BooleanArray(0)
    private var stack = IntArray(0)

    fun candidates(argb: IntArray, size: Int): List<Light> {
        val lim = (size * TOP_FRAC).toInt()
        val n = size * lim
        if (cls.size < n) { cls = CharArray(n); seen = BooleanArray(n); stack = IntArray(n) }
        val cls = cls; val seen = seen; val stack = stack
        for (y in 0 until lim) for (x in 0 until size) cls[y * size + x] = hueClass(argb[y * size + x])
        java.util.Arrays.fill(seen, 0, n, false)
        val out = ArrayList<Light>()
        for (i in 0 until n) {
            if (seen[i] || cls[i] == '.') continue
            val k = cls[i]
            var sp = 0; stack[sp++] = i; seen[i] = true
            var n = 0; var x0 = size; var x1 = 0; var y0 = lim; var y1 = 0; var sx = 0L; var sy = 0L
            while (sp > 0) {
                val j = stack[--sp]; val x = j % size; val y = j / size
                n++; sx += x; sy += y; x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
                if (n > MAX_AREA) break
                for ((dx, dy) in arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= size || ny >= lim) continue
                    val q = ny * size + nx
                    if (!seen[q] && cls[q] == k) { seen[q] = true; stack[sp++] = q }
                }
            }
            if (n < MIN_AREA || n > MAX_AREA) continue
            val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
            if (bw > 3 * bh || bh > 3 * bw) continue                 // 不夠圓（招牌字、車燈條）
            if (n < 0.45f * bw * bh) continue
            // 外圈（框外 1～2 倍半徑）要暗：燈箱
            val cx = sx.toFloat() / n; val cy = sy.toFloat() / n; val rad = max(bw, bh) / 2f + 0.5f
            var sum = 0L; var cnt = 0
            val r1 = (rad * 1.6f + 1).toInt(); val r2 = (rad * 2.6f + 2).toInt()
            for (yy in (cy - r2).toInt()..(cy + r2).toInt()) for (xx in (cx - r2).toInt()..(cx + r2).toInt()) {
                if (xx < 0 || yy < 0 || xx >= size || yy >= size) continue
                val dd = max(abs(xx - cx), abs(yy - cy))
                if (dd < r1 || dd > r2) continue
                val c = argb[yy * size + xx]
                sum += max((c shr 16) and 255, max((c shr 8) and 255, c and 255)); cnt++
            }
            if (cnt == 0) continue
            val ring = sum.toFloat() / cnt
            if (ring > RING_DARK) continue
            val conf = ((RING_DARK - ring) / RING_DARK).coerceIn(0f, 1f) * 0.6f + 0.4f
            out.add(Light(when (k) { 'r' -> "red"; 'y' -> "yellow"; else -> "green" }, cx, cy, rad, conf))
        }
        return out
    }

    /** 每一幀呼叫；回傳目前穩定的燈（多個取最穩、最亮的），沒有回 null。 */
    fun update(argb: IntArray, size: Int): Light? {
        val c = candidates(argb, size)
        for (t in tracks) t.hist = (t.hist shl 1) and 0x1F
        for (l in c) {
            val t = tracks.minByOrNull { abs(it.x - l.x) + abs(it.y - l.y) }
            if (t != null && abs(t.x - l.x) < TRACK_PX && abs(t.y - l.y) < TRACK_PX) {
                t.x += 0.5f * (l.x - t.x); t.y += 0.5f * (l.y - t.y); t.hist = t.hist or 1
                t.state = l.state; t.conf = max(t.conf * 0.7f, l.conf)
            } else tracks.add(Track(l.x, l.y, l.state, 1, l.conf))
        }
        tracks.removeAll { it.hist == 0 }
        val best = tracks.filter { Integer.bitCount(it.hist) >= 3 }.maxByOrNull { Integer.bitCount(it.hist) * 10 + it.conf } ?: return null
        return Light(best.state, best.x, best.y, 0f, best.conf)
    }
}
