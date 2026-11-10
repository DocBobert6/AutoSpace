package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** r221 (audit P-02/P-03): the blocking-config cache + invalidation and
 *  the derived-flow throttle. Pure seams only — no Context needed. */
class FilterListsTest {

    @Test
    fun blockingConfigForBuildsTheSnapshot() {
        val config = FilterLists.blockingConfigFor(setOf("example.org"), true, false)
        assertEquals(setOf("example.org"), config.siteBlockingOff)
        assertTrue(config.blockAds)
        assertFalse(config.blockTrackers)
    }

    @Test
    fun blockingConfigCacheReturnsTheSameInstanceUntilInvalidated() {
        val config = FilterLists.blockingConfigFor(setOf("example.org"), true, true)
        FilterLists.setCachedBlockingConfigForTest(config)
        assertSame(config, FilterLists.cachedBlockingConfigForTest())
        // A flipped toggle invalidates — the next read re-parses, it never
        // serves the stale snapshot.
        FilterLists.invalidateBlockingConfig()
        assertNull(FilterLists.cachedBlockingConfigForTest())
    }

    @Test
    fun derivedThrottlePublishesAt250ms() {
        assertFalse(FilterLists.shouldPublishDerived(249, 0))
        assertTrue(FilterLists.shouldPublishDerived(250, 0))
        assertTrue(FilterLists.shouldPublishDerived(1000, 0))
        assertFalse(FilterLists.shouldPublishDerived(1249, 1000))
        assertTrue(FilterLists.shouldPublishDerived(1250, 1000))
    }
}
