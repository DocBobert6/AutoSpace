package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerNetTest {

    @Test fun userAgentMatchesMariner() {
        assertEquals(
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36",
            PlayerNet.USER_AGENT,
        )
    }

    @Test fun cacheConfigIs200MbLruInPlayerDir() {
        assertEquals(200L * 1024 * 1024, PlayerNet.CACHE_SIZE_BYTES)
        assertEquals("player", PlayerNet.CACHE_DIR_NAME)
    }

    @Test fun headersForHttpsAttachesRefererAndCookie() {
        val out = PlayerNet.headersFor(
            "https://cdn.example.com/v.m3u8",
            "https://page.example.org/watch",
            emptyMap(),
        ) { "session=abc" }
        assertEquals("https://page.example.org/watch", out["Referer"])
        assertEquals("session=abc", out["Cookie"])
    }

    @Test fun headersForHttpAttachesNothing() {
        val out = PlayerNet.headersFor(
            "http://cdn.example.com/v.m3u8",
            "https://page.example.org/watch",
            emptyMap(),
        ) { "session=abc" }
        assertTrue(out.isEmpty())
    }

    @Test fun headersForKeepsExistingHeaders() {
        val out = PlayerNet.headersFor(
            "https://cdn.example.com/v.m3u8",
            "https://page.example.org/watch",
            mapOf("Referer" to "https://other.example/", "Cookie" to "x=1"),
        ) { "session=abc" }
        assertTrue(out.isEmpty())
    }

    @Test fun headersForSkipsBlankRefererAndCookie() {
        val out = PlayerNet.headersFor("https://cdn.example.com/v.m3u8", "  ", emptyMap()) { "   " }
        assertTrue(out.isEmpty())
        val out2 = PlayerNet.headersFor("https://cdn.example.com/v.m3u8", null, emptyMap()) { null }
        assertTrue(out2.isEmpty())
    }

    @Test fun probeHeadersAlwaysCarriesUserAgent() {
        val saved = PlayerNet.referer
        try {
            PlayerNet.referer = null
            val out = PlayerNet.probeHeaders("https://cdn.example.com/v.m3u8")
            assertEquals(PlayerNet.USER_AGENT, out["User-Agent"])
            assertFalse("Referer" in out)
            // The JVM test classpath stubs CookieManager, so the live
            // cookie lookup fails safely inside runCatching: no Cookie
            // header here. The cookie half is covered on device.
            assertFalse("Cookie" in out)
        } finally {
            PlayerNet.referer = saved
        }
    }

    @Test fun probeHeadersCarriesRefererOverHttpsOnly() {
        val saved = PlayerNet.referer
        try {
            PlayerNet.referer = "https://page.example.org/watch"
            assertEquals(
                "https://page.example.org/watch",
                PlayerNet.probeHeaders("https://cdn.example.com/v.m3u8")["Referer"],
            )
            assertFalse("Referer" in PlayerNet.probeHeaders("http://cdn.example.com/v.m3u8"))
        } finally {
            PlayerNet.referer = saved
        }
    }
}
