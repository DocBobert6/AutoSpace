package app.autospace.car

/**
 * Assistant preview (r25 §8): the pure, device-free half of Settings > Assistant preview.
 *
 * The preview classifies an utterance WITHOUT executing anything: it mirrors the routing
 * order in [AssistantCommands] (exact built-ins → keywords → fuzzy → AI fallback →
 * unmatched) using the same trigger lists and the same normalization, but it never calls
 * a command function — so a previewed "call Mom" can never dial, and a previewed
 * "navigate home" can never start navigation. The trace it produces is what the phone
 * screen shows: handler, parameters, whether a confirmation would be required, and the
 * spoken text that would result. Read-only queries are the only thing the preview
 * labels as runnable for real, and even those run only through the UI's explicit choice,
 * never from classification.
 *
 * Honest limit, stated on the screen: this classifier is a MIRROR of the live parsers,
 * not the parsers themselves — the live chain also consults installed apps, contacts,
 * threads, and nav state, which a pure classifier cannot. A case passing here proves the
 * routing decision for its phrasing; it does not prove the live side effect, which is
 * exactly why the preview never performs one.
 */
object AssistantPreview {

    /** Where an utterance would route, in the order [AssistantCommands] checks them. */
    enum class Handler {
        HELP, CANCEL, EMERGENCY, EMERGENCY_OFFER, BUILTIN, KEYWORD, FUZZY, AI, UNMATCHED, AMBIGUOUS,
    }

    /** One classified utterance, as the preview screen shows it. */
    data class Trace(
        val utterance: String,
        val normalized: String,
        /** Never true from classification: previewed actions are never executed for real. */
        val executes: Boolean = false,
        val handler: Handler,
        /** The family or rule that matched, in plain words. */
        val detail: String,
        /** Extracted parameters (destination, contact, term), or blank when none. */
        val params: String = "",
        /** True when the live path would ask for confirmation before acting. */
        val wouldConfirm: Boolean = false,
        /** The spoken text that would result (confirmation question, answer, or refusal). */
        val spoken: String = "",
        /**
         * r27: whether the live path would carry a grounded answer for this utterance.
         * A preview heuristic (see [likelyGrounded]), not a network call — the live
         * source count is only known after Gemini replies, so [sourceCount] stays 0 here.
         */
        val grounded: Boolean = false,
        val sourceCount: Int = 0,
    )

    /** The dry-run banner text, shared by the preview screen and the suite summary. */
    const val DRY_RUN_NOTE = "Dry run — nothing here executes. Calls are not placed, " +
        "messages are not sent, navigation is not started, and settings are not changed."

    // Normalization mirrors AssistantCommands.natural (lead/tail filler stripping).
    private val LEAD_FILLERS = listOf(
        "hey gearslip", "ok gearslip", "okay gearslip", "gearslip", "hey", "ok", "okay", "um", "uh", "so",
        "please", "can you please", "can you", "could you", "would you", "will you", "can i", "could i",
        "i want you to", "i want to", "i'd like to", "i would like to", "i wanna", "i need to", "i need you to",
        "let's", "lets", "go ahead and", "just", "now", "quickly",
    )
    private val TAIL_FILLERS = listOf("thank you very much", "thank you", "right now", "for me", "please", "thanks", "now")

    fun normalize(raw: String): String {
        var t = raw.lowercase().replace(Regex("[.,!?]"), " ").replace(Regex("\\s+"), " ").trim()
        var changed = true
        while (changed) {
            changed = false
            for (lead in LEAD_FILLERS) if (t.startsWith("$lead ")) { t = t.removePrefix("$lead ").trim(); changed = true }
            for (tail in TAIL_FILLERS) if (t.endsWith(" $tail")) { t = t.removeSuffix(" $tail").trim(); changed = true }
        }
        return t
    }

    private fun after(text: String, triggers: List<String>): String? =
        triggers.firstOrNull { text.startsWith(it) }?.let { text.removePrefix(it).trim() }

