package com.vito.avmprobe

import org.junit.Assert.*
import org.junit.Test

class CyProtocolTest {
    private val key = CyProtocol.unhex("00112233445566778899aabbccddeeff")   // 測試用假金鑰

    @Test fun frameLayout() {
        val f = CyProtocol.hex(CyProtocol.build("2F", "03", "", true, key, 5))
        assertTrue(f.startsWith("FF55")); assertTrue(f.endsWith("00"))
        val ll = f.substring(4, 6).toInt(16)
        assertEquals("總長 = LL + 4 bytes", (ll + 4) * 2, f.length)
        assertEquals("3030", f.substring(6, 10))            // 用亂數加密的模式
        assertEquals(0, (f.length - 14) % 32)               // 加密段是 16 的倍數
    }

    @Test fun roundTripDecodes() {
        val f = CyProtocol.hex(CyProtocol.build("27", "01", "0110", false, key, 9))
        assertEquals("3010", f.substring(6, 10))             // 握手用 btkey
        val m = CyProtocol.parse(f, key, null)!!
        assertEquals("2701", m.trcd); assertEquals("0110", m.msg)
        val w = CyProtocol.parse(CyProtocol.hex(CyProtocol.build("2F", "08", "02", true, key, 1)), null, key)!!
        assertEquals("2F08", w.trcd); assertEquals("02", w.msg)
    }

    @Test fun splitHandlesChunks() {
        val f = CyProtocol.hex(CyProtocol.build("2F", "04", "", true, key, 2))
        val sb = StringBuilder("AB" + f.substring(0, 20))
        assertTrue(CyProtocol.split(sb).isEmpty())
        sb.append(f.substring(20) + f)
        assertEquals(listOf(f, f), CyProtocol.split(sb))
    }

    @Test fun refusesDangerousCodes() {
        for (id in listOf("01", "02", "0E", "0F", "07", "10")) {
            try { CyProtocol.build("2F", id, "", true, key, 1); fail("2F$id 不應該能送") } catch (_: IllegalArgumentException) {}
        }
    }
}
