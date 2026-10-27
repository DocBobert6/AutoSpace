package app.autospace.car


import app.autospace.car.theme.*
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import app.autospace.speech.VoiceModels
import app.autospace.speech.VoiceModels.Engine
import app.autospace.speech.VoiceModels.State
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.autospace.speech.VoiceEngine
import app.autospace.speech.VoiceTrace
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
    // Category pages (2026-10-25, r18): the old single long scrolling list
    // is now a top-level category menu; each row opens that category's own
    // page. Nothing moved value, key, default or behavior — every row below
    // is the same composable the long list used, only grouped. Back: the
    // breadcrumb arrow and the head-unit BACK key both go through
    // [CarNavigator.back], which an open page claims first via
    // [CarNavigator.backInterceptor] (same pattern as WebApp), so BACK goes
    // page -> menu -> exit, never trapping the user on a page. One extra
    // tap per setting (menu -> page) is the intended cost of shorter pages.
    var page by remember { mutableStateOf<SettingsCategory?>(null) }
    val navigator = LocalCarNavigator.current
    DisposableEffect(page) {
        navigator.backInterceptor = if (page != null) {
            { page = null; true }
        } else {
            null
        }
        onDispose { navigator.backInterceptor = null }
    }

    // Phase 7 grouped-kit page: bright cards (surfaceBright) on the soft tinted
    // backdrop (surfaceContainerLow) — full-width cards for a landscape car
    // screen, vertical scrolling, controls kept at car size. In light mode this
    // also fixes white-on-white: white cards on the #F2F2F7 tint, not tinted
    // cards on a white page.
    // Phase 8 audit fix: the header is sticky — it sits outside the
    // scroll column, so the car-sized back target stays visible while
    // the sections scroll away underneath it.
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        ScreenHeader(page?.title ?: "Settings", Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 0.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (page == null) {
                SettingsCategory.entries.forEach { category ->
                    GsGroupedCard(
                        Modifier.fillMaxWidth().clickable { page = category },
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(category.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    category.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                when (page) {
                    SettingsCategory.APPEARANCE -> AppearancePage()
                    SettingsCategory.AUDIO_AND_CALLS -> AudioAndCallsPage()
                    SettingsCategory.VOICE -> VoicePage()
                    SettingsCategory.BROWSER_AND_VIDEO -> BrowserAndVideoPage()
                    SettingsCategory.SCREEN_AND_SHARING -> ScreenAndSharingPage()
                    SettingsCategory.CONNECTION -> ConnectionPage()
                    SettingsCategory.ABOUT_AND_EXPERIMENTAL -> AboutAndExperimentalPage()
                    null -> Unit
                }
            }
        }
    }
}

/** One row of the top-level Settings menu: plain name + one-line description. */
private enum class SettingsCategory(val title: String, val description: String) {
    APPEARANCE(
        "Appearance",
        "Sizes, color scheme, dark mode, Auto night, button style, map day/night, units",
    ),
    AUDIO_AND_CALLS(
        "Audio & calls",
        "Car audio, autoplay, Bluetooth warning, calls on the phone speaker",
    ),
    VOICE(
        "Voice",
        "Spoken directions, spoken voices, hotword, phone microphone, voice trace, voice shortcuts",
    ),
    BROWSER_AND_VIDEO(
        "Browser & video",
        "Browser start page, video skip seconds, subtitles",
    ),
    SCREEN_AND_SHARING(
        "Screen & sharing",
        "Screen fit, resolution, screen sharing",
    ),
    CONNECTION(
        "Connection",
        "What opens on connect, location warning, phone, vehicle and certificate",
    ),
    ABOUT_AND_EXPERIMENTAL(
        "About & experimental",
        "Version, what's new, experimental features",
    ),
}

@Composable
private fun AppearancePage() {
    val scale by CarSettings.scale.collectAsState()
    val iconSize by CarSettings.iconSize.collectAsState()
    val tileSize by CarSettings.tileSize.collectAsState()
    val scheme by CarSettings.colorScheme.collectAsState()
    val appearanceMode by CarSettings.appearanceMode.collectAsState()
    val darkType by CarSettings.darkModeType.collectAsState()
    val uiTheme by CarSettings.uiTheme.collectAsState()
    val night by CarSettings.nightMode.collectAsState()
    val units by CarSettings.units.collectAsState()

    // UI size (Phase 10, correcting Phase 9's display): the slider
    // itself runs 0–100, ship default 50 (mid). 0 is a CAPPED
    // minimum size, not zero size. Mapping, unchanged underneath:
    // slider p 0–100 -> scale 0.5 + (p/100)*1.0, so p=0 -> scale
    // 0.5, p=50 -> scale 1.0 (ship default, the old Normal), p=100
    // -> scale 1.5 (the old Extra large). The pill shows p%, and
    // the persisted key is still car_scale (0.5–1.5), so an old
    // stored scale migrates by p=(scale-0.5)*100, clamped 0–100.
    // Phase 9 showed the scale as 50%–150%, which read as a second,
    // different scale next to the 0–100 icon slider.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("UI size", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            androidx.compose.foundation.layout.Box(
                Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, gsButtonShape(androidx.compose.foundation.shape.RoundedCornerShape(50)))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) { Text("${((scale - 0.5f) * 100f).roundToInt().coerceIn(0, 100)}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        }
        GsGroupedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GsSeekBar(
                    fraction = ((scale - 0.5f) / 1.0f).coerceIn(0f, 1f),
                    onSeek = { CarSettings.setScale(0.5f + it * 1.0f) },
                )
                Text(
                    "Scales the whole car UI, 0–100; 50 is the ship default. 0 is the smallest size, not zero size — nothing vanishes at 0.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // Icon size (Phase 6, corrected 2026-10-24 22:10: 0%-100%, superseding
    // the first 20%-100% ask). Finer than the Size presets above, which
    // stay exactly as they are. 0% hides the icon; the tile, its label
    // and its tap area stay, so a tile is never lost by shrinking it.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Icon size", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            androidx.compose.foundation.layout.Box(
                Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, gsButtonShape(androidx.compose.foundation.shape.RoundedCornerShape(50)))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) { Text("${(iconSize * 100).roundToInt()}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        }
        GsGroupedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GsSeekBar(
                    fraction = (iconSize),
                    onSeek = { CarSettings.setIconSize(it) },
                )
                Text(
                    "Icons only; tiles stay large and tappable even at 0%, when the icon is hidden and only the label shows.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // Tile size (Phase 7): 50%-150%, default 100% = the Phase 6 large tile
    // size. The tile graphic shrinks with it, but the tap area never drops
    // below a car target — launcher tiles floor at 88dp high, pinned tiles
    // at 72dp — and the grid reflows with size. Applies to launcher tiles
    // and to screen-sharing (PhoneApps) tiles.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Tile size", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            androidx.compose.foundation.layout.Box(
                Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, gsButtonShape(androidx.compose.foundation.shape.RoundedCornerShape(50)))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) { Text("${(tileSize * 100).roundToInt()}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        }
        GsGroupedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GsSeekBar(
                    fraction = (tileSize - 0.5f),
                    onSeek = { CarSettings.setTileSize(0.5f + it) },
                )
                Text(
                    "Tiles and their grid, 50%–150%; icons keep their own size above.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    ChoiceRow(
        "Color scheme",
        colorSchemes.map { it to it.displayName },
        scheme,
    ) { CarSettings.setColorScheme(it) }
    Text(
        "Neutral is the plain theme, no wallpaper colors. Material You " +
            "follows the phone's wallpaper colors; its light/dark variant " +
            "follows the mode control below. Night is the dedicated " +
            "night-driving scheme: pure black, warm gray text, amber " +
            "accents, always dark. Pitlane is the stock-car scheme: " +
            "carbon black, white text, red and yellow, no blue, always " +
            "dark. Circuit is black with blue, red, and yellow accents " +
            "(white in its light variant). Heritage is deep racing green " +
            "with cream text and brass gold accents. Circuit and Heritage " +
            "follow the mode control; Auto night still switches " +
            "every scheme to Night.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    ChoiceRow(
        "Appearance mode",
        appearanceModes.map { it to it.displayName },
        appearanceMode,
    ) { CarSettings.setAppearanceMode(it) }
    Text(
        "Light and Dark force that variant for every scheme that has " +
            "one. System follows the phone's light/dark. Night and " +
            "Pitlane are always dark, so while one of them is active " +
            "the screen stays dark whatever mode is picked here; your " +
            "mode applies again when you choose another scheme." +
            if (schemeForcesDark(scheme)) " Right now ${scheme.displayName} " +
                "is active, so the screen is dark." else "",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    ChoiceRow(
        "Dark surfaces",
        listOf(DarkModeType.REGULAR to "Regular", DarkModeType.OLED to "Pitch"),
        darkType,
    ) { CarSettings.setDarkModeType(it) }
    Text(
        "Regular dark uses dark-gray surfaces, no pure black. Pitch " +
            "uses pure-black backgrounds and surfaces; panels are " +
            "separated by their edges, not by gray fills. Applies to " +
            "Neutral and Material You when dark, in every style. While Night is " +
            "active, the background is pure black no matter which " +
            "dark type is picked.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    NightSettingsSection()

    ChoiceRow(
        "Button style",
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
}

@Composable
private fun AudioAndCallsPage() {
    val autoplay by CarSettings.autoplay.collectAsState()
    val pipeAudio by CarSettings.pipeAudio.collectAsState()
    val bluetoothWarning by CarSettings.bluetoothWarning.collectAsState()
    val callSpeaker by CarSettings.callSpeaker.collectAsState()

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
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (!pipeAudio) {
        ChoiceRow(
            "Warn when Bluetooth isn't connected",
            listOf(false to "Off", true to "On"),
            bluetoothWarning,
        ) { CarSettings.setBluetoothWarning(it) }
    }

    // Call speaker (2026-10-25): calls only, off by default, next to the
    // phone-mic row it pairs with. When a call starts, audio moves to the
    // phone's speaker — so the caller hears the phone's microphone, not a
    // broken car microphone — and the previous speaker state is restored
    // when the call ends. Voice commands, assistant speech, and media are
    // untouched. The car's Bluetooth may reassert its route, which this
    // cannot override; that is a car-test result, not a setting failure.
    ExperimentalFeatureRow(
        title = "Answer calls on the phone speaker",
        description = "When a call starts, its audio goes to the phone's speaker instead " +
            "of the car's Bluetooth route, so the caller hears you through the " +
            "phone's microphone. This affects phone calls only — not voice " +
            "commands, the assistant, or music — applies from the next call, " +
            "and the speaker is restored when the call ends. If the car's " +
            "Bluetooth takes the call back, this setting cannot override it.",
        checked = callSpeaker,
    ) { CarSettings.setCallSpeaker(it) }
}

@Composable
private fun VoicePage() {
    val speakDirections by CarSettings.speakDirections.collectAsState()
    val systemSpeech by CarSettings.systemSpeech.collectAsState()
    val phoneMicForVoice by CarSettings.phoneMicForVoice.collectAsState()
    val hotword by CarSettings.hotword.collectAsState()
    val hotwordPhoneMic by CarSettings.hotwordPhoneMic.collectAsState()

    ChoiceRow(
        "Spoken directions",
        listOf(false to "Off", true to "On"),
        speakDirections,
    ) { CarSettings.setSpeakDirections(it) }
    Text(
        "AutoSpace reads each turn aloud through the car's speakers, and the music dips while it " +
            "speaks. Turn it off if your map app's own voice already plays in the car. It needs " +
            "a downloaded voice below.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    VoiceSettings()

    VoiceTraceSettings()

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

    // Hotword (2026-10-25): hands-free wake phrase, off by default. The
    // listener itself enforces the rest — car session only, suspended while
    // the assistant is busy or on a call — so this row is only the switch.
    // Hotword-only phone mic (2026-10-25): the same switch as
    // phone Settings, same state. When on, hotword listening uses
    // the phone mic even when the car has one — the workaround for
    // a broken car mic. Button-press commands, calls, and the
    // voice-microphone setting above are untouched by this switch.
    ExperimentalFeatureRow(
        title = "Listen for “Hey AutoSpace” on the phone microphone",
        description = "Hotword listening only: uses the phone's microphone even when " +
            "the car has one. Turn this on if the car's microphone doesn't work " +
            "and “Hey AutoSpace” never answers. Button-press voice commands, " +
            "calls, and everything else are unaffected.",
        checked = hotwordPhoneMic,
    ) { CarSettings.setHotwordPhoneMic(it) }

    ExperimentalFeatureRow(
        title = "Listen for “Hey AutoSpace”",
        description = "While connected to the car, AutoSpace listens for “Hey AutoSpace” " +
            "(or “Hey Gearslip”) and opens the assistant without a button press. " +
            "It needs the Kroko listening model downloaded, it stops while the " +
            "assistant is speaking, on a call, or when the car disconnects, and " +
            "nothing you say leaves the phone.",
        checked = hotword,
    ) { CarSettings.setHotword(it) }

    // Voice shortcuts (r19): read-only here — adding and editing happen on the phone,
    // where typing an address is not a car-keyboard job. This list just shows the driver
    // what already exists, in the same order the phone shows it.
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(context) { VoiceShortcuts.ensureLoaded(context) }
    val shortcuts by VoiceShortcuts.shortcuts.collectAsState()
    GsSectionHeader("Voice shortcuts")
    Text(
        "Saved on the phone, in Settings > Voice shortcuts. Say a keyword after a " +
            "navigate, open, or search phrasing — for example, “navigate to home” for a saved address.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (shortcuts.isEmpty()) {
        Text(
            "No voice shortcuts yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        shortcuts.forEach { shortcut ->
            val typeLabel = when (shortcut.type) {
                VoiceShortcutType.NAVIGATION -> "Navigation"
                VoiceShortcutType.OPEN_APP -> "Open app"
                VoiceShortcutType.BROWSER_SEARCH -> "Browser search"
            }
            val shownTarget = if (shortcut.type == VoiceShortcutType.OPEN_APP && shortcut.label.isNotBlank()) {
                shortcut.label
            } else {
                shortcut.target
            }
            Text(
                "${shortcut.keyword} → $typeLabel → $shownTarget",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }

    // AI assistant (r25): read-only here — the key is entered, tested, and removed on the
    // phone only, never on a head unit. This line just says whether it is on or off.
    androidx.compose.runtime.LaunchedEffect(context) { AiKeyStore.ensureLoaded(context) }
    val aiEnabled by AiKeyStore.enabled.collectAsState()
    val aiProvider by AiKeyStore.provider.collectAsState()
    GsSectionHeader("AI assistant")
    Text(
        // r40: read-only, as before — the provider name is shown, but no key entry,
        // toggle, or key display ever appears on a head unit.
        "AI answers: ${if (aiEnabled) "on" else "off"} — ${aiProvider.displayName}" +
            (if (aiProvider.isExperimental) " (experimental)" else "") +
            " — manage on your phone in Settings > AI assistant.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun BrowserAndVideoPage() {
    val playerSkipSec by CarSettings.playerSkipSec.collectAsState()

    WebPageSetting()

    ChoiceRow(
        "Video skip seconds",
        listOf(5 to "5s", 10 to "10s", 15 to "15s", 30 to "30s", 60 to "60s"),
        playerSkipSec,
    ) { CarSettings.setPlayerSkipSec(it) }
    Text(
        "How far the video player's skip buttons jump. The player shows " +
            "the matching glyph at 5/10/30 seconds and a curved arrow " +
            "with the seconds everywhere else.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    SubtitleSettings()
}

@Composable
private fun ScreenAndSharingPage() {
    val display by CarEnvironment.display.collectAsState()
    val vehicle by CarEnvironment.vehicle.collectAsState()
    val mirrorFit by CarSettings.mirrorFit.collectAsState()

    ScreenFit(vehicle)

    ResolutionSetting(vehicle, display)

    ChoiceRow(
        "Screen sharing",
        listOf(MirrorFit.FIT to "Fit (letterbox)", MirrorFit.FILL to "Fill (crop)"),
        mirrorFit,
    ) { CarSettings.setMirrorFit(it) }
    Text(
        "Fill crops the phone's edges to fill the frame; tap the ✕ in the top-right corner " +
            "to exit. Fit shows the whole phone with bars; tap a bar to exit. A portrait " +
            "phone on a landscape car crops heavily in Fill — Fit looks better there. " +
            "Applies the next time screen sharing starts.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ConnectionPage() {
    val context = LocalContext.current
    val open by CarSettings.openOnConnect.collectAsState()
    val locationWarning by CarSettings.locationWarning.collectAsState()
    val vehicle by CarEnvironment.vehicle.collectAsState()
    val display by CarEnvironment.display.collectAsState()
    val cert by produceState<CertSummary?>(null) {
        value = withContext(Dispatchers.Default) { CertSummary.read(context) }
    }

    ChoiceRow(
        "Open on connect",
        listOf(CarSettings.HOME to "Home") + CarApps.all.filter { it.id != "settings" }.map { it.id to it.label },
        open,
    ) { CarSettings.setOpenOnConnect(it) }

    ChoiceRow(
        "Warn when location is off",
        listOf(false to "Off", true to "On"),
        locationWarning,
    ) { CarSettings.setLocationWarning(it) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Connection", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Info("Phone", "${Build.MANUFACTURER} ${Build.MODEL}")
        Info("Vehicle", vehicle.ifEmpty { "-" })
        Info("Video", display.ifEmpty { "-" })
        Info("Certificate", cert?.headline ?: "Checking…")
    }
}

@Composable
private fun AboutAndExperimentalPage() {
    val experimentalFeatures by CarSettings.experimentalFeaturesEnabled.collectAsState()
    val carSensors by CarSettings.carSensorsEnabled.collectAsState()
    val floatingMedia by CarSettings.floatingMediaEnabled.collectAsState()
    val frostedMapChrome by CarSettings.frostedMapChromeEnabled.collectAsState()
    val meridianChrome by CarSettings.meridianChromeEnabled.collectAsState()
    val fullWidthGlow by CarSettings.fullWidthGlowEnabled.collectAsState()
    val mediaCardDock by CarSettings.mediaCardDock.collectAsState()
    val multiTurn by CarSettings.multiTurnEnabled.collectAsState()
    val sideBar by CarSettings.sideBarEnabled.collectAsState()
    val homeWidgets by CarSettings.homeWidgetsEnabled.collectAsState()
    val widgetsDismissed by CarSettings.widgetsDismissed.collectAsState()
    val hiddenWidgets by CarSettings.hiddenWidgets.collectAsState()
    val whatsNew by CarSettings.whatsNewAfterUpdates.collectAsState()

    // r53: the master switch — a control, not a feature row, so it stays
    // visible like the Hide/Show row itself (see ExperimentalMaster).
    ExperimentalFeatureRow(
        title = "All experimental features",
        description = "Turns on the whole floating look at once: floating media, home widgets, " +
            "and frosted map chrome, and shows the experimental rows below. Turning it off " +
            "turns floating media and home widgets back off; frosted chrome and the row " +
            "visibility stay as they are. Vehicle data is never touched — it stays its own " +
            "switch at the bottom of the list. Turning any one feature off makes this read off again.",
        checked = ExperimentalMaster.isOn(floatingMedia, homeWidgets),
    ) { on ->
        applyMasterWrites(if (on) ExperimentalMaster.onWrites() else ExperimentalMaster.offWrites())
    }
    ChoiceRow(
        "Experimental features",
        listOf(false to "Hide", true to "Show"),
        experimentalFeatures,
    ) { CarSettings.setExperimentalFeaturesEnabled(it) }
    if (experimentalFeatures) {
        ExperimentalFeaturesList(carSensors, floatingMedia, frostedMapChrome, homeWidgets, widgetsDismissed, hiddenWidgets, meridianChrome, fullWidthGlow, mediaCardDock, multiTurn, sideBar)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("About", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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


/**
 * The spoken voice: downloads for Kokoro and Piper, and the Kokoro voice
 * picker (American Adam by default). There are no other voice options —
 * system TTS and other engines were removed, not hidden. If neither model
 * is downloaded, assistant replies and spoken directions stay silent, and
 * this section is where the driver fixes that.
 */
/**
 * Subtitle styling for the video player (audit fix, 2026-10-25,
 * B3/F5): the player already read these five keys and honoured
 * them, but nothing wrote them, so the player always kept the
 * system caption style. Values match exactly what
 * VideoPlayerScreen.applySubtitleStyle maps; an empty value means
 * "system default" for that row, and leaving all five empty keeps
 * the system caption style untouched (the guarded fallback).
 */
@Composable
private fun SubtitleSettings() {
    var font by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(CarSettings.subtitleFont()) }
    var size by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(CarSettings.subtitleSize().ifEmpty { "100" }) }
    var color by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(CarSettings.subtitleColor()) }
    var bg by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(CarSettings.subtitleBg()) }
    var edge by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(CarSettings.subtitleEdge()) }
    GsSectionHeader("Subtitles")
    GsGroupedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "How subtitles look in the video player. Applies to the next video played; " +
                    "leaving everything on System default keeps the system's caption style.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChoiceRow("Font", listOf("" to "System default", "roboto" to "Sans", "serif" to "Serif", "mono" to "Monospace"), font) {
                font = it; CarSettings.setSubtitleFont(it)
            }
            ChoiceRow("Size", listOf("80" to "80%", "100" to "100%", "125" to "125%", "150" to "150%"), size) {
                size = it; CarSettings.setSubtitleSize(it)
            }
            ChoiceRow("Color", listOf("" to "System default", "yellow" to "Yellow", "cyan" to "Cyan", "green" to "Green", "magenta" to "Magenta", "red" to "Red", "blue" to "Blue", "black" to "Black"), color) {
                color = it; CarSettings.setSubtitleColor(it)
            }
            ChoiceRow("Background", listOf("" to "Transparent", "black50" to "Black 50%", "black75" to "Black 75%", "black" to "Black", "white75" to "White 75%"), bg) {
                bg = it; CarSettings.setSubtitleBg(it)
            }
            ChoiceRow("Edge", listOf("" to "Outline", "shadow" to "Drop shadow", "none" to "None"), edge) {
                edge = it; CarSettings.setSubtitleEdge(it)
            }
        }
    }
}

@Composable
private fun VoiceSettings() {
    val context = LocalContext.current
    remember { VoiceModels.init(context) }
    // Audit fix (2026-10-25, V6): re-validate on entering the
    // section so Settings cannot show a stale Downloaded/Failed.
    androidx.compose.runtime.LaunchedEffect(Unit) { VoiceModels.refreshStates(context) }
    val kokoroState by VoiceModels.state(Engine.KOKORO).collectAsState()
    val piperState by VoiceModels.state(Engine.PIPER).collectAsState()
    val selectedVoice by VoiceModels.selectedVoice.collectAsState()
    // Voice preview: the key of the voice currently previewing ("kokoro:<id>"
    // or "piper"), or null. Previewing never writes the selection — it only
    // plays the fixed GPS phrase in that voice through VoiceEngine.
    var previewing by remember { mutableStateOf<String?>(null) }
    val previewingNow = rememberUpdatedState(previewing)
    // Round 3: a failed preview used to end as pure silence, so the
    // row shows the recorded failure stage instead of going quiet.
    var previewError by remember { mutableStateOf<String?>(null) }
    // Leaving this section stops a preview in flight; the guard reads the
    // live value so an assistant reply playing on its own is untouched.
    DisposableEffect(Unit) {
        onDispose { if (previewingNow.value != null) VoiceEngine.stop() }
    }
    fun togglePreview(key: String, voice: VoiceModels.KokoroVoice?) {
        if (previewing == key) {
            VoiceEngine.stop()
            previewing = null
            return
        }
        previewError = null
        val started = VoiceEngine.playPreview(
            context, VoiceModels.PREVIEW_PHRASE, voice,
            onDone = { if (previewing == key) previewing = null },
            onError = { reason ->
                previewError = reason
                if (previewing == key) previewing = null
            },
        )
        // Same not-started surfacing as the phone picker: a refused
        // preview used to end as nothing at all.
        if (!started) previewError = "engine not downloaded"
        previewing = if (started) key else null
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GsSectionHeader("Voice")
        GsGroupedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "AutoSpace speaks with Kokoro-82M, entirely on this phone — no system voice, " +
                        "no other engines. Kokoro speaks first, in the voice picked below; Piper " +
                        "(Amy) speaks only when Kokoro isn't downloaded or fails. Kokoro is " +
                        "Apache-2.0 (hexgrad); Piper's voice is MIT (rhasspy/piper-voices); " +
                    "espeak-ng phonemization is GPL-3.0. " +
                        "Downloads are one-time and live in the app's own storage, not the APK.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                VoiceEngineRow(Engine.KOKORO, kokoroState, previewing = null, onPreview = null) {
                    when (kokoroState) {
                        State.Ready -> VoiceModels.delete(context, Engine.KOKORO)
                        is State.Downloading -> VoiceModels.delete(context, Engine.KOKORO)
                        else -> VoiceModels.download(context, Engine.KOKORO)
                    }
                }
                VoiceEngineRow(
                    Engine.PIPER, piperState,
                    previewing = previewing,
                    onPreview = { togglePreview("piper", null) },
                ) {
                    when (piperState) {
                        State.Ready -> VoiceModels.delete(context, Engine.PIPER)
                        is State.Downloading -> VoiceModels.delete(context, Engine.PIPER)
                        else -> VoiceModels.download(context, Engine.PIPER)
                    }
                }
                previewError?.let { reason ->
                    Text(
                        "Preview failed: $reason.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (kokoroState is State.Missing && piperState is State.Missing) {
                    Text(
                        "No voice downloaded yet: assistant replies and spoken directions will stay silent " +
                            "until Kokoro or Piper is downloaded above.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        GsSectionHeader("Kokoro voice")
        GsGroupedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Applies to Kokoro speech; Piper always speaks as Amy. A new choice takes " +
                        "effect on the next spoken prompt. Every voice previews with " +
                        "“${VoiceModels.PREVIEW_PHRASE}” without changing your choice; " +
                        "previews need Kokoro downloaded, so a missing download leaves " +
                        "its Preview button disabled.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Featured page first: four voices, then a More voices page
                // with the other 49 — same list, same selection, no removal.
                // The default (am_adam) lives on the More voices page.
                var showMore by remember { mutableStateOf(false) }
                if (!showMore) {
                    VoiceModels.FEATURED_VOICES.forEach { voice ->
                        KokoroVoiceRow(
                            voice = voice,
                            selected = voice == selectedVoice,
                            previewing = previewing == "kokoro:${voice.id}",
                            previewEnabled = kokoroState is State.Ready,
                            onSelect = { VoiceModels.selectVoice(context, voice) },
                            onPreview = { togglePreview("kokoro:${voice.id}", voice) },
                        )
                    }
                    GsButton(
                        onClick = { showMore = true },
                        tone = GsTone.Tonal,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        Text("More voices")
                    }
                    Text(
                        "49 more Kokoro voices, including the default.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    GsButton(
                        onClick = { showMore = false },
                        tone = GsTone.Tonal,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    ) {
                        Text("Back to featured voices")
                    }
                    // Grouped by language, in the model's sid order, so the list
                    // reads as voices rather than 49 undifferentiated ids.
                    var lastLanguage: String? = null
                    VoiceModels.MORE_VOICES.forEach { voice ->
                        if (voice.language != lastLanguage) {
                            lastLanguage = voice.language
                            Text(
                                voice.language,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                        KokoroVoiceRow(
                            voice = voice,
                            selected = voice == selectedVoice,
                            previewing = previewing == "kokoro:${voice.id}",
                            previewEnabled = kokoroState is State.Ready,
                            onSelect = { VoiceModels.selectVoice(context, voice) },
                            onPreview = { togglePreview("kokoro:${voice.id}", voice) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Voice trace controls in car Settings (Robert, 2026-10-25, couch
 * testing): the same trace the phone arms for the next launch, now
 * also armable from the car — plus "Start trace now", which records
 * the chosen window from this moment, so a car session itself can
 * be captured without a fresh launch. Window choice, one-shot
 * auto-disarm, Downloads/AutoSpace export and the content filter
 * (stages/file states/error classes only — never spoken text,
 * messages, contacts, numbers, mic audio) are the same as the phone
 * surface; this section only adds the car-side entry points.
 */
@Composable
private fun VoiceTraceSettings() {
    val context = LocalContext.current
    var armed by remember { mutableStateOf(VoiceTrace.isArmed(context)) }
    var window by remember { mutableStateOf(VoiceTrace.windowMs(context)) }
    val capturing by VoiceTrace.capturing.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GsSectionHeader("Voice trace")
        GsGroupedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "A one-time recording of the voice engine's steps, for " +
                        "when a reply or preview fails. It records engine " +
                        "stages, file states and error types only — never " +
                        "spoken text, messages, contacts, numbers, or " +
                        "microphone audio — and nothing is uploaded. The " +
                        "file appears in Downloads/AutoSpace.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChoiceRow(
                    "Trace length",
                    listOf(VoiceTrace.WINDOW_MS to "45 seconds", VoiceTrace.WINDOW_MS_LONG to "90 seconds"),
                    window,
                ) { VoiceTrace.setWindowMs(context, it); window = it }
                if (capturing) {
                    Text(
                        "A trace is recording now — it ends on its own at " +
                            "${window / 1000} seconds, or the moment you stop it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    GsButton(
                        onClick = { VoiceTrace.stopAndExportNow() },
                        tone = GsTone.Tonal,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Stop trace and export now") }
                } else {
                    Text(
                        if (armed) {
                            "Trace armed — it starts on the next fresh launch " +
                                "and records ${window / 1000} seconds, then " +
                                "switches itself off."
                        } else {
                            "Arm it for the next fresh launch, or start one " +
                                "now to capture this car session."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    GsButton(
                        onClick = { VoiceTrace.startNow(context) },
                        tone = GsTone.Tonal,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Start trace now") }
                    if (armed) {
                        GsButton(
                            onClick = { VoiceTrace.disarm(context); armed = false },
                            tone = GsTone.Tonal,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Cancel trace") }
                    } else {
                        GsButton(
                            onClick = { VoiceTrace.arm(context); armed = true },
                            tone = GsTone.Tonal,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Arm launch trace") }
                    }
                }
            }
        }
    }
}

/** One Kokoro voice row: radio selection plus a Preview/Stop button. */
@Composable
private fun KokoroVoiceRow(
    voice: VoiceModels.KokoroVoice,
    selected: Boolean,
    previewing: Boolean,
    previewEnabled: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable { onSelect() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            voice.id + if (voice == VoiceModels.DEFAULT_VOICE) " (default)" else "",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        GsButton(
            onClick = onPreview,
            tone = GsTone.Tonal,
            enabled = previewEnabled,
            modifier = Modifier.height(44.dp),
        ) {
            Text(if (previewing) "Stop" else "Preview")
        }
    }
}

@Composable
private fun VoiceEngineRow(
    engine: Engine,
    state: State,
    previewing: String?,
    onPreview: (() -> Unit)?,
    onAction: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("${engine.label} · ${engine.downloadMb} MB", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    when (val s = state) {
                        State.Ready -> "Downloaded. ${engine.detail}"
                        is State.Downloading -> "Downloading, ${s.percent}%" + (if (s.detail.isNotEmpty()) " — ${s.detail}" else "")
                        is State.Failed -> "Download failed: ${s.reason}"
                        State.Missing -> engine.detail
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Piper's only preview button: its engine row is its voice row
            // (Kokoro's previews live on each voice row below instead).
            if (onPreview != null) {
                GsButton(
                    onClick = onPreview,
                    tone = GsTone.Tonal,
                    enabled = state is State.Ready,
                    modifier = Modifier.height(48.dp),
                ) {
                    Text(if (previewing == "piper") "Stop" else "Preview")
                }
            }
            GsButton(
                onClick = onAction,
                tone = GsTone.Tonal,
                modifier = Modifier.height(48.dp),
            ) {
                Text(
                    when (state) {
                        State.Ready -> "Delete"
                        is State.Downloading -> "Cancel"
                        else -> "Download"
                    },
                )
            }
        }
        (state as? State.Downloading)?.let {
            LinearProgressIndicator(progress = { it.percent / 100f }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun <T> ChoiceRow(title: String, options: List<Pair<T, String>>, selected: T, enabled: Boolean = true, onSelect: (T) -> Unit) {
    // Phase 7: every choice row is its own grouped card (surfaceBright on the
    // page's tinted backdrop), so settings read as Notscape's grouped lists.
    // Phase 8 audit fix: the section header sits ABOVE the card in a
    // neutral colour (not blue text inside it); chips wrap to rows of at
    // most 3, so 6 app-theme chips and long "Maps (mirrored)" labels are
    // never squeezed into one row; labels never wrap mid-chip (maxLines 1
    // + ellipsis). Phase 10: the selected state is the theme PRIMARY
    // (dynamic accent under Material You, system blue under plain) with
    // onPrimary text and Bold — Phase 8's tonal step
    // (surfaceContainerHighest vs High) was indistinguishable in the
    // audit video, and primary is the same accent language as the
    // slider thumb and the nav bar's active pill, so it reads as
    // "selected" in every theme including all-black OLED.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // r32: ChoiceRow titles ARE section headers, so under Pitlane they
        // render engraved like every other section header (GsSectionHeader).
        // Other styles keep the exact SemiBold title they have today.
        // (GsSectionHeader's engraved branch adds its 4dp bottom padding,
        // so the title-card gap is 12dp under Pitlane only.)
        if (gsLook().engravedHeader) GsSectionHeader(title)
        else Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        GsGroupedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                options.chunked(3).forEach { rowOptions ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        rowOptions.forEach { (value, label) ->
                            val on = value == selected
                            val scheme = MaterialTheme.colorScheme
                            GsIconBox(
                                onClick = { onSelect(value) },
                                enabled = enabled,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(64.dp),
                                colors = if (on) GsColors(scheme.primary, scheme.onPrimary)
                                else GsColors(scheme.surfaceContainerHigh, scheme.onSurface),
                                latched = on,
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    softWrap = false,
                                )
                            }
                        }
                        repeat(3 - rowOptions.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/**
 * Night: the dedicated night-driving color scheme and its Auto night
 * schedule (Robert, 2026-10-25). Auto night overrides the chosen scheme
 * at night and the chosen scheme returns in the morning; picking Night
 * yourself means Night all the time; turning Auto night off stops the
 * switching. While Night is active, the background is pure black no
 * matter which dark type is picked.
 */
@Composable
private fun NightSettingsSection() {
    val context = LocalContext.current
    val mode by CarSettings.autoNightMode.collectAsState()
    val start by CarSettings.autoNightStart.collectAsState()
    val end by CarSettings.autoNightEnd.collectAsState()
    val active by CarSettings.autoNightActive.collectAsState()
    val sun by produceState<SunResult?>(null, mode) {
        value = if (mode == AutoNightMode.SUNRISE_SUNSET) NightAuto.todaySunTimes(context) else null
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            CarSettings.refreshAutoNight()
            kotlinx.coroutines.delay(60_000)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceRow(
            "Auto night",
            listOf(
                AutoNightMode.OFF to "Off",
                AutoNightMode.SCHEDULE to "Schedule",
                AutoNightMode.SUNRISE_SUNSET to "Sunrise & sunset",
            ),
            mode,
        ) { CarSettings.setAutoNightMode(it) }
        if (mode == AutoNightMode.SCHEDULE) {
            TimeStepperRow("Night starts", start) { CarSettings.setAutoNightStart(it) }
            TimeStepperRow("Night ends", end) { CarSettings.setAutoNightEnd(it) }
            Text(
                "Night runs from the start time to the end time, past midnight if needed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (mode == AutoNightMode.SUNRISE_SUNSET) {
            when (val s = sun) {
                null -> Text(
                    "Sunrise & sunset needs location. Give AutoSpace location " +
                        "access, or use a schedule instead. Until location is " +
                        "available, Auto night stays off rather than guessing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SunResult.AlwaysDay -> Text(
                    "The sun never sets where you are right now, so Auto night stays off.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SunResult.AlwaysNight -> Text(
                    "The sun never rises where you are right now, so Auto night stays on.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is SunResult.Times -> Text(
                    "Today: sunrise ${NightAuto.formatMinutes(s.sunriseMin)}, sunset " +
                        "${NightAuto.formatMinutes(s.sunsetMin)}. Night runs from sunset to sunrise, " +
                        "computed on this phone from its location — no network, no server.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (active) {
            Text(
                "Night is on now (Auto night).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "At night, Auto night switches the color scheme to Night. Your " +
                "chosen scheme returns in the morning. If you pick Night " +
                "yourself, it stays Night all the time. To stop the switching, " +
                "turn Auto night off.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Hour (0–23, wraps) + minute (15-minute steps, wraps) steppers for one schedule time. */
@Composable
private fun TimeStepperRow(label: String, minutes: Int, onSet: (Int) -> Unit) {
    val hour = minutes / 60
    val minute = minutes % 60
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            GsIconBox(
                onClick = { onSet(((hour + 23) % 24) * 60 + minute) },
                modifier = Modifier.height(56.dp).weight(1f),
            ) { Text("−", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            Text(
                "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            GsIconBox(
                onClick = { onSet(((hour + 1) % 24) * 60 + minute) },
                modifier = Modifier.height(56.dp).weight(1f),
            ) { Text("+", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            GsIconBox(
                onClick = { onSet(hour * 60 + ((minute + 45) % 60)) },
                modifier = Modifier.height(56.dp).weight(1f),
            ) { Text("−15", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            GsIconBox(
                onClick = { onSet(hour * 60 + ((minute + 15) % 60)) },
                modifier = Modifier.height(56.dp).weight(1f),
            ) { Text("+15", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        }
        Text(
            "Left pair steps the hour, right pair steps the minutes by 15.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Individually-switched experimental features, shown once the driver has opted into
 * experimental features as a whole. Each is its own row - a title, what it does, and a switch -
 * so the list reads fine whether there's one entry or several.
 *
 * Order (r53, audit #4 D1 + Robert's 2026-10-26 correction): floating media,
 * frosted map chrome, home widgets, then vehicle data LAST — vehicle data is
 * never part of the master switch (see ExperimentalMaster) and sits last as
 * its own switch. The master row itself lives in AboutAndExperimentalPage
 * above the Hide/Show row, always visible; this list only renders when the
 * rows are shown.
 * r65: Meridian map chrome sits after frosted map chrome; vehicle data stays last.
 * r67: full-width listening glow sits after Meridian; vehicle data stays last.
 * r73: multi-turn conversation sits after the glow; vehicle data stays last.
 */

/** r53: applies one master-switch action's writes through the existing setters, field by field. */
private fun applyMasterWrites(writes: ExperimentalMaster.Writes) {
    writes.experimentalFeatures?.let { CarSettings.setExperimentalFeaturesEnabled(it) }
    writes.floatingMedia?.let { CarSettings.setFloatingMediaEnabled(it) }
    writes.homeWidgets?.let { CarSettings.setHomeWidgetsEnabled(it) }
    writes.widgetsDismissed?.let { CarSettings.setWidgetsDismissed(it) }
    writes.frostedMapChrome?.let { CarSettings.setFrostedMapChromeEnabled(it) }
}
@Composable
private fun ExperimentalFeaturesList(
    carSensors: Boolean, floatingMedia: Boolean, frostedMapChrome: Boolean, homeWidgets: Boolean, widgetsDismissed: Boolean,
    hiddenWidgets: Set<WidgetKind>, meridianChrome: Boolean, fullWidthGlow: Boolean, mediaCardDock: MediaCardDock, multiTurn: Boolean, sideBar: Boolean,
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ExperimentalFeatureRow(
            title = "Floating media over map (experimental)",
            description = "Home shows the map full-screen with the player as a floating card " +
                "over it, instead of side-by-side. The card is a frosted approximation, " +
                "not live blur, and it never covers turn instructions. Classic Home " +
                "returns when this is off.",
            checked = floatingMedia,
        ) { CarSettings.setFloatingMediaEnabled(it) }
        // r69: mini-player docking (A44/A45) — the floating media
        // card's bottom dock, placed with the card's own feature row.
        // End is today's placement; Center centers it; Start moves it
        // to the opposite corner, which is the passenger side in a
        // right-hand-drive car. Start/End follow layout direction,
        // they are not absolute left/right. During guidance the trip
        // bar sits at the bottom-start corner, so a Start dock steps
        // aside to End while the trip bar is showing rather than
        // covering it (see effectiveMediaCardDock).
        ChoiceRow(
            "Mini-player docking (experimental)",
            listOf(MediaCardDock.START to "Start", MediaCardDock.CENTER to "Center", MediaCardDock.END to "End"),
            mediaCardDock,
        ) { CarSettings.setMediaCardDock(it) }
        Text(
            "Takes effect only while experimental features are on; with experiments off the card stays at today's placement and the stored choice is preserved.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // r74: steering-side floating app bar (A1). The bottom bar's
        // app row, stood on its side on the driver's side — placed with
        // the other layout rows (floating media, card docking) at the
        // top of the list. Still behind the general experimental
        // opt-in: the rail renders only when experiments are on AND
        // this is on. Off returns exactly today's bottom bar.
        ExperimentalFeatureRow(
            title = "Steering-side app bar (experimental)",
            description = "Moves the app bar to a floating rail on the driver's side of the screen instead of along the bottom. The bottom bar returns when this is off. The rail steps to the passenger side when the turn card, the widget column, or the media card occupies the driver's side.",
            checked = sideBar,
        ) { CarSettings.setSideBarEnabled(it) }
        ExperimentalFeatureRow(
            title = "Frosted map chrome (experimental)",
            description = "The map pane, turn card, trip bar, and edge buttons get the same " +
                "frosted treatment as the floating media card — the map shows through " +
                "faintly at the same level, in the active style and scheme. This is a " +
                "frosted approximation, not live blur. Classic opaque chrome returns " +
                "when this is off.",
            checked = frostedMapChrome,
        ) { CarSettings.setFrostedMapChromeEnabled(it) }
        // r65: the ONE Meridian toggle. It gates the whole Meridian
        // look at once — turn card, trip pill, panes, and edge
        // controls — and absorbs the retired Appearance choice row, so
        // there is no second control that can disagree with it. It
        // still sits behind the general experimental opt-in: Meridian
        // renders only when experiments are on AND this is on.
        ExperimentalFeatureRow(
            title = "Meridian map chrome (experimental)",
            description = "The whole map look wears Meridian at once: a solid green turn card, " +
                "a dark trip pill, dark solid panes with white text, and dark edge " +
                "controls. Meridian's colors are fixed and do not follow the theme. " +
                "Turn this off and everything returns to the themed look.",
            checked = meridianChrome,
        ) { CarSettings.setMeridianChromeEnabled(it) }
        // r67: the full-width listening glow. It widens the assistant's
        // listening glow to a full-width band at the bottom edge while
        // listening — our own scheme colors, never Gemini's look — and
        // still sits behind the general experimental opt-in: it renders
        // only when experiments are on AND this is on. Off returns the
        // overlay to exactly today's small glow.
        ExperimentalFeatureRow(
            title = "Full-width listening glow (experimental)",
            description = "While the assistant is listening, its glow spans the full width " +
                "of the screen at the bottom edge instead of a small segment. It uses " +
                "the active theme's colors, never covers anything tappable, and " +
                "disappears when the assistant stops listening. Turn this off and " +
                "the small glow returns.",
            checked = fullWidthGlow,
        ) { CarSettings.setFullWidthGlowEnabled(it) }
        // r73: multi-turn conversation (A59). Follow-up answers can
        // refer to earlier turns of the same conversation instead of
        // answering each question on its own. Still behind the general
        // experimental opt-in: history is sent only when experiments
        // are on AND this is on. Off returns exactly today's
        // single-turn answers, and clears the in-memory session.
        ExperimentalFeatureRow(
            title = "Multi-turn conversation (experimental)",
            description = "Follow-up answers can refer to earlier turns of the same conversation instead of answering each question on its own. History lives only in memory for the current drive and is never saved; turning this off clears it at once. It still needs experimental features on, and single-turn answers return when this is off.",
            checked = multiTurn,
        ) { CarSettings.setMultiTurnEnabled(it) }
        ExperimentalFeatureRow(
            title = "Home widgets (experimental)",
            description = "Shows weather, notification digest, and next event as a column " +
                "over the full-screen map when floating media is on. Widgets appear " +
                "only on that floating base — classic Home is unchanged when this " +
                "is off. The column stays up while navigating, moving to the " +
                "passenger side when guidance or a map panel is showing. " +
                "Dismissing the column hides it until you bring it back " +
                "here, across drives.",
            checked = homeWidgets,
        ) { CarSettings.setHomeWidgetsEnabled(it) }
        if (widgetsDismissed) {
            GsButton(
                onClick = { CarSettings.setWidgetsDismissed(false) },
                tone = GsTone.Tonal, modifier = Modifier.height(56.dp),
            ) {
                Text("Show widget column again", style = MaterialTheme.typography.titleMedium)
            }
        }
        // r58 B10: per-widget hide toggles — which of the three widgets
        // the column shows. A hidden widget never renders, and the
        // empty-state explainer names it as hidden rather than empty.
        // The master switch never touches these (see ExperimentalMaster).
        if (homeWidgets) {
            ExperimentalFeatureRow(
                title = "Weather widget",
                description = "Shows the weather chip in the Home widget column.",
                checked = WidgetKind.WEATHER !in hiddenWidgets,
            ) { CarSettings.setWidgetHidden(WidgetKind.WEATHER, !it) }
            ExperimentalFeatureRow(
                title = "Notification digest widget",
                description = "Shows the unread count and latest sender in the Home widget column. Never message text.",
                checked = WidgetKind.DIGEST !in hiddenWidgets,
            ) { CarSettings.setWidgetHidden(WidgetKind.DIGEST, !it) }
            ExperimentalFeatureRow(
                title = "Next event widget",
                description = "Shows the next scheduled event in the Home widget column.",
                checked = WidgetKind.EVENT !in hiddenWidgets,
            ) { CarSettings.setWidgetHidden(WidgetKind.EVENT, !it) }
        }
        // r53: vehicle data LAST, as its own switch — never part of the
        // master switch (see ExperimentalMaster).
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
                    style = MaterialTheme.typography.bodyMedium,
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
        Text("Web page", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Surface(
            onClick = { editing = !editing },
            // Phase 7 kit: bright grouped-card colour, 20dp radius (r29:
            // squared under Pit Lane like the other sheet cards).
            shape = gsButtonShape(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)),
            color = MaterialTheme.colorScheme.surfaceBright,
            modifier = Modifier.fillMaxWidth(),
        ) {
            KeyboardFieldText(
                url,
                "DuckDuckGo (default) - tap to set a different page",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                style = MaterialTheme.typography.bodyLarge,
                showCaret = editing,
            )
        }
        Text(
            "Shown by the Browser app on the launcher. Applies on the next connection.",
            style = MaterialTheme.typography.bodyMedium,
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
            style = MaterialTheme.typography.bodyMedium,
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
        Text("Screen fit", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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

