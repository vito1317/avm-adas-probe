package com.vito.avmprobe

import kotlin.math.abs

/**
 * 起步提醒：停著的時候，前車開走了、或紅燈轉綠了，提醒一下。
 *
 * 兩種提醒分開判斷、也分開回報，因為實際情況常常只有一種成立：
 * 路口排第二台以後只看得到前車（紅綠燈被前車擋住），排第一台則多半只看得到號誌。
 *
 * ### 前車啟動
 * 自車停住（< [STOP_KMH] km/h）滿 [ARM_MS] 之後開始盯「正前方同車道最近的那台車」。
 * 記下它在這段期間離得最近的距離當基準，之後拉開 [GAP_M] 就提醒。
 * 用「最近距離」當基準而不是用第一次看到的距離，是因為前車常常會再往前蹭個半公尺。
 *
 * 不做「前車消失就提醒」：四顆鏡頭輪流偵測，同一台車本來就會斷斷續續；
 * 前車真的開走時距離會先一路拉開再離開偵測範圍，用距離判斷就夠了，而且不會亂叫。
 *
 * ### 紅燈轉綠
 * 要先在停住的狀態下看到夠有把握的紅燈（或黃燈）撐過 [RED_MS]，
 * 之後看到綠燈撐過 [GREEN_MS] 才提醒 —— 只看到綠燈不算，避免一停下來就叫。
 *
 * 兩種都是**一次性**的：提醒過就不再重複，直到自車真的動了（≥ [MOVE_KMH]）才重新武裝。
 * 沒有車速訊號就整個不作用：分不出「前車開走」和「自己在走」。
 */
class StartAssist {

    companion object {
        const val STOP_KMH = 2.0f        // 低於這個算停住
        const val MOVE_KMH = 3.0f        // 高於這個算自己動了 → 解除、重新武裝
        const val ARM_MS = 1500L         // 停住多久才開始盯（別在龜速前進時亂叫）
        const val LANE_HALF = 1.4f       // 正前方同車道的半寬（m）
        const val NEAR_Z = 9.0f          // 前車要先在這麼近才算「我在跟著它等」
        const val MAX_Z = 14.0f          // 超過這距離不當前車
        const val GAP_M = 1.5f           // 比最近距離拉開這麼多 → 它走了
        const val RED_MS = 1500L         // 紅／黃燈要穩定這麼久
        const val GREEN_MS = 400L        // 綠燈要穩定這麼久
        const val TL_CONF = 0.5f         // 號誌信心下限
        const val STALE_STOP_MS = 180_000L   // 停住後車速訊號斷了，還能當「仍然停著」多久
        const val ALERT_MS = 5_000L          // 提醒顯示多久就自己收掉（這是通知，不是狀態）
        private val LEAD_TYPES = setOf("car", "van", "truck", "bus", "moto", "bigbike")
    }

    /** [lead] 前車走了、[green] 轉綠燈了；[gap] 前車比最近時拉開了幾公尺（沒有前車時 0）。 */
    data class State(val lead: Boolean, val green: Boolean, val gap: Float)

    // 車速訊號多半是「有變化才廣播」的，而 VehicleSignals 2 秒就讓它過期 ——
    // 停在紅燈前不動，第 3 秒起 speedKmh 就是 null 了，正好是這個功能要用的時候。
    // 最後一次讀到的是「幾乎沒在動」，之後一直沒有新值，就代表它還是沒動
    //（真的動起來，會變才廣播的訊號就會送新值過來）。
    private var lastSpeed: Float? = null
    private var lastSpeedAt = 0L

    private var stoppedAt = -1L      // 開始停住的時刻；-1 = 沒停
    private var minZ = Float.NaN     // 武裝後前車最近到幾公尺
    private var leadFired = false
    private var greenFired = false
    private var leadAt = 0L
    private var greenAt = 0L
    private var redSince = -1L
    private var greenSince = -1L
    private var sawRed = false
    private var lead = false
    private var green = false
    private var gap = 0f

