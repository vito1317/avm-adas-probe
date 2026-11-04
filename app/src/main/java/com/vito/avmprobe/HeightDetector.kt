package com.vito.avmprobe

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 高於地面的障礙物（車體座標，公尺）：(x, z) 是離車最近那一端的中心，w/l 是該端的寬與長。 */
data class Obstacle(val x: Float, val z: Float, val w: Float, val l: Float, val conf: Float)

/**
 * 不靠模型，只用四顆鏡頭的重疊區找「高於地面的東西」（柱子、牆、路緣、別人的車身）。
 * 純 Kotlin，演算法與門檻跟 tools/height_proto.py 一致（改公式時兩邊一起改，再重產 fixtures）。
 *
 * 原理
 * ----
 * 俯視圖是「把畫面投影到 y = 0 的地面」。地面上同一個點，從兩顆鏡頭投影過去會落在同一個位置；
 * 高於地面 h 的點會沿著「遠離鏡頭」的方向偏 `水平距離 × h / (cy − h)`。鏡頭只裝在 0.30–0.84 m 高，
 * 所以 h = 0.4 m 的東西在 2 m 外就偏了 1.8 m，兩顆鏡頭偏的方向還不一樣。
 *
 * 但是**不能**直接比「地面假設下兩顆鏡頭的差」：拿實際錄到的畫面量過，平坦柏油本身就有
 * 0.2–0.4 m 的殘餘錯位（校正殘差 ＋ 路拱／路面起伏），跟 7–10 cm 高的東西造成的視差同級；
 * 再碰上樹影這種高對比紋理，平地的差值就跟柱子一樣大，整張圖都會報警。
 *
 * 所以改成**垂直方向的平面掃描**：每個地面格子沿鉛直線取幾個高度 h，把 (X, h, Z) 投到兩顆鏡頭比對。
 * 地面格子只有 h = 0 對得起來；柱面、牆面、車身側面在 h > 0 也有實體，所以某個高度會對得更好。
 *
 *     分數 = 代價(h = 0) − min 代價(h > 0)，再除以該處的紋理量；> 門檻 ⇒ 這裡不是地面。
 *
 * 兩個關鍵細節（都是被實際資料打臉之後才加的）：
 * - h > 0 的那幾層要**先過紋理門檻**才能當候選。不然「兩邊都是一片死白」（天空、天花板、
 *   過曝白牆）會算出代價 0 被當成完美吻合，遠處整片誤報。
 * - 掃描高度上限只到 0.8 m：前鏡頭只裝在 0.298 m，更高的點要抬頭看，魚眼 maxDeg 之外就沒了；
 *   而且 h → cy 時地面投影會發散。下限 0.15 m：再低的話視差跟平地殘餘錯位同級。
 *   5 層就夠——量過加到 6 層（多一層 1.0 m）ROC 沒變好，每幀卻要多做 20% 的方框濾波。
 *
 * 能管到的範圍
 * ----------
 * 只有「兩顆以上鏡頭都看得到」的地方算得出來，也就是四個斜角的楔形區；正前／正後／正左／正右
 * 只有一顆鏡頭，這個方法完全管不到（[coverage] 會回報比例）。再扣掉距離門檻，實際可判定的
 * 面積約 65 m²。偵測到的區域是俯視圖上的「拖痕」，物體在拖痕靠車子的那一端，所以回報的
 * (x, z, w, l) 取最近的一帶；實測合成方柱的定位誤差約 1.0–1.5 m（偏外側）。
 *
 * 用法：同一個 instance 重複呼叫（查表會依畫面大小快取），不要多執行緒同時呼叫。
 */
class HeightDetector(private val geo: AvmGeometry) {

