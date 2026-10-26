package app.autospace.car

/**
 * AI assistant (r25): the pure, Android-free half of the Gemini bring-your-own-key layer.
 *
 * Everything here is testable on the JVM with no device: the system prompt, the model reply
 * parser, the proposal validator, the spoken-answer cap, the local confirmation matcher, and
 * the log redaction guard. Anything that touches the network, the Keystore, contacts, or the
 * car UI lives elsewhere ([GeminiClient], [AiKeyStore], [AssistantCommands]).
 *
 * Design rule that shapes this file: the AI PROPOSES, the user DISPOSES. A model reply is
 * never executed. It is parsed into either an answer (spoken text) or a single [AiAction]
 * proposal, and a proposal only ever runs after an explicit confirmation (§5 of the spec)
 * through [AssistantCommands]' pending-action state. Anything the model invents — a contact,
 * an address, a number that is not in the user's words or existing data — is refused here,
 * not confirmed: confirmation verifies understanding, it does not launder guesses.
 */
object AiAssistant {

    // --- Pinned model + endpoint (verified 2026-10-26 against Google's docs) ---
    /** Gemini API host for the Generative Language API (AI Studio keys). */
    const val API_HOST = "generativelanguage.googleapis.com"
    /** API version segment of the REST path. */
    const val API_VERSION = "v1beta"
    /**
     * Pinned Flash-class model id. Verified against Google's model docs at build time
     * (Vertex AI model card lists `gemini-2.5-flash`; the REST reference shows
     * `v1beta/models/gemini-2.5-flash:generateContent` with the `x-goog-api-key` header).
     * This is the stable alias, not a dated `-001` snapshot, so it tracks the current
     * 2.5 Flash build. If Google ever returns 404 for it, Test key surfaces the plain
     * API error and AI answers degrade to the unavailable message — never a guessed answer.
     */
    const val MODEL_ID = "gemini-2.5-flash"
    /** Full generateContent URL, without any key (the key travels only in the header). */
    const val GENERATE_URL = "https://$API_HOST/$API_VERSION/models/$MODEL_ID:generateContent"
    /** Full model resource URL, used only by the no-generation-cost Test key check. */
    const val MODEL_URL = "https://$API_HOST/$API_VERSION/models/$MODEL_ID"

    // --- Timeouts + caps (all reported in BUILD_LOG r25) ---
    /** Connection establishment timeout for a Gemini request. */
    const val CONNECT_TIMEOUT_MS = 5_000
    /** Read timeout for a Gemini request: one attempt, then the unavailable message. */
    const val REQUEST_TIMEOUT_MS = 15_000
    /**
     * Read timeout for a grounded-capable fallback request (r27): grounding adds a search
     * step, so these calls get longer than the plain 15 s. The tool is attached on every
     * fallback request (spec §1), so every fallback call uses this timeout; the no-cost
     * validation GET keeps [REQUEST_TIMEOUT_MS]. Single attempt still, no retries.
     */
    const val GROUNDED_REQUEST_TIMEOUT_MS = 25_000
    /** An AI confirmation unanswered for this long is a DECLINE (see [decideConfirmation]). */
    const val CONFIRM_TIMEOUT_MS = 10_000L
    /** Spoken answers are capped at this many words, cut at a sentence boundary. */
    const val ANSWER_WORD_CAP = 60
    /** Output-token bound on the request itself, so a long model reply is priced small. */
    const val MAX_OUTPUT_TOKENS = 300

