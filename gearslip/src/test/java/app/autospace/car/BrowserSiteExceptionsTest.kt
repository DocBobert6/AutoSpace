package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSiteExceptionsTest {

    @Test
    fun hostSetCodecRoundTrips() {
        val hosts = setOf("example.com", "news.example.org")
        assertEquals(hosts, BrowserStore.decodeSiteHostSet(BrowserStore.encodeSiteHostSet(hosts)))
    }

    @Test
    fun hostSetCodecSkipsBlankLinesAndLowercases() {
        val decoded = BrowserStore.decodeSiteHostSet("Example.COM\n\nnews.org\n  \n")
        assertEquals(setOf("example.com", "news.org"), decoded)
    }

    @Test
    fun encodeSortsHostsSoStorageIsStable() {
        assertEquals(
            "a.com\nb.com",
            BrowserStore.encodeSiteHostSet(setOf("b.com", "a.com")),
        )
    }

    @Test
    fun lookupIsCaseInsensitiveAndDefaultsToAbsent() {
        val hosts = BrowserStore.decodeSiteHostSet("Example.COM\n")
        assertTrue(BrowserStore.isSiteHostIn(hosts, "example.com"))
        assertFalse(BrowserStore.isSiteHostIn(hosts, "other.com"))
        assertFalse(BrowserStore.isSiteHostIn(emptySet(), "example.com"))
    }

    @Test
    fun updateAddsAndRemovesAndIgnoresBlankHosts() {
        val added = BrowserStore.siteHostsWith(emptySet(), "Example.COM", true)
        assertEquals(setOf("example.com"), added)
        assertEquals(emptySet<String>(), BrowserStore.siteHostsWith(added, "example.com", false))
        assertEquals(emptySet<String>(), BrowserStore.siteHostsWith(emptySet(), "  ", true))
        assertEquals(setOf("a.com"), BrowserStore.siteHostsWith(setOf("a.com"), "", true))
    }

    @Test
    fun blockingAndCookieSetsAreIndependent() {
        val blocking = BrowserStore.siteHostsWith(emptySet(), "example.com", true)
        val cookies = BrowserStore.siteHostsWith(emptySet(), "other.org", true)
        assertTrue(BrowserStore.isSiteHostIn(blocking, "example.com"))
        assertFalse(BrowserStore.isSiteHostIn(blocking, "other.org"))
        assertTrue(BrowserStore.isSiteHostIn(cookies, "other.org"))
        assertFalse(BrowserStore.isSiteHostIn(cookies, "example.com"))
    }
}
