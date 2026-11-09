package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserZoomTest {

    @Test
    fun siteZoomCodecRoundTrips() {
        val map = mapOf("example.com" to 75, "news.example.org" to 150)
        assertEquals(map, BrowserStore.decodeSiteZooms(BrowserStore.encodeSiteZooms(map)))
    }

    @Test
    fun siteZoomCodecSkipsBadLinesAndClamps() {
        val decoded = BrowserStore.decodeSiteZooms("example.com|75\nbroken\nnews.org|999\nempty|0\n")
        assertEquals(mapOf("example.com" to 75, "news.org" to 200), decoded)
    }

    @Test
    fun siteZoomLookupIsCaseInsensitiveAndDefaultsToZero() {
        val map = BrowserStore.decodeSiteZooms("Example.COM|125\n")
        assertEquals(125, BrowserStore.siteZoomForHost(map, "example.com"))
        assertEquals(0, BrowserStore.siteZoomForHost(map, "other.com"))
        assertEquals(0, BrowserStore.siteZoomForHost(emptyMap(), "example.com"))
    }

    @Test
    fun effectiveZoomPrefersSiteOverGlobal() {
        assertEquals(150, BrowserStore.effectiveTextZoom(150, 100))
        assertEquals(100, BrowserStore.effectiveTextZoom(0, 100))
    }

    @Test
    fun siteZoomSteppersMatchMariner() {
        assertEquals(75, BrowserStore.siteZoomStepDown(0))
        assertEquals(125, BrowserStore.siteZoomStepUp(0))
        assertEquals(125, BrowserStore.siteZoomStepDown(150))
        assertEquals(175, BrowserStore.siteZoomStepUp(150))
        assertEquals(50, BrowserStore.siteZoomStepDown(50))
        assertEquals(200, BrowserStore.siteZoomStepUp(200))
    }

    @Test
    fun encodeSortsHostsSoStorageIsStable() {
        assertEquals(
            "a.com|100\nb.com|150",
            BrowserStore.encodeSiteZooms(mapOf("b.com" to 150, "a.com" to 100)),
        )
    }
}
