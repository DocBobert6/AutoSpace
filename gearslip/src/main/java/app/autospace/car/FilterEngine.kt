package app.autospace.car

/**
 * r85 — full adblocking, ported from Notscape Mariner's FilterEngine
 * (browser/WebTools.kt). Real filter lists (EasyList + EasyPrivacy +
 * hosts-format + AdGuard + uBlock) parsed into the same structures
 * Mariner compiles: flat host sets, path/$domain-scoped rules, and
 * cosmetic selectors injected as CSS per page.
 *
 * PURE: no Android imports anywhere in this file, so the unit tests
 * run it without a device (the BrowserExtras convention). Callers pass
 * plain strings, never android.net.Uri — Uri is a stub under local
 * JVM tests. The Android half (downloading, disk cache, observable
 * state) lives in FilterLists; this file only holds the parsed data
 * and the matching rules.
 *
 * Adaptations from Mariner, all deliberate:
 * - One toggle, not two: Mariner takes blockAds + blockTrackers;
 *   AutoSpace has a single "Block ads & trackers" switch, so
 *   [isBlocked] takes one [enabled] flag that gates every rule
 *   family (user rules, built-in hosts, list hosts, scoped rules,
 *   path keywords) exactly as the single toggle promises.
 * - User allow rules: Mariner's user rules are block-only. AutoSpace
 *   adds an allow-host rule (see UserBlockRules.NetRule.Allow) so a
 *   driver can unbreak a site the lists break; it is checked first
 *   and beats user blocks and lists alike.
 * - sameSite uses Mariner's fallback heuristic only (common
 *   multi-label suffixes + last-two-labels). Mariner prefers Guava's
 *   full public-suffix data; Guava is not a dependency here, and
 *   the fallback is what Mariner itself uses for hosts Guava
 *   rejects.
 * - No binary compiled cache in v1: lists parse from text on a
 *   background thread at browser init (see FilterLists). The parse
 *   cost is background-only and never blocks a page load.
 */

/** An ABP rule that only applies under conditions: a path prefix on
 *  the request host, and/or only on pages whose host is (or is not)
 *  in a $domain= list. The honest middle ground between ignoring
 *  scoped rules entirely and flattening them into global host
 *  blocks — flattening took out YouTube in Mariner (the
 *  googlevideo.com rule is restricted to two named sites). */
data class ScopedRule(
    val pathPrefix: String?,
    val domains: Set<String>?,
    val notDomains: Set<String>?,
    val exception: Boolean,
    /** null = any party; true = only third-party requests; false = only first-party. */
    val thirdParty: Boolean? = null,
    /** $important: beats exception (@@) rules. */
    val important: Boolean = false,
)

/** The parsed collections, bundled so load, refresh validation and
 *  apply pass one thing around. */
class FilterData {
    val hosts = HashSet<String>()
    val exceptions = HashSet<String>()
    val scoped = HashMap<String, MutableList<ScopedRule>>()
    val generic = LinkedHashSet<String>()
    val perDomain = HashMap<String, MutableList<String>>()
    val docHosts = HashSet<String>()
    val excGeneric = HashSet<String>()
    val excDomain = HashMap<String, MutableList<String>>()
    val hideOff = HashSet<String>()

    fun isEmpty() = hosts.isEmpty() && scoped.isEmpty() &&
        generic.isEmpty()

    fun total() = hosts.size +
        scoped.values.sumOf { it.size } + generic.size

    fun merge(other: FilterData) {
        hosts.addAll(other.hosts)
        exceptions.addAll(other.exceptions)
        other.scoped.forEach { (k, v) ->
            scoped.getOrPut(k) { mutableListOf() }.addAll(v)
        }
        generic.addAll(other.generic)
        other.perDomain.forEach { (k, v) ->
            perDomain.getOrPut(k) { mutableListOf() }.addAll(v)
        }
        docHosts.addAll(other.docHosts)
        excGeneric.addAll(other.excGeneric)
        other.excDomain.forEach { (k, v) ->
            excDomain.getOrPut(k) { mutableListOf() }.addAll(v)
        }
        hideOff.addAll(other.hideOff)
    }
}

