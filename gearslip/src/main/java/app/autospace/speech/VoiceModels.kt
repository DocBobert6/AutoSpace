package app.autospace.speech

import android.content.Context
import app.autospace.GearslipLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * The voices AutoSpace speaks with, downloaded once and kept in the app's own
 * storage — nothing ships in the APK. Kokoro-82M is the voice; Piper is the
 * fallback when Kokoro isn't downloaded or a synthesis fails. System TTS is
 * not an option anywhere: if neither is downloaded, AutoSpace stays silent
 * and Settings says so, rather than borrowing the phone's voice.
 *
 * Kokoro voices (sherpa-onnx's kokoro-multi-lang-v1_0, 53 speakers, sid map
 * from sherpa-onnx's own speaker table): am_adam is the default, not the
 * only voice — every voice below is selectable, and the choice is just a
 * speaker id into the same downloaded voices.bin. Piper is one voice,
 * en_US-amy-medium, from sherpa-onnx's vits-piper-en_US-amy-medium package.
 *
 * Both engines are sherpa-onnx (Apache-2.0) and share one espeak-ng-data
 * each, downloaded per engine from the same Hugging Face repo as its model
 * files (sherpa-onnx's author publishes each model twice: as a .tar.bz2 on
 * GitHub and as plain files on Hugging Face, the same pattern SpeechModels
 * already uses for the listening models). espeak-ng-data is a directory, so
 * it is listed through Hugging Face's tree API and fetched file by file;
 * a missing directory fails the download visibly instead of silently
 * leaving a voice that can never speak.
 */
object VoiceModels {

    /** One Kokoro voice: its id, its speaker id in voices.bin, and its language. */
    data class KokoroVoice(val id: String, val sid: Int, val language: String)

    /**
     * Every voice in kokoro-multi-lang-v1_0, in sid order. Language from the
     * id's first letter (a = American English, b = British English, e =
     * Spanish, f = French, h = Hindi, i = Italian, j = Japanese,
     * p = Portuguese, z = Mandarin); the second letter is gender.
     */
    val KOKORO_VOICES: List<KokoroVoice> = listOf(
        KokoroVoice("af_alloy", 0, "American English"),
        KokoroVoice("af_aoede", 1, "American English"),
        KokoroVoice("af_bella", 2, "American English"),
        KokoroVoice("af_heart", 3, "American English"),
        KokoroVoice("af_jessica", 4, "American English"),
        KokoroVoice("af_kore", 5, "American English"),
        KokoroVoice("af_nicole", 6, "American English"),
        KokoroVoice("af_nova", 7, "American English"),
        KokoroVoice("af_river", 8, "American English"),
        KokoroVoice("af_sarah", 9, "American English"),
        KokoroVoice("af_sky", 10, "American English"),
        KokoroVoice("am_adam", 11, "American English"),
        KokoroVoice("am_echo", 12, "American English"),
        KokoroVoice("am_eric", 13, "American English"),
        KokoroVoice("am_fenrir", 14, "American English"),
        KokoroVoice("am_liam", 15, "American English"),
        KokoroVoice("am_michael", 16, "American English"),
        KokoroVoice("am_onyx", 17, "American English"),
        KokoroVoice("am_puck", 18, "American English"),
        KokoroVoice("am_santa", 19, "American English"),
        KokoroVoice("bf_alice", 20, "British English"),
        KokoroVoice("bf_emma", 21, "British English"),
        KokoroVoice("bf_isabella", 22, "British English"),
        KokoroVoice("bf_lily", 23, "British English"),
        KokoroVoice("bm_daniel", 24, "British English"),
        KokoroVoice("bm_fable", 25, "British English"),
        KokoroVoice("bm_george", 26, "British English"),
        KokoroVoice("bm_lewis", 27, "British English"),
        KokoroVoice("ef_dora", 28, "Spanish"),
        KokoroVoice("em_alex", 29, "Spanish"),
        KokoroVoice("ff_siwis", 30, "French"),
        KokoroVoice("hf_alpha", 31, "Hindi"),
        KokoroVoice("hf_beta", 32, "Hindi"),
        KokoroVoice("hm_omega", 33, "Hindi"),
        KokoroVoice("hm_psi", 34, "Hindi"),
        KokoroVoice("if_sara", 35, "Italian"),
        KokoroVoice("im_nicola", 36, "Italian"),
        KokoroVoice("jf_alpha", 37, "Japanese"),
        KokoroVoice("jf_gongitsune", 38, "Japanese"),
        KokoroVoice("jf_nezumi", 39, "Japanese"),
        KokoroVoice("jf_tebukuro", 40, "Japanese"),
        KokoroVoice("jm_kumo", 41, "Japanese"),
        KokoroVoice("pf_dora", 42, "Portuguese"),
        KokoroVoice("pm_alex", 43, "Portuguese"),
        KokoroVoice("pm_santa", 44, "Portuguese"),
        KokoroVoice("zf_xiaobei", 45, "Mandarin"),
        KokoroVoice("zf_xiaoni", 46, "Mandarin"),
        KokoroVoice("zf_xiaoxiao", 47, "Mandarin"),
        KokoroVoice("zf_xiaoyi", 48, "Mandarin"),
        KokoroVoice("zm_yunjian", 49, "Mandarin"),
        KokoroVoice("zm_yunxi", 50, "Mandarin"),
        KokoroVoice("zm_yunxia", 51, "Mandarin"),
        KokoroVoice("zm_yunyang", 52, "Mandarin"),
    )

    /** The default voice: American Adam, sid 11. */
    val DEFAULT_VOICE: KokoroVoice = KOKORO_VOICES.first { it.id == "am_adam" }

    /**
     * The four featured voices on the picker's main page, in the order
     * Robert listed them (hyphens in his message are underscores in the
     * model ids). Presentation only: every other voice stays selectable
     * on the "More voices" page, and the default above is unchanged.
     */
    val FEATURED_VOICES: List<KokoroVoice> = listOf("am_michael", "bm_george", "af_heart", "bf_emma")
        .mapNotNull { id -> KOKORO_VOICES.firstOrNull { it.id == id } }

    /** Every Kokoro voice not featured — the "More voices" page. */
    val MORE_VOICES: List<KokoroVoice> = KOKORO_VOICES.filter { it !in FEATURED_VOICES }

    /**
     * The phrase a voice preview speaks: a common GPS instruction, in
     * the previewed voice, without changing the stored selection.
     */
    const val PREVIEW_PHRASE = "In 500 feet, turn left."

    /**
     * The espeak voice sherpa-onnx phonemizes with for a Kokoro voice, from
     * the voice id's first letter. Best-known espeak names; a wrong one
     * fails synthesis audibly (the engine falls back to Piper) rather than
     * silently speaking the wrong language, and the car test is the check
     * for the non-English voices.
     */
    fun espeakLang(voice: KokoroVoice): String = when (voice.id.firstOrNull()) {
        'a', 'b' -> "en"
        'e' -> "es"
        'f' -> "fr"
        'h' -> "hi"
        'i' -> "it"
        'j' -> "ja"
        'p' -> "pt"
        'z' -> "cmn"
        else -> "en"
    }

    enum class Engine(
        val id: String,
        val label: String,
        val detail: String,
        val downloadMb: Int,
        val repo: String,
        /** Top-level files, in download order. */
        val files: List<String>,
    ) {
        KOKORO(
            "kokoro", "Kokoro",
            "Kokoro-82M (hexgrad, Apache-2.0), 53 voices, American Adam by default. The main voice.",
            350, "csukuangfj/kokoro-multi-lang-v1_0",
            listOf("model.onnx", "voices.bin", "tokens.txt"),
        ),
        PIPER(
            "piper", "Piper — Amy",
            "Piper VITS voice en_US-amy-medium (rhasspy/piper-voices, MIT), via sherpa-onnx. The fallback when Kokoro isn't downloaded or fails.",
            68, "csukuangfj/vits-piper-en_US-amy-medium",
            listOf("en_US-amy-medium.onnx", "tokens.txt"),
        ),
    }

    sealed interface State {
        data object Missing : State
        data class Downloading(val percent: Int) : State
        data object Ready : State
        data class Failed(val reason: String) : State
    }

    private const val PREFS = "voice"
    private const val KEY_VOICE = "kokoro_voice"

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "gearslip-voice-download") }
    private val states = Engine.entries.associateWith { MutableStateFlow<State>(State.Missing) }
    private val _selectedVoice = MutableStateFlow(DEFAULT_VOICE)

    /** The Kokoro voice that speaks, when Kokoro is the engine. */
    val selectedVoice: StateFlow<KokoroVoice> = _selectedVoice.asStateFlow()
    @Volatile private var cancelled: Engine? = null
    @Volatile private var initialised = false

    fun init(context: Context) {
        if (initialised) return
        initialised = true
        val app = context.applicationContext
        val id = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VOICE, null)
        _selectedVoice.value = KOKORO_VOICES.firstOrNull { it.id == id } ?: DEFAULT_VOICE
        Engine.entries.forEach { e ->
            val failure = validationFailure(app, e)
            states.getValue(e).value = when {
                failure == null -> State.Ready
                anyFilePresent(app, e) -> State.Failed(failure)
                else -> State.Missing
            }
        }
        // A voice an older build offered and this one doesn't: storage nobody can use.
        File(app.filesDir, "voice").listFiles()
            ?.filter { it.isDirectory && it.name.removeSuffix(".partial") !in Engine.entries.map { e -> e.id } }
            ?.forEach {
                GearslipLog.i("voice: removing ${it.name}, no longer offered")
                it.deleteRecursively()
            }
    }

    fun state(engine: Engine): StateFlow<State> = states.getValue(engine).asStateFlow()

    fun selectVoice(context: Context, voice: KokoroVoice) {
        _selectedVoice.value = voice
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_VOICE, voice.id).apply()
        VoiceEngine.unload()
    }

    fun dir(context: Context, engine: Engine) = File(File(context.filesDir, "voice"), engine.id)

    fun espeakDir(context: Context, engine: Engine) = File(dir(context, engine), "espeak-ng-data")

    /**
     * Ready means the engine's own files are all there and espeak-ng-data
     * has its main phoneme table — the two things synthesis actually opens.
     *
     * 2026-10-25 assistant-crash hardening: presence alone is NOT enough.
     * A download that exists but is truncated (killed download, partial
     * file left behind by an older build, corrupt storage) used to count
     * as Ready, so VoiceEngine handed its files to sherpa-onnx and a
     * native generate on a corrupt model killed the whole process — no
     * Java exception, no dialog, phone and Android Auto gone together.
     * [validationFailure] therefore also floors every file at a
     * plausible size: a real Kokoro model.onnx is hundreds of MB, a
     * real Piper model tens of MB, and a voices.bin holding 53 voices
     * is not a few bytes. The floors are deliberately far below the
     * real sizes (so a future smaller model still passes) and far
     * above zero (so a truncated stub fails). A download that fails
     * this check is Failed, never Ready, and synthesis never attempts
     * it — the caller falls back (Kokoro -> Piper -> no-voice warning).
     * This cannot catch every corruption (a full-size file with bad
     * bytes inside still passes), which is why VoiceEngine also logs
     * markers around every native generate: the next crash's last log
     * line then names the exact step that died.
     */
    fun isReady(context: Context, engine: Engine): Boolean = validationFailure(context, engine) == null

    /** Null when [engine]'s on-disk set is usable; else a plain reason for Settings. */
    fun validationFailure(context: Context, engine: Engine): String? {
        val d = dir(context, engine)
        for (name in engine.files) {
            val f = File(d, name)
            if (!f.isFile) return "${engine.label} files incomplete — delete and re-download"
            val min = minFileBytes(engine, name)
            if (f.length() < min) return "${engine.label} files incomplete — delete and re-download"
        }
        val phondata = File(espeakDir(context, engine), "phondata")
        if (!phondata.isFile || phondata.length() < MIN_SMALL_FILE_BYTES) {
            return "${engine.label} files incomplete — delete and re-download"
        }
        return null
    }

    /** True when any of [engine]'s files exist at all (a partial/corrupt set, not a clean Missing). */
    private fun anyFilePresent(context: Context, engine: Engine): Boolean {
        val d = dir(context, engine)
        return engine.files.any { File(d, it).isFile } ||
            File(espeakDir(context, engine), "phondata").isFile
    }

    private const val MIN_KOKORO_MODEL_BYTES = 10_000_000L
    private const val MIN_PIPER_MODEL_BYTES = 1_000_000L
    private const val MIN_VOICES_BIN_BYTES = 1_000L
    private const val MIN_SMALL_FILE_BYTES = 1_000L

    /**
     * Plausible-size floor for one downloaded file, in bytes. A file below
     * its floor counts as truncated, never as a usable download.
     *
     * 2026-10-25 fix (Robert: both engines failed every download with
     * "files incomplete"): tokens.txt files are TINY by nature. Live
     * Hugging Face sizes verified 2026-10-25 via the repos' own API
     * listings: Kokoro tokens.txt is 687 bytes, Piper tokens.txt is
     * 921 bytes — both BELOW the old single 1,000-byte small-file
     * floor. So every complete download of both engines failed its
     * own final validation and reported "files incomplete", forever,
     * no matter how many times it was retried. The tokens floor is
     * therefore its own, far below both real files (687/921) and far
     * above an empty stub. Every other floor is unchanged: model
     * floors stay far below the real models (Kokoro model.onnx
     * 325,560,556 bytes, Piper model 63,201,425 bytes), and
     * phondata keeps the 1,000-byte floor against a real 550,424
     * bytes. Floors must be re-checked against live sizes before
     * they are ever raised again.
     */
    fun minFileBytes(engine: Engine, name: String): Long = when {
        engine == Engine.KOKORO && name == "model.onnx" -> MIN_KOKORO_MODEL_BYTES
        engine == Engine.KOKORO && name == "voices.bin" -> MIN_VOICES_BIN_BYTES
        engine == Engine.PIPER && name == "en_US-amy-medium.onnx" -> MIN_PIPER_MODEL_BYTES
        name == "tokens.txt" -> MIN_TOKENS_BYTES
        else -> MIN_SMALL_FILE_BYTES
    }

    private const val MIN_TOKENS_BYTES = 100L

    fun delete(context: Context, engine: Engine) {
        cancelled = engine
        dir(context, engine).deleteRecursively()
        states.getValue(engine).value = State.Missing
        VoiceEngine.unload()
    }

    fun download(context: Context, engine: Engine) {
        val app = context.applicationContext
        val state = states.getValue(engine)
        if (state.value is State.Downloading || state.value == State.Ready) return
        state.value = State.Downloading(0)
        cancelled = null
        worker.execute {
            val target = dir(app, engine)
            val partial = File(target.parentFile, "${engine.id}.partial").apply { deleteRecursively(); mkdirs() }
            var stage = "starting download"
            try {
                // 2026-10-25: retry from Failed clears the incomplete set
                // FIRST, so a retry can never be poisoned by old files —
                // this is what the Settings text ("delete and
                // re-download") promises and the Download button in
                // Failed state now actually does. The set being deleted
                // is Failed, so synthesis never uses it (VoiceEngine
                // only loads when isReady, i.e. validation passes);
                // a fresh set is swapped in only after it validates,
                // below, so a failed retry leaves Missing, not a
                // half-old, half-new set.
                target.deleteRecursively()
                val total = engine.downloadMb * 1_048_576L
                var done = 0L
                fun progress(read: Long) {
                    if (cancelled == engine) throw InterruptedException("cancelled")
                    state.value = State.Downloading((((done + read) * 100) / total).toInt().coerceIn(0, 99))
                }
                for (name in engine.files) {
                    stage = "downloading $name"
                    val conn = open("https://huggingface.co/${engine.repo}/resolve/main/$name")
                    val length = conn.contentLengthLong
                    val file = File(partial, name)
                    CountingStream(conn.inputStream, ::progress).use { input ->
                        file.outputStream().use { input.copyTo(it, 256 * 1024) }
                    }
                    check(length <= 0 || file.length() == length) { "$name arrived cut short" }
                    done += file.length()
                }
                // espeak-ng-data is a directory: list it, then fetch file by file.
                stage = "listing espeak-ng-data"
                val listed = listHfDir(engine.repo, "espeak-ng-data")
                check(listed.isNotEmpty()) { "espeak-ng-data was not listed in ${engine.repo}" }
                for (path in listed) {
                    stage = "downloading $path"
                    val conn = open("https://huggingface.co/${engine.repo}/resolve/main/$path")
                    val length = conn.contentLengthLong
                    val file = File(partial, path).apply { parentFile?.mkdirs() }
                    CountingStream(conn.inputStream, ::progress).use { input ->
                        file.outputStream().use { input.copyTo(it, 256 * 1024) }
                    }
                    check(length <= 0 || file.length() == length) { "$path arrived cut short" }
                    done += file.length()
                }
                check(engine.files.all { File(partial, it).isFile }) { "some files were missing" }
                check(File(partial, "espeak-ng-data/phondata").isFile) { "espeak-ng-data arrived without phondata" }
                check(partial.renameTo(target)) { "couldn't store the voice" }
                stage = "validating download"
                val failure = validationFailure(app, engine)
                check(failure == null) { failure ?: "voice validation failed" }
                GearslipLog.i("voice: downloaded ${engine.label}")
                state.value = State.Ready
            } catch (e: Exception) {
                partial.deleteRecursively()
                if (cancelled == engine) {
                    state.value = State.Missing
                } else {
                    // 2026-10-25: a failure names its stage and file,
                    // so a download error can no longer masquerade as
                    // a validation failure (or vice versa). The
                    // validation stage keeps its plain reason verbatim,
                    // so "files incomplete" still reads exactly as
                    // before when validation genuinely fails. open()
                    // already embeds the HTTP status ("the server
                    // answered 404"), so a path error, a network
                    // error, and a validation failure are now three
                    // visibly different messages in Settings.
                    val reason = if (stage == "validating download") {
                        e.message ?: e.javaClass.simpleName
                    } else {
                        "$stage: ${e.message ?: e.javaClass.simpleName}"
                    }
                    GearslipLog.w("voice: download of ${engine.label} failed: $reason")
                    state.value = State.Failed(reason)
                }
            }
        }
    }

    private fun listHfDir(repo: String, dir: String): List<String> {
        val conn = open("https://huggingface.co/api/models/$repo/tree/main/$dir?recursive=true")
        val text = conn.inputStream.bufferedReader().readText()
        val out = mutableListOf<String>()
        val arr = JSONArray(text)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.optString("type") == "file") out += obj.getString("path")
        }
        return out
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }.also { check(it.responseCode == 200) { "the server answered ${it.responseCode}" } }

    /** Counts bytes as they pass, reporting about every megabyte. */
    private class CountingStream(
        private val inner: java.io.InputStream,
        private val onProgress: (Long) -> Unit,
    ) : java.io.InputStream() {
        private var read = 0L
        private var reported = 0L

        override fun read(): Int = inner.read().also { if (it >= 0) count(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count(it.toLong()) }
        override fun close() = inner.close()

        private fun count(n: Long) {
            read += n
            if (read - reported >= 1_048_576) {
                reported = read
                onProgress(read)
            }
        }
    }
}
