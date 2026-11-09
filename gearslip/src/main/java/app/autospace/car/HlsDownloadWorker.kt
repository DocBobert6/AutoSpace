package app.autospace.car

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.autospace.GearslipLog
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The HLS download worker (r159, downloads slice 3). Given a record
 * for an .m3u8 URL, it fetches the master playlist, picks the
 * best-bandwidth variant, downloads that variant's segments, joins
 * them to one file, and publishes it through MediaStore into the
 * phone's public Downloads folder — the same IS_PENDING 1, copy,
 * 0 pattern the plain-download path's Mariner reference uses.
 *
 * Resume is by segment file, not by byte range: finished segments
 * sit in the record's temp dir (private app storage) under
 * index-based names, and a resumed run skips every segment file
 * that already exists with bytes. Pause is WorkManager
 * cancellation plus a PAUSED record state, so the worker's own
 * terminal writes are all conditional on the record still being
 * in progress — a pause that lands mid-run wins over a late
 * completion, and a failure never overwrites a pause.
 *
 * Refusals, all honest: encrypted playlists (EXT-X-KEY) and live
 * playlists (no EXT-X-ENDLIST) mark the record FAILED and never
 * produce a file, because joined ciphertext or a sliding-window
 * clip would be a broken file wearing a finished name. An empty
 * segment list likewise refuses rather than shipping a zero-byte
 * file. fMP4 variants (EXT-X-MAP or .m4s segments) join as init
 * segment plus media segments in order to a .mp4; MPEG-TS
 * variants join plainly to a .ts.
 *
 * No network constraint is set on the work request unless the
 * Wi-Fi-only setting is on (r160): then the enqueue carries an
 * UNMETERED constraint, so WorkManager itself holds the work
 * until Wi-Fi instead of failing it on cellular.
 */
class HlsDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val recordId = inputData.getString(KEY_RECORD_ID) ?: return@withContext Result.failure()
        val playlistUrl = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val record = BrowserStore.downloadById(applicationContext, recordId)
            ?: return@withContext Result.failure()
        if (record.state == BrowserStore.DownloadState.COMPLETE) return@withContext Result.success()
        if (record.kind != BrowserStore.DownloadKind.HLS) return@withContext Result.failure()
        // r203: the tap-time identity — the page as Referer, the
        // session cookies, and the tab's user agent — rides every
        // fetch below. The snapshot is host-scoped: it is replayed
        // only to the playlist's own host.
        val identity = BrowserDownloads.identityOf(record)
        val snapshot = identity.cookie?.takeIf { it.isNotBlank() }?.let { cookie ->
            BrowserDownloads.hostOfUrl(playlistUrl)?.let { BrowserDownloads.CookieSnapshot(it, cookie) }
        }
        try {
            updateProgress(recordId, 0)
            // Referer chaining (Mariner Beta.58(3)): the playlist
            // fetch carries the page referer; the variant fetch
            // carries the master URL; segments and the init
            // segment carry the media playlist URL.
            val masterText = fetchText(playlistUrl, identity, snapshot, record.isPrivate)
            val variantUri = HlsPlaylist.selectVariant(masterText)
            val mediaUrl = if (variantUri != null) {
                HlsPlaylist.resolveUrl(playlistUrl, variantUri)
            } else {
                playlistUrl
            }
            val mediaText = fetchText(mediaUrl, identityWithReferer(identity, playlistUrl), snapshot, record.isPrivate)
            val parsed = HlsPlaylist.parseMediaPlaylist(mediaText)
            if (parsed.encrypted || !parsed.hasEndList || parsed.segments.isEmpty()) {
                markFailed(recordId)
                return@withContext Result.failure()
            }
            val tempDir = workerTempDir(applicationContext, recordId)
            if (!tempDir.exists()) tempDir.mkdirs()
            val segmentUrls = parsed.segments.map { HlsPlaylist.resolveUrl(mediaUrl, it) }
            var done = 0
            for ((index, segmentUrl) in segmentUrls.withIndex()) {
                // A pause (or a delete) that landed since the last
                // segment stops this run here; the segments already
                // on disk are what the next run resumes from.
                val current = BrowserStore.downloadById(applicationContext, recordId)
                if (current == null || current.state != BrowserStore.DownloadState.IN_PROGRESS) {
                    return@withContext Result.success()
                }
                val target = segmentFile(tempDir, index, parsed.isFmp4)
                if (target.exists() && target.length() > 0L) {
                    done++
                } else {
                    fetchToFile(segmentUrl, target, identityWithReferer(identity, mediaUrl), snapshot, record.isPrivate)
                    done++
                }
                updateProgress(recordId, done * 100 / segmentUrls.size)
            }
            val joined = File(tempDir, if (parsed.isFmp4) "joined.mp4" else "joined.ts")
            val initFile = parsed.initSegmentUri?.let { initUri ->
                val initTarget = File(tempDir, "init.m4s")
                if (!initTarget.exists() || initTarget.length() == 0L) {
                    fetchToFile(HlsPlaylist.resolveUrl(mediaUrl, initUri), initTarget, identityWithReferer(identity, mediaUrl), snapshot, record.isPrivate)
                }
                initTarget
            }
            val segmentFiles = segmentUrls.indices.map { segmentFile(tempDir, it, parsed.isFmp4) }
            if (!joinSegments(segmentFiles, joined, initFile)) {
                markFailed(recordId)
                return@withContext Result.failure()
            }
            val extension = if (parsed.isFmp4) ".mp4" else ".ts"
            val mimeType = if (parsed.isFmp4) "video/mp4" else "video/mp2t"
            val displayName = finalFileName(record.fileName, extension)
            val published = publishToDownloads(applicationContext, joined, displayName, mimeType)
            if (published == null) {
                markFailed(recordId)
                return@withContext Result.failure()
            }
            val current = BrowserStore.downloadById(applicationContext, recordId)
            if (current == null || current.state != BrowserStore.DownloadState.IN_PROGRESS) {
                // A pause landed during publish: the published file is
                // not referenced by any record yet, so it is deleted
                // rather than orphaned in Downloads, and the record
                // keeps its paused state. The next resume re-joins
                // from the temp segments and publishes again.
                runCatching {
                    applicationContext.contentResolver.delete(android.net.Uri.parse(published), null, null)
                }
                return@withContext Result.success()
            }
            BrowserStore.updateDownload(
                applicationContext,
                current.copy(
                    state = BrowserStore.DownloadState.COMPLETE,
                    localSource = published,
                    progress = 100,
                    fileName = displayName,
                ),
            )
            tempDir.deleteRecursively()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GearslipLog.e("car browser HLS download FAILED: ${GearslipLog.loggableUrl(playlistUrl)} - ${e.message}")
            markFailed(recordId)
            Result.failure()
        }
    }

    /** Marks the record failed only while it is still in progress — never over a pause. */
    private fun markFailed(recordId: String) {
        val current = BrowserStore.downloadById(applicationContext, recordId) ?: return
        if (current.state == BrowserStore.DownloadState.IN_PROGRESS) {
            BrowserStore.updateDownload(
                applicationContext,
                current.copy(state = BrowserStore.DownloadState.FAILED),
            )
        }
    }

    /** Progress writes, same guard: only while the record is in progress. */
    private fun updateProgress(recordId: String, percent: Int) {
        val current = BrowserStore.downloadById(applicationContext, recordId) ?: return
        if (current.state == BrowserStore.DownloadState.IN_PROGRESS && current.progress != percent) {
            BrowserStore.updateDownload(applicationContext, current.copy(progress = percent))
        }
    }

    private fun fetchText(
        url: String,
        identity: BrowserDownloads.DownloadIdentity,
        snapshot: BrowserDownloads.CookieSnapshot?,
        isPrivate: Boolean,
    ): String {
        val connection = downloadConnection(url, identity, snapshot, isPrivate)
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode} for $url")
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Downloads one URL to [target] atomically: bytes land in a
     * sidecar file first and only rename over the target once whole,
     * so a resumed run never mistakes a half-written segment for a
     * finished one (the resume check also requires length > 0).
     */
    private fun fetchToFile(
        url: String,
        target: File,
        identity: BrowserDownloads.DownloadIdentity,
        snapshot: BrowserDownloads.CookieSnapshot?,
        isPrivate: Boolean,
    ) {
        val tmp = File(target.parentFile, "${target.name}.part")
        val connection = downloadConnection(url, identity, snapshot, isPrivate)
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode} for $url")
            }
            connection.inputStream.use { input ->
                tmp.outputStream().buffered().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("could not finish segment ${target.name}")
        }
    }

    /**
     * Publishes a finished file to the public Downloads folder via
     * MediaStore: IS_PENDING 1, copy, then 0. Returns the content URI
     * string, or null when publishing failed (a half-published entry
     * is deleted, never left behind).
     */
    private fun publishToDownloads(
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

    companion object {
        const val KEY_RECORD_ID = "record_id"
        const val KEY_URL = "url"
        const val KEY_REFERER = "referer"
        const val KEY_COOKIE = "cookie"
        const val KEY_USER_AGENT = "user_agent"

        /** The unique-work name for one record's HLS download. */
        fun workName(recordId: String): String = "hls-download-$recordId"

        /** Enqueues (or re-enqueues) the worker for [record] under its unique name. When [wifiOnly] is true the work carries an UNMETERED constraint, so WorkManager itself holds it until Wi-Fi — the record stays in progress, not failed, while it waits. */
        fun enqueue(
            context: Context,
            record: BrowserStore.DownloadRecord,
            replace: Boolean,
            wifiOnly: Boolean = false,
        ) {
            val request = androidx.work.OneTimeWorkRequestBuilder<HlsDownloadWorker>()
                .setInputData(
                    workDataOf(
                        KEY_RECORD_ID to record.id,
                        KEY_URL to record.url,
                        KEY_REFERER to (record.referer ?: ""),
                        KEY_COOKIE to (record.cookie ?: ""),
                        KEY_USER_AGENT to (record.userAgent ?: ""),
                    ),
                )
                .setConstraints(workerNetworkConstraints(wifiOnly))
                .build()
            val policy = if (replace) {
                androidx.work.ExistingWorkPolicy.REPLACE
            } else {
                androidx.work.ExistingWorkPolicy.KEEP
            }
            androidx.work.WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(workName(record.id), policy, request)
        }

        /** Cancels the worker for [recordId], if any is queued or running. */
        fun cancel(context: Context, recordId: String) {
            androidx.work.WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(workName(recordId))
        }
    }
}

