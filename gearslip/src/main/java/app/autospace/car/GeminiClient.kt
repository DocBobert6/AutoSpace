package app.autospace.car

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.autospace.GearslipLog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Gemini API client (r25): one HTTPS request per AI turn, no SDK, no new dependency —
 * [HttpURLConnection] plus a hand-built request string (r27: the body is built as a
 * string so unit tests can pin it on the JVM, where the framework's `org.json` is a
 * stub). The API key travels only in the `x-goog-api-key`
 * request header, never in a URL (so it cannot land in a log line, a proxy log, or a
 * referrer), and neither request bodies nor response bodies are ever logged — only the
 * outcome class (ok / invalid key / quota / unavailable), never the user's words.
 *
 * Efficiency rules from §7 of the spec, enforced here: a single attempt (no retries — a
 * retry doubles the cost of a failure and stalls the car), a short fixed system prompt, no
 * conversation history, and a bounded output (`MAX_OUTPUT_TOKENS`), so each turn costs a
 * small, predictable number of tokens.
 */
object GeminiClient {

    /** Outcome classes only — no bodies, no keys, no utterances in any of these. */
    sealed class Result {
        /**
         * The reply text, plus its grounding outcome (r27): [grounded] true only when
         * web chunks came back, [sourceCount] how many. Chunk contents are never carried.
         */
        data class Ok(val text: String, val grounded: Boolean = false, val sourceCount: Int = 0) : Result()
        /** 400/401/403 from Google: the key is wrong, revoked, or lacks API access. */
        object InvalidKey : Result()
        /** 429 from Google: quota or rate limit on the user's key. */
        object Quota : Result()
        /** 404 from Google: the model is retired or unavailable to this key/project (r38). */
        object ModelUnavailable : Result()
        /** Everything else: timeout, no route, 5xx, malformed reply. */
        object Unavailable : Result()
    }

    /**
     * Pure HTTP-status → outcome mapping (r38), so unit tests can pin it without a
     * network call. Called only for non-200 responses: 400/401/403 = bad key,
     * 404 = model retired/unavailable to this key or project (its own outcome, never
     * folded into the generic unavailable message), 429 = quota, anything else =
     * unavailable.
     */
    fun resultForHttpCode(code: Int): Result = when (code) {
        400, 401, 403 -> Result.InvalidKey
        404 -> Result.ModelUnavailable
        429 -> Result.Quota
        else -> Result.Unavailable
    }

    /** Validation outcome for the Test key button (a GET, not a generation — zero tokens). */
    enum class KeyCheck { VALID, INVALID, UNKNOWN }

