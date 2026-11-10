package com.vito.avmprobe

import org.junit.Assert.*
import org.junit.Test

class ParkingLotStateTest {
    private fun run(s: ParkingLotState, from: Long, to: Long, kmh: Float, gear: Char? = 'D', steer: Float? = 0f): Boolean {
        var t = from; var r = false
        while (t <= to) { r = s.update(t, kmh, gear, steer); t += 100 }
        return r
    }

    @Test fun redLightWaitIsNotParkingLot() {
        val s = ParkingLotState()
        run(s, 0, 60_000, 60f)                         // 開在馬路上
        assertFalse("停紅燈 2 分鐘不算停車場", run(s, 60_100, 180_000, 0f))
        assertFalse(run(s, 180_100, 182_000, 10f))       // 起步經過低速區（2 秒）
    }

    @Test fun slowCrawlEntersAndFastLeaves() {
        val s = ParkingLotState()
        run(s, 0, 30_000, 70f)
        assertTrue("慢慢開 9 秒＝停車場", run(s, 30_100, 39_100, 8f))
        assertTrue("停住也還在停車場", run(s, 39_200, 60_000, 0f))
        assertTrue("剛加速不馬上離開", run(s, 60_100, 63_000, 40f))
        assertFalse("超過 25 km/h 持續 5 秒就離開", run(s, 63_100, 70_000, 40f))
    }

    @Test fun reverseOrParkOrBigSteer() {
        assertTrue(ParkingLotState().update(0, 0f, 'R', 0f))
        assertTrue(ParkingLotState().update(0, 0f, 'P', 0f))
        assertTrue(ParkingLotState().update(0, 5f, 'D', 300f))
        assertFalse(ParkingLotState().update(0, 5f, 'D', 30f))
    }
}
