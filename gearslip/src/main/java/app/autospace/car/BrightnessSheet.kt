package app.autospace.car

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.autospace.car.theme.GsButton
import app.autospace.car.theme.GsIconButton
import app.autospace.car.theme.GsSeekBar
import app.autospace.car.theme.GsSheetSurface
import app.autospace.car.theme.GsTone
import kotlin.math.roundToInt

/** Whether the brightness sheet is open; flipped from the nav bar and the dashboard. */
object BrightnessSheetVisibility {
    val state = mutableStateOf(false)
    fun open() { state.value = true }
    fun close() { state.value = false }
}

/**
 * The brightness sheet: one tap from the nav bar or the dashboard to a large slider.
 * The slider dims the whole car screen through [CarUi]'s overlay — host UI and templated
 * apps alike, since it sits above all of them — and it never blocks touches or audio:
 * the overlay itself takes no input, and this sheet is the only thing that does.
 * The sheet also holds the theme and Night shortcuts, so a driver reaching for
 * brightness at night is not sent three screens deep for the theme. Mirror dimming
 * is out of scope: the mirror replaces the Compose surface, so this overlay cannot
 * dim it — mirror brightness stays with the phone and the head unit.
 *
 * Phase 7: the sheet is the kit sheet (solid surfaceContainerLow, 28dp corners),
 * its content scrolls, and a visible Close (X) sits at the top beside the title
 * — the old bottom-only Done could be pushed off-screen on a 480px car screen
 * (Brightness + Theme rows + Night rows + Done), and the scrim-tap close was
 * not discoverable. Phase 10: Done moves INTO that top header row (Title,
 * Done, Close) and the header is OUTSIDE the scroll column, so both
 * controls are always visible on any screen height — the audit re-check
 * found Done still needed a scroll to the bottom on 480px, since the
 * sheet caps at 440dp against a 416dp content area (480 minus the nav
 * bar) and the Close scrolls away with the content. The content is
 * still capped at 440dp and still scrolls; only the header is sticky.
 */
@Composable
fun BrightnessSheet() {
    val brightness by CarSettings.brightness.collectAsState()
    val theme by CarSettings.effectiveAppTheme.collectAsState()
    val night by CarSettings.nightMode.collectAsState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f))
                .pointerInput(Unit) { detectTapGestures { BrightnessSheetVisibility.close() } },
        )
        GsSheetSurface(
            modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 440.dp),
        ) {
            // Sticky header: Title + Done + Close, outside the scroll
            // column, so both actions are reachable without scrolling.
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Brightness", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                GsButton(onClick = BrightnessSheetVisibility::close) { Text("Done") }
                // Car-sized close control, always at the top of the sheet.
                GsIconButton(
                    Icons.Filled.Close, "Close brightness",
                    BrightnessSheetVisibility::close, size = 56.dp, iconSize = 28.dp,
                )
            }
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GsSeekBar(fraction = brightness, onSeek = CarSettings::setBrightness)
                Text(
                    "${(brightness * 100).roundToInt()}% — 100% is the head unit's own maximum; " +
                        "this slider dims only and cannot brighten past the backlight.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Color scheme", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeChoice("Follow system", theme == AppTheme.FOLLOW_SYSTEM, Modifier.weight(1f)) { CarSettings.setAppTheme(AppTheme.FOLLOW_SYSTEM) }
                    ThemeChoice("Light", theme == AppTheme.LIGHT, Modifier.weight(1f)) { CarSettings.setAppTheme(AppTheme.LIGHT) }
                    ThemeChoice("Dark", theme == AppTheme.DARK, Modifier.weight(1f)) { CarSettings.setAppTheme(AppTheme.DARK) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeChoice("Material You", theme == AppTheme.MATERIAL_YOU, Modifier.weight(1f)) { CarSettings.setAppTheme(AppTheme.MATERIAL_YOU) }
                    ThemeChoice("Material You Light", theme == AppTheme.MATERIAL_YOU_LIGHT, Modifier.weight(1f)) { CarSettings.setAppTheme(AppTheme.MATERIAL_YOU_LIGHT) }
                    ThemeChoice("Material You Dark", theme == AppTheme.MATERIAL_YOU_DARK, Modifier.weight(1f)) { CarSettings.setAppTheme(AppTheme.MATERIAL_YOU_DARK) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeChoice("Night", theme == AppTheme.NIGHT, Modifier.weight(1f)) { CarSettings.setAppTheme(AppTheme.NIGHT) }
                }
                Text("Night mode (maps)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeChoice("Auto", night == NightMode.AUTO, Modifier.weight(1f)) { CarSettings.setNightMode(NightMode.AUTO) }
                    ThemeChoice("Day", night == NightMode.DAY, Modifier.weight(1f)) { CarSettings.setNightMode(NightMode.DAY) }
                    ThemeChoice("Night", night == NightMode.NIGHT, Modifier.weight(1f)) { CarSettings.setNightMode(NightMode.NIGHT) }
                }
            }
        }
    }
}

@Composable
private fun ThemeChoice(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    GsButton(onClick = onClick, tone = if (selected) GsTone.Primary else GsTone.Neutral, modifier = modifier) {
        Text(label)
    }
}
