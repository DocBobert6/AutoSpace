package app.autospace.car

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.car.app.navigation.model.NavigationTemplate
import app.autospace.GearslipLog
import app.autospace.call.CallUiState
import app.autospace.call.CarCalls
import app.autospace.host.clockTime
import app.autospace.host.remainingTime
import app.autospace.media.CarMedia
import app.autospace.mirror.PhoneMirror
import app.autospace.notify.CarNotifications
import app.autospace.speech.VoiceFocus
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Turns what [CarAssistant] heard into an action plus something to say back.
 *
 * Deliberately small and keyword-matched rather than run through any NLU or bundled model: a
 * fixed set of things a driver actually wants hands-free (media, calling, opening an app,
 * getting back Home, and — since the 2026-10-25 expansion — call control, volume, brightness,
 * navigation stop/ETA/repeat, settings toggles, browser search, messages, battery, a guarded
 * emergency call, and a local timer), covering the same ground the rest of the car UI already
 * does rather than trying to be a general assistant.
 *
 * Ordering matters and is deliberate: the cancel, call-control, navigation-stop and toggle
 * commands run BEFORE [mediaCommand], because that command treats anything starting with
 * "stop" as pausing the music — "stop navigation", "stop screen sharing" and "stop speaking"
 * must not pause the music. A bare "stop" still does: it is the music's word, and the
 * assistant's cancel words are "cancel", "never mind", "stop speaking" and "stop talking".
 */
object AssistantCommands {

    /** Uptime until which a spoken "yes" counts as confirming an emergency call, else 0. */
    private var emergencyPendingUntil = 0L

    /**
     * One AI-proposed action awaiting confirmation, or null. Mirrors the emergency pattern:
     * the next command answers it, and the car overlay sheet (see [AssistantOverlay])
     * shows the same proposal with Accept/Decline buttons. The proposal is DATA, never
     * code — execution happens only through [executeAiAction] after an exact accept.
     */
    data class PendingAiAction(val action: AiAssistant.AiAction, val question: String, val expiresAt: Long)

    private val _aiPending = MutableStateFlow<PendingAiAction?>(null)

    /** The pending AI proposal, for the overlay confirmation sheet. Null when none is open. */
    val aiPending: StateFlow<PendingAiAction?> = _aiPending.asStateFlow()

    /**
     * True when the reply [handle] just produced is an AI answer (for CarUi's AI badge).
     * Reset at the start of every [handle] call; set only by the AI answer path. Local
     * refusals, confirmations, and executed local actions are not AI answers, so the badge
     * never appears on them.
     */
    var lastReplyWasAi = false
        private set

    /**
     * Grounding outcome of the last AI answer (r27); the overlay reads it for the
     * grounded glyph (r41). Reset on every [handle] call, and only ever set on an
     * Answer path — a confirmation sheet is not an answer and carries no glyph.
     */
    var lastReplyGrounded = false
        private set
    var lastReplySourceCount = 0
        private set

    /**
     * Provider of the last AI answer (r40); the overlay reads it for the glyph choice,
     * so a Groq answer gets the globe and a Gemini answer the Google G. Reset on every [handle] call.
     */
    var lastReplyProvider: AiAssistant.Provider = AiAssistant.Provider.GEMINI
        private set

    /** The one local timer, if any: a main-thread delayed runnable, never a calendar entry. */
    private var timerHandler: Handler? = null
    private var timerRunnable: Runnable? = null

    suspend fun handle(context: Context, navigator: CarNavigator, frame: CarEnvironment.Frame, heardRaw: String): String {
        lastReplyWasAi = false
        lastReplyGrounded = false
        lastReplySourceCount = 0
        lastReplyProvider = AiAssistant.Provider.GEMINI
        val heard = natural(heardRaw)
        VoiceHelp.hide()
        if (heard in HELP_WORDS || heard.startsWith("what can i say") || heard.startsWith("what can you do")) {
            emergencyPendingUntil = 0L
            _aiPending.value = null
            VoiceHelp.show()
            return "Here's what you can say."
        }
        // Audit fix (2026-10-25, B8/F8): the old reply swore back
        // aloud through the car speakers. Wording only — this branch
        // keeps its position before the emergency-confirmation block.
        if (heard.matches(Regex("(fuck|f\\*+) (you|off)( too)?"))) return "Let's keep it friendly — what can I do for you?"

        // A pending emergency confirmation is answered first: a yes dials, a no or any
        // other command cancels it, and silence cancels it once the window lapses (the
        // assistant only listens on a button press, so there is no background listener
        // to time out — the window is checked on the next command instead).
        if (emergencyPendingUntil != 0L) {
            if (SystemClock.elapsedRealtime() > emergencyPendingUntil) {
                emergencyPendingUntil = 0L
            } else if (heard in EMERGENCY_YES) {
                emergencyPendingUntil = 0L
                if (!hasCallPermissions(context)) return "AutoSpace needs the phone and contacts permissions first, from the phone screen."
                placeCall(context, "911")
                return "Calling emergency services."
            } else if (heard in EMERGENCY_NO) {
                emergencyPendingUntil = 0L
                return "Canceled. I won't call."
            } else {
                emergencyPendingUntil = 0L
            }
        }

        // An AI confirmation (r25) is answered the same way: the next command answers it.
        // An exact accept word inside the window executes the one proposed action; an exact
        // decline word declines it with a plain reply; anything else — including an expired
        // window — declines it silently and the utterance is then handled normally below
        // (mirroring the emergency block's else branch, so a fresh command is never eaten
        // by a stale proposal). The AI never interprets the answer: matching is local
        // and exact, so a vague "uh-huh" can never execute anything.
        val pendingAi = _aiPending.value
        if (pendingAi != null) {
            _aiPending.value = null
            if (SystemClock.elapsedRealtime() <= pendingAi.expiresAt) {
                when (AiAssistant.matchConfirmation(heard)) {
                    AiAssistant.ConfirmationDecision.ACCEPT ->
                        return executeAiAction(context, navigator, frame, pendingAi.action)
                    AiAssistant.ConfirmationDecision.DECLINE ->
                        return "Okay, not doing that."
                    AiAssistant.ConfirmationDecision.UNKNOWN -> Unit // Declined; fall through.
                }
            }
        }

        cancelCommand(heard)?.let { return it }
        callControlCommand(context, navigator, heard)?.let { return it }
        navigateCommand(context, navigator, frame, heard)?.let { return it }
        playSearchCommand(context, navigator, heard)?.let { return it }
        replyRepeatCommand(heard)?.let { return it }
        navExtraCommand(heard)?.let { return it }
        togglesCommand(context, navigator, heard)?.let { return it }
        volumeCommand(context, heard)?.let { return it }
        brightnessCommand(heard)?.let { return it }
        timerCommand(heard)?.let { return it }
        mediaCommand(heard)?.let { return it }
        navCommand(navigator, heard)?.let { return it }
        infoCommand(heard)?.let { return it }
        batteryCommand(context, heard)?.let { return it }
        emergencyCommand(context, heard)?.let { return it }
        callCommand(context, heard)?.let { return it }
        messagesCommand(context, heard)?.let { return it }
        // Custom voice shortcuts (r19) sit here on purpose: every exact built-in above has
        // already had its chance, so a bare keyword never beats a built-in, and the synonym /
        // fuzzy matcher below never gets to guess over a keyword. The "open <keyword>" and
        // "search <keyword>" forms sit before the generic open/search parsers for the same
        // reason a keyword is more specific than the generic command — but navigation
        // keywords are substituted inside navigateCommand above instead, so they keep the
        // full navigate path (nav-app picking, spoken confirmation) unchanged.
        keywordCommand(context, navigator, frame, heard)?.let { return it }
        browserSearchCommand(navigator, heard)?.let { return it }
        openAppCommand(context, navigator, frame, heard)?.let { return it }
        synonymCommand(context, navigator, heard)?.let { return it }
        // AI fallback (r25) sits LAST on purpose, after every exact built-in, keyword, and
        // fuzzy path: a local command must never take a network round-trip, and a keyword
        // or built-in must never lose to an AI guess. When AI is off, keyless, or offline,
        // this returns null and behavior is exactly today's unmatched reply.
        aiFallbackCommand(context, navigator, frame, heard)?.let { return it }

        return "I don't know how to do that yet."
    }