    // Trigger lists mirror AssistantCommands' (same words, same order where order matters).
    private val NAVIGATE_TRIGGERS = listOf(
        "navigate to ", "navigate me to ", "directions to ", "get directions to ", "give me directions to ",
        "take me to ", "drive to ", "drive me to ", "get me to ", "go to ", "head to ", "route to ",
        "how do i get to ", "show me the way to ", "find a route to ", "navigation to ", "get to ",
        "navigate me ", "navigate ", "find me the nearest ", "find the nearest ", "find the closest ",
    )
    private val CALL_TRIGGERS = listOf("call ", "phone ", "dial ", "ring ", "make a call to ", "place a call to ", "start a call with ")
    private val OPEN_TRIGGERS = listOf("open ", "launch ", "start ", "go to ", "switch to ", "show me ", "bring up ", "pull up ")
    private val PLAY_TRIGGERS = listOf("play me ", "play ", "put on ", "listen to ", "i want to hear ", "shuffle ")
    private val EMERGENCY_EXACT = setOf(
        "call emergency services", "call 911", "call nine one one", "dial 911", "dial emergency services",
    )
    private val CANCEL_PHRASES = setOf("cancel", "never mind", "cancel that", "stop speaking", "stop talking")
    private val HELP_WORDS = setOf("help", "commands", "voice commands", "list commands", "show commands")
    private val SYNONYMS = mapOf(
        "make it brighter" to "brightness up", "brighten the screen" to "brightness up",
        "make it dimmer" to "brightness down", "dim the screen" to "brightness down",
        "turn the volume up" to "volume up", "turn the volume down" to "volume down",
        "make it louder" to "louder", "make it quieter" to "quieter",
        "turn the music up" to "volume up", "turn the music down" to "volume down",
        "start the music" to "play", "stop the music" to "pause",
        "skip this song" to "next", "go to the next song" to "next",
        "play the previous song" to "previous", "play the last song" to "previous",
        "turn on spoken directions" to "turn speak directions on",
        "turn off spoken directions" to "turn speak directions off",
        "use the phone mic" to "use phone mic", "use the car mic" to "use car mic",
        "how much longer" to "how long until we arrive", "how much further" to "how much further",
    )
    private val FUZZY_CANONICALS = listOf(
        "brightness up", "brightness down", "volume up", "volume down", "louder", "quieter",
        "mute", "unmute", "pause", "resume", "next", "previous",
        "turn speak directions on", "turn speak directions off",
        "use phone mic", "use car mic", "start screen sharing", "stop screen sharing",
        "start mirroring", "stop mirroring", "stop navigating", "how far", "eta",
    )
    private val FUZZY_GUARD_TOKENS = setOf(
        "call", "emergency", "dial", "text", "message", "navigate", "search", "open", "911",
    )

