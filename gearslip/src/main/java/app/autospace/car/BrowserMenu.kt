package app.autospace.car

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.autospace.car.theme.GsButton
import app.autospace.car.theme.GsIconButton
import app.autospace.car.theme.GsSheetSurface
import app.autospace.car.theme.gsButtonShape

/**
 * The browser's menu sheet (r185, browser parity Phase 2): a
 * Mariner-shaped menu, separate from the settings sheet. Every row
 * dismisses the menu first and then acts — Mariner's pattern — so no
 * row ever acts from behind the sheet. Rows honour the user's menu
 * customization (hidden rows do not render) and Mariner's
 * private-tab rule (bookmark and pin never render for a private tab,
 * which saves nothing). A section renders only when at least one of
 * its rows shows, so a fully hidden section leaves no empty label.
 * The header's menu button opens this sheet; Settings is a row in it.
 * A kit sheet, not a DropdownMenu, because real popup windows crash
 * on this virtual display.
 */
@Composable
fun BrowserMenuSheet(
    isPrivate: Boolean,
    isStartPage: Boolean,
    desktopMode: Boolean,
    isBookmarked: Boolean,
    isPinned: Boolean,
    hiddenIds: Set<String>,
    onClose: () -> Unit,
    onNewTab: () -> Unit,
    onNewPrivateTab: () -> Unit,
    onReload: () -> Unit,
    onSharePage: () -> Unit,
    onBookmarkToggle: () -> Unit,
    onPinToggle: () -> Unit,
    onReadArticle: () -> Unit,
    onMediaScan: () -> Unit,
    onFindInPage: () -> Unit,
    onToggleDesktop: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onCustomize: () -> Unit,
) {
    @Composable
    fun MenuRow(label: String, checked: Boolean, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth()
                .background(
                    if (checked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    gsButtonShape(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)),
                )
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (checked) {
                androidx.compose.material3.Icon(
                    Icons.Filled.Check, null,
                    tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
            } else {
                Spacer(Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal,
                color = if (checked) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    @Composable
    fun MenuSection(title: String) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
    // One row's visibility: the user's hidden set, Mariner's
    // private-tab rule, and the row's own condition (page actions
    // need a loaded page, not a start page).
    fun shows(id: String, extra: Boolean = true): Boolean =
        extra && BrowserMenuRows.isVisible(id, hiddenIds) &&
            BrowserMenuRows.visibleForTab(id, isPrivate)
    val hasPage = !isStartPage
    // Rows grouped the way Mariner groups them (tabs, page actions,
    // library, settings); a section with nothing showing is skipped.
    // Each row carries its registry id so visibility is decided by id,
    // never by position in the list.
    data class MenuItem(val id: String, val label: String, val checked: Boolean, val onClick: () -> Unit)
    val sections: List<Pair<String, List<MenuItem>>> = listOf(
        "Tabs" to listOf(
            MenuItem(BrowserMenuRows.NEW_TAB, "New tab", false, onNewTab),
            MenuItem(BrowserMenuRows.NEW_PRIVATE_TAB, "New private tab", false, onNewPrivateTab),
        ).filter { shows(it.id) },
        "Page" to listOf(
            MenuItem(BrowserMenuRows.RELOAD, "Reload", false, onReload),
            MenuItem(BrowserMenuRows.SHARE_PAGE, "Share page", false, onSharePage),
            MenuItem(
                BrowserMenuRows.BOOKMARK,
                if (isBookmarked) "Remove bookmark" else "Bookmark this page",
                isBookmarked, onBookmarkToggle,
            ),
            MenuItem(
                BrowserMenuRows.PIN,
                if (isPinned) "Remove pin from start page" else "Pin to start page",
                isPinned, onPinToggle,
            ),
            MenuItem(BrowserMenuRows.READ_ARTICLE, "Read this article", false, onReadArticle),
            MenuItem(BrowserMenuRows.MEDIA, "Media on this page", false, onMediaScan),
            MenuItem(BrowserMenuRows.FIND_IN_PAGE, "Find in page", false, onFindInPage),
            // G7: the label flips with the live per-tab state —
            // "Mobile site" while desktop mode is on for this tab.
            MenuItem(
                BrowserMenuRows.DESKTOP_SITE,
                if (desktopMode) "Mobile site" else "Desktop site",
                desktopMode, onToggleDesktop,
            ),
        ).filter { shows(it.id, hasPage) },
        "Library" to listOf(
            MenuItem(BrowserMenuRows.BOOKMARKS, "Bookmarks", false, onOpenBookmarks),
            MenuItem(BrowserMenuRows.HISTORY, "History", false, onOpenHistory),
        ).filter { shows(it.id) },
        "Settings" to listOf(
            MenuItem(BrowserMenuRows.SETTINGS, "Settings", false, onOpenSettings),
        ).filter { shows(it.id) },
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)).clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClose,
            ),
        )
        GsSheetSurface(
            // Same nav-bar clearance as the settings sheet: 80dp
            // bottom padding, harmless when the bar is hidden.
            modifier = Modifier.fillMaxWidth().padding(
                start = 16.dp, end = 16.dp, top = 16.dp, bottom = 80.dp,
            ),
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Browser menu", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    GsIconButton(Icons.Filled.Close, "Close menu", onClose, size = 56.dp, iconSize = 28.dp)
                }
                for ((title, rows) in sections) {
                    if (rows.isEmpty()) continue
                    MenuSection(title)
                    for (item in rows) {
                        MenuRow(item.label, item.checked, item.onClick)
                    }
                }
                // The Customize row is never hideable, so the menu
                // always has a way back to the editor.
                MenuRow("Customize menu", false, onCustomize)
            }
        }
    }
}

/**
 * The "Customize menu" editor (r185, Mariner's editor minus its
 * move up/down — the plan's scope is show/hide + reset): every menu
 * row, in menu order, each with a show/hide switch. Closing the
 * editor returns to the menu, not the browser. Reset restores the
 * default visibility (everything shows).
 */
@Composable
fun BrowserMenuCustomizeSheet(
    hiddenIds: Set<String>,
    onToggleVisible: (String, Boolean) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)).clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClose,
            ),
        )
        GsSheetSurface(
            modifier = Modifier.fillMaxWidth().padding(
                start = 16.dp, end = 16.dp, top = 16.dp, bottom = 80.dp,
            ),
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Customize menu", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    GsIconButton(Icons.Filled.Close, "Close customize menu", onClose, size = 56.dp, iconSize = 28.dp)
                }
                Text(
                    "Choose which rows the browser menu shows. The Customize menu row itself always stays.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for ((id, label) in BrowserMenuRows.ALL) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = BrowserMenuRows.isVisible(id, hiddenIds),
                            onCheckedChange = { checked -> onToggleVisible(id, checked) },
                        )
                    }
                }
                GsButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                    Text("Reset to defaults")
                }
            }
        }
    }
}
