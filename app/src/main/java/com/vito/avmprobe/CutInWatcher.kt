package com.vito.avmprobe

import kotlin.math.abs

/**
 * 旁邊車道的車打方向燈要切進來：前方寬角畫面裡，左右相鄰車道（橫向 1.2～5 m）的每台車各自追蹤、各自用
 * [LeadLightDetector] 看車尾的方向燈。左車道的車打右燈、右車道的車打左燈 → 要切入自己的車道。
 * 車子之間用框中心配對（跳太遠當成另一台）；最多追 [MAX] 台。純邏輯，可單元測試。
 */
class CutInWatcher {
    companion object {
        const val MAX = 6
        const val LANE_MIN = 1.2f
        const val LANE_MAX = 5.0f
        const val DROP_MS = 1500L
    }

    data class Box(val l: Float, val t: Float, val r: Float, val b: Float, val worldX: Float, val worldZ: Float)

    private class Trk(var cx: Float, var cy: Float, var w: Float, var x: Float, var z: Float, var seen: Long) {
        val det = LeadLightDetector()
        var turn: Char? = null
    }
    private val trks = ArrayList<Trk>()

    /** 回傳要切進來的那一側（'L' 左邊車道的車、'R' 右邊）與那台車的位置；沒有回 null。 */
    fun update(now: Long, argb: IntArray, size: Int, boxes: List<Box>): Triple<Char, Float, Float>? {
        for (bx in boxes) {
            if (abs(bx.worldX) < LANE_MIN || abs(bx.worldX) > LANE_MAX || bx.worldZ < 2f) continue
            val cx = (bx.l + bx.r) / 2f; val cy = (bx.t + bx.b) / 2f; val w = bx.r - bx.l
            var t = trks.minByOrNull { abs(it.cx - cx) + abs(it.cy - cy) }
            if (t == null || abs(t.cx - cx) > maxOf(t.w, w) * 0.6f || abs(t.cy - cy) > (bx.b - bx.t) * 0.6f) {
                if (trks.size >= MAX) trks.minByOrNull { it.seen }?.let { trks.remove(it) }
                t = Trk(cx, cy, w, bx.worldX, bx.worldZ, now).also { trks.add(it) }
            }
            t.cx = cx; t.cy = cy; t.w = w; t.x = bx.worldX; t.z = bx.worldZ; t.seen = now
            t.turn = t.det.update(now, argb, size, bx.l, bx.t, bx.r, bx.b).let { s -> if (s.hazard) null else s.turn }
        }
        trks.removeAll { now - it.seen > DROP_MS }
        val hit = trks.filter { (it.x < 0f && it.turn == 'R') || (it.x > 0f && it.turn == 'L') }.minByOrNull { it.z } ?: return null
        return Triple(if (hit.x < 0f) 'L' else 'R', hit.x, hit.z)
    }
}
