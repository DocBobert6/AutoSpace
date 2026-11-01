package app.autospace.car

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.core.content.FileProvider
import app.autospace.GearslipLog
import java.io.File
import java.util.UUID

/**
 * The single plain-download path (r157, slice 1 of the downloads + offline
 * library port). Both former enqueue sites — the browser's DownloadListener
 * and the long-press menu's Download row — now funnel through
 * [enqueueDownload]; there is exactly one place that builds a
 * DownloadManager request for a plain download. Records live in
 * BrowserStore (see its Downloads section); this object owns the
 * DownloadManager side: enqueue, completion queries, retry, delete, open,
 * and private-origin cleanup.
 *
 * Scope boundary, stated plainly: this is plain DownloadManager downloads
 * only, plus — since r159 (slice 3) — the HLS worker path below.
 * HLS (.m3u8) URLs route to the WorkManager worker, which downloads
 * the best-bandwidth variant's segments, joins them, and publishes
 * through MediaStore; DASH (.mpd) and segmented downloads still
 * plain-download until slice 4. r158 (slice 2) adds the media flag and the Offline library actions on
 * top of these records: a download counts as media when its MIME type
 * is a video or audio type (DownloadListener path), or when the caller
 * says so outright (media-finder path); the long-press path passes no
 * MIME type, so it stays an ordinary file even for a media-looking URL.
 * Completion is tracked two ways: the manifest receiver
 * (DownloadCompleteReceiver) updates the record when DownloadManager
 * reports completion, and [refreshDownloads]/[refreshRecord] re-query
 * DownloadManager on list open and before Open/Retry act — so a missed
 * broadcast can never leave a finished download stuck at in progress.
 * Plain-download completion notifications stay system-owned: this slice
 * does not change where their tap lands.
 */
object BrowserDownloads {

    /** What DownloadManager says about one enqueued download. */
    data class DmStatus(val state: BrowserStore.DownloadState, val localUri: String?)

    /** What [openDownload] did, so callers can toast honestly. */
    enum class DownloadOpenResult { OPENED, NOT_READY, FAILED, NO_FILE }

    /** What a library row action resolved to, so callers can toast honestly. */
    enum class LibraryResult { READY, NOT_READY, FAILED, NO_FILE, COPY_FAILED }

    /**
     * Pure MIME classification (r158): video and audio MIME types count
     * as media. Parameters ("; charset=…") are stripped and the match is
     * case-insensitive; null, blank, and anything else are not media.
     * The long-press path passes no MIME type, so it lands here as
     * null and stays an ordinary file by construction.
     */
    fun isMediaMime(mimeType: String?): Boolean {
        val type = mimeType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return type.startsWith("video/") || type.startsWith("audio/")
    }

    /** Where an enqueue goes: plain DownloadManager, or the HLS worker. */
    enum class DownloadRoute { PLAIN, HLS_WORKER }

    /**
     * Pure HLS detection (r159): the URL's path — query and fragment
     * stripped, case-insensitive — ends in .m3u8. A bare .m3u8
     * substring elsewhere (a query token, a directory name) does not
     * count, so ordinary pages never route to the worker by accident.
     */
    fun isHlsUrl(url: String): Boolean =
        url.substringBefore('?').substringBefore('#').lowercase().endsWith(".m3u8")

    /**
     * Pure routing rule (r159), the one place the decision lives.
     * An explicitly non-media caller always plain-downloads — that is
     * the long-press path, which passes media false outright, so a
     * long-pressed playlist link still lands as an ordinary file.
     * Otherwise an HLS URL routes to the worker (listener path with
     * media unknown, finder path with media true); everything else,
     * including .mpd until slice 4, plain-downloads.
     */
    fun routeFor(url: String, isMedia: Boolean?): DownloadRoute = when {
        isMedia == false -> DownloadRoute.PLAIN
        isHlsUrl(url) -> DownloadRoute.HLS_WORKER
        else -> DownloadRoute.PLAIN
    }

    /**
     * Pure library-row action gates (r159), so the sheet never shows
     * an affordance the engine cannot honor. Delete shows on every
     * row (unconditional, by construction). Retry shows only on
     * failed rows — a completed record is a no-op by the retry gate,
     * and a paused worker row offers Resume instead. Pause, Resume,
     * and Cancel show only on worker records: plain DownloadManager
     * downloads are not user-pausable, by the decided Mariner rule,
     * and a pause button on one would be a lie.
     */
    fun showLibraryRetry(record: BrowserStore.DownloadRecord): Boolean =
        record.state == BrowserStore.DownloadState.FAILED