    companion object {
        /** 工作網格 = BEV 的 1/4 解析度（0.04 → 0.16 m/格）：300x350 → 75x87，才跑得動 */
        const val STRIDE = 4
        /** 每格每鏡頭取 TAP x TAP 個 tap 平均，抵一點混疊 */
        const val TAP = 2
        /** 掃描高度（m）；HEIGHTS[0] 必須是 0（地面假設） */
        val HEIGHTS = doubleArrayOf(0.0, 0.15, 0.30, 0.50, 0.80)
        /** R+G+B 低於此值 = 魚眼成像圈外的暗角，該 tap 不算數 */
        const val DARK_SUM = 30
        private const val EGO_MARGIN = 0.05    // 跟 AvmGeometry.buildBevLut 一致
        private const val OCC_MARGIN = 0.02    // 自車遮擋判斷用的矩形外擴
        private const val SKIRT = 0.25         // 車身再往外推這麼多才開始找（後照鏡、保桿不是地面）
        private const val MAX_RANGE = 4.2      // 離車身矩形多遠以內才找（m）
        private const val HP_R = 8             // 高通（減局部平均）方框半徑（格）= 2.72 m 視窗
        private const val MATCH_R = 4          // 比對視窗半徑（格）= 1.44 m，要比殘餘錯位大
        private const val TEX_FLOOR = 3.0      // 紋理下限（灰階）
        private const val MIN_DEN = 0.4        // 比對視窗內至少要有幾成格子有效
        private const val SCORE_T = 0.45       // 分數門檻（實測 ROC：真實平坦柏油誤判 < 1 個/張）
        private const val OPEN_R = 1           // 形態學 opening 半徑（格）
        private const val MIN_AREA = 0.20      // 元件最小面積（m²）
        private const val NEAR_BAND = 0.60     // 拖痕離車最近的這一帶當作物體本體（m）
        private const val MIN_CONF = 0.30
        private const val MIN_PAIR_CELLS = 50  // 一對鏡頭的重疊格少於這個就不處理
        private const val EPS_DEN = 1e-9
        private const val HIST_BINS = 256

        /** 直方圖中位數為中心、涵蓋 68% 樣本的半寬（≈ 1σ）。不排序，跟 Python 端同一條式子。 */
        internal fun robustSd(hist: IntArray, n: Int): Double {
            if (n == 0) return 1.0
            var cs = 0
            var med = HIST_BINS - 1
            val half = (n + 1) / 2
            for (k in 0 until HIST_BINS) {
                cs += hist[k]
                if (cs >= half) { med = k; break }
            }
            val need = 0.68 * n
            var acc = hist[med].toDouble()
            var k = 0
            while (acc < need && k < HIST_BINS) {
                k++
                if (med - k >= 0) acc += hist[med - k]
                if (med + k < HIST_BINS) acc += hist[med + k]
            }
            return max((k + 0.5) * (255.0 / HIST_BINS), 1.0)
        }
    }

    // ───── 跟畫面大小綁定、跨呼叫重複使用的查表與緩衝區 ─────
    private var fw = -1
    private var fh = -1
    private var nx = 0
    private var nz = 0
    private var n = 0
    private var cell = 0.0
    private val ncam = geo.cams.size
    private val nh = HEIGHTS.size
    private val ntap = TAP * TAP

    private var gxs = DoubleArray(0)
    private var gzs = DoubleArray(0)
    private var dist = DoubleArray(0)
    private var ego = BooleanArray(0)
    private var idx = IntArray(0)            // [((c*nh + hi)*ntap + t)*n + p] → 原始畫面像素索引，-1 = 無效
    private var vis = BooleanArray(0)        // [(c*nh + hi)*n + p]
    private var ncov = IntArray(0)           // h=0 時有幾顆鏡頭看得到
    private var pairA = IntArray(0)
    private var pairB = IntArray(0)
    private var npair = 0
    // 方框濾波只在「這一組遮罩可能為真」的外接框裡做（見 boxSum）：外擴半徑、夾進網格
    private var camBoxes = IntArray(0)     // [(c*nh + hi)*4 + (j0, j1, i0, i1)]，外擴 HP_R
    private var pairBoxes = IntArray(0)    // [(k*nh + hi)*4 + ...]，外擴 MATCH_R
    private val camBox = IntArray(4)

    private var value = DoubleArray(0)       // [(c*nh + hi)*n + p]
    private var okArr = BooleanArray(0)
    private var hpv = DoubleArray(0)
    private var cost = DoubleArray(0)        // [hi*n + p]
    private var tex = DoubleArray(0)
    private var score = DoubleArray(0)
    private var valid = BooleanArray(0)
    private var mask = BooleanArray(0)
    private var tmpMask = BooleanArray(0)
    private var lab = IntArray(0)
    private var stack = IntArray(0)
    private var bufA = DoubleArray(0)
    private var bufB = DoubleArray(0)
    private var bufC = DoubleArray(0)
    private var bufD = DoubleArray(0)
    private var pairMask = BooleanArray(0)
    private var csBuf = DoubleArray(0)
    private var csBuf2 = DoubleArray(0)
    private val hist = IntArray(HIST_BINS)
    private val out = ArrayList<Obstacle>()
    private var needed = IntArray(0)

    /** 車身外有幾成的格子有 ≥2 顆鏡頭看得到（= 這個方法管得到的比例）。先呼叫過 [detect] 才有值。 */
    fun coverage(): Double {
        if (n == 0) return 0.0
        var tot = 0
        var two = 0
        for (p in 0 until n) {
            if (ego[p]) continue
            tot++
            if (ncov[p] >= 2) two++
        }
        return if (tot == 0) 0.0 else two.toDouble() / tot
    }

