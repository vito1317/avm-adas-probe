package com.vito.avmprobe

import org.junit.Assert.*
import org.junit.Test

class ParkingLotStateTest {
    private val none = ParkingLotState.Clues()

    private fun run(s: ParkingLotState, from: Long, to: Long, kmh: Float, gear: Char? = 'D', steer: Float? = 0f,
                    clues: ParkingLotState.Clues = none): Boolean {
        var t = from; var r = false
        while (t <= to) { r = s.update(t, kmh, gear, steer, clues); t += 100 }
        return r
    }

    @Test fun redLightWaitIsNotParkingLot() {
        val s = ParkingLotState()
        run(s, 0, 60_000, 60f)
        assertFalse("停紅燈 2 分鐘不算停車場", run(s, 60_100, 180_000, 0f))
    }

    @Test fun highwayTrafficJamIsNotParkingLot() {
        val s = ParkingLotState()
        run(s, 0, 30_000, 90f)
        // 塞車：時速 5～15 走走停停很久，前面一直有車、旁邊有車道線，偶爾還「看到」2 格停車格（車道線＋車湊出來的假格子）
        val jam = ParkingLotState.Clues(leadClose = true, longLanes = true, slots = 2)
        assertFalse("塞車 2 分鐘不算停車場", run(s, 30_100, 150_000, 10f, clues = jam))
        // 塞車時稍微打一點方向盤換車道也不算
        assertFalse(run(s, 150_100, 160_000, 12f, steer = 40f, clues = jam))
    }

    @Test fun parkingLotCrawlWithTurnsEnters() {
        val s = ParkingLotState()
        run(s, 0, 30_000, 70f)
        assertFalse("慢慢開但沒有停車場的證據：還不算", run(s, 30_100, 40_000, 8f))
        assertTrue("轉大彎找車位、前面沒車、旁邊沒長車道線：停車場", run(s, 40_100, 49_000, 8f, steer = 200f))
        assertTrue("停住也還在", run(s, 49_100, 70_000, 0f))
        assertTrue("剛加速不馬上離開", run(s, 70_100, 73_000, 40f))
        assertFalse("超過 25 km/h 持續 5 秒就離開", run(s, 73_100, 80_000, 40f))
    }

    @Test fun slotsAroundAlsoCount() {
        val s = ParkingLotState()
        assertTrue(run(s, 0, 9_000, 6f, clues = ParkingLotState.Clues(slots = 3)))
    }

    @Test fun reverseOrPark() {
        assertTrue(ParkingLotState().update(0, 0f, 'R', 0f))
        assertTrue(ParkingLotState().update(0, 0f, 'P', 0f))
    }
}
