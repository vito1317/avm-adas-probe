package com.vito.avmprobe

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** 三角錐（車體座標，公尺）：x, z 是錐底中心的估計。 */
data class Cone(val x: Float, val z: Float, val conf: Float)

/**
 * 俯視圖上的交通錐。純 Kotlin，跟 tools/perception_proto.py 的 cone_detect(CONE) 一致。
 *
 * 高的東西在俯視圖裡會沿鏡頭射線往外拉長，錐身還會被白色反光帶切成好幾段：
 * - 顏色雙門檻：強橘（色相 5–32°、飽和度 ≥ 70%、R ≥ 110）當種子，淡橘（≥ 45%、R ≥ 90）長出去；
 *   紅磚、紅褐色地磚飽和度不夠，不會有種子。
 * - 錐底 = 元件裡離車身矩形最近的點（取最近 6 cm 內的平均），再往外推 0.12 m 估錐心。
 * - 依離車遠近處理：離得近的先成錐；後面的元件若落在它的射線方向上、而且中間幾乎都是
 *   橘 / 粉 / 白（反光帶），就併進同一個錐；0.4 m 內的也併。
 */
class ConeDetector {

    companion object {
        const val MIN_R = 110
        const val SAT_PCT = 70
        private const val RELAX_R = 90
        private const val RELAX_SAT = 45
        private const val HUE_LO = 5
        private const val HUE_HI = 32
        private const val SEED_MIN = 6
        private const val MIN_AREA = 0.03
        private const val MAX_AREA = 3.0
        private const val STRIP_L = 1.2
        private const val STRIP_W = 0.2
        private const val MERGE_M = 0.4
        private const val BASE_TOL = 0.06
        private const val BRIDGE_DEG = 15.0
        private const val BRIDGE_M = 3.5
        private const val BRIDGE_FRAC = 0.7
        private const val CENTER_OFF = 0.12
        private const val SIDE_CAM_Z = 0.85       // 左右鏡頭（後照鏡）大約在車身中心前方的位置
        private const val EGO_HX = 0.925
        private const val EGO_HZ = 2.42
        private val CAM_X = doubleArrayOf(0.0, 0.0, -EGO_HX, EGO_HX)
        private val CAM_Z = doubleArrayOf(EGO_HZ, -EGO_HZ, SIDE_CAM_Z, SIDE_CAM_Z)
        private val BY_DIST = compareBy<Comp>({ it.dmin }, { it.lab })

        /** 三角錐橘：R 最大、B 最小、色相 5–32°、飽和度 ≥ satPct%、R ≥ minR（全整數）。 */
        fun isOrange(r: Int, g: Int, b: Int, minR: Int, satPct: Int): Boolean =
            r >= minR && r >= g && g >= b &&
                60 * (g - b) >= HUE_LO * (r - b) && 60 * (g - b) <= HUE_HI * (r - b) &&
                100 * (r - b) >= satPct * r
    }

    private class Comp(
        val lab: Int, val dmin: Double, val x: Double, val z: Double, var area: Double,
        val sat: Double, val phi: Double, val elong: Boolean
    )

    private var bw = -1
    private var bh = -1
    private var xs = DoubleArray(0)
    private var zs = DoubleArray(0)
    private var flags = ByteArray(0)        // 1 有效且未排除、2 淡橘、4 強橘、8 橋接
    private var lab = IntArray(0)
    private var stack = IntArray(0)
    private var cap = 0
    private var cN = IntArray(0)
    private var cSeed = IntArray(0)
    private var cSi = LongArray(0)
    private var cSj = LongArray(0)
    private var cSii = LongArray(0)
    private var cSij = LongArray(0)
    private var cSjj = LongArray(0)
    private var cDmin = DoubleArray(0)
    private var cBx = DoubleArray(0)
    private var cBz = DoubleArray(0)
    private var cBn = IntArray(0)
    private var cSat = DoubleArray(0)
    private val comps = ArrayList<Comp>()
    private val cones = ArrayList<Comp>()

    private fun ensure(w: Int, h: Int) {
        if (w == bw && h == bh) return
        bw = w
        bh = h
        xs = DoubleArray(w)
        zs = DoubleArray(h)
        flags = ByteArray(w * h)
        lab = IntArray(w * h)
        stack = IntArray(w * h)
    }

    private fun ensureComps(n: Int) {
        if (n <= cap) return
        cap = max(n, cap * 2 + 64)
        // 標記途中會長大：已累積的統計要保留
        cN = cN.copyOf(cap); cSeed = cSeed.copyOf(cap)
        cSi = cSi.copyOf(cap); cSj = cSj.copyOf(cap); cSii = cSii.copyOf(cap); cSij = cSij.copyOf(cap); cSjj = cSjj.copyOf(cap)
        cDmin = cDmin.copyOf(cap); cBx = cBx.copyOf(cap); cBz = cBz.copyOf(cap); cBn = cBn.copyOf(cap); cSat = cSat.copyOf(cap)
    }

