package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r82: pins the backspace hold-to-repeat timing model. The gesture
 * itself lives in Compose (BackspaceKey); this pins the values and the
 * lift rule the gesture implements, so a later retune must update the
 * test deliberately, not by accident.
 */
class BackspaceRepeatTest {

    @Test
    fun repeatTimingIsCarSized() {
        // Half a second before repeating: a slow tap never repeats.
        assertEquals(500L, BackspaceRepeatPolicy.INITIAL_DELAY_MS)
        // Then a steady ten deletes a second — no acceleration, so the
        // count while driving stays predictable.
        assertEquals(100L, BackspaceRepeatPolicy.REPEAT_INTERVAL_MS)
    }

    @Test
    fun liftDeletesOnlyWhenRepeatNeverFired() {
        assertTrue(BackspaceRepeatPolicy.liftDeletes(repeatFired = false))
        assertFalse(BackspaceRepeatPolicy.liftDeletes(repeatFired = true))
    }
}
