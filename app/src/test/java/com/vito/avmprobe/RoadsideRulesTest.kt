package com.vito.avmprobe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadsideRulesTest {
    private fun line(x: Float, dashed: Boolean) = RoadLine('w', dashed, x, -3f, x, 6f, 0.9f)

    @Test
    fun solidLineOnLeftNothingOnRightIsShoulder() {
        assertTrue(RoadsideRules.onShoulder(listOf(line(-1.4f, false)), null))
        assertTrue(RoadsideRules.onShoulder(listOf(line(-1.4f, false), line(2.0f, false)), 1.6f))   // 右邊很近是路緣
    }

    @Test
    fun normalLaneIsNotShoulder() {
        assertFalse(RoadsideRules.onShoulder(listOf(line(-1.7f, true), line(1.8f, false)), null))   // 左虛線
        assertFalse(RoadsideRules.onShoulder(listOf(line(-1.7f, false), line(1.8f, true)), null))   // 右邊還有車道
        assertFalse(RoadsideRules.onShoulder(emptyList(), null))
    }
}
