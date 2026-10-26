package app.autospace.ui

import app.autospace.speech.SpeechModels
import app.autospace.speech.VoiceModels

import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.LinearProgressIndicator

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.key
import app.autospace.BuildConfig
import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.autospace.AppSettings
import app.autospace.car.CarSettings
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import app.autospace.call.CarCalls
import app.autospace.car.Prefetch
import app.autospace.host.AndroidAuto
import app.autospace.notify.GearslipNotificationListener

/**
 * One page of the guide. [permissions] drives a system permission dialog; [command] is an adb
 * command to copy; [actionLabel] with [onAction] is a button that does something else (opens a
 * settings screen) without by itself finishing the page. Everything is skippable: none of this
 * can be verified as done, only offered.
 */
private class GuidePage(
    val title: String,
    /** Plain text, or an [AnnotatedString] when part of it needs to stand out. */
    val explanation: CharSequence,
    /** Drawn inside the page's badge, in the badge's content colour. */
    val icon: @Composable (Color) -> Unit,
    /** Marked "Optional" above the title: everything works without it. */
    val optional: Boolean = false,
    val permissions: Array<String>? = null,
    val command: String? = null,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    /** Drawn under [explanation]. */
    val content: (@Composable () -> Unit)? = null,
    /** Only shown when the driver picked USB audio on the car audio page. */
    val usbOnly: Boolean = false,
)

/** A plain Material icon, sized to fill the badge's icon box. */
private fun glyph(icon: ImageVector): @Composable (Color) -> Unit = { tint ->
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.fillMaxSize())
}

/** True once every permission in [permissions] is already granted - nothing left for this page to do. */
private fun granted(context: Context, permissions: Array<String>): Boolean =
    permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

/** Whether Gearslip already holds the hidden `PROJECT_MEDIA` app-op an adb command grants -
 * checked the same way the platform checks it, since there is no public permission for it. */
private fun hasProjectMediaOp(context: Context): Boolean {
    val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
    val mode = runCatching {
        ops.unsafeCheckOpNoThrow("android:project_media", android.os.Process.myUid(), context.packageName)
    }.getOrDefault(AppOpsManager.MODE_IGNORED)
    return mode == AppOpsManager.MODE_ALLOWED
}

private fun hasCallScreeningRole(context: Context): Boolean {
    val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
    return runCatching { roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) }.getOrDefault(false)
}

/** Whether the driver has already granted Gearslip's [GearslipNotificationListener] access. */
private fun hasNotificationAccess(context: Context): Boolean {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    val component = ComponentName(context, GearslipNotificationListener::class.java)
    return runCatching { manager.isNotificationListenerAccessGranted(component) }.getOrDefault(false)
}

