package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic coverage for Phase 6 (r186): local suggestions + Frequently visited. */
class BrowserSuggestionsTest {

    @Test
    fun hostOfStripsSchemePathQueryPortAndUserinfo() {
        assertEquals("example.com", suggestionHostOf("https://example.com/a/b?x=1#frag"))
        assertEquals("example.com", suggestionHostOf("http://user@example.com:8080/a"))
        assertEquals("sub.example.com", suggestionHostOf("https://sub.example.com"))
        assertEquals("example.com", suggestionHostOf("example.com/a"))
    }

    @Test
    fun hostOfFallsBackToTheWholeUrlWhenNothingParses() {
        assertEquals("not a url", suggestionHostOf("not a url"))
    }

    @Test
    fun blankQuerySuggestsNothing() {
        assertEquals(
            emptyList<LocalSuggestion>(),
            localSuggestions(
                listOf(SavedPage("https://example.com", "")),
                listOf(SavedPage("https://example.com", "")),
                "",
            ),
        )
        assertEquals(
            emptyList<LocalSuggestion>(),
            localSuggestions(
                listOf(SavedPage("https://example.com", "")),
                listOf(SavedPage("https://example.com", "")),
                "   ",
            ),
        )
    }

    @Test
    fun bookmarksComeFirstAndDedupeAgainstHistory() {
        val bookmarks = listOf(
            SavedPage("https://example.com/a", ""),
            SavedPage("https://other.com/b", ""),
        )
        val history = listOf(
            SavedPage("https://example.com/a", ""),
            SavedPage("https://example.com/c", ""),
        )
        val got = localSuggestions(bookmarks, history, "example")
        assertEquals(
            listOf(
                LocalSuggestion("example.com", "https://example.com/a", true),
                LocalSuggestion("example.com", "https://example.com/c", false),
            ),
            got,
        )
    }

    @Test
    fun matchingIsCaseInsensitiveOnTheUrl() {
        val got = localSuggestions(
            emptyList(),
            listOf(SavedPage("https://Example.COM/News", "")),
            "EXAMPLE",
        )
        assertEquals(
            listOf(LocalSuggestion("Example.COM", "https://Example.COM/News", false)),
            got,
        )
    }

    @Test
    fun suggestionsCapAtSix() {
        val history = (1..9).map { SavedPage("https://site$it.example.com/page", "") }
        val got = localSuggestions(emptyList(), history, "example")
        assertEquals(6, got.size)
        assertEquals("https://site1.example.com/page", got.first().url)
        assertEquals("https://site6.example.com/page", got.last().url)
    }

    @Test
    fun frequentlyVisitedCountsDistinctPagesPerHost() {
        // History dedups re-visits, so a host with three distinct pages
        // outranks a host with one page visited many times.
        val history = listOf(
            SavedPage("https://solo.com/only", ""),
            SavedPage("https://busy.com/a", ""),
            SavedPage("https://busy.com/b", ""),
            SavedPage("https://busy.com/c", ""),
        )
        val got = frequentlyVisited(history, emptySet())
        assertEquals(2, got.size)
        assertEquals(FrequentHost("busy.com", "https://busy.com/a", 3), got[0])
        assertEquals(FrequentHost("solo.com", "https://solo.com/only", 1), got[1])
    }

    @Test
    fun frequentlyVisitedTiesBreakByMostRecentVisit() {
        val history = listOf(
            SavedPage("https://second.com/a", ""),
            SavedPage("https://first.com/a", ""),
            SavedPage("https://second.com/b", ""),
            SavedPage("https://first.com/b", ""),
        )
        // Both hosts have two pages; second.com's most recent visit
        // (index 0) is newer than first.com's (index 1).
        val got = frequentlyVisited(history, emptySet())
        assertEquals("second.com", got[0].host)
        assertEquals("first.com", got[1].host)
    }

    @Test
    fun frequentlyVisitedRepresentsAHostByItsMostRecentVisit() {
        val history = listOf(
            SavedPage("https://example.com/newest", ""),
            SavedPage("https://example.com/older", ""),
        )
        val got = frequentlyVisited(history, emptySet())
        assertEquals(1, got.size)
        assertEquals("https://example.com/newest", got[0].url)
    }

    @Test
    fun frequentlyVisitedExcludesHiddenHostsWithoutTouchingHistory() {
        val history = listOf(
            SavedPage("https://hidden.com/a", ""),
            SavedPage("https://shown.com/a", ""),
        )
        val got = frequentlyVisited(history, setOf("hidden.com"))
        assertEquals(listOf(FrequentHost("shown.com", "https://shown.com/a", 1)), got)
        // The hidden host's history entry is still in the input list.
        assertTrue(history.any { it.url == "https://hidden.com/a" })
    }

    @Test
    fun frequentlyVisitedCapsAtEight() {
        val history = (1..12).map { SavedPage("https://site$it.example.com/page", "") }
        assertEquals(8, frequentlyVisited(history, emptySet()).size)
    }

    @Test
    fun frequentlyVisitedEmptyHistoryIsEmpty() {
        assertEquals(emptyList<FrequentHost>(), frequentlyVisited(emptyList(), emptySet()))
    }

    @Test
    fun suggestionsUseTheStoredTitleWhenRecorded() {
        val bookmarks = listOf(SavedPage("https://example.com/news", "Example News"))
        val got = localSuggestions(bookmarks, emptyList(), "example")
        assertEquals(
            listOf(LocalSuggestion("Example News", "https://example.com/news", true)),
            got,
        )
    }

    @Test
    fun suggestionsMatchOnTitleAsWellAsUrl() {
        val history = listOf(SavedPage("https://example.com/xyz", "Morning Headlines"))
        assertEquals(
            listOf(LocalSuggestion("Morning Headlines", "https://example.com/xyz", false)),
            localSuggestions(emptyList(), history, "headlines"),
        )
        // A title match does not fire on an unrelated query.
        assertEquals(emptyList<LocalSuggestion>(), localSuggestions(emptyList(), history, "sports"))
    }

    @Test
    fun suggestionTitleFallsBackToHost() {
        assertEquals("example.com", suggestionTitle(SavedPage("https://example.com/a", "")))
        assertEquals("Kept Title", suggestionTitle(SavedPage("https://example.com/a", "Kept Title")))
    }
}
