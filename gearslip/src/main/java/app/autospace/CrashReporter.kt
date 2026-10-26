package app.autospace

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local-only crash reports: timestamped .txt files in the phone's
 * Downloads/AutoSpace folder. Nothing is ever uploaded, no Crashlytics
 * or network is involved, there is no next-start nag dialog, and no
 * crash-test button ships anywhere — the reports exist only so the
 * next crash (Java or native) leaves something readable behind.
 *
 * Two mechanisms, because one cannot cover the other:
 *
 * 1. Java uncaught handler. Installed at the earliest start
 * (GearslipActivity.onCreate, the single activity for both the phone
 * UI and the car session, so one install covers both) BEFORE
 * GearslipLog.installCrashHandler, so the chain runs: GearslipLog's
 * handler first (session report + FATAL lines into the log file),
 * then this one (writes the .txt, including those FATAL lines in its
 * log tail), then the system's handler, which terminates the app.
 * The app still terminates — nothing here swallows, restarts, or
 * shows UI from the handler. The handler does only synchronous work
 * on the crashing thread (snapshot + file write, both runCatching);
 * it never touches the log writer's executor, which may be dead.
 *
 * 2. Unclean-shutdown detection, for the case the Java handler can
 * never see: a NATIVE crash (a sherpa-onnx segfault, the suspected
 * voice-crash shape) kills the process below the JVM, so no Java
 * handler runs at all. A marker file is written at every start and
 * deleted only on a clean activity destroy; a start that finds a
 * marker left behind therefore means the previous run did NOT exit
 * cleanly, and it writes an "unexpected shutdown" .txt with the
 * previous session's last log lines. That report is CONTEXT, not a
 * native trace: with the Priority 1 before/after native-generate
 * markers, its last line is the step the process died in (or just
 * after), which is what pins a native crash's location. It is
 * labelled as unconfirmed on its face, because the system killing
 * the process (e.g. after an accessory detach) leaves the same
 * marker — a false positive is possible, and the report says so.
 * A Java crash that already wrote its own .txt deletes the marker
 * first, so one crash never produces two reports.
 *
 * Privacy, audited 2026-10-25: spoken text is never logged
 * (VoiceEngine logs engine + durations only), message text/titles/
 * senders are never logged (VoiceReply logs only the app label),
 * hotword transcripts are never logged, and no contact or number is
 * logged. The exceptions found are filtered out of report tails
 * below: the TestMessage debug path logs its test text, the media
 * status line logs the now-playing title/artist, and the wireless
 * session line logs a local IP address. Report tails drop any line
 * matching those filters, so a report carries diagnostics, not
 * content.
 */
object CrashReporter {

    private const val DIR_NAME = "crash-reports"
    private const val MARKER_NAME = "running.marker"
    private const val TAIL_LINES = 500
    private const val RELATIVE_PATH = "Download/AutoSpace"

    /** Log-tail lines that must never reach a report (see the header). */
    private val TAIL_FILTERS = listOf(
        "test message:",
        "media status: -> track",
        "starting a session over Wi-Fi to",
        // AI key guard (r25, Groq-shaped tokens r40): any tail line containing a
        // Gemini- or Groq-shaped key token is dropped whole — the second guard
        // beside GearslipLog's scrub.
        "AIza",
        "gsk_",
    )

    private fun markerFile(context: Context): File =
        File(File(context.filesDir, DIR_NAME), MARKER_NAME).apply { parentFile?.mkdirs() }

