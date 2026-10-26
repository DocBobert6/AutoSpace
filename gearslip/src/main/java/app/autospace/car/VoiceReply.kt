package app.autospace.car

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.car.app.navigation.model.NavigationTemplate
import app.autospace.GearslipLog
import app.autospace.call.CallUiState
import app.autospace.call.CarCalls
import app.autospace.host.clockTime
import app.autospace.host.remainingTime
import app.autospace.notify.CarNotification
import app.autospace.notify.CarNotifications
import app.autospace.speech.VoiceEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Messages by voice, the way Android Auto does it: a message is read aloud, "Do you want to
 * reply?", a yes gets "Go ahead", the reply is read back, and it sends after a few seconds unless
 * cancelled. Reply does the same; only once the message has just been read does it skip straight
 * to "What's your reply?". While the reply counts down, saying "cancel" or "change it" stops it or
 * starts it again. A quick reply sends a ready-made answer with no voice at all. Listening and speaking
 * go through [CarAssistant], so this hears through the same microphone and recognizer.
 *
 * Every step runs on the main thread. Each run gets a new [run] number, and callbacks from an
 * older run are dropped, so a cancel can never be undone by a late recognizer result.
 */
object VoiceReply {

    enum class Phase {
        IDLE,
        /** Reading the message aloud. */
        READING,
        /** Read; the Reply button stays up for a moment in case the driver wants it. */
        READ,
        /** Asking "Do you want to reply?" and listening for a yes. */
        OFFERING,
        /** Saying "What's your reply?". */
        ASKING,
        LISTENING,
        /**
         * Reading the reply back, then counting down to [State.until] while listening for
         * "cancel" or "change it". The countdown runs out whatever it hears.
         */
        CONFIRMING,
        SENT,
        ERROR,
    }

    data class State(
        val phase: Phase = Phase.IDLE,
        val target: CarNotification? = null,
        val heard: String = "",
        val error: String = "",
        /**
         * Uptime at which this step ends on its own (the card goes, or the reply sends), and how
         * long the step was given, for the card's bar. 0 when nothing is timed.
         */
        val until: Long = 0L,
        val span: Long = 0L,
        /** While a countdown is held, how much of its bar was left, so the bar stays put. */
        val held: Float = 0f,
    )

    const val SEND_DELAY_MS = 5_000L
    private const val LINGER_MS = 5_000L
    private const val SENT_MS = 2_000L

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var run = 0

    fun init(context: Context) { appContext = context.applicationContext }

    val active: Boolean get() = _state.value.phase != Phase.IDLE

    /**
     * A spoken step that rendered nothing: the driver never heard it,
     * so carrying on as if they had (asking about an unheard message,
     * counting down to send an unheard read-back) would be wrong.
     * Stops whatever the step started, then shows the error instead.
     * Bumps [run] so every callback the dead step already posted
     * (offer timeout, countdown, delayed listen) drops on its id
     * check instead of resurrecting the flow.
     */
    private fun silentSpeechError(id: Int) {
        if (id != run) return
        CarAssistant.stopListening()
        CarAssistant.stopSpeaking()
        main.removeCallbacksAndMessages(SEND_TOKEN)
        val nid = ++run
        // Round 3, same honesty rule as the assistant overlay: only a
        // load failure points at the files; a generate/playback
        // failure names its stage, since the files passed validation.
        val reason = VoiceEngine.lastFailure
        val error = when {
            reason == null -> "Couldn't speak that aloud — the voice render produced no audio."
            reason.contains("load") || reason.contains("unavailable") ->
                "Couldn't speak that aloud. Check the voice files in Settings."
            else -> "Couldn't speak that aloud — voice render failed ($reason)."
        }
        _state.value = _state.value.copy(
            phase = Phase.ERROR,
            error = error,
            until = SystemClock.uptimeMillis() + LINGER_MS,
            span = LINGER_MS,
            held = 0f,
        )
        main.postDelayed({ if (nid == run) finish() }, LINGER_MS)
    }

    /**
     * r44 backstop for a spoken step whose onDone/onSilent never
     * arrives: interrupted play on the car path suppresses onDone by
     * design, so without this a step parks its phase forever. The
     * guard is removed the moment the step ends normally, and only
     * fires while the phase is still the one the step started in —
     * so a step that advanced (offer's dictate, confirm's countdown)
     * can never trip it. Firing fails exactly like a silent render:
     * carrying on as if the driver heard it would be wrong.
     */
    private fun sayWithTimeout(id: Int, phase: Phase, text: String, onDone: () -> Unit) {
        val guard = Runnable { if (id == run && _state.value.phase == phase) silentSpeechError(id) }
        main.postDelayed(guard, SAY_TIMEOUT_MS)
        CarAssistant.say(text, onDone = {
            main.removeCallbacks(guard)
            onDone()
        }, onSilent = {
            main.removeCallbacks(guard)
            silentSpeechError(id)
        })
    }

