package com.vito.avmprobe

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 自動對位：拿原廠 360 畫出來的畫面，跟我們用同一份相機影格算的俯視圖比對，
 * 找出原廠俯視圖的比例／位置（[AnnotationMapper.Params]）和 3D 視角的虛擬鏡頭（[PerspectiveMapper.Params]）。
 *
 * 原廠的投影參數拿不到（原生程式＋車機上的 Lua 設定），但它畫的就是同一批鏡頭拍到的地面，
 * 所以「把我們的地面紋理投影上去、跟原廠畫面最像」的那組參數就是答案。
 *
 * 關鍵：兩邊要用**完全一樣的處理**比。每組候選參數都先把我們的地面「畫成原廠那個比例的螢幕影像」，
 * 再跟原廠畫面做同樣的去低頻（扣局部平均）。不然一邊是柏油顆粒、一邊是半公尺大的結構，對不起來。
 * 自車那塊不比（原廠畫的是車模），分數是正規化相關係數（NCC，-1～1）。
 * 純運算、不碰 Android，可以在電腦上用合成畫面驗證。
 */
object AutoCalibrator {

    /** 我們的俯視圖：ARGB、每像素 [mpp] 公尺、自車中心在 ([ox], [oy]) 像素。 */
    class Bev(val argb: IntArray, val w: Int, val h: Int, val mpp: Float, val ox: Float, val oy: Float) {
        /** 亮度，黑（看不到）= NaN。 */
        val lum: FloatArray = FloatArray(w * h) { i ->
            val c = argb[i]
            val l = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
            if (l < 4f) Float.NaN else l
        }
        private val integ = Integral(lum, w, h)

        /** 地面點 (x, z) 附近邊長 [m] 公尺的平均亮度（[m] ≈ 一個螢幕像素蓋到的地面大小）；看不到 → NaN。 */
        fun area(x: Float, z: Float, m: Float): Float {
            val r = max(0.5f, m / mpp / 2f)
            val cx = ox + x / mpp; val cy = oy - z / mpp
            return integ.mean((cx - r).toInt(), (cy - r).toInt(), ceil(cx + r).toInt(), ceil(cy + r).toInt(), minFrac = 0.5f)
        }
    }

    data class TopResult(val params: AnnotationMapper.Params, val score: Float)
    data class P3Result(val params: PerspectiveMapper.Params, val score: Float)

    /** 原廠畫面（[argb]，[w]×[h]）在螢幕上的位置：左上 ([sx0], [sy0])、大小 [sw]×[sh]，螢幕 [screenW]×[screenH]。 */
    class Screen(
        val argb: IntArray, val w: Int, val h: Int,
        val sx0: Int, val sy0: Int, val sw: Int, val sh: Int,
        val screenW: Int, val screenH: Int
    )

    private const val EGO_HW = 1.15f   // 自車外框再放一點（原廠車模比實車寬一點）
    private const val EGO_HL = 2.7f
    private const val BEV_X = 5.5f     // 只比自車附近的地面（遠處原廠是碗狀曲面，不是平面）
    private const val BEV_Z = 6.5f
    private const val HP_R = 3         // 去低頻的半徑（縮圖像素）

    /** 粗掃的看向點。遠的鏡頭移一點看向點差不多；近的鏡頭（側邊／倒車鏡頭展開）差 1 m 畫面就整個跑掉，要掃細一點，含車身兩側。 */
    private val FAR_TARGETS = listOf(0f to 0f, -2f to 2f, 2f to 2f, 0f to 3f, 0f to -3f)
    private val NEAR_TARGETS = listOf(
        0f to 0f, -2f to 0f, 2f to 0f, -2f to 1.5f, 2f to 1.5f, -2f to -1.5f, 2f to -1.5f, 0f to 3.5f, 0f to -3.5f
    )

    // ───────────────────────── 影像工具 ─────────────────────────

