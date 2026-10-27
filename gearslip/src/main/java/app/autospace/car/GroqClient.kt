package app.autospace.car

import android.os.SystemClock
import app.autospace.GearslipLog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Groq API client (r40): the default AI provider, mirroring [GeminiClient]'s shape — one
 * HTTPS request per turn (two when a web search runs, see [generate]), no SDK, no new
 * dependency, [HttpURLConnection] plus hand-built request strings so unit tests can pin
 * them on the JVM, where the framework's `org.json` is a stub. The API key travels only
 * in the `Authorization: Bearer` header, never in a URL, and neither request bodies nor
 * response bodies are ever logged — only the outcome class, never the user's words.
 *
 * Pinned model, verified 2026-10-26 against Groq's own docs (console.groq.com, not
 * blogs — the Gemini 2.5 lesson): the Browser Search page lists `openai/gpt-oss-20b`
 * as browser-search-capable and states browser search is NOT compatible with structured
 * outputs; the API reference lists `reasoning_effort` low/medium/high for the gpt-oss
 * models; the Browser Search page itself recommends `low` for search calls. The model
 * is a named constant with a fallback list ([MODEL_FALLBACKS]) so a future tightening
 * is a one-line change, not a rewrite — and a 404 on the pinned model retries the
 * whole turn once on the first fallback before reporting ModelUnavailable.
 *
 * EXPLICITLY REJECTED, never pinned: `llama-3.1-8b-instant` and every Llama 3.1/3.3
 * model. Groq's deprecation notices moved them from Free and Developer tiers to
 * Enterprise-only on 2026-08-16; pinning one would ship a model that 404s on free
 * keys — the same failure class as Gemini 2.5.
 *
 * Reasoning, and a deliberate deviation from the brief's wording, documented not
 * hidden: Groq's Reasoning page states `reasoning_format` is NOT supported on
 * gpt-oss-20b/120b. Hidden reasoning is therefore achieved the way Groq supports for
 * this model — `include_reasoning: false` on every request, plus [extractContent]
 * reading ONLY `choices[0].message.content` and never the `reasoning` field. The
 * outcome is the brief's requirement (reasoning never reaches spoken text, JSON
 * parsing, logs, or UI), by the mechanism this model actually supports.
 *
 * Structured outputs, same deliberate reading: the answer call does NOT set
 * `response_format`. Forcing `json_object` would break plain-text answers, because the
 * model must be free to return either plain spoken text or one action JSON object —
 * exactly the Gemini path's discipline, enforced by [AiAssistant.parseModelReply],
 * not by the wire format. The search call sets no `response_format` either, because
 * Groq forbids structured outputs alongside browser_search. So the two-call grounded
 * flow is: call 1 searches (tools on, no structured output), call 2 answers with the
 * results injected (tools off, structured output allowed again but not forced).
 *
 * Streaming (r55): the ANSWER call — and only the answer call — can stream
 * (`stream: true`, server-sent events). The search call stays a full-body read:
 * its output is intermediate context, never spoken, and the answer call cannot
 * start until it completes. A streamed answer is segmented by [SentenceSplitter]
 * (the cap logic's own sentence rule) and its mode is decided by its first
 * non-whitespace character: prose streams (display text + sentences), while a
 * reply starting with `{` is an action JSON and never streams anything — it
 * buffers silently exactly as the non-streamed path would, because
 * [AiAssistant.parseModelReply] must see the whole object before acting.
 * Connection reuse (r55): a fully-read 200 response is deliberately NOT
 * disconnected, so the platform's keep-alive pool may reuse the socket for
 * the next call — every prior call disconnected, which forbade reuse and
 * paid a fresh TLS handshake per turn. Pooling itself is
 * implementation-managed; this code now permits it rather than forbidding
 * it. [warmConnection] primes the pool during the listen phase with a
 * zero-token models-list GET (no prompt, no generation), ignored on any
 * failure — a warm-up must never fail a turn.
 */
object GroqClient {

    /** Outcome classes only — no bodies, no keys, no utterances in any of these. */
    sealed class Result {
        /**
         * The reply text, plus its grounding outcome: [grounded] true only when a web
         * search ran and left citation markers, [sourceCount] how many markers.
         * Marker contents are never carried — only the count.
         */
        data class Ok(val text: String, val grounded: Boolean = false, val sourceCount: Int = 0) : Result()
        /** 401 from Groq: the key is wrong or revoked. (Unlike Gemini, 400/403 are not key errors here.) */
        object InvalidKey : Result()
        /** 429 from Groq: quota or rate limit on the user's key. Reset timing comes from the retry-after header, not the body. */
        object Quota : Result()
        /** 404 from Groq: the model is retired or unavailable to this key/project. */
        object ModelUnavailable : Result()
        /** Everything else: timeout, no route, 5xx, malformed reply. */
        object Unavailable : Result()
    }

    /** Validation outcome for the Test key button (a GET, not a generation — zero tokens). */
    enum class KeyCheck { VALID, INVALID, UNKNOWN }

    /** Groq API host for the OpenAI-compatible chat completions API. */
    const val API_HOST = "api.groq.com"
    /** Base URL for the OpenAI-compatible endpoints (chat completions + models list). */
    const val BASE_URL = "https://$API_HOST/openai/v1"
    /** Full chat completions URL. */
    const val CHAT_URL = "$BASE_URL/chat/completions"
    /** Full models list URL, used only by the no-generation-cost Test key check. */
    const val MODELS_URL = "$BASE_URL/models"

    /**
     * Pinned model id (see the header comment for the verification). The fallback list
     * is tried in order when the pinned model 404s, before ModelUnavailable is reported.
     */
    const val MODEL_ID = "openai/gpt-oss-20b"
    val MODEL_FALLBACKS = listOf("openai/gpt-oss-120b")

    /** Reasoning effort for every Groq call: low keeps answers short and searches cheap. */
    const val REASONING_EFFORT = "low"
    /** Output-token bound on the answer call — the same 300 as the Gemini path. */
    const val ANSWER_MAX_TOKENS = AiAssistant.MAX_OUTPUT_TOKENS
    /**
     * Output-token bound on the search call. Larger than the answer bound because the
     * search call's content is intermediate context (snippets plus a draft), never
     * spoken — and it also bounds how much text the answer call ever injects.
     */
    const val SEARCH_MAX_TOKENS = 600

    /**
     * Pure HTTP-status → outcome mapping, so unit tests can pin it without a network
     * call. Called only for non-200 responses: 401 = bad key, 404 = model
     * retired/unavailable, 429 = quota, anything else = unavailable.
     */
    fun resultForHttpCode(code: Int): Result = when (code) {
        401 -> Result.InvalidKey
        404 -> Result.ModelUnavailable
        429 -> Result.Quota
        else -> Result.Unavailable
    }

    /**
     * The cheapest possible key validation: GET the models list. A 200 means the key
     * authenticates; 401/403 means the key is bad; anything else is UNKNOWN (network
     * trouble, not a verdict on the key). No generation, no tokens.
     *
     * Limitation, same as Gemini's (r38): a 200 here does NOT prove chat completions
     * will succeed for a given model — the list may name a model the key's tier cannot
     * run. This check validates the key only; it is deliberately not a generation
     * probe, so it stays zero-token.
     */
    fun validateKey(key: String): KeyCheck {
        if (key.isBlank()) return KeyCheck.INVALID
        return try {
            val conn = (URL(MODELS_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $key")
                connectTimeout = AiAssistant.CONNECT_TIMEOUT_MS
                readTimeout = AiAssistant.REQUEST_TIMEOUT_MS
            }
            val code = conn.responseCode
            conn.disconnect()
            GearslipLog.i("assistant: ai groq key check http=$code")
            when (code) {
                200 -> KeyCheck.VALID
                401, 403 -> KeyCheck.INVALID
                else -> KeyCheck.UNKNOWN
            }
        } catch (e: Exception) {
            GearslipLog.i("assistant: ai groq key check failed class=${e.javaClass.simpleName}")
            KeyCheck.UNKNOWN
        }
    }

    private fun jsonString(value: String): String {
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

    private fun messagesJson(system: String, user: String): String =
        "\"messages\":[" +
            "{\"role\":\"system\",\"content\":${jsonString(system)}}," +
            "{\"role\":\"user\",\"content\":${jsonString(user)}}]"

    private fun commonJson(model: String, maxTokens: Int): String =
        "\"model\":${jsonString(model)}," +
            "\"reasoning_effort\":${jsonString(REASONING_EFFORT)}," +
            "\"include_reasoning\":false," +
            "\"max_completion_tokens\":$maxTokens"

    /**
     * The search call's body (call 1 of the grounded flow): the fixed system prompt
     * plus the transcribed utterance, with the browser_search tool attached and no
     * `response_format` — Groq forbids structured outputs alongside browser_search.
     * `tool_choice` stays at the default (auto), so the model searches only when the
     * question needs current information, exactly as the Gemini path's attached tool
     * behaves. Whether a search ran is read back from citation markers in the reply
     * (see [countCitations]), not assumed.
     */
    fun buildSearchRequestBody(utterance: String, model: String = MODEL_ID): String {
        return "{" +
            messagesJson(AiAssistant.SYSTEM_PROMPT, utterance) + "," +
            commonJson(model, SEARCH_MAX_TOKENS) + "," +
            "\"tools\":[{\"type\":\"browser_search\"}]" +
            "}"
    }

    /**
     * The answer call's body (call 2 of the grounded flow, or the only call when no
     * search ran): the fixed system prompt plus the utterance, with the search
     * results injected ahead of the question when [searchResults] is present. No
     * tools on this call, and no forced `response_format` — the model must stay free
     * to answer in plain text or as one action JSON object (see the header comment).
     */
    fun buildAnswerRequestBody(utterance: String, searchResults: String? = null, model: String = MODEL_ID, stream: Boolean = false): String {
        val user = if (searchResults != null) {
            "Web search results:\n$searchResults\n\nUser question: $utterance"
        } else {
            utterance
        }
        return "{" +
            messagesJson(AiAssistant.SYSTEM_PROMPT, user) + "," +
            commonJson(model, ANSWER_MAX_TOKENS) +
            (if (stream) ",\"stream\":true" else "") +
            "}"
    }

    /**
     * Extracts `choices[0].message.content` only. The `reasoning` field, when present,
     * is never read — reasoning cannot leak into spoken text or action parsing even
     * if a future model returns it despite `include_reasoning: false`. Returns null
     * when there is no usable content.
     */
    fun extractContent(responseJson: String): String? {
        val root = try {
            JsonMini.parse(responseJson) as? JsonMini.Value.Obj
        } catch (e: Exception) {
            null
        } ?: return null
        val choices = (root.objField("choices") as? JsonMini.Value.Arr)?.list ?: return null
        if (choices.isEmpty()) return null
        val first = choices[0] as? JsonMini.Value.Obj ?: return null
        val message = first.objField("message") as? JsonMini.Value.Obj ?: return null
        return message.objField("content")?.asString()?.takeIf { it.isNotBlank() }
    }

    /**
     * Counts citation markers in a search-call reply (the `〖n†…〗` references Groq's
     * browser search leaves in its content). Markers are counted and then dropped —
     * their targets are never carried, logged, or shown, exactly as Gemini's chunk
     * titles and URIs are never carried.
     */
    fun countCitations(content: String): Int = content.count { it == '〖' }

    /**
     * Extracts `choices[0].delta.content` from one streamed chunk object — the
     * delta shape, not the full-body message shape [extractContent] reads.
     * Null when the chunk carries no content (a role-only first chunk, a
     * usage chunk, or anything malformed): the caller skips it, never fails
     * on it. Pure, so unit tests can pin it without a network call.
     */
    fun extractDeltaContent(chunkJson: String): String? {
        val root = try {
            JsonMini.parse(chunkJson) as? JsonMini.Value.Obj
        } catch (e: Exception) {
            null
        } ?: return null
        val choices = (root.objField("choices") as? JsonMini.Value.Arr)?.list ?: return null
        if (choices.isEmpty()) return null
        val first = choices[0] as? JsonMini.Value.Obj ?: return null
        val delta = first.objField("delta") as? JsonMini.Value.Obj ?: return null
        return delta.objField("content")?.asString()
    }

    /** One server-sent-event line, classified so the stream loop stays trivial. */
    sealed class SseEvent {
        /** A data line carrying chunk content (null content = a content-less chunk to skip). */
        data class Data(val content: String?) : SseEvent()
        /** The `data: [DONE]` terminator. */
        object Done : SseEvent()
        /** Blank lines, comments, and anything that is not a data line. */
        object Ignored : SseEvent()
    }

    /** Pure SSE line classifier, so unit tests can pin the wire shape. */
    fun parseSseLine(line: String): SseEvent {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return SseEvent.Ignored
        if (trimmed == "data: [DONE]") return SseEvent.Done
        if (!trimmed.startsWith("data:")) return SseEvent.Ignored
        return SseEvent.Data(extractDeltaContent(trimmed.removePrefix("data:").trim()))
    }

    private data class HttpResult(val code: Int, val body: String?)

    private fun post(key: String, body: String): HttpResult {
        val conn = (URL(CHAT_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = AiAssistant.CONNECT_TIMEOUT_MS
            readTimeout = AiAssistant.GROUNDED_REQUEST_TIMEOUT_MS
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val text = if (code == 200) {
                conn.inputStream.use { stream ->
                    BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).readText()
                }
            } else {
                runCatching { conn.errorStream?.close() }
                null
            }
            // r55 keep-alive: a fully-read 200 is NOT disconnected, so the
            // platform pool may reuse the socket for the next call. Only
            // error and exception paths disconnect — a partially-read
            // error body cannot be pooled anyway.
            if (code != 200) conn.disconnect()
            HttpResult(code, text)
        } catch (e: Exception) {
            runCatching { conn.disconnect() }
            GearslipLog.i("assistant: ai groq request failed class=${e.javaClass.simpleName}")
            HttpResult(-1, null)
        }
    }

    /**
     * Listen-phase warm-up (r55): a zero-token models-list GET that
     * opens the provider connection — DNS, TLS, and a pooled socket —
     * while the user is still talking, so the turn's first real call
     * reuses it instead of paying the handshake. No prompt is ever
     * sent here, and no generation runs. Every failure is swallowed
     * silently: a warm-up must never fail a turn, and a failed warm-up
     * simply means the turn pays the handshake it always paid. The
     * body is fully read and the connection is NOT disconnected, for
     * the same keep-alive reason as [post]'s 200 path.
     */
    fun warmConnection(key: String) {
        if (key.isBlank()) return
        try {
            val conn = (URL(MODELS_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $key")
                connectTimeout = AiAssistant.CONNECT_TIMEOUT_MS
                readTimeout = AiAssistant.REQUEST_TIMEOUT_MS
            }
            val code = conn.responseCode
            if (code == 200) {
                conn.inputStream.use { stream ->
                    BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).readText()
                }
            } else {
                runCatching { conn.errorStream?.close() }
                conn.disconnect()
            }
        } catch (e: Exception) {
            GearslipLog.i("assistant: ai groq warm failed class=${e.javaClass.simpleName}")
        }
    }

    private data class StreamResult(val code: Int, val text: String?)

    /**
     * The answer call as a server-sent-events stream (r55). Each
     * `data:` line's delta content is appended to the full text and
     * handed to [onDelta] in arrival order; `data: [DONE]` ends the
     * stream, and an EOF without it is treated as a complete stream
     * (tolerant: the accumulated text is what the caller checks).
     * A non-200 code returns before any delta, so a 404 mid-turn is
     * still a clean ModelUnavailable retry, never a partial answer.
     * A mid-stream exception returns code -1: the caller aborts the
     * turn rather than presenting a truncated answer as complete.
     * The read timeout is per-read (a stalled stream times out like a
     * stalled full read would), and a completed stream leaves its
     * socket pooled like [post]'s 200 path.
     */
    private fun postStream(key: String, body: String, onDelta: (String) -> Unit): StreamResult {
        val conn = (URL(CHAT_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            connectTimeout = AiAssistant.CONNECT_TIMEOUT_MS
            readTimeout = AiAssistant.GROUNDED_REQUEST_TIMEOUT_MS
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) {
                runCatching { conn.errorStream?.close() }
                conn.disconnect()
                return StreamResult(code, null)
            }
            val full = StringBuilder()
            BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    when (val event = parseSseLine(line)) {
                        is SseEvent.Done -> break
                        is SseEvent.Data -> {
                            val content = event.content
                            if (content != null) {
                                full.append(content)
                                onDelta(content)
                            }
                        }
                        is SseEvent.Ignored -> Unit
                    }
                }
            }
            StreamResult(code, full.toString())
        } catch (e: Exception) {
            runCatching { conn.disconnect() }
            GearslipLog.i("assistant: ai groq stream failed class=${e.javaClass.simpleName}")
            StreamResult(-1, null)
        }
    }

    /**
     * One turn on one model: a search call, then — only when the search left citation
     * markers — an answer call with the search content injected. When the search call
     * fails (other than 404) the turn degrades to a single ungrounded answer call,
     * mirroring how the Gemini path still answers when its server-side search finds
     * nothing. When the search succeeds without markers, its content IS the answer
     * and no second call runs. A 404 anywhere in the turn is ModelUnavailable, which
     * [generate] retries on the next fallback model.
     */
    private fun generateWithModel(key: String, utterance: String, model: String): Result {
        val search = post(key, buildSearchRequestBody(utterance, model))
        if (search.code != 200) {
            GearslipLog.i("assistant: ai groq search http=${search.code}")
            val mapped = resultForHttpCode(search.code)
            if (mapped is Result.ModelUnavailable) return mapped
            // Degrade: a failed search must not lose the turn — answer ungrounded.
            return answerCall(key, utterance, null, model)
        }
        val searchContent = search.body?.let { extractContent(it) }
        if (searchContent.isNullOrBlank()) {
            return answerCall(key, utterance, null, model)
        }
        val citations = countCitations(searchContent)
        if (citations == 0) {
            GearslipLog.i("assistant: ai groq generate ok grounded=false sources=0")
            return Result.Ok(searchContent, grounded = false, sourceCount = 0)
        }
        return answerCall(key, utterance, searchContent, model, citations)
    }

    private fun answerCall(
        key: String,
        utterance: String,
        searchResults: String?,
        model: String,
        citations: Int = 0,
    ): Result {
        val answer = post(key, buildAnswerRequestBody(utterance, searchResults, model))
        if (answer.code != 200) {
            GearslipLog.i("assistant: ai groq answer http=${answer.code}")
            return resultForHttpCode(answer.code)
        }
        val content = answer.body?.let { extractContent(it) }
        if (content.isNullOrBlank()) {
            GearslipLog.i("assistant: ai groq generate empty reply")
            return Result.Unavailable
        }
        val grounded = searchResults != null
        GearslipLog.i("assistant: ai groq generate ok grounded=$grounded sources=$citations")
        return Result.Ok(content, grounded, citations)
    }

    /**
     * The answer call, streamed (r55): same outcome mapping as
     * [answerCall], but the reply arrives as deltas. Mode is decided
     * by the first non-whitespace character of the accumulated text
     * and never revisited: prose streams ([onText] with the growing
     * full text after every delta, [onSentence] for each complete
     * sentence from [SentenceSplitter], trailing partial on stream
     * end), while a `{`-leading reply — an action JSON — suppresses
     * every callback and buffers silently, because
     * [AiAssistant.parseModelReply] must see the whole object before
     * acting. [listenEndMs] is an elapsed-realtime baseline (0 when
     * unknown) for the first-token latency marker; the marker carries
     * a millisecond value only, never any reply text.
     */
    private fun answerCallStreaming(
        key: String,
        utterance: String,
        searchResults: String?,
        model: String,
        citations: Int = 0,
        listenEndMs: Long = 0L,
        onText: (String) -> Unit,
        onSentence: (String) -> Unit,
    ): Result {
        val full = StringBuilder()
        val splitter = SentenceSplitter()
        var prose: Boolean? = null
        var firstTokenLogged = false
        val stream = postStream(key, buildAnswerRequestBody(utterance, searchResults, model, stream = true)) { delta ->
            if (!firstTokenLogged && delta.isNotEmpty()) {
                firstTokenLogged = true
                if (listenEndMs > 0L) {
                    GearslipLog.i(
                        "assistant: latency first-token " +
                            "ms=${SystemClock.elapsedRealtime() - listenEndMs}",
                    )
                } else {
                    GearslipLog.i("assistant: latency first-token")
                }
            }
            full.append(delta)
            if (prose == null) {
                val first = full.toString().trimStart().firstOrNull()
                if (first != null) prose = (first != '{')
            }
            if (prose == true) {
                onText(full.toString())
                for (sentence in splitter.feed(delta)) onSentence(sentence)
            }
        }
        if (stream.code != 200) {
            GearslipLog.i("assistant: ai groq answer http=${stream.code}")
            return resultForHttpCode(stream.code)
        }
        val content = stream.text
        if (content.isNullOrBlank()) {
            GearslipLog.i("assistant: ai groq generate empty reply")
            return Result.Unavailable
        }
        if (prose == true) {
            splitter.finish()?.let { onSentence(it) }
        }
        val grounded = searchResults != null
        GearslipLog.i("assistant: ai groq generate ok grounded=$grounded sources=$citations")
        return Result.Ok(content, grounded, citations)
    }

    /**
     * One streaming turn on one model: the search call is identical to
     * [generateWithModel] (full-body, tools on), and only the answer
     * call streams. A search that succeeds without citation markers
     * still returns its content directly with no answer call — there
     * is nothing left to stream — and a failed search still degrades
     * to a single ungrounded streaming answer call.
     */
    private fun generateStreamingWithModel(
        key: String,
        utterance: String,
        model: String,
        listenEndMs: Long,
        onText: (String) -> Unit,
        onSentence: (String) -> Unit,
    ): Result {
        val search = post(key, buildSearchRequestBody(utterance, model))
        if (search.code != 200) {
            GearslipLog.i("assistant: ai groq search http=${search.code}")
            val mapped = resultForHttpCode(search.code)
            if (mapped is Result.ModelUnavailable) return mapped
            // Degrade: a failed search must not lose the turn — answer ungrounded.
            return answerCallStreaming(key, utterance, null, model, 0, listenEndMs, onText, onSentence)
        }
        val searchContent = search.body?.let { extractContent(it) }
        if (searchContent.isNullOrBlank()) {
            return answerCallStreaming(key, utterance, null, model, 0, listenEndMs, onText, onSentence)
        }
        val citations = countCitations(searchContent)
        if (citations == 0) {
            GearslipLog.i("assistant: ai groq generate ok grounded=false sources=0")
            return Result.Ok(searchContent, grounded = false, sourceCount = 0)
        }
        return answerCallStreaming(key, utterance, searchContent, model, citations, listenEndMs, onText, onSentence)
    }

    /**
     * One streaming AI turn: tries the pinned model, then each fallback
     * in order, stopping at the first non-ModelUnavailable outcome —
     * the same retry rule as [generate]. A 404 on the answer call
     * arrives before any delta, so the retry is clean; a mid-stream
     * failure is Unavailable (no retry), and the caller aborts any
     * partial speech rather than presenting a truncation as complete.
     */
    fun generateStreaming(
        key: String,
        utterance: String,
        listenEndMs: Long = 0L,
        onText: (String) -> Unit,
        onSentence: (String) -> Unit,
    ): Result {
        if (key.isBlank()) return Result.InvalidKey
        val models = listOf(MODEL_ID) + MODEL_FALLBACKS
        for ((index, model) in models.withIndex()) {
            val result = generateStreamingWithModel(key, utterance, model, listenEndMs, onText, onSentence)
            if (result !is Result.ModelUnavailable) return result
            if (index < models.lastIndex) {
                GearslipLog.i("assistant: ai groq model fallback")
            }
        }
        return Result.ModelUnavailable
    }

    /**
     * One AI turn: tries the pinned model, then each fallback in order, stopping at
     * the first non-ModelUnavailable outcome. A single attempt per model (no retries
     * on any other failure — a retry doubles the cost of a failure and stalls the car).
     */
    fun generate(key: String, utterance: String): Result {
        if (key.isBlank()) return Result.InvalidKey
        val models = listOf(MODEL_ID) + MODEL_FALLBACKS
        for ((index, model) in models.withIndex()) {
            val result = generateWithModel(key, utterance, model)
            if (result !is Result.ModelUnavailable) return result
            if (index < models.lastIndex) {
                GearslipLog.i("assistant: ai groq model fallback")
            }
        }
        return Result.ModelUnavailable
    }
}
