package app.autospace.car

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.autospace.AppSettings
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.net.URLEncoder
import org.json.JSONObject

/**
 * Browser batch (r21) support code, ported from Notscape where noted and otherwise
 * written for AutoSpace's car browser. Pure functions (CleanLink, SearchTemplate,
 * BrowserFilter host matching, the session/pin codecs) use only java.net so the
 * unit tests can run them without a device.
 */

/**
 * Clean links (Notscape CleanLink, ported verbatim in logic): strips tracking query
 * parameters (utm_* and the click-id family) from a URL before it is copied or shared.
 * Non-http(s) URLs and URLs without tracked parameters pass through untouched.
 */
object CleanLink {

    private val trackedNames = setOf(
        "fbclid", "gclid", "dclid", "msclkid", "mc_cid", "mc_eid",
        "igshid", "_ga", "yclid", "twclid", "ttclid", "li_fat_id",
    )

    private fun isTracked(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("utm_") || n in trackedNames
    }

    fun strip(url: String): String {
        if (url.isBlank()) return url
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return url
        val scheme = uri.scheme?.lowercase() ?: return url
        if (scheme != "http" && scheme != "https") return url
        val rawQuery = uri.rawQuery ?: return url
        val kept = rawQuery.split("&").filter { segment ->
            val rawName = segment.substringBefore("=")
            val name = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)
            !isTracked(name)
        }
        if (kept.size == rawQuery.split("&").size) return url
        val rebuilt = StringBuilder()
        rebuilt.append(uri.scheme).append("://")
        uri.rawAuthority?.let { rebuilt.append(it) }
        uri.rawPath?.let { rebuilt.append(it) }
        if (kept.isNotEmpty()) rebuilt.append("?").append(kept.joinToString("&"))
        uri.rawFragment?.let { rebuilt.append("#").append(it) }
        return rebuilt.toString()
    }
}

/**
 * Validation for a custom search-engine URL template (Notscape SearchTemplate,
 * ported verbatim): https only, a %s placeholder required, and the
 * javascript:/data:/file: schemes refused outright. Returns a plain error
 * string, or null when the template is usable.
 */
object SearchTemplate {

    fun validate(raw: String): String? {
        val u = raw.trim()
        if (u.isEmpty()) return "Enter a search URL."
        val lower = u.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("data:") ||
            lower.startsWith("file:")
        ) {
            return "That kind of URL can't be used for search."
        }
        if (!lower.startsWith("https://")) {
            return "The search URL must start with https://"
        }
        if (!u.contains("%s")) {
            return "The URL must contain %s where the search terms go."
        }
        return null
    }

    fun isValid(raw: String): Boolean = validate(raw) == null
}

/**
 * The search engine the address bar and the start page search with (r21).
 * Default is no-AI DuckDuckGo, exactly as AutoSpace has always searched
 * ([ProjectedContent.searchUrl], assist=false); a valid custom template
 * replaces it, and clearing it (or an invalid stored value) returns to
 * that default. Voice "search for X" is NOT routed here — it keeps the
 * existing no-AI DuckDuckGo path from r19, by design.
 */
object BrowserSearch {
    private const val KEY_CUSTOM_TEMPLATE = "browser_search_template"
    const val DEFAULT_NAME = "No-AI DuckDuckGo"

    fun customTemplate(context: Context): String =
        AppSettings.getString(context, KEY_CUSTOM_TEMPLATE, "")

    fun setCustomTemplate(context: Context, template: String) {
        AppSettings.putString(context, KEY_CUSTOM_TEMPLATE, template.trim())
    }

    fun clearCustomTemplate(context: Context) {
        AppSettings.putString(context, KEY_CUSTOM_TEMPLATE, "")
    }

    fun searchUrl(context: Context, query: String): String {
        val template = customTemplate(context).takeIf { SearchTemplate.isValid(it) }
            ?: return app.autospace.ProjectedContent.searchUrl(query)
        return template.replace("%s", Uri.encode(query))
    }

