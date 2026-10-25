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

/** One playable URL the finder saw on the current page (Phase 3.2). */
data class FoundMedia(val url: String, val title: String? = null) {
    val kindLabel: String get() = VideoUrl.labelFor(url)
}

/**
 * The media-finder sheet (Phase 3.2): "Media on this page", listed only when the
 * finder actually saw playable media. An in-composition overlay, NOT a Dialog:
 * a real Dialog window crashes on this virtual display (see PhoneDialerScreen's
 * ConfirmCallOverlay comment), so every popup here is drawn in-composition.
 * All chrome uses Material You colours and Gs controls, so the sheet follows
 * the app theme exactly like the rest of AutoSpace (Phase 3.0).
 */
@Composable
fun MediaFinderSheet(items: List<FoundMedia>, onOpen: (FoundMedia) -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClose,
            ),
        )
        // Phase 7 kit sheet: solid surfaceContainerLow, 28dp corners.
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Media on this page",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (items.isEmpty()) {
                    Text(
                        "No playable media found on this page yet. Playable means a direct " +
                            "video/audio file or an HLS/DASH stream the page exposes as a URL — " +
                            "DRM-locked streams and YouTube pages are never listed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(items, key = { it.url }) { item ->
                            GsButton(
                                onClick = { onOpen(item) },
                                tone = GsTone.Neutral,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        item.kindLabel,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Column(Modifier.weight(1f)) {
                                        item.title?.let {
                                            Text(
                                                it,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Text(
                                            item.url,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
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