    /** 可忽略 NaN 的積分影像：任意矩形的平均 O(1)。 */
    class Integral(v: FloatArray, private val w: Int, private val h: Int) {
        private val s = DoubleArray((w + 1) * (h + 1))
        private val n = IntArray((w + 1) * (h + 1))
        init {
            for (y in 0 until h) {
                var rs = 0.0; var rn = 0
                for (x in 0 until w) {
                    val a = v[y * w + x]
                    if (!a.isNaN()) { rs += a; rn++ }
                    val k = (y + 1) * (w + 1) + x + 1
                    s[k] = s[k - (w + 1)] + rs; n[k] = n[k - (w + 1)] + rn
                }
            }
        }
        /** [x0, x1) × [y0, y1) 的平均；有效像素少於 [minFrac] → NaN。 */
        fun mean(x0: Int, y0: Int, x1: Int, y1: Int, minFrac: Float = 0.3f): Float {
            val a = x0.coerceIn(0, w); val b = x1.coerceIn(0, w); val c = y0.coerceIn(0, h); val d = y1.coerceIn(0, h)
            if (b <= a || d <= c) return Float.NaN
            val W = w + 1
            val cnt = n[d * W + b] - n[c * W + b] - n[d * W + a] + n[c * W + a]
            if (cnt < (b - a) * (d - c) * minFrac || cnt == 0) return Float.NaN
            return ((s[d * W + b] - s[c * W + b] - s[d * W + a] + s[c * W + a]) / cnt).toFloat()
        }
    }

    /** 局部平均（半徑 [r]），NaN 保持 NaN。 */
    fun boxBlur(v: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        if (r <= 0) return v
        val ig = Integral(v, w, h)
        return FloatArray(v.size) { i -> if (v[i].isNaN()) Float.NaN else ig.mean(i % w - r, i / w - r, i % w + r + 1, i / w + r + 1, 0.3f) }
    }

    /**
     * 比對用的特徵：先模糊、再扣掉大範圍平均 → 只留下約 1 公尺大小的結構（停車格線、草地邊界、路緣）。
     * 實車驗證：原廠畫面經過它自己的融合／模糊／色彩處理，細紋理（柏油、草的顆粒）跟我們的完全不同，
     * 用細紋理比，正確位置的相關只有 0.14、比錯的位置還低；用這個頻帶是 0.80，全域搜尋也找得到。
     * 半徑跟著解析度變（[w] 是影像寬）：模糊 w/80、去低頻 w/16。
     */
    fun bandFeature(v: FloatArray, w: Int, h: Int, refW: Int = w): FloatArray =
        highpass(boxBlur(v, w, h, max(1, refW / 80)), w, h, max(3, refW / 16))

    /** 俯視圖比對的頻帶，用**公尺**定：模糊約 [BAND_BLUR_M]、去掉大於約 [BAND_HP_M] 的結構；換成這個比例下的像素半徑。 */
    private const val BAND_BLUR_M = 0.12f
    private const val BAND_HP_M = 0.6f
    private fun bandRadii(ppm: Float) = max(1, (BAND_BLUR_M * ppm).roundToInt()) to max(3, (BAND_HP_M * ppm).roundToInt())

    /** 扣掉局部平均（半徑 [r]），NaN 保持 NaN。 */
    fun highpass(v: FloatArray, w: Int, h: Int, r: Int = HP_R): FloatArray {
        val ig = Integral(v, w, h)
        return FloatArray(w * h) { i ->
            val a = v[i]
            if (a.isNaN()) Float.NaN else {
                val x = i % w; val y = i / w
                val m = ig.mean(x - r, y - r, x + r + 1, y + r + 1, minFrac = 0.2f)
                if (m.isNaN()) Float.NaN else a - m
            }
        }
    }

    /**
     * 「邊緣在哪」：去低頻後取絕對值再模糊。比紋理本身平滑很多 —— 差一點點還是很像，
     * 粗搜用它才掃得到；紋理（[highpass]）的峰很尖，只適合細修。
     */
    fun edgeEnergy(hp: FloatArray, w: Int, h: Int, r: Int = 2): FloatArray {
        val a = FloatArray(hp.size) { if (hp[it].isNaN()) Float.NaN else abs(hp[it]) }
        val ig = Integral(a, w, h)
        return FloatArray(a.size) { i ->
            if (a[i].isNaN()) Float.NaN else ig.mean(i % w - r, i / w - r, i % w + r + 1, i / w + r + 1, minFrac = 0.3f)
        }
    }

