package app.autospace.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.autospace.GearslipLog
import app.autospace.audio.CarMic
import app.autospace.car.CarSettings
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Gearslip's own speech-to-text, so voice works on phones with no speech service (GrapheneOS ships
 * none). sherpa-onnx runs a streaming model ([SpeechModels]) on the phone: words come out while
 * the driver is still talking, and the model itself decides when they've finished. A larger model
 * then reads the whole phrase once more for the final words, since a model that waits for the end
 * hears names and places far better than one that has to keep up. The phrase is held in memory
 * for that and nowhere else, and dropped as soon as it's read.
 *
 * Audio comes from the car's microphone, or the phone's when the car has none or sends nothing,
 * or always the phone's when the phone-microphone setting is on (CarSettings.phoneMicForVoice).
 * Everything runs on one worker thread; callbacks arrive on the main thread.
 */
object SpeechEngine {

    /** The error for an engine that can't run at all, which callers may answer with a fallback. */
    const val NOT_LOADED = "The speech model didn't load."

    /** Said when the chosen model hasn't been downloaded yet. */
    const val NOT_DOWNLOADED = "Download a voice model in AutoSpace's settings on the phone first."

    private const val RATE = 16_000
    private const val CHUNK = 1_600 // 100 ms
    private const val MAX_S = 15f
    /** Quiet before the first word that counts as "nobody spoke". */
    private const val NO_SPEECH_S = 6f
    /** Quiet after the last word that ends the phrase. */
    private const val END_SILENCE_S = 1.0f
    private const val CUE_VOLUME = 80
    private const val IDLE_UNLOAD_MS = 5 * 60_000L
    /** How long the car gets to start sending once asked, before the phone's microphone takes over. */
    private const val CAR_FIRST_AUDIO_MS = 1_500L
    private const val CAR_GAP_MS = 1_000L
    /** The phone mic's poll deadline: the car-mic gap discipline, applied to the phone's reads. */
    private const val PHONE_POLL_MS = 1_000L
    private const val CUE_MS = 150
    /** The car plays what the phone sends a moment late; waiting this out keeps the beep off the recording. */
    private const val CUE_TAIL_MS = 250L

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "gearslip-speech") }
    /**
     * The final model takes seconds to load. On [worker], the first press after connecting
     * waited behind it before listening at all; here it loads while the driver talks.
     */
    private val finalWorker = Executors.newSingleThreadExecutor { Thread(it, "gearslip-speech-final") }
    private val finalLock = Object()
    private val main = Handler(Looper.getMainLooper())

    private var recognizer: OnlineRecognizer? = null
    private var loadedModel: SpeechModels.Model? = null
    @Volatile private var finalRecognizer: OfflineRecognizer? = null
    @Volatile private var loadedFinal: SpeechModels.Model? = null

    /**
     * The final model holds most of the memory the two models take, so it goes after a few
     * minutes without a command. Kroko stays: it's small, and keeps listening instant.
     */
    private val idleUnload = Runnable { unloadFinal("after ${IDLE_UNLOAD_MS / 60_000} minutes without use") }
    @Volatile private var run = 0
    /**
     * r44: the id of the most recent listen() call. A task that ends
     * superseded tells its caller apart by this: bumped by a newer
     * listen, it stays silent (the new listen drives the caller);
     * bumped by cancel() alone, it owes the caller an error.
     */
    @Volatile private var latestListenId = 0
    /** r44: the mic the running listen task opened, so the watchdog can close it from the main thread. */
    @Volatile private var activeMic: Mic? = null

    /**
     * True while a button-press (or dictated) phrase is being listened for. [HotwordListener]
     * watches this and closes its own microphone before a phrase starts, so the two never
     * hold the car's single microphone channel at once; it is cleared when the phrase's
     * worker task ends, before its result is posted. A counter, not a bare boolean: two
     * rapid listens must not let the first one's end clear the second one's flag.
     */
    private val listeningCount = java.util.concurrent.atomic.AtomicInteger(0)
    internal val listeningActive: Boolean get() = listeningCount.get() > 0
    internal fun markListeningStart() { listeningCount.incrementAndGet() }
    internal fun markListeningEnd() { if (listeningCount.get() > 0) listeningCount.decrementAndGet() }

    /** Loads the model ahead of time, so the first command doesn't wait for it. */
    fun warm(context: Context) {
        val app = context.applicationContext
        SpeechModels.init(app)
        // Kroko first, alone: loading both at once slowed it from a fraction of a second to six.
        worker.execute {
            ensureLoaded(app)
            finalWorker.execute { ensureFinalLoaded(app) }
        }
    }

    private fun unloadFinal(why: String) {
        finalWorker.execute {
            synchronized(finalLock) {
                val model = loadedFinal ?: return@execute
                finalRecognizer?.release()
                finalRecognizer = null
                loadedFinal = null
                GearslipLog.i("speech: unloaded ${model.label} $why")
            }
        }
    }

    /** Drops the loaded models: the driver picked another or deleted one, or the car went away. */
    fun unload() {
        // r28: a session ending also forgets what it learned about the
        // microphones — deadness is re-proven next session, never carried
        // over. (Model swaps mid-session also call unload; re-proving
        // then costs at most one more fallback hold, which is the safe
        // direction: a stale "dead" must never outlive its evidence.)
        carMicDead = false
        hotwordMicOverride.clear()
        main.removeCallbacks(idleUnload)
        run++
        worker.execute {
            recognizer?.release()
            recognizer = null
            loadedModel = null
        }
        finalWorker.execute {
            synchronized(finalLock) {
                finalRecognizer?.release()
                finalRecognizer = null
                loadedFinal = null
            }
        }
    }

    /**
     * Listens for one phrase. [cue] plays a short tone first, so the driver knows when to talk.
     * [onPartial] gets the words so far as they're recognized, [onSpeaking] fires at the first
     * word, [onHeard] when the driver stops; [onResult] gets the text, [onError] a sentence fit to
     * show on the car screen.
     */
    fun listen(
        context: Context,
        cue: Boolean = true,
        onSpeaking: () -> Unit = {},
        onPartial: (String) -> Unit = {},
        onListening: () -> Unit = {},
        onHeard: () -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val app = context.applicationContext
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError("Needs the microphone permission.")
            return
        }
        val id = ++run
        latestListenId = id
        // r44: exactly one terminal callback per listen, ever. The
        // normal posts keep their run guard; the watchdog's and the
        // superseded task's posts use the gate alone, because by
        // definition the run has already moved past them.
        val delivered = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        fun post(block: () -> Unit) { main.post { if (id == run) block() } }
        fun postTerminal(block: () -> Unit) {
            main.post { if (id == run && delivered.compareAndSet(false, true)) block() }
        }
        fun postSuperseded() {
            main.post { if (delivered.compareAndSet(false, true)) onError("Stopped.") }
        }
        // Brings Parakeet back if it was unloaded, while the driver talks.
        main.removeCallbacks(idleUnload)
        finalWorker.execute { ensureFinalLoaded(app) }
        markListeningStart()
        // r44: the whole-task watchdog. A listen that is still going
        // at the deadline is wedged (the unbounded phone-mic read was
        // the proven shape); invalidate it, close its mic, and end
        // the caller with an error instead of parking it in LISTENING
        // until the process dies. The task's finally cancels this
        // check and sets finished, so a normally-ending task never
        // fires it; the finished flag covers the firing race itself.
        val watchdog = ListenWatchdog(onFire = {
            // Only the still-current task may fire: if a newer
            // listen or a cancel already bumped the run, that owner
            // answers the caller, and this watchdog must not bump
            // past the new task's id.
            if (id == run) {
                run++
                activeMic?.let { mic -> runCatching { mic.close() } }
                GearslipLog.w("speech: listen watchdog fired for run $id after ${ListenWatchdogPolicy.LISTEN_WATCHDOG_MS} ms")
                main.post { if (delivered.compareAndSet(false, true)) onError("The microphone isn't responding — try again.") }
            }
        })
        watchdog.arm(SystemClock.uptimeMillis())
        val watchdogCheck = Runnable { if (!finished.get()) watchdog.check(SystemClock.uptimeMillis()) }
        main.postDelayed(watchdogCheck, ListenWatchdogPolicy.LISTEN_WATCHDOG_MS)
        worker.execute {
            try {
            if (id != run) {
                // r44: superseded with no replacement — the caller is
                // owed an error, not silence (see latestListenId).
                if (shouldDeliverSupersedeError(id, latestListenId, run)) postSuperseded()
                return@execute
            }
            if (!SpeechModels.isReady(app, SpeechModels.selected.value)) return@execute postTerminal { onError(NOT_DOWNLOADED) }
            val r = ensureLoaded(app) ?: return@execute postTerminal { onError(NOT_LOADED) }
            // The hotword loop closes its microphone on [listeningActive] within one
            // 100 ms chunk; wait for that before opening this phrase's microphone, so
            // the car's single microphone channel is never held by both at once.
            HotwordListener.waitForMicRelease()
            VoiceFocus.acquire(app)
            val text = try {
                if (cue) cue()
                if (id != run) {
                    if (shouldDeliverSupersedeError(id, latestListenId, run)) postSuperseded()
                    return@execute
                }
                post(onListening)
                stream(app, r, id, onSpeaking = { post(onSpeaking) }, onPartial = { post { onPartial(it) } }, onStopped = { post(onHeard) })
            } catch (t: Throwable) {
                GearslipLog.e("speech: listening failed", t)
                return@execute postTerminal { onError("The microphone isn't available right now.") }
            } finally {
                VoiceFocus.release()
            }
            main.removeCallbacks(idleUnload)
            main.postDelayed(idleUnload, IDLE_UNLOAD_MS)
            if (id != run) {
                if (shouldDeliverSupersedeError(id, latestListenId, run)) postSuperseded()
                return@execute
            }
            // Audit fix (2026-10-25, V3): onHeard was posted twice —
            // once via stream()'s onStopped callback above and once
            // here. The onStopped post is the exactly-once signal;
            // this second post is dropped so callers see it once.
            postTerminal { if (text.isEmpty()) onError("Didn't catch that.") else onResult(text) }
            } finally {
                // Cleared when the phrase's worker task ends, before its result posts;
                // the assistant's own phase (LISTENING/THINKING/SPEAKING) keeps the
                // hotword suspended through the reply that follows.
                // r44: finished + watchdog cancel first, so a normally-ending
                // task can never trip its own watchdog on the main thread.
                finished.set(true)
                main.removeCallbacks(watchdogCheck)
                markListeningEnd()
            }
        }
    }

    /** Stops listening; a phrase already being worked out is dropped. */
    fun cancel() { run++ }

    /**
     * Feeds the microphone to the recognizer in 100 ms pieces, reporting the words as they firm
     * up, until the model hears the end of the phrase (or nobody speaks, or it runs too long).
     */
    private fun stream(
        context: Context,
        r: OnlineRecognizer,
        id: Int,
        onSpeaking: () -> Unit,
        onPartial: (String) -> Unit,
        onStopped: () -> Unit,
    ): String {
        // One selection point for every bundled-engine voice path
        // (CarAssistant commands, VoiceReply replies, the phone preview's
        // one-shot listens): see [openListenMic]. The system-speech path
        // bypasses this and keeps the phone recognizer's own routing.
        val (mic, source) = openListenMic()
        // r44: the watchdog closes this from the main thread on expiry.
        activeMic = mic
        val s = r.createStream()
        val frame = ShortArray(CHUNK)
        val samples = FloatArray(CHUNK)
        // The whole phrase, for the final read; [MAX_S] and a little over is the most it can hold.
        val phrase = FloatArray((RATE * (MAX_S + 1)).toInt())
        var held = 0
        var last = ""
        var spoke = false
        var audioMs = 0L
        var decodeMs = 0L
        // r28: the button path's answer to the hotword's silence rescue.
        // A dead-but-streaming car mic used to win every button listen
        // forever (it sends frames, so the 1500 ms first-audio fallback
        // never fires). If this whole listen was digital silence on the
        // car mic and nothing was recognized, the mic is dead by the
        // same proof the hotword already ships — mark it, so the next
        // press uses the phone's mic instead of failing identically.
        val silence = SilenceTracker()
        try {
            while (id == run) {
                val n = mic.read(frame)
                if (n < 0) break
                if (n == 0) continue
                if (source == MicSource.CAR) silence.add(frame, n)
                for (i in 0 until n) samples[i] = frame[i] / 32768f
                val keep = minOf(n, phrase.size - held)
                System.arraycopy(samples, 0, phrase, held, keep)
                held += keep
                s.acceptWaveform(if (n == CHUNK) samples else samples.copyOf(n), RATE)
                audioMs += n * 1000L / RATE
                val started = SystemClock.uptimeMillis()
                while (r.isReady(s)) r.decode(s)
                decodeMs += SystemClock.uptimeMillis() - started
                val text = clean(r.getResult(s).text)
                if (text != last) {
                    last = text
                    if (text.isNotEmpty() && !spoke) {
                        spoke = true
                        onSpeaking()
                    }
                    onPartial(text)
                }
                if (r.isEndpoint(s)) break
            }
            s.inputFinished()
            while (r.isReady(s)) r.decode(s)
            last = clean(r.getResult(s).text)
        } finally {
            // r44: cleared only if this task's mic is still the active
            // one — a replacement worker's task must not lose its mic
            // to this task's late finally.
            if (activeMic === mic) activeMic = null
            mic.close()
            s.release()
        }
        // The guards, in order: the car mic (a phone mic cannot prove
        // anything about the car), the listen actually ran to an end
        // (a cancelled press proves nothing), at least one chunk was
        // seen (a zero-frame listen is vacuous), every frame was
        // digitally silent, nothing was recognized even on the final
        // read, and the listen lasted past a trivial tap (2 s of
        // audio). A working mic fails the silence clause on its noise
        // floor alone, so this cannot trip on a quiet cabin.
        if (source == MicSource.CAR && id == run && silence.chunksSeen > 0 &&
            silence.allSilent && last.isEmpty() && audioMs >= 2_000L
        ) {
            noteCarMicDead()
        }
        // Under 1.0 keeps up with the driver; over it, the words fall behind the voice.
        GearslipLog.i(
            "speech: $audioMs ms of audio, decoding took $decodeMs ms " +
                "(${"%.2f".format(decodeMs.toFloat() / max(1L, audioMs))} of real time)",
        )
        if (last.isEmpty() || id != run) { phrase.fill(0f); return last }
        // Still loading (just connected, or back after going idle): the live words now beat
        // Parakeet's in several seconds.
        val wanted = SpeechModels.final.value
        if (wanted == null || finalRecognizer == null || loadedFinal != wanted) {
            if (wanted != null) GearslipLog.i("speech: ${wanted.label} isn't loaded yet, keeping the live words")
            phrase.fill(0f)
            return last
        }
        onStopped()
        val started = SystemClock.uptimeMillis()
        // Held for the whole read, so a model swap or the idle unload can't free it mid-decode.
        val reread = synchronized(finalLock) {
            val final = finalRecognizer?.takeIf { loadedFinal == wanted } ?: run { phrase.fill(0f); return last }
            final.createStream().run {
                try {
                    acceptWaveform(phrase.copyOf(held), RATE)
                    final.decode(this)
                    clean(final.getResult(this).text)
                } finally {
                    release()
                }
            }
        }
        phrase.fill(0f)
        GearslipLog.i("speech: the final read took ${SystemClock.uptimeMillis() - started} ms")
        if (reread.isEmpty()) return last
        if (reread != last) onPartial(reread)
        return reread
    }

    /** The model that rereads the phrase, when one is chosen and downloaded; null otherwise. */
    private fun ensureFinalLoaded(context: Context): OfflineRecognizer? = synchronized(finalLock) {
        val model = SpeechModels.final.value ?: return null
        if (finalRecognizer != null && loadedFinal == model) return finalRecognizer
        finalRecognizer?.release()
        finalRecognizer = null
        if (!SpeechModels.isReady(context, model)) return null
        val dir = SpeechModels.dir(context, model)
        fun path(name: String) = File(dir, name).absolutePath
        val started = SystemClock.uptimeMillis()
        return runCatching {
            OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
                    modelConfig = if (model.modelType == "canary") {
                        // English in, English out, and no punctuation: clean() strips it anyway.
                        OfflineModelConfig(
                            canary = OfflineCanaryModelConfig(path(model.encoder), path(model.decoder), srcLang = "en", tgtLang = "en", usePnc = false),
                            tokens = path("tokens.txt"),
                            numThreads = threads(),
                        )
                    } else {
                        OfflineModelConfig(
                            transducer = OfflineTransducerModelConfig(path(model.encoder), path(model.decoder), path(model.joiner)),
                            tokens = path("tokens.txt"),
                            numThreads = threads(),
                            modelType = model.modelType,
                        )
                    },
                    decodingMethod = "greedy_search",
                ),
            )
        }.onSuccess {
            finalRecognizer = it
            loadedFinal = model
            GearslipLog.i("speech: loaded ${model.label} in ${SystemClock.uptimeMillis() - started} ms")
        }.onFailure {
            GearslipLog.e("speech: couldn't load ${model.label}", it)
        }.getOrNull()
    }

    private fun ensureLoaded(context: Context): OnlineRecognizer? {
        val model = SpeechModels.selected.value
        if (recognizer != null && loadedModel == model) return recognizer
        recognizer?.release()
        recognizer = null
        if (!SpeechModels.isReady(context, model)) return null
        val dir = SpeechModels.dir(context, model)
        fun path(name: String) = File(dir, name).absolutePath
        val started = SystemClock.uptimeMillis()
        return runCatching {
            OnlineRecognizer(
                config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(path(model.encoder), path(model.decoder), path(model.joiner)),
                        tokens = path("tokens.txt"),
                        numThreads = threads(),
                        modelType = model.modelType,
                    ),
                    endpointConfig = EndpointConfig(
                        rule1 = EndpointRule(false, NO_SPEECH_S, 0f),
                        rule2 = EndpointRule(true, END_SILENCE_S, 0f),
                        rule3 = EndpointRule(false, 0f, MAX_S),
                    ),
                    enableEndpoint = true,
                    decodingMethod = "greedy_search",
                ),
            )
        }.onSuccess {
            recognizer = it
            loadedModel = model
            GearslipLog.i("speech: loaded ${model.label} in ${SystemClock.uptimeMillis() - started} ms")
        }.onFailure {
            GearslipLog.e("speech: couldn't load ${model.label}", it)
        }.getOrNull()
    }

    /** The big cores only: more threads than that slow the model down on a phone. */
    private fun threads() = Runtime.getRuntime().availableProcessors().let { if (it >= 8) 4 else max(1, it / 2) }

    /**
     * Models write in upper case, or with punctuation and capitals of their own; commands are
     * matched in plain lower case, so only words and apostrophes are kept. Shared with
     * [HotwordListener], which matches its phrases against the same cleaned text.
     */
    internal fun clean(text: String): String =
        text.lowercase().replace(Regex("[^\\p{L}\\p{N}' ]"), " ").replace(Regex("\\s+"), " ").trim()

    /**
     * A short beep on the media stream, so it plays through the car like everything else, then a
     * pause long enough for it to finish there before the microphone opens and hears it.
     */
    private fun cue() {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, CUE_VOLUME) }.getOrNull() ?: return
        try {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, CUE_MS)
            Thread.sleep(CUE_MS + CUE_TAIL_MS)
        } finally {
            tone.release()
        }
    }

    // --- microphones -----------------------------------------------------------------------

    /** Which physical microphone a [Mic] came from; the hotword loop needs to know. */
    internal enum class MicSource { PHONE, CAR }

    /**
     * r28: whether the car mic has been proven dead this session — by the
     * hotword's 8 s silence fallback, or by a button listen that heard
     * nothing but digital silence. While set, every listen uses the
     * phone's mic and skips the car's 1500 ms first-audio probe
     * entirely, so a dead mic costs no delay either. Cleared by
     * [unload] (session end, or a model swap). The failure direction
     * is safe by construction: a false positive only ever picks the
     * phone's mic, never deafness.
     */
    @Volatile private var carMicDead = false

    /** The mic a hotword was heard on, waiting for the listen it triggers. */
    private val hotwordMicOverride = HotwordMicOverride()

    /** Marks the car mic dead for this session; logs once, on the transition. */
    internal fun noteCarMicDead() {
        if (!carMicDead) {
            carMicDead = true
            GearslipLog.i("speech: car microphone marked dead for this session, voice listens use the phone's")
        }
    }

    /**
     * Remembers the microphone a hotword was heard on, for the one listen
     * that hotword triggers. Only the phone's mic is ever remembered: a
     * hotword heard on the car's mic sets nothing and selection is
     * unchanged. Called by [HotwordListener] before it posts the trigger.
     */
    internal fun inheritHotwordMic(source: MicSource) {
        if (source == MicSource.PHONE) hotwordMicOverride.noteHeardOnPhone(SystemClock.uptimeMillis())
    }

    /**
     * The microphone a phrase is heard from. The decision itself lives
     * in [MicSelection.selectSource] (pure, unit-tested); this only
     * opens what it names. The car mic is probed only when it can
     * actually win — a setting, a proven-dead car mic, or an inherited
     * phone mic all skip the probe, so a dead mic never costs the
     * listen its 1500 ms first-audio wait.
     */
    internal fun openListenMic(): Pair<Mic, MicSource> {
        val inherited = hotwordMicOverride.takePhone(SystemClock.uptimeMillis())
        val setting = CarSettings.phoneMicForVoice.value
        val car = if (!setting && !carMicDead && !inherited) carMic() else null
        if (!setting) {
            if (carMicDead) {
                GearslipLog.i("speech: car microphone already known dead, using the phone's")
            } else if (inherited) {
                GearslipLog.i("speech: hotword was heard on the phone's microphone, keeping it for this listen")
            }
        }
        return when (MicSelection.selectSource(setting, carMicDead, inherited, car != null)) {
            MicSource.PHONE -> phoneMic() to MicSource.PHONE
            MicSource.CAR -> car!! to MicSource.CAR
        }
    }

    /**
     * The microphone a phrase used to be heard from before r28: the
     * phone's when the phone-microphone setting is on, otherwise the
     * car's when it has one and sends usable audio, else the phone's.
     * Kept for the hotword loop, which needs the same base choice
     * before its own fallback logic runs; assistant listens go through
     * [openListenMic] instead, so they can inherit a hotword's mic and
     * remember a proven-dead car mic.
     */
    internal fun openSpeechMicSource(): Pair<Mic, MicSource> =
        if (CarSettings.phoneMicForVoice.value) phoneMic() to MicSource.PHONE
        else carMic()?.let { it to MicSource.CAR } ?: (phoneMic() to MicSource.PHONE)

    /**
     * The microphone the hotword loop listens on. The hotword-only
     * phone-mic switch (2026-10-25, for a car whose built-in mic is
     * broken) wins over everything: when it is on, the loop always
     * uses the phone mic, regardless of the voice-microphone setting.
     * When it is off, the loop follows the voice-microphone setting
     * exactly as a button-press command does. Hotword-only: button
     * commands, calls, and everything else never see this switch.
     */
    internal fun openHotwordMic(): Pair<Mic, MicSource> =
        if (CarSettings.hotwordPhoneMic.value) phoneMic() to MicSource.PHONE
        else openSpeechMicSource()

    /** The phone mic on its own, for the hotword loop's silent-car-mic fallback. */
    internal fun openPhoneMic(): Mic = phoneMic()

    /** Where a phrase is heard from: [read] fills [frame] and returns how many samples it got, -1 at the end. */
    internal interface Mic {
        fun read(frame: ShortArray): Int
        fun close()
    }

    @SuppressLint("MissingPermission") // checked in listen()
    private fun phoneMic(): Mic {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, CHUNK * 4),
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord didn't initialize" }
        recorder.startRecording()
        GearslipLog.i("speech: listening through the phone's microphone")
        // r44: the recorder's read used to block forever with no
        // timeout — the proven wedge shape. Reads now go through a
        // producer thread into a queue the consumer polls (the
        // car-mic gap discipline), so the listen loop always gets
        // back to its run check even if the recorder itself never
        // returns; a producer parked that way is a daemon thread, not
        // a wedged listen.
        val frames = MicFrameQueue(PHONE_POLL_MS)
        val closed = AtomicBoolean(false)
        Thread({
            val buf = ShortArray(CHUNK)
            while (!closed.get()) {
                val n = try {
                    recorder.read(buf, 0, buf.size)
                } catch (t: Throwable) {
                    -1
                }
                if (n < 0) break
                if (n == 0) continue
                frames.offer(buf.copyOf(n))
            }
            frames.markEnded()
        }, "gearslip-phone-mic").apply { isDaemon = true; start() }
        return object : Mic {
            override fun read(frame: ShortArray) = frames.poll(frame)
            override fun close() {
                if (!closed.compareAndSet(false, true)) return
                runCatching { recorder.stop() }
                recorder.release()
                frames.markEnded()
            }
        }
    }

    /**
     * The car's microphone, which hears the driver better than a phone in a cup holder. The car
     * sends audio in its own sized pieces; they're cut to [CHUNK]s here. Null when there's no car
     * microphone, or it sends nothing at first, so the phone's is used instead.
     */
    private fun carMic(): Mic? {
        val car = CarMic.current ?: return null
        if (!car.start()) return null
        val first = car.read(CAR_FIRST_AUDIO_MS)
        if (first == null) {
            car.stop()
            GearslipLog.w("speech: the car's microphone sent nothing - using the phone's")
            return null
        }
        GearslipLog.i("speech: listening through the car's microphone")
        return object : Mic {
            var pending = first
            var at = 0
            override fun read(frame: ShortArray): Int {
                var filled = 0
                while (filled < frame.size) {
                    val chunk = pending ?: car.read(CAR_GAP_MS) ?: break
                    pending = chunk
                    val n = minOf(frame.size - filled, chunk.size - at)
                    System.arraycopy(chunk, at, frame, filled, n)
                    filled += n
                    at += n
                    if (at == chunk.size) { pending = null; at = 0 }
                }
                // The car stopped sending mid-phrase: end the phrase rather than wait forever.
                return if (filled == 0) -1 else filled
            }
            override fun close() = car.stop()
        }
    }
}
