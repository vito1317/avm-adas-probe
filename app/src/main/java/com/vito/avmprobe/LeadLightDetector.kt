package com.vito.avmprobe

import kotlin.math.abs

/**
 * 前車的煞車燈、方向燈／雙閃（看前方去畸變畫面裡正前方那台車的車尾）。
 *
 * - 車尾燈區：框的左右各 30% 寬、上下 25%～75% 高。
 * - 煞車：燈區（左右兩塊＋車尾上方中間的第三煞車燈）的「紅色能量」突然比平常高很多。
 *   紅色能量 = Σ (R − (G+B)/2) × R/255：亮到發白偏粉紅的煞車燈也算得到（以前只數「很紅」的像素，
 *   燈心過曝的煞車燈幾乎不算，結果只有紅色車身的車才偵測得到煞車——車身反光把紅色像素撐起來）。
 * - 方向燈：琥珀色（亮橘黃）像素一亮一滅，0.3～1.2 秒換一次；左右同步閃 = 雙閃，只有一邊 = 那一側的方向燈。
 * 換了一台前車（框跳很遠）就重新記。純邏輯（ARGB 陣列），可單元測試。
 */
class LeadLightDetector {

    data class State(val brake: Boolean, val hazard: Boolean, val turn: Char?)

    private var cx = Float.NaN; private var cy = Float.NaN
    private var base = Float.NaN
    private var braking = false
    private val sideOn = BooleanArray(2)
    private val toggles = Array(2) { ArrayList<Long>() }
    private val onAt = Array(2) { ArrayList<Long>() }
    private var lastAt = 0L

    fun reset() {
        cx = Float.NaN; base = Float.NaN; braking = false
        for (i in 0..1) { sideOn[i] = false; toggles[i].clear(); onAt[i].clear() }
    }

    /**
     * [argb] 是 size×size 的前方畫面，[l]..[b] 是前車的框（像素）。沒有前車時傳 null 讓它重新開始。
     */
    fun update(now: Long, argb: IntArray?, size: Int, l: Float, t: Float, r: Float, b: Float): State {
        if (argb == null || r - l < 8f || b - t < 6f) { if (now - lastAt > 1500) reset(); return State(false, false, null) }
        lastAt = now
        val ncx = (l + r) / 2f; val ncy = (t + b) / 2f
        if (!cx.isNaN() && (abs(ncx - cx) > (r - l) * 0.6f || abs(ncy - cy) > (b - t) * 0.6f)) reset()
        cx = ncx; cy = ncy

        val w = r - l; val h = b - t
        val y0 = (t + h * 0.15f).toInt().coerceIn(0, size - 1); val y1 = (t + h * 0.85f).toInt().coerceIn(y0 + 1, size)
        var energy = 0f; var amb = IntArray(2); var n = 0
        val sideN = IntArray(2)
        for (side in 0..1) {
            val x0 = (if (side == 0) l else r - w * 0.3f).toInt().coerceIn(0, size - 1)
            val x1 = (if (side == 0) l + w * 0.3f else r).toInt().coerceIn(x0 + 1, size)
            for (y in y0 until y1) for (x in x0 until x1) {
                val c = argb[y * size + x]
                val R = (c shr 16) and 255; val G = (c shr 8) and 255; val B = c and 255
                n++; sideN[side]++
                val e = R - (G + B) / 2
                val isAmb = R > 185 && G in 80..215 && B < 125 && R - G > 30 && G - B > 35 && G - B > (R - G)
                if (isAmb) amb[side]++
                else if (R >= 110 && e >= 40) energy += e * (R / 255f)
            }
        }
        // 第三煞車燈：車尾上方中間（框上緣 0～20%、中間 35%～65%）
        run {
            val x0 = (l + w * 0.35f).toInt().coerceIn(0, size - 1); val x1 = (l + w * 0.65f).toInt().coerceIn(x0 + 1, size)
            val ty0 = t.toInt().coerceIn(0, size - 1); val ty1 = (t + h * 0.2f).toInt().coerceIn(ty0 + 1, size)
            for (y in ty0 until ty1) for (x in x0 until x1) {
                val c = argb[y * size + x]
                val R = (c shr 16) and 255; val G = (c shr 8) and 255; val B = c and 255
                val e = R - (G + B) / 2
                if (R >= 110 && e >= 40) energy += e * (R / 255f)
            }
        }
        if (n == 0) return State(false, false, null)
        // 煞車：亮紅比例相對平常值
        // 煞車燈在畫面上常常只是幾個小紅點（實車：急煞也偵測不到）→ 用亮紅點的「數量」比平常多多少，
        // 不用佔燈區的比例（以前要 4%，小紅點永遠不夠）
        val score = energy
        if (base.isNaN()) base = score
        if (!braking) {
            if (score >= base * 1.6f + 150f) braking = true
            else base += 0.03f * (score - base)
        } else if (score < base * 1.25f + 75f) braking = false

        // 方向燈：琥珀色開關的時間點
        for (side in 0..1) {
            // 方向燈在畫面上常常只是一個小黃點（實車）：有幾個像素就算亮，不用佔燈區的一定比例
            val on = amb[side] >= 2 && amb[side].toFloat() / sideN[side].coerceAtLeast(1) > 0.004f
            if (on != sideOn[side]) {
                sideOn[side] = on; toggles[side].add(now)
                if (on) onAt[side].add(now)
            }
            toggles[side].removeAll { now - it > 3000 }
            onAt[side].removeAll { now - it > 3000 }
        }
        fun blinking(side: Int): Boolean {
            val tg = toggles[side]
            if (tg.size < 3) return false
            for (i in 1 until tg.size) { val d = tg[i] - tg[i - 1]; if (d < 250 || d > 1300) return false }
            return true
        }
        val bl = blinking(0); val br = blinking(1)
        // 雙閃：兩邊都在閃，而且亮起來的時間點對得上（差 < 250 ms）
        val sync = bl && br && onAt[0].isNotEmpty() && onAt[1].isNotEmpty() &&
            onAt[0].any { a -> onAt[1].any { b2 -> abs(a - b2) < 250 } }
        return State(braking, sync, if (sync) null else if (bl) 'L' else if (br) 'R' else null)
    }
}