    /**
     * Earliest-start install: checks the previous run, writes this
     * run's marker, and installs the Java handler. Call this BEFORE
     * GearslipLog.init (so the previous session's log file is still
     * the newest one) and BEFORE GearslipLog.installCrashHandler
     * (so the handler chain runs log-recording first, txt second,
     * system termination last).
     */
    fun install(context: Context) {
        val app = context.applicationContext
        runCatching { checkUncleanShutdown(app) }
        runCatching {
            markerFile(app).writeText(
                "started=${stamp()} version=${appVersion(app)} epoch=${System.currentTimeMillis()}\n",
            )
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                writeReport(
                    app,
                    kind = "Java crash",
                    detail = "Thread: ${thread.name}\n\n${throwable.stackTraceToString()}",
                    tail = GearslipLog.snapshot(),
                )
                // Recorded as a crash already: do not also file an
                // unclean-shutdown report for this run on next start.
                runCatching { markerFile(app).delete() }
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                // No system handler to chain to: terminate explicitly
                // rather than leaving a half-dead process behind.
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
        GearslipLog.i("crash reports: writing to Downloads/AutoSpace (local only, never uploaded)")
    }

    /** Deletes the run marker: call only from a genuinely clean exit (activity destroy). */
    fun markCleanExit(context: Context) {
        runCatching { markerFile(context.applicationContext).delete() }
    }

    private fun checkUncleanShutdown(app: Context) {
        val marker = markerFile(app)
        if (!marker.isFile) return
        val started = runCatching { marker.readText().trim() }.getOrDefault("(unreadable marker)")
        val prevLog = previousSessionLog(app)
        val tail = prevLog?.let { readTail(it) } ?: listOf("(previous session log not found)")
        writeReport(
            app,
            kind = "Unexpected shutdown (unconfirmed)",
            detail = "The previous run did not exit cleanly, and no Java crash report was filed for it.\n" +
                "This is CONTEXT, not a confirmed crash and not a native trace: it may also be the\n" +
                "system killing the process (for example after an accessory detach).\n" +
                "Previous run marker: $started\n" +
                "Previous session log: ${prevLog?.name ?: "(none)"}\n" +
                "If the last log line below is a \"native generate begin\" line with no matching\n" +
                "\"native generate end\" line, the process died inside that native call.",
            tail = tail,
        )
        GearslipLog.i("crash reports: previous run ended uncleanly, unexpected-shutdown report written")
    }

    /** The newest session log file in external files (gearslip-<timestamp>.log). */
    private fun previousSessionLog(app: Context): File? {
        val dir = app.getExternalFilesDir(null) ?: return null
        return dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("gearslip-") && it.name.endsWith(".log") }
            ?.maxByOrNull { it.lastModified() }
    }

    private fun readTail(file: File): List<String> {
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        return lines.takeLast(TAIL_LINES)
    }

    private fun appVersion(app: Context): String {
        val info = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        return "${info?.versionName ?: "?"} (${info?.longVersionCode ?: 0})"
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun fileStamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    /**
     * The content filter without the tail truncation: the launch
     * voice trace reuses exactly this predicate (not a copy) so a
     * report and a trace can never drift apart on what they drop.
     */
    internal fun filterContentLines(lines: List<String>): List<String> =
        lines.filter { line -> TAIL_FILTERS.none { line.contains(it) } }

    private fun filteredTail(tail: List<String>): List<String> =
        filterContentLines(tail).takeLast(TAIL_LINES)

    private fun buildText(app: Context, kind: String, detail: String, tail: List<String>): String {
        val kept = filteredTail(tail)
        return buildString {
            appendLine("AutoSpace crash report — $kind")
            appendLine("Written: ${stamp()} (epoch ${System.currentTimeMillis()})")
            appendLine("App: ${appVersion(app)}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE}), ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Local only: this file was never uploaded anywhere.")
            appendLine()
            appendLine(detail.trimEnd())
            appendLine()
            appendLine("--- last ${kept.size} log lines (content-bearing lines filtered out) ---")
            kept.forEach { appendLine(it) }
        }
    }

    /**
     * Writes one report to Downloads/AutoSpace via MediaStore
     * (RELATIVE_PATH, minSdk 31: no storage permission is needed for
     * files this app itself creates there — verified against the
     * scoped-storage rule, not assumed from an old permission model).
     * Falls back to an app-files copy (filesDir/crash-reports) when
     * MediaStore fails, so a report is never lost to a resolver error.
     * Returns where it landed, for the log line only — never content.
     */
    private fun writeReport(app: Context, kind: String, detail: String, tail: List<String>): String {
        val text = buildText(app, kind, detail, tail)
        val name = "autospace-${if (kind.startsWith("Java")) "crash" else "shutdown"}-${fileStamp()}.txt"
        val viaStore = runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            }
            val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert returned null")
            app.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: error("MediaStore output stream was null")
            "Downloads/AutoSpace/$name"
        }
        if (viaStore.isSuccess) return viaStore.getOrThrow()
        val fallback = File(File(app.filesDir, DIR_NAME), name)
        runCatching {
            fallback.parentFile?.mkdirs()
            fallback.writeText(text)
        }
        return "app files: ${fallback.absolutePath} (MediaStore failed: ${viaStore.exceptionOrNull()?.message})"
    }
}
