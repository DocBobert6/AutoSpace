package app.autospace.speech

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import app.autospace.CrashReporter
import app.autospace.GearslipLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The one-shot 45-second launch voice trace (Robert, 2026-10-25):
 * he arms it in phone Settings, force-stops the app, opens it fresh,
 * and tries the voice previews inside the first 45 seconds. At 45
 * seconds the trace writes itself to Downloads/AutoSpace and disarms
 * — it never records perpetually, never re-arms on its own, and never
 * records outside the armed window.
 *
 * What it records: a header (app version, device, both engines'
 * Settings state AND live validation result AND load-check sizes at
 * launch, selected voice) plus every GearslipLog line inside the
 * window — which, with the round-3 stage markers and the preview-tap
 * line, is the full stage chain of any voice attempt in the window:
 * tap received (with readiness at tap time), load attempted /
 * refused / failed with reason, load end, generate outcome,
 * playback outcome. A tap that never reaches generate still leaves
 * its tap line, so "tap ignored" and "generate failed" are visibly
 * different outcomes in the file.
 *
 * Privacy, non-negotiable, same rule as the crash reports: the trace
 * carries engine stages, file states, and error classes ONLY. It
 * never carries spoken text (VoiceEngine logs engine + durations
 * only), message text/titles/senders, hotword transcripts, contacts,
 * or numbers — the shared CrashReporter content filter drops the
 * known content-bearing lines — and it never captures microphone
 * audio. Nothing is uploaded; the file is local only.
 *
 * Limits, stated plainly: the window is exactly 45 seconds from
 * process start (GearslipActivity.onCreate), so a preview tried at
 * second 46 is not in the file; and if the process dies inside the
 * window, no trace is written (re-arm and try again — the crash
 * reporter still files its own report for a death).
 */
object VoiceTrace {

    /** Exactly 45 seconds from launch, as Robert asked. */
    const val WINDOW_MS = 45_000L

    /**
     * Per-build label, bumped by hand on EVERY push, so a trace file
     * can never again be mistaken for another build's: versionName
     * and versionCode never change across debug builds, and round 7
     * proved a trace without a build tag is unattributable — the
     * 18:03 trace fingerprinted as 6122a199 only by which markers it
     * lacked (tap lines present, load-end lines present, playback
     * lines absent), while its sender believed it ran the drain
     * build. The tag is printed in every trace header and logged at
     * window start; the matching test below pins its value, so a
     * push that forgets to bump it fails CI instead of shipping an
     * ambiguous trace. NEVER reuse a tag value across pushes.
     */
    const val BUILD_TAG = "r8"

    private const val PREFS = "voice_trace"
    private const val KEY_ARMED = "armed"
    private const val MAX_LINES = 8000
    private const val RELATIVE_PATH = "Download/AutoSpace"

    @Volatile private var capturing = false
    private val lines = mutableListOf<String>()
    private var app: Context? = null
    private var header: String = ""
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val sink: (String) -> Unit = { line ->
        synchronized(lines) {
            if (lines.size < MAX_LINES) lines.add(line)
        }
    }

    fun isArmed(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ARMED, false)

    fun arm(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ARMED, true).apply()
    }

    fun disarm(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ARMED, false).apply()
    }

    /** Pure filename shape, so unit tests can pin it without a Context. */
    fun traceFileName(stamp: String): String = "autospace-launch-trace-$stamp.txt"

    /**
     * Starts the window on a fresh process start, and only then: the
     * caller (GearslipActivity.onCreate) runs on every activity
     * creation, so the armed flag plus the capturing guard are what
     * keep a rotation or a second onCreate from restarting it.
     */
    fun beginIfArmed(context: Context) {
        val appCtx = context.applicationContext
        if (!isArmed(appCtx) || capturing) return
        capturing = true
        app = appCtx
        synchronized(lines) { lines.clear() }
        // Header first, from live values (not the Settings state flow
        // alone): the flow is computed at VoiceModels.init and only
        // changes on download/delete, while validationFailure reads
        // the disk now — a trace that showed only the flow could hide
        // exactly the state/load disagreement this trace exists to
        // catch. init() is idempotent, so calling it here is safe.
        VoiceModels.init(appCtx)
        header = buildHeader(appCtx)
        GearslipLog.addSink(sink)
        GearslipLog.i("voice: launch trace started — recording 45 seconds from launch")
        GearslipLog.i("voice: launch trace build $BUILD_TAG")
        main.postDelayed({ export() }, WINDOW_MS)
    }

    private fun buildHeader(appCtx: Context): String {
        val info = runCatching { appCtx.packageManager.getPackageInfo(appCtx.packageName, 0) }.getOrNull()
        val version = "${info?.versionName ?: "?"} (${info?.longVersionCode ?: 0})"
        val device = "${Build.MANUFACTURER} ${Build.MODEL}, " +
            "Android ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE}), " +
            Build.SUPPORTED_ABIS.joinToString()
        return buildString {
            appendLine("AutoSpace launch voice trace — 45 seconds from fresh launch")
            appendLine("Written: ${stamp()} (epoch ${System.currentTimeMillis()})")
            appendLine("App: $version")
        appendLine("Build tag: $BUILD_TAG")
            appendLine("Device: $device")
            appendLine("Local only: this file was never uploaded anywhere.")
            appendLine("Records engine stages, file states and error classes ONLY — never spoken text,")
            appendLine("message text, contacts, numbers, or microphone audio.")
            appendLine()
            appendLine("Engine state at launch (Settings state | live validation | load-check sizes):")
            VoiceModels.Engine.entries.forEach { engine ->
                val state = VoiceModels.state(engine).value
                val failure = VoiceModels.validationFailure(appCtx, engine)
                appendLine(
                    "- ${engine.label}: state=$state | " +
                        "validation=${failure ?: "ok"} | ${VoiceModels.loadCheck(appCtx, engine)}",
                )
            }
            appendLine("Selected Kokoro voice: ${VoiceModels.selectedVoice.value.id} " +
                "(sid ${VoiceModels.selectedVoice.value.sid})")
            appendLine()
            appendLine("--- trace lines (45 seconds from launch; content-bearing lines filtered out) ---")
        }
    }

    private fun export() {
        val ctx = app ?: return
        runCatching { GearslipLog.removeSink(sink) }
        capturing = false
        disarm(ctx)
        val kept = synchronized(lines) { CrashReporter.filterContentLines(lines.toList()) }
        val text = buildString {
            append(header)
            kept.forEach { appendLine(it) }
            appendLine("--- end of trace (${kept.size} lines) ---")
        }
        val name = traceFileName(fileStamp())
        io.execute {
            val where = writeTrace(ctx, name, text)
            GearslipLog.i("voice: launch trace written to $where")
        }
    }

    /**
     * Same export route as the crash reports: Downloads/AutoSpace via
     * MediaStore (no storage permission needed for files this app
     * itself creates there), app-files copy when MediaStore fails.
     */
    private fun writeTrace(appCtx: Context, name: String, text: String): String {
        val viaStore = runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            }
            val uri = appCtx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert returned null")
            appCtx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: error("MediaStore output stream was null")
            "Downloads/AutoSpace/$name"
        }
        if (viaStore.isSuccess) return viaStore.getOrThrow()
        val fallback = File(File(appCtx.filesDir, "launch-traces"), name)
        runCatching {
            fallback.parentFile?.mkdirs()
            fallback.writeText(text)
        }
        return "app files: ${fallback.absolutePath} (MediaStore failed: ${viaStore.exceptionOrNull()?.message})"
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun fileStamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}
