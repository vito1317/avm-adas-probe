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
    @Volatile var params: AnnotationMapper.Params = AnnotationMapper.Params.DEFAULT
        set(v) { field = v; postInvalidate() }
    @Volatile var calibrating = false
        set(v) { field = v; postInvalidate() }
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

    override fun onDraw(c: Canvas) {
        val m = AnnotationMapper(params, width, height)
        val p = payload?.takeIf { SystemClock.elapsedRealtime() - payloadAt < STALE_MS }

        c.save()
        c.clipRect(m.clipLeft, m.clipTop, m.clipRight, m.clipBottom)
        if (calibrating) drawCalibration(c, m)
        if (p != null) {
            p.optJSONObject("road")?.optJSONArray("lines")?.let { drawRoad(c, m, it) }
            p.optJSONArray("slots")?.let { drawSlots(c, m, it) }
            p.optJSONObject("park")?.let { drawParkPath(c, m, it) }
            p.optJSONObject("turn")?.let { drawTurnPath(c, m, it) }
            p.optJSONObject("lca")?.let { l ->
                // 變道走廊：路徑上沒威脅＝綠，有來車＝紅
                val col = if (l.optBoolean("pathOk", true)) Color.rgb(90, 220, 120) else Color.rgb(255, 70, 60)
                l.optJSONArray("path")?.let { corridor(c, m, points(it), AnnotationMapper.EGO_HW, col) }
            }
            p.optJSONArray("obs")?.let { drawObstacles(c, m, it) }
            p.optJSONArray("objs")?.let { drawObjects(c, m, it) }
        }
        c.restore()

        drawHud(c, p)
        status?.let { drawStatus(c, m, it) }
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
        polygon(c, m, outer + inner.asReversed(), col)
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
        c.rotate(m.screenAngleDeg(yaw) - if (params.flip) 180f else 0f, cx, cy)
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

    private fun drawStatus(c: Canvas, m: AnnotationMapper, s: String) {
        val lines = s.split('\n').take(8)
        val lh = 16 * dp
        val w = lines.maxOf { text.measureText(it) } + 16 * dp
        val x = m.clipLeft + 8 * dp
        val y = m.clipBottom - lines.size * lh - 16 * dp
        rect.set(x, y, x + w, y + lines.size * lh + 10 * dp)
        fill.color = Color.argb(190, 10, 15, 25)
        c.drawRoundRect(rect, 8 * dp, 8 * dp, fill)
        lines.forEachIndexed { i, l -> c.drawText(l, x + 8 * dp, y + (i + 1) * lh, text) }
    }
}
