package app.autospace.car

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import app.autospace.GearslipLog
import app.autospace.call.CallUiState
import app.autospace.call.CarCalls
import app.autospace.speech.ListenWatchdogPolicy
import app.autospace.speech.SpeechEngine
import app.autospace.speech.VoiceEngine
import app.autospace.speech.VoiceFocus
import app.autospace.speech.VoiceModels
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A voice assistant: Gearslip's own [SpeechEngine] to listen, [VoiceEngine]
 * (Kokoro, with Piper as fallback) to answer. Listening used to go through
 * the system's [SpeechRecognizer] alone, but many phones have no recognizer
 * at all (GrapheneOS ships only text-to-speech), so the system one is now
 * just the fallback for when the bundled engine can't run. Speaking has no
 * system fallback at all: if neither voice model is downloaded, the
 * assistant says so in the overlay instead of borrowing the phone's voice.
 *
 * Listening and speaking live here; deciding what a command *means* is [AssistantCommands], kept
 * separate because it needs a [CarNavigator] and a frame to act on, neither of which this object
 * has - see [pendingCommand].
 */
object CarAssistant {

    /**
     * Whether a reply()/say() acquire of [VoiceFocus] is still owed a
     * release. VoiceEngine.play deliberately does NOT fire onDone when
     * speech is interrupted (a stale onDone would clobber a newer
     * reply's SPEAKING state), so an interrupted reply/say used to leak
     * exactly one focus hold and the music never resumed. The helpers
     * below close that gap: every new acquire first releases a stranded
     * hold, and every path that stops speech (cancel, listen, "stop
     * speaking") releases one too.
     */
    private var speechFocusHeld = false

    private fun acquireSpeechFocus(context: Context) {
        if (speechFocusHeld) VoiceFocus.release()
        VoiceFocus.acquire(context)
        speechFocusHeld = true
    }

    private fun releaseSpeechFocus() {
        if (!speechFocusHeld) return
        speechFocusHeld = false
        VoiceFocus.release()
    }

    enum class Phase { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

    data class State(
        val phase: Phase = Phase.IDLE,
        val heard: String = "",
        val reply: String = "",
        val missingPermission: Boolean = false,
        /** False from the press until the microphone is actually open, so the overlay doesn't claim to listen early. */
        val ready: Boolean = false,
        /** r57: true while the post-reply listen window is open — the overlay reads "Still listening…" instead of "Listening…". */
        val followUp: Boolean = false,
        /** True when the reply being shown is an AI answer (r25 badge); local replies never set this. */
        val fromAi: Boolean = false,
        /**
         * True when the reply being shown is a grounded AI answer (r27 source line);
         * [sourceCount] is how many web sources backed it. Local replies never set these.
         */
        val grounded: Boolean = false,
        val sourceCount: Int = 0,
        /** Provider of the reply being shown (r40) — picks the source line's wording. */
        val provider: AiAssistant.Provider = AiAssistant.Provider.GEMINI,
    )

    private val _state = MutableStateFlow(State())
    /** Last assistant reply that started playing (A1); never message text. */
    var lastAssistantReply: String? = null
        private set
    val state: StateFlow<State> = _state

    /** Set once a recognition result comes in; [reply] clears it once handled. */
    private val _pendingCommand = MutableStateFlow<String?>(null)
    val pendingCommand: StateFlow<String?> = _pendingCommand

    private var appContext: Context? = null
    private var recognizer: SpeechRecognizer? = null

    // r55 streamed replies: a turn whose answer streams speaks its
    // sentences as they arrive, through one VoiceEngine stream
    // session owned here. The session exists only for prose AI
    // answers (GroqClient never fires stream callbacks for a
    // `{`-leading action JSON), and reply() finishes it instead of
    // re-speaking whenever the final text matches the streamed
    // content exactly.
    /** Elapsed-realtime moment the last transcript went final; the latency baseline. */
    @Volatile
    var lastListenEndMs = 0L
        private set

    private class StreamSession(val context: Context) {
        val fullText = StringBuilder()
        val emitter = CappedSentenceEmitter(AiAssistant.ANSWER_WORD_CAP)
        var stream: VoiceEngine.StreamedSpeech? = null
        var speechStarted = false
        var failed = false
        var noEngine = false
    }

