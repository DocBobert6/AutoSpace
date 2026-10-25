package app.autospace.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import app.autospace.GearslipLog
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * AutoSpace's spoken voice, replacing android.speech.tts.TextToSpeech
 * everywhere it was used (CarAssistant replies and read-aloud messages,
 * DirectionsVoice turns). Kokoro speaks first, in the Kokoro voice
 * selected in Settings (American Adam by default); Piper speaks when
 * Kokoro isn't downloaded or a Kokoro synthesis fails. If neither is
 * downloaded, nothing speaks — there is no system-TTS step left to fall
 * back to, by order, so callers must surface that instead of silence.
 *
 * Synthesis runs on its own thread and is waited on for at most
 * [SYNTH_TIMEOUT_S] seconds, so a stuck native generate can't wedge the
 * directions thread the way an unbounded wait would; a timed-out
 * synthesis is simply dropped and the next prompt queues behind it.
 * Playback is an AudioTrack on the media usage, the same stream system
 * TTS used, so it reaches the car speakers exactly the way the old
 * voice did (USB pipe or Bluetooth) and never the phone speaker alone.
 * Nothing spoken is ever logged: only the engine, the audio length, and
 * the synthesis time.
 *
 * Threading invariant (preview-crash fix, 2026-10-25): EVERY native
 * sherpa-onnx call — creating an OfflineTts, generating audio, and
 * releasing one — runs on [synthExecutor], and only there. Previews used
 * to generate on the playback thread while assistant speech generated on
 * the synth thread, so two threads could generate on the same cached
 * instance at once, and [unload] could release an instance mid-generate
 * (tap Preview, then tap a voice radio to select it, or delete an
 * engine, while the preview was still rendering). A native crash there
 * kills the whole process — no Java exception, no dialog, the app just
 * closes — which is what the phone Settings preview did. Serializing
 * through one thread removes both races at the root; [unload] enqueues
 * its release on the same thread so it can only run between generates,
 * never during one.
 */
object VoiceEngine {

    data class Synthesized(val samples: FloatArray, val rate: Int, val engine: String)

    /** Threads sherpa-onnx runs synthesis on; short bursts, so 4 is safe. */
    private const val NUM_THREADS = 4

    /** A turn prompt or reply that takes longer than this to render is dropped. */
    private const val SYNTH_TIMEOUT_S = 20L

    private val synthExecutor = Executors.newSingleThreadExecutor { Thread(it, "gearslip-voice-synth") }
    private val playExecutor = Executors.newSingleThreadExecutor { Thread(it, "gearslip-voice-play") }
    private val main = Handler(Looper.getMainLooper())

    private val lock = Object()
    private var kokoro: OfflineTts? = null
    private var kokoroLang: String? = null
    private var piper: OfflineTts? = null
    private val generation = AtomicInteger(0)
    @Volatile private var currentTrack: AudioTrack? = null

    /**
     * Round-3 diagnosis (2026-10-25): the last synthesis/playback
     * failure, as engine + stage + voice id only — never spoken text.
     * Previews used to swallow every failure as pure silence and the
     * assistant overlay blamed the voice files for every silent render,
     * so a failure whose cause was NOT the files was undiagnosable
     * from the device. Callers read this at onSilent/onError time to
     * name the real stage; it is cleared on any successful render so
     * a stale failure can never mislabel a later silence.
     */
    @Volatile
    var lastFailure: String? = null
        private set

    /**
     * Drops loaded engines (a voice was deleted or changed) and stops playback.
     * Playback stops at once; the release itself is enqueued on the synth
     * thread, behind any generate already running or queued, so an engine
     * is never released mid-generate. A later synthesis recreates its
     * engine on demand (the synth queue is FIFO, so it always runs after
     * this release), and a generate queued before this release still
     * finishes on the old instance first.
     */
    fun unload() {
        stop()
        synthExecutor.execute {
            synchronized(lock) {
                runCatching { kokoro?.release() }
                runCatching { piper?.release() }
                kokoro = null
                kokoroLang = null
                piper = null
            }
        }
    }

    /** Stops playback in flight. A synthesis already running finishes and is discarded. */
    fun stop() {
        generation.incrementAndGet()
        runCatching { currentTrack?.stop() }
        runCatching { currentTrack?.release() }
        currentTrack = null
    }