    /** Reads [n]'s newest messages aloud. */
    fun read(n: CarNotification) {
        val id = begin(State(Phase.READING, n))
        sayWithTimeout(id, Phase.READING, spoken(n)) {
            if (id != run) return@sayWithTimeout
            when {
                n.reply == null -> finish()
                // The microphone is silent during a call, so there's no point asking.
                CarCalls.state.value is CallUiState.Active -> idleAfterRead(id)
                else -> offer(id)
            }
        }
    }

    /**
     * "Do you want to reply?" A yes goes on to the reply; anything else ends quietly. The bar runs
     * while it listens, so the card never looks stuck, and holds once the driver starts talking.
     */
    private fun offer(id: Int) {
        _state.value = _state.value.copy(phase = Phase.OFFERING, heard = "", until = 0L, span = 0L, held = 0f)
        sayWithTimeout(id, Phase.OFFERING, "Do you want to reply?") {
            if (id != run) return@sayWithTimeout
            val until = SystemClock.uptimeMillis() + OFFER_MS
            _state.value = _state.value.copy(until = until, span = OFFER_MS)
            // The recognizer gives up on its own; this only covers one that doesn't.
            main.postAtTime({ if (id == run && _state.value.until != 0L) { CarAssistant.stopListening(); close() } }, until + OFFER_GRACE_MS)
            CarAssistant.dictate(
                onPartial = { text -> if (id == run) _state.value = _state.value.copy(heard = text) },
                onSpeaking = {
                    if (id != run) return@dictate
                    val s = _state.value
                    val left = ((s.until - SystemClock.uptimeMillis()).toFloat() / s.span).coerceIn(0.05f, 1f)
                    _state.value = s.copy(until = 0L, span = 0L, held = left)
                },
                onResult = { text ->
                    if (id != run) return@dictate
                    if (isYes(text)) {
                        _state.value = _state.value.copy(phase = Phase.ASKING, heard = "", until = 0L, span = 0L, held = 0f)
                        sayWithTimeout(id, Phase.ASKING, "Go ahead.") { if (id == run) listen(id) }
                    } else {
                        close()
                    }
                },
                // No answer counts as no: the message has been heard, so the card goes.
                onError = { if (id == run) close() },
            )
        }
    }

    private fun idleAfterRead(id: Int) {
        _state.value = _state.value.copy(phase = Phase.READ, heard = "")
        lingerThenIdle(id)
    }

    private fun isYes(text: String): Boolean {
        val words = words(text)
        if (words.any { it in NO_WORDS }) return false
        return words.any { it in YES_WORDS }
    }

    /**
     * Reads [n] aloud and asks whether to reply, or, when the driver has just heard it, asks for
     * the reply straight away. Then listens, reads the reply back and sends it.
     */
    fun reply(n: CarNotification, readFirst: Boolean = true) {
        if (n.reply == null) {
            val id = begin(State(Phase.ERROR, n, error = "${n.appLabel} doesn't allow replies from the car."))
            lingerThenIdle(id)
            return
        }
        // Android hands every app but the dialer a silent microphone during a call.
        if (CarCalls.state.value is CallUiState.Active) {
            val id = begin(State(Phase.ERROR, n, error = "Voice reply works once the call ends."))
            lingerThenIdle(id)
            return
        }
        if (!readFirst) {
            val id = begin(State(Phase.ASKING, n))
            ask(id)
            return
        }
        // Read it, then "Do you want to reply?", the same as Read.
        read(n)
    }

    private fun ask(id: Int) {
        sayWithTimeout(id, Phase.ASKING, "What's your reply?") { if (id == run) listen(id) }
    }

    private fun listen(id: Int) {
        _state.value = _state.value.copy(phase = Phase.LISTENING, heard = "")
        // r44: the engine watchdog (45 s) normally ends a wedged
        // listen first; this only covers a dictate that never calls
        // back at all. The phase guard means a normally-advanced
        // flow can never trip it.
        main.postDelayed({
            if (id == run && _state.value.phase == Phase.LISTENING) {
                CarAssistant.stopListening()
                _state.value = _state.value.copy(phase = Phase.ERROR, error = "Didn't catch that.")
                lingerThenIdle(id)
            }
        }, LISTEN_TIMEOUT_MS)
        CarAssistant.dictate(
            onPartial = { text -> if (id == run) _state.value = _state.value.copy(heard = text) },
            onResult = { text -> if (id == run) confirm(id, text) },
            onError = { message ->
                if (id != run) return@dictate
                _state.value = _state.value.copy(phase = Phase.ERROR, error = message)
                lingerThenIdle(id)
            },
        )
    }

