package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic coverage for the r200 clear-browsing-data sweep (no Android framework needed). */
class BrowserExtrasTest {

    @Test
    fun clearLabelsListEverySelectedPartInMarinersOrder() {
        assertEquals(
            listOf("history", "cookies", "cache", "site data", "open tabs"),
            clearBrowsingDataLabels(deleteOnExitSelection()),
        )
    }

    @Test
    fun clearLabelsOmitUnselectedParts() {
        assertEquals(
            listOf("cookies", "site data"),
            clearBrowsingDataLabels(
                ClearSelection(
                    history = false, cookies = true, cache = false,
                    siteData = true, tabs = false,
                ),
            ),
        )
    }

    @Test
    fun clearLabelsAreEmptyWhenNothingIsSelected() {
        assertEquals(
            emptyList<String>(),
            clearBrowsingDataLabels(
                ClearSelection(
                    history = false, cookies = false, cache = false,
                    siteData = false, tabs = false,
                ),
            ),
        )
    }

    @Test
    fun clearLabelsCarryTabsOnlyWhenSelected() {
        assertEquals(
            listOf("open tabs"),
            clearBrowsingDataLabels(
                ClearSelection(
                    history = false, cookies = false, cache = false,
                    siteData = false, tabs = true,
                ),
            ),
        )
    }

    @Test
    fun deleteOnExitSelectionCoversAllFiveTraces() {
        val selection = deleteOnExitSelection()
        assertTrue(selection.history)
        assertTrue(selection.cookies)
        assertTrue(selection.cache)
        assertTrue(selection.siteData)
        assertTrue(selection.tabs)
    }
}