    /**
     * Classifies one utterance. [keywords] are the user's real stored shortcuts when the
     * phone screen has them (the canned suite passes its fixture table instead).
     * [aiGates] is the conjunction the live path checks: opt-in on + key stored + network.
     */
    fun classify(
        utterance: String,
        keywords: List<VoiceShortcut> = emptyList(),
        aiGates: Boolean = false,
    ): Trace {
        val heard = normalize(utterance)
        if (heard.isBlank()) {
            return Trace(utterance, heard, handler = Handler.UNMATCHED, detail = "Nothing heard", spoken = "Say something first.")
        }
        if (heard in HELP_WORDS || heard.startsWith("what can i say") || heard.startsWith("what can you do")) {
            return Trace(utterance, heard, handler = Handler.HELP, detail = "Help command", spoken = "Here's what you can say.")
        }
        if (heard in CANCEL_PHRASES) {
            return Trace(utterance, heard, handler = Handler.CANCEL, detail = "Cancel command", spoken = "(stops speaking, no reply)")
        }
        if (heard in EMERGENCY_EXACT) {
            return Trace(
                utterance, heard, handler = Handler.EMERGENCY, wouldConfirm = true,
                detail = "Emergency command — local guard, never AI",
                spoken = "Do you want me to call emergency services? Say yes to confirm.",
            )
        }
        if (AiAssistant.isEmergencyLike(heard)) {
            return Trace(
                utterance, heard, handler = Handler.EMERGENCY_OFFER, wouldConfirm = true,
                detail = "Vague emergency phrasing — local emergency flow offered, AI never dials",
                spoken = "Do you want me to start an emergency call? That needs your confirmation.",
            )
        }
        // Two action families joined by "and" need two confirmations, never one batch.
        val families = listOf(
            CALL_TRIGGERS.any { heard.startsWith(it) },
            NAVIGATE_TRIGGERS.any { heard.startsWith(it) },
            heard.startsWith("text ") || heard.startsWith("message "),
            heard.startsWith("search ") || heard.startsWith("search for "),
        ).count { it }
        if (heard.contains(" and ") && families >= 1 && (
                NAVIGATE_TRIGGERS.any { heard.contains(" and $it") } ||
                    CALL_TRIGGERS.any { heard.contains(" and $it") } ||
                    heard.contains(" and text ") || heard.contains(" and message ")
                )
        ) {
            return Trace(
                utterance, heard, handler = Handler.AMBIGUOUS, wouldConfirm = true,
                detail = "Two actions in one utterance — each needs its own confirmation",
                spoken = "That's two things. Say the first one on its own and confirm it, then the second.",
            )
        }
        // Exact built-ins, in the live chain's spirit (most specific families first).
        after(heard, NAVIGATE_TRIGGERS)?.let { dest ->
            if (dest.isNotBlank()) {
                return Trace(utterance, heard, handler = Handler.BUILTIN, detail = "Navigate command", params = dest, spoken = "Navigating to $dest.")
            }
        }
        // Read-only readouts sit BEFORE the call triggers on purpose, mirroring the live
        // order (battery is checked before call): "phone battery" starts with the
        // "phone " call trigger, so checking call first misroutes it as "Calling battery."
        when {
            heard == "phone battery" || heard == "battery level" || heard == "how much battery" ||
                heard == "how much battery is left" || heard == "what's my phone battery" ->
                return Trace(utterance, heard, handler = Handler.BUILTIN, detail = "Battery readout (read-only, runnable)", spoken = "Your phone battery is at … percent.")
            heard.contains("what time") || heard == "time" ->
                return Trace(utterance, heard, handler = Handler.BUILTIN, detail = "Time readout (read-only, runnable)", spoken = "It's ….")
            heard == "how far" || heard == "eta" || heard == "how much further" ->
                return Trace(utterance, heard, handler = Handler.BUILTIN, detail = "ETA readout (read-only, runnable)", spoken = "… to go.")
        }
        after(heard, CALL_TRIGGERS)?.let { name ->
            if (name.isNotBlank()) {
                return Trace(utterance, heard, handler = Handler.BUILTIN, detail = "Call command", params = name, spoken = "Calling $name.")
            }
        }
        if (heard.startsWith("text ") || heard.startsWith("message ")) {
            val m = Regex("^(?:text|message) (.+?) that (.+)$").find(heard)
            if (m != null) {
                return Trace(utterance, heard, handler = Handler.BUILTIN, detail = "Message reply (existing thread only)", params = "${m.groupValues[1]}: ${m.groupValues[2]}", spoken = "Sent.")
            }
        }
        after(heard, PLAY_TRIGGERS)?.let { query ->
            if (query.isNotBlank() && query !in setOf("music", "the music", "some music", "something", "it", "again", "that", "my music")) {
                return Trace(utterance, heard, handler = Handler.BUILTIN, detail = "Play search", params = query, spoken = "Looking for $query.")
            }
        }
        when {
            heard == "volume up" || heard == "louder" || heard == "turn it up" ->
                return Trace(utterance, heard, handler = Handler.BUILTIN, detail = "Volume command", spoken = "Volume up.")
            heard == "volume down" || heard == "quieter" || heard == "turn it down" ->
                return Trace(utterance, heard, handler = Handler.BUILTIN, detail = "Volume command", spoken = "Volume down.")
            heard == "brightness up" || heard == "brightness down" ->
                return Trace(utterance, heard, handler = Handler.BUILTIN, detail = "Brightness command", spoken = "Brightness changed.")
            heard.startsWith("set a timer for") || heard.startsWith("timer for") || heard.startsWith("remind me in") ->
                return Trace(utterance, heard, handler = Handler.BUILTIN, detail = "Timer command", spoken = "Timer set.")
            heard == "go home" || heard == "home" || heard == "home screen" || heard == "settings" || heard == "apps" ||
                heard == "go back" || heard == "back" || heard == "exit" || heard == "quit" ->
                return Trace(utterance, heard, handler = Handler.BUILTIN, detail = "Navigation command", spoken = "Done.")
            heard == "say that again" || heard == "repeat that" ->
                return Trace(utterance, heard, handler = Handler.BUILTIN, detail = "Repeat last reply", spoken = "(last reply)")
            heard == "read my messages" || heard == "read my notifications" || heard == "any messages" || heard == "check my messages" || heard == "read messages" ->
                return Trace(utterance, heard, handler = Handler.BUILTIN, detail = "Read messages (existing threads only)", spoken = "(messages read aloud)")
            heard == "pause" || heard.startsWith("stop") || heard == "quiet" ->
                return Trace(utterance, heard, handler = Handler.BUILTIN, detail = "Media command", spoken = "Paused.")
            heard == "next" || heard == "skip" ->
                return Trace(utterance, heard, handler = Handler.BUILTIN, detail = "Media command", spoken = "Skipping.")
            heard == "resume" || heard == "play" ->
                return Trace(utterance, heard, handler = Handler.BUILTIN, detail = "Media command", spoken = "Playing.")
            heard == "turn speak directions on" || heard == "turn spoken directions on" ||
                heard == "turn speak directions off" || heard == "turn spoken directions off" ||
                heard == "use phone mic" || heard == "use car mic" ||
                heard == "start screen sharing" || heard == "stop screen sharing" ||
                heard == "start mirroring" || heard == "stop mirroring" ->
                return Trace(utterance, heard, handler = Handler.BUILTIN, detail = "Settings toggle", spoken = "Done.")
            heard == "stop navigation" || heard == "cancel navigation" || heard == "end navigation" ->
                return Trace(utterance, heard, handler = Handler.BUILTIN, detail = "Stop navigation", spoken = "Navigation stopped.")
        }
        // Keywords: exact only, after the specific built-ins, before generic open/search
        // and before fuzzy — the live order (keywordCommand sits before browserSearch /
        // openApp in handle(), so "open my tunes" is a keyword, not a generic open).
        val (candidate, wantedType) = when {
            heard.startsWith("open ") -> heard.removePrefix("open ").trim() to VoiceShortcutType.OPEN_APP
            heard.startsWith("search for ") -> heard.removePrefix("search for ").trim() to VoiceShortcutType.BROWSER_SEARCH
            heard.startsWith("search ") -> heard.removePrefix("search ").trim() to VoiceShortcutType.BROWSER_SEARCH
            else -> heard to null
        }
        val normalizedCandidate = candidate.removePrefix("the ").removePrefix("my ").removeSuffix(" app").trim()
        val shortcut = keywords.firstOrNull { it.keyword == candidate || it.keyword == normalizedCandidate }
        if (shortcut != null && (wantedType == null || shortcut.type == wantedType)) {
            if (shortcut.type == VoiceShortcutType.NAVIGATION) {
                return Trace(utterance, heard, handler = Handler.KEYWORD, detail = "Navigation keyword (substitutes inside navigate)", params = shortcut.target, spoken = "Navigating to ${shortcut.target}.")
            }
            return Trace(utterance, heard, handler = Handler.KEYWORD, detail = "Voice shortcut: ${shortcut.type}", params = shortcut.target, spoken = "Using your saved shortcut.")
        }
        after(heard, listOf("search for ", "search ", "look up "))?.let { query ->
            if (query.isNotBlank()) {
                return Trace(utterance, heard, handler = Handler.BUILTIN, detail = "Browser search", params = query, spoken = "Searching for $query.")
            }
        }
        after(heard, OPEN_TRIGGERS)?.let { name ->
            val wanted = name.removePrefix("the ").removePrefix("my ").removeSuffix(" app").trim()
            if (wanted.isNotBlank()) {
                return Trace(utterance, heard, handler = Handler.BUILTIN, detail = "Open app command", params = wanted, spoken = "Opening $wanted.")
            }
        }
        // Synonym / fuzzy: local only, guarded tokens never fuzzy-matched.
        if (heard.split(" ").none { it in FUZZY_GUARD_TOKENS }) {
            val canon = SYNONYMS[heard] ?: fuzzyCanonical(heard)
            if (canon != null) {
                return Trace(utterance, heard, handler = Handler.FUZZY, detail = "Synonym/fuzzy → canonical '$canon'", params = canon, spoken = "Done (via $canon).")
            }
        }
        // AI fallback: only when every gate passes; otherwise today's unmatched behavior.
        if (aiGates) {
            // Place rule, option 1 (r27), mirroring the live path: a place-dependent
            // question with no place in the utterance is answered with a question,
            // never with a forecast from somewhere else — and no location is read.
            if (AiAssistant.isPlaceDependentQuestion(heard) && !AiAssistant.hasPlace(heard)) {
                return Trace(
                    utterance, heard, handler = Handler.AI,
                    detail = "Place-dependent question with no place — asks for a place, no location used",
                    spoken = "For which place?",
                )
            }
            val grounded = likelyGrounded(heard)
            return Trace(
                utterance, heard, handler = Handler.AI, wouldConfirm = true,
                detail = if (grounded) {
                    "AI fallback, grounded search expected — would call Gemini with this utterance only (source count shown live)"
                } else {
                    "AI fallback — would call Gemini with this utterance only"
                },
                spoken = "(AI answer, or one confirmed proposal — never executed from preview)",
                grounded = grounded,
            )
        }
        return Trace(utterance, heard, handler = Handler.UNMATCHED, detail = "No local match; AI gates closed", spoken = "I don't know how to do that yet.")
    }