    private fun rectDist(x: Double, z: Double): Double {
        val dx = max(abs(x) - EGO_HX, 0.0)
        val dz = max(abs(z) - EGO_HZ, 0.0)
        return sqrt(dx * dx + dz * dz)
    }

    fun detect(
        argb: IntArray, w: Int, h: Int, mpp: Float, cx: Float, cy: Float,
        exclude: List<FloatArray>
    ): List<Cone> {
        ensure(w, h)
        val m = mpp.toDouble()
        val ox = cx.toDouble()
        val oy = cy.toDouble()
        val n = w * h
        for (i in 0 until w) xs[i] = (i + 0.5 - ox) * m
        for (j in 0 until h) zs[j] = (oy - (j + 0.5)) * m
        for (p in 0 until n) {
            val c = argb[p]
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val b = c and 255
            flags[p] = if (r + g + b >= 24) 1 else 0
        }
        for (rc in exclude) {
            val x0 = rc[0].toDouble(); val z0 = rc[1].toDouble(); val x1 = rc[2].toDouble(); val z1 = rc[3].toDouble()
            for (j in 0 until h) {
                if (zs[j] < z0 || zs[j] > z1) continue
                for (i in 0 until w) if (xs[i] >= x0 && xs[i] <= x1) flags[j * w + i] = 0
            }
        }
        var any = false
        for (p in 0 until n) {
            if (flags[p].toInt() == 0) continue
            val c = argb[p]
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val b = c and 255
            var f = 1
            val weak = isOrange(r, g, b, RELAX_R, RELAX_SAT)
            if (weak) { f = f or 2; any = true }
            if (isOrange(r, g, b, MIN_R, SAT_PCT)) f = f or 4
            val y = (77 * r + 150 * g + 29 * b) shr 8
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            // 橋接：淡橘 / 粉（橘跟白色反光帶混色）或亮白
            if (weak || (r >= 100 && r >= g && g >= b && 60 * (g - b) <= 40 * (r - b) && 10 * (r - b) >= 3 * r) ||
                (y >= 140 && mx - mn <= 60)
            ) f = f or 8
            flags[p] = f.toByte()
        }
        if (!any) return emptyList()

        // ── 淡橘的 8 連通元件 ──
        java.util.Arrays.fill(lab, 0, n, 0)
        var nc = 0
        for (p0 in 0 until n) {
            if (flags[p0].toInt() and 2 == 0 || lab[p0] != 0) continue
            nc++
            ensureComps(nc + 1)
            cN[nc] = 0; cSeed[nc] = 0; cSi[nc] = 0; cSj[nc] = 0; cSii[nc] = 0; cSij[nc] = 0; cSjj[nc] = 0
            cDmin[nc] = Double.MAX_VALUE; cBx[nc] = 0.0; cBz[nc] = 0.0; cBn[nc] = 0; cSat[nc] = 0.0
            var sp = 0
            stack[sp++] = p0
            lab[p0] = nc
            while (sp > 0) {
                val p = stack[--sp]
                val i = p % w
                val j = p / w
                cN[nc]++
                if (flags[p].toInt() and 4 != 0) cSeed[nc]++
                cSi[nc] += i.toLong(); cSj[nc] += j.toLong()
                cSii[nc] += (i * i).toLong(); cSij[nc] += (i * j).toLong(); cSjj[nc] += (j * j).toLong()
                for (dj in -1..1) {
                    val jj = j + dj
                    if (jj < 0 || jj >= h) continue
                    for (di in -1..1) {
                        val ii = i + di
                        if (ii < 0 || ii >= w) continue
                        val q = jj * w + ii
                        if (flags[q].toInt() and 2 != 0 && lab[q] == 0) {
                            lab[q] = nc
                            stack[sp++] = q
                        }
                    }
                }
            }
        }
        // 每個元件離車身最近的距離 → 最近 6 cm 內像素的平均當錐底；平均飽和度
        for (p in 0 until n) {
            val l = lab[p]
            if (l == 0) continue
            val d = rectDist(xs[p % w], zs[p / w])
            if (d < cDmin[l]) cDmin[l] = d
        }
        for (p in 0 until n) {
            val l = lab[p]
            if (l == 0) continue
            val i = p % w
            val j = p / w
            if (rectDist(xs[i], zs[j]) <= cDmin[l] + BASE_TOL) {
                cBx[l] += xs[i]; cBz[l] += zs[j]; cBn[l]++
            }
            val c = argb[p]
            val r = (c shr 16) and 255
            val b = c and 255
            cSat[l] += (r - b).toDouble() / max(r, 1)
        }

        comps.clear()
        val a1 = m * m
        for (k in 1..nc) {
            val c = cN[k].toDouble()
            val mi = cSi[k] / c
            val mj = cSj[k] / c
            val cii = cSii[k] / c - mi * mi
            val cij = cSij[k] / c - mi * mj
            val cjj = cSjj[k] / c - mj * mj
            val hd = (cii - cjj) / 2.0
            val disc = sqrt(hd * hd + cij * cij)
            val l1 = (cii + cjj) / 2.0 + disc
            val l2 = max((cii + cjj) / 2.0 - disc, 0.0)
            val al = 0.5 * atan2(2.0 * cij, cii - cjj)
            var phi = Math.toDegrees(atan2(cos(al), -sin(al)))
            while (phi >= 90.0) phi -= 180.0
            while (phi < -90.0) phi += 180.0
            val area = c * a1
            val len = sqrt(12.0 * l1 + 1.0) * m
            val wid = sqrt(12.0 * l2 + 1.0) * m
            if (area < MIN_AREA || area > MAX_AREA) continue
            if (len >= STRIP_L && wid <= STRIP_W) continue
            if (cSeed[k] < SEED_MIN) continue
            comps.add(Comp(k, cDmin[k], cBx[k] / cBn[k], cBz[k] / cBn[k], area, cSat[k] / c, phi, len >= 1.5 * wid))
        }
        comps.sortWith(BY_DIST)

        cones.clear()
        for (q in comps) {
            var hit: Comp? = null
            for (cn in cones) {
                if (hypot(q.x - cn.x, q.z - cn.z) < MERGE_M || bridged(cn, q, w, h, m, ox, oy)) { hit = cn; break }
            }
            if (hit != null) hit.area += q.area else cones.add(Comp(q.lab, q.dmin, q.x, q.z, q.area, q.sat, q.phi, q.elong))
        }
        val out = ArrayList<Cone>(cones.size)
        for (cn in cones) {
            val nx = cn.x - max(-EGO_HX, min(EGO_HX, cn.x))
            val nz = cn.z - max(-EGO_HZ, min(EGO_HZ, cn.z))
            val nn = hypot(nx, nz)
            var x = cn.x
            var z = cn.z
            if (nn > 1e-9) {
                x += nx / nn * CENTER_OFF
                z += nz / nn * CENTER_OFF
            }
            val conf = min(1.0, 0.5 * min(1.0, cn.area / 0.08) + 0.5 * min(1.0, max(0.0, (cn.sat - 0.5) / 0.3)))
            out.add(Cone(x.toFloat(), z.toFloat(), conf.toFloat()))
        }
        return out
    }