private fun pages(context: Context, onRequestCallScreening: () -> Unit): List<GuidePage> = buildList {
    // --- Safety: always first, and never skipped by an already-granted check ---------
    add(
        GuidePage(
            title = "Drive safely.",
            explanation = "AutoSpace is unofficial. No carmaker made or checked it, and it can fail " +
                "without warning. Set it up before you go, then keep your eyes on the road.",
            icon = glyph(Icons.Filled.Warning),
        ),
    )

    // --- Permissions: skipped outright once already granted, nothing left to ask -----
    // Asked whether or not car audio is on: voice reply and the assistant need it too.
    if (!granted(context, arrayOf(Manifest.permission.RECORD_AUDIO))) {
        add(
            GuidePage(
                title = "Microphone.",
                explanation = "AutoSpace uses it to send your music to the car and to hear you when you " +
                    "ask for something or reply to a message. What you say stays on the phone.",
                icon = { MicIcon(it) },
                permissions = arrayOf(Manifest.permission.RECORD_AUDIO),
            ),
        )
    }
    // Phase 7: the phone-microphone choice as its own onboarding step, in
    // GuidePage style (title, explanation, badge icon, content slot) — NOT
    // gated on the permission above, so it stays reachable even when
    // RECORD_AUDIO is already granted (the permission page itself is skipped
    // outright in that case). Same persisted setting as car Settings
    // (CarSettings.phoneMicForVoice, default off); toggling in either place
    // reflects in the other, since both read the same flow.
    add(
        GuidePage(
            title = "Car microphone.",
            explanation = "If your car's microphone doesn't work, AutoSpace can listen " +
                "through the phone's microphone instead, for voice commands and " +
                "voice replies. This does NOT affect phone calls.",
            icon = { MicIcon(it) },
            optional = true,
            content = {
                val phoneMic by CarSettings.phoneMicForVoice.collectAsState()
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Use phone microphone for voice commands",
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = phoneMic, onCheckedChange = CarSettings::setPhoneMicForVoice)
                }
            },
        ),
    )
    // Call speaker (2026-10-25): its own onboarding step, NOT gated on any
    // permission, so it stays reachable even when every permission is already
    // granted — the same reason the "Car microphone" page above is ungated.
    // Same persisted setting as car Settings (CarSettings.callSpeaker,
    // default off); toggling in either place reflects in the other.
    add(
        GuidePage(
            title = "Calls on speaker.",
            explanation = "If your car's microphone doesn't work, calls can go through the " +
                "phone's speaker instead of the car's Bluetooth — so the caller hears " +
                "you through the phone's microphone. This affects phone calls only, " +
                "not voice commands or music, and the speaker is restored when the " +
                "call ends. The car's Bluetooth may still take the call back.",
            icon = { MicIcon(it) },
            optional = true,
            content = {
                val callSpeaker by CarSettings.callSpeaker.collectAsState()
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Answer calls on the phone speaker",
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = callSpeaker, onCheckedChange = CarSettings::setCallSpeaker)
                }
            },
        ),
    )
    if (!granted(context, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))) {
        add(
            GuidePage(
                title = "Location.",
                explanation = "The car's map and nearby searches need it. Without it, the dashboard " +
                    "shows no map.",
                icon = glyph(Icons.Filled.LocationOn),
                permissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            ),
        )
    }
    if (!granted(context, arrayOf(Manifest.permission.READ_CALENDAR))) {
        add(
            GuidePage(
                title = "Calendar.",
                explanation = "The car screen shows your next event. AutoSpace only reads your calendar.",
                icon = glyph(Icons.Filled.DateRange),
                permissions = arrayOf(Manifest.permission.READ_CALENDAR),
            ),
        )
    }
    val callPermissions = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.CALL_PHONE,
    )
    if (!granted(context, callPermissions)) {
        add(
            GuidePage(
                title = "Calls and contacts.",
                explanation = "The car shows who's calling by name, and its Phone app can dial a " +
                    "number or call one of your contacts. AutoSpace only reads them.",
                icon = glyph(Icons.Filled.Phone),
                permissions = callPermissions,
            ),
        )
    }
    if (!granted(context, arrayOf(Manifest.permission.BLUETOOTH_CONNECT))) {
        add(
            GuidePage(
                title = "Bluetooth.",
                explanation = "The first time you plug in, AutoSpace offers to pair your phone with the " +
                    "car, so calls play through its speakers.",
                icon = glyph(Icons.Filled.Call),
                permissions = arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
            ),
        )
    }

    if (!granted(context, arrayOf(Manifest.permission.POST_NOTIFICATIONS))) {
        add(
            GuidePage(
                title = "Notifications.",
                explanation = "While AutoSpace is connected, it shows a notification with a button " +
                    "to stop. Without this, Android hides it.",
                icon = glyph(Icons.Filled.Notifications),
                permissions = arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            ),
        )
    }
    if (!hasNotificationAccess(context)) {
        add(
            GuidePage(
                title = "Messages.",
                explanation = "The car shows your messages, reads them aloud and lets you reply. " +
                    "Turn AutoSpace on in the list this opens.",
                icon = glyph(Icons.Filled.Email),
                actionLabel = "Open notification access",
                onAction = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
            ),
        )
    }

    // --- Android Auto ------------------------------------------------------------------
    val androidAutoPackage = AndroidAuto.installedPackage(context)
        ?.takeIf { AndroidAuto.isActive(context, it) }
    if (androidAutoPackage != null) {
        add(
            GuidePage(
                title = "Turn off Android Auto.",
                explanation = "Android Auto is still installed, and it fights AutoSpace for the car's " +
                    "USB connection. Disable or uninstall it on the screen this opens.",
                icon = glyph(Icons.Filled.Close),
                actionLabel = "Open Android Auto's app info",
                onAction = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", androidAutoPackage, null)),
                    )
                },
            ),
        )
    }

    // --- Default handler ---------------------------------------------------------------
    add(
        GuidePage(
            title = "Choose AutoSpace.",
            explanation = "If your phone asks which app to open when you plug into the car, pick " +
                "AutoSpace and tap Always.",
            icon = glyph(Icons.Filled.Check),
        ),
    )

    // --- Car audio: always asked, and it decides whether the adb pages below show ---------
    add(
        GuidePage(
            title = "Car audio.",
            explanation = "Pick how music reaches the car. Calls go over Bluetooth either way.",
            icon = glyph(Icons.Filled.PlayArrow),
            content = { AudioChoice() },
        ),
    )

    // --- Voice: both recommended models in one tap, skipped once they're on the phone ---
    if (!SpeechModels.RECOMMENDED.all { SpeechModels.isReady(context, it) }) {
        add(
            GuidePage(
                title = "Voice.",
                explanation = "Ask for directions, music, or a call by voice. Your phone turns speech " +
                    "into text by itself, and nothing you say leaves it. It needs " +
                    "${SpeechModels.RECOMMENDED.sumOf { it.downloadMb }} MB, downloaded once. Use Wi-Fi if you can.",
                icon = { MicIcon(it) },
                optional = true,
                actionLabel = "Download voice",
                onAction = { SpeechModels.downloadRecommended(context) },
                content = { VoiceDownloadStatus() },
            ),
        )
    }

    // --- Spoken voice: its own step, right after the listening one, and NOT a
    // replacement for it. The page above is the voice AutoSpace listens with
    // (speech into text); this one is the voice it speaks with (text into
    // speech) — Kokoro first, Piper as fallback. One tap downloads both, the
    // way the listening page downloads both its models, and the page is
    // skipped once either spoken voice is on the phone.
    if (!VoiceModels.isReady(context, VoiceModels.Engine.KOKORO) &&
        !VoiceModels.isReady(context, VoiceModels.Engine.PIPER)
    ) {
        add(
            GuidePage(
                title = "Spoken voice.",
                explanation = "The voice AutoSpace answers and reads directions in: Kokoro-82M " +
                    "first, in American Adam unless you pick another voice in Settings, " +
                    "with Piper (Amy) as the fallback. Entirely on this phone — no " +
                    "system voice, no other engines. It needs " +
                    "${VoiceModels.Engine.KOKORO.downloadMb + VoiceModels.Engine.PIPER.downloadMb} MB, " +
                    "downloaded once. Use Wi-Fi if you can.",
                icon = glyph(Icons.Filled.PlayArrow),
                optional = true,
                actionLabel = "Download voices",
                onAction = {
                    VoiceModels.download(context, VoiceModels.Engine.KOKORO)
                    VoiceModels.download(context, VoiceModels.Engine.PIPER)
                },
                content = { SpokenVoiceDownloadStatus() },
            ),
        )
    }

    // --- Optional: each is skipped once its actual effect is already in place --------
    if (!hasProjectMediaOp(context)) {
        add(
            GuidePage(
                title = "No audio prompt.",
                explanation = "Run this from a computer with USB debugging on, and Android trusts " +
                    "AutoSpace with your audio for good. Without it, you tap Start once each drive.",
                icon = glyph(Icons.Filled.Build),
                optional = true,
                usbOnly = true,
                command = "adb shell appops set ${BuildConfig.APPLICATION_ID} PROJECT_MEDIA allow",
            ),
        )
    }
    if (!granted(context, arrayOf(Manifest.permission.WRITE_SECURE_SETTINGS))) {
        add(
            GuidePage(
                title = "Readable notifications.",
                explanation = "While audio goes to the car, Android hides what notifications say, " +
                    "as if you were sharing your screen. This lets AutoSpace show them during a " +
                    "drive and hide them again after.",
                icon = glyph(Icons.Filled.Build),
                optional = true,
                usbOnly = true,
                command = "adb shell pm grant ${BuildConfig.APPLICATION_ID} android.permission.WRITE_SECURE_SETTINGS",
            ),
        )
    }
    if (!hasCallScreeningRole(context)) {
        add(
            GuidePage(
                title = "Answer calls.",
                explanation = "Make AutoSpace your caller ID app, and the car can answer and decline " +
                    "calls. Without it, calls still ring, but only the phone can answer them.",
                icon = glyph(Icons.Filled.Call),
                optional = true,
                actionLabel = "Set up",
                onAction = onRequestCallScreening,
            ),
        )
    }

    // --- Apps worth installing, once everything above is set up -----------------------
    if (SHOW_RECOMMENDED_APPS) {
        add(
            GuidePage(
                title = "Good company.",
                explanation = RECOMMENDED_INTRO,
                icon = { StarIcon(it) },
                content = { RecommendedAppsList() },
            ),
        )
    }

    // Usage notes were the last page here until 2026-10-25;
    // removed with the rest of the notes surface (no stats server,
    // notes go nowhere). The backend stays, so it is reversible.
}

