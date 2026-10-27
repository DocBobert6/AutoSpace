package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Pure-logic coverage for the r85 filter engine (no Android framework needed). */
class FilterEngineTest {

    @Before
    fun reset() {
        FilterEngine.clearForTest()
    }

    private fun parse(text: String): FilterData {
        val data = FilterData()
        FilterEngine.parseInto(false, text, data)
        return data
    }

    private fun install(
        text: String,
        builtin: Set<String> = emptySet(),
        userNet: List<UserBlockRules.NetRule> = emptyList(),
        userCosmetic: List<UserBlockRules.CosmeticRule> = emptyList(),
    ) {
        FilterEngine.installForTest(parse(text), builtin, userNet, userCosmetic)
    }

    private fun blocked(
        host: String,
        path: String = "/",
        url: String = "https://$host$path",
        pageHost: String = "",
        mainFrame: Boolean = false,
    ): Boolean = FilterEngine.isBlocked(
        scheme = "https",
        host = host,
        path = path,
        urlString = url,
        isForMainFrame = mainFrame,
        enabled = true,
        pageHost = pageHost,
    )

    // ---- Parsing ----

    @Test
    fun pureHostRuleGoesToFlatSet() {
        val data = parse("||ads.example.com^\n")
        assertTrue(data.hosts.contains("ads.example.com"))
        assertTrue(data.scoped.isEmpty())
    }

    @Test
    fun exceptionRuleGoesToExceptionSet() {
        val data = parse("@@||good.example.com^\n")
        assertTrue(data.exceptions.contains("good.example.com"))
        assertTrue(data.hosts.isEmpty())
    }

    @Test
    fun badfilterDisablesItsTwinEitherOrder() {
        val twinFirst = parse("||ads.example.com^\n||ads.example.com^\$badfilter\n")
        assertTrue(twinFirst.hosts.isEmpty())
        val badFirst = parse("||ads.example.com^\$badfilter\n||ads.example.com^\n")
        assertTrue(badFirst.hosts.isEmpty())
    }

    @Test
    fun domainScopedRuleDoesNotFlatten() {
        val data = parse("||ads.example.com^\$domain=news.example.org\n")
        assertTrue(data.hosts.isEmpty())
        val rules = data.scoped["ads.example.com"]
        assertTrue(rules != null && rules.size == 1)
        assertEquals(setOf("news.example.org"), rules!![0].domains)
    }

    @Test
    fun negativeDomainSetCaptured() {
        val data = parse("||ads.example.com^\$domain=~good.example.org\n")
        val rules = data.scoped["ads.example.com"]
        assertTrue(rules != null && rules.size == 1)
        assertEquals(setOf("good.example.org"), rules!![0].notDomains)
    }

    @Test
    fun thirdPartyAndFirstPartyFlagsCaptured() {
        val third = parse("||ads.example.com^\$third-party\n")
        assertEquals(true, third.scoped["ads.example.com"]!![0].thirdParty)
        val first = parse("||ads.example.com^\$~third-party\n")
        assertEquals(false, first.scoped["ads.example.com"]!![0].thirdParty)
    }

    @Test
    fun importantFlagCaptured() {
        val data = parse("||ads.example.com^\$important\n")
        assertTrue(data.scoped["ads.example.com"]!![0].important)
    }

    @Test
    fun pathRuleKeepsPrefix() {
        val data = parse("||example.com/ads/banner^\n")
        assertTrue(data.hosts.isEmpty())
        val rules = data.scoped["example.com"]
        assertTrue(rules != null && rules.size == 1)
        assertEquals("/ads/banner", rules!![0].pathPrefix)
    }

    @Test
    fun unsupportedOptionSkipsRule() {
        val data = parse("||ads.example.com^\$rewrite=foo\n")
        assertTrue(data.hosts.isEmpty())
        assertTrue(data.scoped.isEmpty())
    }

    @Test
    fun typedRuleAppliesAndDocumentTypeGoesToDocHosts() {
        // WebView interception cannot split requests by resource
        // type, so the host part of a typed rule is honoured for
        // every type — a $document-only rule lands in the flat
        // host set AND in the popunder set.
        val data = parse("||pop.example.com^\$document,popup\n")
        assertTrue(data.hosts.contains("pop.example.com"))
        assertTrue(data.docHosts.contains("pop.example.com"))
        // A typed pure-host rule is honoured, not dropped: the
        // type option only marks it, the host part still applies.
        val script = parse("||ads.example.com^\$script\n")
        assertTrue(script.hosts.contains("ads.example.com"))
    }

