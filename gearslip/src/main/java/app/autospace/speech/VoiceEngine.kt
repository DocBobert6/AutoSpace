package app.autospace.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import app.autospace.GearslipLog
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
                val audio = runCatching { kokoro(app, voice)?.generate(text, voice.sid, 1.0f) }.getOrNull()
                GearslipLog.i("voice: native generate end kokoro ${voice.id}")
                if (audio != null && audio.samples.isNotEmpty() && audio.sampleRate > 0) {
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
            val audio = runCatching { piper(app)?.generate(text, 0, 1.0f) }.getOrNull()
            GearslipLog.i("voice: native generate end piper")
            if (audio != null && audio.samples.isNotEmpty() && audio.sampleRate > 0) {
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
                GearslipLog.w("voice: kokoro failed to load: ${it.message}")
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
                GearslipLog.w("voice: piper failed to load: ${it.message}")
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
    fun playPreview(context: Context, text: String, voice: VoiceModels.KokoroVoice?, onDone: () -> Unit): Boolean {
        if (text.isBlank()) {
            main.post(onDone)
            return true
        }
        val app = context.applicationContext
        if (voice != null && !VoiceModels.isReady(app, VoiceModels.Engine.KOKORO)) return false
        if (voice == null && !VoiceModels.isReady(app, VoiceModels.Engine.PIPER)) return false
        val myGeneration = generation.incrementAndGet()
        playExecutor.execute {
            val audio = synthesizePreview(app, voice, text)
            if (myGeneration != generation.get() || audio == null) {
                main.post(onDone)
                return@execute
            }
            playAudio(audio, myGeneration, onDone, notifyOnInterrupt = true)
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
                        null
                    } else {
                        val started = System.currentTimeMillis()
                        GearslipLog.i("voice: native generate begin preview ${voice.id}")
                        val generated = kokoro(app, voice)?.generate(text, voice.sid, 1.0f)
                        GearslipLog.i("voice: native generate end preview ${voice.id}")
                        if (generated != null && generated.samples.isNotEmpty() && generated.sampleRate > 0) {
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
                    val generated = piper(app)?.generate(text, 0, 1.0f)
                    GearslipLog.i("voice: native generate end preview piper")
                    if (generated != null && generated.samples.isNotEmpty() && generated.sampleRate > 0) {
                        GearslipLog.i(
                            "voice: preview piper rendered " +
                                "${generated.samples.size * 1000 / generated.sampleRate} ms",
                        )
                        Synthesized(generated.samples, generated.sampleRate, "piper")
                    } else null
                }
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
                if (written <= 0) break
                offset += written
            }
        } catch (e: Exception) {
            GearslipLog.w("voice: playback failed: ${e.message}")
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