    /** ARGB 縮到寬 [outW] 的亮度（區塊平均）；大半是黑的區塊 = NaN。回傳 (亮度, 高)。 */
    fun shrink(argb: IntArray, w: Int, h: Int, outW: Int): Pair<FloatArray, Int> {
        val k = w.toFloat() / outW
        val outH = max(1, (h / k).toInt())
        val out = FloatArray(outW * outH)
        for (y in 0 until outH) for (x in 0 until outW) {
            val x0 = (x * k).toInt(); val x1 = min(w, max(x0 + 1, ((x + 1) * k).toInt()))
            val y0 = (y * k).toInt(); val y1 = min(h, max(y0 + 1, ((y + 1) * k).toInt()))
            var s = 0f; var n = 0; var dark = 0
            for (yy in y0 until y1) for (xx in x0 until x1) {
                val c = argb[yy * w + xx]
                val l = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
                if (l < 4f) dark++
                s += l; n++
            }
            out[y * outW + x] = if (dark * 2 > n) Float.NaN else s / n
        }
        return out to outH
    }

    /** NCC：a 跟 b 對應位置，任一邊 NaN 略過；有效數少於 [minN] → -1。 */
    private fun ncc(a: FloatArray, b: FloatArray, n: Int, minN: Int): Float {
        var sa = 0.0; var sb = 0.0; var saa = 0.0; var sbb = 0.0; var sab = 0.0; var c = 0
        for (i in 0 until n) {
            val x = a[i]; val y = b[i]
            if (x.isNaN() || y.isNaN()) continue
            sa += x; sb += y; saa += x * x; sbb += y * y; sab += x * y; c++
        }
        if (c < minN) return -1f
        val cov = sab - sa * sb / c; val va = saa - sa * sa / c; val vb = sbb - sb * sb / c
        if (va <= 1e-6 || vb <= 1e-6) return -1f
        return (cov / sqrt(va * vb)).toFloat()
    }

    // ───────────────────────── 俯視圖 ─────────────────────────

    /** 我們的地面畫成「每公尺 [ppm] 像素」的模板（中心 = 自車），去低頻；自車與看不到的 = NaN。 */
    private class Template(val w: Int, val h: Int, val v: FloatArray, val idx: IntArray)

    /**
     * [refW] 是比對影像（原廠畫面縮圖）的寬：模板的模糊／去低頻半徑要跟它一樣（同一個像素尺度），
     * 兩邊才是同一個頻帶。
     */
    private fun template(bev: Bev, ppm: Float, flip: Boolean): Template? {
        val tw = ceil(2 * BEV_X * ppm).toInt(); val th = ceil(2 * BEV_Z * ppm).toInt()
        if (tw < 8 || th < 8) return null
        val sg = if (flip) -1f else 1f
        val raw = FloatArray(tw * th)
        for (j in 0 until th) for (i in 0 until tw) {
            val x = sg * (i + 0.5f - tw / 2f) / ppm
            val z = -sg * (j + 0.5f - th / 2f) / ppm
            raw[j * tw + i] = if (abs(x) < EGO_HW && abs(z) < EGO_HL) Float.NaN else bev.area(x, z, 1f / ppm)
        }
        val (rb, rh) = bandRadii(ppm)
        val hp = highpass(boxBlur(raw, tw, th, rb), tw, th, rh)
        // 取樣：最多 ~1500 個有效點，免得大比例時太慢
        val valid = hp.indices.filter { !hp[it].isNaN() }
        if (valid.size < 60) return null
        val stride = max(1, valid.size / 1500)
        val idx = IntArray((valid.size + stride - 1) / stride) { valid[it * stride] }
        return Template(tw, th, hp, idx)
    }

    /** 模板中心放在螢幕影像 ([cx], [cy]) 時的 NCC。 */
    private fun scoreAt(t: Template, s: FloatArray, sw: Int, sh: Int, cx: Int, cy: Int, buf: FloatArray, tv: FloatArray): Float {
        val ox = cx - t.w / 2; val oy = cy - t.h / 2
        for (k in t.idx.indices) {
            val i = t.idx[k]
            val x = ox + i % t.w; val y = oy + i / t.w
            buf[k] = if (x < 0 || y < 0 || x >= sw || y >= sh) Float.NaN else s[y * sw + x]
            tv[k] = t.v[i]
        }
        // 原廠俯視格常常只顯示自車附近一小塊 → 模板超出畫面是正常的，只要求夠多點落在畫面裡
        return ncc(tv, buf, t.idx.size, max(400, t.idx.size / 8))
    }