    @Test
    fun cosmeticRulesCollectedGenericAndPerDomain() {
        val data = parse(
            "##.ad-banner\n" +
                "news.example.org##.sponsored-box\n" +
                "news.example.org#@#.ad-banner\n",
        )
        assertTrue(data.generic.contains(".ad-banner"))
        assertTrue(data.perDomain["news.example.org"]!!.contains(".sponsored-box"))
        assertTrue(data.excDomain["news.example.org"]!!.contains(".ad-banner"))
    }

    @Test
    fun complexPlainCssSelectorsKept() {
        val data = parse("##div:not(.keep)\n##a[href^=\"ads\"]\n")
        assertTrue(data.generic.contains("div:not(.keep)"))
        assertTrue(data.generic.contains("a[href^=\"ads\"]"))
    }

    @Test
    fun proceduralPseudosDropped() {
        val data = parse(
            "##div:has-text(Ad)\n" +
                "##div:contains(Sponsored)\n" +
                "##.plain-ad\n",
        )
        assertEquals(setOf(".plain-ad"), data.generic)
    }

    @Test
    fun hostsFormatParsed() {
        val data = FilterData()
        FilterEngine.parseInto(
            true,
            "0.0.0.0 ads.example.com\n127.0.0.1 tracker.example.net\n# comment\n",
            data,
        )
        assertTrue(data.hosts.contains("ads.example.com"))
        assertTrue(data.hosts.contains("tracker.example.net"))
    }

    @Test
    fun documentHostRuleKept() {
        val data = parse("||pop.example.com^\$popup\n")
        assertTrue(data.docHosts.contains("pop.example.com"))
    }

    // ---- Matching ----

    @Test
    fun flatHostMatchesSubdomainsOnly() {
        install("||ads.example.com^\n")
        assertTrue(blocked("ads.example.com"))
        assertTrue(blocked("cdn.ads.example.com"))
        assertFalse(blocked("example.com"))
        assertFalse(blocked("notads.example.com"))
    }

    @Test
    fun exceptionBeatsBlock() {
        install("||ads.example.com^\n@@||ads.example.com^\n")
        assertFalse(blocked("ads.example.com"))
    }

    @Test
    fun importantBeatsException() {
        install("||ads.example.com^\$important\n@@||ads.example.com^\n")
        assertTrue(blocked("ads.example.com"))
    }

    @Test
    fun scopedDomainRuleNeedsMatchingPage() {
        install("||ads.example.com^\$domain=news.example.org\n")
        assertTrue(blocked("ads.example.com", pageHost = "news.example.org"))
        assertTrue(blocked("ads.example.com", pageHost = "www.news.example.org"))
        assertFalse(blocked("ads.example.com", pageHost = "other.example.org"))
        assertFalse(blocked("ads.example.com", pageHost = ""))
    }

    @Test
    fun scopedPathRuleNeedsMatchingPath() {
        install("||example.com/ads/banner^\n")
        assertTrue(blocked("example.com", path = "/ads/banner.png"))
        assertFalse(blocked("example.com", path = "/content/article"))
    }

    @Test
    fun thirdPartyRuleNeedsCrossSiteRequest() {
        install("||ads.example.com^\$third-party\n")
        assertTrue(blocked("ads.example.com", pageHost = "news.example.org"))
        assertFalse(blocked("ads.example.com", pageHost = "ads.example.com"))
    }

    @Test
    fun sameSiteTreatsCoUkSubdomainsAsFirstParty() {
        // login.example.co.uk and www.example.co.uk share the
        // registrable domain example.co.uk — first-party, not third.
        assertTrue(FilterEngine.sameSite("login.example.co.uk", "www.example.co.uk"))
        assertFalse(FilterEngine.sameSite("login.example.co.uk", "other.co.uk"))
        assertEquals("example.co.uk", FilterEngine.registrableDomain("www.example.co.uk"))
    }

    @Test
    fun neverBlockHostsSurviveLists() {
        install("||googlevideo.com^\n")
        assertFalse(blocked("googlevideo.com"))
        assertFalse(blocked("r1.googlevideo.com"))
    }