/** A copy of [identity] carrying [referer] instead of the tap-time page URL (r203 referer chaining). */
internal fun identityWithReferer(
    identity: BrowserDownloads.DownloadIdentity,
    referer: String,
): BrowserDownloads.DownloadIdentity = identity.copy(referer = referer)

/** Private-app-storage temp dir for one record's segments and joined file. */
internal fun workerTempDir(context: Context, recordId: String): File =
    File(context.filesDir, "hls-tmp/$recordId")

/** Index-based segment file name: resume keys on the index, never on the remote name. */
internal fun segmentFile(dir: File, index: Int, isFmp4: Boolean): File {
    val extension = if (isFmp4) "m4s" else "ts"
    return File(dir, "seg-" + index.toString().padStart(5, '0') + ".$extension")
}

/**
 * The published file name: the record's display name with any .m3u8
 * suffix stripped, then the joined file's extension. A name that
 * already ends in that extension keeps it (no doubled suffix).
 */
internal fun finalFileName(fileName: String, extension: String): String {
    val stripped = if (fileName.lowercase().endsWith(".m3u8")) fileName.dropLast(5) else fileName
    val base = stripped.ifBlank { "video" }
    return if (base.lowercase().endsWith(extension)) base else base + extension
}

/**
 * Joins [segments] in order to [output], with [initSegment] first
 * when given (the fMP4 shape). Returns false and writes nothing
 * when the segment list is empty — never a zero-byte output file.
 */
internal fun joinSegments(segments: List<File>, output: File, initSegment: File?): Boolean {
    if (segments.isEmpty()) return false
    output.outputStream().buffered().use { out ->
        initSegment?.inputStream()?.buffered()?.use { it.copyTo(out) }
        for (segment in segments) {
            segment.inputStream().buffered().use { it.copyTo(out) }
        }
    }
    return true
}

/**
 * Deletes everything a worker download owns: its temp dir (by
 * engine — HLS keeps its r159 path, DASH and segmented have
 * their own), and its published file when one exists (MediaStore
 * entry, or a plain file path). Already-gone bytes are success,
 * not an error.
 */
internal fun deleteWorkerFiles(context: Context, record: BrowserStore.DownloadRecord) {
    runCatching { downloadTempDir(context, record.kind, record.id).deleteRecursively() }
    val raw = record.localSource ?: return
    runCatching {
        val uri = android.net.Uri.parse(raw)
        if (uri.scheme == "content") {
            context.contentResolver.delete(uri, null, null)
        } else if (uri.scheme == "file") {
            uri.path?.let { File(it).delete() }
        }
    }
}