    /**
     * 在原廠畫面裡找俯視圖：比例（每公尺幾像素）、自車中心位置、是否車頭朝下。
     * 先在寬 160 的縮圖上粗搜，再在寬 320 上細修。分數太低（找不到俯視圖）回 null。
     */
    fun calibrateTop(scr: Screen, bev: Bev, minScore: Float = 0.45f): TopResult? {
        data class Best(val ppm: Float, val cx: Int, val cy: Int, val flip: Boolean, val s: Float)
        fun search(outW: Int, ppms: List<Float>, flips: List<Boolean>, cxs: IntProgression?, cys: IntProgression?): Pair<List<Best>, Pair<Int, Int>> {
            val (lum, sh) = shrink(scr.argb, scr.w, scr.h, outW)
            // 原廠畫面的特徵依比例（頻帶半徑）快取：同一個公尺頻帶在不同比例下是不同的像素半徑
            val feats = HashMap<Pair<Int, Int>, FloatArray>()
            val all = ArrayList<Best>()
            for (flip in flips) for (ppm in ppms) {
                val rr = bandRadii(ppm)
                val hp = feats.getOrPut(rr) { highpass(boxBlur(lum, outW, sh, rr.first), outW, sh, rr.second) }
                val t = template(bev, ppm, flip) ?: continue
                val buf = FloatArray(t.idx.size); val tv = FloatArray(t.idx.size)
                val xs = cxs ?: (0 until outW step 3); val ys = cys ?: (0 until sh step 3)
                for (cy in ys) for (cx in xs) {
                    val s = scoreAt(t, hp, outW, sh, cx, cy, buf, tv)
                    if (s > 0f) all.add(Best(ppm, cx, cy, flip, s))
                }
            }
            return all to (outW to sh)
        }
        // 粗搜：每公尺佔畫面高度 3%～40%；留前幾個「彼此不同」的候選（位置差 4 px 以上、或比例差一成、或翻轉不同）
        // 粗搜解析度：原廠畫面寬的 1/8（1920 → 240）。再粗的話整個螢幕裡車只剩二十幾個像素，
        // 右邊 3D 畫面的某些地方會被當成俯視圖（實車截圖就是這樣錯的）
        val cW = (scr.w / 8).coerceIn(160, 320); val fW = cW * 2
        val (_, coarseH) = shrink(scr.argb, scr.w, scr.h, cW)
        val ppmMin = 0.03f * coarseH; val ppmMax = 0.40f * coarseH
        val ppms = (0 until 16).map { ppmMin * exp(ln(ppmMax / ppmMin) * it / 15f) }
        val (all, _) = search(cW, ppms, listOf(false, true), null, null)
        val picks = ArrayList<Best>()
        for (c in all.sortedByDescending { it.s }) {
            if (picks.none { it.flip == c.flip && abs(it.cx - c.cx) < 4 && abs(it.cy - c.cy) < 4 && abs(ln(it.ppm / c.ppm)) < 0.1f }) picks.add(c)
            if (picks.size >= 10) break
        }
        if (picks.isEmpty()) return null
        // 細修：寬 320，在每個候選附近
        var bestFine: Best? = null; var size = 0 to 0
        for (coarse in picks) {
            val fppms = (-6..6).map { coarse.ppm * 2f * (1f + 0.025f * it) }
            val (f, sz) = search(fW, fppms, listOf(coarse.flip), (coarse.cx * 2 - 6)..(coarse.cx * 2 + 6), (coarse.cy * 2 - 6)..(coarse.cy * 2 + 6))
            val top = f.maxByOrNull { it.s } ?: continue
            if (bestFine == null || top.s > bestFine.s) { bestFine = top; size = sz }
        }
        val b = bestFine ?: return null
        if (b.s < minScore) return null
        val (fw, fh) = size
        val kx = scr.sw.toFloat() / fw; val ky = scr.sh.toFloat() / fh
        return TopResult(
            AnnotationMapper.Params(
                clipL = scr.sx0.toFloat() / scr.screenW, clipT = scr.sy0.toFloat() / scr.screenH,
                clipR = (scr.sx0 + scr.sw).toFloat() / scr.screenW, clipB = (scr.sy0 + scr.sh).toFloat() / scr.screenH,
                cx = (scr.sx0 + (b.cx + 0.5f) * kx) / scr.screenW,
                cy = (scr.sy0 + (b.cy + 0.5f) * ky) / scr.screenH,
                scale = b.ppm * ky / scr.screenH, flip = b.flip
            ),
            b.s
        )
    }

