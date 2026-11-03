package com.vito.avmprobe

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 停車格跨幀穩定：中心 0.6 m 內、方向差 15° 內視為同一格；角點做 EMA；
 * 最近 5 次至少看到 2 次才輸出，600 ms 沒看到就丟掉。佔用狀態取最近一次。
 */
class SlotTracker {

    private class Track(var p: FloatArray, var kind: Char, var occupied: Boolean, var conf: Float, var seen: Long) {
        var hist = 1      // 最近幾次是否命中（位元）
        var hit = false
    }

    private val tracks = ArrayList<Track>()

    fun update(slots: List<ParkingSlot>, nowMs: Long): List<ParkingSlot> {
        for (t in tracks) t.hit = false
        for (s in slots) {
            val c = center(s.p)
            val a = heading(s.p)
            var best: Track? = null
            var bd = 0.6f
            for (t in tracks) {
                if (t.hit) continue
                val d = hypot(center(t.p)[0] - c[0], center(t.p)[1] - c[1])
                if (d < bd && angleDiff(heading(t.p), a) < 15f) { bd = d; best = t }
            }
            if (best == null) {
                tracks.add(Track(s.p.copyOf(), s.kind, s.occupied, s.conf, nowMs).also { it.hit = true })
                continue
            }
            val k = 0.35f
            for (i in 0 until 8) best.p[i] += (s.p[i] - best.p[i]) * k
            best.kind = s.kind
            best.occupied = s.occupied
            best.conf += (s.conf - best.conf) * k
            best.seen = nowMs
            best.hit = true
        }
        val out = ArrayList<ParkingSlot>()
        val it = tracks.iterator()
        while (it.hasNext()) {
            val t = it.next()
            t.hist = ((t.hist shl 1) or if (t.hit) 1 else 0) and 0x1F
            if (nowMs - t.seen > 600) { it.remove(); continue }
            if (Integer.bitCount(t.hist) >= 2) out.add(ParkingSlot(t.p.copyOf(), t.kind, t.occupied, t.conf))
        }
        return out
    }

    private fun center(p: FloatArray) = floatArrayOf((p[0] + p[2] + p[4] + p[6]) / 4f, (p[1] + p[3] + p[5] + p[7]) / 4f)

    /** 入口邊的方向（度，0..180）。 */
    private fun heading(p: FloatArray): Float {
        var a = Math.toDegrees(atan2((p[3] - p[1]).toDouble(), (p[2] - p[0]).toDouble())).toFloat()
        if (a < 0) a += 180f
        if (a >= 180f) a -= 180f
        return a
    }

    private fun angleDiff(a: Float, b: Float): Float {
        val d = abs(a - b) % 180f
        return if (d > 90f) 180f - d else d
    }
}
