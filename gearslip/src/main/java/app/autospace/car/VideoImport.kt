package app.autospace.car

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns

/** Whether a picked file may enter the offline library. */
enum class VideoImportGate {
    OK,
    NOT_VIDEO,
    MANIFEST,
}

/** The outcome of an import attempt. */
sealed interface VideoImportResult {
    /** The video joined the offline library. */
    data class Added(val record: BrowserStore.DownloadRecord) : VideoImportResult

    /** Refused for a reason the user can act on (wrong kind, already there, list full). */
    data class Refused(val reason: String) : VideoImportResult

    /** The import itself failed (unreadable file, permission not kept). */
    data class Failed(val reason: String) : VideoImportResult
}

/**
 * Import videos from the phone into the browser's Offline library (r205).
 *
 * Mariner has NO video-import path — its OpenDocument is subtitle import
 * only (PlayerActivity) — so this is an AutoSpace shape: the picker is a
 * hand-built ACTION_OPEN_DOCUMENT intent (not the OpenDocument contract)
 * so the grant carries FLAG_GRANT_PERSISTABLE_URI_PERMISSION, and the
 * record is a pointer at the phone's file, never a copy. Deleting the row
 * drops the record and releases the persisted permission; the original
 * file is never touched.
 *
 * Identity is the content URI: the record id is `import:<uri>`, so a file
 * already in the library is refused by the same distinctBy-id rule the
 * library already uses for downloaded files. Imports are library actions,
 * not tab actions — private tabs have no interaction with them, and an
 * imported record is never private.
 */
object VideoImport {

    /** The id prefix that marks a record as an import (never a download). */
    const val IMPORT_ID_PREFIX = "import:"

    /** The picker intent: video files on the phone, no new permission needed. */
    fun pickIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "video/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }

    /** Pure: is this filename a video the player can open, a playlist, or neither? */
    fun gateForFileName(fileName: String): VideoImportGate {
        return when (val ext = VideoUrl.extensionOf(fileName)) {
            "m3u8", "mpd" -> VideoImportGate.MANIFEST
            else -> if (VideoUrl.isVideoExtension(ext)) VideoImportGate.OK else VideoImportGate.NOT_VIDEO
        }
    }

    /** Pure: the record id for an imported URI. */
    fun importIdFor(uri: String): String = IMPORT_ID_PREFIX + uri

    /** Pure: is this library record an import (a pointer, not a download)? */
    fun isImportedRecord(record: BrowserStore.DownloadRecord): Boolean =
        record.id.startsWith(IMPORT_ID_PREFIX)

    /** Pure: the library record for an imported video. */
    fun recordForImport(uri: String, displayName: String, nowMs: Long): BrowserStore.DownloadRecord =
        BrowserStore.DownloadRecord(
            id = importIdFor(uri),
            url = uri,
            fileName = displayName,
            state = BrowserStore.DownloadState.COMPLETE,
            downloadManagerId = -1L,
            localSource = uri,
            isPrivate = false,
            createdAtMs = nowMs,
            isMedia = true,
            kind = BrowserStore.DownloadKind.PLAIN,
        )

    /** The picked file's display name, or null when it cannot be read. */
    fun displayName(context: Context, uri: Uri): String? {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()
        val name = fromProvider ?: uri.lastPathSegment
        return name?.takeIf { it.isNotBlank() }
    }

    /**
     * Add the picked video to the offline library. Checks run in order:
     * readable file, video kind, not already imported, room in the list —
     * then the persisted permission is taken and the record is stored.
     * Nothing is taken or stored for a refused import.
     */
    fun importFromUri(context: Context, uri: Uri): VideoImportResult {
        val name = displayName(context, uri)
            ?: return VideoImportResult.Failed("Could not read that file.")
        return when (gateForFileName(name)) {
            VideoImportGate.NOT_VIDEO ->
                VideoImportResult.Refused("Only video files can be added to the offline library.")
            VideoImportGate.MANIFEST ->
                VideoImportResult.Refused("Playlists cannot be imported. Pick a video file.")
            VideoImportGate.OK -> importVideo(context, uri, name)
        }
    }

    private fun importVideo(context: Context, uri: Uri, displayName: String): VideoImportResult {
        val uriText = uri.toString()
        if (BrowserStore.downloads(context).any { it.id == importIdFor(uriText) }) {
            return VideoImportResult.Refused("Already in your offline library")
        }
        if (!BrowserStore.canAddDownload(context)) {
            return VideoImportResult.Refused("Your downloads list is full (200 items). Remove one to add another.")
        }
        val persisted = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.isSuccess
        if (!persisted) {
            return VideoImportResult.Failed("Could not keep access to that file.")
        }
        val record = recordForImport(uriText, displayName, System.currentTimeMillis())
        BrowserStore.addDownload(context, record)
        return VideoImportResult.Added(record)
    }
}