object FilterEngine {

    /** Player/CDN hosts that must never be host-blocked. */
    private val neverBlockHosts = setOf(
        "googlevideo.com", "googleapis.com", "youtube.com", "ytimg.com",
        "ggpht.com", "jwplatform.com", "jwpcdn.com", "jwplayer.com",
        "vimeo.com", "vimeocdn.com", "akamaihd.net", "dailymotion.com",
        "twitch.tv",
    )

    private val pathKeywords = listOf(
        "/ads/", "/adservice", "/pagead/", "/gtag/js", "google-analytics",
        "/analytics.js", "/beacon", "/pixel.gif", "prebid", "adsbygoogle",
    )

    /** Resource-type rule options, ABP and AdGuard spellings. */
    private val typeOptions = setOf(
        "script", "image", "stylesheet", "css", "font", "media",
        "object", "object-subrequest", "xmlhttprequest", "xhr",
        "subdocument", "frame", "document", "popup", "websocket",
        "webrtc", "other", "ping", "beacon", "manifest", "empty", "mp4",
    )

    // ---- Live rule state (written by applyData, read on the
    // request thread). Volatile like Mariner's: interception runs
    // on several WebView threads at once. ----
    @Volatile var listHosts: Set<String> = emptySet()
    @Volatile var exceptionHosts: Set<String> = emptySet()
    @Volatile var scopedRules: Map<String, List<ScopedRule>> = emptyMap()
    @Volatile var genericSelectors: List<String> = emptyList()
    @Volatile var domainSelectors: Map<String, List<String>> = emptyMap()
    @Volatile var docHosts: Set<String> = emptySet()
    @Volatile var cosmeticExcGeneric: Set<String> = emptySet()
    @Volatile var cosmeticExcDomain: Map<String, List<String>> = emptyMap()
    @Volatile var genericHideOffHosts: Set<String> = emptySet()

    /** AutoSpace's bundled host set (BrowserFilter.builtinHosts),
     *  injected by FilterLists at init and kept as the offline
     *  fallback. Tests set it directly. */
    @Volatile var builtinHosts: Set<String> = emptySet()

    /** The user's own rules (see UserBlockRules), set by
     *  FilterLists.syncUserRules. Network rules beat the compiled
     *  lists; cosmetic rules join the sheet for their exact host. */
    @Volatile var userNetRules: List<UserBlockRules.NetRule> = emptyList()
    @Volatile var userCosmeticRules: List<UserBlockRules.CosmeticRule> =
        emptyList()
        set(value) {
            field = value
            cssCache = null
        }

    /** The document-start cosmetic script, rebuilt whenever filter
     *  data lands; "" until the first lists load. Registered on new
     *  WebViews so the generic sheet applies before the page
     *  paints. */
    @Volatile var documentStartScript: String = ""
        private set
    @Volatile private var cssCache: Pair<String, String>? = null

    /** Installs a parsed [FilterData] as the live rule set and
     *  rebuilds the document-start script. Called by FilterLists
     *  after a disk load or a refresh. */
    fun applyData(data: FilterData) {
        listHosts = data.hosts
        exceptionHosts = data.exceptions
        scopedRules = data.scoped
        genericSelectors = data.generic.toList()
        domainSelectors = data.perDomain
        docHosts = data.docHosts
        cosmeticExcGeneric = data.excGeneric
        cosmeticExcDomain = data.excDomain
        genericHideOffHosts = data.hideOff
        cssCache = null
        documentStartScript = buildDocumentStartScript(
            chunkCosmeticSelectors(
                genericSelectors.filter { it !in cosmeticExcGeneric },
                budget = 150_000,
            ),
            genericHideOffHosts,
        )
    }

