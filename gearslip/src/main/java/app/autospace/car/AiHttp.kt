package app.autospace.car

import app.autospace.GearslipLog
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shared key-validation HTTP helper (r174 audit §2 CLEAN-N02): the GET
 * both AI clients used to spell out separately, now in one place.
 *
 * The helper only performs the request and returns the HTTP status code
 * (null when the request itself fails, with a class-only log line — the
 * same house pattern the clients already used). Per-provider
 * status-code mapping stays at the call sites ([GeminiClient] maps
 * 400/401/403 to invalid, [GroqClient] maps 401/403), because the two
 * providers do not agree on what a bad key looks like, and that
 * difference is a property of the provider, not of the request.
 *
 * Deliberately NOT used by [GroqClient.warmConnection]: a warm-up is
 * not a validation — it reads the 200 body and keeps the socket pooled
 * for the turn's first real call, so it needs the connection itself,
 * not just its status code. Routing it through here would disconnect
 * the socket and defeat the warm-up.
 */
internal object AiHttp {

    /**
     * GET [url] with one auth header. Returns the HTTP status code, or
     * null when the request fails before a code exists. The connection
     * is always disconnected: this is a status probe, never a pooled
     * socket. [logTag] is the caller's existing log prefix, so log
     * lines do not change (for example "assistant: ai key check").
     */
    fun getStatusCode(
        url: String,
        headerName: String,
        headerValue: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        logTag: String,
    ): Int? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty(headerName, headerValue)
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
            }
            val code = conn.responseCode
            conn.disconnect()
            code
        } catch (e: Exception) {
            GearslipLog.i("$logTag failed class=${e.javaClass.simpleName}")
            null
        }
    }
}
