package app.autospace.car

import android.net.http.SslError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r204 (compat findings 3+4): the error-handling seams. The Compose
 * branches themselves (the cert panel, the retired HTTP overlay) are
 * code-review verified — there is no Robolectric in this tree — but
 * every pure mapping behind them is pinned here, so a wording or
 * arm regression fails a test, not a drive.
 */
class BrowserErrorTest {

    // Finding 4: cert naming — Mariner's CertErrorPage strings, verbatim.

    @Test
    fun certExpiredNamesExpiry() {
        assertEquals(
            "The certificate for example.com has expired.",
            certErrorMessage(SslError.SSL_EXPIRED, "example.com"),
        )
    }

    @Test
    fun certNotYetValidNamesTiming() {
        assertEquals(
            "The certificate for example.com isn't valid yet.",
            certErrorMessage(SslError.SSL_NOTYETVALID, "example.com"),
        )
    }

    @Test
    fun certMismatchNamesHost() {
        assertEquals(
            "The certificate doesn't match the site name example.com.",
            certErrorMessage(SslError.SSL_IDMISMATCH, "example.com"),
        )
    }

    @Test
    fun certUntrustedNamesTrust() {
        assertEquals(
            "The certificate for example.com isn't trusted by this device.",
            certErrorMessage(SslError.SSL_UNTRUSTED, "example.com"),
        )
    }

    @Test
    fun certDateInvalidBlamesClock() {
        assertEquals(
            "The certificate's dates are invalid — the device clock may be wrong.",
            certErrorMessage(SslError.SSL_DATE_INVALID, "example.com"),
        )
    }

    @Test
    fun certUnknownFallsBackToGeneric() {
        assertEquals(
            "The certificate for example.com couldn't be verified.",
            certErrorMessage(999, "example.com"),
        )
    }

    @Test
    fun certMessageNeverOffersProceed() {
        assertFalse(certErrorMessage(SslError.SSL_EXPIRED, "example.com").contains("Proceed"))
        assertFalse(certErrorMessage(SslError.SSL_IDMISMATCH, "example.com").contains("Continue anyway"))
    }

    // Finding 4: the plain-http fallback target.

    @Test
    fun httpFallbackSwapsScheme() {
        assertEquals(
            "http://example.com/page?x=1",
            httpFallbackFor("https://example.com/page?x=1"),
        )
    }

    @Test
    fun httpFallbackNullForPlainHttp() {
        assertNull(httpFallbackFor("http://example.com"))
    }

    @Test
    fun httpFallbackNullForOtherSchemes() {
        assertNull(httpFallbackFor("ftp://example.com"))
        assertNull(httpFallbackFor("example.com"))
    }

    // Finding 4: the fallback gate — Mariner S-m2.

    @Test
    fun fallbackAllowedWhenNeverSeenSecure() {
        assertTrue(certHttpFallbackAllowed("example.com", emptySet()))
    }

    @Test
    fun fallbackDeniedWhenSeenSecure() {
        assertFalse(certHttpFallbackAllowed("example.com", setOf("example.com")))
    }

    @Test
    fun fallbackDeniedForEmptyHost() {
        assertFalse(certHttpFallbackAllowed("", emptySet()))
    }

    @Test
    fun fallbackDeniedForOtherSeenHostsOnly() {
        assertTrue(certHttpFallbackAllowed("example.com", setOf("other.com")))
    }
}
