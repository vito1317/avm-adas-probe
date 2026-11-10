package com.vito.avmprobe

import org.junit.Assert.*
import org.junit.Test

class RearDisplayTest {
    @Test fun packets() {
        assertArrayEquals(byteArrayOf(7, 0, 8, 0x80.toByte(), 1, 0, 3), RearDisplayProtocol.play(3))
        assertArrayEquals(byteArrayOf(5, 0, 4, 0x80.toByte(), 50), RearDisplayProtocol.brightness(50))
        assertArrayEquals(byteArrayOf(5, 0, 7, 1, 0), RearDisplayProtocol.power(false))
    }

    @Test fun priority() {
        assertEquals(RearDisplayProtocol.Event.BRAKE, RearDisplayProtocol.pick(true, true, true, true))
        assertEquals(RearDisplayProtocol.Event.REVERSE, RearDisplayProtocol.pick(false, true, true, false))
        assertEquals(RearDisplayProtocol.Event.FOLLOW, RearDisplayProtocol.pick(false, false, true, true))
        assertEquals(RearDisplayProtocol.Event.IDLE, RearDisplayProtocol.pick(false, false, false, false))
    }
}
