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
            "Otherwise reply with ONLY the spoken answer text, nothing else."

    /** Privacy notice substance (§6 of the spec), shown before first enable and linked from settings. */
    const val PRIVACY_NOTICE =
        "When AI answers are on, what you say to the assistant is sent to Google's Gemini API " +
            "to generate the answer. Your API key stays on this phone and is never sent anywhere " +
            "except to Google's API. AutoSpace does not send your contacts, messages, location, " +
            "or history to the AI — only the words you just said (and, for an action you confirm, " +
            "the exact thing you approved). Turn AI answers off anytime in Settings > AI assistant, " +
            "and remove your key there too. Without a network connection, AI answers are unavailable " +
            "and the local assistant keeps working. Google's own terms govern Google's side."

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
    fun isReadOnly(action: AiAction): Boolean = action is Readout

    /**
     * The confirmation question spoken (and shown) for a proposal, with the concrete target
     * read back in full. Message text is never summarized: the user confirms the exact words.
     */
    fun confirmationText(action: AiAction): String = when (action) {
        is Navigate -> "Navigate to ${action.destination}?"
        is OpenApp -> "Open ${action.appLabel}?"
        is BrowserSearch -> "Search for ${action.term}?"
        is Call -> "Call ${action.contactName}?"
        is ReplyMessage -> "Send this to ${action.contactName}: '${action.text}'?"
        is Media -> when (action.command) {
            "play" -> "Play the music?"
            "pause" -> "Pause the music?"
            "next" -> "Skip to the next track?"
            else -> "Play the previous track?"
        }
        is Volume -> when (action.mode) {
            "up" -> "Turn the volume up?"
            "down" -> "Turn the volume down?"
            "mute" -> "Mute the music?"
            else -> "Unmute the music?"
        }
        is Brightness -> if (action.percent != null) "Set brightness to ${action.percent} percent?" else "Turn brightness up?"
        is BrightnessDirection -> "Turn brightness ${if (action.direction == "up") "up" else "down"}?"
        is Toggle -> when (action.setting) {
            "speak_directions" -> "Turn spoken directions ${if (action.on) "on" else "off"}?"
            "phone_mic" -> "Use the ${if (action.on) "phone" else "car"} microphone for voice commands?"
            else -> if (action.on) "Start screen sharing?" else "Stop screen sharing?"
        }
        is Timer -> if (action.minutes == 60) "Set a timer for 1 hour?" else "Set a timer for ${action.minutes} minutes?"
        is Readout -> when (action.what) {
            "battery" -> "Check your phone battery?"
            "eta" -> "Check your arrival estimate?"
            else -> "Read your messages?"
        }
    }

    /** The "Would do:" line the phone preview shows for a proposal (dry run — never executed). */
    fun wouldDoText(action: AiAction): String = when (action) {
        is Navigate -> "Would do: navigate to ${action.destination}"
        is OpenApp -> "Would do: open ${action.appLabel}"
        is BrowserSearch -> "Would do: search for ${action.term}"
        is Call -> "Would do: call ${action.contactName}"
        is ReplyMessage -> "Would do: send message to ${action.contactName}: '${action.text}'"
        is Media -> "Would do: media ${action.command}"
        is Volume -> "Would do: volume ${action.mode}"
        is Brightness -> "Would do: set brightness to ${action.percent} percent"
        is BrightnessDirection -> "Would do: brightness ${action.direction}"
        is Toggle -> "Would do: turn ${action.setting} ${if (action.on) "on" else "off"}"
        is Timer -> "Would do: set a timer for ${action.minutes} minutes"
        is Readout -> "Would do: read ${action.what}"
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
        val json = runCatching { org.json.JSONObject(text) }.getOrNull()
            ?: return ParsedReply.Refused("I couldn't understand the AI reply, so I'm not acting on it.")
        val action = json.optString("action").trim()
        return when (action) {
            "navigate" -> {
                val dest = json.optString("destination").trim()
                if (dest.isEmpty()) return ParsedReply.Refused("I couldn't understand the destination, so I'm not navigating.")
                if (!sharesWordWith(dest, utterance)) {
                    return ParsedReply.Refused("I can't navigate somewhere you didn't mention.")
                }
                ParsedReply.Proposal(AiAction.Navigate(dest))
            }
            "open_app" -> {
                val app = json.optString("app").trim()
                if (app.isEmpty()) ParsedReply.Refused("I couldn't understand which app, so I'm not opening anything.")
                else ParsedReply.Proposal(AiAction.OpenApp(app))
            }
            "browser_search" -> {
                val term = json.optString("term").trim()
                if (term.isEmpty()) ParsedReply.Refused("I couldn't understand what to search for.")
                else ParsedReply.Proposal(AiAction.BrowserSearch(term))
            }
            "call" -> {
                val name = json.optString("contact").trim()
                if (name.isEmpty()) return ParsedReply.Refused("I couldn't understand who to call.")
                if (name.lowercase() in setOf("911", "emergency", "emergency services", "nine one one")) {
                    return ParsedReply.EmergencyOffer
                }
                ParsedReply.Proposal(AiAction.Call(name))
            }
            "reply_message" -> {
                val name = json.optString("contact").trim()
                val msg = json.optString("text").trim()
                if (name.isEmpty() || msg.isEmpty()) {
                    ParsedReply.Refused("I couldn't understand that message, so I'm not sending anything.")
                } else {
                    ParsedReply.Proposal(AiAction.ReplyMessage(name, msg))
                }
            }
            "media" -> {
                val cmd = json.optString("command").trim().lowercase()
                if (cmd !in setOf("play", "pause", "next", "previous")) {
                    ParsedReply.Refused("I couldn't understand that media command.")
                } else {
                    ParsedReply.Proposal(AiAction.Media(cmd))
                }
            }
            "volume" -> {
                val mode = json.optString("mode").trim().lowercase()
                if (mode !in setOf("up", "down", "mute", "unmute")) {
                    ParsedReply.Refused("I couldn't understand that volume command.")
                } else {
                    ParsedReply.Proposal(AiAction.Volume(mode))
                }
            }
            "brightness" -> {
                if (json.has("percent")) {
                    val pct = json.optInt("percent", -1)
                    if (pct !in 0..100) ParsedReply.Refused("That brightness value doesn't make sense.")
                    else ParsedReply.Proposal(AiAction.Brightness(pct))
                } else {
                    val dir = json.optString("direction").trim().lowercase()
                    if (dir !in setOf("up", "down")) ParsedReply.Refused("I couldn't understand that brightness command.")
                    else ParsedReply.Proposal(AiAction.BrightnessDirection(dir))
                }
            }
            "toggle" -> {
                val setting = json.optString("setting").trim().lowercase()
                if (setting !in setOf("speak_directions", "phone_mic", "screen_sharing")) {
                    ParsedReply.Refused("I can't change that setting from here.")
                } else {
                    ParsedReply.Proposal(AiAction.Toggle(setting, json.optBoolean("on", true)))
                }
            }
            "timer" -> {
                val minutes = json.optInt("minutes", -1)
                if (minutes !in 1..240) ParsedReply.Refused("I can time up to 4 hours.")
                else ParsedReply.Proposal(AiAction.Timer(minutes))
            }
            "readout" -> {
                val what = json.optString("what").trim().lowercase()
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