    /** True when the phone currently has a validated internet route (not just a link). */
    fun networkAvailable(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = runCatching { manager.getNetworkCapabilities(manager.activeNetwork) }.getOrNull() ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * The cheapest possible key validation: GET the pinned model resource. A 200 means the
     * key authenticates and the model exists; 400/401/403 means the key is bad; anything
     * else is UNKNOWN (network trouble, not a verdict on the key). No generation, no tokens.
     *
     * Limitation (r38, learned the hard way): a 200 here does NOT prove generateContent
     * will succeed — Google may keep a model in the catalogue while refusing generation
     * for keys/projects that never used it (gemini-2.5-flash: GET 200, POST 404). This
     * check validates the key only; it is deliberately not a generation probe, so it
     * stays zero-token.
     */
    fun validateKey(key: String): KeyCheck {
        if (key.isBlank()) return KeyCheck.INVALID
        return try {
            val conn = (URL(AiAssistant.MODEL_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("x-goog-api-key", key)
                connectTimeout = AiAssistant.CONNECT_TIMEOUT_MS
                readTimeout = AiAssistant.REQUEST_TIMEOUT_MS
            }
            val code = conn.responseCode
            conn.disconnect()
            GearslipLog.i("assistant: ai key check http=$code")
            when (code) {
                200 -> KeyCheck.VALID
                400, 401, 403 -> KeyCheck.INVALID
                else -> KeyCheck.UNKNOWN
            }
        } catch (e: Exception) {
            GearslipLog.i("assistant: ai key check failed class=${e.javaClass.simpleName}")
            KeyCheck.UNKNOWN
        }
    }

    /**
     * The generateContent request body, built as a string (not org.json) so the unit
     * tests can pin it on the JVM, where org.json is a stub. The device path uses
     * exactly this string.
     *
     * r27 grounding, verified against Google's own docs at build time (see BUILD_LOG
     * r27): the classic generateContent endpoint takes a `tools` array whose grounding
     * entry is the `googleSearch` object — the canonical REST JSON name for the Google
     * Search tool. (The SDKs spell it `google_search` in code and serialize it to
     * `googleSearch` on the wire; Google's protojson also accepts the snake_case proto
     * name, but the shipped request uses the canonical REST name.) An empty object
     * means "search only when the question needs current information" — the model
     * decides, the app never pre-decides, and no location field is ever sent: there is
     * no userLocation, latLng, or retrieval config in this body, because option 1 puts
     * the place in the user's own words.
     */
    fun buildRequestBody(utterance: String): String {
        fun jsonString(value: String): String {
            val out = StringBuilder()
            out.append('"')
            for (ch in value) {
                when (ch) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    else -> if (ch.code < 0x20) out.append("\\u%04x".format(ch.code)) else out.append(ch)
                }
            }
            out.append('"')
            return out.toString()
        }
        return "{" +
            "\"systemInstruction\":{\"parts\":[{\"text\":${jsonString(AiAssistant.SYSTEM_PROMPT)}}]}," +
            "\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":${jsonString(utterance)}}]}]," +
            "\"generationConfig\":{\"maxOutputTokens\":${AiAssistant.MAX_OUTPUT_TOKENS}}," +
            "\"tools\":[{\"googleSearch\":{}}]" +
            "}"
    }

    /** Grounding outcome reduced to what the UI may show: grounded yes/no + source count. */
    data class Grounding(val grounded: Boolean, val sourceCount: Int)

    /**
     * Reads candidates[0].groundingMetadata.groundingChunks and counts its web chunks.
     * Chunk contents (titles, URIs) are counted and then dropped — they never reach a
     * log, a trace, or the screen. A reply with search queries but no chunks counts
     * as ungrounded: grounding is claimed only when sources exist.
     */
    fun extractGrounding(responseJson: String): Grounding {
        val root = try {
            JsonMini.parse(responseJson) as? JsonMini.Value.Obj
        } catch (e: Exception) {
            null
        } ?: return Grounding(false, 0)
        val candidates = (root.objField("candidates") as? JsonMini.Value.Arr)?.list ?: return Grounding(false, 0)
        if (candidates.isEmpty()) return Grounding(false, 0)
        val first = candidates[0] as? JsonMini.Value.Obj ?: return Grounding(false, 0)
        val metadata = first.objField("groundingMetadata") as? JsonMini.Value.Obj ?: return Grounding(false, 0)
        val chunks = (metadata.objField("groundingChunks") as? JsonMini.Value.Arr)?.list ?: return Grounding(false, 0)
        val webChunks = chunks.count { (it as? JsonMini.Value.Obj)?.objField("web") != null }
        return Grounding(webChunks > 0, webChunks)
    }

    /**
     * One generateContent call: the fixed system prompt plus the transcribed utterance only.
     * No contacts, numbers, messages, history, or location are sent — the request body is
     * exactly those two texts plus the grounding tool. A single attempt: on any failure
     * the caller speaks the plain unavailable message, never a guessed answer.
     *
     * r27: the read timeout is the grounded timeout, because the tool is attached on
     * every fallback request — there is no separate ungrounded fallback call. The key
     * validation GET keeps the plain 15 s timeout.
     */
    fun generate(key: String, utterance: String): Result {
        if (key.isBlank()) return Result.InvalidKey
        return try {
            val conn = (URL(AiAssistant.GENERATE_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("x-goog-api-key", key)
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = AiAssistant.CONNECT_TIMEOUT_MS
                readTimeout = AiAssistant.GROUNDED_REQUEST_TIMEOUT_MS
            }
            conn.outputStream.use { it.write(buildRequestBody(utterance).toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) {
                runCatching { conn.errorStream?.close() }
                conn.disconnect()
                GearslipLog.i("assistant: ai generate http=$code")
                return resultForHttpCode(code)
            }
            val text = conn.inputStream.use { stream ->
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).readText()
            }
            conn.disconnect()
            val reply = extractText(text)
            if (reply.isNullOrBlank()) {
                GearslipLog.i("assistant: ai generate empty reply")
                Result.Unavailable
            } else {
                val grounding = extractGrounding(text)
                // Count only — chunk titles and URIs are never logged.
                GearslipLog.i("assistant: ai generate ok grounded=${grounding.grounded} sources=${grounding.sourceCount}")
                Result.Ok(reply, grounding.grounded, grounding.sourceCount)
            }
        } catch (e: Exception) {
            GearslipLog.i("assistant: ai generate failed class=${e.javaClass.simpleName}")
            Result.Unavailable
        }
    }

    /**
     * Extracts the concatenated text parts of the first candidate. Returns null when the
     * response has no usable text (a blocked reply has no candidates at all — that is an
     * unavailable answer, not a guessed one).
     */
    fun extractText(responseJson: String): String? {
        // JsonMini, not org.json: the framework JSON class is a stub on the JVM
        // unit-test classpath (see AiAssistant.parseModelReply), so the tested parse
        // path must not touch it. Device behavior is identical for Gemini's shape.
        val root = JsonMini.parse(responseJson) as? JsonMini.Value.Obj ?: return null
        val candidates = (root.objField("candidates") as? JsonMini.Value.Arr)?.list ?: return null
        if (candidates.isEmpty()) return null
        val first = candidates[0] as? JsonMini.Value.Obj ?: return null
        val parts = (first.objField("content")?.objField("parts") as? JsonMini.Value.Arr)?.list ?: return null
        val texts = parts.mapNotNull { (it as? JsonMini.Value.Obj)?.objField("text")?.asString() }
            .filter { it.isNotEmpty() }
        return texts.joinToString(" ").takeIf { it.isNotBlank() }
    }
}
