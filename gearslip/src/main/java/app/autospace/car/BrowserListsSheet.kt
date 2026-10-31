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
 * straight to their section.
 */
enum class ListsSection { TOP, BOOKMARKS, HISTORY }

@Composable
fun BrowserListsSheet(
    bookmarks: List<String>,
    history: List<String>,
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
            LaunchedEffect(initialSection, bookmarksY, historyY) {
                val target = when (initialSection) {
                    ListsSection.BOOKMARKS -> bookmarksY
                    ListsSection.HISTORY -> historyY
                    ListsSection.TOP -> -1
                }
                if (target >= 0) scrollState.scrollTo(target)
            }
            // r153 (audit §4 A5): search inside the bookmarks and history lists.
            // In-memory substring filter over the already-received lists, contacts
            // pattern — no BrowserStore change, no new persistence, and the
            // section-jump offsets below keep working because they re-capture
            // on layout.
            var listQuery by remember { mutableStateOf("") }
            var listSearching by remember { mutableStateOf(false) }
            val shownBookmarks = if (listQuery.isBlank()) bookmarks
                else bookmarks.filter { it.contains(listQuery, ignoreCase = true) }
            val shownHistory = if (listQuery.isBlank()) history
                else history.filter { it.contains(listQuery, ignoreCase = true) }
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
                    Text(if (listSearching) "Done searching" else "Search bookmarks and history")
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
                GsButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text("Close")
                }
            }
        }
    }
}
