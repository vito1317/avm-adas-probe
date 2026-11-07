package com.vito.avmprobe

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 停車格（車身座標，公尺；x 右、z 前）。
 * p = x1,z1,x2,z2,x3,z3,x4,z4：p1→p2 是入口邊，p3、p4 是遠端兩角，四點逆時針。
 * kind：'p' 垂直、'l' 平行（路邊）、'a' 斜角。occupied：與車輛框或自車（含原點的排除區）重疊 > 30 % 面積。
 */
data class ParkingSlot(val p: FloatArray, val kind: Char, val occupied: Boolean, val conf: Float) {
    override fun equals(other: Any?): Boolean =
        other is ParkingSlot && p.contentEquals(other.p) && kind == other.kind && occupied == other.occupied && conf == other.conf

    override fun hashCode(): Int = ((p.contentHashCode() * 31 + kind.hashCode()) * 31 + occupied.hashCode()) * 31 + conf.hashCode()
}

/**
 * 公制俯視圖（BEV）上的停車格偵測。演算法與 tools/parking_proto.py 逐步一致（該檔有完整說明與合成資料評估）：
 * 標線脊線遮罩 → Hough 線段 → 平行線對 + 橫邊 / 開口端圍成格子（橫邊與長邊線寬要相近，停止線不算）→
 * 支持度、格內乾淨度（機車格 T 字線頭、斜紋）、外圍紋理（植草磚）驗證 → 類型 / 入口 → NMS。
 * 純 Kotlin（無 android.*）；大陣列在呼叫之間重複使用，不是執行緒安全的（一個執行緒一個實例）。
 */
class ParkingSlotDetector {

    /** 除錯用：設了就把配對與格子驗證每一步的淘汰原因丟出來（實車漏抓時逐步追）。平常是 null，沒有成本。 */
    var trace: ((String) -> Unit)? = null

    private fun tag(a: Int, b: Int): String {
        val la = lines[a]; val lb = lines[b]
        return "(%.2f,%.2f)+(%.2f,%.2f)".format(la.cx, la.cz, lb.cx, lb.cz)
    }

    companion object {
        private const val PAINT_MIN = 50
        private const val NEUTRAL_MAX = 60
        private const val RIDGE_D_M = 0.16
        private const val RIDGE_T = 14
        private const val SECTOR = 10
        private const val MIN_VOTES_M = 0.35
        private const val MAX_PEAKS = 120
        private const val MAX_LINES = 48
        private const val GATHER_TOL0 = 1.5
        private const val GATHER_TOLF_M = 0.05
        private const val GAP_M = 0.3
        private const val LINE_GAP_M = 2.0
        private const val MERGE_DEG = 1.5
        private const val MERGE_TOL_M = 0.06
        private const val MIN_PIECE_M = 0.3
        private const val PAR_DEG = 4.0
        private const val PAIR_WMIN = 1.8
        private const val PAIR_WMAX = 3.5
        private const val PAIR_OVL = -2.5
        private const val CROSS_MIN_DEG = 25.0
        private const val PERP_TOL_DEG = 15.0
        private const val DEPTH_DEF = 5.5
        private const val DEPTH_PAR = 6.0
        private const val LANE_LEN = 7.0
        private const val CROSS_REACH = 1.2   // 橫線自身線段可以離交點多遠（m）
        /** 橫線往「被自車擋住的那條長邊」延伸不到時，容許差這麼多（自車旁邊的地面被車身擋住）。 */
        private const val CROSS_REACH_EGO = 1.8
        /** 跟自車平行、貼著自車側邊（外框外 0.5 m 內）的線 → 視為跟自車共用、被車身擋住的分隔線。 */
        private const val EGO_ADJ_OUT = 0.5
        private const val EGO_ADJ_IN = 0.15
        private val COS_EGO_PAR = cos(8.0 * PI / 180.0)
        private const val GLYPH_M = 0.60      // 遮罩元件兩個方向都小於此值 → 字形（車位號碼、箭頭、污漬）
        private const val RUN_GAP = 1.0
        private const val END_GAP = 0.8
        private const val EXT_MISS = 2
        private const val CLUTTER_MAX = 0.08
        private const val CORRIDOR = 1.2
        private const val OCC_FRAC = 0.30
        private const val NMS_IOU = 0.30
        private const val MAX_SLOTS = 16
        private const val OPEN_WMAX = 2.75
        private const val WIDE_MIN = 0.24       // 橫邊 / 長邊線寬差很多（停止線、斑馬線邊線）→ 不是格子的邊
        private const val WIDE_RATIO = 1.6
        private const val WSTEP = 0.25          // 線寬取樣間距（每條線算一次之後查表）
        private const val STUB_IN = 0.45        // 格內短分隔線頭（機車格 T 字）探針長度
        private const val INNER_PAR = 1.2       // 格內平行線看得到的長度上限
        private const val SEP_MIN_A = 1.0       // 單側斜角格：兩條分隔線伸進格內的最短長度
        private const val RING = 1.0            // 格子外圍一圈的寬度
        private const val RING_MAX = 0.25       // 外圍雜線比例上限（植草磚、連鎖磚）
        private const val TEXTURE_MAX = 0.2     // 整張圖地面的標線遮罩比例上限（一般停車場 ≤ 0.13）
        private const val NEG = -(1 shl 28)
        private const val SENT = 1 shl 24

        private val SIN_PAR = sin(PAR_DEG * (PI / 180.0))
        private const val FAR_X_M = 3.0
        private const val NEAR_X_M = 2.0
        private val SIN_PAR_FAR = sin(7.0 * (PI / 180.0))
        private val COS_OBLIQUE = cos(20.0 * (PI / 180.0))
        private val COS_CROSS = cos(CROSS_MIN_DEG * (PI / 180.0))
        private val SIN_PERP = sin(PERP_TOL_DEG * (PI / 180.0))
        private val SIN_12 = sin(12.0 * (PI / 180.0))
        private val SIN_MERGE = sin(MERGE_DEG * (PI / 180.0))
        private val COS_T = DoubleArray(180) { cos(it * PI / 180.0) }
        private val SIN_T = DoubleArray(180) { sin(it * PI / 180.0) }
        private val KN_MAP = intArrayOf(0, 2, 1, 3)
        private val UNIT_I = intArrayOf(1, 0, 1, 1)
        private val UNIT_J = intArrayOf(0, 1, 1, -1)
    }

    // ---------------------------------------------------------------- 內部資料
    private class Line(val cx: Double, val cz: Double, val ux: Double, val uz: Double, var pieces: DoubleArray, val n: Int) {
        var wT: DoubleArray? = null  // 線寬取樣（每條線算一次）：參數與半高寬
        /** 因為被自車擋住而補上的那段（線參數 t0, t1）；它的兩端不是真的線尾。 */
        var occ: DoubleArray? = null
        var wW = DoubleArray(0)
        var wN = 0
    }

    /** 長邊對的共同座標：原點 o，方向 u。 */
    private class Frame(val ox: Double, val oz: Double, val ux: Double, val uz: Double) {
        fun onX(l: Line, t: Double): Double {
            val du = l.ux * ux + l.uz * uz
            val t0 = (l.cx - ox) * ux + (l.cz - oz) * uz
            return l.cx + (t - t0) / du * l.ux
        }

        fun onZ(l: Line, t: Double): Double {
            val du = l.ux * ux + l.uz * uz
            val t0 = (l.cx - ox) * ux + (l.cz - oz) * uz
            return l.cz + (t - t0) / du * l.uz
        }

        /** 線段在共同座標下的區間 [a0,b0,a1,b1,...]（依起點排序）。 */
        fun pieces(l: Line): DoubleArray {
            val du = l.ux * ux + l.uz * uz
            val t0 = (l.cx - ox) * ux + (l.cz - oz) * uz
            val n = l.pieces.size / 2
            val out = DoubleArray(l.pieces.size)
            for (k in 0 until n) {
                val a = t0 + l.pieces[2 * k] * du
                val b = t0 + l.pieces[2 * k + 1] * du
                out[2 * k] = min(a, b)
                out[2 * k + 1] = max(a, b)
            }
            sortPairs(out)
            return out
        }
    }

    private class Crossing(
        val t: Double, val tA: Double, val tB: Double,
        val pAx: Double, val pAz: Double, val pBx: Double, val pBz: Double,
        val vx: Double, val vz: Double, val perp: Boolean, val open: Boolean, val sense: Int, val ev: Double,
        val cA: Double, val cB: Double, val rAp: Double, val rAm: Double, val rBp: Double, val rBm: Double, val ci: Int
    )

    private class Cell(
        val a: Int, val b: Int, val f: Frame, val w: Double,
        val c0: Crossing, val c1: Crossing?, val s: Int, val len: Double, val one: Boolean, val okinds: String
    )

    private class Cand(
        val cell: Cell, val kinds: String, val ak: DoubleArray, val bk: DoubleArray, val am: DoubleArray, val bm: DoubleArray,
        val score: Double, val row: Boolean
    ) {
        var order = 0
        var kind = 'p'
        var poly = DoubleArray(8)
    }

    // ---------------------------------------------------------------- 重複使用的緩衝區
    private var cap = 0
    private var valid = BooleanArray(0)
    private var colOk = BooleanArray(0)
    private var vis = BooleanArray(0)
    private var state = ByteArray(0)
    private var paint = IntArray(0)
    private var sum3 = IntArray(0)
    private var rowSum = IntArray(0)
    private var rowCnt = IntArray(0)
    private var rowPre = IntArray(0)
    private var colPre = IntArray(0)
    private var best = IntArray(0)
    private var theta = IntArray(0)
    private var kn = ByteArray(0)
    private var mask = BooleanArray(0)
    private var dil = BooleanArray(0)
    private var cen = BooleanArray(0)
    private var glyphM = BooleanArray(0)   // 脊線上的字形元件
    private var glyphD = BooleanArray(0)   // 對齊 dil：附近完全沒有結構標線的字形像素
    private var ccStack = IntArray(0)
    private var ccCells = IntArray(0)
    private var ccLab = IntArray(0)
    private var tmpB = BooleanArray(0)
    private var mlist = IntArray(0)
    private var nM = 0
    private var kOf = IntArray(0)
    private var bucketStart = IntArray(181)
    private var bucketIdx = IntArray(0)
    private var seeds = IntArray(1024)
    private var hough = IntArray(0)
    private var ncap = 0
    private var px = DoubleArray(0)
    private var py = DoubleArray(0)
    private var pth = IntArray(0)
    private var used = BooleanArray(0)
    private var sv = DoubleArray(0)
    private var idxA = IntArray(0)
    private var idxB = IntArray(0)
    private var idxT = IntArray(0)

    private var mpp = 0.04
    private var ocx = 0.0
    private var ocy = 0.0
    private var w = 0
    private var h = 0
    private val lines = ArrayList<Line>()

    /** 各階段累計耗時（ns）：遮罩、線段、格子假設、驗證、其餘。設成非 null 才量。 */
    var profile: LongArray? = null

    // ---------------------------------------------------------------- 主流程
    /**
     * @param argb 0xAARRGGBB，列優先，第 0 列 = 前方；R+G+B < 24 視為無覆蓋
     * @param mpp 每像素公尺數；(cx, cy) 是車身原點的像素位置
     * @param exclude 不看的矩形 [xMin, zMin, xMax, zMax]（車身公尺；自車框、原廠導引線等）。含原點的那個（自車框）也當佔用物，
     *   其他排除區（例如倒車時原廠導引線的整塊區域）只是看不到，不會讓格子變成 occupied
     * @param vehicles 偵測到的車輛框（同上格式）：框內沒看到線當作被車擋住；格子被佔 > 30 % 面積 → occupied
     * @return 依信心排序的停車格（最多 16 格）；時間上的穩定化由呼叫端處理
     */
    fun detect(
        argb: IntArray, w: Int, h: Int, mpp: Float, cx: Float, cy: Float,
        exclude: List<FloatArray>, vehicles: List<FloatArray>
    ): List<ParkingSlot> {
        require(w > 0 && h > 0 && argb.size >= w * h && mpp > 0f)
        this.w = w
        this.h = h
        this.mpp = mpp.toDouble()
        this.ocx = cx.toDouble()
        this.ocy = cy.toDouble()
        ensure(w * h)
        val prof = profile
        var t0 = if (prof != null) System.nanoTime() else 0L
        buildMask(argb, exclude, vehicles)
        if (prof != null) { val t = System.nanoTime(); prof[0] += t - t0; t0 = t }
        // 整片紋理（植草磚、連鎖磚）：看得到的地面 ≥ TEXTURE_MAX 是標線遮罩 → 格子外圍雜線幾乎一定過高，直接結束
        var nVis = 0; var nMask = 0
        for (p in 0 until w * h) if (state[p].toInt() == 1) { nVis++; if (mask[p]) nMask++ }
        if (nVis >= 1000 && nMask >= TEXTURE_MAX * nVis) return emptyList()
        buildLines()
        for (l in lines) extend(l)
        markEgoAdjacent(exclude)
        trace?.let { tr -> for (l in lines) tr("line (%.2f,%.2f) 方向 (%.2f,%.2f) 長 %.2f 段 %d".format(l.cx, l.cz, l.ux, l.uz, l.pieces.let { pc -> var s2 = 0.0; var i = 0; while (i + 1 < pc.size) { s2 += pc[i + 1] - pc[i]; i += 2 }; s2 }, l.pieces.size / 2)) }
        if (prof != null) { val t = System.nanoTime(); prof[1] += t - t0; t0 = t }
        val cells = ArrayList<Cell>()
        for (a in lines.indices) for (b in a + 1 until lines.size) pair(a, b, cells)
        if (prof != null) { val t = System.nanoTime(); prof[2] += t - t0; t0 = t }
        val all = ArrayList<Cand>()
        for (c in cells) evalCell(c)?.let { all.add(it) }
        if (prof != null) { val t = System.nanoTime(); prof[3] += t - t0; t0 = t }
        // 只有分隔線、入口沒畫線的單側格：必須成排或明顯斜於車身（排除兩條車道線）
        val cands = ArrayList<Cand>()
        for (c in all) if (!c.row || rowOk(c, all)) cands.add(c)
        decideKinds(cands)
        val sorted = cands.sortedWith(compareBy<Cand>({ -it.score }, { it.order }))
        val keep = ArrayList<Cand>()
        for (c in sorted) {
            var sup = false
            for (k in keep) if (polyIou(c.poly, k.poly) > NMS_IOU) { sup = true; break }
            if (sup) continue
            keep.add(c)
            if (keep.size >= MAX_SLOTS) break
        }
        val occ = ArrayList<DoubleArray>()
        for (r in vehicles) occ.add(doubleArrayOf(r[0].toDouble(), r[1].toDouble(), r[2].toDouble(), r[3].toDouble()))
        for (r in exclude) {
            // 只有自車（含原點的排除區）算佔用物；原廠倒車導引線等其他排除區不算
            if (r[0] <= 0f && 0f <= r[2] && r[1] <= 0f && 0f <= r[3])
                occ.add(doubleArrayOf(r[0].toDouble(), r[1].toDouble(), r[2].toDouble(), r[3].toDouble()))
        }
        val out = ArrayList<ParkingSlot>(keep.size)
        for (c in keep) {
            val area = abs(signedArea(c.poly))
            var o = false
            for (r in occ) {
                if (area > 1e-6 && clipRectArea(c.poly, r) > OCC_FRAC * area) { o = true; break }
            }
            val p = FloatArray(8) { c.poly[it].toFloat() }
            out.add(ParkingSlot(p, c.kind, o, min(1.0, max(0.0, c.score)).toFloat()))
        }
        if (prof != null) prof[4] += System.nanoTime() - t0
        return out
    }