    /** Address-bar input: a URL loads, anything else searches with the engine setting. */
    fun navigateUrl(context: Context, input: String): String {
        val text = input.trim()
        if (text.isEmpty()) return app.autospace.ProjectedContent.DEFAULT_URL
        if (text.startsWith("<")) return text
        if ("://" in text) return text
        val looksLikeDomain = !text.contains(' ') && text.substringBefore('/').contains('.')
        return if (looksLikeDomain) "https://$text" else searchUrl(context, text)
    }
}

/**
 * Ad/tracker blocking (r21): Notscape FilterEngine's BUILT-IN host set only.
 * The EasyList/EasyPrivacy downloads, path rules, cosmetic rules and
 * user-subscribed lists are NOT ported — the bundled list ships with the
 * app and changes only with an app update, and there is no subscription
 * UI. A host matches itself and every subdomain. Main-frame loads are
 * never blocked (the caller checks that); only subresources are.
 */
object BrowserFilter {

    val builtinHosts = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "google-analytics.com", "googletagmanager.com", "googletagservices.com",
        "analytics.twitter.com", "ads-twitter.com", "connect.facebook.net",
        "facebook.net", "adservice.google.com", "pagead2.googlesyndication.com",
        "tpc.googlesyndication.com", "partner.googleadservices.com",
        "amazon-adsystem.com", "aax.amazon-adsystem.com", "ads.yahoo.com",
        "analytics.yahoo.com", "advertising.com", "adnxs.com", "adsrvr.org",
        "rubiconproject.com", "pubmatic.com", "openx.net", "criteo.com",
        "criteo.net", "taboola.com", "outbrain.com", "scorecardresearch.com",
        "quantserve.com", "moatads.com", "zedo.com", "smartadserver.com",
        "lijit.com", "sovrn.com", "sharethrough.com", "triplelift.com",
        "indexww.com", "casalemedia.com", "contextweb.com", "spotxchange.com",
        "mathtag.com", "bluekai.com", "demdex.net", "krxd.net", "exelator.com",
        "turn.com", "adform.net", "bidswitch.net", "w55c.net", "rlcdn.com",
        "pippio.com", "eyeota.net", "tapad.com", "drawbridge.com",
        "crossdevice.com", "adsymptotic.com", "nr-data.net", "newrelic.com",
        "hotjar.com", "fullstory.com", "mouseflow.com", "crazyegg.com",
        "mixpanel.com", "segment.com", "segment.io", "amplitude.com",
        "branch.io", "adjust.com", "appsflyer.com", "kochava.com",
        "leanplum.com", "clevertap.com", "onesignal.com", "pushwoosh.com",
        "bat.bing.com", "clarity.ms", "mc.yandex.ru",
        "adskeeper.com", "mgid.com", "revcontent.com", "contentad.net",
        "zergnet.com", "newsmaxfeednetwork.com", "disqusads.com",
        "propellerads.com", "propellerclick.com", "onclicks.net",
        "popads.net", "popcash.net", "popmyads.com", "clickadu.com",
        "trafficjunky.net", "exoclick.com", "juicyads.com",
        "applovin.com", "unityads.unity3d.com", "adroll.com",
        "advertising.yahoo.com", "ysm.yahoo.com", "adtech.yahooinc.com",
        "bingads.microsoft.com", "ads.microsoft.com",
        "ads.linkedin.com", "px.ads.linkedin.com", "snap.licdn.com",
        "fls-na.amazon.com", "fingerprintjs.com", "fpjs.io",
        "siftscience.com", "permutive.com", "onetag-sys.com",
        "id5-sync.com", "doubleverify.com",
        "coinimp.com", "webminepool.com", "minero.cc", "mineralt.io",
        "monerominer.rocks", "ads.vk.com", "ad.mail.ru",
        "pixel.quora.com", "qevents.quora.com", "px.arvcs.tumblr.com",
        "widgets.pinterest.com", "advertising.apple.com", "xp.apple.com",
        "metrics.mzstatic.com",
    )

    fun isBlockedHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase().removePrefix("www.")
        return builtinHosts.any { h == it || h.endsWith(".$it") }
    }

    fun isBlockedUrl(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull()
        return isBlockedHost(host)
    }

    /** An empty body for a blocked subresource — never used for a main frame. */
    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}

