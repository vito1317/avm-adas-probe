package com.vito.avmprobe

import org.junit.Assert.*
import org.junit.Test

class HazardPromptTest {
    @Test fun hardBrakeAtSpeedPromptsUntilHazardOn() {
        val h = HazardPrompt()
        assertNull(h.update(0, 80f, 2f, null, null, false))
        assertEquals("hazbrake", h.update(100, 80f, 5f, null, null, false)?.key)
        assertEquals(2, h.update(3000, 40f, 0f, null, null, false)?.level)     // 持續顯示
        assertNull("打了雙閃就收掉", h.update(4000, 30f, 0f, null, 'H', false))
    }

    @Test fun canBrakeLowersThresholdAndSlowBrakeIgnored() {
        val h = HazardPrompt()
        assertNull("低速急煞不提醒", h.update(0, 40f, 6f, true, null, false))
        assertNull(h.update(100, 80f, 4f, null, null, false))
        assertEquals("hazbrake", h.update(200, 80f, 4f, true, null, false)?.key)
    }

    @Test fun leadHazardAndCooldown() {
        val h = HazardPrompt()
        assertEquals("hazlead", h.update(0, 70f, 0f, null, null, true)?.key)
        assertNull(h.update(HazardPrompt.SHOW_MS + 1, 70f, 0f, null, null, false))
        assertNull("冷卻中", h.update(HazardPrompt.SHOW_MS + 1000, 70f, 0f, null, null, true))
    }
}
