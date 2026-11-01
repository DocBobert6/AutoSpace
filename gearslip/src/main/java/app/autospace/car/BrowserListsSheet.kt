package app.autospace.car

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.autospace.car.theme.GsButton
import app.autospace.car.theme.GsSheetSurface
import app.autospace.car.theme.GsTone

/**
 * Bookmarks + history picker (Phase 3.4), in the same in-composition overlay
 * style as the media-finder sheet (no real Dialog window on this virtual
 * display). Tapping an entry loads it; bookmarking the current page and
 * clearing history live here too, so the bar stays to one row.
 *
 * 2026-10-25 reachability fix — the actual break behind "the page
 * is unreachable": this sheet's Column did NOT scroll, and its two
 * lists were LazyColumns weighted inside that wrap-content Column.
 * The unweighted rows alone (title, bookmark button, the whole
 * Popups section, section titles, Close) already exceed a car
 * screen's sheet space, so the weighted lists were measured at
 * ~0 height and History + Close were clipped off-screen entirely.
 * Both tap entries (the bar's Star button, the settings menu's
 * "Saved & history" row) DID open the sheet — what opened simply
 * could not be reached. Now the whole sheet scrolls (like the
 * settings menu) and the lists are plain Columns inside it: history
 * is capped at 50 and bookmarks at 200 in BrowserStore, so eager
 * rows cost nothing here, and every row — including Close — is
 * reachable by scrolling on any screen height. No nested
 * LazyColumn-in-scroll-Column, which Compose forbids in spirit
 * (the inner list would lose its bounds).
 */
/**
 * Which section the lists sheet opens at (r130). TOP is the whole sheet
 * from the top (the bar star button and the old Saved & history row);
 * BOOKMARKS and HISTORY are the settings sheet's Library rows jumping
 * straight to their section; DOWNLOADS is the r157 Downloads row;
 * LIBRARY is the r158 Offline library row; NEWSSTAND is the r162
 * saved-articles row.
 */
enum class ListsSection { TOP, BOOKMARKS, HISTORY, DOWNLOADS, LIBRARY, NEWSSTAND }

/** r162: the caption under a Newsstand row — site name and saved date, either alone when the other is missing. */
private fun newsstandCaption(article: BrowserStore.SavedArticle): String {
    val date = if (article.savedAtMs > 0L) {
        java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US)
            .format(java.util.Date(article.savedAtMs))
    } else ""
    return listOf(article.siteName, date).filter { it.isNotBlank() }.joinToString(" · ")
}
/** r157: the label on a Downloads row — state rides in the label itself. r159: worker rows show their reported percent while downloading, and paused rows say so. r160: a record waiting for Wi-Fi says so. */
private fun downloadRowLabel(record: BrowserStore.DownloadRecord): String =
    when (record.state) {
        BrowserStore.DownloadState.IN_PROGRESS ->
            if (record.progress in 0..100) "${record.fileName} - Downloading ${record.progress}%"
            else "${record.fileName} - Downloading"
        BrowserStore.DownloadState.FAILED -> "${record.fileName} - Failed"
        BrowserStore.DownloadState.COMPLETE -> record.fileName
        BrowserStore.DownloadState.PAUSED -> "${record.fileName} - Paused"
        BrowserStore.DownloadState.WAITING -> "${record.fileName} - Waiting for Wi-Fi"
    }

