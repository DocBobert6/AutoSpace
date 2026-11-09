package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic coverage for r183 open-in-app (no Android framework needed). */
class OpenInAppTest {

    @Test
    fun schemeOfLowercasesAndRejectsJunk() {
        assertEquals("https", OpenInApp.schemeOf("HTTPS://example.com/x"))
        assertEquals("market", OpenInApp.schemeOf("market://details?id=com.example"))
        assertEquals("tel", OpenInApp.schemeOf("tel:+15551234567"))
        assertEquals("intent", OpenInApp.schemeOf("intent://scan/#Intent;end"))
        assertEquals("", OpenInApp.schemeOf("example.com/path"))
        assertEquals("", OpenInApp.schemeOf(""))
        assertEquals("", OpenInApp.schemeOf("://no-scheme"))
        assertEquals("", OpenInApp.schemeOf("ht tp://space-in-scheme"))
    }

    @Test
    fun tapActionNeverHijacksHttp() {
        assertEquals(OpenInApp.TapAction.NONE, OpenInApp.tapAction("https://example.com/page"))
        assertEquals(OpenInApp.TapAction.NONE, OpenInApp.tapAction("http://example.com/page"))
        assertEquals(OpenInApp.TapAction.NONE, OpenInApp.tapAction("example.com"))
        assertEquals(OpenInApp.TapAction.NONE, OpenInApp.tapAction("example.com"))
        assertEquals(OpenInApp.TapAction.NONE, OpenInApp.tapAction(""))
    }

    @Test
    fun tapActionRoutesMarketTelAndIntentFallback() {
        assertEquals(OpenInApp.TapAction.VIEW, OpenInApp.tapAction("market://details?id=com.example"))
        assertEquals(OpenInApp.TapAction.CALL, OpenInApp.tapAction("tel:+15551234567"))
        assertEquals(OpenInApp.TapAction.CALL, OpenInApp.tapAction("TEL:911"))
        assertEquals(
            OpenInApp.TapAction.FALLBACK,
            OpenInApp.tapAction(
                "intent://scan/#Intent;scheme=zxing;package=com.example;" +
                    "S.browser_fallback_url=https%3A%2F%2Fexample.com%2Fscan;end",
            ),
        )
        assertEquals(OpenInApp.TapAction.NONE, OpenInApp.tapAction("intent://scan/#Intent;scheme=zxing;end"))
        assertEquals(
            OpenInApp.TapAction.NONE,
            OpenInApp.tapAction(
                "intent://scan/#Intent;S.browser_fallback_url=myapp%3A%2F%2Fopen;end",
            ),
        )
    }

    @Test
    fun tapActionRoutesDirectLaunchSchemes() {
        assertEquals(OpenInApp.TapAction.LAUNCH, OpenInApp.tapAction("mailto:someone@example.com"))
        assertEquals(OpenInApp.TapAction.LAUNCH, OpenInApp.tapAction("MAILTO:Someone@Example.com"))
        assertEquals(OpenInApp.TapAction.LAUNCH, OpenInApp.tapAction("geo:37.7749,-122.4194"))
        assertEquals(OpenInApp.TapAction.LAUNCH, OpenInApp.tapAction("sms:+15551234567"))
        assertEquals(OpenInApp.TapAction.LAUNCH, OpenInApp.tapAction("SMS:+15551234567?body=hi"))
    }

    @Test
    fun tapActionAsksFirstForUnknownSchemes() {
        assertEquals(OpenInApp.TapAction.ASK, OpenInApp.tapAction("myapp://open"))
        assertEquals(OpenInApp.TapAction.ASK, OpenInApp.tapAction("spotify:track:123"))
        assertEquals(OpenInApp.TapAction.ASK, OpenInApp.tapAction("whatsapp://send?phone=15551234567"))
    }

    @Test
    fun tapActionKeepsBlockedSchemesDead() {
        assertEquals(OpenInApp.TapAction.NONE, OpenInApp.tapAction("intent://scan/#Intent;scheme=zxing;end"))
        assertEquals(OpenInApp.TapAction.NONE, OpenInApp.tapAction("file:///sdcard/page.html"))
        assertEquals(OpenInApp.TapAction.NONE, OpenInApp.tapAction("data:text/html,<b>hi</b>"))
        assertEquals(OpenInApp.TapAction.NONE, OpenInApp.tapAction("about:blank"))
    }