    private val streamLock = Any()
    private var streamSession: StreamSession? = null
    // r44: when the current LISTENING/THINKING phase began, for the
    // stale-phase restart in startOnMainThread.
    private var busySinceMs = 0L
    // r44: the system recognizer's watchdog runnable, so a wedged
    // service can be ended from releaseRecognizer().
    private var systemWatchdog: Runnable? = null
    // Audit fix (2026-10-25, V2): the system-speech path owns one
    // listeningActive count from markListeningStart() until the
    // recognizer is released. The old code decremented only in the
    // result/error callbacks, so a release without a callback (cancel,
    // teardown) left the count stuck and gated the hotword off.
    // releaseRecognizer() now owns the decrement, exactly once.
    private var systemListening = false

    // r72 barge-in: generation guard for superseded turns. A fresh listen (or a
    // cancel) invalidates any older turn still running, so its late stream
    // callbacks die in streamText/streamSentence instead of re-speaking an
    // interrupted reply's tail, and the CarUi collector discards its reply.
    private val turnGate = TurnGate()

    /** The generation of the turn currently in flight, for stream callbacks to capture. */
    val turnGeneration: Int get() = turnGate.current()

    fun init(context: Context) {
        appContext = context.applicationContext
        appContext?.let(VoiceModels::init)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Starts listening, or does nothing if the assistant is already busy with the last request.
     * [SpeechRecognizer] must be driven from a thread with a Looper, but this is also called from
     * the raw wire-protocol worker thread ([GearslipRunner]'s key-event handling for the head
     * unit's own PTT button) which has none - so this always hops to the main thread itself
     * rather than asking every caller to remember to.
     */
    fun start() {
        if (Looper.myLooper() == Looper.getMainLooper()) startOnMainThread() else mainHandler.post(::startOnMainThread)
    }

    private fun startOnMainThread() {
        val context = appContext ?: return
        val phase = _state.value.phase
        if (phase == Phase.LISTENING || phase == Phase.THINKING) {
            // r44: a phase left parked by a listen that ended without
            // a callback (or a dead command consumer) used to no-op
            // every later start forever. A live listen still wins —
            // only a phase with no live listen behind it (LISTENING)
            // or older than any legit think (THINKING) restarts, via
            // the same cancel() a stop press uses, and falls through
            // to a fresh listen below.
            if (!isStalePhase(phase, SpeechEngine.listeningActive, SystemClock.uptimeMillis() - busySinceMs)) return
            GearslipLog.w("assistant: stale $phase phase restarted")
            cancel()
        }

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            _state.value = State(Phase.ERROR, reply = "Needs the microphone permission.", missingPermission = true)
            return
        }
        // Android hands every app but the dialer a silent microphone during a call.
        if (CarCalls.state.value is CallUiState.Active) {
            _state.value = State(Phase.ERROR, reply = "Voice commands work once the call ends.")
            return
        }
        VoiceEngine.stop()
        releaseSpeechFocus()
        synchronized(streamLock) { streamSession = null }
        clearFollowUpExpiry()
        // r72 barge-in: this fresh listen supersedes any turn still running — a
        // reply playing (SPEAKING falls through to here) or an answer still
        // streaming in. Its late callbacks die on the generation guard, and its
        // reply is discarded by the CarUi collector.
        turnGate.beginTurn()
        warmForListen(context)
        _state.value = State(Phase.LISTENING)
        busySinceMs = SystemClock.uptimeMillis()
        listenOnce(
            onListening = { _state.value = _state.value.copy(ready = true) },
            onPartial = { text -> _state.value = _state.value.copy(heard = text) },
            onHeard = {
                _state.value = _state.value.copy(phase = Phase.THINKING)
                busySinceMs = SystemClock.uptimeMillis()
            },
            onResult = { text ->
                lastListenEndMs = SystemClock.elapsedRealtime()
                GearslipLog.i("assistant: latency listen-end")
                _state.value = _state.value.copy(phase = Phase.THINKING, heard = text)
                busySinceMs = SystemClock.uptimeMillis()
                _pendingCommand.value = text
            },
            onError = { message ->
                _state.value = State(Phase.ERROR, reply = message)
                fireHeldNavigation()
            },
        )
    }

    /**
     * Listen-phase warm-up (r55): the voice engine loads while the
     * user talks, and — only when an AI turn on Groq is possible —
     * the provider connection opens with a zero-token models GET
     * (never a prompt) on a throwaway thread, so the turn reuses a
     * warm socket. Failures are swallowed inside the warm-up calls;
     * warming must never fail a listen.
     */
    private fun warmForListen(context: Context) {
        VoiceEngine.warm(context)
        if (AiKeyStore.aiAvailable(context) &&
            AiKeyStore.activeProvider(context) == AiAssistant.Provider.GROQ
        ) {
            val key = AiKeyStore.loadKey(context, AiAssistant.Provider.GROQ) ?: return
            Thread({ runCatching { GroqClient.warmConnection(key) } }, "gearslip-warmup").start()
        }
    }

    /** Closes the overlay from wherever it's showing: mid-listen, mid-think, or after speaking. */
    fun cancel() {
        clearFollowUpExpiry()
        dropHeldNavigation()
        // r72 barge-in: same supersede as a fresh listen — a dismissed turn's
        // late stream callbacks must never re-speak after the overlay closes.
        turnGate.beginTurn()
        releaseRecognizer()
        VoiceEngine.stop()
        releaseSpeechFocus()
        synchronized(streamLock) { streamSession = null }
        _pendingCommand.value = null
        AssistantCommands.clearPendingAiAction()
        _state.value = State(Phase.IDLE)
    }

    // r57: post-response listen window. After a reply finishes
    // speaking, the assistant keeps listening this long for a
    // follow-up instead of dropping straight to IDLE, so a driver
    // can have a little conversation without re-invoking. One
    // tunable constant. Speech in the window starts a fresh turn
    // exactly like a fresh listen (no wake word, no button); the
    // window's own expiry, or a listen error, ends the turn
    // quietly at IDLE — the reply already succeeded, so a missed
    // follow-up is never worth an error card. The timer resets on
    // every partial, so it can never cut off a live utterance:
    // only five seconds with no partials at all ends the window,
    // by which point the engine's own endpointing has already
    // ended any real utterance. Single-turn semantics are
    // preserved: a result lands in pendingCommand exactly like any
    // other turn, with no history carried over (conversational
    // memory is the later A59 multi-turn round, not this one).
    private const val FOLLOW_UP_LISTEN_MS = 5_000L
    private var followUpGeneration = 0
    private var followUpExpiry: Runnable? = null

    // r96: one held voice-navigation request. A voice navigate command holds
    // its connectNav + home here instead of firing at once, so Vela's
    // guidance speech cannot land in the post-reply listen window and be
    // heard as a follow-up (there is no AEC: the mic is VOICE_RECOGNITION
    // and TTS plays through the car speakers). The hold fires exactly once,
    // at the first turn-ending moment after its window: window expiry, the
    // window bailing (call active), a window listen error, or a turn end
    // that opens no window (blank, failed, or unplayable reply; fresh-listen
    // error). It is dropped only by an explicit cancel: cancel(), cancel
    // words, or "stop navigation". A fresh listen disarms the window expiry
    // but NOT the hold, so the request rides the new turn: a new navigate
    // supersedes it, a non-navigation follow-up leaves it to fire at the new
    // turn's window end, a cancel drops it. Tap paths call
    // CarServices.connectNav directly and never touch the hold, so they
    // still fire at once. All access is main-thread only, matching every
    // caller (the CarUi collector, the window expiry runnable, cancel()).
    // The destination is never logged, only held/fired/dropped.
    private val navHold = NavHold()

    /** Holds a voice navigate request until the listen window closes. Replaces any request still held. */
    fun holdNavigation(action: () -> Unit) {
        navHold.hold(action)
        GearslipLog.i("assistant: nav held until window closes")
    }

    /** Drops a held navigate request, if any; true when one was dropped. */
    fun dropHeldNavigation(): Boolean {
        val dropped = navHold.drop()
        if (dropped) GearslipLog.i("assistant: held nav dropped")
        return dropped
    }

    /** Fires the held navigate request once, if any. */
    private fun fireHeldNavigation() {
        if (!navHold.hasPending) return
        GearslipLog.i("assistant: held nav fired")
        navHold.fire()
    }

    /** Disarms any pending window expiry; the window state itself is left to its caller. */
    private fun clearFollowUpExpiry() {
        followUpGeneration++
        followUpExpiry?.let { mainHandler.removeCallbacks(it) }
        followUpExpiry = null
    }

    /**
     * Opens the post-reply listen window: phase LISTENING with
     * [State.followUp] set, so the overlay reads "Still listening…"
     * while the window is open. Callbacks mirror a fresh listen,
     * except a failed listen ends quietly at IDLE instead of an
     * error state. A call starting mid-reply also ends the turn
     * at IDLE, the same way a fresh listen refuses during a call.
     */
    private fun openFollowUpWindow() {
        if (appContext == null || CarCalls.state.value is CallUiState.Active) {
            _state.value = _state.value.copy(phase = Phase.IDLE)
            fireHeldNavigation()
            return
        }
        val generation = ++followUpGeneration
        _state.value = _state.value.copy(
            phase = Phase.LISTENING,
            heard = "",
            ready = false,
            followUp = true,
        )
        busySinceMs = SystemClock.uptimeMillis()
        val expiry = Runnable {
            if (followUpGeneration != generation) return@Runnable
            followUpExpiry = null
            releaseRecognizer()
            _state.value = State(Phase.IDLE)
            fireHeldNavigation()
        }
        followUpExpiry = expiry
        mainHandler.postDelayed(expiry, FOLLOW_UP_LISTEN_MS)
        listenOnce(
            onListening = { _state.value = _state.value.copy(ready = true) },
            onPartial = { text ->
                // Live speech keeps resetting the timer; only a
                // stretch with no partials at all can expire it.
                if (followUpGeneration == generation) {
                    followUpExpiry?.let { mainHandler.removeCallbacks(it) }
                    mainHandler.postDelayed(expiry, FOLLOW_UP_LISTEN_MS)
                }
                _state.value = _state.value.copy(heard = text)
            },
            onHeard = {
                clearFollowUpExpiry()
                _state.value = _state.value.copy(phase = Phase.THINKING, followUp = false)
                busySinceMs = SystemClock.uptimeMillis()
            },
            onResult = { text ->
                clearFollowUpExpiry()
                lastListenEndMs = SystemClock.elapsedRealtime()
                GearslipLog.i("assistant: latency listen-end")
                _state.value = _state.value.copy(phase = Phase.THINKING, heard = text, followUp = false)
                busySinceMs = SystemClock.uptimeMillis()
                _pendingCommand.value = text
            },
            onError = {
                clearFollowUpExpiry()
                releaseRecognizer()
                _state.value = State(Phase.IDLE)
                fireHeldNavigation()
            },
        )
    }

    /**
     * Called once [pendingCommand] has been turned into an answer - from [CarUi], the one place
     * that actually has a [CarNavigator] to have acted on it with. With no
     * voice model downloaded, the overlay says so and points at Settings
     * instead of leaving the driver waiting on silence.
     */
    fun reply(
        text: String,
        fromAi: Boolean = false,
        grounded: Boolean = false,
        sourceCount: Int = 0,
        provider: AiAssistant.Provider = AiAssistant.Provider.GEMINI,
    ) {
        _pendingCommand.value = null
        val context = appContext
        if (text.isBlank() || context == null) {
            _state.value = _state.value.copy(phase = Phase.IDLE)
            fireHeldNavigation()
            return
        }
        // r55 streamed finish: this turn already spoke its sentences
        // as they arrived. When the final text is exactly the capped
        // streamed content, finish the stream (speaking any cap
        // suffix owed) instead of playing the whole text again — a
        // re-play would speak every sentence twice. Any other shape
        // (failed stream, or text that does not match) falls through
        // to the normal play path, or to the error state for a failed
        // stream, so a mismatch can never strand or double speech.
        // Focus is already held from the stream start and is released
        // by the stream's own onDone; nothing re-acquires it here.
        when (val finish = takeStreamFinish(text)) {
            is StreamFinish.Done -> {
                _state.value = _state.value.copy(phase = Phase.SPEAKING, reply = text, fromAi = fromAi, grounded = grounded, sourceCount = sourceCount, provider = provider)
                lastAssistantReply = text
                return
            }
            is StreamFinish.Failed -> {
                _state.value = State(
                    Phase.ERROR,
                    reply = if (finish.noEngine) {
                        "No voice downloaded yet. Download Kokoro or Piper in Settings first."
                    } else {
                        speechFailureMessage()
                    },
                )
                fireHeldNavigation()
                return
            }
            is StreamFinish.Mismatch -> Unit // Falls through to normal play below.
            is StreamFinish.None -> Unit // No stream session: normal play below.
        }
        _state.value = _state.value.copy(phase = Phase.SPEAKING, reply = text, fromAi = fromAi, grounded = grounded, sourceCount = sourceCount, provider = provider)
        val started = VoiceEngine.play(context, text, onDone = {
            releaseSpeechFocus()
            openFollowUpWindow()
        }, onSilent = {
            // 2026-10-25 round 3: the old text blamed the voice files
            // for every silent render — but onSilent fires only after
            // an engine passed validation (play() returns false when
            // none is Ready), so for a generate/playback failure the
            // files are verified good and re-downloading them cannot
            // help. Name the recorded stage instead; keep the files
            // pointer only for a load failure, where the files
            // genuinely are the suspect, and stay neutral when no
            // stage was recorded rather than blaming the files.
            // (r55: the message itself lives in speechFailureMessage,
            // shared with the streamed session's onSilent.)
            _state.value = State(Phase.ERROR, reply = speechFailureMessage())
            fireHeldNavigation()
        })
        if (started) {
            // Audit fix (2026-10-25, A1): the last reply that
            // actually started playing, for "say that again".
            // VoiceReply speaks through say(), never reply(), so
            // message text can never land here — only the
            // assistant's own words. A blank reply returns early
            // above and never overwrites this.
            lastAssistantReply = text
            acquireSpeechFocus(context)
        } else {
            _state.value = State(
                Phase.ERROR,
                reply = "No voice downloaded yet. Download Kokoro or Piper in Settings first.",
            )
            fireHeldNavigation()
        }
    }

    /**
     * Speaks [text] for someone other than the assistant (a message read aloud), then runs
     * [onDone] on the main thread - straight away if speech isn't available.
     */
    fun say(text: String, onDone: () -> Unit, onSilent: () -> Unit = {}) {
        val context = appContext
        if (context == null || text.isBlank()) {
            mainHandler.post(onDone)
            return
        }
        // VoiceReply widening (2026-10-25): VoiceReply speaks ONLY
        // through say(), so it shares the assistant reply's spoken
        // path exactly — including the crash family (a native generate
        // on a corrupt set) and the silent-render case. The P1
        // validation + markers + fallback already cover say() because
        // it funnels through the same play()/synthesizeNow; [onSilent]
        // additionally lets VoiceReply notice a render that produced
        // nothing instead of carrying on as if the driver heard it.
        // A started=false reply (no engine Ready) still just ends the
        // step via onDone, as before — the no-voice warning lives in
        // the assistant overlay, not in the message card.
        val started = VoiceEngine.play(context, text, onDone = {
            releaseSpeechFocus()
            mainHandler.post(onDone)
        }, onSilent = {
            mainHandler.post(onSilent)
        })
        if (started) {
            acquireSpeechFocus(context)
        } else {
            mainHandler.post(onDone)
        }
    }

    fun stopSpeaking() {
        VoiceEngine.stop()
        releaseSpeechFocus()
        synchronized(streamLock) { streamSession = null }
    }

    /**
     * The spoken-failure message for a render that produced no audio
     * (r55: shared by reply()'s onSilent and the streamed session's
     * onSilent, so both name the same stage). Only for a generate /
     * playback failure — a load failure names the voice files, and a
     * missing engine never reaches here (play() returns false first).
     */
    private fun speechFailureMessage(): String {
        val reason = VoiceEngine.lastFailure
        return when {
            reason == null -> "Couldn't speak that reply — the voice render produced no audio."
            reason.contains("load") || reason.contains("unavailable") ->
                "Couldn't speak that reply. Check the voice files in Settings — " +
                    "delete and re-download Kokoro or Piper if they look incomplete."
            else -> "Couldn't speak that reply — voice render failed ($reason). " +
                "The voice files passed validation, so re-downloading them won't help."
        }
    }

    /**
     * Progressive display text for a streaming prose answer. Creates
     * the turn's session on first call; ignored with no app context.
     * The phase is untouched here — THINKING until the first sentence
     * starts speech — and the overlay shows the growing reply during
     * THINKING once it is non-blank.
     * r72 barge-in: [generation] is the turn that started the stream —
     * a superseded turn's late text returns before touching the session.
     */
    fun streamText(generation: Int, full: String) {
        val context = appContext ?: return
        // r72 barge-in: a superseded turn's late text dies here, before it can
        // touch (or recreate) the session the new listen already cleared.
        if (!turnGate.isCurrent(generation)) return
        synchronized(streamLock) {
            val session = streamSession ?: StreamSession(context).also { streamSession = it }
            session.fullText.clear()
            session.fullText.append(full)
        }
        _state.value = _state.value.copy(reply = full)
    }

    /**
     * One complete streamed sentence. Starts the speech stream on the
     * first accepted sentence (phase SPEAKING, focus held) and offers
     * later ones to it; sentences past the answer cap are dropped,
     * exactly as the capped reply drops them. Ignored with no app
     * context, or once the session failed.
     * r72 barge-in: [generation] is the turn that started the stream —
     * a superseded turn's late sentence returns before a session is
     * created, so it can never recreate one the new listen cleared.
     */
    fun streamSentence(generation: Int, sentence: String) {
        val context = appContext ?: return
        // r72 barge-in: checked before a session is created, for the same reason
        // as streamText — a superseded turn must never recreate a session.
        if (!turnGate.isCurrent(generation)) return
        val handle = synchronized(streamLock) {
            val session = streamSession ?: StreamSession(context).also { streamSession = it }
            if (session.failed) return
            if (!session.emitter.offer(sentence)) return
            if (!session.speechStarted) {
                val stream = VoiceEngine.playStreamed(
                    context, lastListenEndMs,
                    onDone = {
                        releaseSpeechFocus()
                        openFollowUpWindow()
                    },
                    onSilent = {
                        // The worker is dead after this — mark the
                        // session failed so reply()'s finish check
                        // takes the error path instead of finishing a
                        // dead stream and stranding SPEAKING (its
                        // onDone will never arrive). The captured
                        // session is this turn's, never a later one:
                        // a new listen clears the field first.
                        synchronized(streamLock) { session.failed = true }
                        _state.value = State(Phase.ERROR, reply = speechFailureMessage())
                    },
                )
                if (stream == null) {
                    session.failed = true
                    session.noEngine = true
                    return
                }
                session.stream = stream
                session.speechStarted = true
                acquireSpeechFocus(context)
                _state.value = _state.value.copy(
                    phase = Phase.SPEAKING,
                    reply = session.fullText.toString(),
                    fromAi = true,
                )
            }
            session.stream
        } ?: return
        handle.offer(sentence)
    }

    /** True while a streamed turn owns a session (used to abort it on failure). */
    fun streamingActive(): Boolean = synchronized(streamLock) { streamSession != null }

    /**
     * Aborts a streamed turn that failed mid-stream (r55): stops any
     * partial speech, releases focus, and clears the session, so the
     * failure message that follows speaks through the normal path
     * instead of finishing a dead stream. The already-spoken prefix
     * stands — a failed stream never re-speaks it.
     */
    fun abortStream() {
        synchronized(streamLock) { streamSession = null }
        VoiceEngine.stop()
        releaseSpeechFocus()
    }

    private sealed class StreamFinish {
        object Done : StreamFinish()
        data class Failed(val noEngine: Boolean) : StreamFinish()
        object Mismatch : StreamFinish()
        object None : StreamFinish()
    }

    /**
     * Consumes the streamed session for [text], if one exists. Done
     * only when the final text is exactly the capped streamed
     * content: the session's spoken prefix plus any remainder owed
     * (the cap suffix) is offered as a final chunk and the stream is
     * finished. Failed when the stream already failed (no engine, or
     * a render/playback failure) — the error state stands and nothing
     * re-speaks. Mismatch stops the stream and clears the session so
     * the caller falls through to normal play. Never leaves a session
     * behind: every path consumes it.
     */
    private fun takeStreamFinish(text: String): StreamFinish {
        val session = synchronized(streamLock) {
            val s = streamSession
            streamSession = null
            s
        } ?: return StreamFinish.None
        if (session.failed) return StreamFinish.Failed(session.noEngine)
        if (AiAssistant.capAnswer(session.fullText.toString()) != text) {
            VoiceEngine.stop()
            return StreamFinish.Mismatch
        }
        val stream = session.stream ?: return StreamFinish.Mismatch
        val remainder = session.emitter.finishRemainder(text)
        if (remainder.isNotBlank()) stream.offer(remainder)
        stream.finish()
        return StreamFinish.Done
    }

    /**
     * Listens once, the same way the assistant does, and hands back what was said without acting
     * on it. Callbacks arrive on the main thread; [onError] gets a sentence fit to show.
     */
    fun dictate(
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        cue: Boolean = true,
        onSpeaking: () -> Unit = {},
    ) {
        cancel()
        listenOnce(onPartial, onHeard = {}, onResult = onResult, onError = onError, cue = cue, onSpeaking = onSpeaking)
    }

    /**
     * One phrase, through Gearslip's own [SpeechEngine], or the phone's recognizer when the driver
     * chose it in settings. Never the phone's on its own: on most phones that's Google's.
     */
    private fun listenOnce(
        onPartial: (String) -> Unit,
        onListening: () -> Unit = {},
        onHeard: () -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        cue: Boolean = true,
        onSpeaking: () -> Unit = {},
    ) {
        val context = appContext ?: return onError("Speech isn't ready yet.")
        if (CarSettings.systemSpeech.value && SpeechRecognizer.isRecognitionAvailable(context)) {
            return systemListen(context, onListening, onPartial, onHeard, onResult, onError)
        }
        SpeechEngine.listen(
            context,
            cue = cue,
            onSpeaking = onSpeaking,
            onPartial = onPartial,
            onListening = onListening,
            onHeard = onHeard,
            onResult = onResult,
            onError = onError,
        )
    }

    private fun systemListen(
        context: Context,
        onListening: () -> Unit,
        onPartial: (String) -> Unit,
        onHeard: () -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        // Same flag the sherpa path sets in SpeechEngine.listen: the hotword loop
        // closes its microphone on it, so the two never hold the car mic at once.
        // (The sherpa path's worker waits for the release; this path relies on the
        // recognizer opening its microphone after the hotword loop's next chunk.)
        SpeechEngine.markListeningStart()
        systemListening = true
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onListening()
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = onHeard()
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                releaseRecognizer()
                onError(errorMessage(error))
            }

            override fun onResults(results: Bundle?) {
                releaseRecognizer()
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isBlank()) onError("Didn't catch that.") else onResult(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            },
        )
        // r44: the system recognizer has no timeout of its own — a
        // wedged service used to leave the assistant parked in
        // LISTENING with the hotword gated off. The watchdog ends it
        // with an error instead; releaseRecognizer() cancels it, so a
        // normally-ending listen never fires it.
        val watchdog = Runnable {
            if (systemListening) {
                GearslipLog.w("assistant: system recognizer watchdog fired")
                releaseRecognizer()
                onError("Speech recognition failed.")
            }
        }
        systemWatchdog = watchdog
        mainHandler.postDelayed(watchdog, ListenWatchdogPolicy.SYSTEM_LISTEN_TIMEOUT_MS)
    }

    fun stopListening() = releaseRecognizer()

    private fun releaseRecognizer() {
        systemWatchdog?.let { mainHandler.removeCallbacks(it) }
        systemWatchdog = null
        SpeechEngine.cancel()
        recognizer?.let { runCatching { it.cancel() }; it.destroy() }
        recognizer = null
        if (systemListening) {
            systemListening = false
            SpeechEngine.markListeningEnd()
        }
    }

    private fun errorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Needs the microphone permission."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech recognizer is busy."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "The speech recognizer needs a network it doesn't have."
        else -> "Speech recognition failed."
    }

    fun shutdown() {
        releaseRecognizer()
        // r72 barge-in: a torn-down session's late callbacks die with it.
        turnGate.beginTurn()
        VoiceEngine.unload()
        // r44: shutdown used to leave the phase wherever it was, so a
        // parked LISTENING/THINKING greeted the next session.
        _state.value = State(Phase.IDLE)
        _pendingCommand.value = null
    }
}
