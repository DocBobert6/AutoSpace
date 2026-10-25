package app.autospace.car

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
 * not discoverable. Done is kept at the bottom. The content is capped at
 * 440dp high so a close control is always reachable on short car screens
 * (checked against the 800x480 car-preview size in code, not a car test).
 */
@Composable
fun BrightnessSheet() {
    val brightness by CarSettings.brightness.collectAsState()
    val theme by CarSettings.appTheme.collectAsState()
    val night by CarSettings.nightMode.collectAsState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f))
                .pointerInput(Unit) { detectTapGestures { BrightnessSheetVisibility.close() } },
        )
        GsSheetSurface(
            modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 440.dp),
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
                    Text("Brightness", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    // Car-sized close control, always at the top of the sheet.
                    GsIconButton(
                        Icons.Filled.Close, "Close brightness",
                        BrightnessSheetVisibility::close, size = 56.dp, iconSize = 28.dp,
                    )
                }
                GsSeekBar(fraction = brightness, onSeek = CarSettings::setBrightness)
                Text(
                    "${(brightness * 100).roundToInt()}% — 100% is the head unit's own maximum; " +
                        "this slider dims only and cannot brighten past the backlight.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Theme", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                Text("Night mode (maps)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeChoice("Auto", night == NightMode.AUTO, Modifier.weight(1f)) { CarSettings.setNightMode(NightMode.AUTO) }
                    ThemeChoice("Day", night == NightMode.DAY, Modifier.weight(1f)) { CarSettings.setNightMode(NightMode.DAY) }
                    ThemeChoice("Night", night == NightMode.NIGHT, Modifier.weight(1f)) { CarSettings.setNightMode(NightMode.NIGHT) }
                }
                Spacer(Modifier.height(4.dp))
                GsButton(onClick = BrightnessSheetVisibility::close, modifier = Modifier.fillMaxWidth()) {
                    Text("Done")
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
