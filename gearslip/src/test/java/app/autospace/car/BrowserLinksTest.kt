package app.autospace.car

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic coverage for the r190 "Open links in a new tab" anchor rule (Mariner's isAnchorLink, verbatim). */
class BrowserLinksTest {

    @Test
    fun anchorLinkIsSamePageWithFragmentChange() {
        assertTrue(isAnchorLink("https://example.com/page", "https://example.com/page#section"))
        assertTrue(isAnchorLink("https://example.com/page#top", "https://example.com/page#bottom"))
    }

    @Test
    fun differentPageIsNotAnAnchor() {
        assertFalse(isAnchorLink("https://example.com/page", "https://example.com/other"))
        assertFalse(isAnchorLink("https://example.com/page", "https://other.com/page"))
    }

    @Test
    fun blankUrlsAreNotAnchors() {
        assertFalse(isAnchorLink("", "https://example.com/page#section"))
        assertFalse(isAnchorLink("https://example.com/page", ""))
    }

    @Test
    fun sameUrlWithoutFragmentIsNotAnAnchor() {
        assertFalse(isAnchorLink("https://example.com/page", "https://example.com/page"))
    }
}