    /**
     * 俯視圖那一格的範圍（螢幕比例 l, t, r, b）：用找到的俯視圖參數畫出「預測畫面」，
     * 一條一條直條比對原廠畫面，從自車中心往兩邊延伸到對不上為止，再對齊到原廠常用的分割比例。
     */
    fun topPane(scr: Screen, top: AnnotationMapper.Params, bev: Bev, strips: Int = 32): FloatArray {
        val outW = 320
        val (lum, oh) = shrink(scr.argb, scr.w, scr.h, outW)
        val m = AnnotationMapper(top, scr.screenW, scr.screenH)
        val ppmThumb = m.pixelsPerMeter * outW / scr.sw
        val (rb, rh) = bandRadii(ppmThumb)
        val hp = highpass(boxBlur(lum, outW, oh, rb), outW, oh, rh)
        val sg = if (top.flip) -1f else 1f
        val kx = scr.sw.toFloat() / outW; val ky = scr.sh.toFloat() / oh
        val foot = kx / m.pixelsPerMeter
        val pred = FloatArray(outW * oh) { i ->
            val sx = scr.sx0 + (i % outW + 0.5f) * kx; val sy = scr.sy0 + (i / outW + 0.5f) * ky
            val x = (sx - m.sx(0f, 0f)) / (sg * m.pixelsPerMeter)
            val z = -(sy - m.sy(0f, 0f)) / (sg * m.pixelsPerMeter)
            if (abs(x) < EGO_HW && abs(z) < EGO_HL) Float.NaN else bev.area(x, z, foot)
        }
        val php = highpass(boxBlur(pred, outW, oh, rb), outW, oh, rh)
        val sw = outW / strips
        val sc = FloatArray(strips) { k ->
            val a = FloatArray(sw * oh); val b = FloatArray(sw * oh)
            for (y in 0 until oh) for (x in 0 until sw) { a[y * sw + x] = hp[y * outW + k * sw + x]; b[y * sw + x] = php[y * outW + k * sw + x] }
            ncc(a, b, a.size, a.size / 5)
        }
        val center = (((m.sx(0f, 0f) - scr.sx0) / kx) / sw).toInt().coerceIn(0, strips - 1)
        var l = center; var r = center
        while (l > 0 && sc[l - 1] > 0.2f) l--
        while (r < strips - 1 && sc[r + 1] > 0.2f) r++
        // 換成原廠畫面比例，對齊到常見分割（viewport.lua：0.3、0.325、0.382、0.5、0.618、0.675、0.7）
        val snaps = floatArrayOf(0f, 0.3f, 0.325f, 0.382f, 0.5f, 0.618f, 0.675f, 0.7f, 1f)
        fun snap(f: Float) = snaps.minByOrNull { abs(it - f) }!!.let { if (abs(it - f) < 0.06f) it else f }
        val fl = snap(l.toFloat() / strips); val fr = snap((r + 1).toFloat() / strips)
        return floatArrayOf(
            (scr.sx0 + fl * scr.sw) / scr.screenW, scr.sy0.toFloat() / scr.screenH,
            (scr.sx0 + fr * scr.sw) / scr.screenW, (scr.sy0 + scr.sh).toFloat() / scr.screenH
        )
    }

    // ───────────────────────── 3D 視角 ─────────────────────────

