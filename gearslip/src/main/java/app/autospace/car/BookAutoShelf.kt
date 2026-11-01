package app.autospace.car

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.autospace.AppSettings

/** Auto-shelf outcome, for the toast the user sees. */
sealed interface AutoShelfOutcome {
    data object Skipped : AutoShelfOutcome
    data object Already : AutoShelfOutcome
    data class Added(val chapters: Int) : AutoShelfOutcome
    data class Failed(val reason: String) : AutoShelfOutcome
}

/**
 * r161: download auto-shelf, the only browser-downloads to bookshelf
 * bridge. When a plain download completes as an EPUB and auto-shelf is
 * on (default on, Mariner's epubAutoShelf default) and the download is
 * not private, the file is copied to the shelf, parsed, and added.
 * A parse failure leaves the download untouched. EPUB downloads always
 * route to plain DownloadManager (the download router sends only
 * classified media to workers), so the completion receiver is the
 * whole hook: no downloads code is reworked here.
 */
object BookAutoShelf {

    fun autoShelfEnabled(context: Context): Boolean =
        AppSettings.getString(context, BookLibrary.KEY_AUTO_SHELF, "true") != "false"

    fun setAutoShelfEnabled(context: Context, on: Boolean) {
        AppSettings.putString(context, BookLibrary.KEY_AUTO_SHELF, on.toString())
    }

    fun shelvedNames(context: Context): Set<String> =
        BookLibrary.decodeNames(
            AppSettings.getString(context, BookLibrary.KEY_AUTO_SHELF_NAMES, ""),
        ).toSet()

    private fun rememberName(context: Context, name: String) {
        val names = shelvedNames(context) + name
        AppSettings.putString(
            context,
            BookLibrary.KEY_AUTO_SHELF_NAMES,
            BookLibrary.encodeNames(names.sorted()),
        )
    }

    /**
     * Pure guard, unit-tested: auto-shelf fires only for a completed,
     * non-private EPUB download when the switch is on and the name
     * has not been auto-shelved already.
     */
    fun shouldAutoShelf(
        autoShelfOn: Boolean,
        isPrivate: Boolean,
        state: BrowserStore.DownloadState,
        fileName: String,
        alreadyShelved: Boolean,
    ): Boolean {
        if (!autoShelfOn) return false
        if (isPrivate) return false
        if (state != BrowserStore.DownloadState.COMPLETE) return false
        if (BookLibrary.gateForFileName(fileName) != BookGate.EPUB) return false
        if (alreadyShelved) return false
        return true
    }

    /**
     * Called from the download completion receiver with a record that
     * has just completed. The guard runs at once; the copy and parse
     * run off the receiver thread (a broadcast receiver must not do
     * slow IO), and the toast posts back to the main thread.
     */
    fun maybeAutoShelf(context: Context, record: BrowserStore.DownloadRecord) {
        val appContext = context.applicationContext
        val source = record.localSource ?: return
        if (!shouldAutoShelf(
                autoShelfOn = autoShelfEnabled(appContext),
                isPrivate = record.isPrivate,
                state = record.state,
                fileName = record.fileName,
                alreadyShelved = false,
            )
        ) {
            return
        }
        if (shelvedNames(appContext).contains(record.fileName)) {
            postToast(appContext, "Already on your shelf")
            return
        }
        Thread {
            val result = BookImport.importFromUri(
                appContext,
                Uri.parse(source),
                allowDuplicate = true,
                displayNameOverride = record.fileName,
            )
            when (result) {
                is BookImportResult.Added -> {
                    rememberName(appContext, record.fileName)
                    postToast(appContext, "Added to your bookshelf")
                }
                is BookImportResult.Refused -> {
                    // A refused auto-shelf (shelf full, or a name that
                    // stopped being an EPUB) leaves the download
                    // untouched and says so plainly.
                    postToast(appContext, result.reason)
                }
                is BookImportResult.Failed -> {
                    postToast(appContext, "That EPUB could not be added to your bookshelf")
                }
            }
        }.start()
    }

    private fun postToast(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post {
            runCatching {
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            }
        }
    }
}
