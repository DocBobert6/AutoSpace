package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun newsstandCodecRoundTripsContentWithNewlinesPipesAndQuotes() {
        val article = BrowserStore.SavedArticle(
            id = "id-1",
            title = "A | Tricky \"Title\"",
            siteName = "Example",
            byline = "By Someone",
            url = "https://example.com/a?x=1&y=2",
            savedAtMs = 1_700_000_000_000L,
            contentHtml = "<p>First line.</p>\n<p>Second | with pipe.</p>",
        )
        val decoded = BrowserStore.decodeNewsstand(BrowserStore.encodeNewsstand(listOf(article)))
        assertEquals(listOf(article), decoded)
    }

    @Test
    fun newsstandCodecIgnoresMalformedLines() {
        val decoded = BrowserStore.decodeNewsstand(
            "garbage\n" +
                "id-1|Title|Site|By|https%3A%2F%2Fexample.com%2Fa|1000|%3Cp%3EBody%3C%2Fp%3E\n" +
                "|NoId|Site|By|https://example.com/b|1000|Body\n" +
                "id-3|Title|Site|By||1000|Body",
        )
        assertEquals(1, decoded.size)
        assertEquals("https://example.com/a", decoded[0].url)
    }

    @Test
    fun newsstandCapKeepsTwoHundredAndAgesOutTheOldest() {
        val existing = (1..200).map {
            BrowserStore.SavedArticle("id-$it", "T$it", "", "", "https://example.com/$it", it.toLong(), "")
        }
        val added = BrowserStore.addToNewsstand(
            existing,
            BrowserStore.SavedArticle("id-new", "New", "", "", "https://example.com/new", 201L, ""),
        )
        assertNotNull(added)
        assertEquals(200, added!!.size)
        assertEquals("https://example.com/new", added[0].url)
        assertFalse(added.any { it.url == "https://example.com/200" })
        assertTrue(added.any { it.url == "https://example.com/199" })
    }

    @Test
    fun newsstandDuplicateUrlRefusesAndNewUrlSavesFirst() {
        val existing = listOf(
            BrowserStore.SavedArticle("id-1", "One", "", "", "https://example.com/one", 1L, ""),
        )
        assertNull(
            BrowserStore.addToNewsstand(
                existing,
                BrowserStore.SavedArticle("id-2", "Again", "", "", "https://example.com/one", 2L, ""),
            ),
        )
        val added = BrowserStore.addToNewsstand(
            existing,
            BrowserStore.SavedArticle("id-3", "Two", "", "", "https://example.com/two", 3L, ""),
        )
        assertEquals(2, added!!.size)
        assertEquals("https://example.com/two", added[0].url)
    }

    @Test
    fun newsstandSaveAllowedOnlyOutsidePrivateTabs() {
        assertTrue(BrowserStore.newsstandSaveAllowed(false))
        assertFalse(BrowserStore.newsstandSaveAllowed(true))
    }

    @Test
    fun newsstandSaveCarriesExactlyTheHandedArticle() {
        // Explicit handoff: the record is built from the article passed
        // in, verbatim — no cache sits between the reader and the store.
        val article = BrowserStore.SavedArticle(
            "id-x", "Handed Title", "Handed Site", "Handed By",
            "https://example.com/handed", 42L, "<p>Handed body.</p>",
        )
        val added = BrowserStore.addToNewsstand(emptyList(), article)
        assertEquals(listOf(article), added)
    }
}
