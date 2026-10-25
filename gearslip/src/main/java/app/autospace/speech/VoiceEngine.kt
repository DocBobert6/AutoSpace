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

    /** Drops loaded engines (a voice was deleted or changed) and stops playback. */
    fun unload() {
        stop()
        synchronized(lock) {
            runCatching { kokoro?.release() }
            runCatching { piper?.release() }
            kokoro = null
            kokoroLang = null
            piper = null
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

    private fun synthesizeNow(app: Context, text: String): Synthesized? {
        if (VoiceModels.isReady(app, VoiceModels.Engine.KOKORO)) {
            val voice = VoiceModels.selectedVoice.value
            val started = System.currentTimeMillis()
            val audio = runCatching { kokoro(app, voice)?.generate(text, voice.sid, 1.0f) }.getOrNull()
            if (audio != null && audio.samples.isNotEmpty()) {
                GearslipLog.i(
                    "voice: kokoro ${voice.id} spoke ${audio.samples.size * 1000 / audio.sampleRate} ms " +
                        "of audio in ${System.currentTimeMillis() - started} ms",
                )
                return Synthesized(audio.samples, audio.sampleRate, "kokoro")
            }
            GearslipLog.w("voice: kokoro synthesis failed, trying piper")
        }
        if (VoiceModels.isReady(app, VoiceModels.Engine.PIPER)) {
            val started = System.currentTimeMillis()
            val audio = runCatching { piper(app)?.generate(text, 0, 1.0f) }.getOrNull()
            if (audio != null && audio.samples.isNotEmpty()) {
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
            return runCatching { OfflineTts(config) }.onFailure {
                GearslipLog.w("voice: kokoro failed to load: ${it.message}")
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
            return runCatching { OfflineTts(config) }.onFailure {
                GearslipLog.w("voice: piper failed to load: ${it.message}")
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
    fun play(context: Context, text: String, onDone: () -> Unit): Boolean {
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
                if (myGeneration == generation.get()) main.post(onDone)
                return@execute
            }
            playAudio(audio, myGeneration, onDone, notifyOnInterrupt = false)
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
            val audio = runCatching {
                if (voice != null) {
                    val started = System.currentTimeMillis()
                    val generated = kokoro(app, voice)?.generate(text, voice.sid, 1.0f)
                    if (generated != null && generated.samples.isNotEmpty()) {
                        GearslipLog.i(
                            "voice: preview ${voice.id} rendered " +
                                "${generated.samples.size * 1000 / generated.sampleRate} ms " +
                                "in ${System.currentTimeMillis() - started} ms",
                        )
                        Synthesized(generated.samples, generated.sampleRate, "kokoro")
                    } else null
                } else {
                    val generated = piper(app)?.generate(text, 0, 1.0f)
                    if (generated != null && generated.samples.isNotEmpty()) {
                        GearslipLog.i(
                            "voice: preview piper rendered " +
                                "${generated.samples.size * 1000 / generated.sampleRate} ms",
                        )
                        Synthesized(generated.samples, generated.sampleRate, "piper")
                    } else null
                }
            }.getOrNull()
            if (myGeneration != generation.get() || audio == null) {
                main.post(onDone)
                return@execute
            }
            playAudio(audio, myGeneration, onDone, notifyOnInterrupt = true)
        }
        return true
    }

    /** The shared AudioTrack playback loop for [play] and [playPreview]. */
    private fun playAudio(audio: Synthesized, myGeneration: Int, onDone: () -> Unit, notifyOnInterrupt: Boolean) {
        val shorts = floatsToShorts(audio.samples)
        val track = AudioTrack.Builder()
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
        try {
            track.play()
            var offset = 0
            while (offset < shorts.size && myGeneration == generation.get()) {
                val written = track.write(shorts, offset, shorts.size - offset)
                if (written <= 0) break
                offset += written
            }
        } catch (e: Exception) {
            GearslipLog.w("voice: playback failed: ${e.message}")
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
            if (currentTrack === track) currentTrack = null
        }
        if (myGeneration == generation.get() || notifyOnInterrupt) main.post(onDone)
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
