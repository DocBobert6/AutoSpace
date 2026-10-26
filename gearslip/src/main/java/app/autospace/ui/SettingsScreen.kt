package app.autospace.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.autospace.AppSettings
import app.autospace.BuildConfig
import app.autospace.CertProvider
import app.autospace.SessionStatus
import app.autospace.notify.TestMessage
import app.autospace.car.AutoNightMode
import app.autospace.car.CarSettings
import app.autospace.car.NightAuto
import app.autospace.car.SunResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenRecommendedApps: () -> Unit,
    onOpenWhatsNew: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenCarPreview: () -> Unit,
    onOpenCarPreviewSettings: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenAssistantPreview: () -> Unit,
    onReplayTutorial: () -> Unit,
    onReplayIntro: () -> Unit,
) {
    val scroll = rememberScrollState()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Spacer(Modifier.height(0.dp))
            ConnectionSection()
            HelpSection(onOpenHelp, onOpenCarPreview, onOpenCarPreviewSettings, onOpenLogs)
            CarAudioSection()
            NightSection()
            VoiceModelSection()
            // Spoken voice, alongside the listening models above — not instead of
            // them: those turn speech into text for voice commands, this one turns
            // text into speech for replies and directions. Same VoiceModels state
            // as car Settings, so either place reflects the other.
            SpokenVoiceSection()
            // Custom voice shortcuts (r19): beside the voice sections, where someone
            // looking for voice settings finds them. The car side lists them read-only.
            VoiceShortcutsSection()
            // AI assistant (r25): beside the voice sections for the same reason — the
            // key is entered, tested, and removed here, never on a head unit.
            AiAssistantSection(onOpenAssistantPreview)
            // Call speaker, same state as car Settings: a call-audio choice, not a
            // voice model, so it gets its own section rather than joining the above.
            CallSpeakerSection()
            if (SHOW_RECOMMENDED_APPS) AppsSection(onOpenRecommendedApps)
            // Usage notes were removed (2026-10-25): there is no
            // stats server, the notes go nowhere, and the section
            // had already shrunk to an informational note. The
            // backend stays, so the removal is reversible.
            SettingsSection("Updates") { UpdateRow() }
            CertificateSection()
            AboutSection(onOpenWhatsNew, onReplayTutorial, onReplayIntro)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Arrow() = Icon(
    Icons.AutoMirrored.Filled.KeyboardArrowRight,
    contentDescription = null,
    tint = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** What's new, the replays, and where Gearslip lives outside the app. */
@Composable
private fun AboutSection(onOpenWhatsNew: () -> Unit, onReplayTutorial: () -> Unit, onReplayIntro: () -> Unit) {
    SettingsSection("About") {
        SettingsRow(
            "What's new",
            Modifier.clickable(onClick = onOpenWhatsNew),
            subtitle = "Version ${BuildConfig.VERSION_NAME}",
            icon = Icons.Filled.Star,
            trailing = { Arrow() },
        )
        SettingsDivider()
        SettingsRow(
            "Replay the ${Intro.release} intro",
            Modifier.clickable(onClick = onReplayIntro),
            subtitle = "What's in this release, in a few taps",
            icon = Icons.Filled.Star,
            trailing = { Arrow() },
        )
        SettingsDivider()
        SettingsRow(
            "Replay the setup guide",
            Modifier.clickable(onClick = onReplayTutorial),
            subtitle = "Go through the first-run steps again",
            icon = Icons.Filled.Refresh,
            trailing = { Arrow() },
        )
        SettingsDivider()
        AboutLinkRows()
    }
}

/** Off, a stock Android Auto app or head unit answers the USB connection instead of Gearslip. */
@Composable
private fun ConnectionSection() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AppSettings.gearslipEnabled(context)) }
    SettingsSection("Connection") {
        SettingsRow(
            "Use AutoSpace in the car",
            Modifier.toggleable(enabled, role = Role.Switch) {
                enabled = it
                AppSettings.setGearslipEnabled(context, it)
            },
            subtitle = if (enabled) "AutoSpace answers when you plug into a car" else "Off: Android Auto can take the connection instead",
            icon = Icons.Filled.Settings,
            trailing = { Switch(checked = enabled, onCheckedChange = null) },
        )
    }
}