/**
 * True private browsing (Notscape PrivateProfile, ported): private tabs run
 * in their own WebView profile (cookies, storage and cache fully separate
 * from the normal profile), and the whole profile is deleted when the last
 * private tab closes — and again at app startup, in case the process died
 * while private tabs were open (profiles persist on disk otherwise).
 * Where the device's WebView provider doesn't support multiple profiles,
 * these calls no-op and private tabs fall back to the older behaviour:
 * nothing is written to history, the saved session, or recently closed.
 */
object PrivateProfile {
    private const val NAME = "autospace_private"

    fun supported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    /** Assign [wv] to the private profile. Must run before the WebView loads anything. */
    fun applyTo(wv: WebView) {
        if (!supported()) return
        runCatching {
            ProfileStore.getInstance().getOrCreateProfile(NAME)
            WebViewCompat.setProfile(wv, NAME)
        }
    }

    /** Delete the private profile and everything stored in it. Callers destroy
     *  private WebViews first — the platform refuses while one still lives. */
    fun wipe() {
        if (!supported()) return
        runCatching { ProfileStore.getInstance().deleteProfile(NAME) }
    }
}

/**
 * WebView hardening baseline (Notscape applyWebHardening, ported with one
 * deliberate omission, flagged in delivery): no file access, no content
 * access, no file-URL access, no mixed content (NEVER), Safe Browsing on
 * where offered, no saved form data. Geolocation is NOT touched here —
 * Robert did not settle that flag, so AutoSpace's existing behaviour
 * (the flag is never set by AutoSpace code, and no geolocation permission
 * callback grants it) is left exactly as-is. Likewise the GearslipField
 * JavaScript interface is NOT removed: CarKeyboard's page-field editing
 * depends on it, and it exposes only focus/blur callbacks, no data.
 */
fun applyWebHardening(settings: android.webkit.WebSettings) {
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    @Suppress("DEPRECATION")
    settings.allowFileAccessFromFileURLs = false
    @Suppress("DEPRECATION")
    settings.allowUniversalAccessFromFileURLs = false
    @Suppress("DEPRECATION")
    settings.saveFormData = false
    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        settings.safeBrowsingEnabled = true
    }
}

// ---- Session / recently-closed / pin codecs (pure, unit-tested) ----

