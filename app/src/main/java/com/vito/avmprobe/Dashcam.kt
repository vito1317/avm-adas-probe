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
 * - 煞車：車機訊號沒有煞車，用車速急降推估（減速度 > [BRAKE_DECEL]），標「推估」。
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
        r.frame({ c -> compose(c, bb, fb, mpp, ox, oy, pl) }, { subtitle(pl) })
    }

    // ───────────────────────── 畫面合成 ─────────────────────────

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 26f; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 54f; isFakeBoldText = true; setShadowLayer(5f, 0f, 0f, Color.BLACK) }
    private val box = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private fun compose(c: Canvas, bev: Bitmap, front: Bitmap, mpp: Float, ox: Float, oy: Float, p: JSONObject?) {
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
        // 俯視圖上的標注（對位是精確的：就是我們自己的俯視圖）
        val top = AnnotationMapper.Params(
            clipL = 0f, clipT = 0f, clipR = bw.toFloat() / W, clipB = 1f,
            cx = ox * k / W, cy = oy * k / H, scale = (k / mpp) / H, flip = false
        )
        painter?.paintRecording(c, W, H, top, p)
        // 自車
        val pm = top.scale * H
        val sprite = appCtx?.let { EgoSprite.get(it) }
        if (sprite != null) {
            // 原廠俯視圖上截下來的車模
            c.drawBitmap(sprite, null, RectF(ox * k - EgoSprite.HW * pm, oy * k - EgoSprite.HL * pm,
                ox * k + EgoSprite.HW * pm, oy * k + EgoSprite.HL * pm), paint)
        } else {
            box.color = Color.argb(200, 230, 230, 230)
            c.drawRoundRect(RectF(ox * k - 0.925f * pm, oy * k - 2.42f * pm, ox * k + 0.925f * pm, oy * k + 2.42f * pm), 10f, 10f, box)
        }
        // 角落：時間、車速、方向燈、檔位、煞車、錄影中
        val veh = p?.optJSONObject("veh")
        box.color = Color.argb(150, 0, 0, 0)
        c.drawRoundRect(RectF(bw + 12f, H - 120f, bw + 470f, H - 12f), 14f, 14f, box)
        c.drawText(clock.format(Date()), bw + 26f, H - 84f, txt)
        val spd = veh?.takeIf { !it.isNull("speed") }?.optDouble("speed")
        c.drawText(if (spd != null) "%.0f km/h".format(spd) else "-- km/h", bw + 26f, H - 28f, big)
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
            c.drawText("煞車（推估）", bw + 22f, H - 140f, txt)
        }
        box.color = Color.rgb(230, 30, 30)
        c.drawCircle(W - 30f, 30f, 10f, box)
        c.drawText("REC", W - 95f, 39f, txt)
        if (SystemClock.elapsedRealtime() - lastImpactAt < IMPACT_HOLD_MS) c.drawText("撞擊！已鎖定", W - 260f, 75f, txt)
    }

    /** 字幕：車速、方向燈、煞車、最近的車、警示。 */
    private fun subtitle(p: JSONObject?): String {
        val sb = StringBuilder()
        val veh = p?.optJSONObject("veh")
        val spd = veh?.takeIf { !it.isNull("speed") }?.optDouble("speed")
        sb.append(if (spd != null) "%.0f km/h".format(spd) else "-- km/h")
        when (veh?.optString("turn")) { "L" -> sb.append("  左轉燈"); "R" -> sb.append("  右轉燈"); "H" -> sb.append("  雙黃燈") }
        if (veh?.optString("gear") == "R") sb.append("  倒車")
        if (braking) sb.append("  煞車(推估)")
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

    private fun updateBrake() {
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
