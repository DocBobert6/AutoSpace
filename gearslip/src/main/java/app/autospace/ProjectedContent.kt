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
            GearslipLog.i("projecting startup URL from settings: $url")
            return url
        }
        val legacy = context.getExternalFilesDir(null)?.let { File(it, "url.txt") }
        if (legacy != null && legacy.isFile) {
            val url = legacy.readText().trim()
            if (url.isNotEmpty()) {
                GearslipLog.i("projecting url.txt override: $url")
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

    /** Big targets, a live clock, and tap feedback - all verifiable at a glance in a car. */
    val TEST_PAGE = """
        <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
          html{height:100%}
          body{margin:0;font-family:sans-serif;background:#101418;color:#fff;
               display:flex;flex-direction:column;height:100%}
          h1{margin:12px;font-size:28px}
          #t{margin:0 12px 8px;font-size:20px;color:#8ab4f8}
          .row{display:flex;flex:1;gap:8px;padding:8px}
          .b{flex:1;display:flex;align-items:center;justify-content:center;
             font-size:26px;border-radius:12px;background:#243043}
          .hit{background:#2e7d32}
        </style></head><body>
        <h1>Gearslip &mdash; projected from the phone</h1>
        <div id="t">starting&hellip;</div>
        <div class="row">
          <div class="b" id="a">TAP A</div><div class="b" id="b">TAP B</div>
          <div class="b" id="c">TAP C</div>
        </div>
        <script>
          setInterval(function(){document.getElementById('t').textContent=new Date().toLocaleTimeString();},1000);
          ['a','b','c'].forEach(function(id){
            var e=document.getElementById(id);
            e.addEventListener('touchstart',function(){e.classList.add('hit');e.textContent='HIT '+id.toUpperCase();});
            e.addEventListener('touchend',function(){setTimeout(function(){e.classList.remove('hit');e.textContent='TAP '+id.toUpperCase();},400);});
          });
        </script></body></html>
    """.trimIndent()
}
