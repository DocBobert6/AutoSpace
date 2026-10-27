package app.autospace.car

/**
 * r85 — the driver's own blocking rules, ported from Notscape
 * Mariner's UserBlockRules (browser/UserBlockRules.kt) and adapted
 * to AutoSpace's settings storage (raw strings under two
 * BrowserStore keys; see BrowserStore.userNetRaw / userCosmeticRaw).
 *
 * Stored one rule per line, tab-separated — the format keeps the
 * data human-readable in storage and trivially portable:
 *   net \t HOST \t <host>                  everything from a host
 *   net \t URL \t <scheme://host/path>     one address (query and
 *                                          fragment stripped at save
 *                                          so ad URLs with
 *                                          cache-busters still
 *                                          match on reload)
 *   net \t ALLOW \t <host>                 AutoSpace addition:
 *                                          unbreak a site the lists
 *                                          break; checked FIRST in
 *                                          FilterEngine.isBlocked,
 *                                          so it beats user blocks
 *                                          and lists alike
 *   cos \t <page host> \t <selector>       hide one element on one
 *                                          page host, exact host
 *                                          only, never inherited
 *
 * PURE: plain strings only (Mariner's netMatches deliberately
 * avoids android.net.Uri so it stays JVM-testable); a network rule
 * never blocks a main-frame load (enforced by the caller, which
 * only consults these for subresources).
 */
object UserBlockRules {

    /** One network rule the driver added. */
    sealed interface NetRule {
        data class Host(val host: String) : NetRule
        data class Url(val urlKey: String) : NetRule
        /** AutoSpace addition (Mariner has block rules only). */
        data class Allow(val host: String) : NetRule
    }

    /** One cosmetic rule the driver added. */
    data class CosmeticRule(val pageHost: String, val selector: String)

    /** The host a URL rule keys on, with query and fragment
     *  stripped: an ad URL with a cache-buster must still match on
     *  reload, and the strip is done at save time, not match time. */
    fun urlKey(scheme: String, host: String, path: String): String {
        val p = if (path.isEmpty()) "/" else path
        return scheme.lowercase() + "://" + host.lowercase() + p
    }

    /** Parses the stored network rules; malformed lines are
     *  skipped, never fatal. */
    fun parseNet(raw: String?): List<NetRule> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<NetRule>()
        for (line in raw.lineSequence()) {
            val parts = line.split('\t')
            if (parts.size != 3 || parts[0] != "net") continue
            when (parts[1]) {
                "HOST" -> {
                    val h = parts[2].trim().lowercase()
                    if (h.contains('.') &&
                        h.all { it.isLetterOrDigit() || it == '.' || it == '-' }
                    ) out.add(NetRule.Host(h))
                }
                "URL" -> {
                    val u = parts[2].trim()
                    val q = u.indexOf('?')
                    val f = u.indexOf('#')
                    val cut = listOf(q, f).filter { it >= 0 }.minOrNull() ?: u.length
                    val cleaned = u.substring(0, cut)
                    if (cleaned.contains("://")) out.add(NetRule.Url(cleaned))
                }
                "ALLOW" -> {
                    val h = parts[2].trim().lowercase()
                    if (h.contains('.') &&
                        h.all { it.isLetterOrDigit() || it == '.' || it == '-' }
                    ) out.add(NetRule.Allow(h))
                }
            }
        }
        return out
    }

    fun serializeNet(rules: List<NetRule>): String =
        rules.joinToString("\n") {
            when (it) {
                is NetRule.Host -> "net\tHOST\t${it.host}"
                is NetRule.Url -> "net\tURL\t${it.urlKey}"
                is NetRule.Allow -> "net\tALLOW\t${it.host}"
            }
        }

    /** Parses the stored cosmetic rules; unsafe selectors are
     *  rejected at parse time, so a stored rule can never inject
     *  anything but a plain selector. */
    fun parseCosmetic(raw: String?): List<CosmeticRule> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<CosmeticRule>()
        for (line in raw.lineSequence()) {
            val parts = line.split('\t')
            if (parts.size != 3 || parts[0] != "cos") continue
            val host = parts[1].trim().lowercase()
            val sel = parts[2].trim()
            if (host.contains('.') && isSafeSelector(sel)) {
                out.add(CosmeticRule(host, sel))
            }
        }
        return out
    }

    fun serializeCosmetic(rules: List<CosmeticRule>): String =
        rules.joinToString("\n") { "cos\t${it.pageHost}\t${it.selector}" }

    /**
     * A selector is safe when it is a plain selector and nothing
     * else: no braces, semicolons, angle brackets or newlines, so
     * it can never escape the style element it is injected into.
     */
    fun isSafeSelector(sel: String): Boolean {
        if (sel.isEmpty() || sel.length > 200) return false
        if (sel.contains('{') || sel.contains('}') || sel.contains(';') ||
            sel.contains('<') || sel.contains('>') || sel.contains('\n')
        ) return false
        return sel.all {
            it.isLetterOrDigit() || it in "#.[]=\"'^$*|_- >+~,()%!:"
        }
    }

    /** True when a block rule matches this subresource request.
     *  Allow rules are NOT matched here — FilterEngine checks them
     *  separately, first, via [allowMatches]. */
    fun netMatches(rule: NetRule, scheme: String, host: String, path: String): Boolean {
        val h = host.lowercase()
        return when (rule) {
            is NetRule.Host -> h == rule.host || h.endsWith("." + rule.host)
            is NetRule.Url -> {
                val p = if (path.isEmpty()) "/" else path
                urlKey(scheme, h, p) == rule.urlKey
            }
            is NetRule.Allow -> false
        }
    }

    /** True when any allow rule covers [host] (exact host or a
     *  subdomain of it). Checked first in FilterEngine.isBlocked. */
    fun allowMatches(rules: List<NetRule>, host: String): Boolean {
        val h = host.lowercase()
        return rules.any {
            it is NetRule.Allow && (h == it.host || h.endsWith("." + it.host))
        }
    }

    /** The user's selectors for exactly [pageHost] — exact host
     *  only, never inherited up the chain the way list selectors
     *  are. */
    fun selectorsForHost(
        rules: List<CosmeticRule>, pageHost: String,
    ): List<String> =
        rules.filter { it.pageHost == pageHost.lowercase() }
            .map { it.selector }
}