    /**
     * [detect] 這一張畫面真正會讀到的像素位置（frame 的線性索引，已排序去重）。
     * 1920x1080 全幀轉 ARGB 是 200 萬次色彩轉換，但這裡只讀得到其中約一成，
     * 所以呼叫端先問這一份清單、只填這些點，就不必為了省時間去降解析度。
     * 只跟校正與畫面大小有關，會快取。
     */
    fun pixelsNeeded(frameW: Int, frameH: Int): IntArray {
        ensure(frameW, frameH)
        return needed
    }

    /** 可判定（兩顆鏡頭都看得到、又在距離範圍內）的面積，m²。 */
    fun decidableArea(): Double {
        var k = 0
        for (p in 0 until n) if (valid[p]) k++
        return k * cell * cell
    }

    /**
     * [frame] 是 Camera2 id "2" 的 2x2 拼接畫面（ARGB，row-major，q0 左上=前、q1 右上=後、
     * q2 左下=左、q3 右下=右）。回傳依信心排序的障礙物。
     */
    fun detect(frame: IntArray, frameW: Int, frameH: Int): List<Obstacle> {
        ensure(frameW, frameH)
        out.clear()
        if (n == 0 || npair == 0) return out
        sample(frame)
        highPass()
        sweep()
        return extract()
    }

    // ───────────────────────────────────────────── 查表（只跟校正與畫面大小有關）

    private fun ensure(frameW: Int, frameH: Int) {
        if (frameW == fw && frameH == fh) return
        fw = frameW
        fh = frameH
        val bev = geo.bev
        cell = bev.mpp * STRIDE
        nx = bev.width / STRIDE
        nz = bev.height / STRIDE
        n = nx * nz
        gxs = DoubleArray(nx) { bev.xMin + (it + 0.5) * cell }
        gzs = DoubleArray(nz) { bev.zMax - (it + 0.5) * cell }
        dist = DoubleArray(n)
        ego = BooleanArray(n)
        val hwE = geo.carWidth / 2 + EGO_MARGIN
        val hlE = geo.carLength / 2 + EGO_MARGIN
        val inRange = BooleanArray(n)
        for (j in 0 until nz) {
            val gz = gzs[j]
            for (i in 0 until nx) {
                val gx = gxs[i]
                val p = j * nx + i
                val dx = max(abs(gx) - geo.carWidth / 2, 0.0)
                val dz = max(abs(gz) - geo.carLength / 2, 0.0)
                val d = hypot(dx, dz)
                dist[p] = d
                ego[p] = abs(gx) <= hwE && abs(gz) <= hlE
                inRange[p] = d >= SKIRT && d <= MAX_RANGE
            }
        }

        idx = IntArray(ncam * nh * ntap * n)
        vis = BooleanArray(ncam * nh * n)
        ncov = IntArray(n)
        val qw = frameW / 2
        val qh = frameH / 2
        val offs = DoubleArray(TAP) { (-0.5 + (it + 0.5) / TAP) * cell }
        val uv = DoubleArray(2)
        val cams = geo.cams.sortedBy { it.quad }
        for (c in cams.indices) {
            val cam = cams[c]
            val qx0 = (cam.quad % 2) * qw
            val qy0 = (cam.quad / 2) * qh
            for (hi in 0 until nh) {
                val hv = HEIGHTS[hi]
                for (j in 0 until nz) {
                    for (i in 0 until nx) {
                        val p = j * nx + i
                        var all = inRange[p] && !egoOccluded(cam, gxs[i], gzs[j])
                        var t = 0
                        for (a in 0 until TAP) {
                            for (b in 0 until TAP) {
                                var v = -1
                                if (all && projectH(cam, gxs[i] + offs[a], hv, gzs[j] + offs[b], qw, qh, uv)) {
                                    v = (qy0 + floor(uv[1]).toInt()) * frameW + qx0 + floor(uv[0]).toInt()
                                } else {
                                    all = false
                                }
                                idx[(((c * nh + hi) * ntap) + t) * n + p] = v
                                t++
                            }
                        }
                        vis[(c * nh + hi) * n + p] = all
                        if (hi == 0 && all) ncov[p]++
                    }
                }
            }
        }

        // 哪幾對鏡頭真的有重疊區（前後、左右彼此沒有，不用算）
        val pa = ArrayList<Int>()
        val pb = ArrayList<Int>()
        for (a in 0 until ncam) for (b in a + 1 until ncam) {
            var k = 0
            for (p in 0 until n) if (vis[a * nh * n + p] && vis[b * nh * n + p]) k++
            if (k >= MIN_PAIR_CELLS) { pa.add(a); pb.add(b) }
        }
        npair = pa.size
        pairA = IntArray(npair) { pa[it] }
        pairB = IntArray(npair) { pb[it] }

        value = DoubleArray(ncam * nh * n)
        okArr = BooleanArray(ncam * nh * n)
        hpv = DoubleArray(ncam * nh * n)
        cost = DoubleArray(nh * n)
        tex = DoubleArray(n)
        score = DoubleArray(n)
        valid = BooleanArray(n)
        mask = BooleanArray(n)
        tmpMask = BooleanArray(n)
        lab = IntArray(n)
        stack = IntArray(n)
        bufA = DoubleArray(n)
        bufB = DoubleArray(n)
        bufC = DoubleArray(n)
        bufD = DoubleArray(n)
        pairMask = BooleanArray(n)
        denScratch = DoubleArray(n)
        csBuf = DoubleArray(max((nz + 1) * nx, nz * (nx + 1)))
        csBuf2 = DoubleArray(csBuf.size)

        camBoxes = IntArray(ncam * nh * 4)
        for (c in 0 until ncam) for (hi in 0 until nh) {
            maskBox(vis, (c * nh + hi) * n, HP_R, camBox)
            System.arraycopy(camBox, 0, camBoxes, (c * nh + hi) * 4, 4)
        }
        pairBoxes = IntArray(max(npair, 1) * nh * 4)
        for (k in 0 until npair) for (hi in 0 until nh) {
            val ah = (pairA[k] * nh + hi) * n
            val bh = (pairB[k] * nh + hi) * n
            for (p in 0 until n) tmpMask[p] = vis[ah + p] && vis[bh + p]
            maskBox(tmpMask, 0, MATCH_R, camBox)
            System.arraycopy(camBox, 0, pairBoxes, (k * nh + hi) * 4, 4)
        }
        java.util.Arrays.fill(tmpMask, false)

        // sample() 會讀到的像素位置：只有 vis 為真的格子才讀，其餘（含 -1）不列入。
        // 排序後去重，呼叫端照這份清單填 ARGB 就夠了。
        var m = 0
        for (k in 0 until ncam * nh) {
            val base = k * n
            val tbase = k * ntap * n
            for (p in 0 until n) {
                if (!vis[base + p]) continue
                for (t in 0 until ntap) if (idx[tbase + t * n + p] >= 0) m++
            }
        }
        val tmp = IntArray(m)
        m = 0
        for (k in 0 until ncam * nh) {
            val base = k * n
            val tbase = k * ntap * n
            for (p in 0 until n) {
                if (!vis[base + p]) continue
                for (t in 0 until ntap) {
                    val v = idx[tbase + t * n + p]
                    if (v >= 0) tmp[m++] = v
                }
            }
        }
        tmp.sort()
        var u = 0
        for (k in tmp.indices) if (k == 0 || tmp[k] != tmp[k - 1]) tmp[u++] = tmp[k]
        needed = tmp.copyOf(u)
    }