/**
 * Bluetooth or USB for the car's music, as two cards. Bluetooth comes first and is picked for a
 * driver who hasn't chosen yet: it never asks for consent, and the map app's voice reaches the car.
 */
/** How the voice download is going: a bar while it runs, a line once it's done or failed. */
@Composable
private fun VoiceDownloadStatus() {
    val context = LocalContext.current
    val progress by SpeechModels.progress.collectAsState()
    val states = SpeechModels.RECOMMENDED.map { SpeechModels.state(it).collectAsState().value }
    val line = when {
        progress != null -> "Downloading, $progress%. You can carry on; it keeps going."
        states.all { it == SpeechModels.State.Ready } -> "Voice is ready."
        states.any { it is SpeechModels.State.Failed } -> "The download didn't finish. Tap Download voice to try again."
        else -> null
    }
    LaunchedEffect(Unit) { SpeechModels.init(context) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        progress?.let { LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth()) }
        line?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** How the spoken-voice download is going, one line per engine. */
@Composable
private fun SpokenVoiceDownloadStatus() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { VoiceModels.init(context) }
    val kokoro by VoiceModels.state(VoiceModels.Engine.KOKORO).collectAsState()
    val piper by VoiceModels.state(VoiceModels.Engine.PIPER).collectAsState()
    fun line(engine: VoiceModels.Engine, state: VoiceModels.State): String = when (state) {
        VoiceModels.State.Ready -> "${engine.label} is ready."
        is VoiceModels.State.Downloading -> "${engine.label}: downloading, ${state.percent}%." + (if (state.detail.isNotEmpty()) " ${state.detail}." else "")
        is VoiceModels.State.Failed -> "${engine.label} didn't finish: ${state.reason}. Tap Download voices to try again."
        VoiceModels.State.Missing -> "${engine.label} not downloaded yet."
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(line(VoiceModels.Engine.KOKORO, kokoro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(line(VoiceModels.Engine.PIPER, piper), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (kokoro is VoiceModels.State.Ready || piper is VoiceModels.State.Ready) {
            Text("Spoken voice is ready.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AudioChoice() {
    val usb by CarSettings.pipeAudio.collectAsState()
    LaunchedEffect(Unit) { if (!CarSettings.pipeAudioChosen) CarSettings.setPipeAudio(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AudioCard(
            "Bluetooth", "Recommended",
            "No prompt each drive, and your map app's voice reaches the car.",
            selected = !usb,
        ) { CarSettings.setPipeAudio(false) }
        AudioCard(
            "USB", null,
            "Clearer sound with no delay. Android asks to share audio once each drive.",
            selected = usb,
        ) { CarSettings.setPipeAudio(true) }
    }
}

@Composable
private fun AudioCard(title: String, tag: String?, body: String, selected: Boolean, onClick: () -> Unit) {
    val c = introColors()
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) c.primaryC else Color.Transparent)
            .border(BorderStroke(2.dp, if (selected) c.primary else c.outline), shape)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, color = if (selected) c.onPrimaryC else c.onSurface, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold))
            tag?.let { Text(it, color = c.primary, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)) }
            Text(
                body, color = if (selected) c.onPrimaryC else c.onVariant,
                style = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * The first-run guide, one page at a time: permissions, then removing Android Auto if it is still installed, then the optional extras. Nothing
 * here can be verified as done except a permission grant, so every other page is a "Next" a
 * driver takes on trust, with "Skip" sitting right next to it.
 */
@Composable
fun SetupGuideScreen(onRequestCallScreening: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val allPages = remember { pages(context, onRequestCallScreening) }
    // The USB-only pages all come after the car audio page, so dropping them never moves the
    // page the driver is on.
    val usbAudio by CarSettings.pipeAudio.collectAsState()
    val pages = remember(usbAudio) { allPages.filter { usbAudio || !it.usbOnly } }
    var index by remember { mutableIntStateOf(0) }

    // Only ours to handle once there is a previous page to return to; at the first page, the
    // system back press falls through to whatever the screen beneath this one does with it.
    BackHandler(enabled = index > 0) { index -= 1 }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { it }) {
            Prefetch.warm(context, force = true)
            CarCalls.start(context)
        }
        index += 1
    }

    val page = pages.getOrNull(index)
    if (page == null) {
        AppSettings.setSeenPermissionsSetup(context)
        onDone()
        return
    }

    val alreadyGranted = page.permissions?.all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    } ?: false

    val c = introColors()
    // The badges take turns through the phone's three accent colours, as the release intro does.
    val (badge, onBadge) = listOf(c.primaryC to c.onPrimaryC, c.secondaryC to c.onSecondaryC, c.tertiaryC to c.onTertiaryC)[index % 3]
    val spin by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(18_000, easing = LinearEasing)), label = "spin",
    )

    BoxWithConstraints(Modifier.fillMaxSize().background(c.surface).systemBarsPadding()) {
        val w = maxWidth
        // One percent of the screen's width, as in the intro, so it scales the same way.
        fun cq(n: Float): Dp = w * (n / 100f)
        val density = LocalDensity.current
        fun cqSp(n: Float): TextUnit = with(density) { cq(n).toSp() }

        Column(Modifier.fillMaxSize().padding(horizontal = cq(8f))) {
            // The page scrolls above the buttons, so a tall one (the usage notes' choices) never
            // pushes them off the screen. Short pages sit in the middle.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                key(index) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .heightIn(min = maxHeight)
                            .padding(vertical = cq(6f)),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(Modifier.size(cq(28f)).pop(entrance(index, 50, 800)), contentAlignment = Alignment.Center) {
                            Cookie(badge, Modifier.fillMaxSize().graphicsLayer { rotationZ = spin })
                            Box(Modifier.size(cq(12f))) { page.icon(onBadge) }
                        }
                        Spacer(Modifier.height(cq(7f)))
                        if (page.optional) {
                            Text(
                                "OPTIONAL", color = c.primary,
                                style = TextStyle(fontSize = cqSp(3.4f), fontWeight = FontWeight.Bold, letterSpacing = cqSp(0.4f)),
                                modifier = Modifier.up(entrance(index, 150, 700), cq(7f)),
                            )
                            Spacer(Modifier.height(cq(1.5f)))
                        }
                        Text(
                            page.title, color = c.onSurface,
                            // Long titles step down a size so they stay on two lines.
                            style = TextStyle(
                                fontSize = cqSp(if (page.title.length > 16) 8f else 10f), fontWeight = FontWeight.Black,
                                letterSpacing = cqSp(-0.4f), lineHeight = cqSp(if (page.title.length > 16) 8.6f else 10.5f),
                            ),
                            modifier = Modifier.up(entrance(index, 200, 700), cq(7f)),
                        )
                        Spacer(Modifier.height(cq(4f)))
                        Column(
                            Modifier.up(entrance(index, 350, 700), cq(7f)),
                            verticalArrangement = Arrangement.spacedBy(cq(3f)),
                        ) {
                            Text(
                                page.explanation as? AnnotatedString ?: AnnotatedString(page.explanation.toString()),
                                color = c.onVariant,
                                style = TextStyle(fontSize = cqSp(4.5f), lineHeight = cqSp(6.5f)),
                            )
                            page.command?.let { CopyableCommand(it) }
                            page.content?.invoke()
                            if (page.permissions != null && alreadyGranted) {
                                Text(
                                    "Already allowed.", color = c.primary,
                                    style = TextStyle(fontSize = cqSp(4.2f), fontWeight = FontWeight.SemiBold),
                                )
                            }
                        }
                    }
                }
            }

            // A single page has nothing to count.
            if (pages.size > 1) {
                Dots(c, pages.size, index, ::cq, Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(cq(5f)))
            }
            val back = if (index > 0) ({ index -= 1 }) else null
            val next = { index += 1 }
            when {
                page.permissions != null && !alreadyGranted -> {
                    PillRow(c, ::cq, ::cqSp, back, "Allow") { launcher.launch(page.permissions) }
                    Pill(c, ::cqSp, "Skip for now", PillStyle.TEXT, Modifier.align(Alignment.CenterHorizontally), next)
                }
                page.onAction != null -> {
                    Pill(c, ::cqSp, page.actionLabel ?: "Open", PillStyle.OUTLINED, Modifier.fillMaxWidth().padding(bottom = cq(3f)), page.onAction)
                    PillRow(c, ::cq, ::cqSp, back, "Next", next)
                }
                else -> PillRow(c, ::cq, ::cqSp, back, if (index == pages.lastIndex) "Done" else "Next", next)
            }
            Spacer(Modifier.height(cq(6f)))
        }
    }
}

