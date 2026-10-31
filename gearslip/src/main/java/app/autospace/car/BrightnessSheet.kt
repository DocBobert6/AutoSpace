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
import androidx.compose.foundation.layout.PaddingValues
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
fun BrightnessSheet(contentPadding: PaddingValues = PaddingValues()) {
    val brightness by CarSettings.brightness.collectAsState()
    val scheme by CarSettings.effectiveColorScheme.collectAsState()
    val appearanceMode by CarSettings.appearanceMode.collectAsState()
    val night by CarSettings.nightMode.collectAsState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f))
                .pointerInput(Unit) { detectTapGestures { BrightnessSheetVisibility.close() } },
        )
        GsSheetSurface(
            // r87: the rail inset comes FIRST — padding before fillMaxWidth is a
            // margin, so the sheet itself stays out of the strip (padding after it
            // would only inset the sheet's content while the surface still spanned
            // the strip and painted over the rail).
            modifier = Modifier.padding(contentPadding).fillMaxWidth().padding(16.dp).heightIn(max = 440.dp),
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
                // r152 (audit §3 F1): rows are built from colorSchemes with
                // displayName, as the settings page does — the old hardcoded
                // rows listed 7 schemes and could never select Horizon,
                // Meridian, Workshop, or Aurora Field. Chunked into rows of
                // three; a short last row is padded with spacers so the
                // grid columns stay aligned.
                for (rowSchemes in colorSchemes.chunked(3)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (rowScheme in rowSchemes) {
                            ThemeChoice(rowScheme.displayName, scheme == rowScheme, Modifier.weight(1f)) { CarSettings.setColorScheme(rowScheme) }
                        }
                        repeat(3 - rowSchemes.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Text("Appearance mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // r152 (audit §3 N7): the Light row is greyed out while
                    // a forced-dark scheme is active, since resolveDark
                    // makes Light a no-op there — reusing schemeForcesDark
                    // rather than a second hardcoded scheme list.
                    ThemeChoice("Light", appearanceMode == AppearanceMode.LIGHT, Modifier.weight(1f), enabled = !schemeForcesDark(scheme)) { CarSettings.setAppearanceMode(AppearanceMode.LIGHT) }
                    ThemeChoice("Dark", appearanceMode == AppearanceMode.DARK, Modifier.weight(1f)) { CarSettings.setAppearanceMode(AppearanceMode.DARK) }
                    ThemeChoice("System", appearanceMode == AppearanceMode.SYSTEM, Modifier.weight(1f)) { CarSettings.setAppearanceMode(AppearanceMode.SYSTEM) }
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
private fun ThemeChoice(label: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    GsButton(onClick = onClick, tone = if (selected) GsTone.Primary else GsTone.Neutral, modifier = modifier, enabled = enabled) {
        Text(label)
    }
}
