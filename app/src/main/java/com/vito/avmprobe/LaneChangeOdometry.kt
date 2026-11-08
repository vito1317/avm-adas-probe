package com.vito.avmprobe

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * 偵測不到車道線時，用車速＋方向盤角度推算變道過程中車子往旁邊移了多少（自行車模型），
 * 讓變道預測線跟著走（實車：車道線偵測不穩時，預測線一直卡在打燈那一刻的位置）。
 *
 * - 打燈那一刻當原點：目標車道中心在打燈那側一個車道寬處、跟當時車頭平行。
 * - 每幀：航向 θ += v·tan(前輪角)/軸距·dt，橫移 y += v·sin θ·dt（+ = 右）。
 * - 方向盤零點（擺正時讀到的值）會偏：在高速、沒打燈時慢慢平均出來扣掉，不然幾秒就漂掉。
 * 純邏輯，可單元測試。
 */
class LaneChangeOdometry(private val geo: VehicleGeometry = VehicleGeometry()) {

    companion object {
        const val ZERO_MIN_KMH = 30f     // 這個速度以上、沒打燈時拿方向盤值估零點
        const val ZERO_TAU_S = 20.0      // 零點平均的時間常數
        const val ARRIVED_M = 0.35f      // 離目標車道中心這麼近 = 變道完成
    }

    private var zero = Double.NaN
    private var dir = 0
    /** 打燈後推算的橫移（m，+ = 右）。 */
    var y = 0.0; private set
    private var th = 0.0
    private var lastAt = 0L

    /** 打燈之前、平常開車時每幀呼叫，估方向盤零點。 */
    fun learnZero(speedKmh: Float?, steerDeg: Float?, dtS: Double) {
        if (speedKmh == null || steerDeg == null || speedKmh < ZERO_MIN_KMH || abs(steerDeg) > 60f) return
        zero = if (zero.isNaN()) steerDeg.toDouble() else zero + (steerDeg - zero) * (dtS / ZERO_TAU_S).coerceIn(0.0, 1.0)
    }

    /** 這次打燈已經推算到目標車道了。 */
    var done = false
        private set

    fun reset() { dir = 0; y = 0.0; th = 0.0; lastAt = 0L; done = false }

    /**
     * 打燈中每幀呼叫。回傳 (目標車道中心的橫向位置 targetX, 車道方向角 phi)，給 [LaneChangeAssist.lanePath]；
     * 已經到了目標車道回 null（不用再畫）。沒有車速或方向盤 → null。
     */
    fun update(now: Long, turn: Char?, speedKmh: Float?, steerDeg: Float?, laneW: Float, yawRate: Double? = null): Pair<Double, Double>? {
        val d = when (turn) { 'L' -> -1; 'R' -> 1; else -> { reset(); return null } }
        if (d != dir) { reset(); dir = d }
        val dt = if (lastAt == 0L) 0.0 else ((now - lastAt).coerceIn(0, 300)) / 1000.0
        lastAt = now
        if (speedKmh == null || (steerDeg == null && yawRate == null)) return null
        val v = speedKmh / 3.6
        if (steerDeg != null) {
            val st = steerDeg - (if (zero.isNaN()) 0.0 else zero)
            val wheel = Math.toRadians((st / geo.steerRatio).coerceIn(-geo.maxWheelDeg, geo.maxWheelDeg))
            th += v * tan(wheel) / geo.wheelbase * dt
        } else {
            // 方向盤不可靠時：直接用量到的車頭轉向角速度（前方畫面，見 VisualYaw；rad/s，+ = 往右）
            th += yawRate!! * dt
        }
        y += v * sin(th) * dt
        val remain = d * laneW - y                       // 車道座標裡離目標中心還有多遠（+ = 右）
        if (remain * d < ARRIVED_M) { done = true; return null }
        val phi = -th
        return remain / cos(phi) to phi
    }
}