    @Test
    fun pathKeywordsBlockWithoutAListRule() {
        install("")
        assertTrue(
            blocked(
                "cdn.example.com",
                path = "/ads/banner.js",
                url = "https://cdn.example.com/ads/banner.js",
            ),
        )
        assertFalse(blocked("cdn.example.com", path = "/content/page"))
    }

    @Test
    fun mainFrameNeverBlocked() {
        install("||ads.example.com^\n")
        assertFalse(blocked("ads.example.com", mainFrame = true))
    }

    @Test
    fun disabledToggleBlocksNothing() {
        install("||ads.example.com^\n")
        assertFalse(
            FilterEngine.isBlocked(
                scheme = "https",
                host = "ads.example.com",
                path = "/",
                urlString = "https://ads.example.com/",
                isForMainFrame = false,
                enabled = false,
            ),
        )
    }

    @Test
    fun builtinHostsAreFallbackWhenListsAbsent() {
        install("", builtin = setOf("ads.example.com"))
        assertTrue(blocked("ads.example.com"))
        assertTrue(blocked("sub.ads.example.com"))
        assertFalse(blocked("other.example.com"))
    }

    // ---- User-rule precedence ----

    @Test
    fun userBlockBeatsListException() {
        install(
            "@@||ads.example.com^\n",
            userNet = listOf(UserBlockRules.NetRule.Host("ads.example.com")),
        )
        assertTrue(blocked("ads.example.com"))
    }

    @Test
    fun userAllowBeatsUserBlockAndLists() {
        install(
            "||ads.example.com^\n",
            userNet = listOf(
                UserBlockRules.NetRule.Host("ads.example.com"),
                UserBlockRules.NetRule.Allow("ads.example.com"),
            ),
        )
        assertFalse(blocked("ads.example.com"))
    }

    @Test
    fun userUrlRuleMatchesAddressOnly() {
        install(
            "",
            userNet = listOf(
                UserBlockRules.NetRule.Url("https://ads.example.com/banner"),
            ),
        )
        assertTrue(
            blocked(
                "ads.example.com",
                path = "/banner",
                url = "https://ads.example.com/banner?cb=123",
            ),
        )
        assertFalse(blocked("ads.example.com", path = "/other"))
    }

    // ---- Cosmetic CSS ----

    @Test
    fun cosmeticCssCombinesGenericDomainAndUserMinusExceptions() {
        install(
            "##.ad-banner\n" +
                "news.example.org##.sponsored-box\n" +
                "news.example.org#@#.ad-banner\n",
            userCosmetic = listOf(
                UserBlockRules.CosmeticRule("news.example.org", ".my-pick"),
            ),
        )
        val css = FilterEngine.cosmeticCss("news.example.org")
        assertFalse(css.contains(".ad-banner"))
        assertTrue(css.contains(".sponsored-box"))
        assertTrue(css.contains(".my-pick"))
        // The generic sheet still applies on an unrelated host.
        assertTrue(FilterEngine.cosmeticCss("other.example.org").contains(".ad-banner"))
    }

    @Test
    fun cosmeticSelectorsChunkedAtTwentyFive() {
        val sels = (1..26).map { ".ad-$it" }
        val css = FilterEngine.chunkCosmeticSelectors(sels, budget = 200_000)
        // Two rules: 25 selectors, then 1 — one invalid selector
        // can only void its own rule.
        assertEquals(2, css.split("{display:none!important}").size - 1)
    }

    @Test
    fun documentStartScriptEmptyWithoutCssAndSkipsOffHosts() {
        assertEquals("", FilterEngine.buildDocumentStartScript("", emptySet()))
        val script = FilterEngine.buildDocumentStartScript(
            ".ad{display:none!important}",
            setOf("news.example.org"),
        )
        assertTrue(script.contains("news.example.org"))
        assertTrue(script.contains("location.hostname"))
    }

    @Test
    fun documentHostCheckRespectsNeverBlockAndExceptions() {
        install("||pop.example.com^\$popup\n@@||safe.example.com^\n")
        assertTrue(FilterEngine.isDocumentHost("pop.example.com"))
        assertTrue(FilterEngine.isDocumentHost("www.pop.example.com"))
        assertFalse(FilterEngine.isDocumentHost("safe.example.com"))
        assertFalse(FilterEngine.isDocumentHost("youtube.com"))
    }
}