    private fun ensure(n: Int) {
        if (n > cap) {
            cap = n
            valid = BooleanArray(n); colOk = BooleanArray(n); vis = BooleanArray(n); state = ByteArray(n)
            glyphM = BooleanArray(n); glyphD = BooleanArray(n)
            ccStack = IntArray(n); ccCells = IntArray(n); ccLab = IntArray(n)
            paint = IntArray(n); sum3 = IntArray(n); rowSum = IntArray(n); rowCnt = IntArray(n)
            best = IntArray(n); theta = IntArray(n); kn = ByteArray(n)
            mask = BooleanArray(n); dil = BooleanArray(n); cen = BooleanArray(n); tmpB = BooleanArray(n); mlist = IntArray(n)
            kOf = IntArray(n)
        }
        if (rowPre.size < w + 1) rowPre = IntArray(w + 1)
        if (colPre.size < w) colPre = IntArray(w)
    }

    private fun rectPx(r: FloatArray): IntArray {
        val x0 = r[0].toDouble(); val z0 = r[1].toDouble(); val x1 = r[2].toDouble(); val z1 = r[3].toDouble()
        val i0 = ceil(x0 / mpp + ocx - 0.5).toInt()
        val i1 = floor(x1 / mpp + ocx - 0.5).toInt()
        val j0 = ceil(ocy - z1 / mpp - 0.5).toInt()
        val j1 = floor(ocy - z0 / mpp - 0.5).toInt()
        return intArrayOf(max(0, i0), min(w - 1, i1), max(0, j0), min(h - 1, j1))
    }

    // ---------------------------------------------------------------- 1. 標線遮罩
    private fun buildMask(argb: IntArray, exclude: List<FloatArray>, vehicles: List<FloatArray>) {
        paintPass(argb)
        // 排除區 → 看不到；車輛框 → 2（標線可能被車擋）
        for (r in exclude) {
            val q = rectPx(r)
            for (j in q[2]..q[3]) for (i in q[0]..q[1]) state[j * w + i] = 0
        }
        for (r in vehicles) {
            val q = rectPx(r)
            for (j in q[2]..q[3]) for (i in q[0]..q[1]) { val p = j * w + i; if (state[p].toInt() == 1) state[p] = 2 }
        }
        for (p in 0 until w * h) vis[p] = state[p].toInt() != 0          // 有覆蓋且不在排除區（車框內也算）
        val d = max(2, (RIDGE_D_M / mpp).roundToInt())
        val dd = max(2, (RIDGE_D_M / mpp / sqrt(2.0)).roundToInt())
        erodeState(d + 1)
        boxSums()
        ridge(d, dd)
        nmsAndDilate()
    }

    /** 有效像素、「漆」通道（亮度 + 黃色加成）、白 / 黃色測試。 */
    private fun paintPass(argb: IntArray) {
        val n = w * h
        for (p in 0 until n) {
            val c = argb[p]
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            val v = r + g + b >= 24
            valid[p] = v
            if (v) {
                val y = (77 * r + 150 * g + 29 * b) shr 8
                val yb = min(r, g) - b
                val pv = if (yb > 40 && r >= 90 && g >= 70) y + ((yb - 40) shr 1) else y
                paint[p] = pv
                val mx = max(max(r, g), b); val mn = min(min(r, g), b)
                val neutral = mx - mn <= NEUTRAL_MAX
                val yellow = r >= 90 && g >= 70 && r - b >= 40 && g - b >= 25 && abs(r - g) <= 80
                colOk[p] = (neutral || yellow) && pv >= PAINT_MIN
                state[p] = 1
            } else {
                paint[p] = 0; colOk[p] = false; state[p] = 0
            }
        }
    }

    /** 離無覆蓋區 / 影像邊 ≤ dR px：脊線測試做不了，取樣時當未知（可分離的方形侵蝕）。 */
    private fun erodeState(dR: Int) {
        for (j in 0 until h) {
            val o = j * w
            rowPre[0] = 0
            for (i in 0 until w) rowPre[i + 1] = rowPre[i] + (if (valid[o + i]) 0 else 1)
            for (i in 0 until w) tmpB[o + i] = i - dR >= 0 && i + dR < w && rowPre[i + dR + 1] - rowPre[i - dR] == 0
        }
        // 直向：逐列維護每行在 [j-dR, j+dR] 內「橫向不合格」的個數（依列存取，快取友善）
        java.util.Arrays.fill(colPre, 0, w, 0)
        for (jj in 0 until min(dR, h)) { val o = jj * w; for (i in 0 until w) if (!tmpB[o + i]) colPre[i]++ }
        for (j in 0 until h) {
            if (j + dR < h) { val o = (j + dR) * w; for (i in 0 until w) if (!tmpB[o + i]) colPre[i]++ }
            if (j - dR - 1 >= 0) { val o = (j - dR - 1) * w; for (i in 0 until w) if (!tmpB[o + i]) colPre[i]-- }
            val inside = j - dR >= 0 && j + dR < h
            val o = j * w
            for (i in 0 until w) if (state[o + i].toInt() != 0 && !(inside && colPre[i] == 0)) state[o + i] = 0
        }
    }

    /** 3x3 加總（可分離）；不是 9 格都有效的記成 SENT。 */
    private fun boxSums() {
        val n = w * h
        for (j in 0 until h) {
            val o = j * w
            rowSum[o] = 0; rowCnt[o] = 0; rowSum[o + w - 1] = 0; rowCnt[o + w - 1] = 0
            for (i in 1 until w - 1) {
                val p = o + i
                rowSum[p] = paint[p - 1] + paint[p] + paint[p + 1]
                rowCnt[p] = (if (valid[p - 1]) 1 else 0) + (if (valid[p]) 1 else 0) + (if (valid[p + 1]) 1 else 0)
            }
        }
        java.util.Arrays.fill(sum3, 0, n, SENT)
        for (j in 1 until h - 1) {
            val o = j * w
            for (i in 1 until w - 1) {
                val p = o + i
                if (rowCnt[p - w] + rowCnt[p] + rowCnt[p + w] == 9) sum3[p] = rowSum[p - w] + rowSum[p] + rowSum[p + w]
            }
        }
    }

    /** 四方向對稱脊線測試 r = min(S(p)-S(p+o), S(p)-S(p-o))，取最大；過門檻者再算連續法線角。 */
    private fun ridge(d: Int, dd: Int) {
        val n = w * h
        // 四方向對稱脊線測試 r = min(S(p)-S(p+o), S(p)-S(p-o))，取最大；過門檻者再算連續法線角
        val o0 = d; val o1 = d * w; val o2 = dd * w + dd; val o3 = dd - dd * w
        val rt = 9 * RIDGE_T
        java.util.Arrays.fill(best, 0, n, NEG)
        java.util.Arrays.fill(mask, 0, n, false)
        nM = 0
        for (j in d until h - d) {
            val o = j * w
            for (i in d until w - d) {
                val p = o + i
                if (!colOk[p] || !vis[p]) continue
                val s0 = sum3[p]
                if (s0 >= SENT) continue
                val a0 = sum3[p + o0]; val b0 = sum3[p - o0]
                val a1 = sum3[p + o1]; val b1 = sum3[p - o1]
                val a2 = sum3[p + o2]; val b2 = sum3[p - o2]
                val a3 = sum3[p + o3]; val b3 = sum3[p - o3]
                if (a0 >= SENT || b0 >= SENT || a1 >= SENT || b1 >= SENT || a2 >= SENT || b2 >= SENT || a3 >= SENT || b3 >= SENT) continue
                val bst = max(max(min(s0 - a0, s0 - b0), min(s0 - a1, s0 - b1)), max(min(s0 - a2, s0 - b2), min(s0 - a3, s0 - b3)))
                if (bst < rt) continue
                best[p] = bst
                mask[p] = true
                mlist[nM++] = p
                val e0 = 2 * s0 - a0 - b0; val e1 = 2 * s0 - a1 - b1; val e2 = 2 * s0 - a2 - b2; val e3 = 2 * s0 - a3 - b3
                var th = atan2((e2 - e3).toDouble(), (e0 - e1).toDouble()) * (90.0 / PI)
                if (th < 0) th += 180.0
                theta[p] = floor(th + 0.5).toInt() % 180
                kn[p] = KN_MAP[floor(th / 45.0 + 0.5).toInt() % 4].toByte()
            }
        }
    }

    private fun nmsAndDilate() {
        val n = w * h
        java.util.Arrays.fill(cen, 0, n, false)
        java.util.Arrays.fill(dil, 0, n, false)
        for (q in 0 until nM) {
            val p = mlist[q]
            val i = p % w; val j = p / w
            // 沿法線非極大值抑制 → 中心線（未過門檻的鄰點一定比較小，記 NEG 不影響結果）
            val k = kn[p].toInt()
            val im = i - UNIT_I[k]; val jm = j - UNIT_J[k]; val ip = i + UNIT_I[k]; val jp = j + UNIT_J[k]
            val rm = if (im in 0 until w && jm in 0 until h) best[jm * w + im] else NEG
            val rp = if (ip in 0 until w && jp in 0 until h) best[jp * w + ip] else NEG
            cen[p] = best[p] > rm && best[p] >= rp
            // 3x3 膨脹 → 取樣用的 D
            for (jj in max(0, j - 1)..min(h - 1, j + 1)) for (ii in max(0, i - 1)..min(w - 1, i + 1)) dil[jj * w + ii] = true
        }
        buildGlyph()
    }

    /**
     * 字形遮罩：脊線遮罩裡「外接框兩個方向都小於 GLYPH_M」的連通元件（8 連通）。
     *
     * 停車格裡常常直接漆著車位號碼（實車地下室擷取就是「565」），箭頭、補土、油漬也一樣。
     * 這些會讓「格內有沒有東西」的檢查誤判成機車格的 T 字，把好好的格子整個刷掉。
     * 實測那張擷取：干擾物最大 0.12 x 0.36 m，最小的真標線元件 1.00 m，中間空得很。
     * 斜紋禁停區的斜線是細長的，不會被當成字形。
     *
     * 一定要在「沒膨脹的脊線」上算：膨脹會把號碼筆畫和旁邊的標線串成同一個元件。
     * 算完各自膨脹，只有附近完全沒有結構標線的像素才算字形（glyphD，對齊 dil）。
     */
    private fun buildGlyph() {
        val n = w * h
        java.util.Arrays.fill(glyphM, 0, n, false)
        java.util.Arrays.fill(glyphD, 0, n, false)
        java.util.Arrays.fill(ccLab, 0, n, -1)
        val lim = GLYPH_M / mpp
        for (q in 0 until nM) {
            val p0 = mlist[q]
            if (ccLab[p0] >= 0) continue
            ccLab[p0] = q
            ccStack[0] = p0
            var sp = 1
            var nc = 0
            var i0 = p0 % w; var i1 = i0
            var j0 = p0 / w; var j1 = j0
            while (sp > 0) {
                sp--
                val p = ccStack[sp]
                ccCells[nc++] = p
                val i = p % w; val j = p / w
                if (i < i0) i0 = i
                if (i > i1) i1 = i
                if (j < j0) j0 = j
                if (j > j1) j1 = j
                for (jj in max(0, j - 1)..min(h - 1, j + 1)) {
                    for (ii in max(0, i - 1)..min(w - 1, i + 1)) {
                        val r = jj * w + ii
                        if (mask[r] && ccLab[r] < 0) { ccLab[r] = q; ccStack[sp++] = r }
                    }
                }
            }
            if ((i1 - i0 + 1) < lim && (j1 - j0 + 1) < lim) {
                for (k in 0 until nc) glyphM[ccCells[k]] = true
            }
        }
        // glyphD = 膨脹(字形) 且 非 膨脹(結構脊線)
        for (q in 0 until nM) {
            val p = mlist[q]
            if (!glyphM[p]) continue
            val i = p % w; val j = p / w
            for (jj in max(0, j - 1)..min(h - 1, j + 1)) for (ii in max(0, i - 1)..min(w - 1, i + 1)) glyphD[jj * w + ii] = true
        }
        for (q in 0 until nM) {
            val p = mlist[q]
            if (glyphM[p]) continue
            val i = p % w; val j = p / w
            for (jj in max(0, j - 1)..min(h - 1, j + 1)) for (ii in max(0, i - 1)..min(w - 1, i + 1)) glyphD[jj * w + ii] = false
        }
    }