    /**
     * 在原廠畫面的 [pane]（螢幕比例 l, t, r, b）裡找 3D 虛擬鏡頭。
     * 每組候選參數：把格子裡每個縮圖像素反投影到地面、取我們地面的亮度（取樣範圍跟著該像素蓋到的
     * 地面大小變），畫成「預測的原廠畫面」，跟原廠畫面做同樣的去低頻再算 NCC。
     * 先粗掃方位角／俯角／距離／視角／看向點，再對前幾名做 Nelder–Mead。
     */
    /**
     * 實車：原廠 3D 是貼在碗狀曲面上、車附近被車模蓋住，能比的平地很少，真實截圖上分數只有 0.2～0.3、
     * 也挑不出對的。門檻設 0.45：合成畫面（平地）能過，實車對不準的不會被套用。
     */
    fun calibrate3d(scr: Screen, pane: FloatArray, bev: Bev, minScore: Float = 0.45f): P3Result? {
        // 先在低解析度（高 36）上找：峰比較寬、容易掃到；再到高 72 細修
        val coarse = scorer3d(scr, pane, bev, 36, edges = true) ?: return null
        val fine = scorer3d(scr, pane, bev, 72) ?: return null
        return search3d(pane, coarse, fine)?.takeIf { it.score >= minScore }
    }

    /** 3D 的評分函式（給定鏡頭參數 → NCC）。格子太小／沒內容回 null。測試也用它檢查「真值分數最高」。 */
    fun scorer3d(scr: Screen, pane: FloatArray, bev: Bev, height: Int = 72, edges: Boolean = false): ((PerspectiveMapper.Params) -> Float)? {
        // 格子範圍（原廠畫面像素）→ 縮成高 72 左右
        val px0 = ((pane[0] * scr.screenW - scr.sx0) * scr.w / scr.sw).toInt().coerceIn(0, scr.w - 1)
        val px1 = ((pane[2] * scr.screenW - scr.sx0) * scr.w / scr.sw).toInt().coerceIn(px0 + 1, scr.w)
        val py0 = ((pane[1] * scr.screenH - scr.sy0) * scr.h / scr.sh).toInt().coerceIn(0, scr.h - 1)
        val py1 = ((pane[3] * scr.screenH - scr.sy0) * scr.h / scr.sh).toInt().coerceIn(py0 + 1, scr.h)
        val cw = px1 - px0; val ch = py1 - py0
        val crop = IntArray(cw * ch) { scr.argb[(py0 + it / cw) * scr.w + px0 + it % cw] }
        val outW = max(16, (cw * height.toFloat() / ch).toInt().coerceAtMost(height * 3))
        val (lum, oh) = shrink(crop, cw, ch, outW)
        // 頻帶：先模糊 1 px、去掉大於約 1/10 格高的結構（實車驗證：細紋理在原廠畫面上對不起來）
        val hpR = max(3, height / 10)
        val shp0 = highpass(boxBlur(lum, outW, oh, 1), outW, oh, hpR)
        if (shp0.count { !it.isNaN() } < 150) return null
        val shp = if (edges) edgeEnergy(shp0, outW, oh) else shp0
        // 每個縮圖像素中心 → 螢幕像素
        val kx = (cw.toFloat() / outW) * scr.sw / scr.w; val ky = (ch.toFloat() / oh) * scr.sh / scr.h
        val bx0 = scr.sx0 + px0 * scr.sw.toFloat() / scr.w; val by0 = scr.sy0 + py0 * scr.sh.toFloat() / scr.h
        val pred = FloatArray(outW * oh)

        fun score(p: PerspectiveMapper.Params): Float {
            if (p.elDeg < 3f || p.elDeg > 89f || p.dist < 1f || p.dist > 40f || p.fovDeg < 15f || p.fovDeg > 115f) return -2f   // 原廠最廣的鏡頭畫面也才 99°；再廣是退化解
            val m = PerspectiveMapper(p, scr.screenW, scr.screenH)
            var used = 0
            for (y in 0 until oh) for (x in 0 until outW) {
                val sx = bx0 + (x + 0.5f) * kx; val sy = by0 + (y + 0.5f) * ky
                val g = m.unprojectToGround(sx, sy)
                pred[y * outW + x] = if (g == null || abs(g[0]) > BEV_X || abs(g[1]) > BEV_Z ||
                    (abs(g[0]) < EGO_HW && abs(g[1]) < EGO_HL)) Float.NaN
                else {
                    // 這個像素蓋到的地面大小：看隔壁像素反投影差多少
                    val g2 = m.unprojectToGround(sx + kx, sy + ky)
                    val foot = if (g2 == null) 0.3f else max(0.04f, min(1.5f, max(abs(g2[0] - g[0]), abs(g2[1] - g[1]))))
                    bev.area(g[0], g[1], foot).also { if (!it.isNaN()) used++ }
                }
            }
            if (used < outW * oh / 6) return -1f
            val php = highpass(boxBlur(pred, outW, oh, 1), outW, oh, hpR)
            return ncc(shp, if (edges) edgeEnergy(php, outW, oh) else php, outW * oh, outW * oh / 8)
        }
        return ::score
    }