    fun showLibraryPause(record: BrowserStore.DownloadRecord): Boolean =
        record.kind == BrowserStore.DownloadKind.HLS &&
            record.state == BrowserStore.DownloadState.IN_PROGRESS

    fun showLibraryResume(record: BrowserStore.DownloadRecord): Boolean =
        record.kind == BrowserStore.DownloadKind.HLS &&
            record.state == BrowserStore.DownloadState.PAUSED

    fun showLibraryCancel(record: BrowserStore.DownloadRecord): Boolean =
        record.kind == BrowserStore.DownloadKind.HLS &&
            (record.state == BrowserStore.DownloadState.IN_PROGRESS ||
                record.state == BrowserStore.DownloadState.PAUSED)

    /**
     * Pure library-play decision (r158): the state gate, the source
     * gate, and the file-exists gate, in that order. The Android side
     * ([librarySource]) supplies [hasSource] and [fileExists]; this
     * decision itself needs no framework, so a JVM test pins it.
     */
    fun libraryPlayDecision(
        state: BrowserStore.DownloadState,
        hasSource: Boolean,
        fileExists: Boolean,
    ): LibraryResult = when {
        state == BrowserStore.DownloadState.FAILED -> LibraryResult.FAILED
        state != BrowserStore.DownloadState.COMPLETE -> LibraryResult.NOT_READY
        !hasSource || !fileExists -> LibraryResult.NO_FILE
        else -> LibraryResult.READY
    }

    /**
     * The one plain-download request builder, shared by enqueue and
     * retry: phone Downloads folder, completed notification,
     * description naming the car browser. There is exactly one place
     * in the tree that builds a plain-download request.
     */
    private fun buildRequest(url: String, fileName: String): DownloadManager.Request =
        DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            .setDescription("Downloaded from the car browser")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

    /**
     * Enqueue one download and record it, or null when enqueue
     * failed (the caller toasts and logs). The plain request is exactly
     * the one the two old blocks built: phone Downloads folder, completed
     * notification, description naming the car browser. [isMedia], when
     * given, wins outright (the media-finder Download row passes true);
     * when null, the MIME type decides (the DownloadListener path), and
     * a null MIME type — the long-press path — is not media.
     *
     * Routing (r159): an HLS URL routes to the worker unless the
     * caller explicitly passed media false (see [routeFor]). A worker
     * record is media by routing — it is a video by nature — so it
     * lands in the Offline library, never in Downloads; its manager
     * id is -1 (no DownloadManager row exists for it) and its file
     * name is the playlist name with the .m3u8 suffix stripped, since
     * the joined file's real extension (.ts or .mp4) is only known
     * once the worker has seen the variant — the worker rewrites the
     * name to the published one on completion.
     */
    fun enqueueDownload(
        context: Context,
        url: String,
        contentDisposition: String?,
        mimeType: String?,
        isPrivate: Boolean,
        isMedia: Boolean? = null,
    ): BrowserStore.DownloadRecord? {
        if (routeFor(url, isMedia) == DownloadRoute.HLS_WORKER) {
            return enqueueWorkerDownload(context, url, contentDisposition, mimeType, isPrivate)
        }
        return runCatching {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val dm = context.getSystemService(DownloadManager::class.java)
                ?: return@runCatching null
            val dmId = dm.enqueue(buildRequest(url, fileName))
            val record = BrowserStore.DownloadRecord(
                id = UUID.randomUUID().toString(),
                url = url,
                fileName = fileName,
                state = BrowserStore.DownloadState.IN_PROGRESS,
                downloadManagerId = dmId,
                localSource = null,
                isPrivate = isPrivate,
                createdAtMs = System.currentTimeMillis(),
                isMedia = isMedia ?: isMediaMime(mimeType),
            )
            BrowserStore.addDownload(context, record)
            GearslipLog.i("car browser download started: $fileName")
            record
        }.getOrElse { e ->
            GearslipLog.e("car browser download FAILED: $url - ${e.message}")
            null
        }
    }