    /**
     * Preview-only guess at whether the live reply would come back grounded (r27).
     * It mirrors the request, not the model: a place-dependent question that names its
     * place, or a question about current facts (news, scores, prices, latest), is the
     * kind the model searches for. General knowledge stays ungrounded. The live flag
     * comes from Gemini's grounding metadata, never from this guess.
     */
    fun likelyGrounded(normalized: String): Boolean {
        val tokens = normalized.split(" ").filter { it.isNotEmpty() }
        if (AiAssistant.isPlaceDependentQuestion(normalized) && AiAssistant.hasPlace(normalized)) return true
        return tokens.any {
            it in setOf("news", "headlines", "score", "scores", "stock", "stocks", "price", "prices", "latest", "current")
        }
    }

    private fun fuzzyCanonical(heard: String): String? {
        val heardTokens = heard.split(" ")
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for (canon in FUZZY_CANONICALS) {
            if (canon.split(" ").size != heardTokens.size) continue
            val distance = editDistance(heard, canon)
            if (distance in 1..2 && distance < bestDistance) {
                best = canon; bestDistance = distance
            }
        }
        return best
    }

    private fun editDistance(a: String, b: String): Int {
        val previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            previous.indices.forEach { previous[it] = current[it] }
        }
        return previous[b.length]
    }

    // --- Canned test suite ---

    /** Fixture keyword table the suite classifies against (not the user's real shortcuts). */
    val FIXTURE_KEYWORDS = listOf(
        VoiceShortcut("my tunes", VoiceShortcutType.OPEN_APP, "com.example.tunes", "Tunes"),
        VoiceShortcut("home", VoiceShortcutType.NAVIGATION, "123 Main Street"),
        VoiceShortcut("my news", VoiceShortcutType.BROWSER_SEARCH, "headlines"),
    )

    data class Case(val utterance: String, val expected: Handler, val note: String)

    /** Built-in list of utterances with expected routing. AI cases need the gates to run. */
    val CANNED_CASES = listOf(
        Case("navigate to the gas station", Handler.BUILTIN, "Exact navigate trigger"),
        Case("call Mom", Handler.BUILTIN, "Exact call trigger"),
        Case("play Daft Punk", Handler.BUILTIN, "Exact play search"),
        Case("volume up", Handler.BUILTIN, "Exact volume"),
        Case("brightness down", Handler.BUILTIN, "Exact brightness"),
        Case("set a timer for 5 minutes", Handler.BUILTIN, "Exact timer"),
        Case("phone battery", Handler.BUILTIN, "Read-only readout"),
        Case("help", Handler.HELP, "Help command"),
        Case("never mind", Handler.CANCEL, "Cancel command"),
        Case("call 911", Handler.EMERGENCY, "Exact emergency — local guard, never AI"),
        Case("I need an ambulance", Handler.EMERGENCY_OFFER, "Vague emergency — local offer, AI never dials"),
        Case("my tunes", Handler.KEYWORD, "Bare app keyword (fixture)"),
        Case("open my tunes", Handler.KEYWORD, "Prefixed app keyword (fixture)"),
        Case("search my news", Handler.KEYWORD, "Prefixed search keyword (fixture)"),
        Case("navigate to home", Handler.BUILTIN, "Navigation keyword substitutes inside navigate — still a builtin route"),
        Case("stop", Handler.BUILTIN, "Bare stop stays the music's word"),
        Case("make it brighter", Handler.FUZZY, "Synonym → brightness up"),
        Case("brighness up", Handler.FUZZY, "Fuzzy, distance 1 → brightness up"),
        Case("what is the capital of France", Handler.AI, "AI fallback sample — skipped when gates closed"),
        Case("tell me a joke", Handler.AI, "AI fallback sample — skipped when gates closed"),
        Case("weather in dry ridge", Handler.AI, "Grounded answer path — place present, skipped when gates closed"),
        Case("what is the weather today", Handler.AI, "Place-less weather — asks for a place, no location used, skipped when gates closed"),
        Case("what is the latest news", Handler.AI, "Grounded current-facts path, skipped when gates closed"),
        Case("call Mom and navigate home", Handler.AMBIGUOUS, "Two actions — two confirmations, never one batch"),
        Case("blorp glorp", Handler.AI, "No local match — AI fallback with gates open; unmatched when gates closed"),
        Case("open spotify", Handler.BUILTIN, "Generic open-app command"),
    )

    data class CaseResult(
        val case: Case,
        val actual: Handler?,
        val passed: Boolean,
        /** AI cases with closed gates count as skipped, never failed. */
        val skipped: Boolean,
    )

    data class SuiteResult(val results: List<CaseResult>) {
        val passed: Int get() = results.count { it.passed }
        val failed: Int get() = results.count { !it.passed && !it.skipped }
        val skipped: Int get() = results.count { it.skipped }
        val total: Int get() = results.size
    }

    /**
     * Runs the canned suite. AI cases are skipped (not failed) when the gates are closed;
     * every other case must match its expected handler exactly.
     */
    fun runSuite(keywords: List<VoiceShortcut> = FIXTURE_KEYWORDS, aiGates: Boolean = false): SuiteResult {
        val results = CANNED_CASES.map { case ->
            if (case.expected == Handler.AI && !aiGates) {
                CaseResult(case, null, passed = false, skipped = true)
            } else {
                val actual = classify(case.utterance, keywords, aiGates).handler
                CaseResult(case, actual, passed = actual == case.expected, skipped = false)
            }
        }
        return SuiteResult(results)
    }

    /** Confirmation-decision cases (pure): reply → expected decision under the live rules. */
    data class ConfirmationCase(val reply: String, val expired: Boolean, val expected: AiAssistant.ConfirmationDecision)

    val CONFIRMATION_CASES = listOf(
        ConfirmationCase("yes", false, AiAssistant.ConfirmationDecision.ACCEPT),
        ConfirmationCase("confirm", false, AiAssistant.ConfirmationDecision.ACCEPT),
        ConfirmationCase("do it", false, AiAssistant.ConfirmationDecision.ACCEPT),
        ConfirmationCase("no", false, AiAssistant.ConfirmationDecision.DECLINE),
        ConfirmationCase("cancel", false, AiAssistant.ConfirmationDecision.DECLINE),
        ConfirmationCase("stop", false, AiAssistant.ConfirmationDecision.DECLINE),
        ConfirmationCase("uh-huh", false, AiAssistant.ConfirmationDecision.DECLINE),
        ConfirmationCase("", false, AiAssistant.ConfirmationDecision.DECLINE),
        ConfirmationCase("yes", true, AiAssistant.ConfirmationDecision.DECLINE),
        ConfirmationCase("maybe", false, AiAssistant.ConfirmationDecision.DECLINE),
    )

    /** Evaluates the confirmation cases against [AiAssistant.decideConfirmation]. */
    fun runConfirmationCases(): List<Pair<ConfirmationCase, Boolean>> =
        CONFIRMATION_CASES.map { case ->
            case to (AiAssistant.decideConfirmation(case.reply.ifBlank { null }, case.expired) == case.expected)
        }
}
