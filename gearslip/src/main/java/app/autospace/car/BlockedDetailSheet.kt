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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.autospace.car.theme.GsSheetSurface

/**
 * True when the Blocked history has nothing to show: no days at all,
 * or every day all-zero (r198, adblock A3) — BlockedHistory.snapshot()
 * always carries today's row, so an empty check alone can never show
 * the empty state on a fresh install. Pure, so the unit tests pin it.
 */
internal fun blockedHistoryIsEmpty(history: Map<String, List<Long>>): Boolean =
    history.isEmpty() || history.all { (_, counts) -> counts.all { it == 0L } }

/**
 * Blocked detail page (r197, adblock A2) — the Blocked page behind the
 * "Blocked..." row in the settings menu's Ads & trackers section. Same
 * in-composition overlay style as the lists sheet (no real Dialog window
 * on this virtual display): a scrim that closes on tap, a kit bottom
 * sheet, the whole sheet scrolling.
 *
 * Three sections, Mariner's Blocked screen shape with this tab's counts
 * and recent hosts on top: "This tab" (this tab's counts by category,
 * plus the most recent blocked hosts with their category), "Blocked
 * this session" (session totals by category), and "History" (per-day
 * rows, newest first, over the last 30 days). Private-tab blocks are
 * session-local by design — they count here and in the session totals,
 * but never appear in the history (FilterEngine only records a day
 * row for non-private blocks).
 */
@Composable
fun BlockedDetailSheet(
    tabId: Int,
    onClose: () -> Unit,
) {
    val tabCounts by FilterLists.blockedPerTabCategories.collectAsState()
    val sessionCounts by FilterLists.blockedByCategory.collectAsState()
    val recentByTab by FilterLists.blockedRecent.collectAsState()
    val history by FilterLists.blockedHistory.collectAsState()
    val tab = tabCounts[tabId] ?: listOf(0L, 0L, 0L)
    val recent = recentByTab[tabId] ?: emptyList()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClose,
            ),
        )
        GsSheetSurface(Modifier.fillMaxWidth().padding(
            start = 16.dp, end = 16.dp, top = 16.dp, bottom = 80.dp,
        )) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(
                    "Blocked",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "This tab",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 16.dp),
                )
                BlockedCountRow("Ads", tab[0])
                BlockedCountRow("Trackers", tab[1])
                BlockedCountRow("Other", tab[2])
                BlockedCountRow("Total", tab[0] + tab[1] + tab[2])
                if (recent.isNotEmpty()) {
                    Text(
                        "Recently blocked",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    for ((host, category) in recent) {
                        Text(
                            "$host · ${FilterEngine.blockedCategoryLabel(category)}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                Text(
                    "Blocked this session",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 16.dp),
                )
                BlockedCountRow("Ads", sessionCounts[0])
                BlockedCountRow("Trackers", sessionCounts[1])
                BlockedCountRow("Other", sessionCounts[2])
                BlockedCountRow(
                    "Total",
                    sessionCounts[0] + sessionCounts[1] + sessionCounts[2],
                )
                Text(
                    "History",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 16.dp),
                )
                if (blockedHistoryIsEmpty(history)) {
                    Text(
                        "No previous days yet",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    for ((day, counts) in history) {
                        Text(
                            "$day — ${counts[0]} ads · ${counts[1]} trackers · ${counts[2]} other",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                Text(
                    "Close",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(top = 24.dp, bottom = 8.dp)
                        .clickable { onClose() },
                )
            }
        }
    }
}

/** One label/value row in the Blocked sheet. */
@Composable
private fun BlockedCountRow(label: String, count: Long) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(count.toString(), style = MaterialTheme.typography.bodyMedium)
    }
}