    /**
     * The overlay sheet's tap path: Accept executes the one pending proposal (or nothing
     * when it expired — the sheet hides at expiry, so this is only a race guard), Decline
     * clears it. Returns the spoken result for Accept, null when there was nothing to do.
     * The caller speaks the result through [CarAssistant.reply], the same path a voice
     * acceptance takes.
     */
    suspend fun acceptPendingAiAction(
        context: Context,
        navigator: CarNavigator,
        frame: CarEnvironment.Frame,
    ): String? {
        val pending = _aiPending.value ?: return null
        _aiPending.value = null
        if (SystemClock.elapsedRealtime() > pending.expiresAt) return null
        return executeAiAction(context, navigator, frame, pending.action)
    }

    /** Clears a pending AI proposal without executing it. True when one was actually open. */
    fun declinePendingAiAction(): Boolean {
        val had = _aiPending.value != null
        _aiPending.value = null
        return had
    }

    /** Clears a pending AI proposal silently (overlay dismiss, cancel) — never executes it. */
    fun clearPendingAiAction() {
        _aiPending.value = null
    }

    /**
     * Executes ONE already-confirmed AI action by re-dispatching through the exact local
     * command functions, with canonical phrasings — never through a new execution path.
     * Re-resolution is deliberate: a contact, thread, or app that vanished between proposal
     * and acceptance fails plainly instead of executing against stale data. Read-only
     * proposals never reach here through the confirmation path (they execute directly in
     * [aiFallbackCommand]); this function handles them too, for the tap path's completeness.
     */
    private suspend fun executeAiAction(
        context: Context,
        navigator: CarNavigator,
        frame: CarEnvironment.Frame,
        action: AiAssistant.AiAction,
    ): String {
        return when (action) {
            is AiAssistant.AiAction.Navigate ->
                navigateCommand(context, navigator, frame, "navigate to ${action.destination}")
                    ?: "I couldn't navigate there."
            is AiAssistant.AiAction.OpenApp ->
                openAppCommand(context, navigator, frame, "open ${action.appLabel}")
                    ?: "I couldn't find that app any more."
            is AiAssistant.AiAction.BrowserSearch ->
                browserSearchCommand(navigator, "search for ${action.term}")
                    ?: "I couldn't search for that."
            is AiAssistant.AiAction.Call ->
                callCommand(context, "call ${action.contactName}")
                    ?: "I couldn't call ${action.contactName}."
            is AiAssistant.AiAction.ReplyMessage ->
                messagesCommand(context, "text ${action.contactName} that ${action.text}")
                    ?: "That message can't be sent any more."
            is AiAssistant.AiAction.Media ->
                mediaCommand(action.command) ?: "Nothing's playing."
            is AiAssistant.AiAction.Volume -> volumeCommand(
                context,
                when (action.mode) {
                    "up" -> "volume up"
                    "down" -> "volume down"
                    "mute" -> "mute"
                    else -> "unmute"
                },
            ) ?: "Couldn't change the volume."
            is AiAssistant.AiAction.Brightness ->
                brightnessCommand("brightness ${action.percent}") ?: "Couldn't change the brightness."
            is AiAssistant.AiAction.BrightnessDirection ->
                brightnessCommand("brightness ${action.direction}") ?: "Couldn't change the brightness."
            is AiAssistant.AiAction.Toggle -> togglesCommand(
                context,
                navigator,
                when (action.setting) {
                    "speak_directions" -> "turn speak directions ${if (action.on) "on" else "off"}"
                    "phone_mic" -> if (action.on) "use phone mic" else "use car mic"
                    else -> if (action.on) "start screen sharing" else "stop screen sharing"
                },
            ) ?: "Couldn't change that setting."
            is AiAssistant.AiAction.Timer ->
                timerCommand("set a timer for ${action.minutes} minutes") ?: "Couldn't set that timer."
            is AiAssistant.AiAction.Readout -> when (action.what) {
                "battery" -> batteryCommand(context, "phone battery")
                "eta" -> navExtraCommand("eta")
                else -> messagesCommand(context, "read my messages")
            } ?: "Couldn't read that out."
        }
    }

