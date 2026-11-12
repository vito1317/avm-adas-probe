package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class RoadTrackerEgoTest {
    /** 世界座標裡 x = 1.8 的一條直線，換到自車座標（自車在 (ex, ez)、車頭朝 yaw）。 */
    private fun lineInEgo(ex: Double, ez: Double, yaw: Double, noise: Double, rnd: Random, kind: Char): RoadLine {
        fun toEgo(wx: Double, wz: Double): FloatArray {
            val dx = wx - ex; val dz = wz - ez
            val c = cos(yaw); val s = sin(yaw)
            return floatArrayOf((dx * c - dz * s).toFloat(), (dx * s + dz * c).toFloat())
        }
        val n = (rnd.nextDouble() - 0.5) * 2 * noise
        val a = toEgo(1.8 + n, ez - 4); val b = toEgo(1.8 + n, ez + 6)
        return RoadLine(kind, false, a[0], a[1], b[0], b[1], 0.8f)
    }

    @Test fun lineStaysPutWhileEgoMovesAndColourFlickers() {
        val tr = RoadTracker(); val rnd = Random(3)
        var ez = 0.0; var ex = 0.0; var yaw = 0.0; var t = 0L
        var worst = 0.0; var maxLines = 0
        repeat(80) { i ->
            val dz = 1.0                      // 每 100 ms 前進 1 m（36 km/h）
            val dyaw = if (i in 20..50) 0.01 else 0.0     // 中間緩慢轉一點（往右偏移）
            // 自車移動：在舊的朝向上往前走 dz，再轉 dyaw（與 egoMotion「先減 dz 再轉」的換算一致）
            ex += dz * sin(yaw); ez += dz * cos(yaw); yaw += dyaw
            tr.egoMotion(dz.toFloat(), dyaw.toFloat())
            t += 100
            val kind = if (rnd.nextDouble() < 0.3) 'y' else 'w'     // 晚上顏色亂跳
            val out = tr.update(RoadResult(listOf(lineInEgo(ex, ez, yaw, 0.15, rnd, kind)), null, null, 0), t)
            maxLines = maxOf(maxLines, out.lines.size)
            if (i > 10 && out.lines.isNotEmpty()) {
                val l = out.lines[0]
                val truth = lineInEgo(ex, ez, yaw, 0.0, rnd, 'w')
                // 兩條線在車身旁（z = 0）的橫向位置
                fun xAt0(q: RoadLine) = q.x1 + (0f - q.z1) * (q.x2 - q.x1) / (q.z2 - q.z1)
                val err = abs((xAt0(l) - xAt0(truth)).toDouble())
                worst = maxOf(worst, err)
            }
        }
        assertEquals("顏色亂跳也只有一條線", 1, maxLines)
        assertTrue("跟真實位置差 < 0.2 m（偵測雜訊 ±0.15 m）：$worst", worst < 0.2)
    }
}