    private fun confirm(id: Int, text: String) {
        _state.value = _state.value.copy(phase = Phase.CONFIRMING, heard = text, until = 0L, span = 0L, held = 0f)
        // The countdown starts once the driver has heard the whole reply, not while it's read out.
        sayWithTimeout(id, Phase.CONFIRMING, "Sending: $text") {
            if (id != run) return@sayWithTimeout
            startCountdown(id, SEND_DELAY_MS)
            // A moment for the read-back to finish playing in the car before the microphone opens.
            main.postDelayed({ if (id == run) listenForCommand(id) }, ECHO_MS)
        }
    }

    /** Sends in [ms]. */
    private fun startCountdown(id: Int, ms: Long) {
        val sendAt = SystemClock.uptimeMillis() + ms
        _state.value = _state.value.copy(until = sendAt, span = ms, held = 0f)
        main.removeCallbacksAndMessages(SEND_TOKEN)
        main.postAtTime({ if (id == run) send() }, SEND_TOKEN, sendAt)
    }

    /**
     * Listens through the countdown for "cancel", "change it" or "send". The countdown never
     * pauses: in a noisy car it held for road noise and the radio, and the reply never went.
     */
    private fun listenForCommand(id: Int) {
        CarAssistant.dictate(
            onPartial = {},
            cue = false,
            onResult = { text ->
                if (id != run) return@dictate
                when (command(text)) {
                    Command.CANCEL -> {
                        close()
                        CarAssistant.say("Not sent.", onDone = {})
                    }
                    Command.CHANGE -> {
                        main.removeCallbacksAndMessages(SEND_TOKEN)
                        _state.value = _state.value.copy(phase = Phase.ASKING, heard = "", until = 0L, span = 0L, held = 0f)
                        ask(id)
                    }
                    Command.SEND -> send()
                    // Talk that wasn't meant for us, a passenger or the radio: keep listening, so a
                    // "cancel" after it still lands before the countdown runs out.
                    null -> listenForCommand(id)
                }
            },
            // Silence ends the recognizer before the countdown does: listen again until it sends.
            onError = {
                if (id != run) return@dictate
                // A short gap, so a recognizer that fails at once can't spin.
                main.postDelayed({ if (id == run) listenForCommand(id) }, RELISTEN_MS)
            },
        )
    }

    private enum class Command { CANCEL, CHANGE, SEND }

    private fun command(text: String): Command? {
        val words = words(text)
        return when {
            words.any { it in CANCEL_WORDS } -> Command.CANCEL
            words.any { it in CHANGE_WORDS } -> Command.CHANGE
            words.any { it in SEND_WORDS } -> Command.SEND
            else -> null
        }
    }

    private fun words(text: String) = text.lowercase().split(Regex("[^a-z']+")).filter { it.isNotEmpty() }

    /**
     * Ready-made replies for a tap instead of a voice reply. The first gives the arrival time when
     * the map is following a route.
     */
    fun quickReplies(): List<String> = listOfNotNull(etaReply(), "Driving, I'll reply soon.", "On my way.")

    private fun etaReply(): String? {
        val estimate = (CarServices.nav.template.value as? NavigationTemplate)?.destinationTravelEstimate ?: return null
        val at = estimate.arrivalTimeAtDestination.clockTime()
        val left = remainingTime(estimate.remainingTimeSeconds)
        return when {
            at.isNotEmpty() -> "Driving, I'll be there around $at."
            left.isNotEmpty() -> "Driving, I'll be there in about $left."
            else -> null
        }
    }

    /** Sends [text] to [n] straight away, as if it had been said and confirmed. */
    fun quickReply(n: CarNotification, text: String) {
        if (n.reply == null) return
        begin(State(Phase.CONFIRMING, n, heard = text))
        send()
    }

    /** Sends a reply that's counting down, without waiting for the rest of it. */
    fun sendNow() {
        if (_state.value.phase == Phase.CONFIRMING) send()
    }

