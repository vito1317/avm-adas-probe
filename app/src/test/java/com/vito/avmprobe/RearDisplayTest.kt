package com.vito.avmprobe

import org.junit.Assert.*
import org.junit.Test

class RearDisplayTest {
    @Test fun packets() {
        assertArrayEquals(byteArrayOf(7, 0, 8, 0x80.toByte(), 1, 0, 3), RearDisplayProtocol.play(3))
        assertArrayEquals(byteArrayOf(5, 0, 4, 0x80.toByte(), 50), RearDisplayProtocol.brightness(50))
        assertArrayEquals(byteArrayOf(5, 0, 7, 1, 0), RearDisplayProtocol.power(false))
        assertArrayEquals(byteArrayOf(9, 0, 8, 0x80.toByte(), 3, 0, 1, 2, 5), RearDisplayProtocol.playList(listOf(1, 2, 5)))
        assertEquals(listOf(1, 2, 3), RearDisplayProtocol.parseList("1, 2、3"))
    }

    @Test fun priority() {
        assertEquals(RearDisplayProtocol.Event.BRAKE, RearDisplayProtocol.pick(true, true, true, true))
        assertEquals(RearDisplayProtocol.Event.REVERSE, RearDisplayProtocol.pick(false, true, true, false))
        assertEquals(RearDisplayProtocol.Event.FOLLOW, RearDisplayProtocol.pick(false, false, true, true))
        assertEquals(RearDisplayProtocol.Event.IDLE, RearDisplayProtocol.pick(false, false, false, false))
        assertEquals(RearDisplayProtocol.Event.START, RearDisplayProtocol.pick(false, false, true, false, null, start = true, wait = true))
        assertEquals(RearDisplayProtocol.Event.WAIT, RearDisplayProtocol.pick(false, false, true, false, null, wait = true))
        assertEquals("方向燈優先於起步", RearDisplayProtocol.Event.LEFT, RearDisplayProtocol.pick(false, false, false, false, 'L', start = true))
        assertEquals(RearDisplayProtocol.Event.LEFT, RearDisplayProtocol.pick(false, false, true, false, 'L'))
        assertEquals("倒車優先於方向燈", RearDisplayProtocol.Event.REVERSE, RearDisplayProtocol.pick(false, true, false, false, 'R'))
    }
}
