package com.vito.avmprobe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Tesla 式道路畫面（原生繪圖；原廠 360 是系統程序，不能用 WebView）：
 * 從自車後上方看——透視的路面、車道線（照量到的彎度往前延伸、虛線照實畫）、周圍的車／機車／行人（有立體感的方塊，
 * 照車頭方向轉、幀與幀之間平滑移動）、變道／轉彎路徑、盲點、紅綠燈、速度與警示。
 * 資料就是 [AdasPipeline] 每幀的 payload（跟標注畫面同一份），座標 x 右、z 前（公尺）。
 */
class RoadView(context: Context) : View(context) {

    // ───── 相機（追車視角）─────
    private val camH = 5.2f
    private val camBack = 8.0f
    private val lookZ = 12f
    private val pitch = atan((camH / (camBack + lookZ)).toDouble()).toFloat()
    private val sp = sin(pitch); private val cp = cos(pitch)

    private var cx = 0f; private var cy = 0f; private var focal = 1f
    private val tmp = FloatArray(3)

    /** 世界座標 → 螢幕；看不到（在相機後面）回 false。tmp[2] = 深度。 */
    private fun proj(x: Float, y: Float, z: Float): Boolean {
        val ry = y - camH; val rz = z + camBack
        val zc = -ry * sp + rz * cp
        if (zc < 0.4f) return false
        val yc = ry * cp + rz * sp
        tmp[0] = cx + focal * x / zc
        tmp[1] = cy - focal * yc / zc
        tmp[2] = zc
        return true
    }

    // ───── 資料（每幀 payload）─────
    @Volatile private var payload: JSONObject? = null
    /** 點到停車格：傳格子中心 (x, z)，回傳有沒有選到（[AdasPipeline.tapSlot]）。 */
    var onTapSlot: ((Float, Float) -> Boolean)? = null