    /**
     * AI fallback (r25), gated three ways before any network happens: opt-in ON, a key
     * stored, and a validated network route. Any gate failing returns null, which keeps
     * today's unmatched behavior byte-for-byte — no error about AI, no partial attempt.
     * The model receives the transcribed utterance ONLY (normalized, as the local parsers
     * see it); contacts, numbers, messages, history, and location are never sent.
     */
    private suspend fun aiFallbackCommand(
        context: Context,
        navigator: CarNavigator,
        frame: CarEnvironment.Frame,
        heard: String,
    ): String? {
        if (!AiKeyStore.aiAvailable(context)) return null
        if (!GeminiClient.networkAvailable(context)) return null
        // Vague emergency phrasing never reaches the model as an action candidate: it
        // offers the LOCAL emergency flow instead, which then follows the existing
        // spoken-confirmation guard. The AI itself never dials emergency numbers.
        if (AiAssistant.isEmergencyLike(heard)) {
            emergencyPendingUntil = SystemClock.elapsedRealtime() + EMERGENCY_CONFIRM_MS
            return "Do you want me to start an emergency call? That needs your confirmation."
        }
        // Place rule, option 1 (r27): a place-dependent question with no place in the
        // utterance is answered with a question, not a forecast — before any network
        // call, and without reading device location or any stored place. This is a
        // local reply, not an AI answer, so the badge stays off.
        if (AiAssistant.isPlaceDependentQuestion(heard) && !AiAssistant.hasPlace(heard)) {
            GearslipLog.i("assistant: ai place requested")
            return "For which place?"
        }
        // r40: the active provider picks the client. Both clients return their own
        // Result type with the same shape (Ok text + grounding, or a failure class),
        // so the branches below handle each explicitly — no shared supertype is
        // invented just to merge them, and neither client's mapping is reinterpreted.
        val provider = AiKeyStore.activeProvider(context)
        val key = AiKeyStore.loadKey(context, provider) ?: return null
        val result: Any = withContext(Dispatchers.IO) {
            when (provider) {
                AiAssistant.Provider.GROQ -> GroqClient.generate(key, heard)
                AiAssistant.Provider.GEMINI -> GeminiClient.generate(key, heard)
            }
        }
        val modelText = when (result) {
            is GeminiClient.Result.Ok -> result.text
            is GroqClient.Result.Ok -> result.text
            else -> {
                // r38: a retired model gets its own plain message and its own log
                // line, so a future retirement never again masquerades as the
                // generic "isn't available right now".
                if (result is GeminiClient.Result.ModelUnavailable ||
                    result is GroqClient.Result.ModelUnavailable
                ) GearslipLog.i("assistant: ai generate model unavailable")
                return when (result) {
                    is GeminiClient.Result -> AiAssistant.failureMessage(result)
                    is GroqClient.Result -> AiAssistant.failureMessage(result)
                    else -> "AI answer isn't available right now."
                }
            }
        }
        val grounded = when (result) {
            is GeminiClient.Result.Ok -> result.grounded
            is GroqClient.Result.Ok -> result.grounded
            else -> false
        }
        val sourceCount = when (result) {
            is GeminiClient.Result.Ok -> result.sourceCount
            is GroqClient.Result.Ok -> result.sourceCount
            else -> 0
        }
        return when (val parsed = AiAssistant.parseModelReply(modelText, heard)) {
            is AiAssistant.ParsedReply.Answer -> {
                val capped = AiAssistant.capAnswer(parsed.text)
                // r41: the spoken answer is the capped text, nothing more — the
                // vehicle never says "From AI". The small visual AI badge in
                // the overlay is the sole AI indicator.
                val spoken = capped
                lastReplyWasAi = true
                lastReplyProvider = provider
                lastReplyGrounded = grounded
                lastReplySourceCount = sourceCount
                GearslipLog.i("assistant: ai answer grounded=$grounded sources=$sourceCount")
                spoken
            }
            is AiAssistant.ParsedReply.Refused -> parsed.reason
            is AiAssistant.ParsedReply.EmergencyOffer -> {
                emergencyPendingUntil = SystemClock.elapsedRealtime() + EMERGENCY_CONFIRM_MS
                "Do you want me to start an emergency call? That needs your confirmation."
            }
            is AiAssistant.ParsedReply.Proposal -> resolveProposal(context, navigator, frame, parsed.action)
        }
    }

    /**
     * Proposal-time resolution: an AI target that does not resolve against existing data
     * is refused here, never confirmed. Contact/app/thread names are resolved the same
     * way the local commands resolve them (substring match on stored contacts, the app
     * entry list, the reply-able notification threads), and the confirmation reads back
     * the RESOLVED name — so "call Mom" confirms "Call Mom Smith?" only when Mom Smith
     * is actually in contacts. Navigation destinations were already overlap-checked in
     * [AiAssistant.parseModelReply]; read-only proposals execute directly (they change
     * no state, send nothing, and navigate nowhere).
     */
    private suspend fun resolveProposal(
        context: Context,
        navigator: CarNavigator,
        frame: CarEnvironment.Frame,
        action: AiAssistant.AiAction,
    ): String {
        val confirmed: AiAssistant.AiAction = when (action) {
            is AiAssistant.AiAction.Call -> {
                if (!hasCallPermissions(context)) {
                    return "AutoSpace needs the phone and contacts permissions first, from the phone screen."
                }
                val match = withContext(Dispatchers.IO) {
                    loadContacts(context).firstOrNull { it.name.lowercase().contains(action.contactName.lowercase()) }
                } ?: return "I couldn't find ${action.contactName} in your contacts."
                AiAssistant.AiAction.Call(match.name)
            }
            is AiAssistant.AiAction.OpenApp -> {
                val match = bestMatch(allEntries(context), action.appLabel) { it.label }
                    ?: return "I couldn't find an app called ${action.appLabel}."
                AiAssistant.AiAction.OpenApp(match.label)
            }
            is AiAssistant.AiAction.ReplyMessage -> {
                val thread = CarNotifications.history.value.firstOrNull { n ->
                    n.reply != null && (squash(n.title).contains(squash(action.contactName)) ||
                        squash(n.appLabel).contains(squash(action.contactName)) ||
                        n.lines.any { line -> line.sender?.let { squash(it).contains(squash(action.contactName)) } == true })
                } ?: return "I can't send a new message to ${action.contactName} from the car — " +
                    "I can only reply to a message they've already sent. Say 'read my messages' first."
                AiAssistant.AiAction.ReplyMessage(thread.title.ifBlank { thread.appLabel }, action.text)
            }
            else -> action
        }
        if (AiAssistant.isReadOnly(confirmed)) {
            return executeAiAction(context, navigator, frame, confirmed)
        }
        _aiPending.value = PendingAiAction(
            confirmed,
            AiAssistant.confirmationText(confirmed),
            SystemClock.elapsedRealtime() + AiAssistant.CONFIRM_TIMEOUT_MS,
        )
        GearslipLog.i("assistant: ai proposal awaiting confirmation")
        return AiAssistant.confirmationText(confirmed)
    }

    /**
     * "cancel", "never mind", "stop speaking", "stop talking": stop whatever the assistant
     * (or a message read) is saying and close the overlay without a spoken reply. Returning a
     * blank reply makes [CarAssistant.reply] settle to IDLE instead of speaking, which is the
     * whole point. A bare "stop" is NOT here on purpose — it stays the music's word.
     */
    private fun cancelCommand(heard: String): String? {
        if (heard !in CANCEL_PHRASES) return null
        CarAssistant.stopSpeaking()
        if (VoiceReply.active) VoiceReply.cancel()
        return ""
    }

    /**
     * Answering a ringing call works through the ordinary ringing-call API. Ending one does
     * not: hanging up an active call needs the dialer, and declining only works inside the
     * few-second call-screening window — both limits are already recorded in [CarKeys]'s end
     * key, so this command says so plainly and opens the phone screen instead of pretending.
     */
    private fun callControlCommand(context: Context, navigator: CarNavigator, heard: String): String? {
        val state = CarCalls.state.value
        if (heard == "answer" || heard == "answer the call" || heard == "answer it") {
            if (state is CallUiState.Ringing) {
                CarCalls.answer(context)
                return "Answering."
            }
            if (state is CallUiState.Active) return "You're already on a call."
            return "There's no call to answer."
        }
        if (heard == "hang up" || heard == "end the call" || heard == "hang up the call") {
            if (state is CallUiState.Ringing) {
                if (!state.canDecline) {
                    navigator.open("dialer")
                    return "That call can't be declined from here any more. Opening the phone so you can end it."
                }
                CarCalls.decline()
                return "Call declined."
            }
            if (state is CallUiState.Active) {
                navigator.open("dialer")
                return "I can't hang up an active call from here — hanging up needs the dialer. Opening the phone."
            }
            return "There's no call to hang up."
        }
        return null
    }

