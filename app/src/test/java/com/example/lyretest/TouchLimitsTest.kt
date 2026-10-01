package com.example.lyretest

import com.example.lyretest.midi.LiveTouchState
import com.example.lyretest.player.REQUESTED_TOUCH_LIMIT
import com.example.lyretest.player.effectiveTouchLimit
import org.junit.Assert.assertEquals
import org.junit.Test

class TouchLimitsTest {
    @Test fun `phone budget remains ten even when the system accepts more strokes`() {
        assertEquals(10, REQUESTED_TOUCH_LIMIT)
        assertEquals(5, effectiveTouchLimit(5))
        assertEquals(10, effectiveTouchLimit(10))
        assertEquals(10, effectiveTouchLimit(20))
        assertEquals(10, effectiveTouchLimit(32))
        assertEquals(1, effectiveTouchLimit(0))
        assertEquals(1, effectiveTouchLimit(-1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `live touch budget must be positive`() {
        LiveTouchState(maxStrokes = 0)
    }
}
