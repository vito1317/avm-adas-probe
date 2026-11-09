package com.vito.avmprobe

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 停車格跨幀穩定：中心 0.6 m 內、方向差 15° 內視為同一格；角點做 EMA；
 * 第一次要最近 5 次看到 2 次才出現；出現之後 [HOLD_MS] 內沒看到也照樣顯示（格子不會動，
 * 偵測偶爾漏一次就不見會一直閃 —— 實車回報）。佔用狀態要連續 2 次不同才改（不然空格會時隱時現）。
 */
class SlotTracker {

    private class Track(var p: FloatArray, var kind: Char, var occupied: Boolean, var conf: Float, var seen: Long) {
        var hist = 1      // 最近幾次是否命中（位元）
        var hit = false
        var shown = false // 已經出現過（之後漏幾次照樣顯示）
        var occFlip = 0   // 佔用狀態連續跟現在不同的次數
    }

    companion object {
        const val HOLD_MS = 2000L
        /** 同一格的判定距離（中心）。偵測深度偶爾不同（預設 5.5 m／分隔線結束處）中心會差 0.3～0.4 m。 */
        const val MATCH_M = 1.0f
        /** 輸出前合併：中心這麼近、方向一樣的兩格當成同一格（不然同一個位置會跳出兩格）。 */
        const val MERGE_M = 1.2f
        /** 跑出偵測範圍的格子記多久、多遠（實車：找到停車格往前開，格子就消失了）。 */
        const val MEM_MS = 30_000L
        const val MEM_M = 25f
    }

    private val tracks = ArrayList<Track>()

    /** 偵測看得到的範圍（自車座標，m）：格子跑出去就改用推算記住，不因為「沒看到」被刪掉。 */
    var viewX = 6f
    var viewZ = 7f

    /**
     * 自車動了：往前 [dz] 公尺、車頭往右轉 [dyaw] 弧度 → 記住的格子在自車座標裡反方向移動。
     * 由車速＋方向盤推算（每次偵測前呼叫一次，給這段時間的累計）。
     */
    fun egoMotion(dz: Float, dyaw: Float) {
        if (dz == 0f && dyaw == 0f) return
        val c = kotlin.math.cos(dyaw); val s = kotlin.math.sin(dyaw)
        for (t in tracks) for (k in 0 until 4) {
            val x = t.p[k * 2]; val z = t.p[k * 2 + 1] - dz
            t.p[k * 2] = x * c - z * s
            t.p[k * 2 + 1] = x * s + z * c
        }
    }

    private fun inView(p: FloatArray): Boolean {
        val c = center(p)
        return abs(c[0]) < viewX - 1f && abs(c[1]) < viewZ - 1.5f
    }

    fun update(slots: List<ParkingSlot>, nowMs: Long): List<ParkingSlot> {
        for (t in tracks) t.hit = false
        for (s in slots) {
            val c = center(s.p)
            val a = heading(s.p)
            var best: Track? = null
            var bd = MATCH_M
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
            if (s.occupied != best.occupied) { if (++best.occFlip >= 2) { best.occupied = s.occupied; best.occFlip = 0 } }
            else best.occFlip = 0
            best.conf += (s.conf - best.conf) * k
            best.seen = nowMs
            best.hit = true
        }
        val it = tracks.iterator()
        while (it.hasNext()) {
            val t = it.next()
            t.hist = ((t.hist shl 1) or if (t.hit) 1 else 0) and 0x1F
            // 還在看得到的範圍卻一直沒偵測到 → 照舊 HOLD_MS 後刪；跑出範圍的已出現格子 → 記 MEM_MS、MEM_M
            val keep = if (t.shown && !inView(t.p)) nowMs - t.seen < MEM_MS && hypot(center(t.p)[0], center(t.p)[1]) < MEM_M
                else nowMs - t.seen <= HOLD_MS
            if (!keep) { it.remove(); continue }
            if (Integer.bitCount(t.hist) >= 2) t.shown = true
        }
        // 同一個位置只留一格：最近看到的（同時看到取信心高的）優先，其餘直接併掉
        tracks.sortWith(compareByDescending<Track> { it.seen }.thenByDescending { it.conf })
        val kept = ArrayList<Track>()
        val drop = ArrayList<Track>()
        for (t in tracks) {
            val c = center(t.p); val a = heading(t.p)
            val dup = kept.any { k -> val kc = center(k.p); hypot(kc[0] - c[0], kc[1] - c[1]) < MERGE_M && angleDiff(heading(k.p), a) < 15f }
            if (dup) drop.add(t) else kept.add(t)
        }
        tracks.removeAll(drop.toSet())
        return kept.filter { it.shown }.map { ParkingSlot(it.p.copyOf(), it.kind, it.occupied, it.conf) }
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
