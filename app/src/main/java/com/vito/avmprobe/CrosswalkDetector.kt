package com.vito.avmprobe

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 斑馬線（純 Kotlin，可單元測試）：在公制俯視圖上找「一排並排、等間距的粗白條」。
 *
 * 1. 白漆：比周圍 1.2 m 平均亮一截、夠亮、顏色中性（不是黃線、不是車燈色）。
 * 2. 4 連通元件 → PCA：長 [STRIPE_LEN_MIN]～[STRIPE_LEN_MAX]、寬 [STRIPE_W_MIN]～[STRIPE_W_MAX]、夠實心的才算一條白條。
 *    車道虛線太細（0.10–0.15 m）、停車格線也太細，到這裡就被濾掉。
 * 3. 方向一致、肩並肩（沿白條方向錯開不到 1 m）、中心間距 [GAP_MIN]～[GAP_MAX] 一條接一條，連成至少 [MIN_STRIPES] 條 → 一塊斑馬線。
 *    車道虛線是前後排（不是肩並肩），兩條車道線間距 3 m 以上，都不會連成一排。
 *
 * 座標：x 右、z 前（m），原點車身中心。俯視圖像素 (i, j) → x = (i + 0.5 - cx)·mpp、z = (cy - j - 0.5)·mpp。
 */
class CrosswalkDetector {
    companion object {
        const val BG_HALF_M = 0.6
        const val PAINT_DY = 28
        const val PAINT_Y_MIN = 95
        const val CHROMA_MAX = 60
        const val STRIPE_LEN_MIN = 1.0
        const val STRIPE_LEN_MAX = 7.0
        const val STRIPE_W_MIN = 0.25
        const val STRIPE_W_MAX = 0.85
        const val FILL_MIN = 0.55
        const val PAR_DEG = 12.0
        const val GAP_MIN = 0.55
        const val GAP_MAX = 1.6
        const val SHIFT_MAX = 1.2
        const val MIN_STRIPES = 3

        /**
         * 畫圖用：把一塊斑馬線（四角 + 白條數）拆回一條一條白條的四邊形（x,z×4）。
         * 角點順序 (u0,v0)(u1,v0)(u1,v1)(u0,v1)：u 沿白條、v 橫跨白條。白條占每格的一半。
         */
        fun stripeQuads(p: FloatArray, n: Int): List<FloatArray> {
            if (p.size < 8 || n < 1) return emptyList()
            val ox = p[0]; val oz = p[1]
            val ux = p[2] - p[0]; val uz = p[3] - p[1]          // 沿白條
            val vx = p[6] - p[0]; val vz = p[7] - p[1]          // 橫跨
            val step = 1f / (2 * n - 1)                          // 白、空、白、…、白
            return (0 until n).map { k ->
                val a = 2 * k * step; val b = a + step
                floatArrayOf(ox + vx * a, oz + vz * a, ox + ux + vx * a, oz + uz + vz * a,
                    ox + ux + vx * b, oz + uz + vz * b, ox + vx * b, oz + vz * b)
            }
        }
    }

    /** 一塊斑馬線：四個角 (x,z)×4（依序繞一圈）、白條數、白條方向（單位向量）。 */
    data class Crosswalk(val p: FloatArray, val stripes: Int, val ux: Float, val uz: Float) {
        /** 離自車車頭（z = [front]）往前多遠；在車後面或腳下 → 負值或 0。 */
        fun ahead(front: Float): Float = (0 until 4).minOf { p[it * 2 + 1] } - front
        fun centerX(): Float = (p[0] + p[2] + p[4] + p[6]) / 4f
        override fun equals(other: Any?) = other is Crosswalk && p.contentEquals(other.p) && stripes == other.stripes
        override fun hashCode() = p.contentHashCode() * 31 + stripes
    }

    private class Stripe(val cx: Double, val cz: Double, val ux: Double, val uz: Double, val len: Double, val wid: Double)

    private var Y = IntArray(0); private var ok = BooleanArray(0); private var lab = IntArray(0)
    private var integ = LongArray(0); private var cnt = IntArray(0)

