package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure-logic coverage for the Phase 6 (r186) pill swipe: it stays in the active tab's class. */
class BrowserPillTest {

    // false = normal tab, true = private tab.
    private val mixed = listOf(false, true, false, false, true)

    @Test
    fun swipeNextStaysInClassSkippingPrivateTabs() {
        // From normal tab 0, the next normal tab is 2 (1 is private).
        assertEquals(2, sameClassSwipeTarget(mixed, 0, true))
    }

    @Test
    fun swipeNextFromLastNormalInClassIsNull() {
        assertNull(sameClassSwipeTarget(mixed, 3, true))
    }

    @Test
    fun swipePreviousStaysInClassSkippingPrivateTabs() {
        // From normal tab 3, the previous normal tab is 2.
        assertEquals(2, sameClassSwipeTarget(mixed, 3, false))
    }

    @Test
    fun swipePreviousFromFirstNormalInClassIsNull() {
        assertNull(sameClassSwipeTarget(mixed, 0, false))
    }

    @Test
    fun privateTabsSwipeAmongThemselvesOnly() {
        assertEquals(4, sameClassSwipeTarget(mixed, 1, true))
        assertEquals(1, sameClassSwipeTarget(mixed, 4, false))
        assertNull(sameClassSwipeTarget(mixed, 4, true))
    }

    @Test
    fun singleTabInItsClassGoesNowhere() {
        assertNull(sameClassSwipeTarget(listOf(false), 0, true))
        assertNull(sameClassSwipeTarget(listOf(false), 0, false))
    }

    @Test
    fun invalidActiveIndexIsNull() {
        assertNull(sameClassSwipeTarget(mixed, -1, true))
        assertNull(sameClassSwipeTarget(mixed, 5, false))
        assertNull(sameClassSwipeTarget(emptyList(), 0, true))
    }
}
