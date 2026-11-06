package com.vito.avmprobe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * 合併版的標注圖層：透明、不吃觸控，蓋在原廠 360 的畫面上。
 * 把 [AdasPipeline] 每幀的結果（跟 3D 面板同一份 JSON）畫在原廠**俯視圖**上：
 * 車輛／行人外框與距離、車道線、停車格、倒車／轉彎路徑、障礙物，以及畫面上方的警示。
 *
 * 對位靠 [AnnotationMapper]；[calibrating] 時加畫自車外框與俯視圖範圍，讓使用者把它對到原廠車模上。
 */
class AnnotationView(context: Context) : View(context) {

    companion object {
        private const val STALE_MS = 1500L
        private const val WARN = 1.2f
        private const val CRIT = 0.5f
        private const val TURN_HOLD_MS = 2000L
        private val NAMES = mapOf(
            "car" to "轎車", "van" to "箱型車", "truck" to "卡車", "bus" to "公車", "moto" to "機車",
            "bigbike" to "重機", "bike" to "腳踏車", "ped" to "行人", "cone" to "三角錐"
        )
        private val HALF = mapOf(
            "car" to floatArrayOf(0.9f, 2.3f), "van" to floatArrayOf(1.0f, 2.5f),
            "truck" to floatArrayOf(1.2f, 3.5f), "bus" to floatArrayOf(1.3f, 5.5f),
            "moto" to floatArrayOf(0.4f, 1.0f), "bigbike" to floatArrayOf(0.45f, 1.1f),
            "bike" to floatArrayOf(0.35f, 0.9f), "ped" to floatArrayOf(0.3f, 0.3f),
            "cone" to floatArrayOf(0.2f, 0.2f)
        )
    }

    @Volatile private var payload: JSONObject? = null
    @Volatile private var payloadAt = 0L
    /**
     * 原廠目前的視角（BV_state 名稱，由注入的掛鉤告訴我們）。null = 還不知道，
     * 就用方向燈當視角（打燈原廠會切側邊 3D）。
     */
    @Volatile var viewKey: String? = null
        set(v) { if (field != v) { field = v; postInvalidate() } }
    /** 每個視角的標注設定（由 [Engine360] 從設定檔讀；改了設定要呼叫 [invalidateProfiles]）。 */
    var profileFor: (String) -> ViewProfile = { ViewProfile.defaultFor(it, AnnotationMapper.Params.DEFAULT) }
    private val profiles = java.util.concurrent.ConcurrentHashMap<String, ViewProfile>()
    fun invalidateProfiles() { profiles.clear(); postInvalidate() }
    /** 對位模式：在目前視角畫出自車外框（俯視圖）／立體自車線框（3D），以及範圍框與格線。 */
    @Volatile var calibrating = false
        set(v) { field = v; postInvalidate() }
    /**
     * 要不要把偵測到的車道線畫出來。原廠俯視圖本來就是真實地面影像，地上的線看得到；
     * 再疊一層偵測結果只會多出逐幀抖動、偶有誤判的「飄動白線」。預設不畫（偵測照跑，LDW／變道要用）。
     */
    @Volatile var drawRoad = false
        set(v) { field = v; postInvalidate() }

    private var lastTurn: Char? = null
    private var lastTurnAt = 0L

    /** 還沒有影格時顯示的診斷文字（null = 不顯示）。 */
    @Volatile var status: String? = null
        set(v) { if (field != v) { field = v; postInvalidate() } }