    /**
     * 任意高度的點 (gx, gy, gz) → 象限內像素，寫進 [uv]；回傳是否有效。
     * 跟 AvmGeometry.project 同一套等距魚眼模型，差別只在高度不寫死成 0（dY = gy − py）。
     */
    private fun projectH(c: CamParams, gx: Double, gy: Double, gz: Double,
                         qw: Int, qh: Int, uv: DoubleArray): Boolean {
        val m = c.rot
        val dx = gx - c.px
        val dy = gy - c.py
        val dz = gz - c.pz
        val x = m[0] * dx + m[3] * dy + m[6] * dz
        val y = m[1] * dx + m[4] * dy + m[7] * dz
        val z = m[2] * dx + m[5] * dy + m[8] * dz
        val r = sqrt(x * x + y * y)
        val th = atan2(r, z)
        val t2 = th * th
        val rd = th * (1.0 + c.k1 * t2 + c.k2 * t2 * t2)
        val ca: Double
        val sa: Double
        if (r > 0.0) { ca = x / r; sa = y / r } else { ca = 1.0; sa = 0.0 }
        var u = c.cx * qw + c.fx * qw * rd * ca
        val v = c.cy * qh + c.fy * qw * rd * sa
        if (c.mirror) u = qw - u
        uv[0] = u
        uv[1] = v
        return th <= c.maxRad && u >= 0.0 && u < qw && v >= 0.0 && v < qh
    }

