package app.autospace.car

/**
 * The car side of Notscape Mariner's popunder guard (BrowserScreen, 0.0.962).
 *
 * Notscape's guard is a verbatim logic rule, not a verbatim list: a
 * script-driven main-frame hop with no user gesture behind it, aimed at a
 * host whose filter-list rules are $document/$popup-only, is the swap
 * trick — the page throws its real content into a window and navigates
 * itself to the ad — and is captured like a blocked popup instead of
 * loaded. Typed addresses never pass through the guard (loadUrl skips
 * shouldOverrideUrlLoading) and tapped links carry a gesture, so only
 * the trick is caught.
 *
 * What could NOT be carried over verbatim is the host set itself:
 * Notscape's docHosts are derived at runtime from the user's downloaded
 * filter lists (WebTools.FilterEngine parses $document/$popup rules into
 * the set), and AutoSpace has no filter lists at all — there is no list
 * in Notscape's repo to copy. So the guard's logic is ported exactly
 * (main frame + no gesture + cross-host, see BrowserClient), but the
 * host test is a small built-in set of well-known popunder networks
 * below, suffix-matched. A no-gesture hop to any other cross-host is
 * left alone, because without a list it cannot be told apart from a
 * legitimate script redirect (login, consent, shortener) — blocking
 * those would break real pages, and Notscape's list is what makes its
 * guard safe to run. If AutoSpace ever gains filter lists, this set
 * should be replaced by the parsed $document/$popup set.
 */
object PopunderHosts {
    private val HOSTS = setOf(
        "popads.net",
        "popcash.net",
        "adcash.com",
        "propellerads.com",
        "adsterra.com",
        "popmyads.com",
        "ad-maven.com",
        "clickadu.com",
    )

    /** True when [host] is (or is a subdomain of) a known popunder host. */
    fun isDocumentHost(host: String): Boolean {
        val h = host.lowercase()
        if (h.isEmpty()) return false
        return HOSTS.any { h == it || h.endsWith(".$it") }
    }
}