    @Test
    fun telNumberStripsParamsAndQuery() {
        assertEquals("+15551234567", OpenInApp.telNumber("tel:+15551234567"))
        assertEquals("911", OpenInApp.telNumber("TEL:911"))
        assertEquals("+15551234567", OpenInApp.telNumber("tel:+15551234567;phone-context=example.com"))
        assertEquals("911", OpenInApp.telNumber("tel:911?body=help"))
        assertEquals("", OpenInApp.telNumber("tel:"))
    }

    @Test
    fun fallbackUrlDecodesOnlyHttpFallbacks() {
        assertEquals(
            "https://example.com/scan",
            OpenInApp.fallbackUrl(
                "intent://scan/#Intent;scheme=zxing;" +
                    "S.browser_fallback_url=https%3A%2F%2Fexample.com%2Fscan;end",
            ),
        )
        assertEquals(
            "http://example.com/plain",
            OpenInApp.fallbackUrl(
                "intent://x/#Intent;S.browser_fallback_url=http%3A%2F%2Fexample.com%2Fplain;end",
            ),
        )
        // A literal plus in the query stays a plus, not a space.
        assertEquals(
            "https://example.com/search?q=a+b",
            OpenInApp.fallbackUrl(
                "intent://x/#Intent;S.browser_fallback_url=https%3A%2F%2Fexample.com%2Fsearch%3Fq%3Da+b;end",
            ),
        )
        assertNull(OpenInApp.fallbackUrl("https://example.com/no-intent-marker"))
        assertNull(OpenInApp.fallbackUrl("intent://x/#Intent;scheme=zxing;end"))
        assertNull(OpenInApp.fallbackUrl("intent://x/#Intent;S.browser_fallback_url=;end"))
        assertNull(
            OpenInApp.fallbackUrl(
                "intent://x/#Intent;S.browser_fallback_url=ftp%3A%2F%2Fexample.com%2Ffile;end",
            ),
        )
        assertNull(
            OpenInApp.fallbackUrl(
                "intent://x/#Intent;S.browser_fallback_url=https%3A%2F%2Fexample.com%2F%ZZ;end",
            ),
        )
    }

    @Test
    fun authorityMatchesExactWildcardAndDotGuard() {
        assertTrue(OpenInApp.authorityMatches("youtube.com", "youtube.com"))
        assertTrue(OpenInApp.authorityMatches("*.youtube.com", "www.youtube.com"))
        assertTrue(OpenInApp.authorityMatches("*.youtube.com", "youtube.com"))
        assertTrue(OpenInApp.authorityMatches("*.youtube.com", "m.www.youtube.com"))
        assertFalse(OpenInApp.authorityMatches("*.youtube.com", "notyoutube.com"))
        assertFalse(OpenInApp.authorityMatches("*.youtube.com", "evil-youtube.com"))
        assertFalse(OpenInApp.authorityMatches("youtube.com", "www.youtube.com"))
        assertFalse(OpenInApp.authorityMatches(null, "youtube.com"))
        assertFalse(OpenInApp.authorityMatches("", "youtube.com"))
        assertFalse(OpenInApp.authorityMatches("youtube.com", ""))
        assertFalse(OpenInApp.authorityMatches("*.", "youtube.com"))
    }

    @Test
    fun viewOfferableNeedsViewSchemeOpenGateAndPublicTab() {
        assertTrue(OpenInApp.viewOfferable("https://example.com", false, true))
        assertTrue(OpenInApp.viewOfferable("http://example.com", false, true))
        assertTrue(OpenInApp.viewOfferable("market://details?id=com.example", false, true))
        assertFalse(OpenInApp.viewOfferable("https://example.com", true, true))
        assertFalse(OpenInApp.viewOfferable("https://example.com", false, false))
        assertFalse(OpenInApp.viewOfferable("market://details?id=com.example", true, true))
        assertFalse(OpenInApp.viewOfferable("tel:+15551234567", false, true))
        assertFalse(OpenInApp.viewOfferable("mailto:someone@example.com", false, true))
        assertFalse(
            OpenInApp.viewOfferable(
                "intent://x/#Intent;S.browser_fallback_url=https%3A%2F%2Fexample.com;end",
                false,
                true,
            ),
        )
        assertFalse(OpenInApp.viewOfferable("", false, true))
    }
}