    /** Test seam: installs [data] plus the given built-in set and
     *  user rules, exactly as applyData + syncUserRules would. */
    internal fun installForTest(
        data: FilterData,
        builtin: Set<String> = emptySet(),
        userNet: List<UserBlockRules.NetRule> = emptyList(),
        userCosmetic: List<UserBlockRules.CosmeticRule> = emptyList(),
    ) {
        builtinHosts = builtin
        userNetRules = userNet
        userCosmeticRules = userCosmetic
        applyData(data)
    }

    /** Test seam: clears every live collection back to empty. */
    internal fun clearForTest() {
        installForTest(FilterData())
        documentStartScript = ""
    }

    // ---- Parsing ----

    /** Parses one list file into [data]. [isHostsFormat] selects
     *  the hosts-file parser (StevenBlack list, index 2); every
     *  other list is ABP syntax. */
    internal fun parseInto(isHostsFormat: Boolean, text: String, data: FilterData) {
        if (isHostsFormat) parseHosts(text, data.hosts)
        else parse(
            text, data.hosts, data.exceptions, data.scoped,
            data.generic, data.perDomain, data.docHosts,
            data.excGeneric, data.excDomain, data.hideOff,
        )
    }

    /**
     * Compile ABP rules into three structures:
     *  - pure ||host^ rules → the flat host sets;
     *  - path-scoped and $domain=-scoped rules → [ScopedRule]s keyed by
     *    host, applied only when their conditions match (rules carrying
     *    other $ options — types, $rewrite — are still skipped);
     *  - cosmetic ## rules with simple selectors → generic and
     *    per-domain hide lists, injected as CSS per page.
     */
    internal fun parse(
        text: String,
        hosts: MutableSet<String>,
        exceptions: MutableSet<String>,
        scoped: MutableMap<String, MutableList<ScopedRule>>,
        generic: MutableSet<String>,
        perDomain: MutableMap<String, MutableList<String>>,
        docHosts: MutableSet<String>,
        genericExc: MutableSet<String>,
        perDomainExc: MutableMap<String, MutableList<String>>,
        hideOff: MutableSet<String>,
    ) {
        // $badfilter pass: a badfilter rule disables its twin. Collect
        // the twins' signatures first (the badfilter line may precede
        // the rule it disables), then skip both in the main pass.
        val badSignatures = HashSet<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.contains("badfilter")) {
                ruleSignature(line)?.let { badSignatures.add(it) }
            }
        }
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) continue
            if (!line.startsWith("|") && !line.startsWith("@@")) {
                // Cosmetic rules: ## hides, #@# unhides. (#$# / #?#
                // extended rules stay out - they are not plain CSS.)
                val excIdx = line.indexOf("#@#")
                val hideIdx = line.indexOf("##")
                val idx = when {
                    excIdx >= 0 -> excIdx
                    hideIdx >= 0 -> hideIdx
                    else -> -1
                }
                if (idx >= 0 && !line.contains("#$#") && !line.contains("#?#")) {
                    val isExc = excIdx >= 0
                    val selector = line.substring(idx + if (isExc) 3 else 2).trim()
                    if (isCssSelector(selector)) {
                        val domainPart = line.substring(0, idx).trim()
                        val positives = ArrayList<String>()
                        val negatives = ArrayList<String>()
                        if (domainPart.isNotEmpty()) {
                            for (dom in domainPart.split(',')) {
                                val d = dom.trim().lowercase()
                                if (d.startsWith("~")) negatives.add(d.substring(1))
                                else if (d.isNotEmpty() && d.contains('.')) positives.add(d)
                            }
                        }
                        fun excFor(d: String) {
                            if (d.contains('.')) {
                                val list = perDomainExc.getOrPut(d) { mutableListOf() }
                                if (selector !in list) list.add(selector)
                            }
                        }
                        if (isExc) {
                            when {
                                positives.isNotEmpty() -> for (d in positives) {
                                    val list = perDomainExc.getOrPut(d) { mutableListOf() }
                                    if (selector !in list) list.add(selector)
                                }
                                domainPart.isEmpty() -> genericExc.add(selector)
                            }
                        } else {
                            when {
                                positives.isNotEmpty() -> {
                                    for (d in positives) {
                                        val list = perDomain.getOrPut(d) { mutableListOf() }
                                        if (selector !in list) list.add(selector)
                                    }
                                    for (d in negatives) excFor(d)
                                }
                                negatives.isNotEmpty() -> {
                                    generic.add(selector)
                                    for (d in negatives) excFor(d)
                                }
                                else -> generic.add(selector)
                            }
                        }
                    }
                }
                continue
            }
            if (line.startsWith("@@") && !line.startsWith("@@||")) {
                // Page-level cosmetic exceptions with no host anchor
                // (@@$generichide,domain=a|b): the named domains lose
                // generic hiding. Nothing else in this shape is
                // honourable here.
                if (line.contains("generichide") || line.contains("ghide")) {
                    val dm = line.indexOf("domain=")
                    if (dm >= 0) {
                        val rest = line.substring(dm + 7)
                        val end = rest.indexOf('$')
                        val domPart = if (end >= 0) rest.substring(0, end) else rest
                        for (dd in domPart.split('|')) {
                            val d = dd.trim().lowercase()
                            if (d.isNotEmpty() && !d.startsWith("~") && d.contains('.')) {
                                hideOff.add(d)
                            }
                        }
                    }
                }
                continue
            }
            val exception = line.startsWith("@@")
            val body0 = when {
                line.startsWith("@@||") -> line.substring(4)
                line.startsWith("||") -> line.substring(2)
                else -> continue
            }
            val dollar = body0.indexOf('$')
            val bodyRaw = if (dollar >= 0) body0.substring(0, dollar) else body0
            // ||*.ads.example.com^ - a leading wildcard is exactly
            // what our parent-walking matcher already does (a listed
            // host covers its subdomains), so it is stripped here,
            // before the host scan (which treats '*' as a
            // terminator). Interior wildcards still skip the rule.
            val body = if (bodyRaw.startsWith("*.")) bodyRaw.substring(2) else bodyRaw
            val options = if (dollar >= 0) body0.substring(dollar + 1) else ""
            if (options.split(',').any { it.trim().lowercase() == "badfilter" }) continue
            if (badSignatures.isNotEmpty() &&
                ruleSignature(line) in badSignatures
            ) continue
            var domains: Set<String>? = null
            var notDomains: Set<String>? = null
            var thirdParty: Boolean? = null
            var important = false
            var hideModifier = false
            var docType = false
            var otherType = false
            var optionsOk = true
            if (options.isNotEmpty()) {
                for (opt in options.split(',')) {
                    val o = opt.trim().lowercase()
                    when {
                        // Party options, ABP and AdGuard spellings.
                        o == "third-party" || o == "3p" || o == "~1p" -> thirdParty = true
                        o == "~third-party" || o == "~3p" || o == "1p" -> thirdParty = false
                        o == "important" -> important = true
                        // Resource-type options ($script, $image,
                        // $document, $~image, ...): the rule DOES
                        // apply. WebView interception cannot split
                        // requests by resource type, so the host/path
                        // part is honoured for every type. Dropping
                        // these rules (the old gate) silently lost
                        // thousands of live list rules, including
                        // most $document popunder hosts.
                        o in typeOptions ||
                            (o.startsWith("~") && o.substring(1) in typeOptions) -> {
                            val base = o.removePrefix("~")
                            if (base == "document" || base == "popup") docType = true
                            else otherType = true
                        }
                        // Cosmetic-scope modifiers: recognised so the
                        // rule is not dropped whole; resolved after the
                        // loop (exceptions) or ignored (block rules,
                        // where the network part still applies).
                        o == "generichide" || o == "ghide" || o == "elemhide" -> hideModifier = true
                        o == "match-case" || o == "all" -> Unit
                        o.startsWith("domain=") -> {
                            val pos = HashSet<String>()
                            val neg = HashSet<String>()
                            for (dd in o.substring(7).split('|')) {
                                val d = dd.trim()
                                if (d.startsWith("~")) neg.add(d.substring(1))
                                else if (d.isNotEmpty()) pos.add(d)
                            }
                            domains = pos.ifEmpty { null }
                            notDomains = neg.ifEmpty { null }
                        }
                        else -> { optionsOk = false; break }
                    }
                }
            }
            if (!optionsOk) continue
            if (exception && hideModifier && !otherType &&
                thirdParty == null && !important
            ) {
                // A cosmetic-scope exception (@@||host^$generichide
                // and friends): it governs generic hiding on pages,
                // never network requests, so it never reaches the
                // network sets. The $domain= set wins; otherwise the
                // rule's own host is the page scope.
                val targets = domains
                if (targets != null) {
                    hideOff.addAll(targets)
                } else {
                    var he = body.length
                    for (i in body.indices) {
                        val c = body[i]
                        if (c == '/' || c == '^' || c == '|' || c == '*') { he = i; break }
                    }
                    val h = body.substring(0, he).trim().lowercase()
                    if (h.contains('.')) hideOff.add(h)
                }
                continue
            }
            var hostEnd = body.length
            for (i in body.indices) {
                val c = body[i]
                if (c == '/' || c == '^' || c == '|' || c == '*') { hostEnd = i; break }
            }
            val host = body.substring(0, hostEnd).trim().lowercase()
            if (!host.contains('.') ||
                !host.all { it.isLetterOrDigit() || it == '.' || it == '-' }
            ) continue
            val rest = body.substring(hostEnd)
            val isScoped = domains != null || notDomains != null || thirdParty != null || important
            if (rest.isEmpty() || rest == "^") {
                if (isScoped) {
                    scoped.getOrPut(host) { mutableListOf() }
                        .add(ScopedRule(null, domains, notDomains, exception, thirdParty, important))
                } else {
                    (if (exception) exceptions else hosts).add(host)
                    // Popunder provenance for the navigation guard:
                    // a pure host whose ONLY type options are
                    // $document/$popup.
                    if (!exception && docType && !otherType) docHosts.add(host)
                }
                continue
            }
            if (rest.startsWith("/")) {
                var path = rest
                val star = path.indexOf('*')
                if (star >= 0) path = path.substring(0, star)
                val caret = path.indexOf('^')
                if (caret >= 0) path = path.substring(0, caret)
                val pipe = path.indexOf('|')
                if (pipe >= 0) path = path.substring(0, pipe)
                if (path.length < 2) continue
                scoped.getOrPut(host) { mutableListOf() }
                    .add(ScopedRule(path.lowercase(), domains, notDomains, exception, thirdParty, important))
                continue
            }
            // Anchored/wildcard leftovers — skip.
        }
    }

    /** Identity of a || rule for $badfilter matching: exception flag,
     *  body, and the option set with badfilter removed (order-free). */
    private fun ruleSignature(line: String): String? {
        val exception = line.startsWith("@@")
        val body0 = when {
            line.startsWith("@@||") -> line.substring(4)
            line.startsWith("||") -> line.substring(2)
            else -> return null
        }
        val dollar = body0.indexOf('$')
        val body = if (dollar >= 0) body0.substring(0, dollar) else body0
        val options = if (dollar >= 0) body0.substring(dollar + 1) else ""
        val opts = options.split(',').map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it != "badfilter" }.sorted()
        return (if (exception) "@@" else "") + body + "$" + opts.joinToString(",")
    }

    /**
     * Plain-CSS selector gate: combinators, commas and functional
     * :not()/:has() are all valid CSS that Chromium evaluates
     * natively. Procedural pseudos (:contains, :has-text, :xpath,
     * :style(), ...) are NOT CSS and stay out — one invalid
     * selector would void its whole rule (the sheet is chunked in
     * cosmeticCss to bound even that).
     */
    private fun isCssSelector(sel: String): Boolean {
        if (sel.isEmpty() || sel.length > 200) return false
        val banned = listOf(
            ":contains", ":has-text", ":xpath", ":matches-css",
            ":matches-attr", ":style(", ":remove(", ":upward",
            ":-abp-", ":if(", ":min-text-length", ":watch-attr", "::",
        )
        if (banned.any { sel.contains(it) }) return false
        return sel.all { it.isLetterOrDigit() || it in "#.[]=\"'^$*|_- >+~,()%!:" }
    }

    /** Hosts-file format (0.0.0.0 domain / 127.0.0.1 domain lines). */
    internal fun parseHosts(text: String, hosts: MutableSet<String>) {
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 2) continue
            if (parts[0] != "0.0.0.0" && parts[0] != "127.0.0.1") continue
            val host = parts[1].lowercase()
            if (host.contains('.') && host.any { it.isLetter() } &&
                host.all { it.isLetterOrDigit() || it == '.' || it == '-' }
            ) {
                hosts.add(host)
            }
        }
    }

    // ---- Matching ----

    /** True if [host] or any of its parent domains is in [set]. */
    private fun matches(set: Set<String>, host: String): Boolean {
        var h = host
        while (true) {
            if (set.contains(h)) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
            if (!h.contains('.')) return set.contains(h)
        }
    }

    /** [host] and each parent domain, longest first. */
    private fun hostChain(host: String): List<String> {
        val out = ArrayList<String>()
        var h = host
        while (true) {
            out.add(h)
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
            if (!h.contains('.')) { out.add(h); break }
        }
        return out
    }

    private val multiLabelSuffixes = setOf(
        // United Kingdom
        "ac.uk", "co.uk", "gov.uk", "me.uk", "net.uk", "nhs.uk", "org.uk", "police.uk", "sch.uk",
        // Australia / New Zealand
        "asn.au", "com.au", "edu.au", "gov.au", "id.au", "net.au", "org.au",
        "ac.nz", "co.nz", "geek.nz", "govt.nz", "net.nz", "org.nz", "school.nz",
        // Japan / Korea
        "ac.jp", "co.jp", "go.jp", "ne.jp", "or.jp",
        "ac.kr", "co.kr", "go.kr", "ne.kr", "or.kr",
        // India
        "ac.in", "co.in", "firm.in", "gen.in", "gov.in", "ind.in", "net.in", "org.in",
        // Brazil / other Latin America
        "com.br", "gov.br", "net.br", "org.br",
        "com.ar", "com.co", "com.mx", "gob.mx", "com.pe", "com.ve",
        // China / Taiwan / Hong Kong / Singapore
        "com.cn", "gov.cn", "net.cn", "org.cn",
        "com.tw", "gov.tw", "org.tw",
        "com.hk", "gov.hk", "org.hk",
        "com.sg", "gov.sg",
        // South Africa, Israel, Turkey, and others with common second levels
        "ac.za", "co.za", "gov.za", "org.za",
        "ac.il", "co.il", "gov.il", "org.il",
        "com.tr", "gov.tr", "org.tr",
        "com.pl", "com.ua", "com.vn", "com.ph", "com.my", "com.pk", "com.ng", "com.eg",
        "com.sa", "com.ae", "com.qa", "com.bd", "com.np", "com.lk", "com.kh", "com.mm",
    )

    private val ipv4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    /** The registrable domain (eTLD+1) of [host]: last two labels,
     *  or last three when the last two are a known multi-label
     *  suffix (co.uk, com.au, …). IPs are their own domain. */
    internal fun registrableDomain(host: String): String {
        val h = host.lowercase().trimEnd('.')
        if (ipv4.matches(h)) return h
        val labels = h.split('.').filter { it.isNotEmpty() }
        if (labels.size <= 2) return labels.joinToString(".")
        val lastTwo = labels.takeLast(2).joinToString(".")
        return if (lastTwo in multiLabelSuffixes) {
            labels.takeLast(3).joinToString(".")
        } else {
            lastTwo
        }
    }

    /**
     * True when [a] and [b] are the same site: the same host, or
     * hosts under the same registrable domain (e.g. login.example.com
     * and www.example.com). IP addresses only ever match themselves.
     */
    internal fun sameSite(a: String, b: String): Boolean {
        val ha = a.lowercase().trimEnd('.')
        val hb = b.lowercase().trimEnd('.')
        if (ha == hb) return true
        if (ipv4.matches(ha) || ipv4.matches(hb)) return false
        return registrableDomain(ha) == registrableDomain(hb)
    }

    /** True when the request is cross-site relative to the page:
     *  anything not on the page's own registrable domain (eTLD+1)
     *  is third-party — so sibling subdomains of a co.uk-style site
     *  count as first-party, as they should. */
    private fun isThirdParty(reqHost: String, pageHost: String): Boolean {
        if (pageHost.isEmpty() || reqHost == pageHost) return false
        return !sameSite(reqHost, pageHost)
    }

    private fun scopedMatch(
        reqHost: String,
        path: String,
        pageHost: String,
        wantException: Boolean,
        onlyImportant: Boolean = false,
    ): Boolean {
        for (h in hostChain(reqHost)) {
            val rules = scopedRules[h] ?: continue
            for (r in rules) {
                if (r.exception != wantException) continue
                if (onlyImportant && !r.important) continue
                if (r.domains != null &&
                    (pageHost.isEmpty() || !matches(r.domains, pageHost))
                ) continue
                if (r.notDomains != null && pageHost.isNotEmpty() &&
                    matches(r.notDomains, pageHost)
                ) continue
                if (r.thirdParty != null &&
                    r.thirdParty != isThirdParty(reqHost, pageHost)
                ) continue
                if (r.pathPrefix != null && !path.startsWith(r.pathPrefix)) continue
                return true
            }
        }
        return false
    }

    /**
     * The blocking decision for one subresource request. Plain
     * strings throughout so the unit tests can run it without a
     * device. Order mirrors Mariner's (user rules beat lists;
     * $important beats exceptions; exceptions beat blocks), with
     * AutoSpace's single [enabled] flag and its allow-host rule:
     * a main-frame load is NEVER blocked, and an allowed host is
     * never blocked either — the allow rule is the driver's
     * explicit override for a site the lists break.
     */
    fun isBlocked(
        scheme: String,
        host: String,
        path: String,
        urlString: String,
        isForMainFrame: Boolean,
        enabled: Boolean,
        pageHost: String = "",
    ): Boolean {
        if (isForMainFrame) return false
        if (!enabled) return false
        val h = host.lowercase()
        if (h.isEmpty()) return false
        // The driver's own allow rule beats everything below it:
        // an explicit tap to allow something is the strongest
        // signal there is. It still honours the global toggle
        // (the early return above) and main-frame loads already
        // returned above, so a rule can never break a tapped link.
        if (UserBlockRules.allowMatches(userNetRules, h)) return false
        // The driver's own block rules beat the compiled lists.
        if (userNetRules.any {
                UserBlockRules.netMatches(it, scheme, h, path)
            }
        ) return true
        if (matches(neverBlockHosts, h)) return false
        val p = path.lowercase()
        val page = pageHost.lowercase()
        // $important block rules beat every exception.
        if (scopedMatch(h, p, page, wantException = false, onlyImportant = true)) return true
        if (matches(exceptionHosts, h)) return false
        if (scopedMatch(h, p, page, wantException = true)) return false
        if (matches(builtinHosts, h)) return true
        if (matches(listHosts, h)) return true
        if (scopedMatch(h, p, page, wantException = false)) return true
        val s = urlString.lowercase()
        if (pathKeywords.any { s.contains(it) }) return true
        return false
    }

    /**
     * True when [host] is a known document/popup ad host: the lists
     * carry a pure ||host^ rule whose only type options are $document
     * and/or $popup — popunder networks, in practice. Consulted by
     * the navigation guard only; typed addresses and tapped links
     * never reach it. (FilterLists falls back to PopunderHosts'
     * built-in set while no lists are loaded.)
     */
    fun isDocumentHost(host: String): Boolean {
        val h = host.lowercase()
        if (h.isEmpty()) return false
        if (matches(neverBlockHosts, h)) return false
        if (matches(exceptionHosts, h)) return false
        return matches(docHosts, h)
    }

    // ---- Cosmetic CSS ----

    /**
     * Chunks selectors into rules of 25 with the standard
     * hiding suffix, under a total size budget — one invalid
     * selector voids only its own rule in CSS, so a bad selector
     * can never take the whole sheet down with it.
     */
    internal fun chunkCosmeticSelectors(
        sels: Collection<String>, budget: Int,
    ): String {
        if (sels.isEmpty()) return ""
        val suffix = "{display:none!important}"
        val sb = StringBuilder()
        var chunk = StringBuilder()
        var inChunk = 0
        fun flushChunk() {
            if (chunk.isNotEmpty()) {
                sb.append(chunk).append(suffix)
                chunk = StringBuilder()
                inChunk = 0
            }
        }
        for (sel in sels) {
            val extra = if (inChunk == 0) sel.length else sel.length + 1
            if (sb.length + chunk.length + extra + suffix.length > budget) break
            if (inChunk > 0) chunk.append(',')
            chunk.append(sel)
            inChunk++
            if (inChunk >= 25) flushChunk()
        }
        flushChunk()
        return sb.toString()
    }

    /**
     * Builds the document-start script: injects [css] as a style
     * element the moment the document exists, skipping hosts in
     * [offHosts] (the $generichide set, checked in-script against
     * location.hostname because the script is registered before
     * the target host is known). Empty css yields an empty script
     * (nothing registered).
     */
    internal fun buildDocumentStartScript(
        css: String, offHosts: Set<String>,
    ): String {
        if (css.isEmpty()) return ""
        val hosts = offHosts.joinToString(",") { h ->
            "\"" + h.filter { it != '"' && it != '\\' } + "\""
        }
        val esc = css
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("</", "<\\/")
        return "(function(){try{var OFF=[HOSTS];if(OFF.indexOf(location.hostname)>=0)return;var s=document.createElement('style');s.textContent='CSS';(document.documentElement||document).appendChild(s);}catch(e){}})();"
            .replace("HOSTS", hosts)
            .replace("CSS", esc)
    }

    /** CSS that hides ad containers on [pageHost]; "" when nothing applies. */
    fun cosmeticCss(pageHost: String): String {
        val host = pageHost.lowercase()
        if (host.isEmpty()) return ""
        cssCache?.let { if (it.first == host) return it.second }
        val sels = LinkedHashSet<String>()
        // A $generichide exception for this page host turns generic
        // hiding off here (domain rules still apply).
        if (!matches(genericHideOffHosts, host)) sels.addAll(genericSelectors)
        for (h in hostChain(host)) domainSelectors[h]?.let { sels.addAll(it) }
        // The user's own picked selectors: exact host only, never
        // inherited up the chain the way list selectors are.
        sels.addAll(UserBlockRules.selectorsForHost(userCosmeticRules, host))
        // #@# unhide exceptions subtract, exact selector for exact
        // selector — the shape list authors actually write.
        sels.removeAll(cosmeticExcGeneric)
        for (h in hostChain(host)) cosmeticExcDomain[h]?.let { sels.removeAll(it) }
        if (sels.isEmpty()) return ""
        // No silent count cap on selectors. The only guard is a
        // generous size budget for the injected <style>; past it,
        // selectors are trimmed (generic first) instead of dropping
        // the whole sheet. Rules are CHUNKED 25 selectors at a time.
        val css = chunkCosmeticSelectors(sels, budget = 200_000)
        if (css.isEmpty()) return ""
        cssCache = host to css
        return css
    }
}
