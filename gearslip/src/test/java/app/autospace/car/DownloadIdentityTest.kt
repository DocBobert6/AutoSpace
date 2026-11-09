package app.autospace.car

import android.app.DownloadManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for the r203 download identity (compat
 * finding 2, no Android framework needed): header construction
 * from referer/cookies/UA, the HTTPS-only credential rule,
 * Referer chaining, the host-scoped cookie snapshot, private
 * download visibility, the record codec round-trip for the three
 * new fields, and the legacy-row default. The live CookieManager
 * reads and the DownloadManager enqueue itself rest on CI
 * compilation plus the device checks in the round report.
 */
class DownloadIdentityTest {

    private val identity = BrowserDownloads.DownloadIdentity(
        referer = "https://example.com/page?x=1",
        cookie = "session=abc; other=1",
        userAgent = "TabAgent/1.0",
    )

    @Test
    fun headersCarryFullIdentityOverHttps() {
        val headers = BrowserDownloads.downloadHeaders(identity, includeCredentials = true)
        assertEquals("TabAgent/1.0", headers["User-Agent"])
        assertEquals("https://example.com/page?x=1", headers["Referer"])
        assertEquals("session=abc; other=1", headers["Cookie"])
    }

    @Test
    fun headersOmitCredentialsOverHttp() {
        // Mariner's S-M8 rule: Referer + Cookie never ride a
        // non-HTTPS request; the user agent always rides.
        val headers = BrowserDownloads.downloadHeaders(identity, includeCredentials = false)
        assertEquals(mapOf("User-Agent" to "TabAgent/1.0"), headers)
    }

    @Test
    fun headersOmitBlankRefererAndCookie() {
        val blank = BrowserDownloads.DownloadIdentity(referer = "  ", cookie = "", userAgent = null)
        val headers = BrowserDownloads.downloadHeaders(blank, includeCredentials = true)
        assertEquals(mapOf("User-Agent" to PlayerNet.USER_AGENT), headers)
    }

    @Test
    fun headersWithoutUserAgentFlagKeepsCredentials() {
        // The DASH resolver shape: the agent rides the HTTP
        // factory instead, so the header map carries only the
        // credentials — still HTTPS-gated.
        val headers = BrowserDownloads.downloadHeaders(
            identity,
            includeCredentials = true,
            includeUserAgent = false,
        )
        assertFalse(headers.containsKey("User-Agent"))
        assertEquals("https://example.com/page?x=1", headers["Referer"])
        assertEquals("session=abc; other=1", headers["Cookie"])
    }

    @Test
    fun userAgentFallsBackToMobileDefault() {
        assertEquals(PlayerNet.USER_AGENT, BrowserDownloads.downloadUserAgent(null))
        assertEquals(PlayerNet.USER_AGENT, BrowserDownloads.downloadUserAgent("   "))
        assertEquals("TabAgent/1.0", BrowserDownloads.downloadUserAgent("TabAgent/1.0"))
    }

    @Test
    fun hostOfUrlExtractsBareHost() {
        assertEquals("example.com", BrowserDownloads.hostOfUrl("https://example.com/a/b.m3u8"))
        assertEquals("example.com", BrowserDownloads.hostOfUrl("https://user@EXAMPLE.com:8443/a?x=1#frag"))
        assertEquals("cdn.example.com", BrowserDownloads.hostOfUrl("http://cdn.example.com/seg0.ts"))
        assertNull(BrowserDownloads.hostOfUrl("not a url"))
        assertNull(BrowserDownloads.hostOfUrl(""))
    }

    @Test
    fun isHttpsUrlIsCaseInsensitive() {
        assertTrue(BrowserDownloads.isHttpsUrl("https://example.com/a"))
        assertTrue(BrowserDownloads.isHttpsUrl("HTTPS://example.com/a"))
        assertFalse(BrowserDownloads.isHttpsUrl("http://example.com/a"))
        assertFalse(BrowserDownloads.isHttpsUrl("ftp://example.com/a"))
    }

    @Test
    fun cookieSnapshotWinsForOwnHost() {
        val snapshot = BrowserDownloads.CookieSnapshot("example.com", "session=abc")
        val got = BrowserDownloads.cookieForDownloadRequest(
            "https://example.com/seg0.ts",
            snapshot,
            isPrivate = false,
        ) { "live=should-not-win" }
        assertEquals("session=abc", got)
    }