    /**
     * The exact system prompt sent with every AI request (verbatim in BUILD_LOG r25).
     * Short on purpose: every token here is paid for on every AI turn, and the car wants
     * brief spoken answers, not essays. The JSON action schema is part of the prompt
     * because that is the only channel by which the model may propose an action at all.
     */
    const val SYSTEM_PROMPT =
        "You are the voice assistant in a car. Answer briefly and plainly, in no more " +
            "than two short sentences. Use no markdown, no lists, and no formatting. " +
            "Never invent addresses, phone numbers, or facts about the user's contacts. " +
            "If you are unsure, say so. Never propose an emergency call or hanging up a call. " +
            "If the user asks you to do something the car assistant can do, reply with ONLY " +
            "one JSON object, no other text: {\"action\": \"navigate\", \"destination\": \"...\"} or " +
            "{\"action\": \"open_app\", \"app\": \"...\"} or {\"action\": \"browser_search\", \"term\": \"...\"} or " +
            "{\"action\": \"call\", \"contact\": \"...\"} or {\"action\": \"reply_message\", \"contact\": \"...\", " +
            "\"text\": \"...\"} or {\"action\": \"media\", \"command\": \"play\"|\"pause\"|\"next\"|\"previous\"} or " +
            "{\"action\": \"volume\", \"mode\": \"up\"|\"down\"|\"mute\"|\"unmute\"} or " +
            "{\"action\": \"brightness\", \"percent\": 0-100} or {\"action\": \"brightness\", \"direction\": \"up\"|\"down\"} or " +
            "{\"action\": \"toggle\", \"setting\": \"speak_directions\"|\"phone_mic\"|\"screen_sharing\", \"on\": true|false} or " +
            "{\"action\": \"timer\", \"minutes\": 1-240} or {\"action\": \"readout\", \"what\": \"battery\"|\"eta\"|\"messages\"}. " +
            "Otherwise reply with ONLY the spoken answer text, nothing else. " +
            "For current facts (weather, news, scores, prices, times), use the search results " +
            "and state the information as of the search time. Never present a searched fact " +
            "as timeless. If a question needs a place and none is given, ask for the place " +
            "instead of guessing one."

    /** Privacy notice substance (§6 of the spec), shown before first enable and linked from settings. */
    const val PRIVACY_NOTICE =
        "When AI answers are on, what you say to the assistant is sent to Google's Gemini API " +
            "to generate the answer. Your API key stays on this phone and is never sent anywhere " +
            "except to Google's API. AutoSpace does not send your contacts, messages, location, " +
            "or history to the AI — only the words you just said (and, for an action you confirm, " +
            "the exact thing you approved). Turn AI answers off anytime in Settings > AI assistant, " +
            "and remove your key there too. Without a network connection, AI answers are unavailable " +
            "and the local assistant keeps working. When a question needs current information, " +
            "the assistant may run a Google Search through the same Gemini connection. No new " +
            "company receives your words, and location is never sent. " +
            "Google's own terms govern Google's side."

    // --- Session badge: "From AI:" is spoken on the FIRST AI answer per session only. ---
    private var firstAnswerSpoken = false

    /** True when the next AI answer should carry the spoken "From AI:" prefix. */
    fun needsAiPrefix(): Boolean = !firstAnswerSpoken

    /** Marks that an AI answer has been spoken this session. */
    fun markAiAnswerSpoken() {
        firstAnswerSpoken = true
    }

    /** A new car session (or app start) restores the first-answer prefix. */
    fun resetSession() {
        firstAnswerSpoken = false
    }

    // --- Actions the AI may propose: exactly the local families, nothing new. ---

    sealed class AiAction {
        data class Navigate(val destination: String) : AiAction()
        data class OpenApp(val appLabel: String) : AiAction()
        data class BrowserSearch(val term: String) : AiAction()
        data class Call(val contactName: String) : AiAction()
        data class ReplyMessage(val contactName: String, val text: String) : AiAction()
        data class Media(val command: String) : AiAction() // play | pause | next | previous
        data class Volume(val mode: String) : AiAction() // up | down | mute | unmute
        data class Brightness(val percent: Int?) : AiAction() // null = direction below
        data class BrightnessDirection(val direction: String) : AiAction() // up | down
        data class Toggle(val setting: String, val on: Boolean) : AiAction()
        data class Timer(val minutes: Int) : AiAction()
        data class Readout(val what: String) : AiAction() // battery | eta | messages
    }

    /** True for actions that only read state (no state change, no communication, no navigation). */
    fun isReadOnly(action: AiAction): Boolean = action is AiAction.Readout

