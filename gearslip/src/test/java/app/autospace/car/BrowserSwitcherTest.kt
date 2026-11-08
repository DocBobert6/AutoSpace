package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-logic coverage for the Phase 3 (r188) same-class tab count: the bar button and the switcher title show the active tab's class, not the total. */
class BrowserSwitcherTest {

    // false = normal tab, true = private tab.
    private val mixed = listOf(false, true, false, false, true)

    @Test
    fun normalActiveCountsOnlyNormalTabs() {
        assertEquals(3, tabClassCount(mixed, 0))
        assertEquals(3, tabClassCount(mixed, 3))
    }

    @Test
    fun privateActiveCountsOnlyPrivateTabs() {
        assertEquals(2, tabClassCount(mixed, 1))
        assertEquals(2, tabClassCount(mixed, 4))
    }

    @Test
    fun singleTabCountsOne() {
        assertEquals(1, tabClassCount(listOf(false), 0))
        assertEquals(1, tabClassCount(listOf(true), 0))
    }

    @Test
    fun allSameClassCountsAll() {
        assertEquals(3, tabClassCount(listOf(true, true, true), 1))
    }

    @Test
    fun invalidActiveIndexFallsBackToTotal() {
        assertEquals(5, tabClassCount(mixed, -1))
        assertEquals(5, tabClassCount(mixed, 5))
        assertEquals(0, tabClassCount(emptyList(), 0))
    }
}