    @Test
    fun cookieSnapshotIgnoredForOtherHostNonPrivateReadsLive() {
        val snapshot = BrowserDownloads.CookieSnapshot("example.com", "session=abc")
        val got = BrowserDownloads.cookieForDownloadRequest(
            "https://cdn.example.com/seg0.ts",
            snapshot,
            isPrivate = false,
        ) { "live=xyz" }
        assertEquals("live=xyz", got)
    }

    @Test
    fun cookieSnapshotIgnoredForOtherHostPrivateSendsNothing() {
        // A private download never re-reads the live jar, and a
        // snapshot for another host never leaks across hosts.
        val snapshot = BrowserDownloads.CookieSnapshot("example.com", "session=abc")
        val got = BrowserDownloads.cookieForDownloadRequest(
            "https://cdn.example.com/seg0.ts",
            snapshot,
            isPrivate = true,
        ) { "live=should-never-appear" }
        assertNull(got)
    }

    @Test
    fun cookiePrivateNeverReadsLiveJar() {
        val got = BrowserDownloads.cookieForDownloadRequest(
            "https://example.com/seg0.ts",
            snapshot = null,
            isPrivate = true,
        ) { "live=should-never-appear" }
        assertNull(got)
    }

    @Test
    fun privateDownloadsHiddenFromShade() {
        assertEquals(
            DownloadManager.Request.VISIBILITY_HIDDEN,
            BrowserDownloads.downloadNotificationVisibility(true),
        )
        assertEquals(
            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            BrowserDownloads.downloadNotificationVisibility(false),
        )
    }

    @Test
    fun identityOfRoundTripsRecord() {
        val record = BrowserStore.DownloadRecord(
            id = "dl-id",
            url = "https://example.com/a.mp4",
            fileName = "a.mp4",
            state = BrowserStore.DownloadState.IN_PROGRESS,
            downloadManagerId = -1L,
            localSource = null,
            isPrivate = false,
            createdAtMs = 1_000_000L,
            referer = "https://example.com/page",
            cookie = "session=abc",
            userAgent = "TabAgent/1.0",
        )
        assertEquals(identity.copy(referer = "https://example.com/page", cookie = "session=abc"), BrowserDownloads.identityOf(record))
    }

    @Test
    fun identityOfLegacyRecordIsNull() {
        val record = BrowserStore.DownloadRecord(
            id = "dl-old",
            url = "https://example.com/a.mp4",
            fileName = "a.mp4",
            state = BrowserStore.DownloadState.COMPLETE,
            downloadManagerId = 7L,
            localSource = null,
            isPrivate = false,
            createdAtMs = 1_000_000L,
        )
        assertEquals(BrowserDownloads.DownloadIdentity(null, null, null), BrowserDownloads.identityOf(record))
    }

    @Test
    fun refererChainingKeepsOtherFields() {
        val chained = identityWithReferer(identity, "https://example.com/master.m3u8")
        assertEquals("https://example.com/master.m3u8", chained.referer)
        assertEquals("session=abc; other=1", chained.cookie)
        assertEquals("TabAgent/1.0", chained.userAgent)
    }

    @Test
    fun codecRoundTripPreservesIdentity() {
        val withIdentity = BrowserStore.DownloadRecord(
            id = "dl-id",
            url = "https://example.com/a.mp4",
            fileName = "a.mp4",
            state = BrowserStore.DownloadState.IN_PROGRESS,
            downloadManagerId = -1L,
            localSource = null,
            isPrivate = true,
            createdAtMs = 1_000_000L,
            isMedia = true,
            kind = BrowserStore.DownloadKind.HLS,
            referer = "https://example.com/page?x=1&y=2",
            cookie = "session=abc; other=1",
            userAgent = "TabAgent/1.0",
        )
        val decoded = BrowserStore.decodeDownloads(BrowserStore.encodeDownloads(listOf(withIdentity)))
        assertEquals(1, decoded.size)
        assertEquals(withIdentity, decoded[0])
    }

    @Test
    fun codecPre203RowsDecodeNullIdentity() {
        // An 11-field (pre-r203) row decodes with null identity,
        // so old records download exactly as before the round.
        val eleven = "dl-old|https://example.com/a.mp4|a.mp4|COMPLETE|7||0|1000000|0|-1|HLS"
        val decoded = BrowserStore.decodeDownloads(eleven)
        assertEquals(1, decoded.size)
        assertNull(decoded[0].referer)
        assertNull(decoded[0].cookie)
        assertNull(decoded[0].userAgent)
        assertEquals(BrowserStore.DownloadKind.HLS, decoded[0].kind)
    }
}
