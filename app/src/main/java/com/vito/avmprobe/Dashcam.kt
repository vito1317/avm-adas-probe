package com.vito.avmprobe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 行車記錄器（合併版）：發動就循環錄影，錄的是**我們自己合成的 360 畫面**：
 * 左邊俯視圖（含車輛框／距離、停車格、各種預測走廊），右邊前方去畸變畫面，
 * 上方警示列，角落時間、車速、方向燈、檔位、煞車。不管原廠 360 畫面有沒有開都能錄。
 *
 * - 字幕（.srt）每秒一行：車速、方向燈、煞車、最近的車距離、警示。
 * - 煞車：有 CAN 煞車訊號就用（標「煞車」）；沒有才用車速急降推估（減速度 > [BRAKE_DECEL]），標「推估」。
 * - 撞擊：車機的加速度感測器偵測到瞬間衝擊（> [IMPACT_G]）→ 這段跟上一段鎖定，不會被循環刪除。
 */
object Dashcam {
    private const val TAG = "Dashcam"
    private const val BRAKE_DECEL = 2.5f      // m/s²（約 0.25 g）
    private const val IMPACT_G = 0.8f         // 扣掉重力後的瞬間加速度（g）
    private const val IMPACT_HOLD_MS = 10_000L

    @Volatile private var rec: DashcamRecorder? = null
    @Volatile private var painter: AnnotationView? = null
    private var bevBmp: Bitmap? = null
    private var frontBmp: Bitmap? = null
    private var bevArr = IntArray(0)
    private var frontArr = IntArray(0)
    private const val FRONT = 416
    @Volatile private var payload: JSONObject? = null
    private var sensorsOn = false
    @Volatile private var lastImpactAt = 0L

    // 煞車推估：車速歷史
    private val spdT = LongArray(16); private val spdV = FloatArray(16); private var spdN = 0
    @Volatile var braking = false; private set

    val recorder: DashcamRecorder? get() = rec
    @Volatile private var appCtx: Context? = null

    fun start(ctx: Context, prefs: AppPrefs) {
        if (!prefs.dashcamOn) return
        val app = ctx.applicationContext
        appCtx = app
        if (rec == null) {
            rec = DashcamRecorder(app) { prefs.dashcamQuotaGb.toLong() * 1024 * 1024 * 1024 }
            painter = AnnotationView(app)
        }
        rec?.start()
        startSensors(app)
        Engine360.log("行車記錄器啟動：${rec?.dir?.absolutePath}")
    }

    fun stop() { rec?.stop() }

    fun lock() { rec?.lock(); Engine360.log("行車記錄：已鎖定這段影片") }

