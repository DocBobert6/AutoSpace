package app.autospace.car

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException

/**
 * Shared plumbing for the downloads workers (r160, downloads slice 4).
 * The HLS worker (r159) keeps its own private copies of the publish
 * and progress helpers, untouched — this file exists so the DASH and
 * segmented workers do not each reinvent them, and so the pure
 * decisions (backfill matching, segment math, file naming, the
 * Wi-Fi gate) stay JVM-testable without a framework.
 *
 * Temp dirs are per engine, so a delete for one record can never
 * sweep another engine's segments: HLS keeps its r159 path
 * (hls-tmp) so an in-flight r159 download resumes after the
 * upgrade, DASH gets dash-tmp, segmented gets seg-tmp.
 */

/** Temp dir for one worker record's in-progress bytes, by engine. */
internal fun downloadTempDir(
    context: Context,
    kind: BrowserStore.DownloadKind,
    recordId: String,
): File = when (kind) {
    BrowserStore.DownloadKind.HLS -> workerTempDir(context, recordId)
    BrowserStore.DownloadKind.DASH -> File(context.filesDir, "dash-tmp/$recordId")
    BrowserStore.DownloadKind.SEGMENTED -> File(context.filesDir, "seg-tmp/$recordId")
    BrowserStore.DownloadKind.PLAIN -> File(context.filesDir, "dl-tmp/$recordId")
}

/**
 * Publishes a finished file to the public Downloads folder via
 * MediaStore: IS_PENDING 1, copy, then 0. Returns the content URI
 * string, or null when publishing failed (a half-published entry
 * is deleted, never left behind). The same pattern the HLS
 * worker uses; the two copies are deliberately identical.
 */
internal fun publishWorkerFile(
    context: Context,
    file: File,
    displayName: String,
    mimeType: String,
): String? {
    val resolver = context.contentResolver
    val pending = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, pending) ?: return null
    return runCatching {
        resolver.openOutputStream(uri)?.use { output ->
            file.inputStream().buffered().use { input -> input.copyTo(output) }
        } ?: throw IOException("no MediaStore stream for $displayName")
        val done = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        resolver.update(uri, done, null, null)
        uri.toString()
    }.getOrElse {
        runCatching { resolver.delete(uri, null, null) }
        null
    }
}

/** Marks the record failed only while it is still in progress — never over a pause. */
internal fun markWorkerFailed(context: Context, recordId: String) {
    val current = BrowserStore.downloadById(context, recordId) ?: return
    if (current.state == BrowserStore.DownloadState.IN_PROGRESS) {
        BrowserStore.updateDownload(
            context,
            current.copy(state = BrowserStore.DownloadState.FAILED),
        )
    }
}

/** Progress writes, same guard: only while the record is in progress. */
internal fun updateWorkerProgress(context: Context, recordId: String, percent: Int) {
    val current = BrowserStore.downloadById(context, recordId) ?: return
    if (current.state == BrowserStore.DownloadState.IN_PROGRESS && current.progress != percent) {
        BrowserStore.updateDownload(context, current.copy(progress = percent))
    }
}

/**
 * The published file name for a worker output: the record's display
 * name with a playlist suffix stripped, then the output extension.
 * A name that already ends in that extension keeps it (no doubled
 * suffix); a blank name becomes "video" plus the extension.
 */
internal fun workerFinalFileName(fileName: String, stripSuffix: String, extension: String): String {
    val stripped = if (fileName.lowercase().endsWith(stripSuffix.lowercase())) {
        fileName.dropLast(stripSuffix.length)
    } else {
        fileName
    }
    val base = stripped.ifBlank { "video" }
    return if (base.lowercase().endsWith(extension)) base else base + extension
}

/** MIME type for a file name, from its extension; unknown extensions get a generic type. */
internal fun mimeForFileName(fileName: String): String {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    if (extension.isBlank()) return "*/*"
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
}

/**
 * Byte ranges for a segmented download: [partSizeBytes]-sized chunks
 * covering [totalBytes], the last one short when the total does not
 * divide evenly. Empty when the total or the part size is not
 * positive — the caller then falls back to a whole-file download
 * rather than inventing ranges.
 */
internal fun segmentRanges(totalBytes: Long, partSizeBytes: Long): List<LongRange> {
    if (totalBytes <= 0L || partSizeBytes <= 0L) return emptyList()
    val ranges = mutableListOf<LongRange>()
    var start = 0L
    while (start < totalBytes) {
        val end = minOf(start + partSizeBytes - 1L, totalBytes - 1L)
        ranges.add(start..end)
        start = end + 1L
    }
    return ranges
}

/** One MediaStore Downloads row, for backfill matching. */
internal data class BackfillCandidate(
    val displayName: String,
    val addedAtMs: Long,
    val uri: String,
)

/** How far a backfill candidate's date may sit from the record's creation time. */
internal const val BACKFILL_WINDOW_MS: Long = 2L * 24L * 60L * 60L * 1000L

/**
 * Pure backfill decision (r160): the surviving file for a record is
 * the one candidate whose display name matches the record's file
 * name exactly and whose date sits within the window of the
 * record's creation time. Zero matches or more than one match
 * refuses — never guess a file — so a moved file with no unique
 * match still reports as gone rather than recovering the wrong
 * file. The window bounds are inclusive.
 */
internal fun backfillMatch(
    candidates: List<BackfillCandidate>,
    fileName: String,
    createdAtMs: Long,
): String? {
    val matches = candidates.filter { candidate ->
        candidate.displayName == fileName &&
            kotlin.math.abs(candidate.addedAtMs - createdAtMs) <= BACKFILL_WINDOW_MS
    }
    return if (matches.size == 1) matches[0].uri else null
}

/**
 * Pure Wi-Fi gate (r160): a new download waits only when the
 * Wi-Fi-only setting is on and the network is not unmetered.
 * An unknown network counts as metered — waiting beats burning a
 * failure on a network the user told the app to avoid.
 */
internal fun shouldWaitForWifi(wifiOnly: Boolean, unmetered: Boolean): Boolean =
    wifiOnly && !unmetered

/** True when the active network reports itself unmetered (Wi-Fi, Ethernet, or similar). */
internal fun isUnmeteredNetwork(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}

/** WorkManager network constraint for one worker enqueue: unmetered only when Wi-Fi-only is on. */
internal fun workerNetworkConstraints(wifiOnly: Boolean): androidx.work.Constraints {
    val networkType = if (wifiOnly) {
        androidx.work.NetworkType.UNMETERED
    } else {
        androidx.work.NetworkType.NOT_REQUIRED
    }
    return androidx.work.Constraints.Builder().setRequiredNetworkType(networkType).build()
}
