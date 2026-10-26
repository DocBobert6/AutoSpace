package app.autospace.car

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import app.autospace.call.CallUiState
import app.autospace.call.CarCalls
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
        /** True when the reply being shown is an AI answer (r25 badge); local replies never set this. */
        val fromAi: Boolean = false,
        /**
         * True when the reply being shown is a grounded AI answer (r27 source line);
         * [sourceCount] is how many web sources backed it. Local replies never set these.
         */
        val grounded: Boolean = false,
        val sourceCount: Int = 0,
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
    // Audit fix (2026-10-25, V2): the system-speech path owns one
    // listeningActive count from markListeningStart() until the
    // recognizer is released. The old code decremented only in the
    // result/error callbacks, so a release without a callback (cancel,
    // teardown) left the count stuck and gated the hotword off.
    // releaseRecognizer() now owns the decrement, exactly once.
    private var systemListening = false

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
        if (_state.value.phase == Phase.LISTENING || _state.value.phase == Phase.THINKING) return

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
        _state.value = State(Phase.LISTENING)
        listenOnce(
            onListening = { _state.value = _state.value.copy(ready = true) },
            onPartial = { text -> _state.value = _state.value.copy(heard = text) },
            onHeard = { _state.value = _state.value.copy(phase = Phase.THINKING) },
            onResult = { text ->
                _state.value = _state.value.copy(phase = Phase.THINKING, heard = text)
                _pendingCommand.value = text
            },
            onError = { message -> _state.value = State(Phase.ERROR, reply = message) },
        )
    }

    /** Closes the overlay from wherever it's showing: mid-listen, mid-think, or after speaking. */
    fun cancel() {
        releaseRecognizer()
        VoiceEngine.stop()
        releaseSpeechFocus()
        _pendingCommand.value = null
        AssistantCommands.clearPendingAiAction()
        _state.value = State(Phase.IDLE)
    }

    /**
     * Called once [pendingCommand] has been turned into an answer - from [CarUi], the one place
     * that actually has a [CarNavigator] to have acted on it with. With no
     * voice model downloaded, the overlay says so and points at Settings
     * instead of leaving the driver waiting on silence.
     */
    fun reply(text: String, fromAi: Boolean = false, grounded: Boolean = false, sourceCount: Int = 0) {
        _pendingCommand.value = null
        val context = appContext
        if (text.isBlank() || context == null) {
            _state.value = _state.value.copy(phase = Phase.IDLE)
            return
        }
        _state.value = _state.value.copy(phase = Phase.SPEAKING, reply = text, fromAi = fromAi, grounded = grounded, sourceCount = sourceCount)
        val started = VoiceEngine.play(context, text, onDone = {
            releaseSpeechFocus()
            _state.value = _state.value.copy(phase = Phase.IDLE)
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
            val reason = VoiceEngine.lastFailure
            val message = when {
                reason == null -> "Couldn't speak that reply — the voice render produced no audio."
                reason.contains("load") || reason.contains("unavailable") ->
                    "Couldn't speak that reply. Check the voice files in Settings — " +
                        "delete and re-download Kokoro or Piper if they look incomplete."
                else -> "Couldn't speak that reply — voice render failed ($reason). " +
                    "The voice files passed validation, so re-downloading them won't help."
            }
            _state.value = State(Phase.ERROR, reply = message)
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
    }

    fun stopListening() = releaseRecognizer()

    private fun releaseRecognizer() {
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
        VoiceEngine.unload()
    }
}
