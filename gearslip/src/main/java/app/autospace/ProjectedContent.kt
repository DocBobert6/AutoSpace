package app.autospace

import android.content.Context
import java.io.File

/** Decides what the car screen shows. */
object ProjectedContent {

    /**
     * Precedence: the URL saved in Settings, then a `url.txt` in the external files dir (the
     * pre-Settings way, still honoured so existing setups keep working), then [DEFAULT_URL].
     */
    fun resolve(context: Context): String {
        val saved = AppSettings.startupUrl(context)
        if (saved.isNotEmpty()) {
            val url = normalize(saved)
            GearslipLog.i("projecting startup URL from settings: ${GearslipLog.loggableUrl(url)}")
            return url
        }
        val legacy = context.getExternalFilesDir(null)?.let { File(it, "url.txt") }
        if (legacy != null && legacy.isFile) {
            val url = legacy.readText().trim()
            if (url.isNotEmpty()) {
                GearslipLog.i("projecting url.txt override: ${GearslipLog.loggableUrl(url)}")
                return url
            }
        }
        return DEFAULT_URL
    }

    /** What the Web app opens until a page is chosen in Settings. */
    // noai.duckduckgo.com hides AI summaries/chat icons (Phase 3.1); the plain
    // duckduckgo.com default is superseded and must not come back.
    const val DEFAULT_URL = "https://noai.duckduckgo.com/"

    /** A search on the no-AI DuckDuckGo endpoint, with AI assistance off. */
    fun searchUrl(query: String): String =
        "https://noai.duckduckgo.com/?q=${java.net.URLEncoder.encode(query, "UTF-8")}&assist=false"

    /**
     * What the browser address/search box does with typed text (Phase 3.1/3.4):
     * something that looks like a URL loads as one (scheme added if missing);
     * anything else is a no-AI search for that text.
     */
    fun navigateUrl(input: String): String {
        val text = input.trim()
        if (text.isEmpty()) return DEFAULT_URL
        if (text.startsWith("<")) return text
        if ("://" in text) return text
        // A bare domain ("example.com", "example.com/path") with no spaces is a URL;
        // anything with spaces or without a dot is a search query.
        val looksLikeDomain = !text.contains(' ') && text.substringBefore('/').contains('.')
        return if (looksLikeDomain) "https://$text" else searchUrl(text)
    }

    /** A bare "example.com" is a URL the user meant; raw HTML is passed through untouched. */
    private fun normalize(value: String): String =
        if (value.startsWith("<") || "://" in value) value else "https://$value"

}
