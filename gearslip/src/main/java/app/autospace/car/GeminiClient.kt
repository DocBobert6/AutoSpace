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
import org.json.JSONObject

/**
 * Gemini API client (r25): one HTTPS request per AI turn, no SDK, no new dependency —
 * [HttpURLConnection] plus the framework's `org.json`, which is the same shape the rest of
 * the app uses for its hand-rolled HTTP. The API key travels only in the `x-goog-api-key`
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
        data class Ok(val text: String) : Result()
        /** 400/401/403 from Google: the key is wrong, revoked, or lacks API access. */
        object InvalidKey : Result()
        /** 429 from Google: quota or rate limit on the user's key. */
        object Quota : Result()
        /** Everything else: timeout, no route, 5xx, malformed reply. */
        object Unavailable : Result()
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
     * One generateContent call: the fixed system prompt plus the transcribed utterance only.
     * No contacts, numbers, messages, history, or location are sent — the request body is
     * exactly those two texts. A single attempt: on any failure the caller speaks the plain
     * unavailable message, never a guessed answer.
     */
    fun generate(key: String, utterance: String): Result {
        if (key.isBlank()) return Result.InvalidKey
        return try {
            val body = JSONObject().apply {
                put("systemInstruction", JSONObject().apply {
                    put("parts", org.json.JSONArray().apply {
                        put(JSONObject().apply { put("text", AiAssistant.SYSTEM_PROMPT) })
                    })
                })
                put("contents", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", org.json.JSONArray().apply {
                            put(JSONObject().apply { put("text", utterance) })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("maxOutputTokens", AiAssistant.MAX_OUTPUT_TOKENS)
                })
            }
            val conn = (URL(AiAssistant.GENERATE_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("x-goog-api-key", key)
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = AiAssistant.CONNECT_TIMEOUT_MS
                readTimeout = AiAssistant.REQUEST_TIMEOUT_MS
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) {
                runCatching { conn.errorStream?.close() }
                conn.disconnect()
                GearslipLog.i("assistant: ai generate http=$code")
                return when (code) {
                    400, 401, 403 -> Result.InvalidKey
                    429 -> Result.Quota
                    else -> Result.Unavailable
                }
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
                GearslipLog.i("assistant: ai generate ok")
                Result.Ok(reply)
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
