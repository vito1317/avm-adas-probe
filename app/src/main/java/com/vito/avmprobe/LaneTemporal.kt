package com.vito.avmprobe

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 車道線的「時間一致性」：真的漆在地上，車子開過去時，它在俯視圖上會照車速、轉向平順地往後移；
 * 車身反光、自己車燈照亮範圍的邊緣、鏡頭邊緣的亮弧是「跟著車子走」的，在俯視圖上不會往後移。
 *
 * 做法（每顆鏡頭各自做，因為四顆鏡頭輪流跑模型、時間不同）：
 * 這顆鏡頭有新結果時，把「上一次這顆鏡頭的結果」依這段時間自己車子的移動（CAN 車速＋轉向角速度積分），
 * 對到現在的位置；這一次說是線、上一次同一個地面位置也是線 → 保留；上一次那裡不是線 → 壓低（乘上 [FLOOR]）。
 * 車子沒什麼移動（< [MIN_MOVE] m）時分不出來，不處理；對過去落在別顆鏡頭或俯視圖外 → 不知道，不處理。
 * 容許誤差：左右 ±[TOL_PX] 像素、前後再加移動距離的 [TOL_FRAC]（車速有誤差）。
 * 純邏輯，可單元測試。只影響畫面上的線與提示，不拿去控制車子。
 */
class LaneTemporal {
    companion object {
        const val MIN_MOVE = 0.4          // m：移動太少分不出「跟著車走」還是「在地上」
        const val TOL_PX = 2
        const val TOL_FRAC = 0.06
        const val FULL = 0.45f            // 上一次的機率到這個值就完全保留
        const val FLOOR = 0.3f            // 上一次完全不是線 → 機率乘這個（0.9 → 0.27，低於畫線門檻 0.5）
        private const val LOG_N = 256
    }

    // 自己車子的位置記錄（世界座標：X 右、Z 前、航向 th 往右為正），用車速＋角速度積分
    private val lt = LongArray(LOG_N); private val lx = DoubleArray(LOG_N); private val lz = DoubleArray(LOG_N); private val lth = DoubleArray(LOG_N)
    private var n = 0; private var head = -1

    @Synchronized fun odo(now: Long, speedMps: Double?, yawRateRight: Double?) {
        if (head < 0) { head = 0; n = 1; lt[0] = now; lx[0] = 0.0; lz[0] = 0.0; lth[0] = 0.0; return }
        val dt = (now - lt[head]) / 1000.0
        if (dt <= 0) return
        val v = if (dt > 1.0) 0.0 else (speedMps ?: 0.0)          // 太久沒更新：不積分（不知道中間怎麼走）
        val w = if (dt > 1.0) 0.0 else (yawRateRight ?: 0.0)
        val th0 = lth[head]; val thm = th0 + w * dt / 2
        val nh = (head + 1) % LOG_N
        lt[nh] = now; lx[nh] = lx[head] + v * dt * sin(thm); lz[nh] = lz[head] + v * dt * cos(thm); lth[nh] = th0 + w * dt
        head = nh; n = min(n + 1, LOG_N)
    }

    /** 時間 [t] 的位置（線性內插）；超出記錄範圍回 null。 */
    @Synchronized fun pose(t: Long): DoubleArray? {
        if (n == 0) return null
        var i = head
        for (k in 0 until n) {
            val j = (head - k + LOG_N) % LOG_N
            if (lt[j] <= t) {
                if (j == head) return if (t - lt[j] < 300) doubleArrayOf(lx[j], lz[j], lth[j]) else null
                val nx = (j + 1) % LOG_N; val f = (t - lt[j]).toDouble() / max(1L, lt[nx] - lt[j])
                return doubleArrayOf(lx[j] + f * (lx[nx] - lx[j]), lz[j] + f * (lz[nx] - lz[j]), lth[j] + f * (lth[nx] - lth[j]))
            }
            i = j
        }
        return null
    }

    /**
     * 算一顆鏡頭的保留係數。[idx] 是俯視圖像素 → (鏡頭 shl 24) or 機率索引（-1 = 看不到），
     * [prev] 是這顆鏡頭上一次的機率、[gateOut] 寫入俯視圖每個像素的係數（只寫這顆鏡頭負責的像素）。
     * [tPrev]、[tNow] 是兩次畫面的時間。回傳 false = 這次不判斷（全部係數設 1）。
     */
    fun gate(q: Int, idx: IntArray, w: Int, h: Int, mpp: Float, cx: Float, cy: Float,
             prev: FloatArray, tPrev: Long, tNow: Long, gateOut: FloatArray): Boolean {
        val a = pose(tPrev); val b = pose(tNow)
        val move = if (a != null && b != null) hypot(b[0] - a[0], b[1] - a[1]) else 0.0
        if (a == null || b == null || move < MIN_MOVE) {
            for (k in idx.indices) if (idx[k] >= 0 && (idx[k] ushr 24) == q) gateOut[k] = 1f
            return false
        }
        val m = mpp.toDouble()
        val rbx = cos(b[2]); val rbz = -sin(b[2]); val fbx = sin(b[2]); val fbz = cos(b[2])
        val rax = cos(a[2]); val raz = -sin(a[2]); val fax = sin(a[2]); val faz = cos(a[2])
        val tolZ = TOL_PX + (move * TOL_FRAC / m).toInt()
        for (k in idx.indices) {
            val e = idx[k]
            if (e < 0 || (e ushr 24) != q) continue
            val i = k % w; val j = k / w
            val x = (i + 0.5 - cx) * m; val z = (cy - (j + 0.5)) * m
            // 現在車身座標 → 世界 → 上一次的車身座標
            val wx = b[0] + x * rbx + z * fbx - a[0]; val wz = b[1] + x * rbz + z * fbz - a[1]
            val xp = wx * rax + wz * raz; val zp = wx * fax + wz * faz
            val ip = floor(xp / m + cx).toInt(); val jp = floor(cy - zp / m).toInt()
            var best = -1f
            for (dj in -tolZ..tolZ) {
                val jj = jp + dj; if (jj < 0 || jj >= h) continue
                for (di in -TOL_PX..TOL_PX) {
                    val ii = ip + di; if (ii < 0 || ii >= w) continue
                    val e2 = idx[jj * w + ii]
                    if (e2 < 0 || (e2 ushr 24) != q) continue
                    best = max(best, prev[e2 and 0xFFFFFF])
                }
            }
            gateOut[k] = if (best < 0f) 1f else FLOOR + (1f - FLOOR) * min(1f, best / FULL)
        }
        return true
    }
}