/** One saved tab or pin: a URL plus its display title. */
data class SavedPage(val url: String, val title: String)

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
private fun dec(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

fun encodePages(pages: List<SavedPage>): String =
    pages.joinToString("\n") { "${enc(it.url)}|${enc(it.title)}" }

fun decodePages(raw: String): List<SavedPage> {
    if (raw.isBlank()) return emptyList()
    return raw.lines().mapNotNull { line ->
        val parts = line.split("|")
        if (parts.size < 2) return@mapNotNull null
        val url = dec(parts[0])
        if (url.isBlank()) return@mapNotNull null
        SavedPage(url, dec(parts[1]))
    }
}

/** Session file: first line "active:<index>", then one page per line. */
fun encodeSession(pages: List<SavedPage>, activeIndex: Int): String =
    "active:$activeIndex\n" + encodePages(pages)

fun decodeSession(raw: String): Pair<List<SavedPage>, Int> {
    if (raw.isBlank()) return emptyList<SavedPage>() to 0
    val lines = raw.lines()
    val active = lines.firstOrNull()?.removePrefix("active:")?.toIntOrNull() ?: 0
    val pages = decodePages(lines.drop(1).joinToString("\n"))
    return pages to active.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
}

// ---- Reader extraction (Notscape ArticleExtractor, ported) ----

/**
 * Reader-mode extraction with Mozilla Readability 0.6.0 (Apache-2.0,
 * vendored under assets/readability/). The library runs inside the live
 * tab's WebView against a CLONE of the document — parse() destroys the
 * DOM it is given — gated by isProbablyReaderable, exactly as Firefox
 * does. The extracted HTML is sanitized in Kotlin and rendered by the
 * reader shell below in a WebView with JavaScript disabled.
 */
data class Article(
    val url: String,
    val title: String,
    val byline: String,
    val siteName: String,
    val contentHtml: String,
)

/** The most recently extracted article, handed from the browser tab to the reader view. */
object ArticleCache {
    @Volatile
    var current: Article? = null
}

object ArticleExtractor {

    @Volatile
    private var librarySource: String? = null

    /** Readability.js + Readability-readerable.js, concatenated once. */
    fun loadLibrary(context: Context): String {
        librarySource?.let { return it }
        val src = buildString {
            append(context.assets.open("readability/Readability.js").bufferedReader().use { it.readText() })
            append("\n;\n")
            append(context.assets.open("readability/Readability-readerable.js").bufferedReader().use { it.readText() })
        }
        librarySource = src
        return src
    }

    /**
     * The extraction glue, appended after the library source and passed as ONE
     * evaluateJavascript string. Injecting a <script> element instead would be
     * subject to the page's CSP; the evaluated string is not. Returns a JS
     * object (never a stringified string) so the callback hands us clean JSON.
     */
    const val GLUE_JS = """
;
(function () {
  try {
    if (typeof Readability === 'undefined' ||
        typeof isProbablyReaderable === 'undefined') return null;
    if (!isProbablyReaderable(document)) return null;
    var article = new Readability(
      document.cloneNode(true), { maxElemsToParse: 80000 }
    ).parse();
    if (!article || !article.content) return null;
    return {
      title: article.title || '',
      byline: article.byline || '',
      siteName: article.siteName || '',
      content: article.content
    };
  } catch (e) { return null; }
})()
"""

    /** Decode the evaluateJavascript callback into an [Article]. */
    fun parseResult(raw: String?, url: String): Article? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return runCatching {
            val o = JSONObject(raw)
            val content = o.optString("content")
            if (content.isBlank()) return null
            Article(
                url = url,
                title = o.optString("title"),
                byline = o.optString("byline"),
                siteName = o.optString("siteName"),
                contentHtml = sanitize(content),
            )
        }.getOrNull()
    }

    /**
     * Readability does not guarantee script-free output (Mozilla's own README
     * says to sanitize), so: drop active-content elements whole, strip inline
     * event handlers, and neutralize javascript: URLs. The reader WebView
     * additionally disables JavaScript entirely.
     */
    fun sanitize(html: String): String {
        var out = html
        for (tag in listOf(
            "script", "iframe", "object", "form", "noscript",
            "template", "svg", "button", "select", "textarea",
        )) {
            out = out.replace(Regex("(?is)<$tag\\b[^>]*>.*?</$tag\\s*>"), "")
            out = out.replace(Regex("(?is)<$tag\\b[^>]*/>"), "")
        }
        for (tag in listOf("embed", "link", "meta", "base", "title", "input")) {
            out = out.replace(Regex("(?is)<$tag\\b[^>]*>"), "")
        }
        out = out.replace(
            Regex("(?i)\\s+on[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)"), "",
        )
        out = out.replace(
            Regex("(?i)(href|src)\\s*=\\s*\"\\s*javascript:[^\"]*\""), "$1=\"#\"",
        )
        out = out.replace(
            Regex("(?i)(href|src)\\s*=\\s*'\\s*javascript:[^']*'"), "$1='#'",
        )
        return out
    }

    /** Plain text of an article, for read-aloud: paragraphs in document order. */
    fun plainText(article: Article): String {
        var text = article.contentHtml
            .replace(Regex("(?is)<(p|div|h[1-6]|li|blockquote|figcaption)[^>]*>"), "\n")
            .replace(Regex("(?is)</(p|div|h[1-6]|li|blockquote|figcaption)>"), "\n")
            .replace(Regex("(?is)<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        text = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        return text
    }

    /**
     * Sentence chunks for read-aloud: sentences grouped to at most ~600 characters,
     * so a long article is never a single giant synthesis — the 20s synth
     * discipline applies per chunk, not per article.
     */
    fun sentenceChunks(text: String, maxChars: Int = 600): List<String> {
        val sentences = text.replace("\n", " ")
            .split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }.filter { it.isNotEmpty() }
        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        for (sentence in sentences) {
            if (current.isNotEmpty() && current.length + 1 + sentence.length > maxChars) {
                chunks += current.toString()
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append(" ")
            current.append(sentence)
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }
}

private fun escapeHtml(s: String): String = s
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\"", "&quot;")

/**
 * The reader document shell: a single column with a site / title / byline header
 * over the extracted article HTML, in the car UI's own surface/on-surface/link
 * colors at large car-legible type. This shell is AutoSpace chrome, not page
 * theming: the page itself is never restyled — the reader builds a NEW document
 * from extracted text, and the original page is untouched underneath.
 */
fun buildReaderShell(article: Article, bg: String, fg: String, link: String): String {
    val byline = if (article.byline.isBlank()) "" else
        """<div class="byline">${escapeHtml(article.byline)}</div>"""
    val site = if (article.siteName.isBlank()) "" else
        """<div class="site">${escapeHtml(article.siteName)}</div>"""
    return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<base href="${escapeHtml(article.url)}">
<title>${escapeHtml(article.title)}</title>
<style>
  html, body { margin: 0; padding: 0; background: $bg; }
  body {
    color: $fg;
    font-family: system-ui, sans-serif;
    font-size: 22px;
    line-height: 1.6;
    -webkit-text-size-adjust: 100%;
  }
  main { max-width: 720px; margin: 0 auto; padding: 28px 20px 72px; }
  .site {
    font-size: 13px; letter-spacing: 0.05em; text-transform: uppercase;
    opacity: 0.6; margin-bottom: 10px;
  }
  h1 { font-size: 1.5em; line-height: 1.22; margin: 0 0 10px; font-weight: 700; }
  .byline { font-size: 0.82em; opacity: 0.65; margin-bottom: 26px; }
  img, video, picture { max-width: 100%; height: auto; border-radius: 10px; }
  figure { margin: 1.2em 0; }
  figcaption { font-size: 0.8em; opacity: 0.65; margin-top: 6px; text-align: center; }
  a { color: $link; }
  pre {
    overflow-x: auto; background: rgba(127,127,127,0.14);
    padding: 12px; border-radius: 10px; font-size: 0.86em;
  }
  code { font-family: ui-monospace, Menlo, monospace; }
  blockquote {
    margin: 1em 0; padding-left: 14px;
    border-left: 3px solid rgba(127,127,127,0.4);
  }
  table {
    display: block; overflow-x: auto; border-collapse: collapse;
    font-size: 0.9em; max-width: 100%;
  }
  td, th { border: 1px solid rgba(127,127,127,0.35); padding: 6px 8px; }
  hr { border: none; border-top: 1px solid rgba(127,127,127,0.35); margin: 1.6em 0; }
  * { overflow-wrap: break-word; }
</style>
</head>
<body>
<main>
  $site
  <h1>${escapeHtml(article.title)}</h1>
  $byline
  ${article.contentHtml}
</main>
</body>
</html>"""
}