    // ---------------------------------------------------------------- 2. Hough + 貪婪線段
    private var nC = 0
    private var minVotes = 6
    private var tolF = 1.2
    private var gapPx = 7.5
    private var minPiecePx = 7.5
    private var lineGap = 50.0

    private fun ensureN(n: Int) {
        if (n > ncap) {
            ncap = n
            px = DoubleArray(n); py = DoubleArray(n); pth = IntArray(n); used = BooleanArray(n)
            sv = DoubleArray(n); idxA = IntArray(n); idxB = IntArray(n); idxT = IntArray(n); bucketIdx = IntArray(n)
        }
    }

    private fun buildLines() {
        lines.clear()
        var cnt = 0
        for (p in 0 until w * h) if (cen[p]) cnt++
        ensureN(max(cnt, 1))
        nC = 0
        // kOf：像素 → 中心線像素索引（-1 = 不是）；收集像素時只走直線附近的帶狀區域
        java.util.Arrays.fill(kOf, 0, w * h, -1)
        for (j in 0 until h) for (i in 0 until w) {
            val p = j * w + i
            if (!cen[p]) continue
            px[nC] = i + 0.5 - w / 2.0
            py[nC] = j + 0.5 - h / 2.0
            pth[nC] = theta[p]
            used[nC] = false
            kOf[p] = nC
            nC++
        }
        // 依法線角分桶（桶內索引遞增）：角度相容的像素少時直接掃桶，多時（植草磚等整片紋理）改走帶狀區域
        java.util.Arrays.fill(bucketStart, 0)
        for (k in 0 until nC) bucketStart[pth[k] + 1]++
        for (b in 0 until 180) bucketStart[b + 1] += bucketStart[b]
        run {
            val fillPos = bucketStart.copyOf()
            for (k in 0 until nC) bucketIdx[fillPos[pth[k]]++] = k
        }
        val rmax = ceil(hypot(w.toDouble(), h.toDouble()) / 2.0).toInt() + 2
        val nr = 2 * rmax + 1
        if (hough.size < 180 * nr) hough = IntArray(180 * nr)
        java.util.Arrays.fill(hough, 0, 180 * nr, 0)
        minVotes = max(6, (MIN_VOTES_M / mpp).roundToInt())
        // 三格和 ≥ minVotes 的格子，三格中至少一格 ≥ minVotes/3：投票時記下達到這個票數的格子，只檢查它們附近
        val seed = (minVotes + 2) / 3
        var nSeed = 0
        for (q in 0 until nC) {
            val x = px[q]; val y = py[q]; val b = pth[q]
            for (dt in -SECTOR..SECTOR) {
                val t = ((b + dt) % 180 + 180) % 180
                val rb = floor(x * COS_T[t] + y * SIN_T[t] + 0.5).toInt() + rmax
                val o = t * nr + rb
                if (++hough[o] == seed) {
                    if (nSeed == seeds.size) seeds = seeds.copyOf(seeds.size * 2 + 64)
                    seeds[nSeed++] = o
                }
            }
        }
        // 峰值：5x7 鄰域內最大（同值時索引小者為峰）
        val peaks = ArrayList<Long>()
        java.util.Arrays.sort(seeds, 0, nSeed)
        var lastO = -1
        for (sq in 0 until nSeed) for (dr0 in -1..1) {
            val o = seeds[sq] + dr0
            val t = o / nr; val r = o % nr
            if (o <= lastO || (dr0 == -1 && seeds[sq] % nr == 0) || (dr0 == 1 && seeds[sq] % nr == nr - 1)) continue
            lastO = o
            val v = h3(o, r, nr)
            if (v < minVotes) continue
            var pk = true
            loop@ for (dt in -2..2) {
                val tt = t + dt
                if (tt < 0 || tt >= 180) continue
                for (dr in -3..3) {
                    if (dt == 0 && dr == 0) continue
                    val rr = r + dr
                    if (rr < 0 || rr >= nr) continue
                    val nb = h3(tt * nr + rr, rr, nr)
                    if (dt < 0 || (dt == 0 && dr < 0)) { if (v <= nb) { pk = false; break@loop } } else if (v < nb) { pk = false; break@loop }
                }
            }
            if (pk) peaks.add((-v.toLong() shl 32) or o.toLong())
        }
        peaks.sort()
        tolF = max(1.2, GATHER_TOLF_M / mpp)
        gapPx = GAP_M / mpp
        minPiecePx = MIN_PIECE_M / mpp
        lineGap = LINE_GAP_M / mpp
        val raw = ArrayList<IntArray>()
        val np = min(peaks.size, MAX_PEAKS)
        peakLoop@ for (q in 0 until np) {
            if (raw.size >= MAX_LINES) break
            val o = (peaks[q] and 0xffffffffL).toInt()
            val t = o / nr
            val rho0 = (o % nr - rmax).toDouble()
            for (rep in 0 until 4) {
                if (raw.size >= MAX_LINES) break
                var n = 0
                val ct = COS_T[t]; val sn = SIN_T[t]
                // 法線角在 t ± (SECTOR+1) 內、離峰值直線 ≤ GATHER_TOL0 的未用像素
                forNear(ct, sn, rho0, GATHER_TOL0, t - (SECTOR + 1), t + (SECTOR + 1)) { k ->
                    if (!used[k] && abs(px[k] * ct + py[k] * sn - rho0) <= GATHER_TOL0) {
                        val dl = abs(pth[k] - t)
                        if (min(dl, 180 - dl) <= SECTOR + 1) idxA[n++] = k
                    }
                }
                java.util.Arrays.sort(idxA, 0, n)
                if (n < minVotes) break
                val d0x = -SIN_T[t]; val d0y = COS_T[t]
                // 沿峰值方向切群，取像素最多的一群（排序後的順序，與原型一致）
                for (k in 0 until n) sv[k] = px[idxA[k]] * d0x + py[idxA[k]] * d0y
                stableSortBy(idxA, sv, n)
                var bestN = -1; var bs = 0; var be = 0; var st = 0
                for (e in 1..n) {
                    if (e == n || sv[e] - sv[e - 1] > lineGap) {
                        if (e - st > bestN) { bestN = e - st; bs = st; be = e }
                        st = e
                    }
                }
                val s0 = sv[bs]; val s1 = sv[be - 1]
                var m = be - bs
                System.arraycopy(idxA, bs, idxB, 0, m)
                if (m < minVotes) break
                var ok = true
                for (it in 0 until 2) {
                    val f = fit(idxB, m)
                    val c = cos(f[2]); val s = sin(f[2])
                    val nrm = (f[2] * (180.0 / PI) + 90.0) % 180.0
                    var n2 = 0
                    val b0 = ceil(nrm - (SECTOR + 1)).toInt(); val b1 = floor(nrm + (SECTOR + 1)).toInt()
                    val f0 = f[0]; val f1 = f[1]
                    forNear(-s, c, f0 * (-s) + f1 * c, tolF, b0, b1) { k ->
                        if (!used[k] && abs((px[k] - f0) * (-s) + (py[k] - f1) * c) <= tolF &&
                            ((pth[k] - b0) % 180 + 180) % 180 <= b1 - b0 && secOk(pth[k], nrm)) {
                            val sp = px[k] * d0x + py[k] * d0y
                            if (sp >= s0 - 0.5 * lineGap && sp <= s1 + 0.5 * lineGap) idxB[n2++] = k
                        }
                    }
                    java.util.Arrays.sort(idxB, 0, n2)
                    m = n2
                    if (m < minVotes) { ok = false; break }
                }
                if (!ok) break
                val kept = splitPieces(idxB, m, null)
                if (kept.size < minVotes) break
                for (k in kept) used[k] = true
                raw.add(kept)
            }
        }
        // 共線的線段合併（被自車、車輛擋成兩段的同一條線），合併後殘差要小才算
        val mergeTol = max(1.5, MERGE_TOL_M / mpp)
        val fits = ArrayList<DoubleArray>()
        for (r in raw) fits.add(fit(r, r.size))
        var merged = true
        while (merged) {
            merged = false
            outer@ for (i in raw.indices) {
                for (j in i + 1 until raw.size) {
                    val fi = fits[i]; val fj = fits[j]
                    if (abs(sin(fi[2] - fj[2])) > SIN_MERGE) continue
                    val ci = cos(fi[2]); val si = sin(fi[2]); val cj = cos(fj[2]); val sj = sin(fj[2])
                    if (abs((fj[0] - fi[0]) * (-si) + (fj[1] - fi[1]) * ci) > mergeTol ||
                        abs((fi[0] - fj[0]) * (-sj) + (fi[1] - fj[1]) * cj) > mergeTol) continue
                    val uni = mergeSorted(raw[i], raw[j])
                    val fu = fit(uni, uni.size)
                    val cu = cos(fu[2]); val su = sin(fu[2])
                    var res = 0.0
                    for (k in uni) res = max(res, abs((px[k] - fu[0]) * (-su) + (py[k] - fu[1]) * cu))
                    if (res > 1.5 * tolF) continue
                    raw[i] = uni
                    fits[i] = fu
                    raw.removeAt(j)
                    fits.removeAt(j)
                    merged = true
                    break@outer
                }
            }
        }
        for (r in raw) {
            System.arraycopy(r, 0, idxT, 0, r.size)
            val pcsIdx = ArrayList<IntArray>()
            val kept = splitPieces(idxT, r.size, pcsIdx)
            if (kept.size < minVotes) continue
            val f = fit(kept, kept.size)
            val mxm = (f[0] + w / 2.0 - ocx) * mpp
            val mzm = (ocy - (f[1] + h / 2.0)) * mpp
            var ux = cos(f[2]); var uz = -sin(f[2])
            if (ux < -1e-12 || (abs(ux) <= 1e-12 && uz < 0)) { ux = -ux; uz = -uz }
            val pcs = DoubleArray(pcsIdx.size * 2)
            for ((q, pi) in pcsIdx.withIndex()) {
                var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
                for (k in pi) {
                    val xm = (px[k] + w / 2.0 - ocx) * mpp
                    val zm = (ocy - (py[k] + h / 2.0)) * mpp
                    val tv = (xm - mxm) * ux + (zm - mzm) * uz
                    if (tv < lo) lo = tv
                    if (tv > hi) hi = tv
                }
                pcs[2 * q] = lo - 0.5 * mpp
                pcs[2 * q + 1] = hi + 0.5 * mpp
            }
            sortPairs(pcs)
            lines.add(Line(mxm, mzm, ux, uz, pcs, kept.size))
        }
    }

    /**
     * 可能落在直線附近、法線角在桶 [b0, b1]（mod 180）的中心線像素索引 → body（呼叫端再做完整判斷）。
     * 桶裡的像素少就直接掃桶，否則只走直線附近的帶狀區域；兩種走法經過完整判斷後得到同一組像素。
     */
    private inline fun forNear(nX: Double, nY: Double, rho: Double, tol: Double, b0: Int, b1: Int, body: (Int) -> Unit) {
        var cntB = 0
        for (bb in b0..b1) { val b = (bb % 180 + 180) % 180; cntB += bucketStart[b + 1] - bucketStart[b] }
        val cntS = (if (abs(nY) >= abs(nX)) w else h) * (2 * tol / max(abs(nX), abs(nY)) + 3)
        if (cntB <= cntS) {
            for (bb in b0..b1) {
                val b = (bb % 180 + 180) % 180
                for (bq in bucketStart[b] until bucketStart[b + 1]) body(bucketIdx[bq])
            }
        } else forStrip(nX, nY, rho, tol, body)
    }

    /**
     * 直線 |x·nX + y·nY − rho| ≤ tol（像素中心座標 x = i+0.5−w/2、y = j+0.5−h/2）附近每個中心線像素的索引 → body。
     * 逐行（或逐列）只走帶狀區域（多留 1 px），呼叫端再做精確判斷，所以結果與掃全部像素相同。
     */
    private inline fun forStrip(nX: Double, nY: Double, rho: Double, tol: Double, body: (Int) -> Unit) {
        val hw = w / 2.0; val hh = h / 2.0
        if (abs(nY) >= abs(nX)) {
            for (i in 0 until w) {
                val x = i + 0.5 - hw
                val ya = (rho - x * nX - tol) / nY; val yb = (rho - x * nX + tol) / nY
                val j0 = max(0, floor(min(ya, yb) + hh - 0.5).toInt() - 1)
                val j1 = min(h - 1, ceil(max(ya, yb) + hh - 0.5).toInt() + 1)
                var j = j0
                while (j <= j1) { val k = kOf[j * w + i]; if (k >= 0) body(k); j++ }
            }
        } else {
            for (j in 0 until h) {
                val y = j + 0.5 - hh
                val xa = (rho - y * nY - tol) / nX; val xb = (rho - y * nY + tol) / nX
                val i0 = max(0, floor(min(xa, xb) + hw - 0.5).toInt() - 1)
                val i1 = min(w - 1, ceil(max(xa, xb) + hw - 0.5).toInt() + 1)
                val o = j * w
                var i = i0
                while (i <= i1) { val k = kOf[o + i]; if (k >= 0) body(k); i++ }
            }
        }
    }