    /**
     * What was said, minus the padding people wrap a command in: "hey", "can you", "I want to",
     * "please", "thanks". Each command below then only has to know its own words.
     */
    private fun natural(raw: String): String {
        var t = raw.lowercase().replace(Regex("[.,!?]"), " ").replace(Regex("\\s+"), " ").trim()
        var changed = true
        while (changed) {
            changed = false
            for (lead in LEAD_FILLERS) if (t.startsWith("$lead ")) { t = t.removePrefix("$lead ").trim(); changed = true }
            for (tail in TAIL_FILLERS) if (t.endsWith(" $tail")) { t = t.removeSuffix(" $tail").trim(); changed = true }
        }
        return t
    }

    /** The part after the first of [triggers] that [text] starts with, or null. */
    private fun after(text: String, triggers: List<String>): String? =
        triggers.firstOrNull { text.startsWith(it) }?.let { text.removePrefix(it).trim() }

    private suspend fun allEntries(context: Context): List<Entry> {
        val vehicleData = CarSettings.experimentalFeaturesEnabled.value && CarSettings.carSensorsEnabled.value
        val mapsInstalled = context.packageManager.getLaunchIntentForPackage(MAPS_PACKAGE_NAME) != null
        val builtIn = BuiltInApps.entries.filter {
            (it.builtIn !== BuiltInApps.VehicleData || vehicleData) &&
                (it.builtIn !== BuiltInApps.MapsMirrored || mapsInstalled)
        }
        return builtIn + (LauncherCache.entries ?: withContext(Dispatchers.IO) { LauncherCache.load(context) })
    }

    /**
     * Best label match: exact, then a label starting with [name], then one containing it. Spaces
     * and punctuation don't count, since the recognizer may hear "MapQuest" as "map quest".
     */
    private fun <T> bestMatch(items: List<T>, name: String, label: (T) -> String): T? {
        val n = squash(name.removePrefix("the ").removePrefix("my ").removeSuffix(" app"))
        if (n.isEmpty()) return null
        items.firstOrNull { squash(label(it)) == n }?.let { return it }
        // Loose matches need a real word: "pizza in a" mustn't pick "Organic Maps" for its "a".
        if (n.length < 3) return null
        return items.firstOrNull { squash(label(it)).startsWith(n) }
            ?: items.firstOrNull { squash(label(it)).contains(n) }
    }