    /**
     * The worker half of [enqueueDownload]: creates the record, then
     * enqueues the unique work for it. When the enqueue itself throws,
     * the record is dropped again and null returns, so no phantom
     * row ever points at work that does not exist.
     */
    private fun enqueueWorkerDownload(
        context: Context,
        url: String,
        contentDisposition: String?,
        mimeType: String?,
        isPrivate: Boolean,
    ): BrowserStore.DownloadRecord? {
        val guessed = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val fileName = if (guessed.lowercase().endsWith(".m3u8")) {
            guessed.dropLast(5).ifBlank { "video" }
        } else {
            guessed
        }
        val record = BrowserStore.DownloadRecord(
            id = UUID.randomUUID().toString(),
            url = url,
            fileName = fileName,
            state = BrowserStore.DownloadState.IN_PROGRESS,
            downloadManagerId = -1L,
            localSource = null,
            isPrivate = isPrivate,
            createdAtMs = System.currentTimeMillis(),
            isMedia = true,
            progress = -1,
            kind = BrowserStore.DownloadKind.HLS,
        )
        BrowserStore.addDownload(context, record)
        return runCatching {
            HlsDownloadWorker.enqueue(context, record, replace = false)
            GearslipLog.i("car browser HLS download started: $fileName")
            record
        }.getOrElse { e ->
            GearslipLog.e("car browser HLS download FAILED: $url - ${e.message}")
            BrowserStore.removeDownload(context, record.id)
            null
        }
    }