/** Connection help, and the tools for checking what Gearslip does. */
@Composable
private fun HelpSection(
    onOpenHelp: () -> Unit,
    onOpenCarPreview: () -> Unit,
    onOpenCarPreviewSettings: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val context = LocalContext.current
    val status by SessionStatus.state.collectAsState()
    val live = status.phase == SessionStatus.Phase.CONNECTING || status.phase == SessionStatus.Phase.PROJECTING
    SettingsSection("Help and tools") {
        SettingsRow(
            "Connection help",
            Modifier.clickable(onClick = onOpenHelp),
            subtitle = "What to set up first",
            icon = Icons.Filled.Info,
            trailing = { Arrow() },
        )
        // The preview draws the car screen on the phone, so it waits until no car is using it.
        if (!live) {
            SettingsDivider()
            SettingsRow(
                "Car preview",
                Modifier.clickable(onClick = onOpenCarPreview),
                subtitle = "See the car screen on your phone",
                icon = Icons.Filled.PlayArrow,
                trailing = { Arrow() },
            )
        }
        // The preview's own settings stay reachable even while a
        // car is connected (they change nothing until the preview
        // opens), so this row is outside the !live block above.
        SettingsDivider()
        SettingsRow(
            "Car preview settings",
            Modifier.clickable(onClick = onOpenCarPreviewSettings),
            subtitle = "Size, density, theme and test options for the preview only",
            icon = Icons.Filled.Settings,
            trailing = { Arrow() },
        )
        SettingsDivider()
        SettingsRow(
            "Live logs",
            Modifier.clickable(onClick = onOpenLogs),
            subtitle = "What AutoSpace and the car are saying to each other",
            icon = Icons.AutoMirrored.Filled.List,
            trailing = { Arrow() },
        )
        if (BuildConfig.DEBUG) {
            SettingsDivider()
            SettingsRow(
                "Send a test message",
                Modifier.clickable { TestMessage.post(context) },
                subtitle = "Debug builds only. Posts a message notification to read on the car",
                icon = Icons.AutoMirrored.Filled.Send,
            )
        }
    }
}

/** How music reaches the car: by Bluetooth as usual, or through Gearslip over USB. Same choice as setup. */
@Composable
private fun CarAudioSection() {
    val usb by CarSettings.pipeAudio.collectAsState()
    SettingsSection(
        "Car audio",
        footer = if (usb) "Run the command in Help once, and USB stops asking." else "Calls go over Bluetooth either way.",
    ) {
        SettingsRow(
            "Bluetooth (Recommended)",
            Modifier.selectable(!usb, role = Role.RadioButton) { CarSettings.setPipeAudio(false) },
            subtitle = "No prompt each drive, and your map app's voice reaches the car",
            trailing = { RadioButton(selected = !usb, onClick = null) },
        )
        SettingsDivider()
        SettingsRow(
            "USB",
            Modifier.selectable(usb, role = Role.RadioButton) { CarSettings.setPipeAudio(true) },
            subtitle = "Clearer sound with no delay. Android asks to share audio once each drive",
            trailing = { RadioButton(selected = usb, onClick = null) },
        )
    }
}