    /**
     * @param objs 目前這一輪要顯示的物件（車身座標公尺，z 向前）
     * @param tlState 號誌燈色 "red"/"yellow"/"green"，沒有有效號誌傳 null
     */
    fun update(
        nowMs: Long,
        speedKmh: Float?,
        objs: List<Obj>,
        tlState: String?,
        tlConf: Float
    ): State {
        val sp = effectiveSpeed(nowMs, speedKmh)
        if (sp == null) {                // 沒車速就分不出是誰在動
            reset()
            return State(false, false, 0f)
        }
        if (sp >= MOVE_KMH) {            // 自己動了 → 全部歸零，下次停下來重新算
            reset()
            return State(false, false, 0f)
        }
        if (sp >= STOP_KMH) {            // 在 STOP 和 MOVE 之間：維持現狀，不武裝也不解除
            return out(nowMs)
        }

        if (stoppedAt < 0L) stoppedAt = nowMs
        val armed = nowMs - stoppedAt >= ARM_MS

        // ---- 號誌 ----
        // 號誌不等武裝就開始累積：停下來的那一刻紅燈多半已經在那裡了，
        // 還要再等 ARM_MS 才開始看紅燈的話，短的紅燈會來不及記到。
        val conf = tlConf >= TL_CONF
        when {
            tlState == "red" || tlState == "yellow" -> {
                if (conf) {
                    if (redSince < 0L) redSince = nowMs
                    if (nowMs - redSince >= RED_MS) sawRed = true
                }
                greenSince = -1L
            }
            tlState == "green" -> {
                redSince = -1L
                if (conf) {
                    if (greenSince < 0L) greenSince = nowMs
                    if (sawRed && !greenFired && nowMs - greenSince >= GREEN_MS) {
                        green = true; greenFired = true; greenAt = nowMs
                    }
                } else greenSince = -1L
            }
            else -> {                    // 號誌不見了（被前車擋住很常見）：紅燈記憶留著，綠燈計時歸零
                greenSince = -1L
                redSince = -1L
            }
        }

        if (!armed) return out(nowMs)

        // ---- 前車 ----
        val l = nearestLead(objs)
        if (l != null) {
            if (minZ.isNaN()) { if (l <= NEAR_Z) minZ = l } else if (l < minZ) minZ = l
            if (!minZ.isNaN()) {
                gap = l - minZ
                if (!leadFired && gap >= GAP_M) { lead = true; leadFired = true; leadAt = nowMs }
            }
        }

        return out(nowMs)
    }

    /**
     * 提醒**會自己收掉**：[ALERT_MS] 過了就不再回報。
     *
     * 本來是「狀態」—— 叫了之後一直為真，等自車動起來（≥ [MOVE_KMH]）才清掉。
     * 問題是車速訊號多半有變化才廣播，停著不動時會過期；為了讓停著時還判得出來，
     * 車速斷訊時會沿用最後那個「停著」的讀數（見 [effectiveSpeed]）——
     * 於是**跟著前車開走之後，如果一直沒有新的車速送來，就以為還停著，提示就不會消失**。
     * 使用者回報的就是這個。
     *
     * 這本來就該是通知而不是狀態：響一聲、顯示幾秒、自己消失。
     * 這樣不管判不判得出來「已經開走了」，都不會卡住。
     */
    private fun out(nowMs: Long): State = State(
        lead && nowMs - leadAt <= ALERT_MS,
        green && nowMs - greenAt <= ALERT_MS,
        gap
    )

    /** 正前方同車道、距離最近的那台車（公尺）；沒有回 null。 */
    private fun nearestLead(objs: List<Obj>): Float? {
        var best: Float? = null
        for (o in objs) {
            if (o.t !in LEAD_TYPES) continue
            if (o.z <= 0f || o.z > MAX_Z) continue
            if (abs(o.x) > LANE_HALF) continue
            if (best == null || o.z < best!!) best = o.z
        }
        return best
    }

    /** 車速訊號斷掉時沿用最後一次的「停著」讀數，見 [lastSpeed] 的說明。 */
    private fun effectiveSpeed(nowMs: Long, speedKmh: Float?): Float? {
        if (speedKmh != null) {
            lastSpeed = speedKmh
            lastSpeedAt = nowMs
            return speedKmh
        }
        val ls = lastSpeed ?: return null
        if (ls >= STOP_KMH) return null                       // 最後是在動 → 現在狀況不明
        if (nowMs - lastSpeedAt > STALE_STOP_MS) return null   // 斷太久了，不猜
        return ls
    }

    private fun reset() {
        stoppedAt = -1L
        minZ = Float.NaN
        leadFired = false
        greenFired = false
        redSince = -1L
        greenSince = -1L
        sawRed = false
        lead = false
        green = false
        gap = 0f
    }

    /** 偵測結果裡這個類別用得到的部分。 */
    data class Obj(val t: String, val x: Float, val z: Float)
}
