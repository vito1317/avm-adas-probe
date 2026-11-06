package com.vito.avmprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertSounderTest {

    private val played = ArrayList<AlertSounder.Kind>()
    private val s = AlertSounder { played.add(it) }

    @Test
    fun ringsOnlyWhenAlertAppears() {
        s.update(0, AlertSounder.State(ldw = true))
        s.update(100, AlertSounder.State(ldw = true))
        s.update(200, AlertSounder.State(ldw = true))
        assertEquals(listOf(AlertSounder.Kind.LANE), played)
    }

    @Test
    fun cooldownStopsRapidRepeats() {
        s.update(0, AlertSounder.State(collision = true))
        s.update(100, AlertSounder.State())
        s.update(200, AlertSounder.State(collision = true))      // 0.2 s 後又出現：還在冷卻
        s.update(300, AlertSounder.State())
        s.update(2000, AlertSounder.State(collision = true))     // 冷卻過了
        assertEquals(2, played.size)
    }

    @Test
    fun dangerWinsOverInfo() {
        s.update(0, AlertSounder.State(start = true, turnHit = true))
        assertEquals(listOf(AlertSounder.Kind.DANGER), played)
    }

    @Test
    fun eachKindHasItsOwnSound() {
        s.update(0, AlertSounder.State(start = true))
        s.update(10, AlertSounder.State(start = true, ldw = true))
        s.update(20, AlertSounder.State(start = true, ldw = true, lcaDanger = true))
        assertEquals(listOf(AlertSounder.Kind.INFO, AlertSounder.Kind.LANE, AlertSounder.Kind.DANGER), played)
    }

    @Test
    fun collisionOnlyCountsWhenMoving() {
        assertFalse(AlertSounder.moving(0f, 'D'))
        assertFalse(AlertSounder.moving(null, null))
        assertTrue(AlertSounder.moving(3f, 'D'))
        assertTrue(AlertSounder.moving(0f, 'R'))
    }
}
