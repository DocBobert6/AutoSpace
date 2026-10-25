package app.autospace.car


import app.autospace.car.theme.*
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.autospace.car.theme.GsColors
import app.autospace.car.theme.GsIconBox
import app.autospace.car.theme.GsThemes
import app.autospace.AppSettings
import app.autospace.ui.CertSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings that make sense while sitting in the car. The web page URL is edited here, with the
 * car's own on-screen keyboard ([CarKeyboard]); certificate management still needs a real
 * keyboard and file picker, so it stays on the phone's Settings screen.
 */
@Composable
fun CarSettingsScreen() {
    val scale by CarSettings.scale.collectAsState()
    val iconSize by CarSettings.iconSize.collectAsState()
    val tileSize by CarSettings.tileSize.collectAsState()
    val darkType by CarSettings.darkModeType.collectAsState()
    val open by CarSettings.openOnConnect.collectAsState()
    val night by CarSettings.nightMode.collectAsState()
    val units by CarSettings.units.collectAsState()
    val whatsNew by CarSettings.whatsNewAfterUpdates.collectAsState()
    val pipeAudio by CarSettings.pipeAudio.collectAsState()
    val speakDirections by CarSettings.speakDirections.collectAsState()
    val bluetoothWarning by CarSettings.bluetoothWarning.collectAsState()
    val locationWarning by CarSettings.locationWarning.collectAsState()
    val autoplay by CarSettings.autoplay.collectAsState()
    val playerTitle by CarSettings.playerTitle.collectAsState()
    val theme by CarSettings.appTheme.collectAsState()
    val uiTheme by CarSettings.uiTheme.collectAsState()
    val experimentalFeatures by CarSettings.experimentalFeaturesEnabled.collectAsState()
    val carSensors by CarSettings.carSensorsEnabled.collectAsState()
    val systemSpeech by CarSettings.systemSpeech.collectAsState()
    val phoneMicForVoice by CarSettings.phoneMicForVoice.collectAsState()
    val playerSkipSec by CarSettings.playerSkipSec.collectAsState()
    val display by CarEnvironment.display.collectAsState()
    val vehicle by CarEnvironment.vehicle.collectAsState()
    val mirrorFit by CarSettings.mirrorFit.collectAsState()
    val context = LocalContext.current
    val cert by produceState<CertSummary?>(null) {
        value = withContext(Dispatchers.Default) { CertSummary.read(context) }
    }

    // Phase 7 grouped-kit page: bright cards (surfaceBright) on the soft tinted
    // backdrop (surfaceContainerLow) — full-width cards for a landscape car
    // screen, vertical scrolling, controls kept at car size. In light mode this
    // also fixes white-on-white: white cards on the #F2F2F7 tint, not tinted
    // cards on a white page.
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader("Settings")

        ChoiceRow(
            "Open on connect",
            listOf(CarSettings.HOME to "Home") + CarApps.all.filter { it.id != "settings" }.map { it.id to it.label },
            open,
        ) { CarSettings.setOpenOnConnect(it) }

        ChoiceRow(
            "Size",
            listOf(1f to "Normal", 1.25f to "Large", 1.5f to "Extra large"),
            scale,
        ) { CarSettings.setScale(it) }

        // Icon size (Phase 6, corrected 2026-10-24 22:10: 0%-100%, superseding
        // the first 20%-100% ask). Finer than the Size presets above, which
        // stay exactly as they are. 0% hides the icon; the tile, its label
        // and its tap area stay, so a tile is never lost by shrinking it.
        GsGroupedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Icon size", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("0%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    GsSeekBar(
                        fraction = (iconSize),
                        onSeek = { CarSettings.setIconSize(it) },
                        modifier = Modifier.weight(1f),
                    )
                    Text("100%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "${(iconSize * 100).roundToInt()}% — icons only; tiles stay large and tappable even at 0%, when the icon is hidden and only the label shows.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Tile size (Phase 7): 50%-150%, default 100% = the Phase 6 large tile
        // size. The tile graphic shrinks with it, but the tap area never drops
        // below a car target — launcher tiles floor at 88dp high, pinned tiles
        // at 72dp — and the grid reflows with size. Applies to launcher tiles
        // and to screen-sharing (PhoneApps) tiles.
        GsGroupedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tile size", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("50%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    GsSeekBar(
                        fraction = (tileSize - 0.5f),
                        onSeek = { CarSettings.setTileSize(0.5f + it) },
                        modifier = Modifier.weight(1f),
                    )
                    Text("150%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "${(tileSize * 100).roundToInt()}% — tiles and their grid; icons keep their own size above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        ChoiceRow(
            "App theme",
            listOf(
                AppTheme.FOLLOW_SYSTEM to "Follow system",
                AppTheme.LIGHT to "Light",
                AppTheme.DARK to "Dark",
                AppTheme.MATERIAL_YOU to "Material You",
                AppTheme.MATERIAL_YOU_LIGHT to "Material You Light",
                AppTheme.MATERIAL_YOU_DARK to "Material You Dark",
            ),
            theme,
        ) { CarSettings.setAppTheme(it) }
        Text(
            "Follow system is the plain neutral theme following the phone's " +
                "light/dark. Light and Dark are plain neutral themes, no " +
                "wallpaper colours. Material You follows the phone's wallpaper " +
                "colours and light/dark; Material You Light / Dark use the " +
                "phone's wallpaper colours, forced to that variant.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ChoiceRow(
            "Dark mode",
            listOf(DarkModeType.REGULAR to "Regular", DarkModeType.OLED to "OLED"),
            darkType,
        ) { CarSettings.setDarkModeType(it) }
        Text(
            "Regular dark uses dark-grey surfaces, no pure black. OLED dark " +
                "uses pure-black backgrounds with dark-grey surfaces. Applies " +
                "to plain Dark, Material You Dark, and the dark variant of " +
                "both Follow options, in every style.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ChoiceRow(
            "Theme",
            GsThemes.builtIn.map { it.id to it.name },
            uiTheme,
        ) { CarSettings.setUiTheme(it) }

        ChoiceRow(
            "Map day/night",
            listOf(NightMode.AUTO to "Auto", NightMode.DAY to "Day", NightMode.NIGHT to "Night"),
            night,
        ) { CarSettings.setNightMode(it) }

        ChoiceRow(
            "Units",
            listOf(Units.AUTO to "Auto", Units.IMPERIAL to "Imperial (mi, ft, °F)", Units.METRIC to "Metric (km, m, °C)"),
            units,
        ) { CarSettings.setUnits(it) }

        ChoiceRow(
            "Player in the nav bar",
            listOf(false to "Skip buttons", true to "Song title"),
            playerTitle,
        ) { CarSettings.setPlayerTitle(it) }

        ChoiceRow(
            "Autoplay on connect",
            listOf(false to "Off", true to "On"),
            autoplay,
        ) { CarSettings.setAutoplay(it) }

        ChoiceRow(
            "Car audio",
            listOf(false to "Bluetooth", true to "USB"),
            pipeAudio,
        ) { CarSettings.setPipeAudio(it) }
        Text(
            "USB sends the phone's sound through AutoSpace. Android asks to share your screen each " +
                "drive, and a few apps block it. Bluetooth plays as the phone normally would, and " +
                "Android never asks.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!pipeAudio) {
            ChoiceRow(
                "Warn when Bluetooth isn't connected",
                listOf(false to "Off", true to "On"),
                bluetoothWarning,
            ) { CarSettings.setBluetoothWarning(it) }
        }
        ChoiceRow(
            "Warn when location is off",
            listOf(false to "Off", true to "On"),
            locationWarning,
        ) { CarSettings.setLocationWarning(it) }

        ChoiceRow(
            "Spoken directions",
            listOf(false to "Off", true to "On"),
            speakDirections,
        ) { CarSettings.setSpeakDirections(it) }
        Text(
            "AutoSpace reads each turn aloud through the car's speakers, and the music dips while it " +
                "speaks. Turn it off if your map app's own voice already plays in the car.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ExperimentalFeatureRow(
            title = "Use the phone's speech recognition",
            description = "Listens through the phone's own speech service instead of AutoSpace's. On " +
                "most phones that's Google's, which may send your voice to Google. GrapheneOS has " +
                "none, so AutoSpace keeps using its own.",
            checked = systemSpeech,
        ) { CarSettings.setSystemSpeech(it) }

        // Phone microphone for voice commands (approved 2026-10-24 22:19):
        // for a car whose built-in mic is broken. Voice commands and voice
        // replies only — this does NOT affect phone calls, which keep the
        // car's audio route exactly as before.
        ExperimentalFeatureRow(
            title = "Use phone microphone for voice commands",
            description = "Voice commands and voice replies listen through the phone's " +
                "microphone instead of the car's, even when the car has one. " +
                "Turn this on if the car's microphone doesn't work. This does " +
                "NOT affect phone calls.",
            checked = phoneMicForVoice,
        ) { CarSettings.setPhoneMicForVoice(it) }

        WebPageSetting()

        ScreenFit(vehicle)

        ResolutionSetting(vehicle, display)

        ChoiceRow(
            "Screen sharing",
            listOf(MirrorFit.FILL to "Fill (crop)", MirrorFit.FIT to "Fit (letterbox)"),
            mirrorFit,
        ) { CarSettings.setMirrorFit(it) }
        Text(
            "Fill crops the phone's edges to fill the frame; tap the ✕ in the top-right corner " +
                "to exit. Fit shows the whole phone with bars; tap a bar to exit. A portrait " +
                "phone on a landscape car crops heavily in Fill — Fit looks better there. " +
                "Applies the next time screen sharing starts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ChoiceRow(
            "Video skip seconds",
            listOf(5 to "5s", 10 to "10s", 15 to "15s", 30 to "30s", 60 to "60s"),
            playerSkipSec,
        ) { CarSettings.setPlayerSkipSec(it) }
        Text(
            "How far the video player's skip buttons jump. The player shows " +
                "the matching glyph at 5/10/30 seconds and a curved arrow " +
                "with the seconds everywhere else.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ChoiceRow(
            "Experimental features",
            listOf(false to "Hide", true to "Show"),
            experimentalFeatures,
        ) { CarSettings.setExperimentalFeaturesEnabled(it) }
        if (experimentalFeatures) {
            ExperimentalFeaturesList(carSensors)
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connection", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Info("Phone", "${Build.MANUFACTURER} ${Build.MODEL}")
            Info("Vehicle", vehicle.ifEmpty { "-" })
            Info("Video", display.ifEmpty { "-" })
            Info("Certificate", cert?.headline ?: "Checking…")
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("About", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Info("Version", app.autospace.BuildConfig.VERSION_NAME)
            Info("Build", app.autospace.BuildConfig.VERSION_CODE.toString())
            ExperimentalFeatureRow(
                "What's new after updates",
                "Show the list of changes once, the first time the car connects after an update.",
                whatsNew,
            ) { CarSettings.setWhatsNewAfterUpdates(it) }
            GsButton(onClick = CarSettings::openWhatsNew, tone = GsTone.Tonal, modifier = Modifier.height(56.dp)) {
                Text("What's new", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun <T> ChoiceRow(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    // Phase 7: every choice row is its own grouped card (surfaceBright on the
    // page's tinted backdrop), so settings read as Notscape's grouped lists.
    GsGroupedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                options.forEach { (value, label) ->
                    val on = value == selected
                    val scheme = MaterialTheme.colorScheme
                    GsIconBox(
                        onClick = { onSelect(value) },
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp),
                        colors = if (on) GsColors(scheme.primaryContainer, scheme.onPrimaryContainer)
                        else GsColors(scheme.surfaceContainerHigh, scheme.onSurface),
                        latched = on,
                    ) {
                        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

/**
 * Individually-switched experimental features, shown once the driver has opted into
 * experimental features as a whole. Each is its own row - a title, what it does, and a switch -
 * so the list reads fine whether there's one entry or several.
 */
@Composable
private fun ExperimentalFeaturesList(carSensors: Boolean) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ExperimentalFeatureRow(
            title = "Vehicle data",
            description = "Adds a Vehicle data tile: a live, raw dump of the head unit's sensor " +
                "channel. Field labels (speed, RPM, fuel...) are carried over from other " +
                "reverse-engineering projects and unverified against this car - treat them as a " +
                "guess, not a fact, until you've watched one change for yourself.",
            checked = carSensors,
        ) { CarSettings.setCarSensorsEnabled(it) }
    }
}

@Composable
private fun ExperimentalFeatureRow(
    title: String,
    description: String,
    checked: Boolean,
    warning: Boolean = false,
    onCheckedChange: (Boolean) -> Unit,
) {
    // Phase 7: grouped card, like ChoiceRow — no more flat tinted rows.
    GsGroupedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            GsSwitch(checked, onCheckedChange)
        }
    }
}

/** The URL (or raw HTML) the Browser app shows. Edited with [CarKeyboard] - no phone in hand needed. */
@Composable
private fun WebPageSetting() {
    val context = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf(AppSettings.startupUrl(context)) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Web page", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Surface(
            onClick = { editing = !editing },
            // Phase 7 kit: bright grouped-card colour, 20dp radius.
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceBright,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                url.ifEmpty { "DuckDuckGo (default) - tap to set a different page" },
                style = MaterialTheme.typography.bodyLarge,
                color = if (url.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
        Text(
            "Shown by the Browser app on the launcher. Applies on the next connection.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (editing) {
            CarKeyboard(
                url,
                onTextChange = {
                    url = it
                    AppSettings.setStartupUrl(context, it)
                },
                onSubmit = { editing = false },
                onDismiss = { editing = false },
            )
        }
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 4.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Nudges how much of the frame the UI keeps clear at the top and bottom, live, so a car's
 * display can be fitted from the driver's seat. Save writes it to this vehicle's profile.
 */
/**
 * The driver's per-vehicle resolution preference, saved into that vehicle's
 * profile (no more hand-editing vehicles.json). It takes effect on the next
 * connect, when the runner resolves it against what the head unit offers.
 */
@Composable
private fun ResolutionSetting(vehicle: String, display: String) {
    val context = LocalContext.current.applicationContext
    val known = vehicle.isNotEmpty() && vehicle != "Unknown vehicle"
    var pref by remember(vehicle) {
        mutableStateOf(app.autospace.VehicleProfiles.resolutionPreference(context, vehicle) ?: "auto")
    }
    var saved by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceRow(
            "Resolution",
            listOf("auto" to "Auto", "720p" to "720p", "1080p" to "1080p", "2k" to "2K", "4k" to "4K"),
            pref,
        ) { value ->
            if (!known) {
                saved = "No vehicle profile matched — nothing saved"
            } else if (app.autospace.VehicleProfiles.saveResolutionPreference(context, vehicle, value)) {
                pref = value
                saved = "Saved for $vehicle — applies on next connect"
            } else {
                saved = "Could not save"
            }
        }
        Text(
            "Now showing: $display. Only sizes this head unit offers are ever used — " +
                "if your pick isn't offered, the nearest size at or below it is picked " +
                "instead. 2K/4K are wired-first: on a wireless link they cap at 1080p.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        saved?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun ScreenFit(vehicle: String) {
    val insets by CarEnvironment.insets.collectAsState()
    val frame by CarEnvironment.frame.collectAsState()
    val context = LocalContext.current.applicationContext
    var saved by remember { mutableStateOf<String?>(null) }
    val known = vehicle.isNotEmpty() && vehicle != "Unknown vehicle"

    fun change(new: app.autospace.Insets) {
        saved = null
        CarEnvironment.setInsets(new)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Screen fit", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Stepper("Top", insets.top) { change(insets.copy(top = (insets.top + it).coerceAtLeast(0))) }
        Stepper("Bottom", insets.bottom) { change(insets.copy(bottom = (insets.bottom + it).coerceAtLeast(0))) }
        Text(
            "Usable height ${frame.height - insets.top - insets.bottom}px of ${frame.height}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GsButton(
            onClick = {
                val ok = app.autospace.VehicleProfiles.saveInsets(context, vehicle, insets)
                saved = if (ok) "Saved to $vehicle" else "Could not save"
            },
            enabled = known,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(if (known) "Save for this vehicle" else "No vehicle profile matched") }
        saved?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun Stepper(label: String, value: Int, onStep: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1.1f))
        StepButton("−5", Modifier.weight(1f)) { onStep(-5) }
        StepButton("−1", Modifier.weight(1f)) { onStep(-1) }
        Text(
            "${value}px",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1.3f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        StepButton("+1", Modifier.weight(1f)) { onStep(1) }
        StepButton("+5", Modifier.weight(1f)) { onStep(5) }
    }
}

@Composable
private fun StepButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    GsIconBox(
        onClick = onClick,
        modifier = modifier.height(64.dp),
        colors = GsColors(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface),
        shape = MaterialTheme.shapes.large,
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge)
    }
}

