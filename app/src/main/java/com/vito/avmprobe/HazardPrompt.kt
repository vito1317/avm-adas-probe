package com.vito.avmprobe

/**
 * 提醒你開雙閃（車機沒辦法自己開雙閃：CAN 盒、遠端模組都不支援行車中控制燈號）：
 * - 自己高速急煞（≥ 60 km/h，減速度 > 4.5 m/s²；CAN 有真煞車訊號時 > 3.5 m/s² 就算）→ 「急煞！開雙閃警示後車」（危險，紅）。
 * - 高速時前車打雙閃（≥ 50 km/h）→ 「前車打雙閃，減速並開雙閃」（注意，橘）。
 * 打了雙閃就收掉；同一種提醒結束後 60 秒內不再出現。
 */
class HazardPrompt {
    data class Msg(val key: String, val text: String, val level: Int)

    companion object {
        const val BRAKE_KMH = 60f
        const val LEAD_KMH = 50f
        const val HARD_DECEL = 4.5f
        const val HARD_DECEL_CAN = 3.5f
        const val SHOW_MS = 10_000L
        const val COOLDOWN_MS = 60_000L
    }

    private var brakeUntil = 0L; private var brakeCool = 0L
    private var leadUntil = 0L; private var leadCool = 0L

    fun update(now: Long, speedKmh: Float?, decel: Float, canBrake: Boolean?, turn: Char?, leadHazard: Boolean): Msg? {
        if (turn == 'H') { brakeUntil = 0L; leadUntil = 0L; return null }   // 已經開了
        val v = speedKmh ?: 0f
        val hard = decel > (if (canBrake == true) HARD_DECEL_CAN else HARD_DECEL)
        if (v >= BRAKE_KMH && hard && now >= brakeCool && now >= brakeUntil) {
            brakeUntil = now + SHOW_MS; brakeCool = brakeUntil + COOLDOWN_MS
        }
        if (v >= LEAD_KMH && leadHazard && now >= leadCool && now >= leadUntil) {
            leadUntil = now + SHOW_MS; leadCool = leadUntil + COOLDOWN_MS
        }
        return when {
            now < brakeUntil -> Msg("hazbrake", "急煞！開雙閃警示後車", 2)
            now < leadUntil -> Msg("hazlead", "前車打雙閃，減速並開雙閃", 1)
            else -> null
        }
    }
}
