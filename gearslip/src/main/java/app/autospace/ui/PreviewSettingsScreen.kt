package app.autospace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.autospace.car.AppTheme
import app.autospace.car.DarkModeType
import app.autospace.car.TestMode
import app.autospace.notify.CarMotion

/**
 * Car preview settings (Robert, 2026-10-25): one page for
 * everything that shapes the bench preview — size, density, theme
 * override, touch markers — plus the couch-testing switches
 * (simulated driving, simulated car session). Reached from phone
 * Settings' "Help and tools" in one tap, and from the preview
 * screen's own "Preview settings" button.
 *
 * The one-line promise at the top is the contract: nothing here
 * touches the real car UI. Preview rendering reads PreviewSettings;
 * the real session reads CarSettings; the two never meet except
 * where a picker sits on "use the car's setting".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewSettingsScreen(onBack: () -> Unit, onOpenCarPreview: () -> Unit) {
    val context = LocalContext.current
    remember { PreviewSettings.ensure(context) }
    val size by PreviewSettings.size.collectAsState()
    val density by PreviewSettings.densityDpi.collectAsState()
    val style by PreviewSettings.styleId.collectAsState()
    val colours by PreviewSettings.colours.collectAsState()
    val darkType by PreviewSettings.darkType.collectAsState()
    val touch by PreviewSettings.touchIndicators.collectAsState()
    val simMode by CarMotion.simMode.collectAsState()
    val testMode by TestMode.active.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Car preview settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Spacer(Modifier.height(0.dp))
            Text(
                "These settings affect only the car preview, never the real car UI.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SettingsSection("Preview size") {
                PreviewSettings.SIZES.forEach { option ->
                    RadioRow(
                        title = option.toString(),
                        selected = size == option,
                    ) { PreviewSettings.setSize(context, option) }
                }
            }

            SettingsSection("Preview density") {
                PreviewSettings.DPIS.forEach { option ->
                    RadioRow(
                        title = "$option dpi",
                        selected = density == option,
                    ) { PreviewSettings.setDensity(context, option) }
                }
            }

            SettingsSection(
                "Preview style",
                footer = "“Use car setting” renders the preview exactly as the real car UI looks.",
            ) {
                RadioRow("Use car setting", style == null) { PreviewSettings.setStyle(context, null) }
                RadioRow("Frost", style == "frost") { PreviewSettings.setStyle(context, "frost") }
                RadioRow("Flat", style == "flat") { PreviewSettings.setStyle(context, "flat") }
                RadioRow("Tactile", style == "tactile") { PreviewSettings.setStyle(context, "tactile") }
                RadioRow("Edge", style == "edge") { PreviewSettings.setStyle(context, "edge") }
                RadioRow("Pitlane", style == "pitlane") { PreviewSettings.setStyle(context, "pitlane") }
            }

            SettingsSection("Preview colors") {
                RadioRow("Use car setting", colours == null) { PreviewSettings.setColours(context, null) }
                RadioRow("Follow system", colours == AppTheme.FOLLOW_SYSTEM) {
                    PreviewSettings.setColours(context, AppTheme.FOLLOW_SYSTEM)
                }
                RadioRow("Plain Light", colours == AppTheme.LIGHT) {
                    PreviewSettings.setColours(context, AppTheme.LIGHT)
                }
                RadioRow("Plain Dark", colours == AppTheme.DARK) {
                    PreviewSettings.setColours(context, AppTheme.DARK)
                }
                RadioRow("Material You", colours == AppTheme.MATERIAL_YOU) {
                    PreviewSettings.setColours(context, AppTheme.MATERIAL_YOU)
                }
                RadioRow("Material You Light", colours == AppTheme.MATERIAL_YOU_LIGHT) {
                    PreviewSettings.setColours(context, AppTheme.MATERIAL_YOU_LIGHT)
                }
                RadioRow("Material You Dark", colours == AppTheme.MATERIAL_YOU_DARK) {
                    PreviewSettings.setColours(context, AppTheme.MATERIAL_YOU_DARK)
                }
                RadioRow("Night", colours == AppTheme.NIGHT) {
                    PreviewSettings.setColours(context, AppTheme.NIGHT)
                }
                RadioRow("Pitlane", colours == AppTheme.PITLANE) {
                    PreviewSettings.setColours(context, AppTheme.PITLANE)
                }
                RadioRow("Circuit", colours == AppTheme.CIRCUIT) {
                    PreviewSettings.setColours(context, AppTheme.CIRCUIT)
                }
                RadioRow("Heritage", colours == AppTheme.HERITAGE) {
                    PreviewSettings.setColours(context, AppTheme.HERITAGE)
                }
            }

            SettingsSection("Preview dark type") {
                RadioRow("Use car setting", darkType == null) { PreviewSettings.setDarkType(context, null) }
                RadioRow("Regular", darkType == DarkModeType.REGULAR) {
                    PreviewSettings.setDarkType(context, DarkModeType.REGULAR)
                }
                RadioRow("Pitch", darkType == DarkModeType.OLED) {
                    PreviewSettings.setDarkType(context, DarkModeType.OLED)
                }
            }

            SettingsSection(
                "Touch indicators",
                footer = "A fading ring where each new finger lands in the preview. Off by default.",
            ) {
                SettingsRow(
                    title = "Show touch indicators",
                    trailing = {
                        Switch(checked = touch, onCheckedChange = { PreviewSettings.setTouchIndicators(context, it) })
                    },
                )
            }

            SettingsSection(
                "Simulated driving",
                footer = "TEST ONLY: forces the passenger gates and the video " +
                    "audio-only branch to behave as if the car were moving " +
                    "or parked, so they can be tested from the couch. It " +
                    "never overrides real GPS during a real car session — " +
                    "the moment a real accessory connects, the override " +
                    "resets to Automatic and the real signal rules.",
            ) {
                RadioRow("Automatic (real GPS)", simMode == CarMotion.SimMode.AUTO) {
                    CarMotion.setSimMode(CarMotion.SimMode.AUTO)
                }
                RadioRow("Moving", simMode == CarMotion.SimMode.MOVING) {
                    CarMotion.setSimMode(CarMotion.SimMode.MOVING)
                }
                RadioRow("Parked", simMode == CarMotion.SimMode.PARKED) {
                    CarMotion.setSimMode(CarMotion.SimMode.PARKED)
                }
            }

            SettingsSection(
                "Simulate car session",
                footer = "TEST MODE: fakes a car session on the phone alone, " +
                    "so the hotword loop and the assistant run without a " +
                    "head unit. While it is on, a TEST MODE banner stays " +
                    "visible on every phone screen, it switches itself off " +
                    "the moment a real accessory connects, it never " +
                    "persists across launches, and its log lines say test " +
                    "mode. Open the car preview to see the assistant — " +
                    "without it, listening still runs and replies still " +
                    "speak, but there is no screen to show them on. " +
                    "Guidance turns still need a real map app's route; " +
                    "test mode enables the plumbing, not a route.",
            ) {
                SettingsRow(
                    title = "Simulate car session (TEST MODE)",
                    trailing = {
                        Switch(
                            checked = testMode,
                            onCheckedChange = { on ->
                                if (on) TestMode.activate(context) else TestMode.deactivate()
                            },
                        )
                    },
                )
            }

            Button(onClick = onOpenCarPreview, modifier = Modifier.fillMaxWidth()) {
                Text("Open car preview")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** One radio row: the whole row is the touch target, the button at the end shows the state. */
@Composable
private fun RadioRow(title: String, selected: Boolean, onSelect: () -> Unit) {
    SettingsRow(
        title = title,
        modifier = Modifier.selectable(selected, role = Role.RadioButton, onClick = onSelect),
        trailing = { RadioButton(selected = selected, onClick = null) },
    )
}