    /**
     * The confirmation question spoken (and shown) for a proposal, with the concrete target
     * read back in full. Message text is never summarized: the user confirms the exact words.
     */
    fun confirmationText(action: AiAction): String = when (action) {
        is AiAction.Navigate -> "Navigate to ${action.destination}?"
        is AiAction.OpenApp -> "Open ${action.appLabel}?"
        is AiAction.BrowserSearch -> "Search for ${action.term}?"
        is AiAction.Call -> "Call ${action.contactName}?"
        is AiAction.ReplyMessage -> "Send this to ${action.contactName}: '${action.text}'?"
        is AiAction.Media -> when (action.command) {
            "play" -> "Play the music?"
            "pause" -> "Pause the music?"
            "next" -> "Skip to the next track?"
            else -> "Play the previous track?"
        }
        is AiAction.Volume -> when (action.mode) {
            "up" -> "Turn the volume up?"
            "down" -> "Turn the volume down?"
            "mute" -> "Mute the music?"
            else -> "Unmute the music?"
        }
        is AiAction.Brightness -> if (action.percent != null) "Set brightness to ${action.percent} percent?" else "Turn brightness up?"
        is AiAction.BrightnessDirection -> "Turn brightness ${if (action.direction == "up") "up" else "down"}?"
        is AiAction.Toggle -> when (action.setting) {
            "speak_directions" -> "Turn spoken directions ${if (action.on) "on" else "off"}?"
            "phone_mic" -> "Use the ${if (action.on) "phone" else "car"} microphone for voice commands?"
            else -> if (action.on) "Start screen sharing?" else "Stop screen sharing?"
        }
        is AiAction.Timer -> if (action.minutes == 60) "Set a timer for 1 hour?" else "Set a timer for ${action.minutes} minutes?"
        is AiAction.Readout -> when (action.what) {
            "battery" -> "Check your phone battery?"
            "eta" -> "Check your arrival estimate?"
            else -> "Read your messages?"
        }
    }

    /** The "Would do:" line the phone preview shows for a proposal (dry run — never executed). */
    fun wouldDoText(action: AiAction): String = when (action) {
        is AiAction.Navigate -> "Would do: navigate to ${action.destination}"
        is AiAction.OpenApp -> "Would do: open ${action.appLabel}"
        is AiAction.BrowserSearch -> "Would do: search for ${action.term}"
        is AiAction.Call -> "Would do: call ${action.contactName}"
        is AiAction.ReplyMessage -> "Would do: send message to ${action.contactName}: '${action.text}'"
        is AiAction.Media -> "Would do: media ${action.command}"
        is AiAction.Volume -> "Would do: volume ${action.mode}"
        is AiAction.Brightness -> "Would do: set brightness to ${action.percent} percent"
        is AiAction.BrightnessDirection -> "Would do: brightness ${action.direction}"
        is AiAction.Toggle -> "Would do: turn ${action.setting} ${if (action.on) "on" else "off"}"
        is AiAction.Timer -> "Would do: set a timer for ${action.minutes} minutes"
        is AiAction.Readout -> "Would do: read ${action.what}"
    }

    // --- Model reply parsing ---

    sealed class ParsedReply {
        /** Plain spoken answer text (already capped by [capAnswer] at the call site). */
        data class Answer(val text: String) : ParsedReply()
        /** One validated action proposal, awaiting confirmation. */
        data class Proposal(val action: AiAction) : ParsedReply()
        /** Refused, with a plain reason to speak — never a confirmation for a guess. */
        data class Refused(val reason: String) : ParsedReply()
        /** The utterance smells like an emergency: offer the LOCAL emergency flow, never AI dialing. */
        object EmergencyOffer : ParsedReply()
    }

