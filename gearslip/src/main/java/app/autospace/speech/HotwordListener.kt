package app.autospace.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.autospace.GearslipLog
import app.autospace.car.CarEnvironment
import app.autospace.car.CarSettings
import app.autospace.car.TestMode
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Hands-free wake phrase, so the driver never has to press a button: while a car session
 * is active and the switch (CarSettings.hotword, off by default) is on, the streaming
 * Kroko recognizer already used for commands listens continuously for "Hey AutoSpace"
 * (or the alias "Hey Gearslip") and hands off to the normal assistant flow on hearing it.
 *
 * Deliberately the simple version, not a dedicated keyword spotter: no new model is
 * downloaded and no new binding is vendored. That has two costs, stated plainly. First,
 * continuous recognition costs CPU and battery while a drive runs — there is no voice-
 * activity gate in this version, because no VAD model is vendored. Second, it is a
 * second Kroko recognizer beside SpeechEngine's own, so a drive holds two copies of a
 * small model in memory. If either cost measures badly on a real phone, the follow-up
 * is a tiny dedicated spotter, not more tuning here.
 *
 * Safety rules, all enforced in the loop, not left to callers:
 * - The phrase must start the utterance: a bare "hey", or the phrase buried mid-sentence,
 *   never triggers. Only the cleaned transcript's first words count.
 * - Nothing but the matched phrase itself is ever logged: transcripts are matched and
 *   dropped, never written to the log.
 * - The microphone is held only while every gate passes (switch on, real car session,
 *   not suspended, no phrase being listened for, microphone permission granted, Kroko
 *   downloaded). The moment a gate fails the microphone closes.
 * - The car's microphone is a single channel (see CarMic): a button-press phrase sets
 *   SpeechEngine.listeningActive, the loop closes its microphone within one 100 ms
 *   chunk, and SpeechEngine's worker waits for that release before opening its own.
 */
object HotwordListener {

    /** The wake phrase; the alias is the assistant's old name, still in its filler list. */
    const val PHRASE_MAIN = "hey autospace"

    /** Alias kept so drivers who learned the old name are not stranded. */
    const val PHRASE_ALIAS = "hey gearslip"

