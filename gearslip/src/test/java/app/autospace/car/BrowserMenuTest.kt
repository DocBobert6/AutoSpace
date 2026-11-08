package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic coverage for the r185 browser menu registry (no Android, no Compose). */
class BrowserMenuTest {

    @Test
    fun registryIdsAreUniqueWithNonBlankLabels() {
        val ids = BrowserMenuRows.ALL.map { it.first }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(BrowserMenuRows.ALL.all { it.second.isNotBlank() })
    }

    @Test
    fun defaultOrderCoversEveryRegisteredRowExactlyOnce() {
        assertEquals(
            BrowserMenuRows.ALL.map { it.first }.toSet(),
            BrowserMenuRows.DEFAULT_ORDER.toSet(),
        )
        assertEquals(BrowserMenuRows.ALL.size, BrowserMenuRows.DEFAULT_ORDER.size)
    }

    @Test
    fun everyRowHasASectionAndCustomizeIsNeverARow() {
        // The Customize row is not in the registry: it is never
        // hideable, so the menu always has a way back to the editor.
        assertFalse("customize" in BrowserMenuRows.DEFAULT_ORDER)
        for (id in BrowserMenuRows.DEFAULT_ORDER) {
            assertTrue(id in BrowserMenuRows.ALL.map { it.first })
            BrowserMenuRows.sectionOf(id) // must not throw; every id maps
        }
    }

    @Test
    fun privateTabsHideOnlyThePersistingRows() {
        assertFalse(BrowserMenuRows.visibleForTab(BrowserMenuRows.BOOKMARK, true))
        assertFalse(BrowserMenuRows.visibleForTab(BrowserMenuRows.PIN, true))
        for (id in BrowserMenuRows.DEFAULT_ORDER) {
            if (id != BrowserMenuRows.BOOKMARK && id != BrowserMenuRows.PIN) {
                assertTrue(id, BrowserMenuRows.visibleForTab(id, true))
            }
            assertTrue(id, BrowserMenuRows.visibleForTab(id, false))
        }
    }

    @Test
    fun hiddenRowsDoNotRender() {
        val hidden = setOf(BrowserMenuRows.RELOAD, BrowserMenuRows.HISTORY)
        assertFalse(BrowserMenuRows.isVisible(BrowserMenuRows.RELOAD, hidden))
        assertFalse(BrowserMenuRows.isVisible(BrowserMenuRows.HISTORY, hidden))
        assertTrue(BrowserMenuRows.isVisible(BrowserMenuRows.NEW_TAB, hidden))
        assertTrue(BrowserMenuRows.isVisible(BrowserMenuRows.SETTINGS, emptySet()))
    }
}