    fun detect(
        argb: IntArray, w: Int, h: Int, mpp: Float, cx: Float, cy: Float,
        valid: BooleanArray?, exclude: List<FloatArray>
    ): List<Crosswalk> {
        val n = w * h
        if (Y.size < n) { Y = IntArray(n); ok = BooleanArray(n); lab = IntArray(n); integ = LongArray((w + 1) * (h + 1)); cnt = IntArray((w + 1) * (h + 1)) }
        val m = mpp.toDouble()
        for (j in 0 until h) for (i in 0 until w) {
            val p = j * w + i; val c = argb[p]
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            Y[p] = (77 * r + 150 * g + 29 * b) shr 8
            var v = r + g + b >= 24 && (valid == null || valid[p])
            if (v && exclude.isNotEmpty()) {
                val x = (i + 0.5 - cx) * m; val z = (cy - j - 0.5) * m
                for (e in exclude) if (x >= e[0] - 0.15 && x <= e[2] + 0.15 && z >= e[1] - 0.15 && z <= e[3] + 0.15) { v = false; break }
            }
            ok[p] = v
        }
        // 積分圖：有效像素的亮度和、個數
        val W1 = w + 1
        for (i in 0..w) { integ[i] = 0; cnt[i] = 0 }
        for (j in 0 until h) {
            var rs = 0L; var rc = 0
            integ[(j + 1) * W1] = 0; cnt[(j + 1) * W1] = 0
            for (i in 0 until w) {
                val p = j * w + i
                if (ok[p]) { rs += Y[p]; rc++ }
                integ[(j + 1) * W1 + i + 1] = integ[j * W1 + i + 1] + rs
                cnt[(j + 1) * W1 + i + 1] = cnt[j * W1 + i + 1] + rc
            }
        }
        val hr = max(2, (BG_HALF_M / m).toInt())
        val paint = BooleanArray(n)
        for (j in 0 until h) for (i in 0 until w) {
            val p = j * w + i
            if (!ok[p] || Y[p] < PAINT_Y_MIN) continue
            val c = argb[p]
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            if (max(r, max(g, b)) - min(r, min(g, b)) > CHROMA_MAX) continue
            val i0 = max(0, i - hr); val i1 = min(w, i + hr + 1); val j0 = max(0, j - hr); val j1 = min(h, j + hr + 1)
            val s = integ[j1 * W1 + i1] - integ[j0 * W1 + i1] - integ[j1 * W1 + i0] + integ[j0 * W1 + i0]
            val k = cnt[j1 * W1 + i1] - cnt[j0 * W1 + i1] - cnt[j1 * W1 + i0] + cnt[j0 * W1 + i0]
            if (k > 0 && Y[p] >= s / k + PAINT_DY) paint[p] = true
        }
        // 連通元件（4 連通，用堆疊）
        java.util.Arrays.fill(lab, 0, n, 0)
        val stripes = ArrayList<Stripe>()
        val stack = IntArray(n)
        var next = 0
        val minArea = (STRIPE_LEN_MIN * STRIPE_W_MIN * FILL_MIN / (m * m)).toInt()
        for (s0 in 0 until n) {
            if (!paint[s0] || lab[s0] != 0) continue
            next++
            var sp = 0; stack[sp++] = s0; lab[s0] = next
            var cntP = 0; var sx = 0.0; var sz = 0.0; var sxx = 0.0; var szz = 0.0; var sxz = 0.0
            while (sp > 0) {
                val p = stack[--sp]
                val i = p % w; val j = p / w
                val x = (i + 0.5 - cx) * m; val z = (cy - j - 0.5) * m
                cntP++; sx += x; sz += z; sxx += x * x; szz += z * z; sxz += x * z
                if (i > 0 && paint[p - 1] && lab[p - 1] == 0) { lab[p - 1] = next; stack[sp++] = p - 1 }
                if (i < w - 1 && paint[p + 1] && lab[p + 1] == 0) { lab[p + 1] = next; stack[sp++] = p + 1 }
                if (j > 0 && paint[p - w] && lab[p - w] == 0) { lab[p - w] = next; stack[sp++] = p - w }
                if (j < h - 1 && paint[p + w] && lab[p + w] == 0) { lab[p + w] = next; stack[sp++] = p + w }
            }
            if (cntP < minArea) continue
            val mx = sx / cntP; val mz = sz / cntP
            val cxx = sxx / cntP - mx * mx; val czz = szz / cntP - mz * mz; val cxz = sxz / cntP - mx * mz
            val tr = cxx + czz; val det = cxx * czz - cxz * cxz
            val disc = sqrt(max(0.0, tr * tr / 4 - det))
            val l1 = tr / 2 + disc; val l2 = max(0.0, tr / 2 - disc)
            val ang = 0.5 * atan2(2 * cxz, cxx - czz)
            // 均勻長方形：長 = √(12·λ)
            val len = sqrt(12 * l1); val wid = sqrt(12 * l2)
            if (len < STRIPE_LEN_MIN || len > STRIPE_LEN_MAX || wid < STRIPE_W_MIN || wid > STRIPE_W_MAX) continue
            if (cntP * m * m < FILL_MIN * len * wid) continue
            stripes.add(Stripe(mx, mz, cos(ang), sin(ang), len, wid))
        }
        if (stripes.size < MIN_STRIPES) return emptyList()
        // 串成一排：照法線方向排序，相鄰的要平行、肩並肩、間距合理
        val used = BooleanArray(stripes.size)
        val out = ArrayList<Crosswalk>()
        val cosPar = cos(Math.toRadians(PAR_DEG))
        for (a in stripes.indices) {
            if (used[a]) continue
            val s = stripes[a]
            val nx = -s.uz; val nz = s.ux
            val grp = stripes.indices.filter { b ->
                val t = stripes[b]
                !used[b] && abs(t.ux * s.ux + t.uz * s.uz) >= cosPar &&
                    abs((t.cx - s.cx) * s.ux + (t.cz - s.cz) * s.uz) <= SHIFT_MAX + 0.5 * abs(t.len - s.len)
            }.sortedBy { b -> (stripes[b].cx - s.cx) * nx + (stripes[b].cz - s.cz) * nz }
            // 取最長的一段等間距連續串
            var best = emptyList<Int>(); var cur = ArrayList<Int>()
            for (b in grp) {
                if (cur.isEmpty()) { cur.add(b); continue }
                val pr = stripes[cur.last()]; val t = stripes[b]
                val gap = (t.cx - pr.cx) * nx + (t.cz - pr.cz) * nz
                if (gap in GAP_MIN..GAP_MAX && max(t.wid, pr.wid) / min(t.wid, pr.wid) < 1.8) cur.add(b)
                else { if (cur.size > best.size) best = cur.toList(); cur = arrayListOf(b) }
            }
            if (cur.size > best.size) best = cur.toList()
            if (best.size < MIN_STRIPES) continue
            for (b in best) used[b] = true
            var u0 = Double.MAX_VALUE; var u1 = -Double.MAX_VALUE; var v0 = Double.MAX_VALUE; var v1 = -Double.MAX_VALUE
            for (b in best) {
                val t = stripes[b]
                val cu = (t.cx - s.cx) * s.ux + (t.cz - s.cz) * s.uz; val cv = (t.cx - s.cx) * nx + (t.cz - s.cz) * nz
                u0 = min(u0, cu - t.len / 2); u1 = max(u1, cu + t.len / 2); v0 = min(v0, cv - t.wid / 2); v1 = max(v1, cv + t.wid / 2)
            }
            fun pt(u: Double, v: Double) = floatArrayOf((s.cx + u * s.ux + v * nx).toFloat(), (s.cz + u * s.uz + v * nz).toFloat())
            val c = listOf(pt(u0, v0), pt(u1, v0), pt(u1, v1), pt(u0, v1))
            out.add(Crosswalk(FloatArray(8) { c[it / 2][it % 2] }, best.size, s.ux.toFloat(), s.uz.toFloat()))
        }
        return out
    }
}
