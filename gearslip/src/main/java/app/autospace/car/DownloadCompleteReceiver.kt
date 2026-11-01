package app.autospace.car

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Plain-download completion (r157, slice 1). DownloadManager sends
 * ACTION_DOWNLOAD_COMPLETE when an enqueued download finishes; this
 * receiver refreshes just that download's record (complete + local
 * source, or failed). Unknown ids are ignored — a spoofed broadcast
 * can only ever no-op, because the refresh queries DownloadManager
 * itself. The receiver is the prompt path; the list-open refresh in
 * BrowserDownloads is the backstop, so a missed broadcast can never
 * leave a finished download stuck at in progress.
 */
class DownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val dmId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (dmId < 0L) return
        val record = BrowserStore.downloadByManagerId(context, dmId) ?: return
        if (record.state != BrowserStore.DownloadState.IN_PROGRESS) return
        BrowserDownloads.refreshRecord(context, record)
    }
}
