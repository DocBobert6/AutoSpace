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

    @Test
    fun parseMenuHiddenBlankMeansNothingHidden() {
        assertEquals(emptySet<String>(), BrowserStore.parseMenuHidden(""))
        assertEquals(emptySet<String>(), BrowserStore.parseMenuHidden("   "))
    }

    @Test
    fun parseMenuHiddenTrimsDedupesAndDropsUnknownIds() {
        assertEquals(
            setOf("newTab", "desktopSite"),
            BrowserStore.parseMenuHidden(" newTab , desktopSite, newTab, notARow, "),
        )
    }

    @Test
    fun parseMenuHiddenKeepsEveryKnownRow() {
        val all = BrowserMenuRows.DEFAULT_ORDER.joinToString(",")
        assertEquals(BrowserMenuRows.DEFAULT_ORDER.toSet(), BrowserStore.parseMenuHidden(all))
    }

    @Test
    fun parseFrequentHiddenBlankMeansNothingHidden() {
        assertEquals(emptySet<String>(), BrowserStore.parseFrequentHidden(""))
        assertEquals(emptySet<String>(), BrowserStore.parseFrequentHidden("   \n  "))
    }

    @Test
    fun parseFrequentHiddenTrimsDedupesAndDropsBlanks() {
        assertEquals(
            setOf("example.com", "other.com"),
            BrowserStore.parseFrequentHidden(" example.com \nother.com\nexample.com\n\n"),
        )
    }

    @Test
    fun decodeTitledEntryReadsLegacyPlainStrings() {
        assertEquals(
            SavedPage("https://example.com/a", ""),
            BrowserStore.decodeTitledEntry("https://example.com/a"),
        )
        assertNull(BrowserStore.decodeTitledEntry(""))
        assertNull(BrowserStore.decodeTitledEntry("   "))
        assertNull(BrowserStore.decodeTitledEntry("{not json"))
    }

    @Test
    fun decodeTitledEntryReadsTitledObjects() {
        assertEquals(
            SavedPage("https://example.com/a", "Example A"),
            BrowserStore.decodeTitledEntry("{\"url\":\"https://example.com/a\",\"title\":\"Example A\"}"),
        )
        // Key order and spacing do not matter.
        assertEquals(
            SavedPage("https://example.com/a", "Example A"),
            BrowserStore.decodeTitledEntry("{ \"title\" : \"Example A\" , \"url\" : \"https://example.com/a\" }"),
        )
        assertEquals(
            SavedPage("https://example.com/b", ""),
            BrowserStore.decodeTitledEntry("{\"url\":\"https://example.com/b\"}"),
        )
        assertNull(BrowserStore.decodeTitledEntry("{\"title\":\"No URL\"}"))
        assertNull(BrowserStore.decodeTitledEntry("{\"url\":\"\",\"title\":\"Blank\"}"))
    }

    @Test
    fun encodeDecodeTitledEntryRoundTripsTitlesWithQuotesAndEscapes() {
        val page = SavedPage(
            "https://example.com/a?x=1&y=2",
            "A \"Quoted\" Title \\ with \n newline",
        )
        assertEquals(page, BrowserStore.decodeTitledEntry(BrowserStore.encodeTitledEntry(page)))
        // An untitled entry encodes as a quoted URL string and decodes back bare.
        val untitled = SavedPage("https://example.com/a", "")
        assertEquals("\"https://example.com/a\"", BrowserStore.encodeTitledEntry(untitled))
        assertEquals(
            untitled,
            BrowserStore.decodeTitledEntry(BrowserStore.encodeTitledEntry(untitled)),
        )
    }

    @Test
    fun jsonQuoteAndParseStringRoundTrip() {
        val tricky = "quote \" backslash \\ newline \n tab \t unicode é"
        assertEquals(tricky, BrowserStore.jsonParseString(BrowserStore.jsonQuote(tricky)))
        assertNull(BrowserStore.jsonParseString("not quoted"))
        assertNull(BrowserStore.jsonParseString("\"unterminated"))
    }

    @Test
    fun displayTitleFallsBackToUrl() {
        assertEquals(
            "Example A",
            BrowserStore.displayTitle(SavedPage("https://example.com/a", "Example A")),
        )
        assertEquals(
            "https://example.com/a",
            BrowserStore.displayTitle(SavedPage("https://example.com/a", "")),
        )
    }

    @Test
    fun addTitledEntryDedupesByUrlAndRefreshesTitle() {
        val existing = listOf(
            SavedPage("https://example.com/a", "Old A"),
            SavedPage("https://example.com/b", "B"),
        )
        val added = BrowserStore.addTitledEntry(existing, SavedPage("https://example.com/a", "New A"), 200)
        assertEquals(
            listOf(SavedPage("https://example.com/a", "New A"), SavedPage("https://example.com/b", "B")),
            added,
        )
    }

    @Test
    fun addTitledEntryCapsAtCap() {
        val existing = (1..3).map { SavedPage("https://example.com/$it", "T$it") }
        val added = BrowserStore.addTitledEntry(existing, SavedPage("https://example.com/new", "New"), 3)
        assertEquals(3, added.size)
        assertEquals("https://example.com/new", added[0].url)
        assertFalse(added.any { it.url == "https://example.com/3" })
    }

    @Test
    fun gpcDefaultsOnWhenNothingStored() {
        assertTrue(BrowserStore.gpcEnabledFromStored(null))
        assertTrue(BrowserStore.gpcEnabledFromStored("true"))
    }

    @Test
    fun gpcOffOnlyWhenStoredFalse() {
        assertFalse(BrowserStore.gpcEnabledFromStored("false"))
    }

    @Test
    fun fillMissingTitleFillsOnlyBlankTitles() {
        val existing = listOf(
            SavedPage("https://example.com/a", ""),
            SavedPage("https://example.com/b", "Kept B"),
        )
        val filled = BrowserStore.fillMissingTitle(existing, "https://example.com/a", "Now A")
        assertEquals(SavedPage("https://example.com/a", "Now A"), filled[0])
        assertEquals(SavedPage("https://example.com/b", "Kept B"), filled[1])
        // A recorded title is never overwritten, and a blank title changes nothing.
        val untouched = BrowserStore.fillMissingTitle(existing, "https://example.com/b", "Other B")
        assertEquals(existing, untouched)
        assertEquals(existing, BrowserStore.fillMissingTitle(existing, "https://example.com/a", ""))
        assertEquals(existing, BrowserStore.fillMissingTitle(existing, "https://example.com/missing", "Nope"))
    }
}
