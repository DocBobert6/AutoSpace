package app.autospace.car

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic coverage for the r130 browser session rule (no Android framework needed). */
class BrowserStoreTest {

    @Test
    fun sessionIsFreshWithinFourHours() {
        val now = 1_000_000_000L
        assertTrue(BrowserStore.sessionIsFresh(now, now))
        assertTrue(BrowserStore.sessionIsFresh(now - 60_000L, now))
        assertTrue(BrowserStore.sessionIsFresh(now - (4L * 60 * 60 * 1000L - 1L), now))
    }

    @Test
    fun sessionIsStaleAtFourHoursAndBeyond() {
        val now = 1_000_000_000L
        val fourHours = 4L * 60 * 60 * 1000L
        assertFalse(BrowserStore.sessionIsFresh(now - fourHours, now))
        assertFalse(BrowserStore.sessionIsFresh(now - fourHours - 1L, now))
        assertFalse(BrowserStore.sessionIsFresh(now - 24L * 60 * 60 * 1000L, now))
    }

    @Test
    fun sessionWithNoStampCountsAsFresh() {
        // A session saved before the timestamp key existed has no
        // provable age — the rule restores rather than discards.
        assertTrue(BrowserStore.sessionIsFresh(0L, 1_000_000_000L))
        assertTrue(BrowserStore.sessionIsFresh(-1L, 1_000_000_000L))
    }

    @Test
    fun sessionWithClockSetBackwardsCountsAsFresh() {
        // A negative age proves nothing about 4 hours passing.
        assertTrue(BrowserStore.sessionIsFresh(1_000_000_000L, 999_000_000L))
    }
}