    /**
     * 鏡頭 → 地面點的視線（俯視平面上的線段）有沒有穿過自車矩形（slab method）。
     * 鏡頭本身落在矩形內（後鏡頭 z = −2.3825 而半長 2.42）時不算自己擋自己。
     */
    private fun egoOccluded(c: CamParams, gx: Double, gz: Double): Boolean {
        val hw = geo.carWidth / 2 + OCC_MARGIN
        val hl = geo.carLength / 2 + OCC_MARGIN
        if (abs(c.px) <= hw && abs(c.pz) <= hl) return false
        var t0 = 0.0
        var t1 = 1.0
        for (k in 0 until 2) {
            val cc = if (k == 0) c.px else c.pz
            val d = if (k == 0) gx - c.px else gz - c.pz
            val h = if (k == 0) hw else hl
            val lo: Double
            val hiv: Double
            if (abs(d) < 1e-12) {
                val inside = abs(cc) <= h
                lo = if (inside) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
                hiv = if (inside) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY
            } else {
                val a = (-h - cc) / d
                val b = (h - cc) / d
                lo = min(a, b)
                hiv = max(a, b)
            }
            t0 = max(t0, lo)
            t1 = min(t1, hiv)
        }
        return t0 < t1 - 1e-9
    }

    // ───────────────────────────────────────────── 每幀

    /** 每個 (鏡頭, 高度, 格子) 取 TAP² 個 tap 的灰階平均；有 tap 落在暗角（R+G+B < 30）就整格不算。 */
    private fun sample(frame: IntArray) {
        // 只掃 camBoxes 那一塊：框外 vis 一定是 false，後面所有方框濾波也都只在框內讀 okArr
        for (c in 0 until ncam) {
            for (hi in 0 until nh) {
                val k = c * nh + hi
                val base = k * n
                val tbase = k * ntap * n
                val j0 = camBoxes[k * 4]; val j1 = camBoxes[k * 4 + 1]
                val i0 = camBoxes[k * 4 + 2]; val i1 = camBoxes[k * 4 + 3]
                for (j in j0 until j1) {
                    val row = j * nx
                    for (i in i0 until i1) {
                        val p = row + i
                        if (!vis[base + p]) { okArr[base + p] = false; continue }
                        var acc = 0
                        var good = true
                        for (t in 0 until ntap) {
                            val pix = frame[idx[tbase + t * n + p]]
                            val r = (pix shr 16) and 255
                            val g = (pix shr 8) and 255
                            val b = pix and 255
                            if (r + g + b < DARK_SUM) { good = false; break }
                            acc += (77 * r + 150 * g + 29 * b) shr 8
                        }
                        okArr[base + p] = good
                        if (good) value[base + p] = acc.toDouble() / ntap
                    }
                }
            }
        }
    }

    /** 減掉局部平均：吃掉四顆鏡頭的曝光／白平衡／暗角差（偏移在相減時消掉，只剩增益要對）。 */
    private fun highPass() {
        // hpv 不用先清零：它只會在 okArr 為真的格子被讀到，而那些格子每一幀都會重寫
        for (c in 0 until ncam) {
            for (hi in 0 until nh) {
                val k = c * nh + hi
                val base = k * n
                val box = camBox
                System.arraycopy(camBoxes, k * 4, box, 0, 4)
                boxMean(value, base, okArr, base, HP_R, bufA, null, box)
                for (j in box[0] until box[1]) {
                    val row = j * nx
                    for (i in box[2] until box[3]) {
                        val p = row + i
                        if (okArr[base + p]) hpv[base + p] = value[base + p] - bufA[p]
                    }
                }
            }
        }
    }