    private fun search3d(
        pane: FloatArray,
        coarse: (PerspectiveMapper.Params) -> Float,
        fine: (PerspectiveMapper.Params) -> Float
    ): P3Result? {
        val base = PerspectiveMapper.Params(pane[0], pane[1], pane[2], pane[3], 0f, 25f, 9f, 50f, 0f, 0f)
        val grid = ArrayList<Pair<PerspectiveMapper.Params, Float>>()
        // 包含鏡頭展開畫面那種「很近、很廣角」（側邊／倒車鏡頭往外看）
        for (az in -180 until 180 step 15) for (el in listOf(10f, 18f, 28f, 40f, 55f, 75f)) for (d in listOf(1.8f, 3.5f, 6f, 9f, 13f))
            for (fov in listOf(40f, 55f, 75f, 100f)) for ((tx, tz) in (if (d < 5f) NEAR_TARGETS else FAR_TARGETS)) {
                val p = base.copy(azDeg = az.toFloat(), elDeg = el, dist = d, fovDeg = fov, tx = tx, tz = tz)
                grid.add(p to coarse(p))
            }
        // 近鏡頭（鏡頭展開畫面）與遠鏡頭（3D 環繞）分開挑候選，免得某一類的錯誤解把名額佔光。
        // 先在低解析度上收斂（便宜），每類再挑最好的 2 個到高解析度細修（貴，車機上大部分時間花在這）
        fun coarseRefine(list: List<Pair<PerspectiveMapper.Params, Float>>, n: Int) =
            list.sortedByDescending { it.second }.take(n).map { (p0, _) ->
                var c = nelderMead(p0, coarse, 100)
                val c2 = nelderMead(c.params, coarse, 60); if (c2.score > c.score) c = c2
                c
            }.sortedByDescending { it.score }.take(2)
        val finalists = coarseRefine(grid.filter { it.first.dist < 5f }, 4) + coarseRefine(grid.filter { it.first.dist >= 5f }, 8)
        var best: P3Result? = null
        for (c in finalists) {
            // 細修重啟幾次：距離跟視角會互相抵（看起來差不多），單次單形容易卡住
            var r = nelderMead(c.params, fine, 150)
            repeat(3) {
                val d = dollyZoom(r, fine)
                val r2 = nelderMead(d.params, fine, 120)
                if (r2.score > r.score + 1e-4f) r = r2
            }
            if (best == null || r.score > best.score) best = r
        }
        return best
    }

    /**
     * 使用者拖動／縮放原廠 3D 之後，從上一組參數快速重新找（比整套 [calibrate3d] 快很多）：
     * 拖動主要改方位角（繞車轉）、上下拖改俯角、縮放改距離 → 先繞一圈掃方位角，
     * 再掃俯角與距離，最後細修。用模糊邊緣粗掃（峰寬），紋理細修。
     */
    fun retrack3d(scr: Screen, start: PerspectiveMapper.Params, bev: Bev, minScore: Float = 0.45f): P3Result? {
        val pane = floatArrayOf(start.clipL, start.clipT, start.clipR, start.clipB)
        val coarse = scorer3d(scr, pane, bev, 36, edges = true) ?: return null
        val fine = scorer3d(scr, pane, bev, 72) ?: return null
        // 方位角（繞一圈）× 俯角 × 距離一起粗掃：使用者常常同時轉、上下拖、縮放，分開掃會在第一步就丟掉正解
        val grid = ArrayList<Pair<PerspectiveMapper.Params, Float>>()
        for (k in 0 until 36) for (el in listOf(10f, 20f, 30f, 45f, 60f)) for (dk in listOf(0.7f, 1f, 1.4f)) {
            val q = start.copy(azDeg = start.azDeg + k * 10f, elDeg = el, dist = (start.dist * dk).coerceIn(1f, 40f))
            grid.add(q to coarse(q))
        }
        val cands = grid.sortedByDescending { it.second }.take(5).map { it.first }
        var out: P3Result? = null
        for (c0 in cands) {
            val c = nelderMead(c0, coarse, 60)
            var r = nelderMead(c.params, fine, 120)
            val d = dollyZoom(r, fine); val r2 = nelderMead(d.params, fine, 80); if (r2.score > r.score) r = r2
            if (out == null || r.score > out.score) out = r
        }
        return out?.takeIf { it.score >= minScore }
    }