    private val dp = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * dp }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * dp; color = Color.WHITE; setShadowLayer(3f * dp, 0f, 0f, Color.BLACK)
    }
    private val hud = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f * dp; color = Color.WHITE; isFakeBoldText = true }
    private val dash = DashPathEffect(floatArrayOf(10f * dp, 8f * dp), 0f)
    private val path = Path()
    private val rect = RectF()

    init {
        isClickable = false
        isFocusable = false
        setWillNotDraw(false)
    }

    /** 任何執行緒都可以呼叫。 */
    fun update(p: JSONObject) {
        payload = p
        payloadAt = SystemClock.elapsedRealtime()
        postInvalidate()
    }

    /** 現在套用的是哪個視角：原廠告訴我們的優先，不知道就看方向燈。 */
    fun currentKey(): String {
        viewKey?.let { return it }
        return when (activeTurn(payload)) {
            'L' -> ViewProfile.TURN_L
            'R' -> ViewProfile.TURN_R
            else -> ViewProfile.DEFAULT
        }
    }

    fun currentProfile(): ViewProfile = currentKey().let { k -> profiles.getOrPut(k) { profileFor(k) } }

    override fun onDraw(c: Canvas) {
        val p = payload?.takeIf { SystemClock.elapsedRealtime() - payloadAt < STALE_MS }
        val prof = currentProfile()
        val top = prof.top
        if (top != null) drawTop(c, AnnotationMapper(top, width, height), p)
        prof.p3?.let { draw3d(c, PerspectiveMapper(it, width, height), p) }
        drawHud(c, p)
        status?.let { drawStatus(c, it, top?.let { t -> AnnotationMapper(t, width, height) }) }
    }

    private fun drawTop(c: Canvas, m: AnnotationMapper, p: JSONObject?) {
        c.save()
        c.clipRect(m.clipLeft, m.clipTop, m.clipRight, m.clipBottom)
        if (calibrating) drawCalibration(c, m)
        if (p != null) {
            if (drawRoad) p.optJSONObject("road")?.optJSONArray("lines")?.let { drawRoad(c, m, it) }
            p.optJSONArray("slots")?.let { drawSlots(c, m, it) }
            p.optJSONObject("park")?.let { drawParkPath(c, m, it) }
            p.optJSONObject("turn")?.let { drawTurnPath(c, m, it) }
            p.optJSONObject("lc")?.let { drawLaneChange(c, m, it) }
            p.optJSONArray("obs")?.let { drawObstacles(c, m, it) }
            p.optJSONArray("objs")?.let { drawObjects(c, m, it) }
        }
        c.restore()
    }

    // ───────────────────────── 原廠 3D 視角（打方向燈時） ─────────────────────────

    /** 方向燈閃爍時訊號會一亮一滅，撐 [TURN_HOLD_MS] 免得標注跟著閃。 */
    private fun activeTurn(p: JSONObject?): Char? {
        val now = SystemClock.elapsedRealtime()
        val t = p?.optJSONObject("veh")?.optString("turn")?.firstOrNull()
        if (t == 'L' || t == 'R') { lastTurn = t; lastTurnAt = now }
        return if (now - lastTurnAt < TURN_HOLD_MS) lastTurn else null
    }

    private fun draw3d(c: Canvas, m: PerspectiveMapper, p: JSONObject?) {
        c.save()
        c.clipRect(m.clipLeft, m.clipTop, m.clipRight, m.clipBottom)
        if (calibrating) drawCalibration3d(c, m)
        if (p != null) {
            p.optJSONObject("turn")?.let { t ->
                val col = if (t.optInt("hit") > 0) Color.rgb(255, 70, 60) else Color.rgb(255, 200, 60)
                val o = t.optJSONArray("o")?.let { points(it) }
                val i = t.optJSONArray("i")?.let { points(it) }
                val sw = t.optJSONArray("sw")?.let { points(it) }
                if (sw != null && sw.size >= 3) groundPolygon3d(c, m, sw, col)
                else if (o != null && i != null && o.size >= 2 && i.size >= 2) groundPolygon3d(c, m, o + i.asReversed(), col)
            }
            p.optJSONObject("lc")?.let { lc ->
                val col = laneChangeColor(lc)
                groundPolygon3d(c, m, laneBand(lc), col, alpha = 35)
                lc.optJSONArray("pts")?.let { groundPolygon3d(c, m, AnnotationMapper.corridor(points(it), AnnotationMapper.EGO_HW), col) }
            }
            p.optJSONObject("park")?.optJSONArray("pts")?.let {
                groundPolygon3d(c, m, AnnotationMapper.corridor(points(it), AnnotationMapper.EGO_HW), Color.rgb(70, 170, 255))
            }
            p.optJSONArray("objs")?.let { drawObjects3d(c, m, it) }
        }
        c.restore()
    }

    /** 地面（y = 0）上的多邊形。鏡頭背後的點丟掉（鏡頭在車後上方往前看，丟的都是看不到的部分）。 */
    private fun groundPolygon3d(c: Canvas, m: PerspectiveMapper, poly: List<FloatArray>, col: Int, alpha: Int = 80) {
        val pts = poly.mapNotNull { m.project(it[0], 0f, it[1]) }
        if (pts.size < 3) return
        path.reset()
        pts.forEachIndexed { i, q -> if (i == 0) path.moveTo(q[0], q[1]) else path.lineTo(q[0], q[1]) }
        path.close()
        fill.color = col; fill.alpha = alpha
        c.drawPath(path, fill)
        if (alpha >= 60) { stroke.color = col; stroke.alpha = 230; stroke.pathEffect = null; c.drawPath(path, stroke) }
    }

    /** 物件畫成立體線框（地面外框 + 高度），上面標距離。 */
    private fun drawObjects3d(c: Canvas, m: PerspectiveMapper, a: JSONArray) {
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val t = o.optString("t")
            val x = o.optDouble("x").toFloat(); val z = o.optDouble("z").toFloat()
            val hl = HALF[t] ?: floatArrayOf(0.5f, 0.5f)
            val h = when (t) { "ped" -> 1.7f; "bus", "truck" -> 3.0f; "van" -> 2.0f; "cone" -> 0.7f; "car" -> 1.5f; else -> 1.4f }
            val clr = clearance(x, z)
            val col = when {
                clr < CRIT -> Color.rgb(255, 60, 50)
                clr < WARN -> Color.rgb(255, 150, 40)
                t == "ped" || t == "bike" || t == "moto" || t == "bigbike" -> Color.rgb(255, 220, 60)
                else -> Color.rgb(60, 220, 255)
            }
            val yaw = if (o.has("yaw")) o.optDouble("yaw").toFloat() else 0f
            if (!box3d(c, m, x, z, hl[0], hl[1], h, yaw, col)) continue
            val top = m.project(x, h + 0.2f, z) ?: continue
            val label = "${NAMES[t] ?: t} ${"%.1f".format(clr)}m"
            c.drawText(label, top[0] - text.measureText(label) / 2f, top[1], text)
        }
    }

    /** 以 (x, z) 為中心、半寬 hw、半長 hl、高 h、方向 yaw 的立體線框。任何角點在鏡頭後面就不畫。 */
    private fun box3d(c: Canvas, m: PerspectiveMapper, x: Float, z: Float, hw: Float, hl: Float, h: Float, yaw: Float, col: Int): Boolean {
        val cs = kotlin.math.cos(yaw); val sn = kotlin.math.sin(yaw)
        val corners = arrayOf(floatArrayOf(-hw, -hl), floatArrayOf(hw, -hl), floatArrayOf(hw, hl), floatArrayOf(-hw, hl))
        val bottom = ArrayList<FloatArray>(4); val topP = ArrayList<FloatArray>(4)
        for (q in corners) {
            // 車身座標（右 q0、前 q1）轉 yaw（從 +z 往 +x）
            val wx = x + q[0] * cs + q[1] * sn
            val wz = z - q[0] * sn + q[1] * cs
            bottom.add(m.project(wx, 0f, wz) ?: return false)
            topP.add(m.project(wx, h, wz) ?: return false)
        }
        path.reset()
        bottom.forEachIndexed { i, q -> if (i == 0) path.moveTo(q[0], q[1]) else path.lineTo(q[0], q[1]) }
        path.close()
        fill.color = col; fill.alpha = 55
        c.drawPath(path, fill)
        stroke.color = col; stroke.alpha = 235; stroke.pathEffect = null
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            c.drawLine(bottom[i][0], bottom[i][1], bottom[j][0], bottom[j][1], stroke)
            c.drawLine(topP[i][0], topP[i][1], topP[j][0], topP[j][1], stroke)
            c.drawLine(bottom[i][0], bottom[i][1], topP[i][0], topP[i][1], stroke)
        }
        return true
    }

    private fun drawCalibration3d(c: Canvas, m: PerspectiveMapper) {
        stroke.pathEffect = null
        stroke.color = Color.argb(200, 0, 220, 255)
        c.drawRect(m.clipLeft + 2, m.clipTop + 2, m.clipRight - 2, m.clipBottom - 2, stroke)
        // 地面 1 m 格線
        stroke.color = Color.argb(80, 255, 255, 255); stroke.strokeWidth = 1f * dp
        for (i in -6..6) {
            val a = m.project(i.toFloat(), 0f, -6f); val b = m.project(i.toFloat(), 0f, 12f)
            if (a != null && b != null) c.drawLine(a[0], a[1], b[0], b[1], stroke)
        }
        for (k in -6..12) {
            val a = m.project(-6f, 0f, k.toFloat()); val b = m.project(6f, 0f, k.toFloat())
            if (a != null && b != null) c.drawLine(a[0], a[1], b[0], b[1], stroke)
        }
        stroke.strokeWidth = 2.5f * dp
        // 立體自車線框（車高 1.45 m），車頭那一面加粗標示
        box3d(c, m, 0f, 0f, AnnotationMapper.EGO_HW, AnnotationMapper.EGO_HL, 1.45f, 0f, Color.argb(230, 255, 0, 255))
        val fl = m.project(-AnnotationMapper.EGO_HW, 0.7f, AnnotationMapper.EGO_HL)
        val fr = m.project(AnnotationMapper.EGO_HW, 0.7f, AnnotationMapper.EGO_HL)
        if (fl != null && fr != null) {
            stroke.strokeWidth = 5f * dp
            c.drawLine(fl[0], fl[1], fr[0], fr[1], stroke)
            stroke.strokeWidth = 2.5f * dp
            c.drawText("車頭", (fl[0] + fr[0]) / 2f, (fl[1] + fr[1]) / 2f - 6 * dp, text)
        }
    }

    // ───────────────────────── 俯視圖上的東西 ─────────────────────────

    private fun drawCalibration(c: Canvas, m: AnnotationMapper) {
        stroke.pathEffect = null
        stroke.color = Color.argb(200, 0, 220, 255)
        c.drawRect(m.clipLeft + 2, m.clipTop + 2, m.clipRight - 2, m.clipBottom - 2, stroke)
        box(c, m, 0f, 0f, AnnotationMapper.EGO_HW, AnnotationMapper.EGO_HL, 0f, Color.argb(230, 255, 0, 255), fillAlpha = 40)
        // 1 m 格線，方便看比例
        stroke.color = Color.argb(70, 255, 255, 255)
        stroke.strokeWidth = 1f * dp
        for (i in -8..8) {
            c.drawLine(m.sx(i.toFloat(), -10f), m.sy(i.toFloat(), -10f), m.sx(i.toFloat(), 10f), m.sy(i.toFloat(), 10f), stroke)
            c.drawLine(m.sx(-8f, i.toFloat()), m.sy(-8f, i.toFloat()), m.sx(8f, i.toFloat()), m.sy(8f, i.toFloat()), stroke)
        }
        stroke.strokeWidth = 2.5f * dp
    }

    private fun drawObjects(c: Canvas, m: AnnotationMapper, a: JSONArray) {
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val t = o.optString("t")
            val x = o.optDouble("x").toFloat(); val z = o.optDouble("z").toFloat()
            val hl = HALF[t] ?: floatArrayOf(0.5f, 0.5f)
            val clr = clearance(x, z)
            val col = when {
                clr < CRIT -> Color.rgb(255, 60, 50)
                clr < WARN -> Color.rgb(255, 150, 40)
                t == "ped" || t == "bike" || t == "moto" || t == "bigbike" -> Color.rgb(255, 220, 60)
                else -> Color.rgb(60, 220, 255)
            }
            val yaw = if (o.has("yaw")) o.optDouble("yaw").toFloat() else 0f
            box(c, m, x, z, hl[0], hl[1], yaw, col, fillAlpha = 50)
            val label = "${NAMES[t] ?: t} ${"%.1f".format(clr)}m"
            c.drawText(label, m.sx(x, z) - text.measureText(label) / 2f, m.sy(x, z) - hl[1] * m.pixelsPerMeter - 4 * dp, text)
        }
    }

    private fun drawRoad(c: Canvas, m: AnnotationMapper, a: JSONArray) {
        for (i in 0 until a.length()) {
            val l = a.optJSONObject(i) ?: continue
            stroke.color = when (l.optString("k")) {
                "y" -> Color.rgb(255, 210, 60)
                "r" -> Color.rgb(255, 80, 70)
                else -> Color.WHITE
            }
            stroke.pathEffect = if (l.optInt("d") == 1) dash else null
            val x1 = l.optDouble("x1").toFloat(); val z1 = l.optDouble("z1").toFloat()
            val x2 = l.optDouble("x2").toFloat(); val z2 = l.optDouble("z2").toFloat()
            c.drawLine(m.sx(x1, z1), m.sy(x1, z1), m.sx(x2, z2), m.sy(x2, z2), stroke)
        }
        stroke.pathEffect = null
    }

    private fun drawSlots(c: Canvas, m: AnnotationMapper, a: JSONArray) {
        for (i in 0 until a.length()) {
            val s = a.optJSONObject(i) ?: continue
            // 只畫空車位：有車的格子在原廠影像上本來就看得到，畫紅框只會一閃一閃地添亂
            if (s.optInt("o") == 1) continue
            val q = s.optJSONArray("p") ?: continue
            if (q.length() < 8) continue
            path.reset()
            for (k in 0 until 4) {
                val x = q.optDouble(k * 2).toFloat(); val z = q.optDouble(k * 2 + 1).toFloat()
                if (k == 0) path.moveTo(m.sx(x, z), m.sy(x, z)) else path.lineTo(m.sx(x, z), m.sy(x, z))
            }
            path.close()
            val col = if (s.optInt("o") == 1) Color.rgb(255, 90, 70) else Color.rgb(80, 230, 120)
            fill.color = col; fill.alpha = 45
            c.drawPath(path, fill)
            stroke.color = col
            c.drawPath(path, stroke)
        }
    }

    private fun drawObstacles(c: Canvas, m: AnnotationMapper, a: JSONArray) {
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val x = o.optDouble("x").toFloat(); val z = o.optDouble("z").toFloat()
            box(c, m, x, z, o.optDouble("w").toFloat() / 2f, o.optDouble("l").toFloat() / 2f, 0f,
                Color.rgb(255, 120, 200), fillAlpha = 40)
        }
    }

    // ───────────────────────── 變道（打方向燈） ─────────────────────────

    private fun laneChangeColor(lc: JSONObject): Int =
        if (lc.optBoolean("ok", true)) Color.rgb(90, 220, 120) else Color.rgb(255, 70, 60)

    /** 目標車道（打燈那一側的整條車道）：自車後方 4 m 到前方 25 m。 */
    private fun laneBand(lc: JSONObject): List<FloatArray> {
        val dir = lc.optInt("dir", 1).toFloat()
        val w = lc.optDouble("w", 3.5).toFloat()
        val a = dir * w / 2f; val b = dir * w * 1.5f
        return listOf(floatArrayOf(a, -4f), floatArrayOf(a, 25f), floatArrayOf(b, 25f), floatArrayOf(b, -4f))
    }

    /** 變道：目標車道半透明標出來，再畫一條從目前車道切過去的車寬走廊。那一側有來車就變紅。 */
    private fun drawLaneChange(c: Canvas, m: AnnotationMapper, lc: JSONObject) {
        val col = laneChangeColor(lc)
        val band = laneBand(lc)
        path.reset()
        band.forEachIndexed { i, q -> if (i == 0) path.moveTo(m.sx(q[0], q[1]), m.sy(q[0], q[1])) else path.lineTo(m.sx(q[0], q[1]), m.sy(q[0], q[1])) }
        path.close()
        fill.color = col; fill.alpha = 35
        c.drawPath(path, fill)
        lc.optJSONArray("pts")?.let { corridor(c, m, points(it), AnnotationMapper.EGO_HW, col) }
    }

    // ───────────────────────── 預測走廊（車寬） ─────────────────────────

    private fun points(a: JSONArray): List<FloatArray> {
        val out = ArrayList<FloatArray>(a.length())
        for (i in 0 until a.length()) {
            val q = a.optJSONArray(i) ?: continue
            out.add(floatArrayOf(q.optDouble(0).toFloat(), q.optDouble(1).toFloat()))
        }
        return out
    }

    /** 沿中心線、寬 2×[halfWidth] 的半透明走廊，兩側緣描邊。 */
    private fun corridor(c: Canvas, m: AnnotationMapper, center: List<FloatArray>, halfWidth: Float, col: Int) {
        polygon(c, m, AnnotationMapper.corridor(center, halfWidth), col)
    }

    private fun polygon(c: Canvas, m: AnnotationMapper, poly: List<FloatArray>, col: Int) {
        if (poly.size < 3) return
        path.reset()
        poly.forEachIndexed { i, q ->
            if (i == 0) path.moveTo(m.sx(q[0], q[1]), m.sy(q[0], q[1])) else path.lineTo(m.sx(q[0], q[1]), m.sy(q[0], q[1]))
        }
        path.close()
        fill.color = col; fill.alpha = 70
        c.drawPath(path, fill)
        stroke.color = col; stroke.alpha = 230
        stroke.pathEffect = null
        c.drawPath(path, stroke)
    }

    /**
     * 倒車入庫：路徑點是**後軸中心**，走廊以它為中心、車寬；終點畫出停好時的車身外框，
     * 一眼看出會停在格子的哪裡。
     */
    private fun drawParkPath(c: Canvas, m: AnnotationMapper, park: JSONObject) {
        val col = Color.rgb(70, 170, 255)
        park.optJSONArray("pts")?.let { corridor(c, m, points(it), AnnotationMapper.EGO_HW, col) }
        val g = park.optJSONArray("goal") ?: return
        if (g.length() < 3) return
        val th = g.optDouble(2).toFloat()
        // 後軸 → 車身中心：沿車頭方向（θ 從 +z 往 +x 量）前移
        val cx = g.optDouble(0).toFloat() + AnnotationMapper.AXLE_TO_CENTER * kotlin.math.sin(th)
        val cz = g.optDouble(1).toFloat() + AnnotationMapper.AXLE_TO_CENTER * kotlin.math.cos(th)
        box(c, m, cx, cz, AnnotationMapper.EGO_HW, AnnotationMapper.EGO_HL, th, col, fillAlpha = 90)
    }

    /**
     * 轉彎：外側車頭角的路徑與內側後輪的路徑之間，就是車身掃過的範圍（含內輪差）。
     * 範圍裡有車或人（hit）就變紅。
     */
    private fun drawTurnPath(c: Canvas, m: AnnotationMapper, t: JSONObject) {
        val col = if (t.optInt("hit") > 0) Color.rgb(255, 70, 60) else Color.rgb(255, 200, 60)
        val outer = t.optJSONArray("o")?.let { points(it) } ?: return
        val inner = t.optJSONArray("i")?.let { points(it) } ?: return
        if (outer.size < 2 || inner.size < 2) return
        polygon(c, m, t.optJSONArray("sw")?.let { points(it) } ?: (outer + inner.asReversed()), col)
        // 內側後輪那條另外用虛線強調（內輪差捲到機車最常出事）
        stroke.color = col; stroke.pathEffect = dash
        path.reset()
        inner.forEachIndexed { i, q ->
            if (i == 0) path.moveTo(m.sx(q[0], q[1]), m.sy(q[0], q[1])) else path.lineTo(m.sx(q[0], q[1]), m.sy(q[0], q[1]))
        }
        c.drawPath(path, stroke)
        stroke.pathEffect = null
    }

    /** 以 (x, z) 為中心、半寬 hw、半長 hl、方向 yaw 的外框。 */
    private fun box(c: Canvas, m: AnnotationMapper, x: Float, z: Float, hw: Float, hl: Float, yaw: Float, col: Int, fillAlpha: Int) {
        val cx = m.sx(x, z); val cy = m.sy(x, z); val ppm = m.pixelsPerMeter
        rect.set(cx - hw * ppm, cy - hl * ppm, cx + hw * ppm, cy + hl * ppm)
        c.save()
        c.rotate(m.screenAngleDeg(yaw) - if (m.p.flip) 180f else 0f, cx, cy)
        fill.color = col; fill.alpha = fillAlpha
        c.drawRoundRect(rect, 4 * dp, 4 * dp, fill)
        stroke.color = col
        c.drawRoundRect(rect, 4 * dp, 4 * dp, stroke)
        c.restore()
    }

    private fun clearance(x: Float, z: Float): Float =
        hypot(max(abs(x) - AnnotationMapper.EGO_HW, 0f), max(abs(z) - AnnotationMapper.EGO_HL, 0f))

    // ───────────────────────── 畫面上方的警示 ─────────────────────────

    private fun drawHud(c: Canvas, p: JSONObject?) {
        if (p == null) return
        val msgs = ArrayList<Pair<String, Int>>()
        var minClr = Float.MAX_VALUE
        p.optJSONArray("objs")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let {
                minClr = minOf(minClr, clearance(it.optDouble("x").toFloat(), it.optDouble("z").toFloat()))
            }
        }
        if (minClr < CRIT) msgs.add("⚠ 距離過近" to Color.rgb(220, 40, 40))
        else if (minClr < WARN) msgs.add("⚠ 碰撞預警" to Color.rgb(230, 120, 20))
        p.optJSONObject("start")?.let { s ->
            if (s.optBoolean("lead")) msgs.add("前車已起步" to Color.rgb(30, 150, 230))
            else if (s.optBoolean("green")) msgs.add("綠燈了" to Color.rgb(30, 170, 80))
            Unit
        }
        p.optJSONObject("ldw")?.let { l ->
            if (l.optBoolean("l")) msgs.add("◀ 偏離車道" to Color.rgb(230, 160, 20))
            if (l.optBoolean("r")) msgs.add("偏離車道 ▶" to Color.rgb(230, 160, 20))
        }
        p.optJSONObject("lca")?.let { l ->
            if (l.optInt("L") >= 2) msgs.add("◀ 左後來車" to Color.rgb(220, 40, 40))
            else if (l.optInt("L") == 1) msgs.add("◀ 左側有車" to Color.rgb(230, 140, 20))
            if (l.optInt("R") >= 2) msgs.add("右後來車 ▶" to Color.rgb(220, 40, 40))
            else if (l.optInt("R") == 1) msgs.add("右側有車 ▶" to Color.rgb(230, 140, 20))
            Unit
        }
        curbLine(p)?.let { msgs.add(it) }
        p.optJSONObject("tl")?.let { t ->
            when (t.optString("s")) {
                "red" -> msgs.add("● 紅燈" to Color.rgb(200, 40, 40))
                "yellow" -> msgs.add("● 黃燈" to Color.rgb(210, 160, 20))
                "green" -> msgs.add("● 綠燈" to Color.rgb(30, 160, 70))
                else -> {}
            }
            Unit
        }
        if (msgs.isEmpty()) return
        var y = 12 * dp
        val padX = 14 * dp; val h = 34 * dp
        for ((s, col) in msgs) {
            val w = hud.measureText(s) + padX * 2
            val x = (width - w) / 2f
            rect.set(x, y, x + w, y + h)
            fill.color = col; fill.alpha = 225
            c.drawRoundRect(rect, h / 2, h / 2, fill)
            c.drawText(s, x + padX, y + h * 0.7f, hud)
            y += h + 6 * dp
        }
    }

    /** 診斷文字：放在俯視圖那格左下；這個視角沒有俯視圖就放畫面左下。 */
    /**
     * 路邊（右側）紅線／黃線：慢速時提醒能不能停。只看右側 —— 左側多半是中線（常常是黃的），
     * 講「左側黃線」反而誤導。線要大致跟車身平行、在右側 0.9～3.5 m、跟車身前後有重疊。
     */
    private fun curbLine(p: JSONObject): Pair<String, Int>? {
        val spd = p.optJSONObject("veh")?.let { if (it.isNull("speed")) null else it.optDouble("speed") }
        if (spd != null && spd > 15.0) return null
        val lines = p.optJSONObject("road")?.optJSONArray("lines") ?: return null
        var best: Char? = null; var bestX = Float.MAX_VALUE
        for (i in 0 until lines.length()) {
            val l = lines.optJSONObject(i) ?: continue
            val k = l.optString("k").firstOrNull() ?: continue
            if (k != 'r' && k != 'y') continue
            val x1 = l.optDouble("x1").toFloat(); val z1 = l.optDouble("z1").toFloat()
            val x2 = l.optDouble("x2").toFloat(); val z2 = l.optDouble("z2").toFloat()
            val dx = x2 - x1; val dz = z2 - z1
            if (abs(dz) < 2f * abs(dx) || hypot(dx, dz) < 1.5f) continue          // 要跟車身平行、夠長
            if (maxOf(z1, z2) < -3f || minOf(z1, z2) > 3f) continue                // 要在車旁邊
            val xm = (x1 + x2) / 2f
            if (xm < 0.9f || xm > 3.5f) continue                                     // 右側路邊
            if (xm < bestX) { bestX = xm; best = k }
        }
        return when (best) {
            'r' -> "右側紅線：禁止臨停、停車" to Color.rgb(200, 40, 40)
            'y' -> "右側黃線：禁止停車（可臨停）" to Color.rgb(200, 150, 20)
            else -> null
        }
    }

    private fun drawStatus(c: Canvas, s: String, m: AnnotationMapper?) {
        val lines = s.split('\n').take(8)
        val lh = 16 * dp
        val w = lines.maxOf { text.measureText(it) } + 16 * dp
        val x = (m?.clipLeft ?: 0f) + 8 * dp
        val y = (m?.clipBottom ?: (height * 0.86f)) - lines.size * lh - 16 * dp
        rect.set(x, y, x + w, y + lines.size * lh + 10 * dp)
        fill.color = Color.argb(190, 10, 15, 25)
        c.drawRoundRect(rect, 8 * dp, 8 * dp, fill)
        lines.forEachIndexed { i, l -> c.drawText(l, x + 8 * dp, y + (i + 1) * lh, text) }
    }
}