    /** 平面掃描：每個高度算局部 SAD，h > 0 的要先過紋理門檻才能當候選。 */
    private fun sweep() {
        java.util.Arrays.fill(tex, 0.0)
        for (hi in 0 until nh) java.util.Arrays.fill(cost, hi * n, hi * n + n, Double.POSITIVE_INFINITY)
        val minDen = (2 * MATCH_R + 1) * (2 * MATCH_R + 1) * MIN_DEN
        val box = camBox
        for (k in 0 until npair) {
            val a = pairA[k]
            val b = pairB[k]
            val a0 = a * nh * n
            val b0 = b * nh * n
            java.util.Arrays.fill(pairMask, false)
            var cnt = 0
            System.arraycopy(pairBoxes, (k * nh) * 4, box, 0, 4)
            for (j in box[0] until box[1]) {
                val row = j * nx
                for (i in box[2] until box[3]) {
                    val p = row + i
                    val m = okArr[a0 + p] && okArr[b0 + p]
                    pairMask[p] = m
                    if (m) cnt++
                }
            }
            if (cnt == 0) continue
            val gn = pairGain(a0, b0, box)
            for (hi in 0 until nh) {
                val ah = (a * nh + hi) * n
                val bh = (b * nh + hi) * n
                System.arraycopy(pairBoxes, (k * nh + hi) * 4, box, 0, 4)
                var any = false
                for (j in box[0] until box[1]) {
                    val row = j * nx
                    for (i in box[2] until box[3]) {
                        val p = row + i
                        val m = okArr[ah + p] && okArr[bh + p]
                        tmpMask[p] = m
                        if (m) {
                            bufC[p] = 0.5 * (gn * abs(hpv[ah + p]) + abs(hpv[bh + p]))
                            any = true
                        } else {
                            bufC[p] = 0.0
                        }
                    }
                }
                if (!any) continue
                boxMean(bufC, 0, tmpMask, 0, MATCH_R, bufA, null, box)   // bufA = 局部紋理量
                for (j in box[0] until box[1]) {
                    val row = j * nx
                    for (i in box[2] until box[3]) {
                        val p = row + i
                        if (!tmpMask[p]) continue
                        if (hi == 0) {
                            if (bufA[p] > tex[p]) tex[p] = bufA[p]
                        } else if (bufA[p] < TEX_FLOOR) {
                            tmpMask[p] = false
                            bufC[p] = 0.0
                            continue
                        }
                        bufC[p] = abs(gn * hpv[ah + p] - hpv[bh + p])
                    }
                }
                boxMean(bufC, 0, tmpMask, 0, MATCH_R, bufB, bufD, box)   // bufB = SAD、bufD = 有效格數
                val co = hi * n
                for (j in box[0] until box[1]) {
                    val row = j * nx
                    for (i in box[2] until box[3]) {
                        val p = row + i
                        if (pairMask[p] && tmpMask[p] && bufD[p] > minDen && bufB[p] < cost[co + p]) {
                            cost[co + p] = bufB[p]
                        }
                    }
                }
            }
        }
        for (p in 0 until n) {
            val c0 = cost[p]
            if (c0.isInfinite()) { valid[p] = false; score[p] = 0.0; continue }
            var up = Double.POSITIVE_INFINITY
            for (hi in 1 until nh) {
                val v = cost[hi * n + p]
                if (v < up) up = v
            }
            // 沒有任何 h > 0 過得了紋理門檻 ⇒ 沒有「上面站著東西」的正向證據 ⇒ 分數 0
            if (up.isInfinite()) up = c0
            valid[p] = true
            score[p] = (c0 - up) / max(tex[p], TEX_FLOOR)
        }
    }

    /** 成對鏡頭的全域增益：用重疊區的穩健尺度（直方圖）對齊曝光，再夾在 0.4–2.5 倍。 */
    private fun pairGain(a0: Int, b0: Int, box: IntArray): Double {
        var na = 0
        java.util.Arrays.fill(hist, 0)
        for (j in box[0] until box[1]) {
            val row = j * nx
            for (i in box[2] until box[3]) {
                val p = row + i
                if (pairMask[p]) { hist[bin(value[a0 + p])]++; na++ }
            }
        }
        val sa = robustSd(hist, na)
        java.util.Arrays.fill(hist, 0)
        for (j in box[0] until box[1]) {
            val row = j * nx
            for (i in box[2] until box[3]) {
                val p = row + i
                if (pairMask[p]) hist[bin(value[b0 + p])]++
            }
        }
        val sb = robustSd(hist, na)
        return min(2.5, max(0.4, sb / max(sa, 1.0)))
    }

    private fun bin(v: Double): Int = min(HIST_BINS - 1, max(0, (v / 255.0 * HIST_BINS).toInt()))

