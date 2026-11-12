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

    @Test fun leavingSlowlyIntoCityStreetWithLanesExits() {
        // 實車：開出停車場後市區慢慢開（< 20 km/h），以前一直當停車場、不畫道路線
        val s = ParkingLotState()
        run(s, 0, 2_000, 0f, gear = 'R')
        assertTrue(run(s, 2_100, 3_000, 0f, gear = 'P'))
        val street = ParkingLotState.Clues(longLanes = true)
        assertFalse("兩旁有車道線開一段就離開", run(s, 3_100, 15_000, 15f, clues = street))
    }

    @Test fun drivingFarWithoutLotSignsExits() {
        val s = ParkingLotState()
        run(s, 0, 1_000, 0f, gear = 'P')
        // 15 km/h 一直開、沒有格子、沒打大方向盤、也沒有明顯車道線（巷子）：開超過 150 m 就離開
        assertTrue("才開 40 m 還在", run(s, 1_100, 10_000, 15f))
        assertFalse("開了 160 m 離開", run(s, 10_100, 40_000, 15f))
    }

    @Test fun circlingInsideLotStays() {
        val s = ParkingLotState()
        run(s, 0, 1_000, 0f, gear = 'R')
        // 停車場裡繞：常常大角度轉彎、看得到格子 → 開很久也還在
        var t = 1_100L; var r = true
        repeat(12) { r = run(s, t, t + 4_000, 10f, steer = if (it % 2 == 0) 300f else 0f, clues = ParkingLotState.Clues(slots = 3)); t += 4_100 }
        assertTrue(r)
    }
}
