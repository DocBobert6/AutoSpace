package app.autospace.car

/**
 * Phase 6 (r186) — local entry-point suggestions + Frequently visited.
 *
 * Everything in this file is pure (no Android framework, no Context),
 * so the JVM suite can pin it. The composables that render these live
 * in WebApp.kt; the hidden-hosts store lives in BrowserStore.
 *
 * Local suggestions: matches from this device's own bookmarks and
 * history only — Mariner's card, car-sized. Nothing is sent anywhere.
 * Bookmarks and history carry no titles in AutoSpace (titles arrive
 * with G2), so a suggestion's display title is its host and its
 * second line is the full URL. Bookmark matches come first and wear
 * a star; history matches wear a history glyph.
 */
data class LocalSuggestion(val title: String, val url: String, val isBookmark: Boolean)

/** One host on the Frequently visited list: its most recent visit URL + its count. */
data class FrequentHost(val host: String, val url: String, val visits: Int)

/** Mariner's suggestion cap, kept: six rows. */
const val LOCAL_SUGGESTION_LIMIT = 6

/** Frequently visited cap, matching the start page's eight history chips. */
const val FREQUENT_VISITED_LIMIT = 8

/**
 * Host from a URL with plain string ops (no android.net.Uri, so this
 * stays JVM-testable): strip the scheme, then userinfo, then the
 * path/query/fragment, then the port. Falls back to the whole URL
 * when nothing parses.
 */
fun suggestionHostOf(url: String): String {
    val noScheme = url.substringAfter("://", url)
    val hostPort = noScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    val host = hostPort.substringAfter('@', hostPort).substringBefore(':')
    return host.ifBlank { url }
}

/**
 * Local suggestions for [query]: bookmarks first, then history, each
 * matched case-insensitively on the URL, deduped by URL (a bookmark
 * wins over the same history URL), capped at [limit]. A blank query
 * matches nothing — the card stays hidden until the driver types.
 */
fun localSuggestions(
    bookmarks: List<String>,
    history: List<String>,
    query: String,
    limit: Int = LOCAL_SUGGESTION_LIMIT,
): List<LocalSuggestion> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    val seen = mutableSetOf<String>()
    val out = mutableListOf<LocalSuggestion>()
    for (url in bookmarks) {
        if (out.size >= limit) break
        if (url.lowercase().contains(q) && seen.add(url)) {
            out.add(LocalSuggestion(suggestionHostOf(url), url, true))
        }
    }
    for (url in history) {
        if (out.size >= limit) break
        if (url.lowercase().contains(q) && seen.add(url)) {
            out.add(LocalSuggestion(suggestionHostOf(url), url, false))
        }
    }
    return out
}

/**
 * Frequently visited, from the history list (most-recent-first).
 * History dedups re-visits — BrowserStore.recordHistory moves a
 * re-visited URL to the front instead of counting it — so "visits"
 * here counts DISTINCT pages per host: breadth, not raw visit count.
 * Mariner counts raw visits from timestamped entries; AutoSpace's
 * store cannot reproduce that without a store change, so this is the
 * honest car form: most distinct pages first, ties broken by most
 * recent visit. Each host is represented by its most recent visit
 * URL. Hosts in [hidden] are excluded (their history is untouched).
 */
fun frequentlyVisited(
    history: List<String>,
    hidden: Set<String>,
    limit: Int = FREQUENT_VISITED_LIMIT,
): List<FrequentHost> {
    val firstIndex = mutableMapOf<String, Int>()
    val byHost = linkedMapOf<String, MutableList<String>>()
    history.forEachIndexed { index, url ->
        val host = suggestionHostOf(url)
        firstIndex.putIfAbsent(host, index)
        byHost.getOrPut(host) { mutableListOf() }.add(url)
    }
    return byHost.entries
        .filter { (host, _) -> host !in hidden }
        .map { (host, urls) -> FrequentHost(host, urls.first(), urls.size) }
        .sortedWith(
            compareByDescending<FrequentHost> { it.visits }
                .thenBy { firstIndex[it.host] ?: Int.MAX_VALUE },
        )
        .take(limit)
}