    /**
     * 遮罩內的方框平均（邊界截斷，不補零），寫進 [outMean]；[outDen] 不是 null 就一併回傳有效格數。
     * 先沿 j（直）再沿 i（橫）做前綴和 —— 跟 Python 端的 `_box` 同一個加總順序，數值才會完全一樣；
     * 分子與分母共用同一趟掃描（兩組累加器），省掉一半的迴圈與索引計算。
     *
     * 只在 [box]（已經外擴 r 並夾進網格的 [j0, j1) x [i0, i1)）裡算，框外的值是上次留下的垃圾，
     * 呼叫端只能讀遮罩內的格子。只算框內跟算整張**逐位元相同**：框外的格子都在遮罩外、貢獻剛好
     * 是 0.0，而 x + 0.0 == x，所以前綴和從框的邊界起算跟從 0 起算差值一樣；邊界截斷也因為框
     * 外擴了 r 而不受影響。
     */
    private fun boxMean(src: DoubleArray, srcOff: Int, msk: BooleanArray, mskOff: Int,
                        r: Int, outMean: DoubleArray, outDen: DoubleArray?, box: IntArray) {
        val den = outDen ?: denScratch
        val j0 = box[0]; val j1 = box[1]; val i0 = box[2]; val i1 = box[3]
        if (j1 <= j0 || i1 <= i0) return
        val lw = i1 - i0
        // ── 直向前綴和（csBuf = 值、csBuf2 = 格數；第 0 列都是 0）──
        for (i in 0 until lw) { csBuf[i] = 0.0; csBuf2[i] = 0.0 }
        for (j in j0 until j1) {
            val row = j * nx + i0
            val prev = (j - j0) * lw
            val cur = prev + lw
            for (i in 0 until lw) {
                val p = row + i
                if (msk[mskOff + p]) {
                    csBuf[cur + i] = csBuf[prev + i] + src[srcOff + p]
                    csBuf2[cur + i] = csBuf2[prev + i] + 1.0
                } else {
                    csBuf[cur + i] = csBuf[prev + i]
                    csBuf2[cur + i] = csBuf2[prev + i]
                }
            }
        }
        for (j in j0 until j1) {
            val lo = (max(j - r, j0) - j0) * lw
            val hi = (min(j + r + 1, j1) - j0) * lw
            val row = j * nx + i0
            for (i in 0 until lw) {
                outMean[row + i] = csBuf[hi + i] - csBuf[lo + i]
                den[row + i] = csBuf2[hi + i] - csBuf2[lo + i]
            }
        }
        // ── 橫向前綴和（就地在 csBuf 上做，每列寬 lw + 1）──
        val w1 = lw + 1
        for (j in j0 until j1) {
            val cs = (j - j0) * w1
            val row = j * nx + i0
            csBuf[cs] = 0.0
            csBuf2[cs] = 0.0
            for (i in 0 until lw) {
                csBuf[cs + i + 1] = csBuf[cs + i] + outMean[row + i]
                csBuf2[cs + i + 1] = csBuf2[cs + i] + den[row + i]
            }
        }
        for (j in j0 until j1) {
            val cs = (j - j0) * w1
            val row = j * nx + i0
            for (i in 0 until lw) {
                val lo = cs + max(i - r, 0)
                val hi = cs + min(i + r + 1, lw)
                val d = csBuf2[hi] - csBuf2[lo]
                den[row + i] = d
                outMean[row + i] = (csBuf[hi] - csBuf[lo]) / max(d, EPS_DEN)
            }
        }
    }

    private var denScratch = DoubleArray(0)

    /** 整張網格的方框和（給形態學用；邊界截斷，不補零）。 */
    private fun boxSum(src: DoubleArray, r: Int, dst: DoubleArray) {
        for (i in 0 until nx) csBuf[i] = 0.0
        for (j in 0 until nz) {
            val row = j * nx
            for (i in 0 until nx) csBuf[row + nx + i] = csBuf[row + i] + src[row + i]
        }
        for (j in 0 until nz) {
            val lo = max(j - r, 0) * nx
            val hi = min(j + r + 1, nz) * nx
            val row = j * nx
            for (i in 0 until nx) dst[row + i] = csBuf[hi + i] - csBuf[lo + i]
        }
        val w1 = nx + 1
        for (j in 0 until nz) {
            val cs = j * w1
            val row = j * nx
            csBuf[cs] = 0.0
            for (i in 0 until nx) csBuf[cs + i + 1] = csBuf[cs + i] + dst[row + i]
        }
        for (j in 0 until nz) {
            val cs = j * w1
            val row = j * nx
            for (i in 0 until nx) {
                dst[row + i] = csBuf[cs + min(i + r + 1, nx)] - csBuf[cs + max(i - r, 0)]
            }
        }
    }

    /** 遮罩的外接框往外擴 r、夾進網格；空遮罩回傳空框。 */
    private fun maskBox(msk: BooleanArray, off: Int, r: Int, box: IntArray) {
        var j0 = nz; var j1 = -1; var i0 = nx; var i1 = -1
        for (j in 0 until nz) {
            val row = j * nx
            for (i in 0 until nx) {
                if (!msk[off + row + i]) continue
                if (j < j0) j0 = j
                if (j > j1) j1 = j
                if (i < i0) i0 = i
                if (i > i1) i1 = i
            }
        }
        if (j1 < 0) { box[0] = 0; box[1] = 0; box[2] = 0; box[3] = 0; return }
        box[0] = max(j0 - r, 0); box[1] = min(j1 + r + 1, nz)
        box[2] = max(i0 - r, 0); box[3] = min(i1 + r + 1, nx)
    }

    // ───────────────────────────────────────────── 二值化 → 元件 → 障礙物

