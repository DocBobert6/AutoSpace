package app.autospace.car

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.autospace.car.theme.GsButton
import app.autospace.car.theme.GsSheetSurface
import app.autospace.car.theme.gsButtonShape

/**
 * Clear browsing data sheet (r200, adblock A5) — the "Clear browsing
 * data…" row in the settings menu's Ads & trackers section. Same
 * in-composition overlay style as the Blocked detail page (no real
 * Dialog window on this virtual display): a scrim that closes on tap,
 * a kit bottom sheet, the whole sheet scrolling.
 *
 * Four checkboxes, all ON by default as in Mariner, plus a Clear
 * button that runs the shared sweep ([clearBrowsingData]) and a
 * cleared line underneath it — Mariner's clearedNote shape ("Cleared:
 * history, cookies, cache, site data.") — so the driver sees what
 * actually cleared without leaving the sheet. Bookmarks and downloads
 * are never touched; the sheet offers no way to clear them. There is
 * no time range here (Mariner's beta.65 added one); the approved
 * scope is the four checkboxes only.
 */
@Composable
fun ClearBrowsingDataSheet(
    onClear: (ClearSelection) -> Unit,
    onClose: () -> Unit,
) {
    var historyOn by remember { mutableStateOf(true) }
    var cookiesOn by remember { mutableStateOf(true) }
    var cacheOn by remember { mutableStateOf(true) }
    var siteDataOn by remember { mutableStateOf(true) }
    var clearedNote by remember { mutableStateOf("") }
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
                    "Clear browsing data",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Cookies, cache and site data are cleared completely. Bookmarks and downloads are kept.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CheckRow("History", historyOn) { historyOn = it }
                CheckRow("Cookies", cookiesOn) { cookiesOn = it }
                CheckRow("Cache", cacheOn) { cacheOn = it }
                CheckRow("Site data", siteDataOn) { siteDataOn = it }
                GsButton(
                    onClick = {
                        val selection = ClearSelection(
                            history = historyOn,
                            cookies = cookiesOn,
                            cache = cacheOn,
                            siteData = siteDataOn,
                            tabs = false,
                        )
                        onClear(selection)
                        clearedNote = "Cleared: " +
                            clearBrowsingDataLabels(selection).joinToString(", ") + "."
                    },
                    enabled = historyOn || cookiesOn || cacheOn || siteDataOn,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Clear browsing data") }
                if (clearedNote.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(clearedNote)
                }
            }
        }
    }
}

/** A toggle row in the sheet, mirroring the settings menu's MenuRow look. */
@Composable
private fun CheckRow(label: String, checked: Boolean, onClick: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(
                if (checked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                gsButtonShape(RoundedCornerShape(20.dp)),
            )
            .clickable { onClick(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checked) {
            Icon(
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
        )
    }
}
