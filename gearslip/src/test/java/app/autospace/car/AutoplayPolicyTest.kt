package app.autospace.car

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoplayPolicyTest {
    private val dayMs = 24L * 60L * 60L * 1000L

    @Test fun recentWithinWindow() {
        assertTrue(AutoplayPolicy.isRecent(1_000L, 1_000L + 29L * dayMs))
    }

    @Test fun staleBeyondWindow() {
        assertFalse(AutoplayPolicy.isRecent(1_000L, 1_000L + 31L * dayMs))
    }

    @Test fun exactlyAtWindowEdgeIsRecent() {
        assertTrue(AutoplayPolicy.isRecent(1_000L, 1_000L + AutoplayPolicy.RECENT_WINDOW_MS))
    }

    @Test fun oneMsPastWindowIsStale() {
        assertFalse(AutoplayPolicy.isRecent(1_000L, 1_000L + AutoplayPolicy.RECENT_WINDOW_MS + 1L))
    }

    @Test fun unknownTimestampIsNotRecent() {
        assertFalse(AutoplayPolicy.isRecent(0L, 1_000_000L))
    }

    @Test fun stopInsideWindowCounts() {
        assertTrue(AutoplayPolicy.countsAsAutoplayStop(10_000L, 10_000L + 60_000L))
    }

    @Test fun stopAtWindowEdgeCounts() {
        assertTrue(AutoplayPolicy.countsAsAutoplayStop(10_000L, 10_000L + AutoplayPolicy.STOP_WINDOW_MS))
    }

    @Test fun stopAfterWindowDoesNotCount() {
        assertFalse(AutoplayPolicy.countsAsAutoplayStop(10_000L, 10_000L + AutoplayPolicy.STOP_WINDOW_MS + 1L))
    }

    @Test fun noSessionNeverCounts() {
        assertFalse(AutoplayPolicy.countsAsAutoplayStop(null, 10_000L))
    }

    @Test fun pauseBeforeStartNeverCounts() {
        assertFalse(AutoplayPolicy.countsAsAutoplayStop(10_000L, 9_000L))
    }

    @Test fun twoStopsDoNotOffer() {
        assertFalse(AutoplayPolicy.shouldOffer(2))
    }

    @Test fun threeStopsOffer() {
        assertTrue(AutoplayPolicy.shouldOffer(3))
    }

    @Test fun moreStopsStillOffer() {
        assertTrue(AutoplayPolicy.shouldOffer(4))
    }
}
