package com.example.lyretest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlBarLayoutTest {
    @Test
    fun `narrow bar hides every optional part`() {
        val state = controlBarVisibility(220)
        assertFalse(state.showTitle)
        assertFalse(state.showNext)
        assertFalse(state.showStop)
    }

    @Test
    fun `optional controls return in priority order`() {
        assertTrue(controlBarVisibility(320).showStop)
        assertFalse(controlBarVisibility(320).showNext)
        assertTrue(controlBarVisibility(400).showNext)
        assertFalse(controlBarVisibility(400).showTitle)
        assertTrue(controlBarVisibility(480).showTitle)
    }
}