    /**
     * Parses one model reply. A reply that starts with `{` is treated as an action proposal
     * and must parse as JSON with a known action and valid parameters; anything else about
     * it (bad JSON, unknown action, blank parameter, invented target) refuses it outright.
     * A reply that does not start with `{` is an answer — a parse failure there can never
     * become an action, which is the whole point of the shape check.
     */
    fun parseModelReply(raw: String, utterance: String): ParsedReply {
        val text = raw.trim()
        if (text.isEmpty()) return ParsedReply.Refused("AI answer isn't available right now.")
        if (!text.startsWith("{")) return ParsedReply.Answer(text)
        // Parsed with JsonMini, not org.json: the framework JSON class is a stub on the
        // JVM unit-test classpath (its methods return null under return-default-values),
        // which NPE'd every proposal test. JsonMini behaves identically on device and JVM.
        val root = JsonMini.parse(text) as? JsonMini.Value.Obj
            ?: return ParsedReply.Refused("I couldn't understand the AI reply, so I'm not acting on it.")
        fun strField(vararg names: String): String {
            for (n in names) {
                val v = root.objField(n)?.asString()
                if (v != null) return v.trim()
            }
            return ""
        }
        fun intField(name: String): Int? = root.objField(name)?.asInt()
        fun boolField(name: String): Boolean? = root.objField(name)?.asBool()
        val action = strField("action")
        return when (action) {
            "navigate" -> {
                val dest = strField("destination")
                if (dest.isEmpty()) return ParsedReply.Refused("I couldn't understand the destination, so I'm not navigating.")
                if (!sharesWordWith(dest, utterance)) {
                    return ParsedReply.Refused("I can't navigate somewhere you didn't mention.")
                }
                ParsedReply.Proposal(AiAction.Navigate(dest))
            }
            "open_app" -> {
                val app = strField("app", "app_label")
                if (app.isEmpty()) ParsedReply.Refused("I couldn't understand which app, so I'm not opening anything.")
                else ParsedReply.Proposal(AiAction.OpenApp(app))
            }
            "browser_search" -> {
                val term = strField("term")
                if (term.isEmpty()) ParsedReply.Refused("I couldn't understand what to search for.")
                else ParsedReply.Proposal(AiAction.BrowserSearch(term))
            }
            "call" -> {
                val name = strField("contact", "contact_name")
                if (name.isEmpty()) return ParsedReply.Refused("I couldn't understand who to call.")
                if (name.lowercase() in setOf("911", "emergency", "emergency services", "nine one one")) {
                    return ParsedReply.EmergencyOffer
                }
                ParsedReply.Proposal(AiAction.Call(name))
            }
            "reply_message" -> {
                val name = strField("contact", "contact_name")
                val msg = strField("text")
                if (name.isEmpty() || msg.isEmpty()) {
                    ParsedReply.Refused("I couldn't understand that message, so I'm not sending anything.")
                } else {
                    ParsedReply.Proposal(AiAction.ReplyMessage(name, msg))
                }
            }
            "media" -> {
                val cmd = strField("command").lowercase()
                if (cmd !in setOf("play", "pause", "next", "previous")) {
                    ParsedReply.Refused("I couldn't understand that media command.")
                } else {
                    ParsedReply.Proposal(AiAction.Media(cmd))
                }
            }
            "volume" -> {
                val mode = strField("mode").lowercase()
                if (mode !in setOf("up", "down", "mute", "unmute")) {
                    ParsedReply.Refused("I couldn't understand that volume command.")
                } else {
                    ParsedReply.Proposal(AiAction.Volume(mode))
                }
            }
            "brightness" -> {
                val pct = intField("percent")
                if (pct != null) {
                    if (pct !in 0..100) ParsedReply.Refused("That brightness value doesn't make sense.")
                    else ParsedReply.Proposal(AiAction.Brightness(pct))
                } else {
                    val dir = strField("direction").lowercase()
                    if (dir !in setOf("up", "down")) ParsedReply.Refused("I couldn't understand that brightness command.")
                    else ParsedReply.Proposal(AiAction.BrightnessDirection(dir))
                }
            }
            "toggle" -> {
                val setting = strField("setting").lowercase()
                if (setting !in setOf("speak_directions", "phone_mic", "screen_sharing")) {
                    ParsedReply.Refused("I can't change that setting from here.")
                } else {
                    ParsedReply.Proposal(AiAction.Toggle(setting, boolField("on") ?: true))
                }
            }
            "timer" -> {
                val minutes = intField("minutes") ?: -1
                if (minutes !in 1..240) ParsedReply.Refused("I can time up to 4 hours.")
                else ParsedReply.Proposal(AiAction.Timer(minutes))
            }
            "readout" -> {
                val what = strField("what").lowercase()
                if (what !in setOf("battery", "eta", "messages")) {
                    ParsedReply.Refused("I can't read that out from here.")
                } else {
                    ParsedReply.Proposal(AiAction.Readout(what))
                }
            }
            "hangup", "hang_up", "end_call" ->
                ParsedReply.Refused("I can't hang up an active call from here — hanging up needs the dialer.")
            else -> ParsedReply.Refused("I couldn't understand the AI reply, so I'm not acting on it.")
        }
    }

