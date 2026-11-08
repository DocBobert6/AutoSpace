package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun httpsUpgradeUpgradesPlainHttp() {
        assertEquals("https://example.com/page", httpsUpgradeFor(emptySet(), "http://example.com/page"))
        assertEquals(
            "https://example.com:8080/a?b=c#d",
            httpsUpgradeFor(emptySet(), "http://example.com:8080/a?b=c#d"),
        )
    }

    @Test
    fun httpsUpgradeSkipsNonHttpAndLocal() {
        assertNull(httpsUpgradeFor(emptySet(), "https://example.com/page"))
        assertNull(httpsUpgradeFor(emptySet(), "http://localhost/page"))
        assertNull(httpsUpgradeFor(emptySet(), "http://printer.local/page"))
        assertNull(httpsUpgradeFor(emptySet(), "http://192.168.1.1/page"))
        assertNull(httpsUpgradeFor(emptySet(), "http://10.0.0.5/"))
        assertNull(httpsUpgradeFor(emptySet(), ""))
    }

    @Test
    fun httpsUpgradeSkipsFailedHosts() {
        assertNull(httpsUpgradeFor(setOf("example.com"), "http://example.com/page"))
        assertEquals("https://other.com/", httpsUpgradeFor(setOf("example.com"), "http://other.com/"))
    }

    @Test
    fun httpsHostExtraction() {
        assertEquals("example.com", httpsHostOf("http://user:pass@example.com:8080/a?b#c"))
        assertEquals("example.com", httpsHostOf("https://EXAMPLE.COM/"))
        assertEquals("::1", httpsHostOf("http://[::1]/"))
        assertEquals("", httpsHostOf("example.com"))
    }

    @Test
    fun httpsWarnNeedsModeOnAndPlainHttp() {
        assertFalse(httpsWarnNeeded(false, emptySet(), "", "http://example.com/"))
        assertFalse(httpsWarnNeeded(true, emptySet(), "", "https://example.com/"))
        assertTrue(httpsWarnNeeded(true, emptySet(), "", "http://example.com/"))
        assertFalse(httpsWarnNeeded(true, setOf("example.com"), "", "http://example.com/"))
        assertFalse(httpsWarnNeeded(true, emptySet(), "example.com", "http://example.com/"))
        assertFalse(httpsWarnNeeded(true, emptySet(), "EXAMPLE.COM", "http://example.com/"))
    }
}