    private const val RATE = 16_000
    private const val CHUNK = 1_600 // 100 ms, as in SpeechEngine
    /** Unchanged transcript this long means the utterance is over: start a fresh one. */
    private const val SILENCE_RESET_MS = 2_500L
    /** A transcript this long without a hotword is not going to grow one: start fresh. */
    private const val MAX_TRANSCRIPT = 300
    private const val POLL_MS = 250L
    private const val MIC_RELEASE_WAIT_MS = 1_500L
    private const val TRIGGER_COOLDOWN_MS = 1_500L
    /**
     * Broken-car-mic fallback (2026-10-25): a car mic that opens and
     * streams silence never trips SpeechEngine's 1500 ms first-audio
     * fallback (that one only fires when NOTHING arrives), so the loop
     * used to listen to silence forever. A chunk counts as silent when
     * every sample is within [SILENCE_AMPLITUDE] of zero (16-bit
     * samples: a working mic's noise floor alone is louder than this,
     * and any real speech is hundreds of times louder); [SILENCE_CHUNKS]
     * of unbroken silence (8 s) on a car mic switches this hold to the
     * phone mic, once per hold, and logs it. The flap guard is the
     * combination: continuous silence only (any loud chunk resets the
     * count), a long window (a quiet car at a red light does not trip
     * 8 s of digital silence), and once-per-hold (it never bounces
     * back and forth). A mic streaming loud garbage is NOT detected —
     * silence is the only broken-mic shape this can prove — and the
     * phone-mic hotword switch below is the workaround for that case.
     *
     * The constant is internal, not private, because [SilenceTracker]
     * shares it: the hotword loop and the button listen must never
     * drift apart on what "silent" means.
     *
     * r28: the fallback no longer dies with the hold. Firing it also
     * marks the car mic dead for the session ([SpeechEngine.noteCarMicDead]),
     * so later listens stop trusting it, and the trigger hands the mic
     * the hotword was heard on to the assistant listen that follows
     * ([SpeechEngine.inheritHotwordMic]) — before r28 that listen
     * re-selected from scratch and went deaf on the dead car mic.
     */
    internal const val SILENCE_AMPLITUDE = 32
    private const val SILENCE_FALLBACK_MS = 8_000L
    /** How often the "waiting for Kroko" diagnostic may repeat while the loop retries. */
    private const val MODEL_WAIT_LOG_MS = 30_000L

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "gearslip-hotword") }
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var generation = 0
    /** True only while this loop itself holds a microphone open. */
    @Volatile private var holdingMic = false
    /** True while a real car session runs; set by the car layer, never by this loop. */
    @Volatile private var sessionCheck: () -> Boolean = { false }
    /** True while something else must own the microphone (assistant busy, on a call). */
    @Volatile private var suspendCheck: () -> Boolean = { true }
    @Volatile private var onHotword: (() -> Unit)? = null
    @Volatile private var lastModelWaitLog = 0L

    /**
     * Wiring from the car layer: [sessionCheck] is true only for a real car session
     * (CarEnvironment.isRunning — the phone preview never sets it), [suspendCheck] is
     * true while the assistant is busy or a call is active, and [onHotword] starts the
     * assistant exactly as a button press does.
     */
    fun configure(
        sessionCheck: () -> Boolean,
        suspendCheck: () -> Boolean,
        onHotword: () -> Unit,
    ) {
        this.sessionCheck = sessionCheck
        this.suspendCheck = suspendCheck
        this.onHotword = onHotword
    }

    /** Starts the loop; safe to call repeatedly, and a no-op until the gates pass. */
    fun start(context: Context) {
        if (running) return
        running = true
        GearslipLog.i("hotword: loop started")
        val gen = ++generation
        val app = context.applicationContext
        SpeechModels.init(app)
        worker.execute { loop(app, gen) }
    }

    /** Stops the loop; the loop closes its microphone as it exits (within one audio chunk of the flag flipping), not synchronously here. */
    fun stop() {
        if (!running) return
        running = false
        GearslipLog.i("hotword: loop stopped")
    }

    /**
     * Called by SpeechEngine's phrase worker before it opens its microphone: waits until
     * this loop has closed its microphone, or the wait runs out (the phrase then opens
     * its microphone anyway, rather than wedging the command). A no-op when this loop
     * holds no microphone.
     */
    internal fun waitForMicRelease(timeoutMs: Long = MIC_RELEASE_WAIT_MS) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (holdingMic && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(50)
        }
    }

    /**
     * The wake phrase at the start of [cleaned] text, or null. The phrase must be the whole
     * text or be followed by a space: "hey autospacex" is not a trigger, and neither is
     * a phrase that only appears later in the sentence. Pure, so it is unit-tested
     * without a microphone.
     *
     * Brand names are the trap here (2026-10-25): a speech model does not have to spell
     * them the way the app does. "AutoSpace" is two common words to a recognizer, so
     * real transcripts say "hey auto space" at least as often as "hey autospace", and
     * "Gearslip" likewise splits into "gear slip". The matcher therefore accepts every
     * spelling the model can produce for each phrase, with the same start-of-utterance
     * and whole-word boundary rules for each spelling: "hey auto spacex" and
     * "hey auto spacecraft" still do not trigger, because neither spelling is followed
     * by a space there.
     */
    internal fun findHotword(cleaned: String): String? {
        for ((phrase, spellings) in PHRASE_SPELLINGS) {
            if (spellings.any { cleaned == it || cleaned.startsWith("$it ") }) return phrase
        }
        return null
    }

    private val PHRASE_SPELLINGS: List<Pair<String, List<String>>> = listOf(
        PHRASE_MAIN to listOf(PHRASE_MAIN, "hey auto space"),
        PHRASE_ALIAS to listOf(PHRASE_ALIAS, "hey gear slip"),
    )

    private fun gatesPass(app: Context): Boolean {
        if (!CarSettings.hotword.value) return false
        if (!sessionCheck()) return false
        if (suspendCheck()) return false
        if (SpeechEngine.listeningActive) return false
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        return SpeechModels.isReady(app, SpeechModels.selected.value)
    }

    /**
     * Which gate is blocking the loop, or null when all pass. Logged on change only
     * (2026-10-25): the old loop slept in total silence whenever a gate failed, so a
     * blocked loop looked exactly like a deaf one and no trace could tell them apart.
     * The reason names the gate, never any speech: no transcript is ever logged here.
     */
    private fun gateReason(app: Context): String? {
        if (!CarSettings.hotword.value) return "hotword switch off"
        if (!sessionCheck()) return "no car session"
        if (suspendCheck()) return "assistant busy or call active"
        if (SpeechEngine.listeningActive) return "button-press listen active"
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return "microphone permission missing"
        if (!SpeechModels.isReady(app, SpeechModels.selected.value)) return "Kroko listening model not ready"
        return null
    }

    @Volatile private var lastGateReason: String? = null

    /** Logged once per listening stretch while test mode is the only session source. */
    @Volatile private var testModeAnnounced = false

    /** Logs a blocking gate the first time it appears; silence while it persists. */
    private fun logGate(app: Context) {
        val reason = gateReason(app)
        if (reason != lastGateReason) {
            lastGateReason = reason
            if (reason != null) GearslipLog.w("hotword: waiting ($reason)")
        }
    }

    private fun loop(app: Context, gen: Int) {
        var recognizer: OnlineRecognizer? = null
        try {
            while (running && gen == generation) {
                if (!gatesPass(app)) {
                    logGate(app)
                    testModeAnnounced = false
                    Thread.sleep(POLL_MS)
                    continue
                }
                lastGateReason = null
                // Test mode attribution (Robert, 2026-10-25): when the
                // loop is running under a simulated session only — no
                // real session, no preview — say so once, so a log
                // line can never be mistaken for a real car session.
                // (Simple names, not app.autospace.car.…: this
                // function's Context parameter is named `app`, and it
                // shadows the package qualifier — the qualified form
                // failed CI with "Unresolved reference 'autospace'".)
                if (TestMode.active.value &&
                    !CarEnvironment.isRunning &&
                    !testModeAnnounced
                ) {
                    testModeAnnounced = true
                    GearslipLog.i(
                        "hotword: listening in TEST MODE (simulated car session, not a real car)",
                    )
                }
                if (recognizer == null) {
                    recognizer = loadRecognizer(app)
                    if (recognizer == null) {
                        // Kroko not downloaded (yet): retry once a second,
                        // but say so on a throttle instead of in total
                        // silence — a loop that never logs looks dead
                        // exactly like a loop that never hears, and the
                        // driver cannot tell which one they have.
                        val now = SystemClock.uptimeMillis()
                        if (now - lastModelWaitLog > MODEL_WAIT_LOG_MS) {
                            lastModelWaitLog = now
                            GearslipLog.w(
                                "hotword: waiting for the Kroko listening model — " +
                                    "download it in Settings on the phone",
                            )
                        }
                        Thread.sleep(1_000)
                        continue
                    }
                }
                // A previous loop (from an earlier session) may still be closing
                // its microphone hold; wait for it rather than opening a second
                // hold on the car's single microphone channel.
                run {
                    val deadline = SystemClock.uptimeMillis() + 2_000
                    while (holdingMic && SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
                }
                val opened = try {
                    SpeechEngine.openHotwordMic()
                } catch (t: Throwable) {
                    GearslipLog.w("hotword: could not open a microphone: ${t.message}")
                    Thread.sleep(1_000)
                    continue
                }
                var liveMic = opened.first
                holdingMic = true
                GearslipLog.i("hotword: listening for \"Hey AutoSpace\" via ${opened.second}")
                try {
                    liveMic = runStream(recognizer, liveMic, opened.second)
                } catch (t: Throwable) {
                    GearslipLog.w("hotword: listening failed: ${t.message}")
                } finally {
                    // r28: close FIRST, then drop the flag. The old order (flag,
                    // then close) let SpeechEngine's waitForMicRelease pass while
                    // the close was still in flight, so a phrase could open its
                    // microphone against a close racing it on the car's single
                    // channel. Close calls here never block (a send, or a
                    // recorder stop), so no waiter can wedge on this.
                    runCatching { liveMic.close() }
                    holdingMic = false
                }
                // The driver is mid-command after a trigger (or a gate just closed):
                // do not reopen the microphone into the tail of that speech.
                Thread.sleep(TRIGGER_COOLDOWN_MS)
            }
        } finally {
            GearslipLog.i("hotword: loop exited")
            runCatching { recognizer?.release() }
        }
    }

    /**
     * One microphone hold: feeds the recognizer until a hotword fires, a gate fails, or
     * the microphone ends. Utterances are segmented by silence (or length) with a fresh
     * stream, so a long drive's transcript never grows into a false trigger.
     */
    private fun runStream(
        recognizer: OnlineRecognizer,
        mic: SpeechEngine.Mic,
        source: SpeechEngine.MicSource,
    ): SpeechEngine.Mic {
        var liveMic = mic
        var liveSource = source
        var silentChunks = 0
        var fellBack = false
        // Shared with the button listen: one definition of "silent".
        val silence = SilenceTracker()
        var stream = recognizer.createStream()
        val frame = ShortArray(CHUNK)
        val samples = FloatArray(CHUNK)
        var lastText = ""
        var lastChange = SystemClock.uptimeMillis()
        try {
            while (running) {
                if (!gatesPassForStream()) break
                val n = liveMic.read(frame)
                if (n < 0) break
                if (n == 0) continue
                // Broken-car-mic fallback, this hold only: unbroken
                // digital silence on a car mic means the mic is not
                // hearing the cabin at all. Switch to the phone mic
                // for the rest of the hold (old mic closed first, so
                // the single channel is never held twice). r28: firing
                // also marks the car mic dead for the session, so later
                // listens — hotword holds and button presses alike —
                // stop trusting it until the session ends.
                if (liveSource == SpeechEngine.MicSource.CAR && !fellBack) {
                    val chunkSilent = silence.add(frame, n)
                    silentChunks = if (chunkSilent) silentChunks + 1 else 0
                    if (silentChunks * 100L >= SILENCE_FALLBACK_MS) {
                        runCatching { liveMic.close() }
                        liveMic = try {
                            SpeechEngine.openPhoneMic()
                        } catch (t: Throwable) {
                            GearslipLog.w("hotword: phone mic fallback failed: ${t.message}")
                            break
                        }
                        liveSource = SpeechEngine.MicSource.PHONE
                        fellBack = true
                        silentChunks = 0
                        SpeechEngine.noteCarMicDead()
                        GearslipLog.i("hotword: car mic silent, using phone mic")
                    }
                }
                for (i in 0 until n) samples[i] = frame[i] / 32768f
                stream.acceptWaveform(if (n == CHUNK) samples else samples.copyOf(n), RATE)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                val text = SpeechEngine.clean(recognizer.getResult(stream).text)
                if (text != lastText) {
                    lastText = text
                    lastChange = SystemClock.uptimeMillis()
                    // Only the matched phrase constant is logged, never the transcript.
                    findHotword(text)?.let { phrase ->
                        GearslipLog.i("hotword: heard \"$phrase\", opening the assistant")
                        // r28: hand the mic this hold actually heard on to the
                        // assistant listen that follows. Without this the
                        // listen re-selected from scratch and, on a dead
                        // car mic, went deaf right after a phone-mic
                        // hotword — the handoff defect at the heart of r28.
                        SpeechEngine.inheritHotwordMic(liveSource)
                        val action = onHotword
                        main.post { action?.invoke() }
                        return liveMic
                    }
                }
                val now = SystemClock.uptimeMillis()
                if (lastText.isNotEmpty() &&
                    (now - lastChange > SILENCE_RESET_MS || lastText.length > MAX_TRANSCRIPT)
                ) {
                    recognizer.reset(stream)
                    lastText = ""
                    lastChange = now
                }
            }
        } finally {
            runCatching { stream.release() }
        }
        return liveMic
    }

    /**
     * Gates re-checked inside a microphone hold. The full [gatesPass] (which re-reads the
     * model-download state) is too heavy per chunk; the download state cannot change
     * without the user deleting a model mid-drive, which the next hold re-checks.
     */
    private fun gatesPassForStream(): Boolean {
        if (!CarSettings.hotword.value) return false
        if (!sessionCheck()) return false
        if (suspendCheck()) return false
        return !SpeechEngine.listeningActive
    }

    private fun loadRecognizer(app: Context): OnlineRecognizer? {
        val model = SpeechModels.selected.value
        if (!SpeechModels.isReady(app, model)) return null
        val dir = SpeechModels.dir(app, model)
        fun path(name: String) = File(dir, name).absolutePath
        return runCatching {
            OnlineRecognizer(
                config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            path(model.encoder),
                            path(model.decoder),
                            path(model.joiner),
                        ),
                        tokens = path("tokens.txt"),
                        numThreads = threads(),
                        modelType = model.modelType,
                    ),
                    // Endpointing is managed here (silence reset above), not by sherpa.
                    enableEndpoint = false,
                    decodingMethod = "greedy_search",
                ),
            )
        }.onSuccess {
            GearslipLog.i("hotword: loaded ${model.label}")
        }.onFailure {
            GearslipLog.e("hotword: couldn't load ${model.label}", it)
        }.getOrNull()
    }

    /** The big cores only, as in SpeechEngine: more threads slow a phone model down. */
    private fun threads() = Runtime.getRuntime().availableProcessors().let { if (it >= 8) 4 else max(1, it / 2) }
}