    /**
     * The invented-target guard for navigation: the destination must share at least one
     * content word (3+ letters, not an article) with the utterance, so the model cannot
     * smuggle in a place the user never said. App/contact/message targets are guarded
     * differently — by resolution against existing data at proposal time, in
     * [AssistantCommands] — because a contact's name legitimately need not appear
     * word-for-word in a nickname phrasing.
     */
    fun sharesWordWith(destination: String, utterance: String): Boolean {
        val stop = setOf("the", "a", "an", "to", "me", "my", "please")
        val uttered = utterance.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in stop }.toSet()
        val dest = destination.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in stop }.toSet()
        return uttered.intersect(dest).isNotEmpty()
    }

    // --- Spoken-answer cap ---

    /**
     * Caps an answer at [ANSWER_WORD_CAP] words, cut at a sentence boundary, with the
     * spoken "that's the short version" marker — never a mid-sentence cut, never a
     * multi-paragraph monologue. Exception, documented: when even the first sentence
     * alone exceeds the cap, it is spoken whole rather than cut mid-sentence.
     */
    fun capAnswer(text: String): String {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size <= ANSWER_WORD_CAP) return text.trim()
        val sentences = text.trim().split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        val kept = StringBuilder()
        var count = 0
        for (sentence in sentences) {
            val n = sentence.split(Regex("\\s+")).count { it.isNotEmpty() }
            if (kept.isNotEmpty() && count + n > ANSWER_WORD_CAP) break
            if (kept.isNotEmpty()) kept.append(' ')
            kept.append(sentence.trim())
            count += n
            if (count >= ANSWER_WORD_CAP) break
        }
        val result = kept.toString().ifBlank { sentences.firstOrNull()?.trim().orEmpty() }
        return "$result That's the short version."
    }

    // --- Confirmation matching: LOCAL exact matching only; the AI never interprets a yes/no. ---

    enum class ConfirmationDecision { ACCEPT, DECLINE, UNKNOWN }

    private val ACCEPT_WORDS = setOf("yes", "yeah", "yep", "yup", "sure", "ok", "okay", "confirm", "do it")
    private val DECLINE_WORDS = setOf("no", "nope", "nah", "cancel", "don't", "stop", "never mind")

    /** Matches a normalized heard phrase against the exact accept/decline vocabularies. */
    fun matchConfirmation(heardNormalized: String): ConfirmationDecision = when (heardNormalized.trim()) {
        in ACCEPT_WORDS -> ConfirmationDecision.ACCEPT
        in DECLINE_WORDS -> ConfirmationDecision.DECLINE
        else -> ConfirmationDecision.UNKNOWN
    }

    /**
     * The confirmation default, and the whole point of the design: silence, timeout,
     * ambiguity, and any unrecognized reply are all DECLINE. Nothing executes unless the
     * user said an exact accept word inside the window.
     */
    fun decideConfirmation(heardNormalized: String?, expired: Boolean): ConfirmationDecision {
        if (expired) return ConfirmationDecision.DECLINE
        if (heardNormalized.isNullOrBlank()) return ConfirmationDecision.DECLINE
        return when (matchConfirmation(heardNormalized)) {
            ConfirmationDecision.ACCEPT -> ConfirmationDecision.ACCEPT
            else -> ConfirmationDecision.DECLINE
        }
    }

    // --- Emergency smell check (before any AI call) ---

    /** True when an utterance that matched no built-in still smells like an emergency. */
    fun isEmergencyLike(utteranceNormalized: String): Boolean {
        val t = utteranceNormalized.lowercase()
        return "emergency" in t || "ambulance" in t || "911" in t || "nine one one" in t ||
            "call the police" in t || "get help" in t
    }

    // --- Place-dependent questions, option 1 (r27): the place must be in the question. ---

    /**
     * Words that make a question place-dependent: without a place there is no correct
     * answer, only a guess from somewhere else. Kept deliberately narrow — a general
     * question that happens to mention rain in passing ("is rain good for plants") is
     * not place-dependent, but the classifier only ever sees this AFTER the built-in /
     * keyword / fuzzy chain has passed, so a local command never reaches this check.
     */
    private val PLACE_DEPENDENT_WORDS = setOf(
        "weather", "forecast", "rain", "raining", "rainy", "snow", "snowing", "snowy",
        "temperature", "humidity", "humid", "windy", "wind",
    )
    /** Words that are times, not places — stripped when looking for a place phrase. */
    private val TIME_WORDS = setOf(
        "today", "tonight", "tomorrow", "this", "next", "weekend", "week", "morning",
        "afternoon", "evening", "now", "currently", "later",
    )
    private val PLACE_PREPOSITIONS = setOf("in", "at", "near", "around", "for")

    /** True when the normalized utterance asks something that needs a place to answer. */
    fun isPlaceDependentQuestion(normalized: String): Boolean {
        val tokens = normalized.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        if (tokens.any { it in PLACE_DEPENDENT_WORDS }) return true
        // "how hot/cold is it outside" is a weather question; without "outside" it is
        // ambiguous ("how hot is the oven") and is left to the model, not this guard.
        val joined = tokens.joinToString(" ")
        return (joined.contains("how hot") || joined.contains("how cold")) &&
            ("outside" in tokens || "out" in tokens)
    }

    /**
     * True when the normalized utterance names a place after a place preposition
     * ("weather in Dry Ridge", "will it rain in Cincinnati tomorrow"). Trailing time
     * words are stripped first, so "in Cincinnati tomorrow" still counts and "for
     * today" does not. No location is ever read here — this only reads the user's
     * own words, which is the whole point of option 1.
     */
    fun hasPlace(normalized: String): Boolean {
        val t = normalized.lowercase()
        val match = Regex("""\b(in|at|near|around|for)\s+([a-z][a-z ]*)$""").find(t) ?: return false
        val placeTokens = match.groupValues[2].trim().split(Regex("\\s+"))
            .filter { it.isNotEmpty() && it !in TIME_WORDS && it !in PLACE_PREPOSITIONS }
        return placeTokens.isNotEmpty()
    }

    /** The visual source line for a grounded answer; null for ungrounded ones. Never spoken. */
    fun sourceLine(grounded: Boolean): String? = if (grounded) "via Google Search" else null

    // --- Log redaction: a Gemini API key must never survive into a log, trace, or report. ---

    /**
     * Scrubs Gemini-shaped API key tokens (`AIza` + 10 or more key characters) from one
     * log line. [GearslipLog] applies this to every line it records, and the crash
     * reporter's tail filter drops any line containing `AIza` outright — two independent
     * guards, so a key can neither leak in full nor in a masked form that leaks length.
     * The key value itself is never compared here: only its shape.
     */
    object AiRedaction {
        private val KEY_TOKEN = Regex("AIza[0-9A-Za-z_\\-]{10,}")
        const val KEY_MARKER = "AIza"

        fun scrub(line: String): String = KEY_TOKEN.replace(line, "[api key redacted]")

        /** True when a line must be dropped whole rather than scrubbed (crash-report tails). */
        fun mustDrop(line: String): Boolean = KEY_MARKER in line
    }
}