    /**
     * Renders [text] to audio, Kokoro first, Piper on any Kokoro failure.
     * Blocking; callers must not run it on the main thread. Null when
     * neither engine is downloaded, or when synthesis fails or times out.
     */
    fun synthesize(context: Context, text: String): Synthesized? {
        if (text.isBlank()) return null
        val app = context.applicationContext
        val future: Future<Synthesized?> = synthExecutor.submit<Synthesized?> {
            runCatching { synthesizeNow(app, text) }.getOrNull()
        }
        return try {
            future.get(SYNTH_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            GearslipLog.w("voice: synthesis timed out after ${SYNTH_TIMEOUT_S}s")
            null
        } catch (e: Exception) {
            GearslipLog.w("voice: synthesis failed: ${e.message}")
            null
        }
    }

    /**
     * The espeak languages sherpa-onnx can phonemize with for the
     * catalogue voices. A selected voice whose sid is outside the
     * voices.bin table (0..52) or whose language is not one of these
     * must never reach a native generate: that call is exactly the
     * shape of crash that kills the process with no Java exception.
     * A stored selection can go stale this way if the catalogue ever
     * changes under it; the check costs nothing and keeps the stale
     * value from ever being spoken — Piper answers instead.
     */
    private val ALLOWED_ESPEAK_LANGS = setOf("en", "es", "fr", "hi", "it", "ja", "pt", "cmn")

    private fun validKokoroVoice(voice: VoiceModels.KokoroVoice): Boolean {
        if (voice.sid !in 0..52) return false
        if (VoiceModels.KOKORO_VOICES.none { it.id == voice.id && it.sid == voice.sid }) return false
        return VoiceModels.espeakLang(voice) in ALLOWED_ESPEAK_LANGS
    }

    /**
     * Round-3 diagnosis (2026-10-25): a native generate can fail in
     * visibly different ways, and the old code collapsed them all —
     * a throw and an empty render shared one "synthesis failed" line
     * (assistant path) or no line at all (preview path), and a buffer
     * of exact zeros counted as success and played as nothing. Each
     * branch here logs its own line and records [lastFailure], so a
     * silent preview or reply names its stage instead of blaming the
     * voice files. Null on any failure; a render with real samples
     * on success. The engine-unavailable branch means the load line
     * just above already said why (refused, or failed to load).
     */
    private fun generateKokoro(app: Context, voice: VoiceModels.KokoroVoice, text: String): GeneratedAudio? {
        val engine = kokoro(app, voice) ?: run {
            // 2026-10-25, stage-preservation fix (Robert's 16:14 report:
            // previews showed "engine unavailable" for BOTH engines while
            // Settings read Downloaded): kokoro()/piper() ALWAYS record
            // the specific reason they returned null — "load refused:
            // <reason>" or "load failed <Class>" — and the old code here
            // overwrote it with the generic "engine unavailable", which
            // destroyed exactly the evidence the diagnostic build was
            // meant to surface. A null engine now keeps the load stage
            // it arrived with; the generic wording is only a fallback
            // for a null that somehow recorded no stage at all.
            val stage = lastFailure ?: "kokoro engine unavailable"
            GearslipLog.w("voice: kokoro engine unavailable at generate (load stage: $stage)")
            lastFailure = stage
            return null
        }
        return classifyGenerate("kokoro ${voice.id}") { engine.generate(text, voice.sid, 1.0f) }
    }

    private fun generatePiper(app: Context, text: String): GeneratedAudio? {
        val engine = piper(app) ?: run {
            // Same stage-preservation as generateKokoro above: piper()
            // records "load refused" / "load failed <Class>" before
            // returning null, and overwriting that with the generic
            // wording hid whether native creation threw or refused.
            val stage = lastFailure ?: "piper engine unavailable"
            GearslipLog.w("voice: piper engine unavailable at generate (load stage: $stage)")
            lastFailure = stage
            return null
        }
        return classifyGenerate("piper") { engine.generate(text, 0, 1.0f) }
    }

    private fun classifyGenerate(tag: String, generate: () -> GeneratedAudio): GeneratedAudio? {
        val audio = try {
            generate()
        } catch (e: Exception) {
            // A JNI null return surfaces here as NullPointerException,
            // an UnsatisfiedLinkError as itself: the class IS the clue.
            GearslipLog.w("voice: $tag generate threw ${e.javaClass.simpleName}: ${e.message}")
            lastFailure = "$tag generate threw ${e.javaClass.simpleName}"
            return null
        }
        if (audio.samples.isEmpty() || audio.sampleRate <= 0) {
            GearslipLog.w("voice: $tag generate empty (samples=${audio.samples.size} rate=${audio.sampleRate})")
            lastFailure = "$tag generate empty"
            return null
        }
        if (audio.samples.all { it == 0f }) {
            GearslipLog.w("voice: $tag generate digital silence (${audio.samples.size} samples all zero)")
            lastFailure = "$tag digital silence"
            return null
        }
        return audio
    }

    private fun synthesizeNow(app: Context, text: String): Synthesized? {
        if (VoiceModels.isReady(app, VoiceModels.Engine.KOKORO)) {
            val voice = VoiceModels.selectedVoice.value
            if (!validKokoroVoice(voice)) {
                GearslipLog.w("voice: selected voice ${voice.id} sid ${voice.sid} is not valid, trying piper")
            } else {
                val started = System.currentTimeMillis()
                // Markers around the native call (engine + voice id only,
                // never spoken text): if this generate kills the process,
                // the begin line without an end line names the step.
                GearslipLog.i("voice: native generate begin kokoro ${voice.id}")
                val audio = generateKokoro(app, voice, text)
                GearslipLog.i("voice: native generate end kokoro ${voice.id}")
                if (audio != null) {
                    lastFailure = null
                    GearslipLog.i(
                        "voice: kokoro ${voice.id} spoke ${audio.samples.size * 1000 / audio.sampleRate} ms " +
                            "of audio in ${System.currentTimeMillis() - started} ms",
                    )
                    return Synthesized(audio.samples, audio.sampleRate, "kokoro")
                }
                GearslipLog.w("voice: kokoro synthesis failed, trying piper")
            }
        }
        if (VoiceModels.isReady(app, VoiceModels.Engine.PIPER)) {
            val started = System.currentTimeMillis()
            GearslipLog.i("voice: native generate begin piper")
            val audio = generatePiper(app, text)
            GearslipLog.i("voice: native generate end piper")
            if (audio != null) {
                lastFailure = null
                GearslipLog.i(
                    "voice: piper spoke ${audio.samples.size * 1000 / audio.sampleRate} ms " +
                        "of audio in ${System.currentTimeMillis() - started} ms",
                )
                return Synthesized(audio.samples, audio.sampleRate, "piper")
            }
            GearslipLog.w("voice: piper synthesis failed")
        }
        return null
    }

    private fun kokoro(app: Context, voice: VoiceModels.KokoroVoice): OfflineTts? {
        synchronized(lock) {
            val lang = VoiceModels.espeakLang(voice)
            val cached = kokoro
            if (cached != null && kokoroLang == lang) return cached
            runCatching { cached?.release() }
            val dir = VoiceModels.dir(app, VoiceModels.Engine.KOKORO)
            // 2026-10-25: never hand a refused set to native code.
            // A native load failure is a whole-process exit (sherpa
            // SHERPA_ONNX_EXIT), not a Java exception, so the only
            // safe moment to refuse is here, before the call, while
            // the caller can still fall back to Piper. The load
            // check line underneath is what makes the NEXT crash
            // report decisive: exact sizes here plus a death in load
            // would exclude the files entirely.
            VoiceModels.validationFailure(app, VoiceModels.Engine.KOKORO)?.let {
                GearslipLog.w("voice: kokoro load refused: $it")
                lastFailure = "kokoro load refused"
                return null
            }
            GearslipLog.i("voice: load check ${VoiceModels.loadCheck(app, VoiceModels.Engine.KOKORO)}")
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = "${dir.absolutePath}/model.onnx",
                        voices = "${dir.absolutePath}/voices.bin",
                        tokens = "${dir.absolutePath}/tokens.txt",
                        dataDir = "${dir.absolutePath}/espeak-ng-data",
                        lang = lang,
                    ),
                    numThreads = NUM_THREADS,
                    debug = false,
                ),
            )
            GearslipLog.i("voice: native load begin kokoro")
            return runCatching { OfflineTts(config) }.onFailure {
                // The class IS the clue here: a require() in the
                // binding's init (native creation returned a null
                // pointer) surfaces as IllegalArgumentException, an
                // ORT failure as its own class — and the old line
                // logged only the message, so the class was lost
                // unless the caller happened to read lastFailure.
                GearslipLog.w("voice: kokoro failed to load: ${it.javaClass.simpleName}: ${it.message}")
                lastFailure = "kokoro load failed ${it.javaClass.simpleName}"
            }.onSuccess {
                GearslipLog.i("voice: native load end kokoro")
            }.getOrNull()?.also {
                kokoro = it
                kokoroLang = lang
            }
        }
    }

    private fun piper(app: Context): OfflineTts? {
        synchronized(lock) {
            piper?.let { return it }
            val dir = VoiceModels.dir(app, VoiceModels.Engine.PIPER)
            // Same refusal-before-native-call as kokoro() above: a
            // bad Piper set must fall back to the no-voice warning,
            // never reach a native load that can exit the process.
            VoiceModels.validationFailure(app, VoiceModels.Engine.PIPER)?.let {
                GearslipLog.w("voice: piper load refused: $it")
                lastFailure = "piper load refused"
                return null
            }
            GearslipLog.i("voice: load check ${VoiceModels.loadCheck(app, VoiceModels.Engine.PIPER)}")
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = "${dir.absolutePath}/en_US-amy-medium.onnx",
                        tokens = "${dir.absolutePath}/tokens.txt",
                        dataDir = "${dir.absolutePath}/espeak-ng-data",
                    ),
                    numThreads = NUM_THREADS,
                    debug = false,
                ),
            )
            GearslipLog.i("voice: native load begin piper")
            return runCatching { OfflineTts(config) }.onFailure {
                // Same class-in-the-line fix as kokoro() above.
                GearslipLog.w("voice: piper failed to load: ${it.javaClass.simpleName}: ${it.message}")
                lastFailure = "piper load failed ${it.javaClass.simpleName}"
            }.onSuccess {
                GearslipLog.i("voice: native load end piper")
            }.getOrNull()?.also { piper = it }
        }
    }

    /**
     * Renders [text] for a car audio sink: mono, resampled to [rate],
     * copied to [channels], 16-bit little-endian — the shape
     * DirectionsVoice.stream expects. Null when nothing can speak.
     */
    fun synthesizeForSink(context: Context, text: String, rate: Int, channels: Int): ByteArray? {
        val audio = synthesize(context, text) ?: return null
        return resample(audio.samples, audio.rate, rate, channels)
    }

    /**
     * Plays [text] through the media stream and runs [onDone] on the main
     * thread when it finishes, is stopped, or can't be spoken at all.
     * Returns false straight away when no engine can speak, so the caller
     * can say that instead of leaving the driver waiting on silence.
     */
    fun play(context: Context, text: String, onDone: () -> Unit, onSilent: () -> Unit = {}): Boolean {
        if (text.isBlank()) {
            main.post(onDone)
            return true
        }
        val app = context.applicationContext
        if (!VoiceModels.isReady(app, VoiceModels.Engine.KOKORO) &&
            !VoiceModels.isReady(app, VoiceModels.Engine.PIPER)
        ) {
            return false
        }
        val myGeneration = generation.incrementAndGet()
        playExecutor.execute {
            val audio = synthesize(app, text)
            if (myGeneration != generation.get() || audio == null) {
                // A failed render used to end here as plain silence: the
                // overlay closed and the driver got nothing, not even a
                // reason. [onSilent] lets the caller say so instead.
                // Interrupted speech (a newer reply took over) is not a
                // failure and must not surface: it is already superseded.
                if (myGeneration == generation.get()) {
                    main.post(onDone)
                    main.post(onSilent)
                }
                return@execute
            }
            playAudio(audio, myGeneration, onDone, notifyOnInterrupt = false, onSilent = onSilent)
        }
        return true
    }

    /**
     * A voice preview: renders [text] in exactly one voice and plays it,
     * without touching the stored selection — [VoiceModels.selectVoice]
     * is never called, so no engine is unloaded and the default survives.
     * [voice] is a Kokoro voice, or null for Piper (Amy). Returns false
     * straight away when that engine isn't downloaded, so the UI can say
     * so instead of showing a dead button; there is deliberately no
     * fallback to the other engine, because a preview of one voice spoken
     * in another would be a lie.
     *
     * Playback shares the single channel assistant speech uses (the same
     * generation counter and current track): starting a preview stops
     * any assistant speech in flight, and a new assistant reply stops a
     * preview — the preview's [onDone] still fires on interruption, so
     * the row's playing state clears instead of sticking. Stop a preview
     * with [stop], the same call that stops assistant speech.
     */
    /**
     * [onError] is new in round 3: a preview used to end every failure
     * as pure silence (onDone only, no message, no log line), which is
     * exactly the symptom on the current build. A failure now reports
     * its recorded stage ([lastFailure]) here, on the main thread, so
     * the row can say why instead of going quiet; an interrupted
     * preview (the user tapped Stop, or a reply took over) still ends
     * quietly through onDone alone, as before.
     */
    fun playPreview(
        context: Context,
        text: String,
        voice: VoiceModels.KokoroVoice?,
        onDone: () -> Unit,
        onError: (String) -> Unit = {},
    ): Boolean {
        if (text.isBlank()) {
            main.post(onDone)
            return true
        }
        val app = context.applicationContext
        // 2026-10-25 launch-trace requirement: a preview tap must
        // leave a line even when nothing follows it — otherwise a tap
        // the gate refuses is indistinguishable from a tap that never
        // arrived, and the trace cannot tell "tap ignored" from
        // "generate failed". The readiness at tap time is part of the
        // line, so a gate refusal names itself.
        val tapReady = if (voice != null) {
            VoiceModels.isReady(app, VoiceModels.Engine.KOKORO)
        } else {
            VoiceModels.isReady(app, VoiceModels.Engine.PIPER)
        }
        GearslipLog.i("voice: preview tap ${voice?.id ?: "piper"} ready=$tapReady")
        if (!tapReady) {
            GearslipLog.w("voice: preview not started ${voice?.id ?: "piper"} not ready")
            return false
        }
        val myGeneration = generation.incrementAndGet()
        playExecutor.execute {
            val audio = synthesizePreview(app, voice, text)
            if (myGeneration != generation.get() || audio == null) {
                if (myGeneration == generation.get()) {
                    main.post { onError(lastFailure ?: "unknown voice failure") }
                }
                main.post(onDone)
                return@execute
            }
            playAudio(
                audio, myGeneration, onDone, notifyOnInterrupt = true,
                onSilent = { onError(lastFailure ?: "playback failed") },
            )
        }
        return true
    }

    /**
     * Renders a preview in exactly one voice, on the synth thread like
     * every other generate, and waited on for at most [SYNTH_TIMEOUT_S]
     * seconds — a preview used to generate unbounded on the playback
     * thread, so a stuck native render wedged every later preview and
     * every assistant reply queued behind it on that thread. Null when
     * the engine can't render (the caller posts [onDone] either way, so
     * the row never sticks on "Stop").
     */
    private fun synthesizePreview(app: Context, voice: VoiceModels.KokoroVoice?, text: String): Synthesized? {
        val future: Future<Synthesized?> = synthExecutor.submit<Synthesized?> {
            runCatching {
                if (voice != null) {
                    if (!validKokoroVoice(voice)) {
                        GearslipLog.w("voice: preview voice ${voice.id} sid ${voice.sid} is not valid")
                        lastFailure = "preview voice ${voice.id} not valid"
                        null
                    } else {
                        val started = System.currentTimeMillis()
                        GearslipLog.i("voice: native generate begin preview ${voice.id}")
                        val generated = generateKokoro(app, voice, text)
                        GearslipLog.i("voice: native generate end preview ${voice.id}")
                        if (generated != null) {
                            lastFailure = null
                            GearslipLog.i(
                                "voice: preview ${voice.id} rendered " +
                                    "${generated.samples.size * 1000 / generated.sampleRate} ms " +
                                    "in ${System.currentTimeMillis() - started} ms",
                            )
                            Synthesized(generated.samples, generated.sampleRate, "kokoro")
                        } else null
                    }
                } else {
                    GearslipLog.i("voice: native generate begin preview piper")
                    val generated = generatePiper(app, text)
                    GearslipLog.i("voice: native generate end preview piper")
                    if (generated != null) {
                        lastFailure = null
                        GearslipLog.i(
                            "voice: preview piper rendered " +
                                "${generated.samples.size * 1000 / generated.sampleRate} ms",
                        )
                        Synthesized(generated.samples, generated.sampleRate, "piper")
                    } else null
                }
            }.onFailure {
                GearslipLog.w("voice: preview synthesis threw ${it.javaClass.simpleName}: ${it.message}")
                lastFailure = "preview threw ${it.javaClass.simpleName}"
            }.getOrNull()
        }
        return try {
            future.get(SYNTH_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            GearslipLog.w("voice: preview synthesis timed out after ${SYNTH_TIMEOUT_S}s")
            null
        } catch (e: Exception) {
            GearslipLog.w("voice: preview synthesis failed: ${e.message}")
            null
        }
    }

    /** The shared AudioTrack playback loop for [play] and [playPreview]. */
    private fun playAudio(
        audio: Synthesized,
        myGeneration: Int,
        onDone: () -> Unit,
        notifyOnInterrupt: Boolean,
        onSilent: () -> Unit = {},
    ) {
        // The Builder used to sit outside the try: a generated rate the
        // hardware rejects (0, or absurd) threw IllegalArgumentException
        // on this thread, onDone never fired, and the overlay stuck on
        // SPEAKING. A bad rate is a failed render, not a crash: guard it
        // and fail through the same path as a failed synthesis.
        if (audio.rate <= 0 || audio.rate > 96_000 || audio.samples.isEmpty()) {
            GearslipLog.w("voice: refusing playback of unusable audio (rate ${audio.rate})")
            lastFailure = "playback refused unusable audio"
            if (myGeneration == generation.get()) {
                main.post(onDone)
                main.post(onSilent)
            }
            return
        }
        val shorts = floatsToShorts(audio.samples)
        var track: AudioTrack? = null
        var failed = false
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(audio.rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(shorts.size * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            currentTrack = track
            track.play()
            var offset = 0
            while (offset < shorts.size && myGeneration == generation.get()) {
                val written = track.write(shorts, offset, shorts.size - offset)
                // Round 3: a stalled write used to break out of the loop
                // with failed still false, so a reply ended in silence
                // with no onSilent and no log line — a fourth silent
                // branch beside generate-threw, generate-empty, and
                // load refusal. A stall is a playback failure: log it
                // with its position and fail through onSilent/onError.
                if (written <= 0) {
                    GearslipLog.w("voice: playback write stalled at $offset/${shorts.size} (returned $written)")
                    lastFailure = "playback write stalled"
                    failed = true
                    break
                }
                offset += written
            }
        } catch (e: Exception) {
            GearslipLog.w("voice: playback failed: ${e.message}")
            lastFailure = "playback threw ${e.javaClass.simpleName}"
            failed = true
        } finally {
            track?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
                if (currentTrack === it) currentTrack = null
            }
        }
        if (myGeneration == generation.get() || notifyOnInterrupt) main.post(onDone)
        if (failed && myGeneration == generation.get()) main.post(onSilent)
    }

    /** Float samples in [-1, 1] to 16-bit PCM, clamped, never silent by overflow. */
    internal fun floatsToShorts(samples: FloatArray): ShortArray =
        ShortArray(samples.size) { i ->
            (samples[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }

    /** Mixes to mono, resamples linearly, copies to each channel — as DirectionsVoice did from WAV. */
    internal fun resample(samples: FloatArray, srcRate: Int, dstRate: Int, channels: Int): ByteArray {
        val outFrames = (samples.size.toLong() * dstRate / srcRate).toInt()
        val out = ByteArray(outFrames * channels * 2)
        val step = srcRate.toDouble() / dstRate
        var pos = 0
        for (i in 0 until outFrames) {
            val at = i * step
            val j = at.toInt().coerceAtMost(samples.size - 1)
            val next = (j + 1).coerceAtMost(samples.size - 1)
            val v = samples[j] + (samples[next] - samples[j]) * (at - j).toFloat()
            val s = (v * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            repeat(channels) {
                out[pos++] = (s.toInt() and 0xFF).toByte()
                out[pos++] = ((s.toInt() shr 8) and 0xFF).toByte()
            }
        }
        return out
    }
}