@Composable
fun BrowserListsSheet(
    bookmarks: List<String>,
    history: List<String>,
    // r157: the Downloads list (non-private, non-media records only —
    // the caller filters) plus its row actions; the sheet itself never
    // touches BrowserStore or DownloadManager directly.
    downloads: List<BrowserStore.DownloadRecord>,
    // r158: the Offline library (media, non-private records only — the
    // caller filters) plus its row actions. Play hands the verified
    // local source to the existing video player; Share and Save-a-copy
    // never touch the record itself.
    library: List<BrowserStore.DownloadRecord>,
    initialSection: ListsSection = ListsSection.TOP,
    currentUrl: String,
    currentHost: String,
    popupStrict: Boolean,
    popupSetting: BrowserStore.PopupSetting,
    onPopupStrictChange: (Boolean) -> Unit,
    onPopupSettingChange: (BrowserStore.PopupSetting) -> Unit,
    isBookmarked: Boolean,
    isPinned: Boolean,
    onPinToggle: () -> Unit,
    onLoad: (String) -> Unit,
    onBookmarkToggle: () -> Unit,
    onRemoveBookmark: (String) -> Unit,
    onClearHistory: () -> Unit,
    onOpenDownload: (BrowserStore.DownloadRecord) -> Unit,
    onDeleteDownload: (BrowserStore.DownloadRecord) -> Unit,
    onRetryDownload: (BrowserStore.DownloadRecord) -> Unit,
    onPlayLibrary: (BrowserStore.DownloadRecord) -> Unit,
    onShareLibrary: (BrowserStore.DownloadRecord) -> Unit,
    onSaveLibraryCopy: (BrowserStore.DownloadRecord) -> Unit,
    // r159: library row management. Delete drops the record and its
    // bytes (already-gone is success); Retry re-enqueues a failed
    // row under the same id; Pause, Resume, and Cancel exist only
    // for worker rows (the sheet gates them with the BrowserDownloads
    // helpers, so plain rows never show a pause affordance).
    onDeleteLibrary: (BrowserStore.DownloadRecord) -> Unit,
    onRetryLibrary: (BrowserStore.DownloadRecord) -> Unit,
    onPauseLibrary: (BrowserStore.DownloadRecord) -> Unit,
    onResumeLibrary: (BrowserStore.DownloadRecord) -> Unit,
    onCancelLibrary: (BrowserStore.DownloadRecord) -> Unit,
    // r162: Newsstand (saved articles only — the caller passes the
    // stored list; the sheet itself never touches BrowserStore).
    // Open reopens the saved article in the reader overlay; Delete
    // drops the row only (no clear-all in v1). The live page opens
    // through the shared onLoad below.
    newsstand: List<BrowserStore.SavedArticle>,
    onOpenNewsstand: (BrowserStore.SavedArticle) -> Unit,
    onDeleteNewsstand: (BrowserStore.SavedArticle) -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClose,
            ),
        )
        // Phase 7 kit sheet: solid surfaceContainerLow, 28dp corners.
        // Phase 8: the kit sheet composable itself (same colours, plus the
        // OLED hairline when OLED is on).
        GsSheetSurface(Modifier.fillMaxWidth().padding(
            start = 16.dp, end = 16.dp, top = 16.dp, bottom = 80.dp,
        )) {
            // r130: section jumps. The title/row offsets are captured
            // in the column's own coordinates, which is exactly what
            // the scroll state scrolls by; the effect re-runs when an
            // offset lands after first layout, so the jump happens even
            // though layout has not run when the sheet first composes.
            val scrollState = rememberScrollState()
            var bookmarksY by remember { mutableStateOf(-1) }
            var historyY by remember { mutableStateOf(-1) }
            var downloadsY by remember { mutableStateOf(-1) }
            var libraryY by remember { mutableStateOf(-1) }
            var newsstandY by remember { mutableStateOf(-1) }
            LaunchedEffect(initialSection, bookmarksY, historyY, downloadsY, libraryY, newsstandY) {
                val target = when (initialSection) {
                    ListsSection.BOOKMARKS -> bookmarksY
                    ListsSection.HISTORY -> historyY
                    ListsSection.DOWNLOADS -> downloadsY
                    ListsSection.LIBRARY -> libraryY
                    ListsSection.NEWSSTAND -> newsstandY
                    ListsSection.TOP -> -1
                }
                if (target >= 0) scrollState.scrollTo(target)
            }
            // r153 (audit §4 A5): search inside the bookmarks and history lists.
            // In-memory substring filter over the already-received lists, contacts
            // pattern — no BrowserStore change, no new persistence, and the
            // section-jump offsets below keep working because they re-capture
            // on layout. r157: Downloads rides the same search (file name or
            // source URL), since it is now a list in this same sheet.
            // r158: the Offline library rides it too.
            // r162: Newsstand rides it too (title, site name, or source URL).
            var listQuery by remember { mutableStateOf("") }
            var listSearching by remember { mutableStateOf(false) }
            val shownBookmarks = if (listQuery.isBlank()) bookmarks
                else bookmarks.filter { it.contains(listQuery, ignoreCase = true) }
            val shownHistory = if (listQuery.isBlank()) history
                else history.filter { it.contains(listQuery, ignoreCase = true) }
            val shownDownloads = if (listQuery.isBlank()) downloads
                else downloads.filter {
                    it.fileName.contains(listQuery, ignoreCase = true) ||
                        it.url.contains(listQuery, ignoreCase = true)
                }
            val shownLibrary = if (listQuery.isBlank()) library
                else library.filter {
                    it.fileName.contains(listQuery, ignoreCase = true) ||
                        it.url.contains(listQuery, ignoreCase = true)
                }
            val shownNewsstand = if (listQuery.isBlank()) newsstand
                else newsstand.filter {
                    it.title.contains(listQuery, ignoreCase = true) ||
                        it.siteName.contains(listQuery, ignoreCase = true) ||
                        it.url.contains(listQuery, ignoreCase = true)
                }
            Column(
                Modifier.fillMaxWidth().verticalScroll(scrollState).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Saved & history",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                GsButton(onClick = { listSearching = !listSearching }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (listSearching) "Done searching" else "Search bookmarks, history, downloads, library, and newsstand")
                }
                if (listSearching) {
                    CarKeyboard(
                        listQuery,
                        onTextChange = { listQuery = it },
                        onSubmit = { listSearching = false },
                        onDismiss = { listSearching = false },
                    )
                }
                GsButton(onClick = onBookmarkToggle, modifier = Modifier.fillMaxWidth()) {
                    Text(if (isBookmarked) "Remove this page from bookmarks" else "Bookmark this page")
                }
                // Start-page pins (r21): the user pins a page here, and it
                // shows on every new tab's start page until removed.
                GsButton(onClick = onPinToggle, modifier = Modifier.fillMaxWidth()) {
                    Text(if (isPinned) "Remove this page from start page pins" else "Pin this page to the start page")
                }
                // Phase 8 per-site popup settings (Notscape's model): strict
                // blocking is ON by default; a site set to Allow loads its
                // popups in this browser (Back returns), a site set to Block
                // always silences them with no notice, and Strict default
                // captures them for the popup notice instead.
                Text("Popups", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Strict blocking captures popups and offers them from a notice " +
                        "instead of opening them silently. Allowed popups load in this " +
                        "browser — Back returns.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                GsButton(onClick = { onPopupStrictChange(!popupStrict) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (popupStrict) "Strict popup blocking: On" else "Strict popup blocking: Off")
                }
                if (currentHost.isNotBlank()) {
                    Text(
                        "For $currentHost:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GsButton(
                            onClick = { onPopupSettingChange(BrowserStore.PopupSetting.ALLOW) },
                            tone = if (popupSetting == BrowserStore.PopupSetting.ALLOW) GsTone.Primary else GsTone.Neutral,
                            modifier = Modifier.weight(1f),
                        ) { Text("Allow") }
                        GsButton(
                            onClick = { onPopupSettingChange(BrowserStore.PopupSetting.BLOCK) },
                            tone = if (popupSetting == BrowserStore.PopupSetting.BLOCK) GsTone.Primary else GsTone.Neutral,
                            modifier = Modifier.weight(1f),
                        ) { Text("Block always") }
                        GsButton(
                            onClick = { onPopupSettingChange(BrowserStore.PopupSetting.STRICT) },
                            tone = if (popupSetting == BrowserStore.PopupSetting.STRICT) GsTone.Primary else GsTone.Neutral,
                            modifier = Modifier.weight(1f),
                        ) { Text("Strict default") }
                    }
                }
                Text(
                    "Bookmarks",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.onGloballyPositioned { bookmarksY = it.positionInParent().y.toInt() },
                )
                if (shownBookmarks.isEmpty()) {
                    Text(
                        if (listQuery.isBlank()) "No bookmarks yet." else "No bookmarks match \"$listQuery\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        shownBookmarks.forEach { url ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                GsButton(onClick = { onLoad(url) }, tone = GsTone.Neutral, modifier = Modifier.weight(1f)) {
                                    Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                GsButton(onClick = { onRemoveBookmark(url) }, tone = GsTone.Neutral) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.onGloballyPositioned { historyY = it.positionInParent().y.toInt() },
                ) {
                    Text("History", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    GsButton(onClick = onClearHistory, tone = GsTone.Neutral) { Text("Clear") }
                }
                if (shownHistory.isEmpty()) {
                    Text(
                        if (listQuery.isBlank()) "No history yet." else "No history matches \"$listQuery\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        shownHistory.forEach { url ->
                            GsButton(onClick = { onLoad(url) }, tone = GsTone.Neutral, modifier = Modifier.fillMaxWidth()) {
                                Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                // r157: Downloads. Tap the file name to open the finished
                // file with the system viewer (a download still running
                // or failed toasts instead of opening); Retry shows only
                // on failed rows (retrying a finished download would be
                // a duplicate, so it is a no-op by design); Delete drops
                // the record and the bytes, and is success even when the
                // file is already gone. Private-origin and media
                // downloads never reach this list — the caller filters
                // them out (media lives in the Offline library below).
                Text(
                    "Downloads",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.onGloballyPositioned { downloadsY = it.positionInParent().y.toInt() },
                )
                if (shownDownloads.isEmpty()) {
                    Text(
                        if (listQuery.isBlank()) "No downloads yet." else "No downloads match \"$listQuery\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        shownDownloads.forEach { record ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                GsButton(onClick = { onOpenDownload(record) }, tone = GsTone.Neutral, modifier = Modifier.weight(1f)) {
                                    Text(downloadRowLabel(record), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (record.state == BrowserStore.DownloadState.FAILED) {
                                    GsButton(onClick = { onRetryDownload(record) }, tone = GsTone.Neutral) {
                                        Text("Retry")
                                    }
                                }
                                GsButton(onClick = { onDeleteDownload(record) }, tone = GsTone.Neutral) {
                                    Text("Delete")
                                }
                            }
                        }
                    }
                }
                // r158: Offline library. Media downloads only — video
                // and audio, non-private, any state (a row still
                // downloading or failed toasts instead of playing).
                // Play hands the verified local source to the existing
                // video player (audio plays there too, by decision);
                // Share sends the file out; Save-a-copy copies it to a
                // user-chosen location and never touches the record.
                // Private-origin media never reaches this list — the
                // caller filters it out, and its bytes die with the
                // private profile exactly like any private download.
                // r159: Delete drops the row and its bytes; Retry
                // re-enqueues a failed row; Pause, Resume, and Cancel
                // show only on worker rows, gated by the
                // BrowserDownloads helpers — plain rows never offer
                // a pause, because plain downloads are not pausable.
                // r160: the worker rows are HLS, DASH, and segmented.
                Text(
                    "Offline library",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.onGloballyPositioned { libraryY = it.positionInParent().y.toInt() },
                )
                if (shownLibrary.isEmpty()) {
                    Text(
                        if (listQuery.isBlank()) "No offline media yet." else "No offline media matches \"$listQuery\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        shownLibrary.forEach { record ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                GsButton(onClick = { onPlayLibrary(record) }, tone = GsTone.Neutral, modifier = Modifier.weight(1f)) {
                                    Text(downloadRowLabel(record), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                GsButton(onClick = { onShareLibrary(record) }, tone = GsTone.Neutral) {
                                    Text("Share")
                                }
                                GsButton(onClick = { onSaveLibraryCopy(record) }, tone = GsTone.Neutral) {
                                    Text("Save a copy")
                                }
                                GsButton(onClick = { onDeleteLibrary(record) }, tone = GsTone.Neutral) {
                                    Text("Delete")
                                }
                                if (BrowserDownloads.showLibraryRetry(record)) {
                                    GsButton(onClick = { onRetryLibrary(record) }, tone = GsTone.Neutral) {
                                        Text("Retry")
                                    }
                                }
                                if (BrowserDownloads.showLibraryPause(record)) {
                                    GsButton(onClick = { onPauseLibrary(record) }, tone = GsTone.Neutral) {
                                        Text("Pause")
                                    }
                                }
                                if (BrowserDownloads.showLibraryResume(record)) {
                                    GsButton(onClick = { onResumeLibrary(record) }, tone = GsTone.Neutral) {
                                        Text("Resume")
                                    }
                                }
                                if (BrowserDownloads.showLibraryCancel(record)) {
                                    GsButton(onClick = { onCancelLibrary(record) }, tone = GsTone.Neutral) {
                                        Text("Cancel")
                                    }
                                }
                            }
                        }
                    }
                }
                // r162: Newsstand. Saved articles only — never downloads,
                // never books. Open reopens the saved article in the same
                // reader overlay (no re-extraction, no network, at the
                // top); Live page opens the saved URL in a normal tab;
                // Delete drops the row only (no clear-all in v1).
                Text(
                    "Newsstand",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.onGloballyPositioned { newsstandY = it.positionInParent().y.toInt() },
                )
                if (shownNewsstand.isEmpty()) {
                    Text(
                        if (listQuery.isBlank()) "No saved articles yet." else "No saved articles match \"$listQuery\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        shownNewsstand.forEach { article ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    GsButton(onClick = { onOpenNewsstand(article) }, tone = GsTone.Neutral, modifier = Modifier.fillMaxWidth()) {
                                        Text(article.title.ifBlank { article.url }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    val caption = newsstandCaption(article)
                                    if (caption.isNotBlank()) {
                                        Text(
                                            caption,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                GsButton(onClick = { onLoad(article.url) }, tone = GsTone.Neutral) {
                                    Text("Live page")
                                }
                                GsButton(onClick = { onDeleteNewsstand(article) }, tone = GsTone.Neutral) {
                                    Text("Delete")
                                }
                            }
                        }
                    }
                }
                GsButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text("Close")
                }
            }
        }
    }
}