    /**
     * Query DownloadManager for one enqueued id. Null when DownloadManager
     * has no row for it (removed elsewhere, or never enqueued) — the
     * caller then leaves the record alone rather than guessing.
     */
    fun queryStatus(context: Context, dmId: Long): DmStatus? {
        if (dmId < 0L) return null
        val dm = context.getSystemService(DownloadManager::class.java) ?: return null
        return runCatching {
            val query = DownloadManager.Query().setFilterById(dmId)
            dm.query(query).use { cursor ->
                if (!cursor.moveToFirst()) return@runCatching null
                val status = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS),
                )
                val localUri = runCatching {
                    cursor.getString(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI),
                    )
                }.getOrNull()
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL ->
                        DmStatus(BrowserStore.DownloadState.COMPLETE, localUri)
                    DownloadManager.STATUS_FAILED ->
                        DmStatus(BrowserStore.DownloadState.FAILED, null)
                    else -> DmStatus(BrowserStore.DownloadState.IN_PROGRESS, null)
                }
            }
        }.getOrNull()
    }

    /** Re-query every in-progress record and persist any state change. */
    fun refreshDownloads(context: Context) {
        BrowserStore.downloads(context)
            .filter { it.state == BrowserStore.DownloadState.IN_PROGRESS }
            .forEach { refreshRecord(context, it) }
    }

    /** Re-query one record, persist any state change, and return the current record. Worker records have no DownloadManager row (manager id -1), so the query is a no-op for them — their state is written by the worker itself. */
    fun refreshRecord(context: Context, record: BrowserStore.DownloadRecord): BrowserStore.DownloadRecord {
        val status = queryStatus(context, record.downloadManagerId) ?: return record
        if (status.state == record.state && status.localUri == record.localSource) return record
        val updated = record.copy(state = status.state, localSource = status.localUri)
        BrowserStore.updateDownload(context, updated)
        return updated
    }

    /**
     * Re-enqueue a failed or interrupted download under the same record
     * id, resetting it to in progress. A completed record is a no-op:
     * retrying it must never produce a duplicate download. When the fresh
     * enqueue itself fails, the record is marked failed.
     *
     * Worker records (r159) re-enqueue through the worker, not through
     * DownloadManager: REPLACE policy, so any stale run is cancelled
     * first, and the run resumes from the segments already on disk.
     * A paused worker record retries the same way — Resume and Retry
     * are one path, differing only in which button the sheet shows.
     */
    fun retryDownload(context: Context, record: BrowserStore.DownloadRecord): BrowserStore.DownloadRecord {
        if (!BrowserStore.retryable(record)) return record
        if (record.kind == BrowserStore.DownloadKind.HLS) {
            val current = BrowserStore.downloadById(context, record.id) ?: record
            if (!BrowserStore.retryable(current)) return current
            return runCatching {
                val updated = BrowserStore.retriedWorker(current)
                BrowserStore.updateDownload(context, updated)
                HlsDownloadWorker.enqueue(context, updated, replace = true)
                GearslipLog.i("car browser HLS download retried: ${record.fileName}")
                updated
            }.getOrElse { e ->
                GearslipLog.e("car browser HLS download retry FAILED: ${record.url} - ${e.message}")
                val failed = current.copy(state = BrowserStore.DownloadState.FAILED)
                BrowserStore.updateDownload(context, failed)
                failed
            }
        }
        val dm = context.getSystemService(DownloadManager::class.java)
        if (dm == null) {
            val failed = record.copy(state = BrowserStore.DownloadState.FAILED)
            BrowserStore.updateDownload(context, failed)
            return failed
        }
        return runCatching {
            val dmId = dm.enqueue(buildRequest(record.url, record.fileName))
            val updated = BrowserStore.retried(record, dmId)
            BrowserStore.updateDownload(context, updated)
            GearslipLog.i("car browser download retried: ${record.fileName}")
            updated
        }.getOrElse { e ->
            GearslipLog.e("car browser download retry FAILED: ${record.url} - ${e.message}")
            val failed = record.copy(state = BrowserStore.DownloadState.FAILED)
            BrowserStore.updateDownload(context, failed)
            failed
        }
    }

    /**
     * Pause a worker download (r159): cancels the unique work and
     * marks the record paused, keeping its temp segments for resume.
     * A no-op on anything but an in-progress worker record — plain
     * downloads are not user-pausable, and a finished, failed, or
     * already-paused record has nothing to pause. The stored record
     * is re-read first, so a completion that landed since the list
     * was drawn is never overwritten by a stale pause.
     */
    fun pauseDownload(context: Context, record: BrowserStore.DownloadRecord): BrowserStore.DownloadRecord {
        val current = BrowserStore.downloadById(context, record.id) ?: record
        if (current.kind != BrowserStore.DownloadKind.HLS ||
            current.state != BrowserStore.DownloadState.IN_PROGRESS
        ) {
            return current
        }
        HlsDownloadWorker.cancel(context, current.id)
        val paused = current.copy(state = BrowserStore.DownloadState.PAUSED)
        BrowserStore.updateDownload(context, paused)
        return paused
    }

    /**
     * Cancel a worker download (r159): cancels the unique work and
     * drops the record together with everything it owns — temp
     * segments and any published file. A no-op on plain records and
     * on completed worker records (a finished file is deleted through
     * Delete, not Cancel).
     */
    fun cancelWorkerDownload(context: Context, record: BrowserStore.DownloadRecord) {
        val current = BrowserStore.downloadById(context, record.id) ?: record
        if (current.kind != BrowserStore.DownloadKind.HLS) return
        if (current.state == BrowserStore.DownloadState.COMPLETE) return
        HlsDownloadWorker.cancel(context, current.id)
        deleteWorkerFiles(context, current)
        BrowserStore.removeDownload(context, current.id)
    }

    /**
     * Delete a download: remove it from DownloadManager (which deletes
     * the bytes, or cancels a partial download) and drop the record.
     * A record whose file is already gone is success, not an error —
     * the record is dropped either way. Worker records (r159) delete
     * through their own path: cancel the work, drop temp segments
     * and any published file, then drop the record.
     */
    fun deleteDownload(context: Context, record: BrowserStore.DownloadRecord) {
        if (record.kind == BrowserStore.DownloadKind.HLS) {
            val current = BrowserStore.downloadById(context, record.id) ?: record
            HlsDownloadWorker.cancel(context, current.id)
            deleteWorkerFiles(context, current)
            BrowserStore.removeDownload(context, current.id)
            return
        }
        if (record.downloadManagerId >= 0L) {
            runCatching {
                context.getSystemService(DownloadManager::class.java)?.remove(record.downloadManagerId)
            }
        }
        BrowserStore.removeDownload(context, record.id)
    }

    /**
     * Delete every private-origin download's bytes and records. Called
     * alongside PrivateProfile.wipe() wherever the private profile dies
     * (last private tab closed, last tab reset, browser left with
     * private tabs open, app startup after a dead process), so private
     * downloads never outlive the private profile the way ordinary
     * records outlive a session.
     */
    fun deletePrivateDownloads(context: Context) {
        BrowserStore.downloads(context)
            .filter { it.isPrivate }
            .forEach { deleteDownload(context, it) }
    }

    /**
     * Open a completed download with the system viewer: a plain
     * ACTION_VIEW for the file, NOT the library player (library playback
     * is slice 2). The local URI comes from DownloadManager; content
     * URIs go straight out with a read grant, file URIs are wrapped
     * through the downloads FileProvider first (a raw file URI would
     * trip FileUriExposedException on the receiving app).
     */
    fun openDownload(context: Context, record: BrowserStore.DownloadRecord): DownloadOpenResult {
        val current = refreshRecord(context, record)
        if (current.state == BrowserStore.DownloadState.FAILED) return DownloadOpenResult.FAILED
        if (current.state != BrowserStore.DownloadState.COMPLETE) return DownloadOpenResult.NOT_READY
        val raw = current.localSource ?: return DownloadOpenResult.NO_FILE
        return runCatching {
            val uri = shareableUri(context, raw) ?: return@runCatching DownloadOpenResult.NO_FILE
            val mime = mimeFor(current.fileName)
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(view)
            DownloadOpenResult.OPENED
        }.getOrElse { e ->
            GearslipLog.e("car browser download open FAILED: ${current.fileName} - ${e.message}")
            DownloadOpenResult.NO_FILE
        }
    }

    /**
     * Library Play (r158, slice 2): refresh the record, then verify
     * the local file still exists before handing anything to the
     * player. A vanished file resolves to NO_FILE and the caller
     * toasts "Download file is gone" instead of opening the player —
     * the slice-1 vanished-file pattern, extended to library rows.
     * The caller plays [String] through the existing video player
     * (which takes URL strings); this function never touches the
     * player itself. No MediaStore backfill here: the refresh covers
     * completion-state staleness, not a file moved out from under
     * DownloadManager — that heuristic stays unbuilt for the worker
     * slices if still needed.
     */
    fun librarySource(
        context: Context,
        record: BrowserStore.DownloadRecord,
    ): Pair<LibraryResult, String?> {
        val current = refreshRecord(context, record)
        val raw = current.localSource
        val exists = raw != null && localFileExists(context, raw)
        val decision = libraryPlayDecision(current.state, raw != null, exists)
        return decision to (raw.takeIf { decision == LibraryResult.READY })
    }

    /** True when [raw] (a DownloadManager local URI) still resolves to bytes. */
    private fun localFileExists(context: Context, raw: String): Boolean {
        val uri = Uri.parse(raw)
        if (uri.scheme == "file") {
            val path = uri.path ?: return false
            return File(path).exists()
        }
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { true } ?: false
        }.getOrDefault(false)
    }

    /**
     * Library Share (r158): the same verified source as Play, sent
     * out as a system share of the file (chooser, read grant) instead
     * of a view intent. READY means the chooser opened; anything else
     * means nothing was shared and the caller toasts the reason.
     */
    fun shareDownload(context: Context, record: BrowserStore.DownloadRecord): LibraryResult {
        val (decision, raw) = librarySource(context, record)
        if (decision != LibraryResult.READY || raw == null) return decision
        return runCatching {
            val uri = shareableUri(context, raw) ?: return@runCatching LibraryResult.NO_FILE
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mimeFor(record.fileName)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Share file"))
            LibraryResult.READY
        }.getOrElse { e ->
            GearslipLog.e("car browser download share FAILED: ${record.fileName} - ${e.message}")
            LibraryResult.NO_FILE
        }
    }

    /**
     * Library Save-a-copy (r158): copy the verified file to the
     * user-chosen Storage Access Framework destination. The original
     * record is untouched on every outcome, including a failed copy.
     * The copy runs on the caller's thread — the WebApp launcher
     * callback puts it on Dispatchers.IO, never the main thread.
     */
    fun copyDownload(
        context: Context,
        record: BrowserStore.DownloadRecord,
        destUri: Uri,
    ): LibraryResult {
        val (decision, raw) = librarySource(context, record)
        if (decision != LibraryResult.READY || raw == null) return decision
        return runCatching {
            val input = context.contentResolver.openInputStream(Uri.parse(raw))
                ?: return@runCatching LibraryResult.NO_FILE
            val output = context.contentResolver.openOutputStream(destUri)
                ?: return@runCatching LibraryResult.COPY_FAILED
            input.use { inputStream ->
                output.use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            LibraryResult.READY
        }.getOrElse { e ->
            GearslipLog.e("car browser download copy FAILED: ${record.fileName} - ${e.message}")
            LibraryResult.COPY_FAILED
        }
    }

    private fun shareableUri(context: Context, raw: String): Uri? {
        val uri = Uri.parse(raw)
        if (uri.scheme != "file") return uri
        val path = uri.path ?: return null
        return runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.downloads",
                File(path),
            )
        }.getOrNull()
    }

    private fun mimeFor(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension.isBlank()) return "*/*"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
    }
}