    private fun extract(): List<Obstacle> {
        for (p in 0 until n) mask[p] = valid[p] && score[p] > SCORE_T
        morph(OPEN_R, false)
        morph(OPEN_R, true)
        for (p in 0 until n) mask[p] = mask[p] && valid[p]

        // ── 8 連通標記；元件編號依「第一個遇到的格子」的 row-major 順序，跟 Python 端一樣 ──
        java.util.Arrays.fill(lab, 0)
        var nc = 0
        for (p0 in 0 until n) {
            if (!mask[p0] || lab[p0] != 0) continue
            nc++
            var sp = 0
            stack[sp++] = p0
            lab[p0] = nc
            while (sp > 0) {
                val p = stack[--sp]
                val i = p % nx
                val j = p / nx
                var jj = max(j - 1, 0)
                while (jj <= min(j + 1, nz - 1)) {
                    var ii = max(i - 1, 0)
                    while (ii <= min(i + 1, nx - 1)) {
                        val q = jj * nx + ii
                        if (mask[q] && lab[q] == 0) {
                            lab[q] = nc
                            stack[sp++] = q
                        }
                        ii++
                    }
                    jj++
                }
            }
        }
        if (nc == 0) return out
        ensureComps(nc + 1)
        for (k in 1..nc) { cCnt[k] = 0; cSum[k] = 0.0; cDmin[k] = Double.MAX_VALUE }
        // 統計一律用 row-major 掃過去，浮點加總順序才跟 Python 的 sc[sel] 一致
        for (p in 0 until n) {
            val k = lab[p]
            if (k == 0) continue
            cCnt[k]++
            cSum[k] += score[p]
            if (dist[p] < cDmin[k]) cDmin[k] = dist[p]
        }
        for (k in 1..nc) { cX0[k] = Double.MAX_VALUE; cX1[k] = -Double.MAX_VALUE
                           cZ0[k] = Double.MAX_VALUE; cZ1[k] = -Double.MAX_VALUE }
        for (p in 0 until n) {
            val k = lab[p]
            if (k == 0 || dist[p] > cDmin[k] + NEAR_BAND) continue
            val gx = gxs[p % nx]
            val gz = gzs[p / nx]
            if (gx < cX0[k]) cX0[k] = gx
            if (gx > cX1[k]) cX1[k] = gx
            if (gz < cZ0[k]) cZ0[k] = gz
            if (gz > cZ1[k]) cZ1[k] = gz
        }
        val minCells = MIN_AREA / (cell * cell)
        for (k in 1..nc) {
            if (cCnt[k] < minCells) continue
            val conf = min(1.0, max(0.0, (cSum[k] / cCnt[k] / SCORE_T - 1.0) * 0.9 + 0.35))
            if (conf < MIN_CONF) continue
            out.add(Obstacle(
                ((cX0[k] + cX1[k]) / 2).toFloat(), ((cZ0[k] + cZ1[k]) / 2).toFloat(),
                (cX1[k] - cX0[k] + cell).toFloat(), (cZ1[k] - cZ0[k] + cell).toFloat(),
                conf.toFloat()
            ))
        }
        out.sortByDescending { it.conf }
        return out
    }

    private var cCap = 0
    private var cCnt = IntArray(0)
    private var cSum = DoubleArray(0)
    private var cDmin = DoubleArray(0)
    private var cX0 = DoubleArray(0)
    private var cX1 = DoubleArray(0)
    private var cZ0 = DoubleArray(0)
    private var cZ1 = DoubleArray(0)

    private fun ensureComps(need: Int) {
        if (need <= cCap) return
        cCap = max(need, cCap * 2 + 32)
        cCnt = IntArray(cCap); cSum = DoubleArray(cCap); cDmin = DoubleArray(cCap)
        cX0 = DoubleArray(cCap); cX1 = DoubleArray(cCap)
        cZ0 = DoubleArray(cCap); cZ1 = DoubleArray(cCap)
    }

    /** 方框結構元的 erosion（grow = false）/ dilation（grow = true），就地改 [mask]。 */
    private fun morph(r: Int, grow: Boolean) {
        if (r <= 0) return
        for (p in 0 until n) bufC[p] = if (mask[p]) 1.0 else 0.0
        boxSum(bufC, r, bufA)
        for (j in 0 until nz) {
            // 視窗內的格子數（邊界會被截短），直接算，不用再跑一次方框和
            val wj = min(j + r + 1, nz) - max(j - r, 0)
            val row = j * nx
            for (i in 0 until nx) {
                val sz = wj * (min(i + r + 1, nx) - max(i - r, 0))
                mask[row + i] = if (grow) bufA[row + i] > 0.5 else bufA[row + i] >= sz - 0.5
            }
        }
    }
}
