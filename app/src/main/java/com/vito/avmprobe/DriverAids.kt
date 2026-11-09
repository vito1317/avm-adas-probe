package com.vito.avmprobe

import kotlin.math.abs

/**
 * 駕駛輔助警示（純邏輯，可單元測試）：
 * - 倒車兩側來車：倒車時，後方兩側有車橫向接近、3 秒內會到車後 → 危險。
 * - 開門預警：停著時後方有機車／腳踏車／車從旁邊接近 → 提醒；車門正被打開 → 危險。
 * - 後方快速接近：停著或慢速時，正後方的車接近太快、2.5 秒內會撞上 → 危險。
 * - 跟車秒數：同車道前車的淨距 ÷ 自己車速；< 1.2 秒提醒、< 0.7 秒危險。
 * - 休息提醒：連續開超過 2 小時（中間沒停 10 分鐘），或 10 分鐘內偏離車道 4 次以上。
 *
 * 座標同其他偵測：x 右、z 前（m），vx／vz 是相對自車的速度（m/s）。
 */
class DriverAids {

    data class Obj(val t: String, val x: Float, val z: Float, val vx: Float, val vz: Float, val clr: Float)
    data class Msg(val key: String, val text: String, val level: Int)   // level：0 資訊、1 注意、2 危險

    data class Input(
        val now: Long, val speedKmh: Float?, val gear: Char?,
        val objs: List<Obj>, val doorOpen: Boolean, val ldwEvent: Boolean
    )

    companion object {
        const val RCTA_TTC = 3.0f
        const val REAR_TTC = 2.5f
        const val HEADWAY_WARN = 1.2f
        const val HEADWAY_DANGER = 0.7f
        const val DRIVE_LIMIT_MS = 2 * 3600_000L
        const val REST_MS = 10 * 60_000L
        const val REMIND_EVERY_MS = 30 * 60_000L
    }

    private var drivingSince = -1L
    private var stoppedSince = -1L
    private var lastRestRemind = 0L
    private val ldwTimes = ArrayDeque<Long>()
    private var lastLdw = false

    fun update(i: Input): List<Msg> {
        val out = ArrayList<Msg>()
        val v = i.speedKmh ?: 0f
        val moving = v > 10f

        // ── 倒車兩側來車 ──
        if (i.gear == 'R') {
            for (o in i.objs) {
                if (o.z > 1.5f || o.z < -12f || abs(o.x) < 1.6f || abs(o.x) > 15f) continue
                val toward = (o.x > 0 && o.vx < -0.5f) || (o.x < 0 && o.vx > 0.5f)
                if (!toward) continue
                val ttc = (abs(o.x) - 1.2f) / abs(o.vx)
                if (ttc < RCTA_TTC) {
                    out.add(Msg("rcta", if (o.x < 0) "◀ 左側來車，停！" else "右側來車，停！ ▶", 2)); break
                }
            }
        }

        // ── 開門預警（停著）──
        if (v < 3f) {
            val two = setOf("moto", "bigbike", "bike", "ped", "car", "van")
            val comer = i.objs.firstOrNull { o ->
                o.t in two && o.z < 3f && o.z > -25f && abs(o.x) in 0.8f..3.5f && o.vz > 1.0f && (o.vz > 0 && -o.z / o.vz < 6f || o.z > -2f)
            }
            if (comer != null) {
                val side = if (comer.x < 0) "左" else "右"
                out.add(if (i.doorOpen) Msg("dow", "${side}後方來車，別開門！", 2) else Msg("dow", "${side}後方有來車，開門注意", 1))
            }
        }

        // ── 後方快速接近（停著或慢速）──
        if (v < 15f) {
            for (o in i.objs) {
                if (o.z > -2.5f || abs(o.x) > 1.5f || o.vz < 3f) continue
                val ttc = o.clr / o.vz
                if (ttc < REAR_TTC) { out.add(Msg("rear", "後方來車太快！", 2)); break }
            }
        }

        // ── 跟車秒數 ──
        if (v > 30f && i.gear != 'R') {
            val lead = i.objs.filter { ObjTypes.group(it.t) == "veh" && abs(it.x) < 1.3f && it.z > 2f }.minByOrNull { it.z }
            if (lead != null) {
                val sec = lead.clr / (v / 3.6f)
                when {
                    sec < HEADWAY_DANGER -> out.add(Msg("headway", "跟車太近 %.1f 秒".format(sec), 2))
                    sec < HEADWAY_WARN -> out.add(Msg("headway", "跟車 %.1f 秒，保持距離".format(sec), 1))
                }
            }
        }

        // ── 休息提醒 ──
        if (moving) {
            if (drivingSince < 0L) drivingSince = i.now
            stoppedSince = -1L
        } else {
            if (stoppedSince < 0L) stoppedSince = i.now
            if (i.now - stoppedSince > REST_MS) { drivingSince = -1L; lastRestRemind = 0L }
        }
        if (i.ldwEvent && !lastLdw) ldwTimes.addLast(i.now)
        lastLdw = i.ldwEvent
        while (ldwTimes.isNotEmpty() && i.now - ldwTimes.first() > 10 * 60_000L) ldwTimes.removeFirst()
        val longDrive = drivingSince >= 0L && i.now - drivingSince > DRIVE_LIMIT_MS
        val weaving = ldwTimes.size >= 4
        if ((longDrive || weaving) && (lastRestRemind == 0L || i.now - lastRestRemind < 15_000L || i.now - lastRestRemind > REMIND_EVERY_MS)) {
            if (lastRestRemind == 0L || i.now - lastRestRemind > REMIND_EVERY_MS) lastRestRemind = i.now
            out.add(Msg("rest", if (weaving) "常常偏離車道，注意精神、找地方休息" else "已經開超過 2 小時，找地方休息一下", 1))
        }
        return out
    }
}