    /** 點畫面上的停車格 → 選它當目標（再點一次取消）。用投影到畫面的四邊形判斷點到哪格。 */
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.actionMasked != android.view.MotionEvent.ACTION_UP) return true
        val a = payload?.optJSONArray("slots") ?: return super.onTouchEvent(e)
        val sx = e.x; val sy = e.y
        for (i in 0 until a.length()) {
            val s = a.optJSONObject(i) ?: continue
            if (s.optInt("o") == 1) continue
            val q = s.optJSONArray("p") ?: continue
            if (q.length() < 8) continue
            val px = FloatArray(4); val py = FloatArray(4); var ok = true; var mx = 0f; var mz = 0f
            for (k in 0 until 4) {
                val x = q.optDouble(k * 2).toFloat(); val z = q.optDouble(k * 2 + 1).toFloat(); mx += x / 4; mz += z / 4
                if (!proj(x, 0f, z)) { ok = false; break }
                px[k] = tmp[0]; py[k] = tmp[1]
            }
            if (!ok) continue
            var inside = false; var j = 3
            for (k in 0 until 4) {
                if ((py[k] > sy) != (py[j] > sy) && sx < (px[j] - px[k]) * (sy - py[k]) / (py[j] - py[k]) + px[k]) inside = !inside
                j = k
            }
            if (inside) { onTapSlot?.invoke(mx, mz); performClick(); return true }
        }
        return super.onTouchEvent(e)
    }
    override fun performClick(): Boolean = super.performClick()
    @Volatile private var payloadAt = 0L
    fun update(p: JSONObject) { payload = p; payloadAt = SystemClock.elapsedRealtime() }

    /** 物件平滑：同一個 track id 的位置／方向用指數平滑，看起來是在滑動而不是跳。 */
    private class Smooth(var x: Float, var z: Float, var yaw: Float, var t: String, var seen: Long, var alpha: Float = 0f, var id: Int = 0)
    private val tracks = HashMap<String, Smooth>()

    // ───── 畫筆 ─────
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; isFakeBoldText = true }
    private val path = Path()

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }

    // ───── 真實車款圖（tools/road_sprites 用 bev3d 的 3D 模型預先算好：每款 16 個方位）─────
    private class Sprite(val bmp: Bitmap, val n: Int, val px: Int, val span: Float, val cy: Float)
    private val sprites = HashMap<String, Sprite?>()
    private val index: JSONObject? by lazy {
        try { JSONObject(context.assets.open("rs_index.json").bufferedReader().readText()) } catch (_: Throwable) { null }
    }
    private val srcR = Rect(); private val dstR = RectF()
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** 用到才載入（每款約 2 MB）。 */
    private fun sprite(name: String): Sprite? = sprites.getOrPut(name) {
        val meta = index?.optJSONObject(name) ?: return@getOrPut null
        try {
            val b = context.assets.open("rs_$name.webp").use { BitmapFactory.decodeStream(it) } ?: return@getOrPut null
            Sprite(b, meta.optInt("n", 16), meta.optInt("px", 192), meta.optDouble("span").toFloat(), meta.optDouble("cy").toFloat())
        } catch (_: Throwable) { null }
    }

    /** 每個追蹤到的物件固定一個車款（依 id 挑；計程車少一點）。 */
    private fun modelFor(t: String, id: Int): String {
        val h = (id * 2654435761L).ushr(7).toInt() and 0x7fffffff
        return when (t) {
            "car" -> arrayOf("car_sedan", "car_hatch", "car_logan", "suv_evoque", "suv_generic", "car_sedan", "car_hatch", "car_taxi")[h % 8]
            "van" -> arrayOf("van_minivan", "van_postvan")[h % 2]
            "truck" -> "truck"
            "bus" -> arrayOf("bus", "bus_city")[h % 2]
            "moto" -> "scooter_rider"
            "bigbike" -> "bigbike_rider"
            "bike" -> "bicycle_rider"
            "ped" -> arrayOf("ped_man", "ped_woman", "ped_worker", "ped_man", "ped_woman", "ped_child")[h % 6]
            "cone" -> "cone"
            else -> "car_sedan"
        }
    }

    /**
     * 畫車款圖：照「相機在物件的哪個方位」挑 16 格中的一格，照物件的深度縮放。
     * 圖是正交投影算的，中心是模型的中心（離地 cy 公尺），寬高都是 span 公尺。
     */
    // 最近一次畫的圖：用哪一張方位、畫在哪、多大（車燈要對到圖上的位置）
    private var lastK = 0; private var lastN = 16; private var lastSpan = 1f; private var lastCy = 0f
    private var lastSx = 0f; private var lastSy = 0f; private var lastSize = 0f

    /**
     * 車模型上一點（模型座標：x 往車的左邊、y 往上、z 往車頭，公尺）→ 畫在最近那張車子圖上的位置。
     * 圖是正交相機、仰角 13° 算的（tools/road_sprites/render.html），照同一個投影算才會剛好落在車燈上
     * （以前用 3D 透視投影另外算，燈飄在車子旁邊）。回傳 null = 這一面背對鏡頭（看不到）。
     */
    private fun onSprite(mx: Float, my: Float, mz: Float, facing: Float): FloatArray? {
        val a = lastK * 2 * Math.PI / lastN + Math.PI
        val e = Math.toRadians(13.0)
        val dx = sin(a) * cos(e); val dz = cos(a) * cos(e)                 // 相機方向（從車往相機）
        if (facing != 0f && facing * dz < -0.15) return null              // facing: +1 車頭那面、-1 車尾那面
        if (facing == 0f && (abs(dx) < 0.45 || dx * mx < 0)) return null   // 側燈：要從那一側看過去才看得到
        val px = mx.toDouble(); val py = (my - lastCy).toDouble(); val pz = mz.toDouble()
        val u = px * cos(a) - pz * sin(a)                                   // 相機的右方 = (cos a, 0, −sin a)
        val v = -px * sin(e) * sin(a) + py * cos(e) - pz * sin(e) * cos(a)  // 相機的上方
        val k = lastSize / lastSpan
        return floatArrayOf((lastSx + u * k).toFloat(), (lastSy - v * k).toFloat())
    }

    private fun drawSprite(c: Canvas, name: String, x: Float, z: Float, yaw: Float, alpha: Float): Boolean {
        val sp = sprite(name) ?: return false
        val vx = -x; val vz = -camBack - z                       // 物件 → 相機（地面上）
        val lx = vx * cos(yaw) - vz * sin(yaw); val lz = vx * sin(yaw) + vz * cos(yaw)
        var a = kotlin.math.atan2(lx, lz) - Math.PI.toFloat()   // 0 = 從車尾看
        val step = (2 * Math.PI / sp.n).toFloat()
        while (a < 0) a += (2 * Math.PI).toFloat()
        val k = Math.round(a / step) % sp.n
        if (!proj(x, sp.cy, z)) return true
        val size = sp.span * focal / tmp[2]
        if (size < 2f) return true
        srcR.set(k * sp.px, 0, (k + 1) * sp.px, sp.px)
        dstR.set(tmp[0] - size / 2, tmp[1] - size / 2, tmp[0] + size / 2, tmp[1] + size / 2)
        lastK = k; lastN = sp.n; lastSpan = sp.span; lastCy = sp.cy; lastSx = tmp[0]; lastSy = tmp[1]; lastSize = size
        bmpPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        c.drawBitmap(sp.bmp, srcR, dstR, bmpPaint)
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        cx = w / 2f; cy = h * 0.42f; focal = h * 1.05f
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val now = SystemClock.elapsedRealtime()
        val p = payload?.takeIf { now - payloadAt < 3000 }
        drawSky(c, w, h)
        drawGround(c, w, h, p)
        if (p != null) {
            drawPaths(c, p)
            drawObjects(c, p, now)
        }
        drawEgo(c, p, now)
        drawHud(c, w, h, p, now)
        if (isShown) postInvalidateOnAnimation()
    }

    // ───── 背景、路面 ─────
    private fun drawSky(c: Canvas, w: Float, h: Float) {
        fill.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(Color.rgb(6, 10, 20), Color.rgb(12, 22, 40), Color.rgb(4, 6, 12)), floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill); fill.shader = null
        // 地平線光暈
        fill.shader = RadialGradient(cx, cy, w * 0.6f, Color.argb(70, 56, 225, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, cy - h * 0.3f, w, cy + h * 0.3f, fill); fill.shader = null
    }

    /** 一條車道線：從量到的兩端點、照彎度往前延伸到 70 m、往後到 -6 m 的取樣點。 */
    private fun lineSamples(l: JSONObject): List<FloatArray> {
        var x1 = l.optDouble("x1").toFloat(); var z1 = l.optDouble("z1").toFloat()
        var x2 = l.optDouble("x2").toFloat(); var z2 = l.optDouble("z2").toFloat()
        if (z2 < z1) { val tx = x1; x1 = x2; x2 = tx; val tz = z1; z1 = z2; z2 = tz }
        val dz = z2 - z1
        if (dz < 0.5f) return emptyList()
        val slope = (x2 - x1) / dz
        val k = -l.optDouble("kv", 0.0).toFloat()              // 由後往前：k > 0 = 往左彎 → 換成 + = 往右
        val out = ArrayList<FloatArray>()
        var z = -6f
        while (z <= 70f) {
            val x = when {
                z < z1 -> x1 + slope * (z - z1)
                z > z2 -> x2 + slope * (z - z2) + 0.5f * k * (z - z2) * (z - z2)
                else -> x1 + slope * (z - z1)
            }
            out.add(floatArrayOf(x, z)); z += 1f
        }
        return out
    }

    /** 停車場：深灰地板＋淡淡的 2.5 m 格線（沒有車道線、路緣）。 */
    private fun drawLotGround(c: Canvas, h: Float) {
        path.reset(); var first = true
        for ((x, z) in listOf(-14f to -8f, -14f to 45f, 14f to 45f, 14f to -8f)) if (proj(x, 0f, z)) { if (first) path.moveTo(tmp[0], tmp[1]) else path.lineTo(tmp[0], tmp[1]); first = false }
        path.close()
        fill.shader = LinearGradient(0f, cy, 0f, h, Color.rgb(20, 24, 30), Color.rgb(34, 38, 46), Shader.TileMode.CLAMP)
        c.drawPath(path, fill); fill.shader = null
        stroke.color = Color.argb(28, 200, 215, 235); stroke.strokeWidth = 1.5f; stroke.pathEffect = null
        var gx = -12.5f
        while (gx <= 12.5f) { if (proj(gx, 0f, -8f)) { val x0 = tmp[0]; val y0 = tmp[1]; if (proj(gx, 0f, 40f)) c.drawLine(x0, y0, tmp[0], tmp[1], stroke) }; gx += 2.5f }
        var gz = -7.5f
        while (gz <= 40f) { if (proj(-14f, 0f, gz)) { val x0 = tmp[0]; val y0 = tmp[1]; if (proj(14f, 0f, gz)) c.drawLine(x0, y0, tmp[0], tmp[1], stroke) }; gz += 2.5f }
    }

    /** 停車格：空格綠、有車灰、自己停的那格青（標「目前車格」）、選中的藍。 */
    private fun drawSlots(c: Canvas, h: Float, p: JSONObject) {
        val a = p.optJSONArray("slots") ?: return
        for (i in 0 until a.length()) {
            val s = a.optJSONObject(i) ?: continue
            val q = s.optJSONArray("p") ?: continue
            if (q.length() < 8) continue
            val own = s.optInt("own") == 1; val sel = s.optInt("sel") == 1; val occ = s.optInt("o") == 1
            val col = when { sel -> Color.rgb(60, 150, 255); own -> Color.rgb(40, 225, 255); occ -> Color.rgb(120, 130, 145); else -> Color.rgb(70, 230, 120) }
            path.reset(); var ok = true; var mx = 0f; var mz = 0f
            for (k in 0 until 4) {
                val x = q.optDouble(k * 2).toFloat(); val z = q.optDouble(k * 2 + 1).toFloat(); mx += x / 4; mz += z / 4
                if (!proj(x, 0f, z)) { ok = false; break }
                if (k == 0) path.moveTo(tmp[0], tmp[1]) else path.lineTo(tmp[0], tmp[1])
            }
            if (!ok) continue
            path.close()
            fill.color = col; fill.alpha = if (occ && !own) 40 else if (sel) 110 else 70
            c.drawPath(path, fill); fill.alpha = 255
            stroke.color = col; stroke.strokeWidth = if (sel || own) 5f else 3f; stroke.pathEffect = null
            c.drawPath(path, stroke)
            if ((own || sel || !occ) && proj(mx, 0f, mz)) {
                text.textAlign = Paint.Align.CENTER; text.textSize = h * (if (own) 0.04f else 0.032f); text.color = Color.WHITE
                text.setShadowLayer(8f, 0f, 0f, col)
                c.drawText(if (own) "目前車格" else if (sel) "選定" else "P", tmp[0], tmp[1], text)
                text.clearShadowLayer()
            }
        }
    }

    private fun drawGround(c: Canvas, w: Float, h: Float, p: JSONObject?) {
        // 停車場：換成停車場地板＋停車格，不畫車道線
        if (p?.optInt("lot") == 1) { drawLotGround(c, h); drawSlots(c, h, p); return }
        // 路面：左右邊界（偵測到的路緣，否則預設三線道）之間
        val road = p?.optJSONObject("road")
        val el = road?.takeIf { !it.isNull("el") }?.optDouble("el")?.toFloat() ?: -5.3f
        val er = road?.takeIf { !it.isNull("er") }?.optDouble("er")?.toFloat() ?: 5.3f
        path.reset()
        var first = true
        for (zz in listOf(-6f, 80f)) if (proj(el, 0f, zz)) { if (first) path.moveTo(tmp[0], tmp[1]) else path.lineTo(tmp[0], tmp[1]); first = false }
        for (zz in listOf(80f, -6f)) if (proj(er, 0f, zz)) path.lineTo(tmp[0], tmp[1])
        path.close()
        fill.shader = LinearGradient(0f, cy, 0f, h, Color.rgb(16, 24, 38), Color.rgb(28, 40, 58), Shader.TileMode.CLAMP)
        c.drawPath(path, fill); fill.shader = null

        // 斑馬線：貼地的白條
        p?.optJSONArray("cw")?.let { cw ->
            for (i in 0 until cw.length()) {
                val o = cw.optJSONObject(i) ?: continue
                val q = o.optJSONArray("p") ?: continue
                if (q.length() < 8) continue
                val pc = FloatArray(8) { q.optDouble(it).toFloat() }
                fill.color = Color.argb(215, 240, 246, 255)
                for (sq in CrosswalkDetector.stripeQuads(pc, o.optInt("n", 5))) {
                    path.reset(); var okAll = true
                    for (k in 0 until 4) { if (!proj(sq[k * 2], 0f, sq[k * 2 + 1])) { okAll = false; break }; if (k == 0) path.moveTo(tmp[0], tmp[1]) else path.lineTo(tmp[0], tmp[1]) }
                    if (okAll) { path.close(); c.drawPath(path, fill) }
                }
            }
        }
        if (p != null) drawSlots(c, h, p)
        val lines = road?.optJSONArray("lines")
        if (lines == null || lines.length() == 0) {
            // 沒偵測到線：畫預設的淡淡車道線，看得出路
            for (x in floatArrayOf(-1.8f, 1.8f)) drawLine(c, listOf(floatArrayOf(x, -6f), floatArrayOf(x, 70f)), Color.argb(70, 220, 235, 255), dashed = true)
            return
        }
        for (i in 0 until lines.length()) {
            val l = lines.optJSONObject(i) ?: continue
            val col = when (l.optString("k")) { "y" -> Color.rgb(255, 196, 64); "r" -> Color.rgb(255, 70, 70); else -> Color.rgb(235, 245, 255) }
            drawLine(c, lineSamples(l), col, l.optInt("d") == 1)
        }
    }

    /** 車道線畫成貼地的寬條（0.15 m），遠處自然變細；虛線 3 m 畫、6 m 空。 */
    private fun drawLine(c: Canvas, pts: List<FloatArray>, col: Int, dashed: Boolean) {
        if (pts.size < 2) return
        val hw = 0.08f
        for (i in 0 until pts.size - 1) {
            val a = pts[i]; val b = pts[i + 1]
            if (dashed && ((a[1] + 60f) % 9f) > 3f) continue
            path.reset()
            if (!proj(a[0] - hw, 0f, a[1])) continue; path.moveTo(tmp[0], tmp[1])
            if (!proj(b[0] - hw, 0f, b[1])) continue; path.lineTo(tmp[0], tmp[1])
            if (!proj(b[0] + hw, 0f, b[1])) continue; path.lineTo(tmp[0], tmp[1])
            if (!proj(a[0] + hw, 0f, a[1])) continue; path.lineTo(tmp[0], tmp[1]); path.close()
            val fade = (1f - (a[1] / 70f)).coerceIn(0.15f, 1f)
            glow.color = col; glow.alpha = (90 * fade).toInt(); c.drawPath(path, glow)
            fill.color = col; fill.alpha = (255 * fade).toInt(); c.drawPath(path, fill)
        }
        fill.alpha = 255
    }

    // ───── 路徑（變道、轉彎）─────
    private fun drawPaths(c: Canvas, p: JSONObject) {
        p.optJSONObject("lc")?.let { lc ->
            val pts = lc.optJSONArray("pts") ?: return@let
            val ok = lc.optBoolean("ok", true) && lc.optInt("car") < 1
            ribbon(c, pts, 0.92f, if (ok) Color.rgb(56, 225, 255) else Color.rgb(255, 61, 90))
        }
        p.optJSONObject("turn")?.optJSONArray("sw")?.let { sw ->
            path.reset(); var any = false
            for (i in 0 until sw.length()) {
                val q = sw.optJSONArray(i) ?: continue
                if (!proj(q.optDouble(0).toFloat(), 0.02f, q.optDouble(1).toFloat())) continue
                if (!any) { path.moveTo(tmp[0], tmp[1]); any = true } else path.lineTo(tmp[0], tmp[1])
            }
            if (any) {
                path.close()
                val hit = p.optJSONObject("turn")!!.optInt("hit") > 0
                fill.color = if (hit) Color.argb(110, 255, 61, 90) else Color.argb(80, 255, 176, 32); c.drawPath(path, fill)
            }
        }
    }

    private fun ribbon(c: Canvas, pts: JSONArray, hw: Float, col: Int) {
        if (pts.length() < 2) return
        val left = ArrayList<FloatArray>(); val right = ArrayList<FloatArray>()
        for (i in 0 until pts.length()) {
            val q = pts.optJSONArray(i) ?: continue
            val a = pts.optJSONArray(max(0, i - 1)) ?: q; val b = pts.optJSONArray(min(pts.length() - 1, i + 1)) ?: q
            var dx = b.optDouble(0) - a.optDouble(0); var dz = b.optDouble(1) - a.optDouble(1)
            val n = kotlin.math.hypot(dx, dz).coerceAtLeast(1e-6); dx /= n; dz /= n
            val x = q.optDouble(0).toFloat(); val z = q.optDouble(1).toFloat()
            left.add(floatArrayOf(x - (dz * hw).toFloat(), z + (dx * hw).toFloat())); right.add(floatArrayOf(x + (dz * hw).toFloat(), z - (dx * hw).toFloat()))
        }
        path.reset(); var started = false
        for (q in left) if (proj(q[0], 0.03f, q[1])) { if (!started) { path.moveTo(tmp[0], tmp[1]); started = true } else path.lineTo(tmp[0], tmp[1]) }
        for (q in right.asReversed()) if (proj(q[0], 0.03f, q[1])) path.lineTo(tmp[0], tmp[1])
        if (!started) return
        path.close()
        glow.color = col; glow.alpha = 120; c.drawPath(path, glow)
        fill.color = col; fill.alpha = 90; c.drawPath(path, fill)
        stroke.color = col; stroke.alpha = 230; stroke.strokeWidth = 3f; c.drawPath(path, stroke)
        fill.alpha = 255
    }

    // ───── 物件 ─────
    private fun dims(t: String): FloatArray = when (t) {           // 半寬、半長、高、車廂比例
        "bus" -> floatArrayOf(1.25f, 5.5f, 3.1f, 0f)
        "truck" -> floatArrayOf(1.2f, 4.0f, 3.0f, 0f)
        "van" -> floatArrayOf(0.95f, 2.5f, 1.95f, 0f)
        "moto", "bigbike", "scooter" -> floatArrayOf(0.4f, 1.0f, 1.45f, 0f)
        "bike" -> floatArrayOf(0.35f, 0.9f, 1.6f, 0f)
        "ped" -> floatArrayOf(0.28f, 0.28f, 1.7f, 0f)
        "cone" -> floatArrayOf(0.2f, 0.2f, 0.7f, 0f)
        else -> floatArrayOf(0.9f, 2.3f, 1.0f, 0.5f)                // 轎車：車身 1.0 m＋車廂 0.5 m
    }

    private fun colorOf(t: String): Int = when (ObjTypes.group(t)) {
        "veh" -> if (t == "truck" || t == "bus") Color.rgb(170, 140, 255) else Color.rgb(170, 190, 215)
        "two" -> Color.rgb(255, 190, 80)
        "ped" -> Color.rgb(80, 255, 160)
        else -> Color.rgb(255, 140, 60)
    }

    private fun drawObjects(c: Canvas, p: JSONObject, now: Long) {
        val objs = p.optJSONArray("objs") ?: JSONArray()
        val seenNow = HashSet<String>()
        for (i in 0 until objs.length()) {
            val o = objs.optJSONObject(i) ?: continue
            val t = o.optString("t")
            val key = if (o.has("id")) "id" + o.optInt("id") else "p$i$t"
            seenNow.add(key)
            val x = o.optDouble("x").toFloat(); val z = o.optDouble("z").toFloat()
            val yaw = if (o.has("yaw")) o.optDouble("yaw").toFloat() else 0f
            val s = tracks[key]
            if (s == null) tracks[key] = Smooth(x, z, yaw, t, now, id = o.optInt("id", i))
            else { s.x += (x - s.x) * 0.35f; s.z += (z - s.z) * 0.35f; s.yaw += (yaw - s.yaw) * 0.3f; s.t = t; s.seen = now }
        }
        // 淡入淡出，消失 0.6 秒後移除
        val it = tracks.entries.iterator()
        while (it.hasNext()) {
            val e = it.next(); val s = e.value
            s.alpha = if (e.key in seenNow) min(1f, s.alpha + 0.15f) else s.alpha - 0.08f
            if (s.alpha <= 0f && now - s.seen > 600) it.remove()
        }
        // 遠的先畫
        val lca = p.optJSONObject("lca")
        for (s in tracks.values.sortedByDescending { it.z }) {
            val danger = lca != null && ((s.x < -1f && lca.optInt("L") >= 2) || (s.x > 1f && lca.optInt("R") >= 2)) && s.z < 3f
            // 危險（盲點來車）：底下一圈紅光
            if (danger && proj(s.x, 0f, s.z)) {
                val r = (focal * 3f / tmp[2]).coerceAtLeast(6f)
                fill.shader = RadialGradient(tmp[0], tmp[1], r, Color.argb(200, 255, 40, 60), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                c.drawCircle(tmp[0], tmp[1], r, fill); fill.shader = null
            } else if (proj(s.x, 0f, s.z)) {                       // 一般：淡淡的地面陰影
                val r = (focal * 1.6f / tmp[2]).coerceAtLeast(4f)
                fill.shader = RadialGradient(tmp[0], tmp[1], r, Color.argb((110 * s.alpha).toInt(), 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                c.drawCircle(tmp[0], tmp[1], r, fill); fill.shader = null
            }
            if (!drawSprite(c, modelFor(s.t, s.id), s.x, s.z, s.yaw, s.alpha))
                drawBox(c, s.x, s.z, s.yaw, dims(s.t), if (danger) Color.rgb(255, 61, 90) else colorOf(s.t), s.alpha, false)
        }
    }

    /** 有立體感的方塊（車身＋車廂）：只畫看得到的面，頂面亮、側面暗。 */
    private fun drawBox(c: Canvas, x: Float, z: Float, yaw: Float, d: FloatArray, col: Int, alpha: Float, ego: Boolean) {
        val cs = cos(yaw); val sn = sin(yaw)
        fun corner(dx: Float, dz: Float, y: Float): FloatArray? =
            if (proj(x + dx * cs + dz * sn, y, z - dx * sn + dz * cs)) floatArrayOf(tmp[0], tmp[1], tmp[2]) else null
        val hw = d[0]; val hl = d[1]; val bodyH = d[2]
        // 地面陰影／光暈
        if (proj(x, 0f, z)) {
            val r = focal * (hl + 0.6f) / tmp[2]
            fill.shader = RadialGradient(tmp[0], tmp[1], r.coerceAtLeast(4f), if (ego) Color.argb(160, 56, 225, 255) else Color.argb((90 * alpha).toInt(), 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawCircle(tmp[0], tmp[1], r.coerceAtLeast(4f), fill); fill.shader = null
        }
        prism(c, ::corner, hw, hl, 0.12f, bodyH, col, alpha, ego)
        if (d[3] > 0f) prism(c, ::corner, hw * 0.82f, hl * 0.55f, bodyH, bodyH + d[3], col, alpha, ego, cabin = true, back = -hl * 0.12f)
    }

    private fun prism(c: Canvas, corner: (Float, Float, Float) -> FloatArray?, hw: Float, hl: Float, y0: Float, y1: Float,
                      col: Int, alpha: Float, ego: Boolean, cabin: Boolean = false, back: Float = 0f) {
        // 底部四角 a、頂部四角 b：0 左後、1 右後、2 右前、3 左前
        val xs = floatArrayOf(-hw, hw, hw, -hw); val zs = floatArrayOf(-hl + back, -hl + back, hl + back, hl + back)
        val a = arrayOfNulls<FloatArray>(4); val b = arrayOfNulls<FloatArray>(4)
        for (k in 0 until 4) { a[k] = corner(xs[k], zs[k], y0) ?: return; b[k] = corner(xs[k], zs[k], y1) ?: return }
        val r = Color.red(col); val g = Color.green(col); val bl = Color.blue(col)
        fun shade(f: Float, extra: Int = 255) = Color.argb((extra * alpha).toInt().coerceIn(0, 255), (r * f).toInt().coerceAtMost(255), (g * f).toInt().coerceAtMost(255), (bl * f).toInt().coerceAtMost(255))
        // 側面：依螢幕上的面積正負決定看不看得到（背面剔除）
        val faces = listOf(intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 0))
        for ((fi, f) in faces.withIndex()) {
            val p0 = a[f[0]]!!; val p1 = a[f[1]]!!; val q1 = b[f[1]]!!; val q0 = b[f[0]]!!
            val area = (p1[0] - p0[0]) * (q0[1] - p0[1]) - (p1[1] - p0[1]) * (q0[0] - p0[0])
            if (area > 0) continue
            path.reset(); path.moveTo(p0[0], p0[1]); path.lineTo(p1[0], p1[1]); path.lineTo(q1[0], q1[1]); path.lineTo(q0[0], q0[1]); path.close()
            val f0 = when (fi) { 0 -> 0.55f; 2 -> 0.7f; else -> 0.45f } * (if (cabin) 0.75f else 1f)
            fill.color = shade(f0, if (cabin) 210 else 255); c.drawPath(path, fill)
        }
        // 頂面
        path.reset(); path.moveTo(b[0]!![0], b[0]!![1]); for (k in 1 until 4) path.lineTo(b[k]!![0], b[k]!![1]); path.close()
        fill.color = shade(if (cabin) 0.6f else 1f, if (cabin) 200 else 255); c.drawPath(path, fill)
        if (ego || !cabin) {
            stroke.color = if (ego) Color.argb(255, 140, 240, 255) else shade(1.25f, 200); stroke.strokeWidth = if (ego) 2.5f else 1.5f
            c.drawPath(path, stroke)
        }
        // 尾燈（後面那一面看得到才畫）
        if (!cabin) {
            val l0 = corner(-hw * 0.85f, -hl + back, y1 * 0.7f); val l1 = corner(hw * 0.85f, -hl + back, y1 * 0.7f)
            if (l0 != null && l1 != null && l0[1] > 0) {
                val brake = ego && egoBrake
                glow.color = if (brake) Color.rgb(255, 40, 40) else Color.rgb(200, 30, 40); glow.alpha = ((if (brake) 255 else 120) * alpha).toInt()
                val rr = (focal * 0.18f / l0[2]).coerceAtLeast(2f)
                c.drawCircle(l0[0], l0[1], rr, glow); c.drawCircle(l1[0], l1[1], rr, glow)
            }
        }
    }

    // ───── 自車 ─────
    private var egoBrake = false
    private fun drawEgo(c: Canvas, p: JSONObject?, now: Long) {
        val veh = p?.optJSONObject("veh")
        egoBrake = CanVehicle.brake() == true
        // 自車底下的光圈
        if (proj(0f, 0f, 0f)) {
            val r = focal * 3.4f / tmp[2]
            fill.shader = RadialGradient(tmp[0], tmp[1], r, Color.argb(150, 56, 225, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawCircle(tmp[0], tmp[1], r, fill); fill.shader = null
        }
        val haveSprite = drawSprite(c, "e60", 0f, 0f, 0f, 1f) && sprite("e60") != null
        if (!haveSprite)
            drawBox(c, 0f, 0f, 0f, floatArrayOf(0.93f, 2.42f, 0.95f, 0.5f), Color.rgb(225, 240, 255), 1f, true)
        // 燈的位置（模型座標，跟中控台 carfx.js 一樣：x 往車的左邊）：照車子圖的投影畫，才會在車燈上
        fun lamp(mx: Float, my: Float, mz: Float, facing: Float, col: Int, r: Float) {
            val pt = if (haveSprite) onSprite(mx, my, mz, facing) else if (proj(-mx, my, mz)) floatArrayOf(tmp[0], tmp[1]) else null
            pt ?: return
            glow.color = col; glow.alpha = 255
            c.drawCircle(pt[0], pt[1], (lastSize * r).coerceAtLeast(3f), glow)
        }
        // 煞車燈：車尾兩點＋第三煞車燈
        if (egoBrake) {
            val red = Color.rgb(255, 30, 30)
            lamp(0.6f, 0.78f, -2.38f, -1f, red, 0.045f); lamp(-0.6f, 0.78f, -2.38f, -1f, red, 0.045f); lamp(0f, 1.12f, -1.95f, -1f, red, 0.03f)
        }
        // 方向燈：前、側（葉子板）、後，打哪邊閃哪邊；雙閃兩邊
        val turn = VehicleBus.signals.snapshot().turn?.toString()
        val on = (now / 400) % 2 == 0L
        if (on && (turn == "L" || turn == "R" || turn == "H")) {
            val amb = Color.rgb(255, 176, 32)
            for (side in listOf(1f, -1f)) {                                  // +1 = 車的左邊
                if (turn == "L" && side < 0) continue; if (turn == "R" && side > 0) continue
                lamp(0.74f * side, 0.82f, -2.3f, -1f, amb, 0.04f)
                lamp(0.72f * side, 0.62f, 2.25f, 1f, amb, 0.04f)
                lamp(0.96f * side, 0.78f, 1.55f, 0f, amb, 0.025f)
            }
        }
    }

    // ───── 資訊 ─────
    private fun drawHud(c: Canvas, w: Float, h: Float, p: JSONObject?, now: Long) {
        val vs = VehicleBus.signals.snapshot()
        val pad = h * 0.04f
        // 速度
        text.textAlign = Paint.Align.LEFT
        text.textSize = h * 0.13f; text.color = Color.WHITE
        text.setShadowLayer(18f, 0f, 0f, Color.argb(160, 56, 225, 255))
        c.drawText("%.0f".format(vs.speedKmh ?: 0f), pad, pad + text.textSize, text)
        val sw = text.measureText("%.0f".format(vs.speedKmh ?: 0f))
        text.textSize = h * 0.035f; text.color = Color.argb(200, 160, 190, 220); text.clearShadowLayer()
        c.drawText("km/h", pad + sw + 10f, pad + h * 0.13f, text)
        // 檔位
        val gear = (CanVehicle.info.gear ?: vs.gear ?: 'D').toString()
        text.textSize = h * 0.06f; text.color = if (gear == "R") Color.rgb(255, 61, 90) else Color.rgb(56, 225, 255)
        c.drawText(gear, pad, pad + h * 0.22f, text)
        // 紅綠燈
        p?.optJSONObject("tl")?.optString("s")?.takeIf { it == "red" || it == "green" || it == "yellow" }?.let { s ->
            val col = when (s) { "red" -> Color.rgb(255, 50, 50); "green" -> Color.rgb(60, 255, 120); else -> Color.rgb(255, 200, 50) }
            glow.color = col; glow.alpha = 255; c.drawCircle(w - pad - h * 0.04f, pad + h * 0.05f, h * 0.035f, glow)
            fill.color = col; c.drawCircle(w - pad - h * 0.04f, pad + h * 0.05f, h * 0.025f, fill)
        }
        // 警示（最多兩則，紅色在前）
        val aids = p?.optJSONArray("aids")
        if (aids != null && aids.length() > 0) {
            val list = (0 until aids.length()).mapNotNull { aids.optJSONObject(it) }.sortedByDescending { it.optInt("l") }.take(2)
            text.textAlign = Paint.Align.CENTER; text.textSize = h * 0.045f
            for ((k, m) in list.withIndex()) {
                val col = when (m.optInt("l")) { 2 -> Color.rgb(255, 61, 90); 1 -> Color.rgb(255, 176, 32); else -> Color.rgb(56, 225, 255) }
                val y = pad + h * 0.06f + k * h * 0.075f
                val tw = text.measureText(m.optString("t")) / 2 + h * 0.03f
                fill.color = Color.argb(150, Color.red(col) / 4, Color.green(col) / 4, Color.blue(col) / 4)
                c.drawRoundRect(w / 2 - tw, y - h * 0.045f, w / 2 + tw, y + h * 0.017f, h * 0.03f, h * 0.03f, fill)
                text.color = col; c.drawText(m.optString("t"), w / 2, y, text)
            }
        }
        // 停車場：右上角標「停車場・空位 N 格」
        if (p?.optInt("lot") == 1) {
            val sl = p.optJSONArray("slots")
            val free = if (sl == null) 0 else (0 until sl.length()).count { sl.optJSONObject(it)?.let { o -> o.optInt("o") == 0 && o.optInt("own") == 0 } == true }
            val t = "停車場・空位 $free 格"
            text.textAlign = Paint.Align.RIGHT; text.textSize = h * 0.045f
            val tw = text.measureText(t)
            val x1 = w - pad - (if (p.optJSONObject("tl") != null) h * 0.1f else 0f); val y = pad + h * 0.06f
            fill.color = Color.argb(170, 10, 40, 50); c.drawRoundRect(x1 - tw - h * 0.03f, y - h * 0.05f, x1 + h * 0.015f, y + h * 0.018f, h * 0.03f, h * 0.03f, fill)
            text.color = Color.rgb(40, 225, 255); c.drawText(t, x1, y, text)
        }
        if (p == null) {
            text.textAlign = Paint.Align.CENTER; text.textSize = h * 0.035f; text.color = Color.argb(180, 160, 190, 220)
            c.drawText("等待偵測資料…", w / 2, h * 0.92f, text)
        }
    }
}