    private fun send() {
        val s = _state.value
        val n = s.target ?: return finish()
        val context = appContext ?: return finish()
        val id = ++run
        CarAssistant.stopListening()
        CarAssistant.stopSpeaking()
        if (CarNotifications.reply(context, n, s.heard.trim())) {
            GearslipLog.i("voice reply: sent to ${n.appLabel}")
            CarAssistant.say("Message sent.", onDone = {})
            CarNotifications.dismissPopup(n.id)
            _state.value = s.copy(phase = Phase.SENT, until = SystemClock.uptimeMillis() + SENT_MS, span = SENT_MS, held = 0f)
            main.postDelayed({ if (id == run) finish() }, SENT_MS)
        } else {
            _state.value = s.copy(phase = Phase.ERROR, error = "${n.appLabel} withdrew the reply. Open the app on the phone to answer.")
            lingerThenIdle(id)
        }
    }

    /** Stops wherever it is. Nothing is sent, and the message card closes. */
    fun cancel() {
        if (!active) return
        CarAssistant.stopListening()
        CarAssistant.stopSpeaking()
        close()
    }

    /**
     * Ends without sending and takes the message card with it. Without this the card's own popup
     * came back with a fresh bar, as if the message had just arrived.
     */
    private fun close() {
        _state.value.target?.let { CarNotifications.dismissPopup(it.id) }
        finish()
    }

    /**
     * The steering wheel's voice key. With a message on screen it answers that message, and
     * during a countdown it sends straight away. Returns false when there's nothing to answer, so
     * the key can start the assistant instead.
     */
    fun onVoiceKey(): Boolean {
        val s = _state.value
        when (s.phase) {
            // Already heard, so straight to the reply.
            Phase.READ, Phase.READING, Phase.OFFERING, Phase.ERROR -> s.target?.let { reply(it, readFirst = false); return true }
            Phase.CONFIRMING -> { sendNow(); return true }
            Phase.ASKING, Phase.LISTENING, Phase.SENT -> return true
            Phase.IDLE -> Unit
        }
        val popup = CarNotifications.popup.value ?: return false
        if (popup.reply == null) return false
        reply(popup)
        return true
    }

    private fun begin(state: State): Int {
        CarAssistant.stopListening()
        CarAssistant.stopSpeaking()
        main.removeCallbacksAndMessages(null)
        _state.value = state
        return ++run
    }

    private fun lingerThenIdle(id: Int) {
        _state.value = _state.value.copy(until = SystemClock.uptimeMillis() + LINGER_MS, span = LINGER_MS, held = 0f)
        main.postDelayed({ if (id == run) finish() }, LINGER_MS)
    }

    private fun finish() {
        run++
        main.removeCallbacksAndMessages(null)
        _state.value = State()
    }

    /** "Alex says: ..." or, in a group, "Sam in Climbing says: ...", for the newest messages. */
    private fun spoken(n: CarNotification): String {
        val from = n.title.ifBlank { n.appLabel }
        val lines = n.lines.filter { it.sender != null }.takeLast(n.burst.coerceIn(1, MAX_READ))
        if (lines.isEmpty()) return "$from says: ${n.text}"
        return lines.joinToString(". ") { line ->
            val who = if (n.isGroup) "${line.sender} in $from" else from
            "$who says: ${line.text}"
        }
    }

    private const val MAX_READ = 3
    private val YES_WORDS = setOf("yes", "yeah", "yep", "yup", "sure", "ok", "okay", "reply", "please")
    private val NO_WORDS = setOf("no", "nope", "nah", "cancel", "don't", "stop")
    private val CANCEL_WORDS = setOf("cancel", "stop", "don't", "no", "nope")
    private val CHANGE_WORDS = setOf("change", "redo", "again", "edit", "wrong", "fix")
    private val SEND_WORDS = setOf("send", "yes", "yeah", "ok", "okay", "go")

    private val SEND_TOKEN = Any()
    private const val ECHO_MS = 300L
    /** How long "Do you want to reply?" waits for an answer: the start beep, then the engine's silence limit. */
    private const val OFFER_MS = 6_500L
    private const val OFFER_GRACE_MS = 1_500L
    private const val RELISTEN_MS = 400L
    /**
     * r44 backstop for a spoken step that never ends (see
     * sayWithTimeout). Long on purpose: a long message read can
     * legitimately take over a minute, so this only catches a true
     * wedge, never a slow read.
     */
    private const val SAY_TIMEOUT_MS = 180_000L
    /** r44: longer than the engine's 45 s watchdog, so the engine's own error normally wins. */
    private const val LISTEN_TIMEOUT_MS = 60_000L
}
