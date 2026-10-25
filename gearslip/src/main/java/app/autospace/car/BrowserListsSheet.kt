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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.autospace.car.theme.GsButton
import app.autospace.car.theme.GsTone

/**
 * Bookmarks + history picker (Phase 3.4), in the same in-composition overlay
 * style as the media-finder sheet (no real Dialog window on this virtual
 * display). Tapping an entry loads it; bookmarking the current page and
 * clearing history live here too, so the bar stays to one row.
 */
@Composable
fun BrowserListsSheet(
    bookmarks: List<String>,
    history: List<String>,
    currentUrl: String,
    isBookmarked: Boolean,
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
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Saved & history",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                GsButton(onClick = onBookmarkToggle, modifier = Modifier.fillMaxWidth()) {
                    Text(if (isBookmarked) "Remove this page from bookmarks" else "Bookmark this page")
                }
                Text("Bookmarks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (bookmarks.isEmpty()) {
                    Text(
                        "No bookmarks yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(bookmarks, key = { "b:$it" }) { url ->
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("History", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    GsButton(onClick = onClearHistory, tone = GsTone.Neutral) { Text("Clear") }
                }
                if (history.isEmpty()) {
                    Text(
                        "No history yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(history, key = { "h:$it" }) { url ->
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
