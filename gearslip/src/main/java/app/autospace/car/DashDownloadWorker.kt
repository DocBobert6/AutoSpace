package app.autospace.car

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.autospace.GearslipLog
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The DASH download worker (r160, downloads slice 4). Given a record
 * for an .mpd URL, it hands the presentation to Media3 Transformer,
 * which remuxes it to a single .mp4, and publishes that file
 * through MediaStore into the phone's public Downloads folder —
 * the same IS_PENDING 1, copy, 0 pattern the HLS worker uses.
 *
 * Resume restarts the export: Transformer writes one output file
 * and cannot resume a partial one, so a paused run's partial
 * export is discarded and the next run exports from scratch.
 * That is the honest shape of pause for this engine — the record
 * keeps its place in the library and its pause/resume buttons,
 * but resume re-does the export rather than continuing it.
 * Pause itself is WorkManager cancellation plus a PAUSED record
 * state, so the worker's terminal writes are conditional on the
 * record still being in progress — a pause that lands mid-run
 * wins over a late completion, and a failure never overwrites
 * a pause.
 *
 * Refusals, all honest: a failed export (unreachable manifest,
 * unsupported codec, DRM-protected presentation) marks the
 * record FAILED and never produces a file, because a partial or
 * undecryptable export wearing a finished name would be a
 * broken file. No quality picker exists for this port, by
 * decision — Transformer exports the presentation as offered.
 *
 * No JVM seam exists for the remux itself (Transformer needs the
 * Android framework and device codecs), so it rests on CI
 * compilation plus the device checks in the round report: a
 * DASH video must download into the Offline library and play
 * end-to-end on the car screen, including mini/PiP.
 */
class DashDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val recordId = inputData.getString(KEY_RECORD_ID) ?: return@withContext Result.failure()
        val manifestUrl = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val record = BrowserStore.downloadById(applicationContext, recordId)
            ?: return@withContext Result.failure()
        if (record.state == BrowserStore.DownloadState.COMPLETE) return@withContext Result.success()
        if (record.kind != BrowserStore.DownloadKind.DASH) return@withContext Result.failure()
        try {
            updateWorkerProgress(applicationContext, recordId, 0)
            val tempDir = downloadTempDir(applicationContext, BrowserStore.DownloadKind.DASH, recordId)
            if (!tempDir.exists()) tempDir.mkdirs()
            val exportFile = File(tempDir, "export.mp4")
            if (exportFile.exists()) {
                // A paused run's partial export is unusable — the
                // export cannot resume — so it is discarded and
                // this run exports from scratch.
                exportFile.delete()
            }
            val exported = exportDash(applicationContext, manifestUrl, exportFile)
            if (!exported) {
                markWorkerFailed(applicationContext, recordId)
                return@withContext Result.failure()
            }
            val displayName = workerFinalFileName(record.fileName, ".mpd", ".mp4")
            val published = publishWorkerFile(
                applicationContext,
                exportFile,
                displayName,
                "video/mp4",
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
                // resume re-exports and publishes again.
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
            GearslipLog.e("car browser DASH download FAILED: $manifestUrl - ${e.message}")
            markWorkerFailed(applicationContext, recordId)
            Result.failure()
        }
    }

    /**
     * Exports one DASH presentation to [outputFile] via Transformer
     * and suspends until it completes or fails. Cancellation
     * cancels the export, so a paused run never keeps encoding
     * after its record has moved on.
     */
    private suspend fun exportDash(context: Context, manifestUrl: String, outputFile: File): Boolean =
        suspendCancellableCoroutine { continuation ->
            val transformer = Transformer.Builder(context).build()
            transformer.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    GearslipLog.e("car browser DASH export FAILED: $manifestUrl - ${exportException.message}")
                    if (continuation.isActive) continuation.resume(false)
                }
            })
            continuation.invokeOnCancellation { transformer.cancel() }
            runCatching {
                transformer.start(MediaItem.fromUri(manifestUrl), outputFile.absolutePath)
            }.getOrElse { e ->
                GearslipLog.e("car browser DASH export FAILED to start: $manifestUrl - ${e.message}")
                if (continuation.isActive) continuation.resume(false)
            }
        }

    companion object {
        const val KEY_RECORD_ID = "record_id"
        const val KEY_URL = "url"

        /** The unique-work name for one record's DASH download. */
        fun workName(recordId: String): String = "dash-download-$recordId"

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
            val request = androidx.work.OneTimeWorkRequestBuilder<DashDownloadWorker>()
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