    /**
     * 沿「推軌變焦」方向掃：距離變 d'、視角跟著變到讓看向點附近大小不變（tan(fov'/2) = tan(fov/2)·d/d'）。
     * 這兩個參數互相抵，自車附近幾乎一樣、只有遠處不同 —— 一般的單形法很難沿著這條谷走，專門掃一次。
     */
    private fun dollyZoom(r: P3Result, f: (PerspectiveMapper.Params) -> Float): P3Result {
        var best = r
        val t0 = kotlin.math.tan(Math.toRadians(r.params.fovDeg / 2.0))
        for (k in -6..6) {
            if (k == 0) continue
            val d = r.params.dist * exp(0.07f * k)
            val fov = (2.0 * Math.toDegrees(kotlin.math.atan(t0 * r.params.dist / d))).toFloat()
            val p = r.params.copy(dist = d, fovDeg = fov)
            val s = f(p)
            if (s > best.score) best = P3Result(p, s)
        }
        return best
    }

    /** 6 維 Nelder–Mead：方位、俯角、距離、視角、看向點 x、z（最大化分數）。 */
    private fun nelderMead(p0: PerspectiveMapper.Params, f: (PerspectiveMapper.Params) -> Float, iters: Int): P3Result {
        fun toV(p: PerspectiveMapper.Params) = floatArrayOf(p.azDeg, p.elDeg, p.dist, p.fovDeg, p.tx, p.tz)
        fun toP(v: FloatArray) = p0.copy(azDeg = v[0], elDeg = v[1], dist = v[2], fovDeg = v[3], tx = v[4], tz = v[5])
        val steps = floatArrayOf(15f, 8f, 2.5f, 12f, 1.2f, 1.2f)
        val n = 6
        val simplex = Array(n + 1) { i -> toV(p0).also { if (i > 0) it[i - 1] += steps[i - 1] } }
        val vals = FloatArray(n + 1) { -f(toP(simplex[it])) }   // 最小化 −NCC
        repeat(iters) {
            val order = (0..n).sortedBy { vals[it] }
            val bestI = order[0]; val worst = order[n]; val second = order[n - 1]
            val cen = FloatArray(n)
            for (i in order.take(n)) for (j in 0 until n) cen[j] += simplex[i][j] / n
            fun pt(a: Float) = FloatArray(n) { cen[it] + a * (simplex[worst][it] - cen[it]) }
            val xr = pt(-1f); val fr = -f(toP(xr))
            when {
                fr < vals[bestI] -> {
                    val xe = pt(-2f); val fe = -f(toP(xe))
                    if (fe < fr) { simplex[worst] = xe; vals[worst] = fe } else { simplex[worst] = xr; vals[worst] = fr }
                }
                fr < vals[second] -> { simplex[worst] = xr; vals[worst] = fr }
                else -> {
                    val xc = pt(0.5f); val fc = -f(toP(xc))
                    if (fc < vals[worst]) { simplex[worst] = xc; vals[worst] = fc }
                    else for (i in 0..n) if (i != bestI) {
                        for (j in 0 until n) simplex[i][j] = simplex[bestI][j] + 0.5f * (simplex[i][j] - simplex[bestI][j])
                        vals[i] = -f(toP(simplex[i]))
                    }
                }
            }
        }
        val bi = (0..n).minByOrNull { vals[it] }!!
        return P3Result(toP(simplex[bi]), -vals[bi])
    }
}