/** Night color scheme + Auto night scheduling (same state as car Settings). */
@Composable
private fun NightSection() {
    val context = LocalContext.current
    val mode by CarSettings.autoNightMode.collectAsState()
    val start by CarSettings.autoNightStart.collectAsState()
    val end by CarSettings.autoNightEnd.collectAsState()
    val active by CarSettings.autoNightActive.collectAsState()
    val sun by produceState<SunResult?>(null, mode) {
        value = if (mode == AutoNightMode.SUNRISE_SUNSET) NightAuto.todaySunTimes(context) else null
    }
    LaunchedEffect(Unit) {
        while (true) {
            CarSettings.refreshAutoNight()
            delay(60_000)
        }
    }
    SettingsSection(
        "Night",
        footer = "At night, Auto night switches the color scheme to Night. Your chosen scheme " +
            "returns in the morning. If you pick Night yourself, it stays Night all the time. " +
            "To stop the switching, turn Auto night off. While Night is active, the background " +
            "is pure black no matter which dark type is picked.",
    ) {
        NightModeRow("Off", "No automatic switching", mode == AutoNightMode.OFF) {
            CarSettings.setAutoNightMode(AutoNightMode.OFF)
        }
        SettingsDivider()
        NightModeRow("Schedule", "Night runs between your start and end times", mode == AutoNightMode.SCHEDULE) {
            CarSettings.setAutoNightMode(AutoNightMode.SCHEDULE)
        }
        if (mode == AutoNightMode.SCHEDULE) {
            SettingsDivider()
            NightTimeRow("Night starts", start) { CarSettings.setAutoNightStart(it) }
            SettingsDivider()
            NightTimeRow("Night ends", end) { CarSettings.setAutoNightEnd(it) }
        }
        SettingsDivider()
        NightModeRow("Sunrise & sunset", "Computed on this phone from its location, no network", mode == AutoNightMode.SUNRISE_SUNSET) {
            CarSettings.setAutoNightMode(AutoNightMode.SUNRISE_SUNSET)
        }
        if (mode == AutoNightMode.SUNRISE_SUNSET) {
            SettingsDivider()
            val sunText = when (val s = sun) {
                null -> "Sunrise & sunset needs location. Give AutoSpace location access, or use a " +
                    "schedule instead. Until location is available, Auto night stays off rather than guessing."
                SunResult.AlwaysDay -> "The sun never sets where you are right now, so Auto night stays off."
                SunResult.AlwaysNight -> "The sun never rises where you are right now, so Auto night stays on."
                is SunResult.Times -> "Today: sunrise ${NightAuto.formatMinutes(s.sunriseMin)}, sunset " +
                    "${NightAuto.formatMinutes(s.sunsetMin)}. Night runs from sunset to sunrise."
            }
            SettingsRow(sunText, Modifier, null)
        }
        if (active) {
            SettingsDivider()
            SettingsRow("Night is on now (Auto night)", Modifier, null)
        }
    }
}

@Composable
private fun NightModeRow(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit) {
    SettingsRow(
        title,
        Modifier.selectable(selected, role = Role.RadioButton) { onSelect() },
        subtitle = subtitle,
        trailing = { RadioButton(selected = selected, onClick = null) },
    )
}

@Composable
private fun NightTimeRow(label: String, minutes: Int, onSet: (Int) -> Unit) {
    val hour = minutes / 60
    val minute = minutes % 60
    SettingsRow(
        label,
        Modifier,
        subtitle = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}",
        trailing = {
            androidx.compose.foundation.layout.Row {
                IconButton(onClick = { onSet(((hour + 23) % 24) * 60 + minute) }) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Hour down")
                }
                IconButton(onClick = { onSet(((hour + 1) % 24) * 60 + minute) }) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Hour up")
                }
                IconButton(onClick = { onSet(hour * 60 + ((minute + 45) % 60)) }) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Minutes down")
                }
                IconButton(onClick = { onSet(hour * 60 + ((minute + 15) % 60)) }) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Minutes up")
                }
            }
        },
    )
}

@Composable
private fun AppsSection(onOpenRecommendedApps: () -> Unit) {
    SettingsSection("Apps") {
        SettingsRow(
            "Recommended apps",
            Modifier.clickable(onClick = onOpenRecommendedApps),
            icon = Icons.Filled.Star,
            trailing = { Arrow() },
        )
    }
}

