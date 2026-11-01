package app.autospace.car

import android.app.DownloadManager
import android.content.ContentUris
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
 * Slice 4 (r160) completes the port: DASH routes to a Transformer
 * remux worker, media files route to a segmented range worker while
 * the segmented setting is on, the Wi-Fi-only setting gates new
 * downloads, and a MediaStore backfill rescues files moved out
 * from under DownloadManager. Plain DownloadManager downloads are
 * still not user-pausable, by the decided Mariner rule.
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

    /** Where an enqueue goes: plain DownloadManager, or one of the workers. */
    enum class DownloadRoute { PLAIN, HLS_WORKER, DASH_WORKER, SEGMENTED_WORKER }

    /**
     * Pure HLS detection (r159): the URL's path — query and fragment
     * stripped, case-insensitive — ends in .m3u8. A bare .m3u8
     * substring elsewhere (a query token, a directory name) does not
     * count, so ordinary pages never route to the worker by accident.
     */
    fun isHlsUrl(url: String): Boolean =
        url.substringBefore('?').substringBefore('#').lowercase().endsWith(".m3u8")

    /**
     * Pure DASH detection (r160): the same path-only, case-insensitive
     * rule as [isHlsUrl], for the .mpd suffix.
     */
    fun isDashUrl(url: String): Boolean =
        url.substringBefore('?').substringBefore('#').lowercase().endsWith(".mpd")

    /**
     * Pure routing rule (r160), the one place the decision lives.
     * An explicitly non-media caller always plain-downloads — that is
     * the long-press path, which passes media false outright, so a
     * long-pressed playlist link still lands as an ordinary file.
     * Otherwise a playlist or manifest URL routes to its worker
     * (HLS for .m3u8, DASH for .mpd) on any path, including media
     * unknown; an ordinary media URL routes to the segmented worker
     * only when the caller confirmed media AND the segmented
     * setting is on; everything else plain-downloads. Media unknown
     * (the listener path before MIME classification) never
     * segments — the enqueue path resolves it from the MIME type
     * first, so only classified media reaches this rule as true.
     */
    fun routeFor(url: String, isMedia: Boolean?, segmentedOn: Boolean): DownloadRoute = when {
        isMedia == false -> DownloadRoute.PLAIN
        isHlsUrl(url) -> DownloadRoute.HLS_WORKER
        isDashUrl(url) -> DownloadRoute.DASH_WORKER
        isMedia == true && segmentedOn -> DownloadRoute.SEGMENTED_WORKER
        else -> DownloadRoute.PLAIN
    }

    /** True for the engine kinds a worker owns — the rows with pause, resume, and cancel. */
    fun isWorkerKind(kind: BrowserStore.DownloadKind): Boolean =
        kind == BrowserStore.DownloadKind.HLS ||
            kind == BrowserStore.DownloadKind.DASH ||
            kind == BrowserStore.DownloadKind.SEGMENTED

    /**
     * Pure library-row action gates (r159, generalized r160 to every
     * worker engine), so the sheet never shows an affordance the
     * engine cannot honor. Delete shows on every row (unconditional,
     * by construction). Retry shows only on failed rows — a completed
     * record is a no-op by the retry gate, and a paused worker row
     * offers Resume instead. Pause, Resume, and Cancel show only on
     * worker records: plain DownloadManager downloads are not
     * user-pausable, by the decided Mariner rule, and a pause button
     * on one would be a lie.
     */
    fun showLibraryRetry(record: BrowserStore.DownloadRecord): Boolean =
        record.state == BrowserStore.DownloadState.FAILED

    fun showLibraryPause(record: BrowserStore.DownloadRecord): Boolean =
        isWorkerKind(record.kind) &&
            record.state == BrowserStore.DownloadState.IN_PROGRESS

    fun showLibraryResume(record: BrowserStore.DownloadRecord): Boolean =
        isWorkerKind(record.kind) &&
            record.state == BrowserStore.DownloadState.PAUSED

    fun showLibraryCancel(record: BrowserStore.DownloadRecord): Boolean =
        isWorkerKind(record.kind) &&
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
     * Routing (r159, extended r160): the media question is resolved
     * first — an explicit caller value wins, otherwise the MIME
     * type decides — and then [routeFor] picks the engine. A worker
     * record is media by routing — it is a video by nature — so it
     * lands in the Offline library, never in Downloads; its manager
     * id is -1 (no DownloadManager row exists for it) and its file
     * name is the playlist or manifest name with its suffix
     * stripped, since the joined or remuxed file's real extension
     * is only known once the worker has run — the worker rewrites
     * the name to the published one on completion. A plain record
     * under the Wi-Fi-only setting on a metered network is not
     * enqueued at all: it is stored WAITING (see [tryStartWaiting]),
     * and the caller toasts "Waiting for Wi-Fi" instead of
     * "Download started".
     */
    fun enqueueDownload(
        context: Context,
        url: String,
        contentDisposition: String?,
        mimeType: String?,
        isPrivate: Boolean,
        isMedia: Boolean? = null,
    ): BrowserStore.DownloadRecord? {
        val effectiveMedia = isMedia ?: isMediaMime(mimeType)
        when (routeFor(url, effectiveMedia, BrowserStore.segmentedDownloadsEnabled(context))) {
            DownloadRoute.HLS_WORKER ->
                return enqueueWorkerDownload(context, url, contentDisposition, mimeType, isPrivate)
            DownloadRoute.DASH_WORKER ->
                return enqueueDashDownload(context, url, contentDisposition, mimeType, isPrivate)
            DownloadRoute.SEGMENTED_WORKER ->
                return enqueueSegmentedDownload(context, url, contentDisposition, mimeType, isPrivate)
            DownloadRoute.PLAIN -> Unit
        }
        if (shouldWaitForWifi(
                BrowserStore.wifiOnlyDownloadsEnabled(context),
                isUnmeteredNetwork(context),
            )
        ) {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val record = BrowserStore.DownloadRecord(
                id = UUID.randomUUID().toString(),
                url = url,
                fileName = fileName,
                state = BrowserStore.DownloadState.WAITING,
                downloadManagerId = -1L,
                localSource = null,
                isPrivate = isPrivate,
                createdAtMs = System.currentTimeMillis(),
                isMedia = effectiveMedia,
            )
            BrowserStore.addDownload(context, record)
            GearslipLog.i("car browser download waiting for Wi-Fi: $fileName")
            return record
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
            HlsDownloadWorker.enqueue(
                context,
                record,
                replace = false,
                wifiOnly = BrowserStore.wifiOnlyDownloadsEnabled(context),
            )
            GearslipLog.i("car browser HLS download started: $fileName")
            record
        }.getOrElse { e ->
            GearslipLog.e("car browser HLS download FAILED: $url - ${e.message}")
            BrowserStore.removeDownload(context, record.id)
            null
        }
    }

    /**
     * The DASH half of [enqueueDownload] (r160): same shape as the
     * HLS half — record first, then the unique work, and the
     * record is dropped again when the enqueue itself throws, so
     * no phantom row ever points at work that does not exist.
     * The file name strips the .mpd suffix; the worker rewrites
     * it to the published .mp4 name on completion.
     */
    private fun enqueueDashDownload(
        context: Context,
        url: String,
        contentDisposition: String?,
        mimeType: String?,
        isPrivate: Boolean,
    ): BrowserStore.DownloadRecord? {
        val guessed = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val fileName = if (guessed.lowercase().endsWith(".mpd")) {
            guessed.dropLast(4).ifBlank { "video" }
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
            kind = BrowserStore.DownloadKind.DASH,
        )
        BrowserStore.addDownload(context, record)
        return runCatching {
            DashDownloadWorker.enqueue(
                context,
                record,
                replace = false,
                wifiOnly = BrowserStore.wifiOnlyDownloadsEnabled(context),
            )
            GearslipLog.i("car browser DASH download started: $fileName")
            record
        }.getOrElse { e ->
            GearslipLog.e("car browser DASH download FAILED: $url - ${e.message}")
            BrowserStore.removeDownload(context, record.id)
            null
        }
    }

    /**
     * The segmented half of [enqueueDownload] (r160): same shape
     * as the other worker halves. The guessed file name is kept
     * whole — a segmented download is an ordinary file fetched
     * in parts, so there is no playlist suffix to strip and the
     * published name is the guessed one.
     */
    private fun enqueueSegmentedDownload(
        context: Context,
        url: String,
        contentDisposition: String?,
        mimeType: String?,
        isPrivate: Boolean,
    ): BrowserStore.DownloadRecord? {
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
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
            kind = BrowserStore.DownloadKind.SEGMENTED,
        )
        BrowserStore.addDownload(context, record)
        return runCatching {
            SegmentedDownloadWorker.enqueue(
                context,
                record,
                replace = false,
                wifiOnly = BrowserStore.wifiOnlyDownloadsEnabled(context),
            )
            GearslipLog.i("car browser segmented download started: $fileName")
            record
        }.getOrElse { e ->
            GearslipLog.e("car browser segmented download FAILED: $url - ${e.message}")
            BrowserStore.removeDownload(context, record.id)
            null
        }
    }

    /** Re-enqueues one worker record's work by engine, reading the Wi-Fi-only setting fresh. */
    private fun enqueueWorkFor(context: Context, record: BrowserStore.DownloadRecord, replace: Boolean) {
        val wifiOnly = BrowserStore.wifiOnlyDownloadsEnabled(context)
        when (record.kind) {
            BrowserStore.DownloadKind.HLS -> HlsDownloadWorker.enqueue(context, record, replace, wifiOnly)
            BrowserStore.DownloadKind.DASH -> DashDownloadWorker.enqueue(context, record, replace, wifiOnly)
            BrowserStore.DownloadKind.SEGMENTED -> SegmentedDownloadWorker.enqueue(context, record, replace, wifiOnly)
            BrowserStore.DownloadKind.PLAIN -> Unit
        }
    }

    /** Cancels one worker record's work by engine; a plain record has no work to cancel. */
    private fun cancelWorkFor(context: Context, record: BrowserStore.DownloadRecord) {
        when (record.kind) {
            BrowserStore.DownloadKind.HLS -> HlsDownloadWorker.cancel(context, record.id)
            BrowserStore.DownloadKind.DASH -> DashDownloadWorker.cancel(context, record.id)
            BrowserStore.DownloadKind.SEGMENTED -> SegmentedDownloadWorker.cancel(context, record.id)
            BrowserStore.DownloadKind.PLAIN -> Unit
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

    /**
     * Re-query every live record and persist any state change.
     * In-progress records re-query DownloadManager (worker records
     * are a no-op there — the worker writes its own state);
     * WAITING records try to start (see [tryStartWaiting]), so a
     * download held for Wi-Fi begins on the next list open once
     * Wi-Fi is available or the setting is off.
     */
    fun refreshDownloads(context: Context) {
        BrowserStore.downloads(context).forEach { record ->
            when (record.state) {
                BrowserStore.DownloadState.IN_PROGRESS -> refreshRecord(context, record)
                BrowserStore.DownloadState.WAITING -> tryStartWaiting(context, record)
                else -> Unit
            }
        }
    }

    /**
     * Starts a WAITING plain record for real (r160): when the
     * Wi-Fi-only setting is off, or the phone is on an unmetered
     * network, the record enqueues through DownloadManager under
     * its same id; when still gated it is left waiting, never
     * failed. A missing DownloadManager or a failed enqueue marks
     * it failed — that is a real failure, not a network the user
     * told the app to avoid.
     */
    private fun tryStartWaiting(context: Context, record: BrowserStore.DownloadRecord) {
        val current = BrowserStore.downloadById(context, record.id) ?: return
        if (current.state != BrowserStore.DownloadState.WAITING) return
        if (shouldWaitForWifi(
                BrowserStore.wifiOnlyDownloadsEnabled(context),
                isUnmeteredNetwork(context),
            )
        ) {
            return
        }
        val dm = context.getSystemService(DownloadManager::class.java)
        if (dm == null) {
            BrowserStore.updateDownload(context, current.copy(state = BrowserStore.DownloadState.FAILED))
            return
        }
        runCatching {
            val dmId = dm.enqueue(buildRequest(current.url, current.fileName))
            val updated = BrowserStore.retried(current, dmId)
            BrowserStore.updateDownload(context, updated)
            GearslipLog.i("car browser download started after Wi-Fi wait: ${current.fileName}")
        }.getOrElse { e ->
            GearslipLog.e("car browser download start FAILED: ${current.url} - ${e.message}")
            BrowserStore.updateDownload(context, current.copy(state = BrowserStore.DownloadState.FAILED))
        }
    }

    /**
     * Re-query one record, persist any state change, and return the current record. Worker records have no DownloadManager row (manager id -1), so the query is a no-op for them — their state is written by the worker itself. A complete plain record whose file no longer resolves then gets the backfill attempt (see [backfillLocalSource]) — whether its DownloadManager row is gone or merely points at a file moved elsewhere.
     */
    fun refreshRecord(context: Context, record: BrowserStore.DownloadRecord): BrowserStore.DownloadRecord {
        val status = queryStatus(context, record.downloadManagerId)
        val afterQuery = if (status == null) {
            record
        } else if (status.state == record.state && status.localUri == record.localSource) {
            record
        } else {
            val updated = record.copy(state = status.state, localSource = status.localUri)
            BrowserStore.updateDownload(context, updated)
            updated
        }
        if (afterQuery.kind == BrowserStore.DownloadKind.PLAIN &&
            afterQuery.state == BrowserStore.DownloadState.COMPLETE &&
            (afterQuery.localSource == null || !localFileExists(context, afterQuery.localSource))
        ) {
            val backfilled = backfillLocalSource(context, afterQuery)
            if (backfilled != null) {
                val updated = afterQuery.copy(localSource = backfilled)
                BrowserStore.updateDownload(context, updated)
                return updated
            }
        }
        return afterQuery
    }

    /**
     * MediaStore backfill (r160): for a complete plain record whose
     * file no longer resolves, look for a Downloads row with the
     * record's exact file name dated within two days of the
     * record's creation, and return its content URI when exactly
     * one such row exists. Zero or several matches refuse — the
     * record is left as-is and still reports as gone, rather than
     * recovering the wrong file. Worker records never backfill:
     * their published file either exists at its recorded URI or
     * it is gone.
     */
    private fun backfillLocalSource(context: Context, record: BrowserStore.DownloadRecord): String? {
        val candidates = runCatching {
            val projection = arrayOf(
                android.provider.MediaStore.MediaColumns._ID,
                android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                android.provider.MediaStore.MediaColumns.DATE_ADDED,
            )
            val selection = "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            context.contentResolver.query(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                arrayOf(record.fileName),
                null,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
                val dateIndex = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.DATE_ADDED)
                val out = mutableListOf<BackfillCandidate>()
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val uri = ContentUris.withAppendedId(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        id,
                    ).toString()
                    out.add(
                        BackfillCandidate(
                            displayName = cursor.getString(nameIndex) ?: "",
                            addedAtMs = cursor.getLong(dateIndex) * 1000L,
                            uri = uri,
                        ),
                    )
                }
                out
            } ?: emptyList()
        }.getOrDefault(emptyList())
        return backfillMatch(candidates, record.fileName, record.createdAtMs)
    }

    /**
     * Re-enqueue a failed or interrupted download under the same record
     * id, resetting it to in progress. A completed record is a no-op:
     * retrying it must never produce a duplicate download. When the fresh
     * enqueue itself fails, the record is marked failed.
     *
     * Worker records (r159, generalized r160 to every worker
     * engine) re-enqueue through their worker, not through
     * DownloadManager: REPLACE policy, so any stale run is cancelled
     * first, and the run resumes from the work already done — HLS
     * segments on disk, segmented parts on disk, or, for DASH, a
     * fresh export (Transformer cannot resume a partial export).
     * A paused worker record retries the same way — Resume and Retry
     * are one path, differing only in which button the sheet shows.
     */
    fun retryDownload(context: Context, record: BrowserStore.DownloadRecord): BrowserStore.DownloadRecord {
        if (!BrowserStore.retryable(record)) return record
        if (isWorkerKind(record.kind)) {
            val current = BrowserStore.downloadById(context, record.id) ?: record
            if (!BrowserStore.retryable(current)) return current
            return runCatching {
                val updated = BrowserStore.retriedWorker(current)
                BrowserStore.updateDownload(context, updated)
                enqueueWorkFor(context, updated, replace = true)
                GearslipLog.i("car browser worker download retried: ${record.fileName}")
                updated
            }.getOrElse { e ->
                GearslipLog.e("car browser worker download retry FAILED: ${record.url} - ${e.message}")
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
     * Pause a worker download (r159, generalized r160): cancels the unique work and
     * marks the record paused, keeping its temp work for resume.
     * A no-op on anything but an in-progress worker record — plain
     * downloads are not user-pausable, and a finished, failed, or
     * already-paused record has nothing to pause. The stored record
     * is re-read first, so a completion that landed since the list
     * was drawn is never overwritten by a stale pause.
     */
    fun pauseDownload(context: Context, record: BrowserStore.DownloadRecord): BrowserStore.DownloadRecord {
        val current = BrowserStore.downloadById(context, record.id) ?: record
        if (!isWorkerKind(current.kind) ||
            current.state != BrowserStore.DownloadState.IN_PROGRESS
        ) {
            return current
        }
        cancelWorkFor(context, current)
        val paused = current.copy(state = BrowserStore.DownloadState.PAUSED)
        BrowserStore.updateDownload(context, paused)
        return paused
    }

    /**
     * Cancel a worker download (r159, generalized r160): cancels the unique work and
     * drops the record together with everything it owns — temp
     * work and any published file. A no-op on plain records and
     * on completed worker records (a finished file is deleted through
     * Delete, not Cancel).
     */
    fun cancelWorkerDownload(context: Context, record: BrowserStore.DownloadRecord) {
        val current = BrowserStore.downloadById(context, record.id) ?: record
        if (!isWorkerKind(current.kind)) return
        if (current.state == BrowserStore.DownloadState.COMPLETE) return
        cancelWorkFor(context, current)
        deleteWorkerFiles(context, current)
        BrowserStore.removeDownload(context, current.id)
    }

    /**
     * Delete a download: remove it from DownloadManager (which deletes
     * the bytes, or cancels a partial download) and drop the record.
     * A record whose file is already gone is success, not an error —
     * the record is dropped either way. Worker records (r159,
     * generalized r160) delete through their own path: cancel the work, drop temp
     * work and any published file, then drop the record.
     */
    fun deleteDownload(context: Context, record: BrowserStore.DownloadRecord) {
        if (isWorkerKind(record.kind)) {
            val current = BrowserStore.downloadById(context, record.id) ?: record
            cancelWorkFor(context, current)
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
     * player itself. A complete plain record whose file no longer
     * resolves gets the MediaStore backfill attempt first (see
     * [backfillLocalSource]): a moved file with a unique
     * name-and-date match recovers, and only a genuinely gone or
     * ambiguous file reports as gone.
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
