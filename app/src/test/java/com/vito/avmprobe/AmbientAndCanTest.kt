package com.vito.avmprobe

import org.junit.Assert.*
import org.junit.Test

class AmbientAndCanTest {
    @Test fun ambientFrames() {
        assertEquals("<06FF0000>", String(AmbientProtocol.color(AmbientProtocol.ZONE_ALL, 0xFF0000), Charsets.US_ASCII))
        assertEquals("<100040FF>", String(AmbientProtocol.color(AmbientProtocol.ZONE_1, 0x0040FF), Charsets.US_ASCII))
        assertEquals("<0A01>", String(AmbientProtocol.frame("0a01"), Charsets.US_ASCII))
    }

    @Test fun ambientStatusParse() {
        // 0x12 狀態：[4..6] 第 1 組、[7..9] 第 2 組
        val hex = "12000100" + "FF8000" + "0000FF" + "6464" + "000000"
        val parts = AmbientProtocol.parse("<$hex><0A>".toByteArray(Charsets.US_ASCII))
        assertEquals(2, parts.size)
        assertEquals(0xFF8000 to 0x0000FF, AmbientProtocol.statusColors(parts[0]))
        assertNull(AmbientProtocol.statusColors(parts[1]))
    }

    @Test fun ambientRules() {
        assertNull(AmbientRules.want(false, null, false, false, 0))
        assertEquals(AmbientRules.RED, AmbientRules.want(true, 'L', true, false, 0)!!.rgb)
        assertEquals('L', AmbientRules.want(true, 'L', false, false, 0)!!.side)
        assertEquals(AmbientRules.ORANGE, AmbientRules.want(false, null, true, false, 0)!!.rgb)
    }

    @Test fun canDecodeOnlyTrustsActiveFields() {
        val d = CanVehicle.Decoder()
        val a = IntArray(82)
        var i = d.decode(a)
        assertNull("一直是 0 的欄位不採用", i.brake); assertNull(i.gear); assertNull(i.beltOk)
        a[22] = 1; a[24] = 4; a[19] = 1; a[15] = 138; a[13] = 420
        i = d.decode(a)
        assertEquals(true, i.brake); assertEquals('D', i.gear); assertEquals(true, i.beltOk)
        assertEquals(13.8f, i.batV!!, 0.01f); assertEquals(420, i.rangeKm)
        a[22] = 0; a[24] = 1; a[19] = 0
        i = d.decode(a)
        assertEquals(false, i.brake); assertEquals('R', i.gear); assertEquals(false, i.beltOk)
        assertTrue(CanVehicle.alerts(i, 30f).any { it.key == "belt" })
        assertFalse("停著不提醒安全帶", CanVehicle.alerts(i, 0f).any { it.key == "belt" })
    }

    @Test fun rememberedFieldTrustedFromStart() {
        val seen = ArrayList<Int>()
        val d = CanVehicle.Decoder(setOf(19)) { seen.add(it) }
        val a = IntArray(82)
        assertEquals("記得安全帶欄位：0 就是沒繫", false, d.decode(a).beltOk)
        assertTrue(CanVehicle.alerts(d.decode(a), 40f).any { it.key == "belt" })
        a[22] = 1; d.decode(a)
        assertEquals("新看到的欄位要回報記住", listOf(22), seen)
    }
}
