package com.vito.avmprobe

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * 從車子「正在轉多快」推估道路彎度：彎度 = 轉向角速度 ÷ 車速（1/m，+ = 往右彎）。
 *
 * 為什麼：這台車的前鏡頭裝得很低（約 0.3 m），10 m 外的地面離地平線只有 1～2°，校正差 2° 遠處就整個投影到天上，
 * 用畫面量不到前方道路的彎度；俯視圖可靠範圍又只有車旁約 3 m（實車 t8：右彎抓到的全是直線段）。
 * 道路彎度是連續的，車子正沿著彎道開時，前方一小段的彎度跟現在差不多。
 *
 * 來源：陀螺儀（投影到重力方向，車機斜著裝也沒關係）優先，沒有就用 GPS 行進方向的變化。方向盤讀值不可靠，不用。
 */
object MotionCurvature {

    /** 純計算核心（可單元測試）。 */
    class Core {
        private val g = DoubleArray(3)        // 低通後的重力方向（加速度計靜止時讀到的是 +g 往上）
        private var gOk = false
        private var yaw = 0.0                  // 繞「上」軸的角速度（rad/s，+ = 逆時針 = 往左），EMA
        var yawAt = 0L; private set
        private var lastBearing = Double.NaN; private var lastBearingAt = 0L
        var bearingRate = 0.0; private set     // GPS：rad/s，+ = 往右
        var bearingAt = 0L; private set

        fun onAccel(x: Float, y: Float, z: Float) {
            val a = 0.05
            if (!gOk) { g[0] = x.toDouble(); g[1] = y.toDouble(); g[2] = z.toDouble(); gOk = true }
            else { g[0] += a * (x - g[0]); g[1] += a * (y - g[1]); g[2] += a * (z - g[2]) }
        }

        fun onGyro(now: Long, x: Float, y: Float, z: Float) {
            if (!gOk) return
            val n = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]); if (n < 5) return
            val w = (x * g[0] + y * g[1] + z * g[2]) / n
            yaw += 0.15 * (w - yaw)
            yawAt = now
        }

        /** GPS 行進方向（度，0 = 北、順時針）；車速太慢方向會亂跳，呼叫端只在 > 10 km/h 時給。 */
        fun onBearing(now: Long, deg: Double) {
            if (!lastBearing.isNaN() && now > lastBearingAt) {
                var d = deg - lastBearing
                while (d > 180) d -= 360; while (d < -180) d += 360
                val dt = (now - lastBearingAt) / 1000.0
                if (dt in 0.3..3.0) { bearingRate += 0.5 * (Math.toRadians(d) / dt - bearingRate); bearingAt = now }
            }
            lastBearing = deg; lastBearingAt = now
        }

        /** 目前的路徑彎度（1/m，+ = 往右）；資料太舊或太慢回 null。 */
        fun curvature(now: Long, speedKmh: Float?): Double? {
            val v = (speedKmh ?: return null) / 3.6
            if (v < 2.5) return null
            if (now - yawAt < 500) return -yaw / v          // 逆時針（往左）為正 → 換成往右為正
            if (now - bearingAt < 2500) return bearingRate / v
            return null
        }
    }

    private val core = Core()
    @Volatile var source: String = "沒有"
        private set
    private var started = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        try {
            val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (gyro != null && acc != null) {
                val l = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        synchronized(core) {
                            if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) core.onAccel(e.values[0], e.values[1], e.values[2])
                            else core.onGyro(SystemClock.elapsedRealtime(), e.values[0], e.values[1], e.values[2])
                        }
                    }
                    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                }
                sm.registerListener(l, acc, SensorManager.SENSOR_DELAY_GAME)
                sm.registerListener(l, gyro, SensorManager.SENSOR_DELAY_GAME)
                source = "陀螺儀"
            } else source = "GPS 行進方向（沒有陀螺儀）"
            Engine360.log("道路彎度來源：$source")
        } catch (t: Throwable) { Engine360.log("陀螺儀不能用：$t") }
    }

    /** FactoryVehicle 收到 GPS 時餵進來（有行進方向、車速 > 10 km/h）。 */
    fun onGps(bearingDeg: Float, speedKmh: Float) {
        if (speedKmh < 10f) return
        synchronized(core) { core.onBearing(SystemClock.elapsedRealtime(), bearingDeg.toDouble()) }
    }

    fun curvature(speedKmh: Float?): Double? = synchronized(core) { core.curvature(SystemClock.elapsedRealtime(), speedKmh) }

}
