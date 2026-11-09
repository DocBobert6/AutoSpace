package app.autospace.car

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Open-in-app (r183): hand a page or link to another app on the phone,
 * instead of the browser's old bare "Could not load" dead-end for
 * non-http(s) schemes. Everything a unit test can pin lives here as
 * pure functions; the Context calls ([hasAppHandler], [openInApp],
 * [launchExternal]) are the only seams that need a device.
 *
 * Rules, exactly as ordered:
 * - Tapped links: only a real tap (never a script-driven hop) may
 *   leave the browser, and only for market: (VIEW), tel: (the
 *   [placeCall] path behind the existing call confirmation),
 *   mailto:/geo:/sms: (LAUNCH, at once), an unknown scheme (ASK,
 *   ask-first naming its target), or an intent: URL carrying an
 *   http(s) fallback (loaded in the tab). http(s) taps are NEVER
 *   hijacked here — they load in the tab as before. Sign-in/OAuth
 *   flows are never handed off. r207: the tel: gates are gone (a
 *   call asks first and dials even while driving or from a private
 *   tab, Mariner's shape); mailto:/geo:/sms: launch ungated too,
 *   with no handler pre-check, so no new manifest queries entry.
 * - Rows (long-press menu, current-page menu row): only for
 *   http(s)/market: URLs, only in a non-private tab, only while
 *   parked or with a live passenger allowance, and only when a
 *   handler actually exists. The handler check is host-specific
 *   for http(s): a generic browser does not count, or the row
 *   would appear on every link and mostly open a browser chooser.
 * - Firing is ask-every-time for http(s) (a chooser, never a
 *   remembered default); market: fires raw (one handler in
 *   practice). Everything runs through runCatching: a failed
 *   launch is a silent no-op, never a crash.
 */
internal object OpenInApp {

    /** What a tapped non-http(s) link does. NONE means "today's behavior". */
    enum class TapAction { VIEW, CALL, LAUNCH, ASK, FALLBACK, NONE }

    /** The scheme of [url], lowercased, or "" when there is none. */
    fun schemeOf(url: String): String {
        val colon = url.indexOf(':')
        if (colon <= 0) return ""
        val scheme = url.substring(0, colon).lowercase()
        return if (scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) scheme else ""
    }

    /**
     * The http(s) fallback embedded in an intent: URL, or null.
     * Format: intent://...#Intent;...;S.browser_fallback_url=<encoded>;end.
     * The value is percent-decoded with '+' preserved as a literal
     * plus (URLDecoder alone would turn it into a space, corrupting
     * query strings). A non-http(s) fallback is not a fallback.
     */
    fun fallbackUrl(intentUrl: String): String? {
        val marker = "#Intent;"
        val start = intentUrl.indexOf(marker)
        if (start < 0) return null
        val rest = intentUrl.substring(start + marker.length)
        val prefix = "S.browser_fallback_url="
        for (segment in rest.split(';')) {
            if (segment.startsWith(prefix)) {
                val raw = segment.substring(prefix.length)
                if (raw.isBlank()) return null
                val decoded = runCatching {
                    java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
                }.getOrNull() ?: return null
                if (decoded.startsWith("http://") || decoded.startsWith("https://")) return decoded
                return null
            }
        }
        return null
    }

    /** What a tapped link does. http(s) taps are never hijacked. */
    fun tapAction(url: String): TapAction {
        return when (schemeOf(url)) {
            "market" -> TapAction.VIEW
            "tel" -> TapAction.CALL
            "mailto", "geo", "sms" -> TapAction.LAUNCH
            "intent" -> if (fallbackUrl(url) != null) TapAction.FALLBACK else TapAction.NONE
            // r207 (compat finding 8): Mariner's table — http(s)/about
            // load in the tab, data:/file: never launch, and anything
            // else asks first, naming its target. intent: without a
            // fallback stays NONE, as in r183.
            "http", "https", "about", "data", "file" -> TapAction.NONE
            "" -> TapAction.NONE
            else -> TapAction.ASK
        }
    }

    /** The phone number of a tel: URL, without params or a query. */
    fun telNumber(url: String): String =
        url.substringAfter(':').substringBefore(';').substringBefore('?')

    /**
     * Fire a VIEW intent for [url]: mailto:/geo:/sms: (and any scheme
     * the ask-first overlay confirmed) launch at once, Mariner's
     * shape — a plain intent, runCatching-guarded, with no handler
     * pre-check, so no new manifest queries entry is needed. A
     * missing handler is a silent no-op. Callers classify first.
     */
    fun launchExternal(context: Context, url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * Whether a row may offer "Open in app" for [url]: a VIEW-able
     * scheme, a non-private tab, and a parked-or-allowed car. The
     * handler-existence check ([hasAppHandler]) is separate, because
     * it needs a PackageManager.
     */
    fun viewOfferable(url: String, isPrivate: Boolean, gateOpen: Boolean): Boolean {
        if (isPrivate || !gateOpen) return false
        return schemeOf(url) == "http" || schemeOf(url) == "https" || schemeOf(url) == "market"
    }

    /**
     * Whether a filter authority claims [host]. Exact matches count;
     * a "*.example.com" wildcard counts for the base host and any
     * subdomain, but the dot guard keeps "evil-example.com" out.
     */
    fun authorityMatches(authority: String?, host: String): Boolean {
        if (authority.isNullOrBlank() || host.isBlank()) return false
        if (authority == host) return true
        if (authority.startsWith("*.")) {
            val base = authority.substring(2)
            if (base.isBlank()) return false
            return host == base || host.endsWith(".$base")
        }
        return false
    }

    /**
     * Whether another app can actually open [url]. http(s) counts
     * only when some handler's filter claims this exact host (see
     * [authorityMatches]) — a generic browser never counts.
     * market: counts on any handler. Anything else never counts.
     * Needs the r183 manifest VIEW queries: without them, package
     * visibility returns nothing and this is always false.
     */
    fun hasAppHandler(context: Context?, url: String): Boolean {
        if (context == null) return false
        val scheme = schemeOf(url)
        if (scheme != "http" && scheme != "https" && scheme != "market") return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val handlers = runCatching {
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrNull() ?: return false
        if (scheme == "market") return handlers.isNotEmpty()
        val host = runCatching { Uri.parse(url).host ?: "" }.getOrDefault("")
        if (host.isBlank()) return false
        for (info in handlers) {
            val filter = info.filter ?: continue
            for (i in 0 until filter.countDataAuthorities()) {
                val entry = runCatching { filter.getDataAuthority(i) }.getOrNull() ?: continue
                if (authorityMatches(entry.host, host)) return true
            }
        }
        return false
    }

    /** Whether a call may be placed from [context] (the placeCall guard). */
    fun canCall(context: Context?): Boolean {
        if (context == null) return false
        return context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Fire a VIEW intent for [url]: a chooser for http(s)
     * (ask-every-time, no remembered default), raw for market:.
     * Anything else is a silent no-op — callers classify first.
     */
    fun openInApp(context: Context, url: String) {
        val scheme = schemeOf(url)
        if (scheme != "http" && scheme != "https" && scheme != "market") return
        runCatching {
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val toStart = if (scheme == "market") view else Intent.createChooser(view, "Open in app")
            context.startActivity(toStart)
        }
    }
}