    /** 三個 ρ bin 的和（Hough 峰值用）。 */
    private fun h3(o: Int, r: Int, nr: Int): Int {
        var v = hough[o]
        if (r > 0) v += hough[o - 1]
        if (r < nr - 1) v += hough[o + 1]
        return v
    }

    private fun secOk(base: Int, th: Double): Boolean {
        val dl = abs(base - th)          // base、th 都在 [0,180)，差 < 180，不必再取模
        return min(dl, 180.0 - dl) <= SECTOR + 1
    }

    /** PCA 擬合（依陣列順序逐項相加）：回傳 (mx, my, phi)。 */
    private fun fit(idx: IntArray, n: Int): DoubleArray {
        var sx = 0.0; var sy = 0.0
        for (q in 0 until n) { sx += px[idx[q]]; sy += py[idx[q]] }
        val mx = sx / n; val my = sy / n
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (q in 0 until n) {
            val dx = px[idx[q]] - mx; val dy = py[idx[q]] - my
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        return doubleArrayOf(mx, my, 0.5 * atan2(2.0 * sxy, sxx - syy))
    }

    /** 依擬合方向排序、以 > GAP 切段；太短或太稀疏的段丟掉。回傳保留像素（遞增）；pcs 非 null 時填入各段。 */
    private fun splitPieces(idx: IntArray, n: Int, pcs: ArrayList<IntArray>?): IntArray {
        val f = fit(idx, n)
        val c = cos(f[2]); val s = sin(f[2])
        for (q in 0 until n) sv[q] = (px[idx[q]] - f[0]) * c + (py[idx[q]] - f[1]) * s
        stableSortBy(idx, sv, n)
        val kept = IntArray(n)
        var nk = 0
        var st = 0
        for (e in 1..n) {
            if (e == n || sv[e] - sv[e - 1] > gapPx) {
                val span = sv[e - 1] - sv[st] + 1.0
                if (span >= minPiecePx && e - st >= 0.4 * span) {
                    pcs?.add(idx.copyOfRange(st, e))
                    for (q in st until e) kept[nk++] = idx[q]
                }
                st = e
            }
        }
        val out = kept.copyOf(nk)
        out.sort()
        return out
    }

    /** 依 key 穩定排序 idx[0..n)（key 同步排序）；合併排序。 */
    private fun stableSortBy(idx: IntArray, key: DoubleArray, n: Int) {
        if (n < 2) return
        val ti = IntArray(n); val tk = DoubleArray(n)
        var width = 1
        var srcI = idx; var srcK = key; var dstI = ti; var dstK = tk
        while (width < n) {
            var lo = 0
            while (lo < n) {
                val mid = min(lo + width, n); val hi = min(lo + 2 * width, n)
                var a = lo; var b = mid; var o = lo
                while (a < mid && b < hi) {
                    if (srcK[b] < srcK[a]) { dstI[o] = srcI[b]; dstK[o] = srcK[b]; b++ } else { dstI[o] = srcI[a]; dstK[o] = srcK[a]; a++ }
                    o++
                }
                while (a < mid) { dstI[o] = srcI[a]; dstK[o] = srcK[a]; a++; o++ }
                while (b < hi) { dstI[o] = srcI[b]; dstK[o] = srcK[b]; b++; o++ }
                lo = hi
            }
            val ri = srcI; val rk = srcK
            srcI = dstI; srcK = dstK; dstI = ri; dstK = rk
            width *= 2
        }
        if (srcI !== idx) { System.arraycopy(srcI, 0, idx, 0, n); System.arraycopy(srcK, 0, key, 0, n) }
    }

    private fun mergeSorted(a: IntArray, b: IntArray): IntArray {
        val out = IntArray(a.size + b.size)
        var i = 0; var j = 0; var o = 0
        while (i < a.size && j < b.size) out[o++] = if (a[i] <= b[j]) a[i++] else b[j++]
        while (i < a.size) out[o++] = a[i++]
        while (j < b.size) out[o++] = b[j++]
        return out
    }

    /** 線頭沿擬合直線延伸到遮罩斷掉為止（只走看得到、不在車框內的像素），再合併重疊 / 間隙 ≤ GAP 的段。 */
    private fun extend(l: Line) {
        val n = l.pieces.size / 2
        val out = DoubleArray(l.pieces.size)
        for (q in 0 until n) {
            val t0 = l.pieces[2 * q]; val t1 = l.pieces[2 * q + 1]
            var e0 = 0.0; var e1 = 0.0
            for (side in 0..1) {
                val sg = if (side == 0) -1.0 else 1.0
                val te = if (side == 0) t0 else t1
                var last = te - sg * 0.5 * mpp
                var t = last
                var miss = 0
                while (true) {
                    t += sg * mpp
                    val i = floor((l.cx + t * l.ux) / mpp + ocx).toInt()
                    val j = floor(ocy - (l.cz + t * l.uz) / mpp).toInt()
                    if (i < 0 || i >= w || j < 0 || j >= h || state[j * w + i].toInt() != 1) break
                    if (dil[j * w + i]) { last = t; miss = 0 } else { miss++; if (miss > EXT_MISS) break }
                }
                if (side == 0) e0 = last + sg * 0.5 * mpp else e1 = last + sg * 0.5 * mpp
            }
            out[2 * q] = min(t0, e0)
            out[2 * q + 1] = max(t1, e1)
        }
        sortPairs(out)
        val mg = DoubleArray(out.size)
        var m = 0
        for (q in 0 until n) {
            val a = out[2 * q]; val b = out[2 * q + 1]
            if (m > 0 && a - mg[2 * m - 1] <= GAP_M) mg[2 * m - 1] = max(mg[2 * m - 1], b)
            else { mg[2 * m] = a; mg[2 * m + 1] = b; m++ }
        }
        l.pieces = mg.copyOf(2 * m)
    }

    // ---------------------------------------------------------------- 取樣支持度
    private var segHit = 0
    private var segVis = 0
    private var segN = 0

    private fun seg(x0: Double, z0: Double, x1: Double, z1: Double, strict: Boolean) {
        val len = hypot(x1 - x0, z1 - z0)
        val n = max(2, ceil(len / mpp).toInt())
        var hit = 0; var vis = 0
        for (k in 0 until n) {
            val f = (k + 0.5) / n
            val x = x0 + (x1 - x0) * f
            val z = z0 + (z1 - z0) * f
            val i = floor(x / mpp + ocx).toInt()
            val j = floor(ocy - z / mpp).toInt()
            if (i < 0 || i >= w || j < 0 || j >= h) continue
            val p = j * w + i
            val v = state[p].toInt()
            if (v == 0 || (v == 2 && (strict || !dil[p]))) continue
            vis++
            if (dil[p] && !(strict && glyphD[p])) hit++
        }
        segHit = hit; segVis = vis; segN = n
    }

    /** 遮罩命中率（只算看得到的取樣點）；看得到的太少回傳 NaN（未知）。 */
    private fun ratio(x0: Double, z0: Double, x1: Double, z1: Double, strict: Boolean = false): Double {
        seg(x0, z0, x1, z1, strict)
        if (strict) {
            if (segVis < 3 || segVis * mpp < 0.25) return Double.NaN
        } else if (segVis < 3 || (segVis < 0.3 * segN && segVis * mpp < 1.0)) return Double.NaN
        return segHit.toDouble() / segVis
    }

    private fun ratioOn(f: Frame, l: Line, ta: Double, tb: Double, strict: Boolean = false): Double =
        ratio(f.onX(l, ta), f.onZ(l, ta), f.onX(l, tb), f.onZ(l, tb), strict)

    // ---------------------------------------------------------------- 線寬（停止線、斑馬線邊線比停車格線寬很多）
    private var pbuf = IntArray(0)
    private var pbufL = IntArray(0)
    private var pbufR = IntArray(0)

    /** (x0, z0) 沿法線 (nx, nz) 的漆通道剖面半高寬（m），步長 mpp/2、±0.45 m；看不到或對比太低回傳 NaN。 */
    private fun widthAt(x0: Double, z0: Double, nx: Double, nz: Double): Double {
        val hs = 0.5 * mpp
        val kK = ceil(0.45 / hs).toInt()
        val ko = ceil(0.3 / hs).toInt()
        val kc = (0.12 / hs).toInt()
        val n = 2 * kK + 1
        if (pbuf.size < n) { pbuf = IntArray(n); pbufL = IntArray(n); pbufR = IntArray(n) }
        for (k in -kK..kK) {
            val o = k * hs
            val i = floor((x0 + o * nx) / mpp + ocx).toInt()
            val j = floor(ocy - (z0 + o * nz) / mpp).toInt()
            if (i < 0 || i >= w || j < 0 || j >= h || state[j * w + i].toInt() != 1) return Double.NaN
            pbuf[k + kK] = paint[j * w + i]
        }
        var pk = kK - kc
        for (k in kK - kc..kK + kc) if (pbuf[k] > pbuf[pk]) pk = k
        val nl = kK - ko + 1
        System.arraycopy(pbuf, 0, pbufL, 0, nl)
        java.util.Arrays.sort(pbufL, 0, nl)
        val nr = n - (kK + ko)
        System.arraycopy(pbuf, kK + ko, pbufR, 0, nr)
        java.util.Arrays.sort(pbufR, 0, nr)
        val bg = max(pbufL[nl / 2], pbufR[nr / 2])
        if (pbuf[pk] - bg < 20) return Double.NaN
        val thr2 = pbuf[pk] + bg          // 半高門檻 ×2（整數比較）
        var a = pk
        while (a > 0 && 2 * pbuf[a - 1] >= thr2) a--
        var b = pk
        while (b < 2 * kK && 2 * pbuf[b + 1] >= thr2) b++
        return (b - a + 1) * hs
    }

    /**
     * 線 l 沿各線段每 WSTEP 一點（只取遮罩上的點；離段端 < 0.3 m 的不取，交會處量到的是別條線）的半高寬，
     * 每條線算一次，存在 l.wT / l.wW。
     */
    private fun widthSamples(l: Line) {
        if (l.wT != null) return
        val nx = -l.uz; val nz = l.ux
        var cnt = 0
        for (k in 0 until l.pieces.size / 2) cnt += max(0, ((l.pieces[2 * k + 1] - l.pieces[2 * k]) / WSTEP).toInt()) + 2
        val ts = DoubleArray(cnt); val ws = DoubleArray(cnt)
        var m = 0
        for (k in 0 until l.pieces.size / 2) {
            val a = l.pieces[2 * k]; val b = l.pieces[2 * k + 1]
            var q = 0
            while (true) {
                val t = a + 0.3 + WSTEP * q
                if (t > b - 0.3) break
                q++
                val x = l.cx + t * l.ux; val z = l.cz + t * l.uz
                val i = floor(x / mpp + ocx).toInt()
                val j = floor(ocy - z / mpp).toInt()
                if (i < 0 || i >= w || j < 0 || j >= h || !dil[j * w + i]) continue
                val v = widthAt(x, z, nx, nz)
                if (!v.isNaN()) { ts[m] = t; ws[m] = v; m++ }
            }
        }
        l.wT = ts; l.wW = ws; l.wN = m
    }

    private var wmed = DoubleArray(64)

    /** 線 l 在參數 [lo, hi] 內（且 |t - t0| ≥ gap）的半高寬（下）中位數；不到 nmin 點回傳 NaN。 */
    private fun widthMed(l: Line, lo: Double, hi: Double, gap: Double = -1.0, t0: Double = 0.0, nmin: Int = 2): Double {
        widthSamples(l)
        val ts = l.wT!!
        if (wmed.size < l.wN) wmed = DoubleArray(l.wN + 16)
        var m = 0
        for (q in 0 until l.wN) {
            val t = ts[q]
            if (lo <= t && t <= hi && abs(t - t0) >= gap) wmed[m++] = l.wW[q]
        }
        if (m < nmin) return Double.NaN
        java.util.Arrays.sort(wmed, 0, m)
        return wmed[(m - 1) / 2]
    }

    /** 橫邊與長邊的線寬差很多（停止線、機車停等區、斑馬線邊線 0.3 m 以上，停車格線 0.10–0.15 m）→ true。 */
    private fun wideCross(la: Line, lb: Line, cl: Line, pA: DoubleArray, pB: DoubleArray): Boolean {
        val ta = (pA[0] - cl.cx) * cl.ux + (pA[1] - cl.cz) * cl.uz
        val tb = (pB[0] - cl.cx) * cl.ux + (pB[1] - cl.cz) * cl.uz
        if (abs(tb - ta) < 0.9) return false
        // 橫邊寬度：兩條長邊之間的局部值與整條線的值取大（磨損的停止線局部可能只剩細細一條；被自車擋住時量不到）
        val wl = widthMed(cl, min(ta, tb) + 0.25, max(ta, tb) - 0.25)
        val wa = widthMed(cl, -1e9, 1e9, nmin = 3)
        if (wl.isNaN() && wa.isNaN()) return false
        val wc = max(if (wl.isNaN()) -1.0 else wl, if (wa.isNaN()) -1.0 else wa)
        var ws = -1.0
        for (which in 0..1) {                       // 長邊：交點前後 0.3–1.5 m
            val x = if (which == 0) la else lb
            val p = if (which == 0) pA else pB
            val t0 = (p[0] - x.cx) * x.ux + (p[1] - x.cz) * x.uz
            val v = widthMed(x, t0 - 1.5, t0 + 1.5, 0.3, t0)
            if (!v.isNaN()) ws = max(ws, v)
        }
        if (ws < 0) {
            for (which in 0..1) {
                val v = widthMed(if (which == 0) la else lb, -1e9, 1e9, nmin = 3)
                if (!v.isNaN()) ws = max(ws, v)
            }
        }
        if (ws < 0) return false
        return (wc >= WIDE_MIN && wc >= WIDE_RATIO * ws) || (ws >= WIDE_MIN && ws >= WIDE_RATIO * wc)
    }

    /** 被自車擋住的分隔線（index）。 */
    private val egoAdj = HashSet<Int>()
    /** 自車外框（排除區裡含原點的那個）；沒有 = null。 */
    private var egoBox: FloatArray? = null

    /**
     * 自己停在格子裡時，跟隔壁格共用的那條分隔線緊貼著自車側邊，大半被自己的車身擋住
     * （側鏡頭往下拍，車身鼓出來遮住車邊的地面），只看得到車頭或車尾旁邊一小段 → 格子湊不起來
     * （實車：露天停車場，左邊一格空位就是這樣漏掉的）。
     * 跟自車平行、貼著自車外框的線：延伸到整個車身長度，並記下來讓橫邊往它延伸時放寬。
     */
    private fun markEgoAdjacent(exclude: List<FloatArray>) {
        egoAdj.clear()
        egoBox = null
        val ego = exclude.firstOrNull { it[0] <= 0f && 0f <= it[2] && it[1] <= 0f && 0f <= it[3] } ?: return
        egoBox = ego
        for ((i, l) in lines.withIndex()) {
            if (abs(l.uz) < COS_EGO_PAR) continue
            // 線在 z 方向的範圍，跟自車有沒有前後重疊
            var tMin = Double.MAX_VALUE; var tMax = -Double.MAX_VALUE
            var k = 0
            while (k < l.pieces.size) { tMin = min(tMin, l.pieces[k]); tMax = max(tMax, l.pieces[k + 1]); k += 2 }
            val zA = l.cz + tMin * l.uz; val zB = l.cz + tMax * l.uz
            if (max(zA, zB) < ego[1] || min(zA, zB) > ego[3]) continue
            // 橫向位置（取線在自車中段 z = 0 的 x）
            val x0 = l.cx + (0.0 - l.cz) / l.uz * l.ux
            val left = x0 < 0
            val edge = if (left) ego[0].toDouble() else ego[2].toDouble()
            val out = if (left) edge - x0 else x0 - edge          // 在外框外多遠（負 = 伸進外框）
            if (out < -EGO_ADJ_IN || out > EGO_ADJ_OUT) continue
            val t0 = (ego[1] - l.cz) / l.uz; val t1 = (ego[3] - l.cz) / l.uz
            // 只有真的被擋住（車身範圍內看得到的不到一半）才補；整條看得到的線不動
            val lo = min(t0, t1); val hi = max(t0, t1)
            var seen = 0.0
            k = 0
            while (k < l.pieces.size) { seen += max(0.0, min(hi, l.pieces[k + 1]) - max(lo, l.pieces[k])); k += 2 }
            if (seen >= 0.5 * (hi - lo)) continue
            // 延伸到整個車身長度
            val add = doubleArrayOf(min(t0, t1).toDouble(), max(t0, t1).toDouble())
            val all = l.pieces + add
            sortPairs(all)
            val mg = DoubleArray(all.size); var m = 0
            for (q in 0 until all.size / 2) {
                val a = all[2 * q]; val b = all[2 * q + 1]
                if (m > 0 && a - mg[2 * m - 1] <= GAP_M) mg[2 * m - 1] = max(mg[2 * m - 1], b)
                else { mg[2 * m] = a; mg[2 * m + 1] = b; m++ }
            }
            l.pieces = mg.copyOf(2 * m)
            l.occ = add
            egoAdj.add(i)
            trace?.invoke("ego 共用線 (%.2f,%.2f) 在自車%s %.2f m 外，延伸到整個車身".format(l.cx, l.cz, if (left) "左" else "右", out))
        }
    }

    // ---------------------------------------------------------------- 3. 長邊對 → 橫邊 → 格子
    private fun pair(a: Int, b: Int, cells: ArrayList<Cell>) {
        val la = lines[a]; val lb = lines[b]
        val dot = la.ux * lb.ux + la.uz * lb.uz
        // 停在格子裡看隔壁格：俯視圖外圈拼接變形大（實車：離車 4 m 的外側線歪了 6°，跟緊貼自車的共用線差 4.6°）。
        // 只在「一條緊貼自車（2 m 內）、另一條在 3 m 外、同一側」時放寬，其他情況照原本的 4°
        val near = min(abs(la.cx), abs(lb.cx)); val farX = max(abs(la.cx), abs(lb.cx))
        val far = near < NEAR_X_M && farX > FAR_X_M && la.cx * lb.cx > 0 && abs(la.cz) < 3.0 && abs(lb.cz) < 3.0
        if (abs(la.ux * lb.uz - la.uz * lb.ux) > (if (far) SIN_PAR_FAR else SIN_PAR)) { if (far) trace?.invoke("pair ${tag(a, b)} 不平行"); return }
        val tr = trace
        val sg = if (dot >= 0) 1.0 else -1.0
        var ux = la.ux + sg * lb.ux; var uz = la.uz + sg * lb.uz
        val nn = hypot(ux, uz)
        ux /= nn; uz /= nn
        val f = Frame(la.cx, la.cz, ux, uz)
        val pa = f.pieces(la); val pb = f.pieces(lb)
        val minA = pa[0]; val minB = pb[0]
        var maxA = -Double.MAX_VALUE; var maxB = -Double.MAX_VALUE
        for (k in 0 until pa.size / 2) maxA = max(maxA, pa[2 * k + 1])
        for (k in 0 until pb.size / 2) maxB = max(maxB, pb[2 * k + 1])
        val lo = max(minA, minB); val hi = min(maxA, maxB)
        if (hi - lo < PAIR_OVL) { tr?.invoke("pair ${tag(a, b)} 重疊不足 ${hi - lo}"); return }
        val tm = 0.5 * (lo + hi)
        val xa = f.onX(la, tm); val za = f.onZ(la, tm); val xb = f.onX(lb, tm); val zb = f.onZ(lb, tm)
        val wdt = abs((xb - xa) * (-uz) + (zb - za) * ux)
        if (wdt < PAIR_WMIN || wdt > PAIR_WMAX) return
        tr?.invoke("pair ${tag(a, b)} 寬 %.2f 重疊 %.2f".format(wdt, hi - lo))
        val nCells0 = cells.size
        var crs = ArrayList<Crossing>()
        for ((ci, cl) in lines.withIndex()) {
            if (ci == a || ci == b) continue
            if (abs(cl.ux * ux + cl.uz * uz) > COS_CROSS) continue
            val pA = intersect(la, cl) ?: continue
            val pB = intersect(lb, cl) ?: continue
            // intersect() 算的是無限長直線的交點：兩公尺外一條 1 m 的斜線延伸過來照樣有交點，
            // 會把格子從中間切成兩半（實車地下室擷取就是這樣掉的）。要求橫線自己的線段
            // 真的伸到交點附近；被停著的車擋掉一段還是過得了（pieces 首尾涵蓋整段）。
            val tc0 = cl.pieces[0]
            var tc1 = cl.pieces[1]
            var pi = 3
            while (pi < cl.pieces.size) { if (cl.pieces[pi] > tc1) tc1 = cl.pieces[pi]; pi += 2 }
            var reach = 0.0
            for ((pk, pt) in arrayOf(pA, pB).withIndex()) {
                val tc = (pt[0] - cl.cx) * cl.ux + (pt[1] - cl.cz) * cl.uz
                var r = 0.0
                if (tc0 - tc > r) r = tc0 - tc
                if (tc - tc1 > r) r = tc - tc1
                // 往被自車擋住的那條長邊延伸不到：放寬（那段地面被車身擋住）。
                // 但緊貼自車頭尾（0.3 m 內）的橫線不放寬：那多半是排除區邊緣弄出來的假線
                val eb = egoBox
                val nearEgoEnd = eb != null && (abs(cl.cz - eb[1]) < 0.3 || abs(cl.cz - eb[3]) < 0.3)
                val lim = if ((if (pk == 0) a else b) in egoAdj && !nearEgoEnd) CROSS_REACH_EGO else CROSS_REACH
                if (r - lim > reach - CROSS_REACH) reach = r - lim + CROSS_REACH
            }
            if (reach > CROSS_REACH) continue
            val tA = (pA[0] - f.ox) * ux + (pA[1] - f.oz) * uz
            val tB = (pB[0] - f.ox) * ux + (pB[1] - f.oz) * uz
            val inA = minA - 1.0 <= tA && tA <= maxA + 1.0
            val inB = minB - 1.0 <= tB && tB <= maxB + 1.0
            // 至少一條長邊延伸到橫邊附近；另一條可能被車擋住
            if (!(inA || inB) || tA < minA - LANE_LEN || tA > maxA + LANE_LEN || tB < minB - LANE_LEN || tB > maxB + LANE_LEN) continue
            crossing(la, lb, f, pA, pB, tA, tB, ci)?.let { crs.add(it) }
        }
        crs = ArrayList(crs.sortedWith(compareBy<Crossing>({ it.t }, { it.ci })))
        val ded = ArrayList<Crossing>()
        for (c in crs) {
            var dup = -1
            for ((q, d) in ded.withIndex()) if (abs(d.tA - c.tA) < 0.35 && abs(d.tB - c.tB) < 0.35) { dup = q; break }
            if (dup < 0) ded.add(c) else if (c.ev > ded[dup].ev) ded[dup] = c
        }
        crs = ded
        tr?.invoke("pair ${tag(a, b)} 橫邊 ${crs.size} 條：" + crs.joinToString { "t=%.2f%s".format(it.t, if (it.perp) "⊥" else "∠") })
        // 開口端：兩條線在同一側真的結束（後面看得到地面但沒有線）
        val nline = crs.size
        val endsA = ends(la, f, pa); val endsB = ends(lb, f, pb)
        for (qa in 0 until endsA.size / 2) {
            val eA = endsA[2 * qa]; val sA = endsA[2 * qa + 1].toInt()
            for (qb in 0 until endsB.size / 2) {
                val eB = endsB[2 * qb]; val sB = endsB[2 * qb + 1].toInt()
                if (sA != sB) continue
                val ax = f.onX(la, eA); val az = f.onZ(la, eA); val bx = f.onX(lb, eB); val bz = f.onZ(lb, eB)
                var vx = bx - ax; var vz = bz - az
                val dv = hypot(vx, vz)
                if (dv < 1e-6) continue
                vx /= dv; vz /= dv
                val cosang = abs(vx * ux + vz * uz)
                if (cosang > COS_CROSS) continue
                var dup = false
                for ((q, c) in crs.withIndex()) {
                    if (abs(c.tA - eA) < 0.5 && abs(c.tB - eB) < 0.5 && (q < nline || c.sense == sA)) { dup = true; break }
                }
                if (dup) continue
                crs.add(Crossing(0.5 * (eA + eB), eA, eB, ax, az, bx, bz, vx, vz, cosang <= SIN_PERP, true, sA, 0.0,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, if (sA > 0) -2 else -1))
            }
        }
        crs = ArrayList(crs.sortedWith(compareBy<Crossing>({ it.t }, { it.ci })))
        for (k in 0 until crs.size - 1) {
            val c0 = crs[k]; val c1 = crs[k + 1]
            if (c0.open && c0.sense > 0) continue
            if (c1.open && c1.sense < 0) continue
            val lA = c1.tA - c0.tA; val lB = c1.tB - c0.tB
            if (lA < 0.5 || lB < 0.5) continue
            if (abs(c0.vx * c1.vz - c0.vz * c1.vx) > SIN_12) continue
            cells.add(Cell(a, b, f, wdt, c0, c1, 1, 0.5 * (lA + lB), false, ""))
        }
        tr?.invoke("pair ${tag(a, b)} 兩端都有橫邊的格子 ${cells.size - nCells0} 個")
        // 單側格：入口有 T/L 標記（或兩條分隔線在入口同時結束），另一端出畫面或沒畫
        for ((k, c) in crs.withIndex()) {
            if (c.open) {
                val s = -c.sense
                var near = false
                for ((q, d) in crs.withIndex()) {
                    val dt = (d.t - c.t) * s
                    if (q != k && 0.4 <= dt && dt <= LANE_LEN) { near = true; break }
                }
                if (near) continue
                val eA = run(pa, c.tA, s); val eB = run(pb, c.tB, s)
                val lA = abs(eA - c.tA); val lB = abs(eB - c.tB)
                if (min(lA, lB) < 1.2 || max(lA, lB) > LANE_LEN) continue
                if (dashAfter(pa, eA, s) || dashAfter(pb, eB, s)) continue      // 虛線車道線
                if (!c.perp && abs(uz) > COS_OBLIQUE) continue                // 斜角格的分隔線不會和車身平行
                cells.add(Cell(a, b, f, wdt, c, null, s, DEPTH_DEF, true, if (c.perp) "p" else "a"))
                continue
            }
            for (s in intArrayOf(1, -1)) {
                var near = false
                for ((q, d) in crs.withIndex()) {
                    if (q == k || d.open) continue
                    val dt = (d.t - c.t) * s
                    if (0.4 <= dt && dt <= LANE_LEN) { near = true; break }
                }
                if (near) continue
                val fa = if (s > 0) c.rAp else c.rAm
                val fb = if (s > 0) c.rBp else c.rBm
                val ga = if (s > 0) c.rAm else c.rAp
                val gb = if (s > 0) c.rBm else c.rBp
                if (!((fa >= 0.4 && (fb.isNaN() || fb >= 0.4)) || (fb >= 0.4 && fa.isNaN()))) continue
                val eA = run(pa, c.tA, s); val eB = run(pb, c.tB, s)
                val lA = abs(eA - c.tA); val lB = abs(eB - c.tB)
                val sb = StringBuilder()
                // 垂直 / 斜角格：橫邊是長邊對的終點（另一側沒有線），長邊不能太長（車道線）
                val term = !(ga > 0.3 || gb > 0.3)
                // 斜角格兩條分隔線都要真的伸進格內：斜紋、網狀線、字的筆畫湊出的斜格常常只有一條
                if (term && max(lA, lB) <= LANE_LEN && (c.perp || min(lA, lB) >= SEP_MIN_A) &&
                    !(1.4 <= max(lA, lB) && max(lA, lB) <= 4.2 && realEnd(la, f, eA, s) && realEnd(lb, f, eB, s))) {
                    sb.append(if (c.perp) 'p' else 'a')
                }
                // 平行格：長邊對是入口線與路緣側的線，往 s 側延伸到畫面外
                if (c.perp && PAIR_WMIN <= wdt && wdt <= 3.0 && min(lA, lB) >= 2.5 && max(lA, lB) <= 7.5 &&
                    !realEnd(la, f, eA, s) && !realEnd(lb, f, eB, s)) {
                    sb.append('l')
                }
                // 深度：分隔線真的在 4.2～6.5 m 處結束（後面看得到地面但沒線）就用它，不要一律套預設 5.5 m
                // （實車：自車旁的空格被畫得比實際深 0.3 m）。被自車擋住、補長的那條不算。
                fun endDepth(l: Line, e: Double, len: Double, adj: Boolean) =
                    if (!adj && len in 4.2..6.5 && realEnd(l, f, e, s)) len else Double.NaN
                // 只在明顯比預設短（差 0.3 m 以上）時才用；不會因此把格子畫得比預設深
                // （合成場景的分隔線會比格子後緣多畫一點，用它反而變深）
                val lineDepth = listOf(endDepth(la, eA, lA, a in egoAdj), endDepth(lb, eB, lB, b in egoAdj))
                    .filter { !it.isNaN() }.maxOrNull()
                val depth = if (lineDepth != null && lineDepth < DEPTH_DEF - 0.3) lineDepth else DEPTH_DEF
                if (sb.isNotEmpty()) cells.add(Cell(a, b, f, wdt, c, null, s, depth, true, sb.toString()))
            }
        }
    }

    /** 線段在共同座標下的「真的結束」端點：[t, sense, ...]。 */
    private fun ends(l: Line, f: Frame, pcs: DoubleArray): DoubleArray {
        val out = ArrayList<Double>()
        val n = pcs.size / 2
        // 被自車擋住而補上的那段，換到這個 Frame 的參數（端點落在這附近的不算真的線尾）
        val occF = l.occ?.let { o ->
            val a = f.pieces(Line(l.cx, l.cz, l.ux, l.uz, doubleArrayOf(o[0], o[1]), 0))
            doubleArrayOf(a[0], a[1])
        }
        fun synthetic(t: Double) = occF != null && (abs(t - occF[0]) < 0.3 || abs(t - occF[1]) < 0.3)
        for (q in 0 until n) {
            val p0 = pcs[2 * q]; val p1 = pcs[2 * q + 1]
            if ((q == 0 || p0 - pcs[2 * q - 1] >= END_GAP) && !synthetic(p0) && realEnd(l, f, p0, -1)) { out.add(p0); out.add(-1.0) }
            if ((q == n - 1 || pcs[2 * q + 2] - p1 >= END_GAP) && !synthetic(p1) && realEnd(l, f, p1, 1)) { out.add(p1); out.add(1.0) }
        }
        return out.toDoubleArray()
    }

    private fun realEnd(l: Line, f: Frame, e: Double, sense: Int): Boolean {
        val r = ratioOn(f, l, e + sense * 0.15, e + sense * 0.8)
        return !r.isNaN() && r <= 0.2
    }

    private fun crossing(
        la: Line, lb: Line, f: Frame, pA: DoubleArray, pB: DoubleArray, tA: Double, tB: Double, ci: Int
    ): Crossing? {
        var vx = pB[0] - pA[0]; var vz = pB[1] - pA[1]
        val dv = hypot(vx, vz)
        if (dv < 0.5) return null
        vx /= dv; vz /= dv
        val e0 = 0.12; val e1 = 0.45
        val rc = ratio(pA[0] + e0 * vx, pA[1] + e0 * vz, pB[0] - e0 * vx, pB[1] - e0 * vz)
        val cA = ratio(pA[0] + e0 * vx, pA[1] + e0 * vz, pA[0] + e1 * vx, pA[1] + e1 * vz)
        val cB = ratio(pB[0] - e1 * vx, pB[1] - e1 * vz, pB[0] - e0 * vx, pB[1] - e0 * vz)
        val strong = rc >= 0.55 || (cA >= 0.55 && cB >= 0.55)
        val partial = (cA >= 0.55 && cB.isNaN()) || (cB >= 0.55 && cA.isNaN())
        if (!(strong || partial)) return null
        val rAp = ratioOn(f, la, tA + 0.15, tA + 1.2)
        val rAm = ratioOn(f, la, tA - 0.15, tA - 1.2)
        val rBp = ratioOn(f, lb, tB + 0.15, tB + 1.2)
        val rBm = ratioOn(f, lb, tB - 0.15, tB - 1.2)
        val okA = rAp >= 0.4 || rAm >= 0.4 || (rAp.isNaN() && rAm.isNaN())
        val okB = rBp >= 0.4 || rBm >= 0.4 || (rBp.isNaN() && rBm.isNaN())
        if (!(okA && okB)) return null
        if (wideCross(la, lb, lines[ci], pA, pB)) return null
        val km = if (!cA.isNaN() && !cB.isNaN()) (cA + cB) / 2.0 else if (!cA.isNaN()) cA else if (!cB.isNaN()) cB else 0.0
        val ev = max(if (rc.isNaN()) 0.0 else rc, km)
        val cosang = abs(vx * f.ux + vz * f.uz)
        return Crossing(0.5 * (tA + tB), tA, tB, pA[0], pA[1], pB[0], pB[1], vx, vz, cosang <= SIN_PERP, false, 0,
            min(1.0, ev), cA, cB, rAp, rAm, rBp, rBm, ci)
    }

    // ---------------------------------------------------------------- 4. 格子驗證
    private fun evalCell(c: Cell): Cand? {
        val la = lines[c.a]; val lb = lines[c.b]; val f = c.f
        val w = c.w
        val c0 = c.c0; val c1 = c.c1; val s = c.s
        val tA0 = c0.tA; val tB0 = c0.tB
        val tA1 = c1?.tA ?: (tA0 + s * c.len)      // 單側格：c.len = 深度（分隔線結束處，否則預設）
        val tB1 = c1?.tB ?: (tB0 + s * c.len)
        val len = 0.5 * (abs(tA1 - tA0) + abs(tB1 - tB0))
        val perp = c0.perp && (c1 == null || c1.perp)
        val ang = !c0.perp && (c1 == null || !c1.perp)
        val nOpen = (if (c0.open) 1 else 0) + (if (c1 != null && c1.open) 1 else 0)
        val kinds = StringBuilder()
        if (c.one) {
            for (k in c.okinds) if (k == 'l' || (2.0 <= w && w <= 3.4)) kinds.append(k)
        } else if (perp) {
            if (2.0 <= w && w <= 3.4 && 4.2 <= len && len <= 7.0 && (nOpen == 0 || (len >= 4.5 && w <= 3.2))) kinds.append('p')
            if (1.8 <= w && w <= 3.0 && 4.8 <= len && len <= 7.5 && nOpen == 0) kinds.append('l')
        } else if (ang) {
            if (2.0 <= w && w <= 3.4 && 4.0 <= len && len <= 7.0) kinds.append('a')
        }
        val tr = trace
        if (kinds.isEmpty()) { tr?.invoke("eval ${tag(c.a, c.b)} 種類不合：寬 %.2f 長 %.2f perp=$perp open=$nOpen one=${c.one}".format(w, len)); return null }
        val ak: DoubleArray; val bk: DoubleArray; val am: DoubleArray; val bm: DoubleArray
        if (c1 == null) {
            ak = doubleArrayOf(c0.pAx, c0.pAz); bk = doubleArrayOf(c0.pBx, c0.pBz)
            am = doubleArrayOf(f.onX(la, tA1), f.onZ(la, tA1)); bm = doubleArrayOf(f.onX(lb, tB1), f.onZ(lb, tB1))
        } else {
            ak = doubleArrayOf(c0.pAx, c0.pAz); bk = doubleArrayOf(c0.pBx, c0.pBz)
            am = doubleArrayOf(c1.pAx, c1.pAz); bm = doubleArrayOf(c1.pBx, c1.pBz)
        }
        // 長邊支持度。跟自車共用、被車身擋住的分隔線（[markEgoAdjacent]）看得到的本來就只有一小段，
        // 支持度沒有參考價值 → 不足時當作剛好及格
        val adjA = c.a in egoAdj; val adjB = c.b in egoAdj
        val rA0 = ratioOn(f, la, min(tA0, tA1) + 0.2, max(tA0, tA1) - 0.2)
        val rB0 = ratioOn(f, lb, min(tB0, tB1) + 0.2, max(tB0, tB1) - 0.2)
        val rA = if (adjA && (rA0.isNaN() || rA0 < 0.6)) 0.6 else rA0
        val rB = if (adjB && (rB0.isNaN() || rB0 < 0.6)) 0.6 else rB0
        // 四角：長邊靠角 0.12–0.6 m 與橫邊靠角
        var cornersOk = 0
        var cornersN = 0
        for (side in 0..1) {
            val cr = (if (side == 0) c0 else c1) ?: continue
            if (cr.open) continue
            val sg = if (side == 0) s else -s
            for (which in 0..1) {
                val l = if (which == 0) la else lb
                val t = if (which == 0) cr.tA else cr.tB
                val cc = if (which == 0) cr.cA else cr.cB
                cornersN++
                val pr = ratioOn(f, l, t + sg * 0.12, t + sg * 0.6)
                if (pr >= 0.5 && cc >= 0.55) cornersOk++
            }
        }
        var row = false
        if (c.one && c0.open) {
            if (rA.isNaN() || rB.isNaN() || rA < 0.6 || rB < 0.6 || w > OPEN_WMAX) { tr?.invoke("eval ${tag(c.a, c.b)} 單側開口 rA=%.2f rB=%.2f w=%.2f".format(rA, rB, w)); return null }
            row = true
        } else if (c.one) {
            val hidden = c0.cA.isNaN() || c0.cB.isNaN() || adjA || adjB
            if (cornersOk < 1 || (cornersOk < 2 && cornersN == 2 && !hidden)) { tr?.invoke("eval ${tag(c.a, c.b)} 單側角不足 $cornersOk/$cornersN"); return null }
        } else if (nOpen == 0) {
            val okA = rA >= 0.35; val okB = rB >= 0.35
            val sepOk = (okA && okB) || (okA && rB.isNaN()) || (okB && rA.isNaN())
            if (!sepOk && cornersOk < 3) { tr?.invoke("eval ${tag(c.a, c.b)} 長邊支持不足 rA=%.2f rB=%.2f 角 $cornersOk/$cornersN".format(rA, rB)); return null }
        } else if (nOpen == 1) {
            // 角：被自車擋住的那側角看不到，另一側有一個角就夠
            if (rA.isNaN() || rB.isNaN() || rA < 0.5 || rB < 0.5 || (cornersOk < 1 && !(adjA || adjB))) {
                tr?.invoke("eval ${tag(c.a, c.b)} 一端開口：rA=%.2f rB=%.2f 角 $cornersOk/$cornersN 長 %.2f".format(rA, rB, len)); return null
            }
        } else {
            if (rA.isNaN() || rB.isNaN() || rA < 0.6 || rB < 0.6 || w > OPEN_WMAX) { tr?.invoke("eval ${tag(c.a, c.b)} 兩端開口 rA=%.2f rB=%.2f".format(rA, rB)); return null }
        }
        // 格內不得有平行線 / 橫線
        if (interior(f, c.a, c.b, tA0, tA1, tB0, tB1, c0, c1, adjA || adjB)) { tr?.invoke("eval ${tag(c.a, c.b)} 格內有線"); return null }
        val rsep = if (!rA.isNaN() && !rB.isNaN()) (rA + rB) / 2.0 else if (!rA.isNaN()) rA else if (!rB.isNaN()) rB else 0.3
        val cfrac = if (cornersN > 0) cornersOk.toDouble() / cornersN else 0.5
        var endsEv = 0.0
        for (side in 0..1) {
            val cr = if (side == 0) c0 else c1
            endsEv += if (cr == null) 0.3 else if (cr.open) 0.6 else cr.ev
        }
        val score = 0.35 * min(1.0, rsep / 0.8) + 0.15 * endsEv + 0.25 * cfrac + 0.1 * (if (c1 != null) 1.0 else 0.0)
        if (score < 0.40) { tr?.invoke("eval ${tag(c.a, c.b)} 分數 %.2f < 0.40（rA=%.2f rB=%.2f 角 $cornersOk/$cornersN）".format(score, rA, rB)); return null }
        if (clutter(ak, bk, bm, am) > CLUTTER_MAX) { tr?.invoke("eval ${tag(c.a, c.b)} 雜線太多"); return null }
        if (ring(ak, bk, am) > RING_MAX) { tr?.invoke("eval ${tag(c.a, c.b)} 外圍紋理太多"); return null }
        tr?.invoke("eval ${tag(c.a, c.b)} 通過 分數 %.2f 種類 $kinds".format(score))
        return Cand(c, kinds.toString(), ak, bk, am, bm, score, row)
    }

    /**
     * 格子外圍 RING 寬的一圈（o→pa、o→pb 張成的平行四邊形往外擴）看得到的像素中標線遮罩的比例。
     * 植草磚、連鎖磚這類整片紋理會很高；停車場裡格子旁邊多半是空的地面。每 2 px 取樣。
     */
    private fun ring(o: DoubleArray, pa: DoubleArray, pb: DoubleArray): Double {
        val ax = pa[0] - o[0]; val az = pa[1] - o[1]
        val bx = pb[0] - o[0]; val bz = pb[1] - o[1]
        val la = hypot(ax, az); val lb = hypot(bx, bz)
        if (la < 1e-6 || lb < 1e-6) return 0.0
        val ea = RING / la; val eb = RING / lb
        val na = ceil((la + 2 * RING) / (2 * mpp)).toInt()
        val nb = ceil((lb + 2 * RING) / (2 * mpp)).toInt()
        var n = 0; var m = 0
        for (q in 0..nb) {
            val fb = -eb + (1 + 2 * eb) * q / nb
            val inb = 0.0 <= fb && fb <= 1.0
            for (k in 0..na) {
                val fa = -ea + (1 + 2 * ea) * k / na
                if (inb && 0.0 <= fa && fa <= 1.0) continue
                val x = o[0] + fa * ax + fb * bx
                val z = o[1] + fa * az + fb * bz
                val i = floor(x / mpp + ocx).toInt()
                val j = floor(ocy - z / mpp).toInt()
                if (i < 0 || i >= w || j < 0 || j >= h) continue
                val p = j * w + i
                if (state[p].toInt() != 1) continue
                n++
                if (mask[p]) m++
            }
        }
        return if (n >= 30) m.toDouble() / n else 0.0
    }

    /** 格內（往中心縮 15 %）看得到的像素中標線遮罩的比例；草叢、樹影拖曳的雜線會很高。每 2 px 取樣。 */
    private fun clutter(a: DoubleArray, b: DoubleArray, c: DoubleArray, d: DoubleArray): Double {
        val gx = 0.25 * (a[0] + b[0] + c[0] + d[0])
        val gz = 0.25 * (a[1] + b[1] + c[1] + d[1])
        val q = DoubleArray(8)
        for ((k, v) in arrayOf(a, b, c, d).withIndex()) {
            q[2 * k] = gx + (v[0] - gx) * 0.85
            q[2 * k + 1] = gz + (v[1] - gz) * 0.85
        }
        if (signedArea(q) < 0) reverseQuad(q)
        var x0 = q[0]; var x1 = q[0]; var z0 = q[1]; var z1 = q[1]
        for (k in 1 until 4) { x0 = min(x0, q[2 * k]); x1 = max(x1, q[2 * k]); z0 = min(z0, q[2 * k + 1]); z1 = max(z1, q[2 * k + 1]) }
        val i0 = max(0, floor(x0 / mpp + ocx).toInt())
        val i1 = min(w - 1, floor(x1 / mpp + ocx).toInt())
        val j0 = max(0, floor(ocy - z1 / mpp).toInt())
        val j1 = min(h - 1, floor(ocy - z0 / mpp).toInt())
        var n = 0; var m = 0
        var j = j0
        while (j <= j1) {
            val z = (ocy - (j + 0.5)) * mpp
            var i = i0
            while (i <= i1) {
                val p = j * w + i
                if (state[p].toInt() == 1) {
                    val x = (i + 0.5 - ocx) * mpp
                    var inside = true
                    for (k in 0 until 4) {
                        val ax = q[2 * k]; val az = q[2 * k + 1]
                        val bx = q[(2 * k + 2) % 8]; val bz = q[(2 * k + 3) % 8]
                        if ((bx - ax) * (z - az) - (bz - az) * (x - ax) < 0) { inside = false; break }
                    }
                    if (inside) { n++; if (mask[p] && !glyphM[p]) m++ }
                }
                i += 2
            }
            j += 2
        }
        return if (n >= 20) m.toDouble() / n else 0.0
    }

    private var stubR = DoubleArray(0)

    /** 格內有與長邊平行的線（機車格、兩格合成一格）或橫過的線 → true。車框內不算。 */
    private fun interior(
        f: Frame, ia: Int, ib: Int, tA0: Double, tA1: Double, tB0: Double, tB1: Double, c0: Crossing, c1: Crossing?,
        egoRow: Boolean = false
    ): Boolean {
        val la = lines[ia]; val lb = lines[ib]
        val sA = if (tA1 >= tA0) 1.0 else -1.0
        val sB = if (tB1 >= tB0) 1.0 else -1.0
        // 從真的橫邊伸進格內的短分隔線頭（機車格的 T 字、每 1 m 一格）→ 不是汽車格。
        // 沿橫邊在寬度 20–80 % 處逐點往格內探 STUB_IN（直接看遮罩，短線頭常常抽不成 Hough 線）；
        // 線頭是細的：該點命中、左右各 0.2 m 的探針都沒命中（橫向的東西會讓三支都命中）。
        // 跟自車共用分隔線的格子（自己停的是汽車格，同一排的隔壁也是）→ 不做機車格線頭檢查：
        // 格號數字寫在前端橫線旁，筆畫會被當成線頭（實車：16 號格因此被淘汰）
        for (side in 0..1) {
            if (egoRow) break
            val cr = (if (side == 0) c0 else c1) ?: continue
            if (cr.open) continue
            val sg = if (side == 0) sA else -sA
            val ax = cr.pAx; val az = cr.pAz; val bx = cr.pBx; val bz = cr.pBz
            val wd = hypot(bx - ax, bz - az)
            val sn = abs(f.ux * (bz - az) - f.uz * (bx - ax)) / max(wd, 1e-9)
            val e0 = 0.1 / max(sn, 0.5)
            val m = ceil(0.6 * wd / mpp).toInt()
            val dl = Math.rint(0.2 / (0.6 * wd / m)).toInt()
            val nr = m + 2 * dl + 1
            if (stubR.size < nr) stubR = DoubleArray(nr + 16)
            for (k in -dl..m + dl) {
                val fr = 0.2 + 0.6 * k / m
                val px = ax + (bx - ax) * fr; val pz = az + (bz - az) * fr
                seg(px + sg * e0 * f.ux, pz + sg * e0 * f.uz, px + sg * (e0 + STUB_IN) * f.ux, pz + sg * (e0 + STUB_IN) * f.uz, true)
                stubR[k + dl] = if (segVis >= 0.8 * segN) segHit.toDouble() / segVis else -1.0   // 被擋住 / 看不到 → 不判斷
            }
            for (k in 0..m) {
                val l0 = stubR[k]; val l2 = stubR[k + 2 * dl]
                if (stubR[k + dl] >= 0.7 && 0.0 <= l0 && l0 < 0.4 && 0.0 <= l2 && l2 < 0.4) return true
            }
        }
        val a0x = f.onX(la, tA0 + sA * 0.5); val a0z = f.onZ(la, tA0 + sA * 0.5)
        val a1x = f.onX(la, tA1 - sA * 0.5); val a1z = f.onZ(la, tA1 - sA * 0.5)
        val b0x = f.onX(lb, tB0 + sB * 0.5); val b0z = f.onZ(lb, tB0 + sB * 0.5)
        val b1x = f.onX(lb, tB1 - sB * 0.5); val b1z = f.onZ(lb, tB1 - sB * 0.5)
        for (fr in doubleArrayOf(0.3, 0.5, 0.7)) {
            val r = ratio(a0x + (b0x - a0x) * fr, a0z + (b0z - a0z) * fr, a1x + (b1x - a1x) * fr, a1z + (b1z - a1z) * fr, true)
            if (r >= 0.45) return true
        }
        for (g in doubleArrayOf(0.3, 0.5, 0.7)) {
            val ta = tA0 + (tA1 - tA0) * g; val tb = tB0 + (tB1 - tB0) * g
            val pax = f.onX(la, ta); val paz = f.onZ(la, ta); val pbx = f.onX(lb, tb); val pbz = f.onZ(lb, tb)
            var vx = pbx - pax; var vz = pbz - paz
            val dv = hypot(vx, vz)
            vx /= dv; vz /= dv
            val r = ratio(pax + 0.3 * vx, paz + 0.3 * vz, pbx - 0.3 * vx, pbz - 0.3 * vz, true)
            if (r >= 0.45) return true
        }
        // 偵測到的線：在格內的平行線、或從兩長邊中間橫過的線
        val ux = f.ux; val uz = f.uz
        val nx = -uz; val nz = ux
        val tma = 0.5 * (tA0 + tA1); val tmb = 0.5 * (tB0 + tB1)
        val pAx = f.onX(la, tma); val pAz = f.onZ(la, tma); val pBx = f.onX(lb, tmb); val pBz = f.onZ(lb, tmb)
        val wB = (pBx - pAx) * nx + (pBz - pAz) * nz
        val lo = max(min(tA0, tA1), min(tB0, tB1)) + 0.15
        val hi = min(max(tA0, tA1), max(tB0, tB1)) - 0.15
        for ((k, x) in lines.withIndex()) {
            if (k == ia || k == ib) continue
            if (abs(x.ux * uz - x.uz * ux) <= SIN_PAR) {
                val off = ((x.cx - pAx) * nx + (x.cz - pAz) * nz) / wB
                if (off <= 0.15 || off >= 0.85 || hi - lo < 0.6) continue
                // 命中比例高，或看得到的線夠長（斜紋禁停區的斜線常常只有一段在格內）
                val r = ratioOn(f, x, lo, hi, true)
                if (!r.isNaN() && (r >= 0.45 || segHit * (hi - lo) / segN >= INNER_PAR)) return true
            } else if (abs(x.ux * ux + x.uz * uz) <= COS_CROSS) {
                val pA = intersect(la, x) ?: continue
                val pB = intersect(lb, x) ?: continue
                val tA = (pA[0] - f.ox) * ux + (pA[1] - f.oz) * uz
                val tB = (pB[0] - f.ox) * ux + (pB[1] - f.oz) * uz
                val fa = (tA - tA0) / (tA1 - tA0)
                val fb = (tB - tB0) / (tB1 - tB0)
                if (fa <= 0.1 || fa >= 0.9 || fb <= 0.1 || fb >= 0.9) continue
                var vx = pB[0] - pA[0]; var vz = pB[1] - pA[1]
                val dv = hypot(vx, vz)
                if (dv < 0.8) continue
                vx /= dv; vz /= dv
                val r = ratio(pA[0] + 0.3 * vx, pA[1] + 0.3 * vz, pB[0] - 0.3 * vx, pB[1] - 0.3 * vz, true)
                if (r >= 0.45) return true
            }
        }
        return false
    }

    /**
     * 只有分隔線的單側格：成排（與別格共用分隔線），或分隔線明顯斜於車頭方向（車道線多半與車身平行）。
     * 成排時鄰格的入口邊（開口端或入口線）方向要一致；不一致是被柱子 / 磨損截短的分隔線湊出的鋸齒，不算。
     */
    private fun rowOk(c: Cand, all: List<Cand>): Boolean {
        val a = c.cell.c0
        var zig = false
        for (d in all) {
            if (d === c || !longNeighbors(c.cell, d.cell)) continue
            val b = d.cell.c0
            if (abs(a.vx * b.vz - a.vz * b.vx) > SIN_12) { zig = true; continue }
            return true
        }
        return !zig && abs(c.cell.f.uz) < COS_CROSS
    }

    private fun cellRange(cell: Cell, which: Int): DoubleArray {
        val t0 = if (which == 0) cell.c0.tA else cell.c0.tB
        val c1 = cell.c1
        val t1 = if (c1 != null) (if (which == 0) c1.tA else c1.tB) else t0 + cell.s * cell.len
        return doubleArrayOf(min(t0, t1), max(t0, t1))
    }

    /** 格子在某條長邊上的區段，用該線自身參數表示。 */
    private fun spanOn(cell: Cell, which: Int): DoubleArray {
        val f = cell.f
        val x = lines[if (which == 0) cell.a else cell.b]
        val r = cellRange(cell, which)
        val s0 = (f.onX(x, r[0]) - x.cx) * x.ux + (f.onZ(x, r[0]) - x.cz) * x.uz
        val s1 = (f.onX(x, r[1]) - x.cx) * x.ux + (f.onZ(x, r[1]) - x.cz) * x.uz
        return doubleArrayOf(min(s0, s1), max(s0, s1))
    }

    /** 兩格共用一條長邊（同一條線、分處兩側、範圍重疊過半）。 */
    private fun longNeighbors(c: Cell, d: Cell): Boolean {
        for (wc in 0..1) {
            val lc = if (wc == 0) c.a else c.b
            for (wd in 0..1) {
                val ld = if (wd == 0) d.a else d.b
                if (lc != ld) continue
                val oc = if (wc == 0) c.b else c.a
                val od = if (wd == 0) d.b else d.a
                if (oc == od) continue
                val x = lines[lc]; val yc = lines[oc]; val yd = lines[od]
                val sc = (yc.cx - x.cx) * (-x.uz) + (yc.cz - x.cz) * x.ux
                val sd = (yd.cx - x.cx) * (-x.uz) + (yd.cz - x.cz) * x.ux
                if (sc * sd >= 0) continue
                val ra = spanOn(c, wc); val rb = spanOn(d, wd)
                val ov = min(ra[1], rb[1]) - max(ra[0], rb[0])
                if (ov >= 0.5 * min(ra[1] - ra[0], rb[1] - rb[0])) return true
            }
        }
        return false
    }

    /** 同一長邊對、共用一個橫邊、分處兩側。 */
    private fun shortNeighbors(c: Cell, d: Cell): Boolean {
        if (c.a != d.a || c.b != d.b) return false
        for (x in arrayOf(c.c0, c.c1)) for (y in arrayOf(d.c0, d.c1)) if (x != null && x === y) return true
        return false
    }

    // ---------------------------------------------------------------- 5. 類型與入口
    private fun decideKinds(cands: List<Cand>) {
        for ((i, c) in cands.withIndex()) c.order = i
        for (c in cands) {
            val cell = c.cell
            val quad = doubleArrayOf(c.ak[0], c.ak[1], c.bk[0], c.bk[1], c.bm[0], c.bm[1], c.am[0], c.am[1])
            val kind: Char
            if (c.kinds.length == 1) kind = c.kinds[0]
            else {
                var vp = 0.0; var vl = 0.0
                if (!c.kinds.contains('l')) vp += 10
                if (!c.kinds.contains('p')) vl += 10
                val fe = facingEdge2(quad, ALL_EDGES)
                if (fe >= 0) { if (fe == 0 || fe == 2) vp += 1.0 else vl += 1.0 }
                if (!cell.one) { if (cell.len >= 5.9) vl += 0.6 else if (cell.len <= 5.6) vp += 0.6 }
                if (cell.w <= 2.3) vl += 0.4 else if (cell.w >= 2.45) vp += 0.4
                for (d in cands) {
                    if (d === c) continue
                    val wgt = if (d.cell.one || cell.one) 1.0 else 1.5
                    if (longNeighbors(cell, d.cell)) vp += wgt
                    else if (shortNeighbors(cell, d.cell)) vl += wgt
                }
                kind = if (vp >= vl) 'p' else 'l'
            }
            c.kind = kind
            if (kind == 'l' && cell.one) {
                // 平行格只看到一個橫邊：遠端用標準長度 6 m
                val f = cell.f; val la = lines[cell.a]; val lb = lines[cell.b]
                val tb = cell.c0.tB + cell.s * DEPTH_PAR; val ta = cell.c0.tA + cell.s * DEPTH_PAR
                quad[4] = f.onX(lb, tb); quad[5] = f.onZ(lb, tb)
                quad[6] = f.onX(la, ta); quad[7] = f.onZ(la, ta)
            }
            // 入口邊（quad 的邊 0 = c0 橫邊, 1 = B 長邊, 2 = c1 橫邊, 3 = A 長邊）
            val ent: Int
            if (kind == 'p' || kind == 'a') {
                val zmin = min(min(quad[1], quad[3]), min(quad[5], quad[7]))
                val zmax = max(max(quad[1], quad[3]), max(quad[5], quad[7]))
                if (kind == 'p' && zmin < -0.5 && zmax > 0.5 && abs(cell.f.uz) > COS_CROSS) {
                    // 和自車並排（分隔線與車身平行）：入口取車頭那端（台灣多倒車入庫）
                    ent = if (0.5 * (quad[1] + quad[3]) >= 0.5 * (quad[5] + quad[7])) 0 else 2
                } else if (cell.one) {
                    ent = 0
                } else {
                    var fe = facingEdge2(quad, CROSS_EDGES)
                    if (fe < 0) fe = if (0.5 * (quad[1] + quad[3]) >= 0.5 * (quad[5] + quad[7])) 0 else 2
                    ent = fe
                }
            } else {
                var fe = facingEdge2(quad, LONG_EDGES)
                if (fe < 0) fe = if (0.5 * (quad[2] + quad[4]) <= 0.5 * (quad[6] + quad[0])) 1 else 3
                ent = fe
            }
            val poly = DoubleArray(8)
            for (k in 0 until 4) {
                val v = (ent + k) % 4
                poly[2 * k] = quad[2 * v]; poly[2 * k + 1] = quad[2 * v + 1]
            }
            if (signedArea(poly) < 0) {
                // [p1,p0,p3,p2]：入口兩點對調，遠端兩點對調
                val t0x = poly[0]; val t0z = poly[1]; poly[0] = poly[2]; poly[1] = poly[3]; poly[2] = t0x; poly[3] = t0z
                val t2x = poly[4]; val t2z = poly[5]; poly[4] = poly[6]; poly[5] = poly[7]; poly[6] = t2x; poly[7] = t2z
            }
            c.poly = poly
        }
    }

    private val ALL_EDGES = intArrayOf(0, 1, 2, 3)
    private val CROSS_EDGES = intArrayOf(0, 2)
    private val LONG_EDGES = intArrayOf(1, 3)

    /** 面向原點（自車）的邊：原點在其外側且距離最近者；沒有回傳 -1。 */
    private fun facingEdge(quad: DoubleArray, only: IntArray): Int {
        val gx = (quad[0] + quad[2] + quad[4] + quad[6]) / 4.0
        val gz = (quad[1] + quad[3] + quad[5] + quad[7]) / 4.0
        var best = -1; var bf = 1e18
        for (e in only) {
            val q1x = quad[2 * e]; val q1z = quad[2 * e + 1]
            val q2x = quad[(2 * e + 2) % 8]; val q2z = quad[(2 * e + 3) % 8]
            val ex = q2x - q1x; val ez = q2z - q1z
            var nx = -ez; var nz = ex
            if ((gx - q1x) * nx + (gz - q1z) * nz > 0) { nx = -nx; nz = -nz }
            val nl = hypot(nx, nz)
            if (nl < 1e-9) continue
            val f = ((0.0 - q1x) * nx + (0.0 - q1z) * nz) / nl
            if (f > 0 && f < bf) { best = e; bf = f }
        }
        return best
    }

    /** 入口面向自車：在自車左右 → 朝向自車、較平行車身的邊；在車頭 / 車尾方向 → 較垂直車身的邊。取中點最近者。 */
    private fun facingEdge2(quad: DoubleArray, only: IntArray): Int {
        val xmin = min(min(quad[0], quad[2]), min(quad[4], quad[6]))
        val xmax = max(max(quad[0], quad[2]), max(quad[4], quad[6]))
        val corr = xmin < CORRIDOR && xmax > -CORRIDOR
        val gx = (quad[0] + quad[2] + quad[4] + quad[6]) / 4.0
        val gz = (quad[1] + quad[3] + quad[5] + quad[7]) / 4.0
        var best = -1; var bd = 1e18
        for (e in only) {
            val q1x = quad[2 * e]; val q1z = quad[2 * e + 1]
            val q2x = quad[(2 * e + 2) % 8]; val q2z = quad[(2 * e + 3) % 8]
            val mx = 0.5 * (q1x + q2x); val mz = 0.5 * (q1z + q2z)
            var nx = -(q2z - q1z); var nz = q2x - q1x
            if ((gx - q1x) * nx + (gz - q1z) * nz > 0) { nx = -nx; nz = -nz }
            val ok = if (corr) abs(nz) >= abs(nx) && nz * (0.0 - mz) > 0 else abs(nx) >= abs(nz) && nx * (0.0 - mx) > 0
            if (!ok) continue
            val d = mx * mx + mz * mz
            if (d < bd) { best = e; bd = d }
        }
        return if (best >= 0) best else facingEdge(quad, only)
    }

    // ---------------------------------------------------------------- 幾何小工具
    private fun intersect(l1: Line, l2: Line): DoubleArray? {
        val den = l1.ux * l2.uz - l1.uz * l2.ux
        if (abs(den) < 1e-9) return null
        val dx = l2.cx - l1.cx; val dz = l2.cz - l1.cz
        val a = (dx * l2.uz - dz * l2.ux) / den
        return doubleArrayOf(l1.cx + a * l1.ux, l1.cz + a * l1.uz)
    }

    /** 從 t 沿線往 s 方向走過間隙 ≤ RUN_GAP 的線段，回傳最遠到達的參數。 */
    private fun run(pcs: DoubleArray, t: Double, s: Int): Double {
        var far = t
        val n = pcs.size / 2
        if (s > 0) {
            for (k in 0 until n) {
                val p0 = pcs[2 * k]; val p1 = pcs[2 * k + 1]
                if (p1 <= far) continue
                if (p0 - far > RUN_GAP) break
                far = p1
            }
        } else {
            for (k in n - 1 downTo 0) {
                val p0 = pcs[2 * k]; val p1 = pcs[2 * k + 1]
                if (p0 >= far) continue
                if (far - p1 > RUN_GAP) break
                far = p0
            }
        }
        return far
    }

    /** 端點 e 之後（往 s 方向）1–8 m 內同一條線上還有別段 → 像虛線。 */
    private fun dashAfter(pcs: DoubleArray, e: Double, s: Int): Boolean {
        for (k in 0 until pcs.size / 2) {
            val d = if (s > 0) pcs[2 * k] - e else e - pcs[2 * k + 1]
            if (1.0 <= d && d <= 8.0) return true
        }
        return false
    }
}

// ---------------------------------------------------------------- 多邊形工具（[x0,z0,x1,z1,...]）
private fun sortPairs(a: DoubleArray) {
    // 依 (起點, 終點) 字典序排序成對的值；段數很少，用插入排序
    val n = a.size / 2
    for (i in 1 until n) {
        val x = a[2 * i]; val y = a[2 * i + 1]
        var j = i - 1
        while (j >= 0 && (a[2 * j] > x || (a[2 * j] == x && a[2 * j + 1] > y))) {
            a[2 * j + 2] = a[2 * j]; a[2 * j + 3] = a[2 * j + 1]; j--
        }
        a[2 * j + 2] = x; a[2 * j + 3] = y
    }
}

private fun signedArea(p: DoubleArray): Double {
    var s = 0.0
    val n = p.size / 2
    for (k in 0 until n) {
        val x0 = p[2 * k]; val z0 = p[2 * k + 1]
        val x1 = p[(2 * k + 2) % p.size]; val z1 = p[(2 * k + 3) % p.size]
        s += x0 * z1 - x1 * z0
    }
    return 0.5 * s
}

private fun reverseQuad(q: DoubleArray) {
    val n = q.size / 2
    for (k in 0 until n / 2) {
        val a = 2 * k; val b = 2 * (n - 1 - k)
        val tx = q[a]; val tz = q[a + 1]; q[a] = q[b]; q[a + 1] = q[b + 1]; q[b] = tx; q[b + 1] = tz
    }
}

/** Sutherland–Hodgman：保留 inside(x,z) 的部分；cut 回傳交點。 */
private inline fun clipPoly(
    p: DoubleArray, inside: (Double, Double) -> Boolean, cut: (Double, Double, Double, Double) -> DoubleArray
): DoubleArray {
    val n = p.size / 2
    val out = ArrayList<Double>()
    for (k in 0 until n) {
        val cx = p[2 * k]; val cz = p[2 * k + 1]
        val pk = (k - 1 + n) % n
        val px = p[2 * pk]; val pz = p[2 * pk + 1]
        val ic = inside(cx, cz); val ip = inside(px, pz)
        if (ic) {
            if (!ip) { val q = cut(px, pz, cx, cz); out.add(q[0]); out.add(q[1]) }
            out.add(cx); out.add(cz)
        } else if (ip) {
            val q = cut(px, pz, cx, cz); out.add(q[0]); out.add(q[1])
        }
    }
    return out.toDoubleArray()
}

private fun clipRectArea(poly: DoubleArray, r: DoubleArray): Double {
    val x0 = r[0]; val z0 = r[1]; val x1 = r[2]; val z1 = r[3]
    var p = poly
    p = clipPoly(p, { x, _ -> x >= x0 }) { ax, az, bx, bz -> doubleArrayOf(x0, az + (bz - az) * ((x0 - ax) / (bx - ax))) }
    if (p.isEmpty()) return 0.0
    p = clipPoly(p, { x, _ -> x <= x1 }) { ax, az, bx, bz -> doubleArrayOf(x1, az + (bz - az) * ((x1 - ax) / (bx - ax))) }
    if (p.isEmpty()) return 0.0
    p = clipPoly(p, { _, z -> z >= z0 }) { ax, az, bx, bz -> doubleArrayOf(ax + (bx - ax) * ((z0 - az) / (bz - az)), z0) }
    if (p.isEmpty()) return 0.0
    p = clipPoly(p, { _, z -> z <= z1 }) { ax, az, bx, bz -> doubleArrayOf(ax + (bx - ax) * ((z1 - az) / (bz - az)), z1) }
    return if (p.size >= 6) abs(signedArea(p)) else 0.0
}

/** 凸多邊形交集（clipper 須逆時針）。 */
private fun clipConvex(subject: DoubleArray, clipper: DoubleArray): DoubleArray {
    var p = subject
    val n = clipper.size / 2
    for (k in 0 until n) {
        if (p.isEmpty()) break
        val ax = clipper[2 * k]; val az = clipper[2 * k + 1]
        val ex = clipper[(2 * k + 2) % clipper.size] - ax; val ez = clipper[(2 * k + 3) % clipper.size] - az
        p = clipPoly(p, { x, z -> ex * (z - az) - ez * (x - ax) >= 0 }) { sx, sz, qx, qz ->
            val ds = ex * (sz - az) - ez * (sx - ax)
            val de = ex * (qz - az) - ez * (qx - ax)
            val t = ds / (ds - de)
            doubleArrayOf(sx + (qx - sx) * t, sz + (qz - sz) * t)
        }
    }
    return p
}

private fun reversed(p: DoubleArray): DoubleArray {
    val q = p.copyOf()
    reverseQuad(q)
    return q
}

private fun polyIou(p0: DoubleArray, q0: DoubleArray): Double {
    val p = if (signedArea(p0) < 0) reversed(p0) else p0
    val q = if (signedArea(q0) < 0) reversed(q0) else q0
    val inter = clipConvex(p, q)
    val ia = if (inter.size >= 6) abs(signedArea(inter)) else 0.0
    val u = abs(signedArea(p)) + abs(signedArea(q)) - ia
    return if (u > 1e-9) ia / u else 0.0
}