    /** 偵測管線每一幀送出結果之後（影格執行緒）。 */
    fun onFrame(p: AdasPipeline, latest: JSONObject?) {
        val r = rec ?: return
        if (!r.running) return
        payload = latest
        updateBrake()
        val bw = p.bevWidth; val bh = p.bevHeight
        if (bw <= 0) return
        if (bevArr.size != bw * bh) { bevArr = IntArray(bw * bh); bevBmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888) }
        if (frontArr.size != FRONT * FRONT) { frontArr = IntArray(FRONT * FRONT); frontBmp = Bitmap.createBitmap(FRONT, FRONT, Bitmap.Config.ARGB_8888) }
        if (!p.renderBevInto(bevArr) || !p.renderFrontInto(frontArr, FRONT)) return
        val bb = bevBmp!!; val fb = frontBmp!!
        bb.setPixels(bevArr, 0, bw, 0, 0, bw, bh)
        fb.setPixels(frontArr, 0, FRONT, 0, 0, FRONT, FRONT)
        val mpp = p.bevMpp; val ox = p.bevOriginX; val oy = p.bevOriginY
        val pl = latest
        val egoO = p.recBowlOrigin; val egoP = p.recBowlParams; val bowlV = p.recBowl
        r.frame({ c -> compose(c, bb, fb, mpp, ox, oy, pl, egoO, egoP, bowlV) }, { subtitle(pl) })
    }

    // ───────────────────────── 畫面合成 ─────────────────────────

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 26f; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 54f; isFakeBoldText = true; setShadowLayer(5f, 0f, 0f, Color.BLACK) }
    private val box = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private fun compose(c: Canvas, bev: Bitmap, front: Bitmap, mpp: Float, ox: Float, oy: Float, p: JSONObject?,
                        egoO: FloatArray? = null, egoP: PerspectiveMapper.Params? = null, bowlV: BowlView? = null) {
        val W = DashcamRecorder.W; val H = DashcamRecorder.H
        c.drawColor(Color.BLACK)
        // 左：俯視圖，高 720
        val k = H.toFloat() / bev.height
        val bw = (bev.width * k).toInt()
        c.drawBitmap(bev, null, Rect(0, 0, bw, H), paint)
        // 右：前方畫面（正方形，左右裁掉一點塞進剩下的寬）
        val rw = W - bw
        val srcW = (front.width * rw.toFloat() / H).toInt().coerceAtMost(front.width)
        val sx = (front.width - srcW) / 2
        c.drawBitmap(front, Rect(sx, 0, sx + srcW, front.height), Rect(bw, 0, W, H), paint)
        // 3D 轉彎：路口轉彎的掃過範圍（外側車頭角、內側後輪虛線），跟 360 畫面同一份路徑，投影到重建的 3D 視角；畫在車模下面
        if (bowlV != null) p?.optJSONObject("turn")?.let { t ->
            val k2 = H.toFloat() / front.height
            c.save(); c.clipRect(bw, 0, W, H)
            drawTurn3D(c, bowlV, t) { pt -> floatArrayOf(bw + (pt[0] - sx) * k2, pt[1] * k2) }
            c.restore()
        }
        // 右半邊是重建的原廠 3D 視角：在自車位置貼 E60（挑最接近這個視角的渲染圖）
        val app0 = appCtx
        if (egoO != null && egoP != null && app0 != null) {
            val k2 = H.toFloat() / front.height
            c.save(); c.clipRect(bw, 0, W, H)
            val wheelDeg = VehicleBus.signals.snapshot().steerDeg?.let { (it / VehicleGeometry().steerRatio).toFloat().coerceIn(-35f, 35f) } ?: 0f
            EgoViews.draw(app0, c, bw + (egoO[0] - sx) * k2, egoO[1] * k2, egoO[2] * k2, egoP.azDeg, egoP.elDeg, egoP.mirror, wheelDeg)
            c.restore()
        }
        // 動態輔助線（倒車或慢速）：照方向盤角度算車輪會走的弧線，投影到同一個 3D 視角
        if (bowlV != null) {
            val k2 = H.toFloat() / front.height
            c.save(); c.clipRect(bw, 0, W, H)
            drawGuides(c, bowlV) { pt -> floatArrayOf(bw + (pt[0] - sx) * k2, pt[1] * k2) }
            c.restore()
        }
        // 俯視圖上的標注（對位是精確的：就是我們自己的俯視圖）
        val top = AnnotationMapper.Params(
            clipL = 0f, clipT = 0f, clipR = bw.toFloat() / W, clipB = 1f,
            cx = ox * k / W, cy = oy * k / H, scale = (k / mpp) / H, flip = false
        )
        painter?.paintRecording(c, W, H, top, p)
        // 自車
        val pm = top.scale * H
        // 自車：直接畫向量車模（以前從原廠俯視圖截下來貼，邊緣帶到地面、比例怪）；煞車尾燈亮、方向燈閃
        val vehNow = p?.optJSONObject("veh")
        val blinkOn = (SystemClock.elapsedRealtime() / 400L) % 2L == 0L
        EgoTopCar.draw(c, ox * k, oy * k, pm, braking, vehNow?.optString("turn"), blinkOn, appCtx)
        // 角落：時間、車速、方向燈、檔位、煞車、錄影中
        val veh = p?.optJSONObject("veh")
        box.color = Color.argb(150, 0, 0, 0)
        c.drawRoundRect(RectF(bw + 12f, H - 120f, bw + 470f, H - 12f), 14f, 14f, box)
        c.drawText(clock.format(Date()), bw + 26f, H - 84f, txt)
        val spd = veh?.takeIf { !it.isNull("speed") }?.optDouble("speed")
        c.drawText(if (spd != null) "%.0f km/h".format(spd) else "-- km/h", bw + 26f, H - 28f, big)
        // 車速來源：CAN（車上的真車速）以外都是 GPS 換算的（原廠 360 的車速也是 GPS），標出來才知道準不準
        if (spd != null) c.drawText(if (CanCarInfo.fresh()) "CAN" else "GPS", bw + 26f, H - 92f, txt)
        var x = bw + 290f
        val turn = veh?.optString("turn")
        if (turn == "L" || turn == "H") { box.color = Color.rgb(60, 220, 90); c.drawText("◀", x, H - 30f, big.apply { color = Color.rgb(60, 220, 90) }); }
        x += 52f
        if (turn == "R" || turn == "H") c.drawText("▶", x, H - 30f, big.apply { color = Color.rgb(60, 220, 90) })
        big.color = Color.WHITE
        if (veh?.optString("gear") == "R") c.drawText("R", bw + 420f, H - 28f, big.apply { color = Color.rgb(255, 200, 60) })
        big.color = Color.WHITE
        if (braking) {
            box.color = Color.rgb(220, 30, 30)
            c.drawRoundRect(RectF(bw + 12f, H - 170f, bw + 210f, H - 128f), 10f, 10f, box)
            c.drawText(if (brakeReal) "煞車" else "煞車（推估）", bw + 22f, H - 140f, txt)
        }
        box.color = Color.rgb(230, 30, 30)
        c.drawCircle(W - 30f, 30f, 10f, box)
        c.drawText("REC", W - 95f, 39f, txt)
        if (SystemClock.elapsedRealtime() - lastImpactAt < IMPACT_HOLD_MS) c.drawText("撞擊！已鎖定", W - 260f, 75f, txt)
    }

    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND }

    /** 動態輔助線：兩條輪跡（自車寬）沿方向盤決定的圓弧往行進方向延伸 4 m，0.5 m 內紅、1.5 m 內黃、其餘綠。 */
    private val turnFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val turnLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; strokeJoin = Paint.Join.ROUND }
    private val turnDash = android.graphics.DashPathEffect(floatArrayOf(18f, 12f), 0f)
    private val turnPath = android.graphics.Path()

    /** 轉彎路徑（payload "turn"：o 外側、i 內側後輪、sw 掃過多邊形，x 右 z 前、原點車中心）投影到 3D 視角。 */
    private fun drawTurn3D(c: Canvas, bv: BowlView, t: JSONObject, toCanvas: (FloatArray) -> FloatArray) {
        fun pts(key: String): List<FloatArray> {
            val a = t.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { q -> bv.projectGround(q.optDouble(0), q.optDouble(1))?.let(toCanvas) } }
        }
        val col = when { t.optInt("hit") > 0 -> Color.rgb(255, 70, 60); t.optInt("est") == 1 -> Color.rgb(90, 200, 230); else -> Color.rgb(255, 200, 60) }
        fun poly(ps: List<FloatArray>, close: Boolean) {
            turnPath.reset(); ps.forEachIndexed { i, q -> if (i == 0) turnPath.moveTo(q[0], q[1]) else turnPath.lineTo(q[0], q[1]) }
            if (close) turnPath.close()
        }
        val sw = pts("sw")
        if (sw.size >= 3) { poly(sw, true); turnFill.color = col; turnFill.alpha = 70; c.drawPath(turnPath, turnFill) }
        turnLine.color = col; turnLine.pathEffect = null
        pts("o").takeIf { it.size >= 2 }?.let { poly(it, false); c.drawPath(turnPath, turnLine) }
        turnLine.pathEffect = turnDash
        pts("i").takeIf { it.size >= 2 }?.let { poly(it, false); c.drawPath(turnPath, turnLine) }
        turnLine.pathEffect = null
    }

    private fun drawGuides(c: Canvas, bv: BowlView, toCanvas: (FloatArray) -> FloatArray) {
        val vs = VehicleBus.signals.snapshot()
        val rev = (CanVehicle.info.gear ?: vs.gear) == 'R'
        if (!rev && (vs.speedKmh ?: 99f) > 15f) return
        val geo = VehicleGeometry()
        val steer = vs.steerDeg ?: 0f
        val wheel = Math.toRadians((steer / geo.steerRatio).coerceIn(-35.0, 35.0))
        val dir = if (rev) -1.0 else 1.0
        // 後軸中心（z = −軸距一半附近）當旋轉基準；前進看前方、倒車看後方
        val axleZ = -geo.wheelbase / 2
        val startZ = if (rev) -geo.length / 2 else geo.length / 2
        val hw = geo.width / 2
        for (side in doubleArrayOf(-hw, hw)) {
            var prev: FloatArray? = null
            for (k in 0..40) {
                val s = k * 0.1                                     // 沿路徑走了幾公尺
                val (x, z) = if (kotlin.math.abs(wheel) < 1e-3) Pair(side, startZ + dir * s) else {
                    val rad = geo.wheelbase / kotlin.math.tan(wheel)    // + = 往右轉
                    val ang = dir * (s + (startZ - axleZ) * dir) / rad  // 從後軸算的轉角
                    val rr = rad - side
                    Pair(rad - rr * kotlin.math.cos(ang), axleZ + rr * kotlin.math.sin(ang))
                }
                val pt = bv.projectGround(x, z)?.let(toCanvas)
                if (pt != null && prev != null) {
                    guide.color = when { s < 0.5 -> Color.rgb(255, 60, 60); s < 1.5 -> Color.rgb(255, 210, 40); else -> Color.rgb(60, 220, 120) }
                    c.drawLine(prev[0], prev[1], pt[0], pt[1], guide)
                }
                prev = pt
            }
        }
    }

    /** 字幕：車速、方向燈、煞車、最近的車、警示。 */
    private fun subtitle(p: JSONObject?): String {
        val sb = StringBuilder()
        val veh = p?.optJSONObject("veh")
        val spd = veh?.takeIf { !it.isNull("speed") }?.optDouble("speed")
        sb.append(if (spd != null) "%.0f km/h".format(spd) else "-- km/h")
        if (spd != null) sb.append(if (CanCarInfo.fresh()) "(CAN)" else "(GPS)")
        when (veh?.optString("turn")) { "L" -> sb.append("  左轉燈"); "R" -> sb.append("  右轉燈"); "H" -> sb.append("  雙黃燈") }
        if (veh?.optString("gear") == "R") sb.append("  倒車")
        if (braking) sb.append(if (brakeReal) "  煞車" else "  煞車(推估)")
        CanVehicle.info.takeIf { CanVehicle.fresh() }?.let { i ->
            i.rpm?.let { sb.append("  ${it}rpm") }; i.gear?.let { if (it != 'R') sb.append("  $it 檔") }
            if (i.highBeam == true) sb.append("  遠光"); if (i.fogFront == true || i.fogRear == true) sb.append("  霧燈")
        }
        p?.optJSONArray("objs")?.let { a ->
            var best = Float.MAX_VALUE; var bt = ""
            for (i in 0 until a.length()) a.optJSONObject(i)?.let {
                val d = kotlin.math.hypot(it.optDouble("x").toFloat(), it.optDouble("z").toFloat())
                if (d < best) { best = d; bt = it.optString("t") }
            }
            if (best < 50f) sb.append("  最近：$bt %.1fm".format(best))
            sb.append("  偵測 ${a.length()}")
        }
        p?.optJSONObject("lc")?.optString("ban")?.takeIf { it.isNotEmpty() }?.let { sb.append("  $it") }
        p?.optJSONObject("start")?.let { if (it.optBoolean("lead")) sb.append("  前車起步") }
        p?.optJSONObject("tl")?.optString("s")?.takeIf { it.isNotEmpty() && it != "off" }?.let { sb.append("  號誌:$it") }
        if (SystemClock.elapsedRealtime() - lastImpactAt < IMPACT_HOLD_MS) sb.append("  撞擊")
        return sb.toString()
    }

    // ───────────────────────── 煞車推估、撞擊 ─────────────────────────

    @Volatile private var brakeReal = false

    private fun updateBrake() {
        // 車機 CAN 有真的煞車訊號就用它
        CanVehicle.brake()?.let { braking = it; brakeReal = true; return }
        brakeReal = false
        val v = VehicleBus.signals.snapshot().speedKmh ?: run { braking = false; return }
        val now = SystemClock.elapsedRealtime()
        if (spdN > 0 && spdV[(spdN - 1) % 16] == v / 3.6f && now - spdT[(spdN - 1) % 16] < 300) return
        spdT[spdN % 16] = now; spdV[spdN % 16] = v / 3.6f; spdN++
        // 跟約 1 秒前比
        var i = spdN - 2
        while (i >= 0 && i > spdN - 16 && now - spdT[i % 16] < 800) i--
        if (i < 0 || i <= spdN - 16) { braking = false; return }
        val dt = (now - spdT[i % 16]) / 1000f
        if (dt <= 0.3f || dt > 3f) { braking = false; return }
        braking = (spdV[i % 16] - v / 3.6f) / dt > BRAKE_DECEL && v > 2f
    }

    private fun startSensors(ctx: Context) {
        if (sensorsOn) return
        sensorsOn = true
        try {
            val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: run { Engine360.log("沒有加速度感測器：撞擊鎖定不可用"); return }
            val g = FloatArray(3); var init = false
            sm.registerListener(object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    if (!init) { for (k in 0..2) g[k] = e.values[k]; init = true; return }
                    // 低通得到重力方向，扣掉之後是車的加速度
                    var m2 = 0f
                    for (k in 0..2) { g[k] = 0.95f * g[k] + 0.05f * e.values[k]; val a = e.values[k] - g[k]; m2 += a * a }
                    val gs = sqrt(m2) / SensorManager.GRAVITY_EARTH
                    val now = SystemClock.elapsedRealtime()
                    if (gs > IMPACT_G && now - lastImpactAt > IMPACT_HOLD_MS) {
                        lastImpactAt = now
                        rec?.lock()
                        Engine360.log("撞擊 %.2f g：已鎖定這段影片".format(gs))
                    }
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }, acc, SensorManager.SENSOR_DELAY_GAME)
        } catch (t: Throwable) { Log.e(TAG, "sensors", t) }
    }
}
