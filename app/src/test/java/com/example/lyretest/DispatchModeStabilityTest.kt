package com.example.lyretest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DispatchModeStabilityTest {
    @Test
    fun `requires five consecutive eligible successes`() {
        val tracker = DispatchModeStability()
        repeat(4) {
            assertFalse(tracker.recordSuccess(AutomaticDispatchMode.HANDLER_THREAD, true))
        }
        assertTrue(tracker.recordSuccess(AutomaticDispatchMode.HANDLER_THREAD, true))
    }

    @Test
    fun `foreground validation and failure break success streak`() {
        val tracker = DispatchModeStability()
        repeat(4) { tracker.recordSuccess(AutomaticDispatchMode.MAIN_THREAD, true) }
        assertFalse(tracker.recordSuccess(AutomaticDispatchMode.MAIN_THREAD, false))
        repeat(4) {
            assertFalse(tracker.recordSuccess(AutomaticDispatchMode.MAIN_THREAD, true))
        }
        assertTrue(tracker.recordSuccess(AutomaticDispatchMode.MAIN_THREAD, true))

        tracker.reset()
        repeat(4) { tracker.recordSuccess(AutomaticDispatchMode.LOW_JITTER, true) }
        assertFalse(tracker.recordFailure(AutomaticDispatchMode.LOW_JITTER))
        assertFalse(tracker.recordSuccess(AutomaticDispatchMode.LOW_JITTER, true))
    }

    @Test
    fun `forgets after three consecutive failures`() {
        val tracker = DispatchModeStability()
        assertFalse(tracker.recordFailure(AutomaticDispatchMode.LOW_JITTER))
        assertFalse(tracker.recordFailure(AutomaticDispatchMode.LOW_JITTER))
        assertTrue(tracker.recordFailure(AutomaticDispatchMode.LOW_JITTER))
    }

    @Test
    fun `mode change resets both streaks`() {
        val tracker = DispatchModeStability()
        repeat(4) { tracker.recordSuccess(AutomaticDispatchMode.LOW_JITTER, true) }
        assertFalse(tracker.recordSuccess(AutomaticDispatchMode.HANDLER_THREAD, true))

        repeat(2) { assertFalse(tracker.recordFailure(AutomaticDispatchMode.MAIN_THREAD)) }
        assertFalse(tracker.recordFailure(AutomaticDispatchMode.HANDLER_THREAD))
    }
}