    private fun squash(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * "navigate to X [with|using|on|via Y]" - Y picks the nav app by (partial) label; without it,
     * whichever nav app was used last, or the only one installed, or the first one found. Starts
     * it straight into navigating there via [CarAppConnection]'s `ACTION_NAVIGATE` deep link -
     * the same one Android Auto's own Assistant integration uses - rather than opening the app
     * and leaving the driver to type the address in on the car keyboard.
     */
    private suspend fun navigateCommand(
        context: Context,
        navigator: CarNavigator,
        frame: CarEnvironment.Frame,
        heard: String,
    ): String? {
        // "use Organic Maps to get to X", "open OsmAnd and navigate to X": the app comes first.
        var text = heard
        var leadingApp: String? = null
        Regex("^(?:use|using|with|open|in|on) (.+?) (?:to |and )(.+)$").find(text)?.let { m ->
            if (after(m.groupValues[2], NAVIGATE_TRIGGERS) != null) {
                leadingApp = m.groupValues[1]
                text = m.groupValues[2]
            }
        }
        var destination = after(text, NAVIGATE_TRIGGERS) ?: return null
        if (destination.isBlank()) return "Where to?"

        // Custom voice shortcut (r19): an exact navigation keyword stands for its stored
        // address, substituted here so everything below — nav-app picking, the geo query,
        // the spoken confirmation naming the address — runs exactly as for a spoken address.
        // Only the type name is logged, never the keyword or the address.
        val navShortcut = VoiceShortcuts.findNavigation(context, destination)
            ?: destination.removePrefix("the ").takeIf { it != destination }
                ?.let { VoiceShortcuts.findNavigation(context, it) }
        if (navShortcut != null) {
            GearslipLog.i("assistant: voice shortcut matched type=NAVIGATION")
            destination = navShortcut.target
        }

        val entries = allEntries(context)
        // "go to Spotify" means the app, and "go to settings" the screen; "go to Target" is a place.
        val asApp = destination.removePrefix("the ").removeSuffix(" app")
        if (text.startsWith("go to ") &&
            (asApp in SCREEN_WORDS || asApp in APP_NICKNAMES || entries.any { it.label.lowercase() == asApp })
        ) {
            return null
        }
        val navApps = entries.filter { it.template?.isNavigation == true }
        if (navApps.isEmpty()) return "You don't have any navigation apps installed."

        // "X with Organic Maps", "X on OsmAnd": split only when the end names a nav app, so a
        // place like "Starbucks on Main Street" stays whole.
        var chosenApp = leadingApp?.let { name -> bestMatch(navApps, name) { it.label } ?: return "I couldn't find a navigation app called $name." }
        if (chosenApp == null) {
            for (sep in listOf(" with ", " using ", " on ", " via ", " in ", " through ")) {
                val at = destination.lastIndexOf(sep)
                if (at <= 0) continue
                val app = bestMatch(navApps, destination.substring(at + sep.length)) { it.label } ?: continue
                chosenApp = app
                destination = destination.substring(0, at).trim()
                break
            }
        }
        // "the nearest gas station" -> "gas station": the map app already searches around the car
        // and AutoRoute picks the nearest result, and Organic Maps took "nearest" as part of the name.
        val (place, nearby) = plainPlace(destination)
        destination = place
        if (destination.isBlank()) return "Where to?"

        val chosen = chosenApp ?: when {
            navApps.size == 1 -> navApps.first()
            else -> navApps.firstOrNull { it.componentId == CarSettings.lastNav.value } ?: navApps.first()
        }
        val template = chosen.template ?: return "That's not a navigation app."

        CarServices.connectNav(template, frame, destination)
        navigator.home()
        return if (nearby) "Finding the nearest $destination with ${chosen.label}." else "Navigating to $destination with ${chosen.label}."
    }

    /** [destination] without "a", "the nearest", "near me" and the like, and whether any of that said "close by". */
    private fun plainPlace(destination: String): Pair<String, Boolean> {
        var t = destination
        var nearby = false
        var changed = true
        while (changed) {
            changed = false
            for (lead in PLACE_LEADS) if (t.startsWith(lead)) {
                t = t.removePrefix(lead).trim(); changed = true
                if (lead.trim() !in ARTICLES) nearby = true
            }
            for (tail in PLACE_TAILS) if (t.endsWith(tail)) { t = t.removeSuffix(tail).trim(); changed = true; nearby = true }
        }
        return t to nearby
    }

    /**
     * "play X" with something after it: a song, artist, album or playlist, asked of the music app
     * that's open through its own search ([CarMedia.search]). A bare "play", or "play music",
     * falls through to resume.
     */
    private suspend fun playSearchCommand(context: Context, navigator: CarNavigator, heard: String): String? {
        var query = after(heard, PLAY_TRIGGERS) ?: return null
        query = query.removePrefix("some ").trim()
        if (query.isEmpty() || query in RESUME_WORDS) return null
        val media = CarServices.media

        // "play Daft Punk on Metrolist": switch to that app first, if it's one we know.
        for (sep in listOf(" on ", " in ", " from ", " with ", " using ")) {
            val at = query.lastIndexOf(sep)
            if (at <= 0) continue
            val apps = withContext(Dispatchers.IO) { app.autospace.media.MediaCatalog.installed(context) }
            val wanted = bestMatch(apps, query.substring(at + sep.length)) { it.label } ?: continue
            query = query.substring(0, at).trim()
            if (query.isEmpty() || query in RESUME_WORDS) {
                CarServices.openMedia(wanted, autoplay = true)
                navigator.media()
                return "Opening ${wanted.label}."
            }
            if (CarServices.mediaApp.value?.component != wanted.component) {
                CarServices.openMedia(wanted)
                navigator.media()
                // Give it a moment to connect before asking it to search.
                withTimeoutOrNull(APP_CONNECT_MS) {
                    while (media.appLabel == null || (!media.canSearchLibrary && !media.now.value.canSearch)) delay(200)
                }
            }
            break
        }

        // No music app open, or its connection is gone (the app was closed): use the one played
        // last, the way "play X" works on Android Auto.
        val phase = media.phase.value
        if (media.appLabel == null || phase == CarMedia.Phase.IDLE || phase == CarMedia.Phase.REJECTED) {
            if (!CarServices.reopenLastMedia()) return "Open a music app first, then ask again."
            navigator.media()
        }
        withTimeoutOrNull(APP_CONNECT_MS) {
            while (media.phase.value != CarMedia.Phase.READY || media.appLabel == null ||
                (!media.canSearchLibrary && !media.now.value.canSearch)
            ) delay(200)
        }
        val app = media.appLabel ?: return "Your music app didn't open in time. Try again."
        if (!media.canSearchLibrary && !media.now.value.canSearch) {
            return "$app ignores voice searches. Pick something on its screen instead."
        }
        media.playBestMatch(query)
        return "Looking for $query on $app."
    }

    private fun mediaCommand(heard: String): String? {
        val media = CarServices.media
        return when {
            "pause" in heard || heard.startsWith("stop") || heard == "quiet" || heard == "shut up" -> {
                media.pause()
                VoiceFocus.afterRelease { media.pause() }
                "Paused."
            }
            "resume" in heard || "unpause" in heard || heard in RESUME_PHRASES ||
                (heard.startsWith("play") && "playing" !in heard) -> {
                if (!media.now.value.playing) media.togglePlay()
                "Playing."
            }
            "next" in heard || "skip" in heard || heard.startsWith("change the song") -> {
                media.next()
                "Skipping."
            }
            "previous" in heard || "last song" in heard || "back song" in heard || "go back a song" in heard ||
                "play that again" in heard || "replay" in heard -> {
                media.previous()
                "Playing the previous track."
            }
            ("what" in heard && "playing" in heard) || "what song" in heard || "who sings" in heard ||
                "who's singing" in heard || "who is singing" in heard || "what's this song" in heard ||
                "name of this song" in heard -> {
                val now = media.now.value
                if (now.isActive) listOf(now.title, now.artist).filter { it.isNotBlank() }.joinToString(" by ")
                else "Nothing's playing."
            }
            else -> null
        }
    }

    /**
     * "Say that again" / "repeat that" for the assistant's own last
     * reply (audit fix, 2026-10-25, A1). Returns null when no
     * assistant reply has played, so the chain falls through to
     * [navExtraCommand], which answers with the last spoken
     * direction instead — both repeats keep working, and a
     * direction prompt (spoken by DirectionsVoice, never an
     * assistant reply) is never shadowed by an older reply.
     * Stop-speaking/cancel semantics are untouched: this runs
     * after cancelCommand, and only answers, never speaks over
     * anything — CarAssistant.reply() plays the answer.
     */
    private fun replyRepeatCommand(heard: String): String? {
        if (heard !in SAY_AGAIN_PHRASES) return null
        return CarAssistant.lastAssistantReply
    }

    private val SAY_AGAIN_PHRASES = setOf("say that again", "repeat that")

    /**
     * Synonyms + guarded fuzzy matching for ordinary commands
     * (audit fix, 2026-10-25, F11 — a matcher extension, NOT an
     * LLM). This runs LAST, after every exact parser, so an exact
     * match always wins. It only ever re-dispatches to the
     * no-argument parsers (brightness, volume, media, nav-extra,
     * settings toggles): emergency and call commands are never
     * fuzzy-matched — any heard phrase containing an emergency,
     * call, message, navigation-destination or search token
     * returns null outright, and the emergency-confirmation guard
     * above is untouched. "Stop" keeps meaning music: this runs
     * after cancelCommand, and a bare "stop" is already claimed
     * by mediaCommand's pause branch in the main chain.
     * Fuzzy matching is a Levenshtein distance of at most 2 on
     * a same-token-count canonical phrase, so "brighness up"
     * works but "brightness" alone never guesses a direction.
     * Only the canonical phrase is logged, never the raw heard
     * text.
     */
    private fun synonymCommand(context: Context, navigator: CarNavigator, heard: String): String? {
        val tokens = heard.split(" ")
        if (tokens.any { it in FUZZY_GUARD_TOKENS }) return null
        val numeric = Regex("^set (the )?brightness to (\\d{1,3})( percent)?$").find(heard)
        val canon = SYNONYMS[heard]
            ?: numeric?.let { "brightness ${it.groupValues[2]}" }
            ?: fuzzyCanonical(heard)
            ?: return null
        GearslipLog.i("assistant: synonym/fuzzy matched canonical '$canon'")
        // Order mirrors the main chain on purpose: mediaCommand
        // claims anything starting with "stop", so navExtra and
        // toggles must run first or "stop navigating" / "stop
        // screen sharing" would pause the music instead.
        return navExtraCommand(canon)
            ?: togglesCommand(context, navigator, canon)
            ?: volumeCommand(context, canon)
            ?: brightnessCommand(canon)
            ?: mediaCommand(canon)
    }

    private val FUZZY_GUARD_TOKENS = setOf(
        "call", "emergency", "dial", "text", "message", "navigate", "search", "open", "911",
    )

    private val SYNONYMS = mapOf(
        "make it brighter" to "brightness up",
        "brighten the screen" to "brightness up",
        "make it dimmer" to "brightness down",
        "dim the screen" to "brightness down",
        "turn the volume up" to "volume up",
        "turn the volume down" to "volume down",
        "make it louder" to "louder",
        "make it quieter" to "quieter",
        "turn the music up" to "volume up",
        "turn the music down" to "volume down",
        "start the music" to "play",
        "stop the music" to "pause",
        "skip this song" to "next",
        "go to the next song" to "next",
        "play the previous song" to "previous",
        "play the last song" to "previous",
        "turn on spoken directions" to "turn speak directions on",
        "turn off spoken directions" to "turn speak directions off",
        "use the phone mic" to "use phone mic",
        "use the car mic" to "use car mic",
        "how much longer" to "how long until we arrive",
        "how much further" to "how much further",
    )

    private val FUZZY_CANONICALS = listOf(
        "brightness up", "brightness down", "volume up", "volume down", "louder", "quieter",
        "mute", "unmute", "pause", "resume", "next", "previous",
        "turn speak directions on", "turn speak directions off",
        "use phone mic", "use car mic", "start screen sharing", "stop screen sharing",
        "start mirroring", "stop mirroring", "stop navigating", "how far", "eta",
    )

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
                current[j] = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
            }
            previous.indices.forEach { previous[it] = current[it] }
        }
        return previous[b.length]
    }

    /**
     * Stop a route, ask how far / when you'll arrive, repeat the last direction. The stop is
     * the nav app's own "stop navigating" call (see [CarAppConnection.stopNavigating]), not a
     * back press — a back press only pops the on-screen card and leaves the app believing it
     * is still navigating. ETA and distance come from the nav template's destination estimate
     * (the same fields the map chrome's estimate bar and the message quick-replies use), not
     * from the next-turn step, which only knows the distance to the next turn.
     */
    private fun navExtraCommand(heard: String): String? {
        if (heard in STOP_NAV_PHRASES) {
            val nav = CarServices.navOrNull()
            if (nav == null || (nav.trip.value == null && NavTurn.current() == null)) {
                return "You're not navigating."
            }
            nav.stopNavigating()
            return "Navigation stopped."
        }
        if (heard in ETA_PHRASES) {
            val estimate = (CarServices.navOrNull()?.template?.value as? NavigationTemplate)?.destinationTravelEstimate
                ?: return "You're not navigating."
            val distance = estimate.remainingDistance
            val seconds = estimate.remainingTimeSeconds
            val arrival = estimate.arrivalTimeAtDestination.clockTime()
            val parts = mutableListOf<String>()
            if (distance != null) parts += "${spokenDistance(distance)} to go"
            if (seconds > 0) parts += "about ${remainingTime(seconds)}"
            if (arrival.isNotEmpty()) parts += "arriving around $arrival"
            if (parts.isEmpty()) return "You're navigating, but I don't have an arrival estimate yet."
            return parts.joinToString(", ") + "."
        }
        if (heard in REPEAT_PHRASES) {
            return DirectionsVoice.lastSpoken ?: "Nothing to repeat yet."
        }
        return null
    }

    /** Distance the way a person would say it: feet under a mile, miles above it. */
    private fun spokenDistance(distance: androidx.car.app.model.Distance): String {
        val meters = NavTurn.toMeters(distance)
        return when {
            meters < 1609 -> "$meters feet"
            meters % 1609 == 0 -> "${meters / 1609} miles"
            else -> "%.1f miles".format(meters / 1609.0)
        }
    }

    /**
     * Settings the driver already flips by hand, now by voice. Screen sharing is the partial
     * one: stopping it is a direct call, but starting it needs the phone's screen-sharing
     * permission, which only the phone screen can grant — so "start" opens the screen-sharing
     * screen and says so, instead of claiming to have started it.
     */
    private fun togglesCommand(context: Context, navigator: CarNavigator, heard: String): String? {
        if (heard == "turn speak directions on" || heard == "turn spoken directions on") {
            CarSettings.setSpeakDirections(true)
            return "Spoken directions on."
        }
        if (heard == "turn speak directions off" || heard == "turn spoken directions off") {
            CarSettings.setSpeakDirections(false)
            return "Spoken directions off."
        }
        if (heard == "use phone mic" || heard == "use the phone microphone" || heard == "use phone microphone") {
            CarSettings.setPhoneMicForVoice(true)
            return "Using the phone microphone for voice commands."
        }
        if (heard == "use car mic" || heard == "use the car microphone" || heard == "use car microphone") {
            CarSettings.setPhoneMicForVoice(false)
            return "Using the car microphone for voice commands."
        }
        if (heard == "start screen sharing" || heard == "start mirroring") {
            navigator.open("phone")
            return "Opening screen sharing. Pick an app to mirror — starting it needs the phone's screen-sharing permission."
        }
        if (heard == "stop screen sharing" || heard == "stop mirroring") {
            if (!PhoneMirror.active.value) return "Screen sharing isn't running."
            PhoneMirror.requestStop()
            return "Screen sharing stopped."
        }
        return null
    }

    /** Media-stream volume (STREAM_MUSIC — the stream the car music and the mute helper use). */
    private fun volumeCommand(context: Context, heard: String): String? {
        val audio = context.getSystemService(AudioManager::class.java) ?: return null
        val stream = AudioManager.STREAM_MUSIC
        fun percent(): Int {
            val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
            return (audio.getStreamVolume(stream) * 100 / max)
        }
        return when {
            heard == "volume up" || heard == "turn it up" || heard == "louder" -> {
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, 0)
                "Volume ${percent()} percent."
            }
            heard == "volume down" || heard == "turn it down" || heard == "quieter" -> {
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, 0)
                "Volume ${percent()} percent."
            }
            heard == "mute" || heard == "mute the music" -> {
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
                "Muted."
            }
            heard == "unmute" || heard == "unmute the music" -> {
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                "Unmuted."
            }
            else -> null
        }
    }

    /** Car-screen brightness in 10-point steps, or an exact percent; clamped by the setter. */
    private fun brightnessCommand(heard: String): String? {
        val current = CarSettings.brightness.value
        if (heard == "brightness up" || heard == "turn brightness up") {
            CarSettings.setBrightness(current + 0.1f)
            return "Brightness ${(CarSettings.brightness.value * 100).toInt()} percent."
        }
        if (heard == "brightness down" || heard == "turn brightness down") {
            CarSettings.setBrightness(current - 0.1f)
            return "Brightness ${(CarSettings.brightness.value * 100).toInt()} percent."
        }
        val percent = Regex("^brightness (\\d{1,3})(?: percent)?$").find(heard)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        CarSettings.setBrightness(percent / 100f)
        return "Brightness ${(CarSettings.brightness.value * 100).toInt()} percent."
    }

    /**
     * One local timer, spoken when it fires. No calendar, no connection, no persistence past
     * the app's life — a main-thread delayed runnable, cancelled by "cancel timer".
     */
    private fun timerCommand(heard: String): String? {
        if (heard == "cancel timer" || heard == "cancel the timer") {
            val handler = timerHandler ?: return "No timer is running."
            timerRunnable?.let { handler.removeCallbacks(it) }
            timerHandler = null
            timerRunnable = null
            return "Timer canceled."
        }
        val minutes = Regex("^(?:remind me in|set a timer for|timer for) (\\d+) minutes?$").find(heard)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("^(?:remind me in|set a timer for|timer for) an? hour$").find(heard)?.let { 60 }
            ?: Regex("^(?:remind me in|set a timer for|timer for) (\\d+) hours?$").find(heard)
                ?.groupValues?.get(1)?.toIntOrNull()?.times(60)
            ?: return null
        if (minutes <= 0 || minutes > 240) return "I can time up to 4 hours."
        timerRunnable?.let { timerHandler?.removeCallbacks(it) }
        val handler = Handler(Looper.getMainLooper())
        val runnable = Runnable {
            timerHandler = null
            timerRunnable = null
            CarAssistant.say("Your timer is done.", {})
        }
        timerHandler = handler
        timerRunnable = runnable
        handler.postDelayed(runnable, minutes * 60_000L)
        return if (minutes == 60) "Timer set for 1 hour." else "Timer set for $minutes minutes."
    }

    /** Phone battery, from the system's battery service plus the sticky charging broadcast. */
    private fun batteryCommand(context: Context, heard: String): String? {
        if (heard != "what's my phone battery" && heard != "phone battery" && heard != "battery level" &&
            heard != "how much battery" && heard != "how much battery is left"
        ) return null
        val battery = context.getSystemService(BatteryManager::class.java)
        val percent = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        if (percent < 0) return "I couldn't read the battery level."
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val charging = sticky?.let {
            val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        } ?: false
        return "Your phone battery is at $percent percent" + if (charging) ", and charging." else "."
    }

    /**
     * Emergency calls NEVER dial straight away: this only arms a 30-second confirmation
     * window and asks. The next command answers it (see [handle]): a yes dials 911 through
     * the same [placeCall] path as a contact call, anything else cancels, and an expired
     * window cancels silently. A misheard "call 911" therefore cannot dial on its own.
     */
    private fun emergencyCommand(context: Context, heard: String): String? {
        if (heard != "call emergency services" && heard != "call 911" && heard != "call nine one one" &&
            heard != "dial 911" && heard != "dial emergency services"
        ) return null
        if (!hasCallPermissions(context)) return "AutoSpace needs the phone and contacts permissions first, from the phone screen."
        emergencyPendingUntil = SystemClock.elapsedRealtime() + EMERGENCY_CONFIRM_MS
        return "Do you want me to call emergency services? Say yes to confirm."
    }

    /**
     * Read the stored notifications, newest first. If the newest one can be replied to, it
     * goes through [VoiceReply.read] — which reads it and offers a reply — and this command
     * returns blank so the assistant overlay settles instead of speaking over it. Otherwise
     * the newest three are summarized as one spoken reply. "Text X that Y" only ever sends
     * through an existing thread's own reply action: there is no in-car route for a brand-new
     * message, and this says so rather than opening a composer on the phone screen.
     */
    private fun messagesCommand(context: Context, heard: String): String? {
        if (heard == "read my messages" || heard == "read my notifications" || heard == "any messages" ||
            heard == "check my messages" || heard == "read messages"
        ) {
            val history = CarNotifications.history.value
            if (history.isEmpty()) return "No messages."
            val newest = history.first()
            if (newest.reply != null) {
                VoiceReply.read(newest)
                return ""
            }
            val lines = history.take(3).map { n ->
                val from = n.title.ifBlank { n.appLabel }
                "$from says: ${n.text}"
            }
            return "${history.size} message${if (history.size == 1) "" else "s"}. " + lines.joinToString(". ") + "."
        }
        val send = Regex("^(?:text|message) (.+?) that (.+)$").find(heard) ?: return null
        val name = send.groupValues[1].trim()
        val message = send.groupValues[2].trim()
        if (name.isBlank() || message.isBlank()) return "Say it as: text [name] that [message]."
        val thread = CarNotifications.history.value.firstOrNull { n ->
            n.reply != null && (squash(n.title).contains(squash(name)) ||
                squash(n.appLabel).contains(squash(name)) ||
                n.lines.any { line -> line.sender?.let { squash(it).contains(squash(name)) } == true })
        } ?: return "I can't send a new message to $name from the car — I can only reply to a message they've already sent. Say 'read my messages' first."
        return if (CarNotifications.reply(context, thread, message)) {
            "Sent to ${thread.title.ifBlank { thread.appLabel }}."
        } else {
            "That message can't be replied to any more."
        }
    }

    /**
     * Custom voice shortcuts (r19), exact match only — a near-miss falls through to the
     * generic handlers and the fuzzy matcher, never to a keyword. Bare form: "my tunes"
     * opens the saved app, "my news" searches the saved term. Prefixed form: "open my
     * tunes", "search my news". A navigation keyword has no bare form here — it only
     * substitutes inside [navigateCommand]. Only the type is logged, never the keyword
     * or its target. Apps resolve through the same entry list [openAppCommand] uses
     * (built-ins + installed), by stored component id first, then by stored label.
     */
    private suspend fun keywordCommand(
        context: Context,
        navigator: CarNavigator,
        frame: CarEnvironment.Frame,
        heard: String,
    ): String? {
        val (candidate, wantedType) = when {
            heard.startsWith("open ") -> heard.removePrefix("open ").trim() to VoiceShortcutType.OPEN_APP
            heard.startsWith("search for ") -> heard.removePrefix("search for ").trim() to VoiceShortcutType.BROWSER_SEARCH
            heard.startsWith("search ") -> heard.removePrefix("search ").trim() to VoiceShortcutType.BROWSER_SEARCH
            else -> heard to null
        }
        if (candidate.isBlank()) return null
        // "open the browser app" style padding, same normalization openAppCommand uses.
        val normalized = candidate.removePrefix("the ").removePrefix("my ").removeSuffix(" app").trim()
        val shortcut = VoiceShortcuts.findKeyword(context, candidate)
            ?: VoiceShortcuts.findKeyword(context, normalized)
            ?: return null
        if (wantedType != null && shortcut.type != wantedType) return null
        return when (shortcut.type) {
            VoiceShortcutType.NAVIGATION -> null // Only via a navigate phrasing, in navigateCommand.
            VoiceShortcutType.OPEN_APP -> {
                GearslipLog.i("assistant: voice shortcut matched type=OPEN_APP")
                val entries = allEntries(context)
                val match = entries.firstOrNull { it.componentId == shortcut.target }
                    ?: shortcut.label.takeIf { it.isNotBlank() }?.let { bestMatch(entries, it) { entry -> entry.label } }
                    ?: return "I couldn't find that saved app any more. Edit the shortcut on the phone to pick it again."
                launchEntry(match, navigator, frame)
                "Opening ${match.label}."
            }
            VoiceShortcutType.BROWSER_SEARCH -> {
                GearslipLog.i("assistant: voice shortcut matched type=BROWSER_SEARCH")
                BrowserLaunch.pendingUrl = "https://noai.duckduckgo.com/?q=" + URLEncoder.encode(shortcut.target, "UTF-8")
                navigator.open("web")
                "Searching for ${shortcut.target}."
            }
        }
    }

    /** "search for X" opens the browser on no-AI DuckDuckGo with the query, via a pending URL. */
    private fun browserSearchCommand(navigator: CarNavigator, heard: String): String? {
        val query = after(heard, listOf("search for ", "search ", "look up ")) ?: return null
        if (query.isBlank()) return "What should I search for?"
        BrowserLaunch.pendingUrl = "https://noai.duckduckgo.com/?q=" + URLEncoder.encode(query, "UTF-8")
        navigator.open("web")
        return "Searching for $query."
    }

    private fun navCommand(navigator: CarNavigator, heard: String): String? = when {
        heard == "go back" || heard == "back" -> {
            navigator.back()
            "Going back."
        }
        heard in EXIT_WORDS -> {
            BuiltInApps.Exit.open(navigator)
            "Back to the car's screen."
        }
        "go home" in heard || heard == "home" || heard == "home screen" || "go to the home screen" in heard -> {
            navigator.home()
            "Heading home."
        }
        "open apps" in heard || heard == "apps" || "app list" in heard || "all apps" in heard || "my apps" in heard || heard == "go to apps" -> {
            navigator.apps()
            "Here are your apps."
        }
        "settings" in heard -> {
            navigator.settings()
            "Opening settings."
        }
        "vehicle data" in heard || "car data" in heard -> {
            if (CarSettings.experimentalFeaturesEnabled.value && CarSettings.carSensorsEnabled.value) {
                navigator.vehicleData()
                "Opening vehicle data."
            } else {
                "Vehicle data is off. Turn it on under experimental features in settings."
            }
        }
        else -> null
    }

    private fun infoCommand(heard: String): String? = when {
        "what time" in heard || "what's the time" in heard || heard == "time" || "the time" in heard ->
            "It's ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())}."
        "weather" in heard || "going to rain" in heard || "gonna rain" in heard || "how hot" in heard ||
            "how cold" in heard || "temperature outside" in heard -> {
            val data = (Weather.state.value as? WeatherState.Ready)?.data
            if (data != null) "${data.temp}${data.tempUnit} and ${Weather.describe(data.code)} in ${data.place}."
            else "I don't have the weather yet."
        }
        else -> null
    }

    private suspend fun callCommand(context: Context, heard: String): String? {
        val name = (
            after(heard, CALL_TRIGGERS)
                ?: Regex("^give (.+) a (?:call|ring)$").find(heard)?.groupValues?.get(1)
                ?: return null
            ).replace(Regex(" (?:on|at) (?:their |his |her )?(?:mobile|cell|phone|home|work)$"), "").trim()
        if (name.isBlank()) return "Who should I call?"
        if (!hasCallPermissions(context)) return "AutoSpace needs the phone and contacts permissions first, from the phone screen."

        val match = withContext(Dispatchers.IO) {
            loadContacts(context).firstOrNull { it.name.lowercase().contains(name) }
        } ?: return "I couldn't find $name in your contacts."

        placeCall(context, match.number)
        return "Calling ${match.name}."
    }

    private suspend fun openAppCommand(
        context: Context,
        navigator: CarNavigator,
        frame: CarEnvironment.Frame,
        heard: String,
    ): String? {
        val name = (after(heard, OPEN_TRIGGERS) ?: return null)
            .removePrefix("the ").removePrefix("my ").removeSuffix(" app").trim()
        if (name.isBlank()) return null
        val wanted = APP_NICKNAMES[name] ?: name

        // Gearslip's own apps come first in the list, so "phone" opens its dialer, not the phone's.
        val match = bestMatch(allEntries(context), wanted) { it.label }
            ?: return "I couldn't find an app called $name."

        launchEntry(match, navigator, frame)
        return "Opening ${match.label}."
    }

    private val EXIT_WORDS = setOf("exit", "exit gearslip", "close gearslip", "quit", "quit gearslip", "open exit")

    /** Other names people use for Gearslip's own apps. */
    private val APP_NICKNAMES = mapOf(
        "web" to "browser", "internet" to "browser", "the web" to "browser", "web browser" to "browser",
        "dialer" to "phone", "the dialer" to "phone", "keypad" to "phone",
        "screen share" to "screen sharing", "screen mirroring" to "screen sharing", "mirroring" to "screen sharing",
    )

    private val NAVIGATE_TRIGGERS = listOf(
        "navigate to ", "navigate me to ", "directions to ", "get directions to ", "give me directions to ",
        "take me to ", "drive to ", "drive me to ", "get me to ", "go to ", "head to ", "route to ",
        "how do i get to ", "show me the way to ", "find a route to ", "navigation to ", "get to ",
        // Last, so "navigate to" wins: the recognizer sometimes drops the "to".
        "navigate me ", "navigate ", "find me the nearest ", "find the nearest ", "find the closest ",
    )

    private val ARTICLES = setOf("a", "an", "the", "some")
    // Longest first, so "the nearest " goes before "the ".
    private val PLACE_LEADS = listOf(
        "the nearest ", "the closest ", "a nearby ", "the nearby ", "nearest ", "closest ", "nearby ",
        "a ", "an ", "some ",
    )
    private val PLACE_TAILS = listOf(" near me", " nearby", " near here", " around here", " close by", " closest to me")

    private val PLAY_TRIGGERS = listOf("play me ", "play ", "put on ", "listen to ", "i want to hear ", "shuffle ")

    private val CALL_TRIGGERS = listOf("call ", "phone ", "dial ", "ring ", "make a call to ", "place a call to ", "start a call with ")

    private val OPEN_TRIGGERS = listOf("open ", "launch ", "start ", "go to ", "switch to ", "show me ", "bring up ", "pull up ")

    /** Screens "go to X" means rather than a place. */
    private val SCREEN_WORDS = setOf("settings", "home", "the home screen", "home screen", "apps", "my apps", "all apps")

    /** Padding stripped from the front of a command, longest first where they overlap. */
    private val LEAD_FILLERS = listOf(
        "hey gearslip", "ok gearslip", "okay gearslip", "gearslip", "hey", "ok", "okay", "um", "uh", "so",
        "please", "can you please", "can you", "could you", "would you", "will you", "can i", "could i",
        "i want you to", "i want to", "i'd like to", "i would like to", "i wanna", "i need to", "i need you to",
        "let's", "lets", "go ahead and", "just", "now", "quickly",
    )

    /** Longest first, so "right now" goes whole rather than leaving "right". */
    private val TAIL_FILLERS = listOf("thank you very much", "thank you", "right now", "for me", "please", "thanks", "now")

    private const val APP_CONNECT_MS = 5_000L

    /** Assistant-cancel words: a bare "stop" is deliberately NOT here — it stays the music's. */
    private val CANCEL_PHRASES = setOf("cancel", "never mind", "cancel that", "stop speaking", "stop talking")

    private val STOP_NAV_PHRASES = setOf(
        "stop navigation", "cancel navigation", "end navigation", "stop navigating",
        "cancel the route", "end the route", "stop the route",
    )

    private val ETA_PHRASES = setOf(
        "how far", "how much further", "how far to go", "when will i arrive", "when do we arrive",
        "what's my eta", "what is my eta", "eta", "arrival time", "how long until we arrive",
    )

    private val REPEAT_PHRASES = setOf(
        "repeat that", "say that again", "repeat the last direction", "repeat the direction",
        "what was that direction",
    )

    /** Answers to the emergency-call confirmation; anything else cancels it. */
    private val EMERGENCY_YES = setOf("yes", "yeah", "yep", "yup", "sure", "ok", "okay", "confirm", "do it", "call them")
    private val EMERGENCY_NO = setOf("no", "nope", "nah", "cancel", "don't", "stop", "never mind")

    /** How long a spoken "yes" counts as confirming an emergency call. */
    private const val EMERGENCY_CONFIRM_MS = 30_000L

    private val HELP_WORDS = setOf("help", "commands", "voice commands", "list commands", "show commands")

    private val RESUME_WORDS = setOf("music", "the music", "some music", "something", "it", "again", "that", "my music")

    private val RESUME_PHRASES = setOf("keep playing", "continue", "keep going", "start the music", "music on", "turn the music back on")
}
