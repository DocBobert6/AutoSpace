package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic coverage for the r21 browser batch (no Android framework needed). */
class BrowserExtrasTest {

    @Test
    fun cleanLinkStripsTrackingParamsAndKeepsTheRest() {
        assertEquals(
            "https://example.com/page?keep=1&other=2",
            CleanLink.strip("https://example.com/page?utm_source=x&keep=1&utm_medium=y&other=2"),
        )
        assertEquals(
            "https://example.com/page",
            CleanLink.strip("https://example.com/page?fbclid=abc&gclid=def"),
        )
        assertEquals(
            "https://example.com/page?keep=1#section",
            CleanLink.strip("https://example.com/page?keep=1&mc_cid=9#section"),
        )
    }

    @Test
    fun cleanLinkLeavesUntrackedAndNonHttpUrlsAlone() {
        assertEquals(
            "https://example.com/page?keep=1",
            CleanLink.strip("https://example.com/page?keep=1"),
        )
        assertEquals(
            "mailto:someone@example.com?subject=hi&utm_source=x",
            CleanLink.strip("mailto:someone@example.com?subject=hi&utm_source=x"),
        )
        assertEquals("", CleanLink.strip(""))
    }

    @Test
    fun searchTemplateAcceptsValidHttpsTemplateAndRefusesTheRest() {
        assertNull(SearchTemplate.validate("https://example.com/search?q=%s"))
        assertTrue(SearchTemplate.validate("https://example.com/search?q=x")!!.contains("%s"))
        assertTrue(SearchTemplate.validate("http://example.com/search?q=%s")!!.contains("https://"))
        assertTrue(SearchTemplate.validate("javascript:alert(1)")!!.contains("can't be used"))
        assertTrue(SearchTemplate.validate("data:text/html,%s")!!.contains("can't be used"))
        assertTrue(SearchTemplate.validate("file:///search?q=%s")!!.contains("can't be used"))
        assertTrue(SearchTemplate.validate("")!!.contains("Enter"))
    }

    @Test
    fun filterBlocksListedHostsAndTheirSubdomainsOnly() {
        assertTrue(BrowserFilter.isBlockedHost("doubleclick.net"))
        assertTrue(BrowserFilter.isBlockedHost("ads.doubleclick.net"))
        assertTrue(BrowserFilter.isBlockedHost("www.google-analytics.com"))
        assertTrue(BrowserFilter.isBlockedUrl("https://connect.facebook.net/lib.js"))
        assertFalse(BrowserFilter.isBlockedHost("example.com"))
        assertFalse(BrowserFilter.isBlockedHost("notdoubleclick.net"))
        assertFalse(BrowserFilter.isBlockedHost(null))
        assertFalse(BrowserFilter.isBlockedUrl("https://example.com/page"))
    }

    @Test
    fun sessionCodecRoundTripsTabsAndActiveIndex() {
        val pages = listOf(
            SavedPage("https://example.com/one", "One"),
            SavedPage("https://example.com/two?a=1&b=2", ""),
        )
        val (decoded, active) = decodeSession(encodeSession(pages, 1))
        assertEquals(pages, decoded)
        assertEquals(1, active)
        assertEquals(emptyList<SavedPage>() to 0, decodeSession(""))
        val (_, clamped) = decodeSession(encodeSession(pages, 99))
        assertEquals(1, clamped)
    }

    @Test
    fun pagesCodecIgnoresMalformedLines() {
        val decoded = decodePages("garbage\nhttps%3A%2F%2Fexample.com%2Fpage|Title\n|NoUrl")
        assertEquals(listOf(SavedPage("https://example.com/page", "Title")), decoded)
    }

    @Test
    fun readerSanitizeDropsScriptsAndJavascriptUrls() {
        val dirty = """<p onclick="evil()">Hello</p><script>alert(1)</script><a href="javascript:evil()">x</a>"""
        val clean = ArticleExtractor.sanitize(dirty)
        assertFalse(clean.contains("<script"))
        assertFalse(clean.contains("onclick"))
        assertFalse(clean.contains("javascript:"))
        assertTrue(clean.contains("Hello"))
    }

    @Test
    fun readerChunksNeverExceedTheCapByMuchAndCoverAllSentences() {
        val text = (1..20).joinToString(" ") { "Sentence number $it ends here." }
        val chunks = ArticleExtractor.sentenceChunks(text, maxChars = 100)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 130 })
        assertEquals(text, chunks.joinToString(" "))
    }

    @Test
    fun readerPlainTextKeepsParagraphOrder() {
        val article = Article(
            url = "https://example.com/a",
            title = "T",
            byline = "",
            siteName = "",
            contentHtml = "<h1>Head</h1><p>First para.</p><p>Second para.</p>",
        )
        assertEquals("Head\nFirst para.\nSecond para.", ArticleExtractor.plainText(article))
    }
}