@Composable
private fun CertificateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var refresh by remember { mutableIntStateOf(0) }
    // The cached summary shows at once; the read still runs, in case the cert changed since.
    val summary by produceState(CertSummary.cached, refresh) {
        value = withContext(Dispatchers.Default) { CertSummary.read(context) }
    }

    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var detailsOpen by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingUri = uri
    }

    SettingsSection("Certificate", footer = notice ?: "Changes take effect on the next connection.") {
        val s = summary
        val loaded = s != null && s.kind != CertProvider.Kind.SELF_SIGNED
        val hasDetails = s?.subject != null || s?.issuer != null
        SettingsRow(
            s?.headline ?: "Checking…",
            if (hasDetails) Modifier.clickable { detailsOpen = !detailsOpen } else Modifier,
            subtitle = s?.validUntil?.let { "Valid until $it" },
            icon = if (s == null || loaded) Icons.Filled.Lock else Icons.Filled.Warning,
            tint = if (s == null || loaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            trailing = if (hasDetails) {
                {
                    Icon(
                        if (detailsOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (detailsOpen) "Hide details" else "Show details",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else null,
        )
        AnimatedVisibility(detailsOpen && hasDetails) {
            Column(
                Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                s?.subject?.let { Detail("Subject", it) }
                s?.issuer?.let { Detail("Issuer", it) }
            }
        }
        SettingsDivider()
        SettingsRow(
            if (downloading) "Downloading…" else "Download from aasdk",
            Modifier.clickable(enabled = !downloading) {
                downloading = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { CertProvider.downloadAndImport(context) } }
                    downloading = false
                    result.onSuccess {
                        notice = "Certificate downloaded."
                        refresh++
                    }.onFailure {
                        notice = "Download failed: ${it.message}"
                    }
                }
            },
            subtitle = "The certificate the open-source aasdk project publishes on GitHub. AutoSpace doesn't include one",
            icon = Icons.Filled.Refresh,
        )
        SettingsDivider()
        SettingsRow(
            "Import my own file",
            Modifier.clickable { picker.launch(arrayOf("*/*")) },
            subtitle = "A .p12 file with its private key, from wherever you choose",
            icon = Icons.Filled.Add,
        )
        val removable = when (s?.kind) {
            CertProvider.Kind.IMPORTED -> "Remove imported certificate"
            CertProvider.Kind.DOWNLOADED -> "Remove downloaded certificate"
            else -> null
        }
        if (removable != null) {
            SettingsDivider()
            SettingsRow(
                removable,
                Modifier.clickable {
                    if (s?.kind == CertProvider.Kind.IMPORTED) CertProvider.removeImported(context) else CertProvider.removeDownloaded(context)
                    notice = if (s?.kind == CertProvider.Kind.IMPORTED) "Removed the imported certificate." else "Removed the downloaded certificate."
                    refresh++
                },
                icon = Icons.Filled.Delete,
                tint = MaterialTheme.colorScheme.error,
                titleColor = MaterialTheme.colorScheme.error,
            )
        }
    }

    pendingUri?.let { uri ->
        PasswordDialog(
            onDismiss = { pendingUri = null },
            onConfirm = { password, reportError ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { CertProvider.importFrom(context, uri, password) }
                    }
                    result.onSuccess {
                        pendingUri = null
                        notice = "Certificate imported."
                        refresh++
                    }.onFailure {
                        reportError("Could not import: wrong password, or not a PKCS#12 file with a private key.")
                    }
                }
            },
        )
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PasswordDialog(onDismiss: () -> Unit, onConfirm: (String, (String) -> Unit) -> Unit) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Certificate password") },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; error = null },
                singleLine = true,
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = error != null,
                supportingText = { Text(error ?: "Leave empty if the file has none.") },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(password) { error = it } }) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
