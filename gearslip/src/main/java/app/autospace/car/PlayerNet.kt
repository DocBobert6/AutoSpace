package app.autospace.car

import android.content.Context
import android.webkit.CookieManager
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * Shared video-player construction (r193): the fullscreen player and
 * the mini video card both play through here, so every media request
 * carries the browser's identity — the same shape as Notscape
 * Mariner's PlayerNet (Beta.58(1)), ported apart from the package.
 *
 * Many CDNs refuse playback (403) without a browser user-agent, a
 * Referer, and the page's cookies, which looks like a broken stream:
 * cookie-gated streams played fine in the WebView but 403'd in the
 * player, because the player previously sent none of them.
 * CookieManager returns only the cookies valid for the exact URI, so
 * the scoping is the platform's, not ours.
 *
 * Cache (approved default, r193): process-level, 200MB LRU in the
 * app cache dir — a segment cached from a private-tab session
 * persists in the cache folder. That is Mariner's shape as well;
 * scoping the cache per session would be a deliberate extra, not
 * part of this round.
 */
object PlayerNet {

    /** The page's URL at launch time: the Referer for media requests. */
    @Volatile
    var referer: String? = null

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    /** Process-level player cache: 200MB, LRU, in the app cache dir. */
    const val CACHE_SIZE_BYTES = 200L * 1024 * 1024
    const val CACHE_DIR_NAME = "player"

    @Volatile
    private var playerCache: SimpleCache? = null

    private fun cacheFor(context: Context): SimpleCache {
        playerCache?.let { return it }
        synchronized(this) {
            playerCache?.let { return it }
            val cache = SimpleCache(
                java.io.File(context.cacheDir, CACHE_DIR_NAME),
                LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES),
            )
            playerCache = cache
            return cache
        }
    }

    private fun liveCookie(uri: String): String? =
        runCatching { CookieManager.getInstance().getCookie(uri) }
            .getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * The headers a media request gains beyond the user-agent (which
     * rides the HTTP factory, not the resolver): Referer + Cookie,
     * HTTPS only, and only where the request does not already carry
     * them. Pure apart from [cookieFor], which the player passes as
     * the live CookieManager lookup and tests pass as a fake.
     */
    internal fun headersFor(
        uri: String,
        referer: String?,
        existing: Map<String, String>,
        cookieFor: (String) -> String?,
    ): Map<String, String> {
        if (uri.substringBefore(":", "").lowercase() != "https") return emptyMap()
        val out = HashMap<String, String>()
        if (!existing.containsKey("Referer")) {
            referer?.takeIf { it.isNotBlank() }?.let { out["Referer"] = it }
        }
        if (!existing.containsKey("Cookie")) {
            cookieFor(uri)?.takeIf { it.isNotBlank() }?.let { out["Cookie"] = it }
        }
        return out
    }

    /**
     * The headers the HLS quality probe sends for [uri]: the browser
     * user-agent, plus Referer + Cookie over HTTPS (see [headersFor]).
     * The probe runs on Dispatchers.IO; the cookie lookup fails safely
     * inside runCatching when no WebView cookies exist yet.
     */
    fun probeHeaders(uri: String): Map<String, String> {
        val out = HashMap<String, String>()
        out["User-Agent"] = USER_AGENT
        out.putAll(headersFor(uri, referer, emptyMap(), ::liveCookie))
        return out
    }

    fun buildPlayer(context: Context): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        val upstream = CacheDataSource.Factory()
            .setCache(cacheFor(context))
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, http))
        val resolving = ResolvingDataSource.Factory(upstream) { spec ->
            val extra = headersFor(
                spec.uri.toString(),
                referer,
                spec.httpRequestHeaders,
                ::liveCookie,
            )
            if (extra.isEmpty()) {
                spec
            } else {
                spec.buildUpon()
                    .setHttpRequestHeaders(HashMap(spec.httpRequestHeaders).apply { putAll(extra) })
                    .build()
            }
        }
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .build()
    }
}
