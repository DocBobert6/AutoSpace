package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic coverage for the r85 user blocking rules. */
class UserBlockRulesTest {

    @Test
    fun netRulesRoundTrip() {
        val rules = listOf(
            UserBlockRules.NetRule.Host("ads.example.com"),
            UserBlockRules.NetRule.Url("https://ads.example.com/banner"),
            UserBlockRules.NetRule.Allow("news.example.org"),
        )
        val parsed = UserBlockRules.parseNet(UserBlockRules.serializeNet(rules))
        assertEquals(rules, parsed)
    }

    @Test
    fun malformedNetLinesSkipped() {
        val parsed = UserBlockRules.parseNet(
            "net\tHOST\tads.example.com\n" +
                "garbage line\n" +
                "net\tHOST\t\n" +
                "net\tBOGUS\tx.example.com\n" +
                "cos\tnews.example.org\t.ad\n",
        )
        assertEquals(
            listOf(UserBlockRules.NetRule.Host("ads.example.com")),
            parsed,
        )
    }

    @Test
    fun hostRuleMatchesSubdomains() {
        val rule = UserBlockRules.NetRule.Host("ads.example.com")
        assertTrue(UserBlockRules.netMatches(rule, "https", "ads.example.com", "/"))
        assertTrue(UserBlockRules.netMatches(rule, "https", "cdn.ads.example.com", "/x"))
        assertFalse(UserBlockRules.netMatches(rule, "https", "example.com", "/"))
    }

    @Test
    fun urlRuleIgnoresQueryAndFragment() {
        // Query and fragment are stripped at parse time, so an ad
        // URL with a cache-buster still matches on reload.
        val parsed = UserBlockRules.parseNet(
            "net\tURL\thttps://ads.example.com/banner?cb=1#frag\n",
        )
        assertEquals(
            listOf(UserBlockRules.NetRule.Url("https://ads.example.com/banner")),
            parsed,
        )
        val rule = parsed[0]
        assertTrue(UserBlockRules.netMatches(rule, "https", "ads.example.com", "/banner"))
        assertFalse(UserBlockRules.netMatches(rule, "https", "ads.example.com", "/other"))
    }

    @Test
    fun allowRuleMatchesHostAndSubdomainsOnly() {
        val rules = listOf(UserBlockRules.NetRule.Allow("news.example.org"))
        assertTrue(UserBlockRules.allowMatches(rules, "news.example.org"))
        assertTrue(UserBlockRules.allowMatches(rules, "www.news.example.org"))
        assertFalse(UserBlockRules.allowMatches(rules, "other.example.org"))
        // Allow rules are never block matches themselves.
        assertFalse(
            UserBlockRules.netMatches(rules[0], "https", "news.example.org", "/"),
        )
    }

    @Test
    fun urlKeyDefaultsEmptyPathToSlash() {
        assertEquals(
            "https://example.com/",
            UserBlockRules.urlKey("HTTPS", "Example.COM", ""),
        )
    }

    @Test
    fun cosmeticRulesRoundTripAndHostFilter() {
        val rules = listOf(
            UserBlockRules.CosmeticRule("news.example.org", ".sponsored"),
            UserBlockRules.CosmeticRule("other.example.org", ".ad-box"),
        )
        val parsed = UserBlockRules.parseCosmetic(
            UserBlockRules.serializeCosmetic(rules),
        )
        assertEquals(rules, parsed)
        assertEquals(
            listOf(".sponsored"),
            UserBlockRules.selectorsForHost(parsed, "news.example.org"),
        )
        assertTrue(
            UserBlockRules.selectorsForHost(parsed, "third.example.org").isEmpty(),
        )
    }

    @Test
    fun unsafeSelectorsRejected() {
        assertFalse(UserBlockRules.isSafeSelector(".ad{color:red}"))
        assertFalse(UserBlockRules.isSafeSelector(".ad; color:red"))
        assertFalse(UserBlockRules.isSafeSelector("div > script"))
        assertFalse(UserBlockRules.isSafeSelector(".ad\n.x"))
        assertTrue(UserBlockRules.isSafeSelector("div.ad-box:not(.keep)"))
        // A stored unsafe selector never parses back in.
        val parsed = UserBlockRules.parseCosmetic(
            "cos\tnews.example.org\t.ad{color:red}\n" +
                "cos\tnews.example.org\t.safe\n",
        )
        assertEquals(
            listOf(UserBlockRules.CosmeticRule("news.example.org", ".safe")),
            parsed,
        )
    }
}
