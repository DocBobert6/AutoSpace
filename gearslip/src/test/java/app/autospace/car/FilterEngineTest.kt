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

    // ---- r210: popunder guard honours list exceptions (finding 11) ----

    @Test
    fun documentExceptedCoversBareScopedAndNeverBlockExceptions() {
        install(
            "||pop.example.com^\$popup\n" +
            "@@||safe.example.com^\n" +
            "@@||popads.net^\n" +
            "||scoped.example.com^\$document,domain=news.example.com\n" +
            "@@||scoped.example.com^\$document,domain=news.example.com\n",
        )
        // A bare @@ exception lifts the guard, for the host and its subdomains.
        assertTrue(FilterEngine.isDocumentExcepted("safe.example.com", "/", ""))
        assertTrue(FilterEngine.isDocumentExcepted("www.safe.example.com", "/", ""))
        // The same holds for a built-in popunder host carrying a list exception.
        assertTrue(FilterEngine.isDocumentExcepted("popads.net", "/", ""))
        // A scoped $document exception lifts it only on its matching page.
        assertTrue(FilterEngine.isDocumentExcepted("scoped.example.com", "/", "news.example.com"))
        assertFalse(FilterEngine.isDocumentExcepted("scoped.example.com", "/", "other.example.com"))
        // A $document host with no exception stays enforced, as does an empty host.
        assertFalse(FilterEngine.isDocumentExcepted("pop.example.com", "/", ""))
        assertFalse(FilterEngine.isDocumentExcepted("", "/", ""))
        // A neverBlock host is excepted too.
        assertTrue(FilterEngine.isDocumentExcepted("youtube.com", "/", ""))
    }

    @Test
    fun documentExceptedDoesNotLiftImportantDocumentRules() {
        install(
            "||hard.example.com^\$document,important\n" +
            "@@||hard.example.com^\n",
        )
        // The helper reports the exception, but isDocumentBlocked still
        // returns true — $important beats exceptions, so the guard's
        // first arm fires and the hop is still captured.
        assertTrue(FilterEngine.isDocumentExcepted("hard.example.com", "/", ""))
        assertTrue(FilterEngine.isDocumentBlocked("hard.example.com", "/", ""))
    }

    // ---- r192: Global Privacy Control ----

    @Test
    fun gpcHeadersOnCarriesSecGpcOne() {
        assertEquals(mapOf("Sec-GPC" to "1"), FilterEngine.gpcHeaders(true))
    }

    @Test
    fun gpcHeadersOffSendsNothing() {
        assertTrue(FilterEngine.gpcHeaders(false).isEmpty())
    }

    @Test
    fun gpcDomScriptDefinesGlobalPrivacyControlTrue() {
        val script = FilterEngine.GPC_SCRIPT
        assertTrue(script.contains("globalPrivacyControl"))
        assertTrue(script.contains("Object.defineProperty"))
        assertTrue(script.contains("return true"))
    }

    @Test
    fun gpcDomScriptIsStandaloneWithoutCosmetics() {
        assertFalse(FilterEngine.GPC_SCRIPT.contains("createElement('style')"))
    }

    // ---- r197: separate ads/trackers toggles + blocked counts ----

    private fun category(
        host: String,
        blockAds: Boolean = true,
        blockTrackers: Boolean = true,
        path: String = "/",
        url: String = "https://$host$path",
        pageHost: String = "",
        mainFrame: Boolean = false,
    ): Int? = FilterEngine.blockCategory(
        scheme = "https",
        host = host,
        path = path,
        urlString = url,
        isForMainFrame = mainFrame,
        blockAds = blockAds,
        blockTrackers = blockTrackers,
        pageHost = pageHost,
    )

    private fun parseCategorized(text: String, cat: Int): FilterData {
        val data = FilterData()
        FilterEngine.parseInto(false, text, data, cat)
        return data
    }

    private fun installCategorized(
        text: String,
        cat: Int,
        builtin: Set<String> = emptySet(),
        userNet: List<UserBlockRules.NetRule> = emptyList(),
    ) {
        FilterEngine.installForTest(parseCategorized(text, cat), builtin, userNet)
    }

    @Test
    fun categoryForListNamesTrackerLists() {
        assertEquals(FilterEngine.CAT_TRACKER, FilterEngine.categoryForList(1))
        assertEquals(FilterEngine.CAT_TRACKER, FilterEngine.categoryForList(3))
        assertEquals(FilterEngine.CAT_ADS, FilterEngine.categoryForList(0))
        assertEquals(FilterEngine.CAT_ADS, FilterEngine.categoryForList(2))
        assertEquals(FilterEngine.CAT_ADS, FilterEngine.categoryForList(4))
        assertEquals(FilterEngine.CAT_ADS, FilterEngine.categoryForList(5))
        assertEquals(FilterEngine.CAT_ADS, FilterEngine.categoryForList(6))
    }

    @Test
    fun trackerListHostsLandInTrackerSet() {
        val data = parseCategorized("||trk.example.com^\n", FilterEngine.CAT_TRACKER)
        assertTrue(data.trackerHosts.contains("trk.example.com"))
        assertTrue(data.hosts.contains("trk.example.com"))
        val ads = parseCategorized("||ads.example.com^\n", FilterEngine.CAT_ADS)
        assertTrue(ads.trackerHosts.isEmpty())
        assertTrue(ads.hosts.contains("ads.example.com"))
    }

    @Test
    fun scopedRuleCarriesItsListCategory() {
        val data = parseCategorized(
            "||trk.example.com^\$domain=news.example.org\n", FilterEngine.CAT_TRACKER,
        )
        val rules = data.scoped["trk.example.com"]
        assertTrue(rules != null && rules.size == 1)
        assertEquals(FilterEngine.CAT_TRACKER, rules!![0].category)
        val ads = parseCategorized(
            "||ads.example.com^\$domain=news.example.org\n", FilterEngine.CAT_ADS,
        )
        assertEquals(FilterEngine.CAT_ADS, ads.scoped["ads.example.com"]!![0].category)
    }

    @Test
    fun trackerHostCountsAsTrackerOnlyWhenTrackersOn() {
        installCategorized("||trk.example.com^\n", FilterEngine.CAT_TRACKER)
        assertEquals(FilterEngine.CAT_TRACKER, category("trk.example.com"))
        assertEquals(FilterEngine.CAT_TRACKER, category("trk.example.com", blockAds = false))
        assertEquals(null, category("trk.example.com", blockTrackers = false))
        assertEquals(null, category("trk.example.com", blockAds = false, blockTrackers = false))
    }

    @Test
    fun adHostCountsAsAdsOnlyWhenAdsOn() {
        installCategorized("||ads.example.com^\n", FilterEngine.CAT_ADS)
        assertEquals(FilterEngine.CAT_ADS, category("ads.example.com"))
        assertEquals(FilterEngine.CAT_ADS, category("ads.example.com", blockTrackers = false))
        // Deliberate deviation from Mariner's literal code: an
        // ad-list host does NOT block with the ads toggle off,
        // even while trackers is on — each toggle gates only its
        // own category.
        assertEquals(null, category("ads.example.com", blockAds = false))
        assertEquals(null, category("ads.example.com", blockAds = false, blockTrackers = false))
    }

    @Test
    fun builtinHostCountsAsTracker() {
        installCategorized("", FilterEngine.CAT_ADS, builtin = setOf("trk-builtin.example.com"))
        assertEquals(FilterEngine.CAT_TRACKER, category("trk-builtin.example.com"))
        assertEquals(null, category("trk-builtin.example.com", blockTrackers = false))
    }

    @Test
    fun userRuleCountsAsOtherWheneverEitherToggleOn() {
        installCategorized(
            "", FilterEngine.CAT_ADS,
            userNet = listOf(UserBlockRules.NetRule.Host("mine.example.com")),
        )
        assertEquals(FilterEngine.CAT_OTHER, category("mine.example.com"))
        assertEquals(FilterEngine.CAT_OTHER, category("mine.example.com", blockAds = false))
        assertEquals(FilterEngine.CAT_OTHER, category("mine.example.com", blockTrackers = false))
        assertEquals(null, category("mine.example.com", blockAds = false, blockTrackers = false))
    }

    @Test
    fun pathKeywordCountsAsAdsOnlyWhenAdsOn() {
        installCategorized("", FilterEngine.CAT_ADS)
        assertEquals(FilterEngine.CAT_ADS, category("cdn.example.com", path = "/ads/banner.js"))
        assertEquals(null, category("cdn.example.com", path = "/ads/banner.js", blockAds = false))
    }

    @Test
    fun mainFrameAndBothOffNeverBlock() {
        installCategorized("||ads.example.com^\n", FilterEngine.CAT_ADS)
        assertEquals(null, category("ads.example.com", mainFrame = true))
        assertEquals(null, category("ads.example.com", blockAds = false, blockTrackers = false))
    }

    @Test
    fun scopedCategoryGatedByItsToggle() {
        installCategorized(
            "||trk.example.com^\$domain=news.example.org\n", FilterEngine.CAT_TRACKER,
        )
        assertEquals(
            FilterEngine.CAT_TRACKER,
            category("trk.example.com", pageHost = "news.example.org"),
        )
        assertEquals(
            null,
            category("trk.example.com", pageHost = "news.example.org", blockTrackers = false),
        )
    }

    @Test
    fun perTabCountsAccumulateAndResetOnNavigation() {
        FilterEngine.noteBlockedFor(7, FilterEngine.CAT_ADS, "a.example.com", false)
        FilterEngine.noteBlockedFor(7, FilterEngine.CAT_ADS, "b.example.com", false)
        FilterEngine.noteBlockedFor(7, FilterEngine.CAT_TRACKER, "t.example.com", false)
        FilterEngine.noteBlockedFor(9, FilterEngine.CAT_ADS, "a.example.com", false)
        assertEquals(3L, FilterEngine.blockedCountFor(7))
        assertEquals(listOf(2L, 1L, 0L), FilterEngine.blockedCategoriesFor(7))
        assertEquals(1L, FilterEngine.blockedCountFor(9))
        assertEquals(listOf(3L, 1L, 0L), FilterEngine.blockedByCategory())
        assertEquals(4L, FilterEngine.blockedCount())
        FilterEngine.resetTabCount(7)
        assertEquals(0L, FilterEngine.blockedCountFor(7))
        assertEquals(listOf(0L, 0L, 0L), FilterEngine.blockedCategoriesFor(7))
        assertEquals(1L, FilterEngine.blockedCountFor(9))
        // Resetting a tab does not touch the session totals.
        assertEquals(4L, FilterEngine.blockedCount())
    }

    @Test
    fun recentHostsAreMostRecentFirstDistinctAndCapped() {
        for (i in 1..10) {
            FilterEngine.noteBlockedFor(3, FilterEngine.CAT_ADS, "h$i.example.com", false)
        }
        val recent = FilterEngine.recentBlockedHosts(3)
        assertEquals(FilterEngine.RECENT_HOSTS_MAX, recent.size)
        assertEquals("h10.example.com", recent[0].first)
        assertEquals(FilterEngine.CAT_ADS, recent[0].second)
        // Re-blocking a host moves it to the front, no duplicate.
        FilterEngine.noteBlockedFor(3, FilterEngine.CAT_TRACKER, "h5.example.com", false)
        val moved = FilterEngine.recentBlockedHosts(3)
        assertEquals("h5.example.com", moved[0].first)
        assertEquals(FilterEngine.CAT_TRACKER, moved[0].second)
        assertEquals(1, moved.count { it.first == "h5.example.com" })
        FilterEngine.resetTabCount(3)
        assertTrue(FilterEngine.recentBlockedHosts(3).isEmpty())
    }

    @Test
    fun privateBlocksCountInSessionButNeverReachHistory() {
        FilterEngine.noteBlockedFor(5, FilterEngine.CAT_ADS, "p.example.com", true)
        assertEquals(1L, FilterEngine.blockedCountFor(5))
        assertEquals(1L, FilterEngine.blockedCount())
        // No counts anywhere in the history: a private-only
        // session leaves no history (today's row is all zeros).
        assertTrue(FilterEngine.blockedHistory.snapshot().values.all { it.all { c -> c == 0L } })
        FilterEngine.noteBlockedFor(5, FilterEngine.CAT_TRACKER, "q.example.com", false)
        val snap = FilterEngine.blockedHistory.snapshot()
        assertEquals(1, snap.size)
        assertEquals(listOf(0L, 1L, 0L), snap.values.first())
    }

    // ---- r197: per-day history ----

    @Test
    fun historyFlushesTodayAndLoadsBack() {
        val h = BlockedHistory()
        h.note(FilterEngine.CAT_ADS)
        h.note(FilterEngine.CAT_ADS)
        h.note(FilterEngine.CAT_TRACKER)
        val snap = h.snapshot()
        assertEquals(1, snap.size)
        assertEquals(listOf(2L, 1L, 0L), snap.values.first())
        val raw = h.flush()
        assertTrue(raw != null && raw!!.contains("=2,1,0"))
        // Nothing changed since the flush.
        assertEquals(null, h.flush())
        val h2 = BlockedHistory()
        h2.load(raw!!)
        assertEquals(snap, h2.snapshot())
    }

    @Test
    fun historyRollsAtMidnightWithoutDroppingADay() {
        val h = BlockedHistory()
        // 26 hours apart, so the two instants are on different
        // local days in any timezone (a local day is at most 25h).
        val now = System.currentTimeMillis()
        val yesterday = now - 26L * 3600 * 1000
        h.note(FilterEngine.CAT_ADS, yesterday)
        h.note(FilterEngine.CAT_TRACKER, yesterday)
        val snap = h.snapshot(now)
        assertEquals(2, snap.size)
        // Yesterday's counts survive the roll; today starts empty.
        assertEquals(listOf(1L, 1L, 0L), snap[todayKey(yesterday)])
        assertEquals(listOf(0L, 0L, 0L), snap[todayKey(now)])
        h.note(FilterEngine.CAT_OTHER, now)
        assertEquals(listOf(0L, 0L, 1L), h.snapshot(now)[todayKey(now)])
    }

    @Test
    fun historyPrunesToThirtyDaysAndDropsBadLines() {
        val h = BlockedHistory()
        // 35 distinct valid days: Dec 2025 (31) + Jan 2026 (4).
        val valid = (1..31).map { "2025-12-${"%02d".format(it)}=1,0,0" } +
            (1..4).map { "2026-01-${"%02d".format(it)}=2,0,0" }
        h.load((valid + listOf("not-a-day=1,2,3", "2026-01-05=1,2", "2026-01-06=-1,0,0", "junk")).joinToString("\n"))
        val snap = h.snapshot()
        // 30 persisted days plus today's (empty) row, newest first.
        // The snapshot always carries today, even with zero counts.
        assertEquals(31, snap.size)
        assertEquals(todayKey(), snap.keys.first())
        assertEquals("2026-01-04", snap.keys.drop(1).first())
        assertEquals("2025-12-06", snap.keys.last())
        assertEquals(listOf(2L, 0L, 0L), snap["2026-01-04"])
        // Bad lines never land: no malformed day, no short row, no negative count.
        assertTrue(snap.keys.none { it == "2026-01-05" || it == "2026-01-06" })
    }

    @Test
    fun historySerializeParseRoundTrip() {
        val days = mapOf(
            "2026-11-08" to longArrayOf(3L, 1L, 0L),
            "2026-11-07" to longArrayOf(0L, 0L, 5L),
        )
        val raw = serializeBlockedHistory(days)
        assertEquals("2026-11-08=3,1,0\n2026-11-07=0,0,5", raw)
        val back = parseBlockedHistory(raw)
        assertEquals(2, back.size)
        assertEquals(listOf(3L, 1L, 0L), back["2026-11-08"]!!.toList())
        assertEquals(listOf(0L, 0L, 5L), back["2026-11-07"]!!.toList())
    }

    // ---- r198 (adblock A3): $document enforcement ----

    private fun docBlocked(
        host: String,
        path: String = "/",
        pageHost: String = "",
    ): Boolean = FilterEngine.isDocumentBlocked(host, path, pageHost)

    @Test
    fun documentFlagCapturedOnScopedRules() {
        val scoped = parse("||ads.example.com^\$document,domain=news.example.org\n")
        assertTrue(scoped.scoped["ads.example.com"]!![0].document)
        val pathRule = parse("||example.com/ads/x^\$document\n")
        assertTrue(pathRule.scoped["example.com"]!![0].document)
        assertEquals("/ads/x", pathRule.scoped["example.com"]!![0].pathPrefix)
        val plain = parse("||ads.example.com^\$domain=news.example.org\n")
        assertFalse(plain.scoped["ads.example.com"]!![0].document)
    }

    @Test
    fun documentBlockedPureHostRule() {
        install("||pop.example.com^\$document\n")
        assertTrue(docBlocked("pop.example.com"))
        assertTrue(docBlocked("www.pop.example.com"))
        assertFalse(docBlocked("safe.example.com"))
    }

    @Test
    fun documentBlockedScopedRuleNeedsMatchingDomain() {
        install("||ads.example.com^\$document,domain=news.example.org\n")
        assertTrue(docBlocked("ads.example.com", pageHost = "news.example.org"))
        assertFalse(docBlocked("ads.example.com", pageHost = "other.example.org"))
        assertFalse(docBlocked("ads.example.com"))
    }

    @Test
    fun documentBlockedPathScopedRule() {
        install("||example.com/ads^\$document\n")
        assertTrue(docBlocked("example.com", path = "/ads/banner"))
        assertFalse(docBlocked("example.com", path = "/other"))
    }

    @Test
    fun documentBlockedExceptionVetoes() {
        install("||pop.example.com^\$document\n@@||pop.example.com^\$document\n")
        assertFalse(docBlocked("pop.example.com"))
    }

    @Test
    fun documentBlockedImportantBeatsException() {
        install("||pop.example.com^\$document,important\n@@||pop.example.com^\n")
        assertTrue(docBlocked("pop.example.com"))
    }

    @Test
    fun documentBlockedNeverBlockHostsExempt() {
        install("||youtube.com^\$document\n")
        assertFalse(docBlocked("youtube.com"))
    }

    @Test
    fun documentBlockedPlainRuleDoesNotBlock() {
        install("||ads.example.com^\n")
        assertFalse(docBlocked("ads.example.com"))
    }

    // ---- r198 (adblock A3): cosmetic order ----

    @Test
    fun orderedSelectorsDomainUserGeneric() {
        install(
            "##.gen-x\n" +
                "news.example.org##.dom-x\n",
            userCosmetic = listOf(
                UserBlockRules.CosmeticRule("news.example.org", ".usr-x"),
            ),
        )
        val css = FilterEngine.cosmeticCss("news.example.org")
        val dom = css.indexOf(".dom-x")
        val usr = css.indexOf(".usr-x")
        val gen = css.indexOf(".gen-x")
        assertTrue(dom >= 0 && usr >= 0 && gen >= 0)
        assertTrue(dom < usr && usr < gen)
    }

    @Test
    fun generichideSuppressesGenericOnSubdomainsToo() {
        install(
            "##.gen-x\n" +
                "news.example.org##.dom-x\n" +
                "@@||news.example.org^\$generichide\n",
        )
        val page = FilterEngine.cosmeticCss("news.example.org")
        assertFalse(page.contains(".gen-x"))
        assertTrue(page.contains(".dom-x"))
        val sub = FilterEngine.cosmeticCss("deep.news.example.org")
        assertFalse(sub.contains(".gen-x"))
        assertTrue(sub.contains(".dom-x"))
    }

    @Test
    fun documentStartScriptWalksParentDomains() {
        val script = FilterEngine.buildDocumentStartScript(
            ".ad{display:none!important}",
            setOf("example.org"),
        )
        assertTrue(script.contains("while(h)"))
        assertTrue(script.contains("substring(d+1)"))
        assertFalse(script.contains("indexOf(location.hostname)"))
    }

    // ---- r198 (adblock A3): history empty state ----

    @Test
    fun blockedHistoryEmptyStateGatesOnAllZeroCounts() {
        assertTrue(blockedHistoryIsEmpty(emptyMap()))
        assertTrue(blockedHistoryIsEmpty(mapOf("2026-11-08" to listOf(0L, 0L, 0L))))
        assertFalse(blockedHistoryIsEmpty(mapOf("2026-11-08" to listOf(1L, 0L, 0L))))
        assertFalse(
            blockedHistoryIsEmpty(
                mapOf(
                    "2026-11-08" to listOf(0L, 0L, 0L),
                    "2026-11-07" to listOf(0L, 2L, 0L),
                ),
            ),
        )
    }
}
