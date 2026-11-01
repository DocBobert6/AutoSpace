package app.autospace.car

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.autospace.GearslipLog
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The segmented download worker (r160, downloads slice 4). Given a
 * record for an ordinary media file, it downloads the file in
 * 1 MiB byte ranges, joins the parts in order, and publishes the
 * result through MediaStore into the phone's public Downloads
 * folder — the same IS_PENDING 1, copy, 0 pattern the HLS worker
 * uses. Segmented downloads apply to media downloads only (the
 * routing rule in BrowserDownloads), and only while the
 * segmented-downloads setting is on; everything else still
 * plain-downloads through DownloadManager.
 *
 * Resume is by part file, not by byte range: finished parts sit
 * in the record's temp dir (private app storage) under
 * index-based names, and a resumed run skips every part file
 * that already exists with its full expected length. Pause is
 * WorkManager cancellation plus a PAUSED record state, so the
 * worker's terminal writes are conditional on the record still
 * being in progress — a pause that lands mid-run wins over a
 * late completion, and a failure never overwrites a pause.
 *
 * When the server does not support ranges (no 206 answer to a
 * range probe), the worker degrades honestly to one whole-file
 * stream instead of failing the download: pause then cancels
 * the stream and resume restarts it from scratch, since there
 * are no parts to resume from. The record keeps its pause and
 * resume buttons either way — only what resume reuses changes.
 *
 * The network half has no JVM seam, so it rests on CI
 * compilation plus the device checks in the round report: a
 * segmented media download must pause and resume, and must
 * still arrive whole and playable in the Offline library.
 */
class SegmentedDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val recordId = inputData.getString(KEY_RECORD_ID) ?: return@withContext Result.failure()
        val fileUrl = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val record = BrowserStore.downloadById(applicationContext, recordId)
            ?: return@withContext Result.failure()
        if (record.state == BrowserStore.DownloadState.COMPLETE) return@withContext Result.success()
        if (record.kind != BrowserStore.DownloadKind.SEGMENTED) return@withContext Result.failure()
        try {
            updateWorkerProgress(applicationContext, recordId, 0)
            val tempDir = downloadTempDir(applicationContext, BrowserStore.DownloadKind.SEGMENTED, recordId)
            if (!tempDir.exists()) tempDir.mkdirs()
            val totalBytes = probeTotalBytes(fileUrl)
            val joined: File
            if (totalBytes != null) {
                joined = File(tempDir, "joined.bin")
                val ranges = segmentRanges(totalBytes, PART_SIZE_BYTES)
                var doneBytes = 0L
                for ((index, range) in ranges.withIndex()) {
                    // A pause (or a delete) that landed since the
                    // last part stops this run here; the parts
                    // already on disk are what the next run
                    // resumes from.
                    val current = BrowserStore.downloadById(applicationContext, recordId)
                    if (current == null || current.state != BrowserStore.DownloadState.IN_PROGRESS) {
                        return@withContext Result.success()
                    }
                    val target = segmentPartFile(tempDir, index)
                    val expected = range.last - range.first + 1L
                    if (target.exists() && target.length() == expected) {
                        doneBytes += expected
                    } else {
                        fetchRangeToFile(fileUrl, range, target)
                        doneBytes += expected
                    }
                    updateWorkerProgress(applicationContext, recordId, (doneBytes * 100L / totalBytes).toInt())
                }
                val partFiles = ranges.indices.map { segmentPartFile(tempDir, it) }
                if (!joinSegments(partFiles, joined, null)) {
                    markWorkerFailed(applicationContext, recordId)
                    return@withContext Result.failure()
                }
            } else {
                // No range support: one whole-file stream. Progress
                // tracks bytes when the server reports a length,
                // and otherwise stays at zero until the file lands.
                joined = File(tempDir, "whole.bin")
                fetchWholeToFile(fileUrl, joined, recordId)
                val current = BrowserStore.downloadById(applicationContext, recordId)
                if (current == null || current.state != BrowserStore.DownloadState.IN_PROGRESS) {
                    return@withContext Result.success()
                }
            }
            val displayName = record.fileName
            val published = publishWorkerFile(
                applicationContext,
                joined,
                displayName,
                mimeForFileName(displayName),
            )
            if (published == null) {
                markWorkerFailed(applicationContext, recordId)
                return@withContext Result.failure()
            }
            val current = BrowserStore.downloadById(applicationContext, recordId)
            if (current == null || current.state != BrowserStore.DownloadState.IN_PROGRESS) {
                // A pause landed during publish: the published file
                // is not referenced by any record yet, so it is
                // deleted rather than orphaned in Downloads, and
                // the record keeps its paused state. The next
                // resume re-joins from the parts and publishes
                // again (or re-streams, for a range-less server).
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
            GearslipLog.e("car browser segmented download FAILED: $fileUrl - ${e.message}")
            markWorkerFailed(applicationContext, recordId)
            Result.failure()
        }
    }

    /**
     * Probes range support with a one-byte range request: a 206
     * answer carries the total in its Content-Range header
     * ("bytes 0-0/<total>"); anything else means the server does
     * not do ranges, and the caller falls back to a whole-file
     * stream. Null on any transport failure — the caller treats
     * that as range-less too, and the stream itself will fail
     * honestly if the server is truly unreachable.
     */
    private fun probeTotalBytes(url: String): Long? {
        return runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", "AutoSpace")
                connection.setRequestProperty("Range", "bytes=0-0")
                if (connection.responseCode != HttpURLConnection.HTTP_PARTIAL) return@runCatching null
                val contentRange = connection.getHeaderField("Content-Range") ?: return@runCatching null
                contentRange.substringAfter('/', "").toLongOrNull()?.takeIf { it > 0L }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    /**
     * Downloads one byte range to [target] atomically: bytes land
     * in a sidecar file first and only rename over the target
     * once whole, so a resumed run never mistakes a half-written
     * part for a finished one (the resume check also requires
     * the full expected length). A non-206 answer is a failure,
     * not a silent whole file wearing a part's name.
     */
    private fun fetchRangeToFile(url: String, range: LongRange, target: File) {
        val tmp = File(target.parentFile, "${target.name}.part")
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "AutoSpace")
            connection.setRequestProperty("Range", "bytes=${range.first}-${range.last}")
            if (connection.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("HTTP ${connection.responseCode} for range $range of $url")
            }
            connection.inputStream.use { input ->
                tmp.outputStream().buffered().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("could not finish part ${target.name}")
        }
    }

    /**
     * Streams one whole file to [target] atomically (the
     * range-less fallback). Progress tracks bytes when the server
     * reports a content length; without one it stays at zero
     * until the file lands, which the caller set at the start.
     */
    private fun fetchWholeToFile(url: String, target: File, recordId: String) {
        val tmp = File(target.parentFile, "${target.name}.part")
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "AutoSpace")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode} for $url")
            }
            val total = connection.contentLengthLong.takeIf { it > 0L }
            var done = 0L
            connection.inputStream.use { input ->
                tmp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total != null) {
                            updateWorkerProgress(
                                applicationContext,
                                recordId,
                                (done * 100L / total).toInt().coerceIn(0, 100),
                            )
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("could not finish file ${target.name}")
        }
    }

    companion object {
        const val KEY_RECORD_ID = "record_id"
        const val KEY_URL = "url"

        /** One segment's size: 1 MiB parts keep resume granularity fine without file spam. */
        const val PART_SIZE_BYTES: Long = 1024L * 1024L

        /** The unique-work name for one record's segmented download. */
        fun workName(recordId: String): String = "seg-download-$recordId"

        /**
         * Enqueues (or re-enqueues) the worker for [record] under
         * its unique name. When [wifiOnly] is true the work carries
         * an UNMETERED constraint, so WorkManager itself holds it
         * until Wi-Fi — the record stays in progress, not failed,
         * while it waits.
         */
        fun enqueue(
            context: Context,
            record: BrowserStore.DownloadRecord,
            replace: Boolean,
            wifiOnly: Boolean = false,
        ) {
            val request = androidx.work.OneTimeWorkRequestBuilder<SegmentedDownloadWorker>()
                .setInputData(
                    workDataOf(
                        KEY_RECORD_ID to record.id,
                        KEY_URL to record.url,
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

/** Index-based part file name: resume keys on the index, never on the remote name. */
internal fun segmentPartFile(dir: File, index: Int): File =
    File(dir, "part-" + index.toString().padStart(5, '0') + ".bin")
