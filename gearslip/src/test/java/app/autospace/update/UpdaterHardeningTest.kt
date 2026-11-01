package app.autospace.update

import java.io.IOException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r172 (audit round R10, updater hardening). Pure functions only — the network,
 * prefs, and notification wiring behind them is covered by CI compile + the
 * device test list, not faked as unit tests. The seams are stated, not hidden:
 * fetchNewest throwing RateLimitedException, checkNow mapping it, dueForCheck
 * honoring the wait, and the download's Range/verify gates are code-review
 * verified; what is pinned here is every pure decision those paths route
 * through, so a wording or precedence slip fails loudly.
 */
class UpdaterHardeningTest {

    private val now = 1_700_000_000_000L

    @Test
    fun secureApkUrlAcceptsHttpsOnly() {
        assertTrue(UpdateChecker.isSecureApkUrl("https://github.com/DocBobert6/AutoSpace/releases/download/v0.4.119-alpha/AutoSpace-release.apk"))
        assertFalse(UpdateChecker.isSecureApkUrl("http://github.com/x.apk"))
        assertFalse(UpdateChecker.isSecureApkUrl("ftp://example.com/x.apk"))
        assertFalse(UpdateChecker.isSecureApkUrl("not a url"))
        assertFalse(UpdateChecker.isSecureApkUrl(""))
    }

    @Test
    fun rateLimitMessageUsesMinutesCeiled() {
        assertEquals(
            "GitHub is rate-limiting checks right now — try again in 1 minute.",
            UpdateChecker.rateLimitMessage(60L, null, now),
        )
        assertEquals(
            "GitHub is rate-limiting checks right now — try again in 2 minutes.",
            UpdateChecker.rateLimitMessage(61L, null, now),
        )
        assertEquals(
            "GitHub is rate-limiting checks right now — try again in 60 minutes.",
            UpdateChecker.rateLimitMessage(3600L, null, now),
        )
    }

    @Test
    fun rateLimitMessageFallsBackToResetThenToLater() {
        assertEquals(
            "GitHub is rate-limiting checks right now — try again in 5 minutes.",
            UpdateChecker.rateLimitMessage(null, now / 1000 + 300, now),
        )
        assertEquals(
            "GitHub is rate-limiting checks right now — try again later.",
            UpdateChecker.rateLimitMessage(null, null, now),
        )
        assertEquals(
            "GitHub is rate-limiting checks right now — try again later.",
            UpdateChecker.rateLimitMessage(0L, null, now),
        )
        assertEquals(
            "GitHub is rate-limiting checks right now — try again later.",
            UpdateChecker.rateLimitMessage(null, now / 1000 - 60, now),
        )
    }

    @Test
    fun rateLimitMessagePrefersRetryAfterOverReset() {
        assertEquals(
            "GitHub is rate-limiting checks right now — try again in 2 minutes.",
            UpdateChecker.rateLimitMessage(120L, now / 1000 + 3600, now),
        )
    }

    @Test
    fun rateLimitUntilMsTracksTheSameWait() {
        assertEquals(now + 120_000L, UpdateChecker.rateLimitUntilMs(120L, null, now))
        assertEquals((now / 1000 + 300) * 1000, UpdateChecker.rateLimitUntilMs(null, now / 1000 + 300, now))
        assertNull(UpdateChecker.rateLimitUntilMs(null, null, now))
        assertNull(UpdateChecker.rateLimitUntilMs(0L, null, now))
    }

    @Test
    fun checkFailureMessageKeepsKindsApart() {
        // Rate limits are their own kind — and the ordering matters, because the
        // rate-limit exception IS an IOException and must not fall into the
        // connection branch below it.
        assertTrue(UpdateChecker.RateLimitedException(429, null, null) is IOException)
        assertEquals(
            "GitHub is rate-limiting checks right now — try again in 1 minute.",
            UpdateChecker.checkFailureMessage(UpdateChecker.RateLimitedException(429, 60L, null), now),
        )
        assertEquals(
            "GitHub is rate-limiting checks right now — try again later.",
            UpdateChecker.checkFailureMessage(UpdateChecker.RateLimitedException(403, null, null), now),
        )
        // Actual network errors keep the existing connection message, verbatim.
        assertEquals(
            "Couldn't reach GitHub. Check the connection and try again.",
            UpdateChecker.checkFailureMessage(IOException("timeout"), now),
        )
        assertEquals(
            "Couldn't reach GitHub. Check the connection and try again.",
            UpdateChecker.checkFailureMessage(UnknownHostException("api.github.com"), now),
        )
        // Anything else — a bad answer, an unparseable body — never blames the
        // connection.
        val generic = UpdateChecker.checkFailureMessage(IllegalStateException("GitHub answered 500"), now)
        assertEquals("Couldn't check for updates. Try again.", generic)
        assertFalse(generic.contains("connection", ignoreCase = true))
    }

    @Test
    fun resumePlanCoversEveryPartialShape() {
        assertEquals(UpdateChecker.ResumePlan.FRESH, UpdateChecker.resumePlan(0L, 100L))
        assertEquals(UpdateChecker.ResumePlan.FRESH, UpdateChecker.resumePlan(-5L, 100L))
        assertEquals(UpdateChecker.ResumePlan.FRESH, UpdateChecker.resumePlan(0L, -1L))
        assertEquals(UpdateChecker.ResumePlan.RESUME, UpdateChecker.resumePlan(50L, 100L))
        assertEquals(UpdateChecker.ResumePlan.RESUME, UpdateChecker.resumePlan(50L, -1L))
        assertEquals(UpdateChecker.ResumePlan.VERIFY_COMPLETE, UpdateChecker.resumePlan(100L, 100L))
        assertEquals(UpdateChecker.ResumePlan.RESTART, UpdateChecker.resumePlan(150L, 100L))
    }

    @Test
    fun onlyA206ToARangedRequestAppends() {
        assertTrue(UpdateChecker.resumesStream(206, true))
        assertFalse(UpdateChecker.resumesStream(200, true))
        assertFalse(UpdateChecker.resumesStream(206, false))
        assertFalse(UpdateChecker.resumesStream(404, true))
    }

    @Test
    fun apkFileNameMatchesTheDownloadName() {
        assertEquals("AutoSpace-0.4.119-alpha.apk", UpdateChecker.apkFileName("0.4.119-alpha"))
    }
}