internal enum class PillStyle { FILLED, OUTLINED, TEXT }

/** The intro's rounded buttons: filled for the step's main action, outlined beside it, bare text to skip. */
@Composable
internal fun Pill(c: IntroColors, cqSp: (Float) -> TextUnit, label: String, style: PillStyle, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .then(
                when (style) {
                    PillStyle.FILLED -> Modifier.background(c.primary)
                    PillStyle.OUTLINED -> Modifier.border(BorderStroke(2.dp, c.outline), shape)
                    PillStyle.TEXT -> Modifier
                },
            )
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (style == PillStyle.FILLED) c.onPrimary else c.primary,
            style = TextStyle(fontSize = cqSp(4.4f), fontWeight = FontWeight.Bold),
        )
    }
}

/** Back on the left once there's a page to go back to, and the page's main action filling the rest. */
@Composable
internal fun PillRow(
    c: IntroColors, cq: (Float) -> Dp, cqSp: (Float) -> TextUnit,
    onBack: (() -> Unit)?, label: String, onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(cq(3f))) {
        if (onBack != null) Pill(c, cqSp, "Back", PillStyle.OUTLINED, Modifier.weight(1f), onBack)
        Pill(c, cqSp, label, PillStyle.FILLED, Modifier.weight(if (onBack != null) 1.4f else 1f), onClick)
    }
}