    /** b 落在 a 的拉長方向（鏡頭射線）上，兩者之間幾乎都是橘 / 粉 / 白 → 同一個錐。 */
    private fun bridged(a: Comp, b: Comp, w: Int, h: Int, m: Double, ox: Double, oy: Double): Boolean {
        val da = hypot(b.x - a.x, b.z - a.z)
        if (da > BRIDGE_M || b.dmin <= a.dmin) return false
        val vang = Math.toDegrees(atan2(b.x - a.x, b.z - a.z))
        // 最近的標稱鏡頭：前 / 後保桿中央、左右後照鏡
        var bx = CAM_X[0]
        var bz = CAM_Z[0]
        var bd = (a.x - bx) * (a.x - bx) + (a.z - bz) * (a.z - bz)
        for (q in 1 until 4) {
            val qx = CAM_X[q]
            val qz = CAM_Z[q]
            val dd = (a.x - qx) * (a.x - qx) + (a.z - qz) * (a.z - qz)
            if (dd < bd) { bd = dd; bx = qx; bz = qz }
        }
        val ray = Math.toDegrees(atan2(a.x - bx, a.z - bz))
        var ang = abs(vang - ray) % 360.0
        ang = min(ang, 360.0 - ang)
        if (a.elong) ang = min(ang, abs(RoadExtractor.wrap90(vang - a.phi)))
        if (ang > BRIDGE_DEG) return false
        val ns = max(1, (da / m).toInt())
        var good = 0
        for (t in 0..ns) {
            val x = a.x + (b.x - a.x) * t / ns
            val z = a.z + (b.z - a.z) * t / ns
            val i = floor(x / m + ox).toInt()
            val j = floor(oy - z / m).toInt()
            if (i in 0 until w && j in 0 until h && flags[j * w + i].toInt() and 8 != 0) good++
        }
        return good >= BRIDGE_FRAC * (ns + 1)
    }
}
