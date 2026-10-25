package app.autospace.car

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.BoxScope
import androidx.car.app.model.CarIcon
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import app.autospace.host.CarAppConnection
import app.autospace.host.display
import app.autospace.host.text
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.animation.animateContentSize
import android.media.session.PlaybackState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import app.autospace.R
import app.autospace.car.theme.GsColors
import app.autospace.car.theme.GsIconBox
import app.autospace.car.theme.LocalOledDark
import app.autospace.car.theme.GsIconButton
import app.autospace.car.theme.GsButton
import app.autospace.car.theme.GsTone
import app.autospace.car.theme.gsColors
import app.autospace.car.theme.GsThemes
import app.autospace.car.theme.LocalGsTheme
import app.autospace.media.CarMedia
import app.autospace.media.MediaArt
import app.autospace.mirror.PhoneMirror
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.autospace.notify.CarNotifications
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * The whole car screen: the current screen on top, the nav bar along the bottom.
 *
 * Colours follow AppTheme: plain Light/Dark are neutral, Material You Light/Dark and
 * Follow phone use the phone's wallpaper colours. The size setting scales everything by
 * changing the density, so text, icons and touch targets grow together.
 */
@Composable
fun CarUi() {
    val scale by CarSettings.scale.collectAsState()
    val phoneDark by CarEnvironment.phoneDark.collectAsState()
    val appTheme by CarSettings.appTheme.collectAsState()
    val uiThemeId by CarSettings.uiTheme.collectAsState()
    val mediaApp by CarServices.mediaApp.collectAsState()
    val darkOutside by CarEnvironment.darkOutside.collectAsState()
    val appContext = LocalContext.current.applicationContext
    val darkType by CarSettings.darkModeType.collectAsState()
    // Phase 9 immersive player: CarUi reads the player's published
    // controls visibility and hides the nav bar with it (see below).
    val playerControlsVisible by PlayerImmersiveState.controlsVisible.collectAsState()
    // Phase 9 mirror immersive: while mirroring, no AutoSpace chrome
    // may be drawn over the phone video. In a real car this is already
    // the case — showMirror stops the projector and the mirror owns
    // the encoder surface — but CarUi can still be composed (phone
    // preview, or a session where the projector is alive), so hide the
    // nav bar and the notification popup outright while mirroring.
    // CallOverlay stays: a ringing call outranks the mirror. When
    // mirroring ends, PhoneMirror.onEnded restarts CarUi with chrome
    // back, so nothing here needs to restore it.
    val mirrorActive by PhoneMirror.active.collectAsState()
    val dark = when (appTheme) {
        AppTheme.FOLLOW_SYSTEM, AppTheme.MATERIAL_YOU -> phoneDark
        AppTheme.LIGHT, AppTheme.MATERIAL_YOU_LIGHT -> false
        AppTheme.DARK, AppTheme.MATERIAL_YOU_DARK -> true
    }
    // Theme set, Phase 7 (Robert 2026-10-24 23:58, correcting the 22:32 five):
    // Follow system = PLAIN scheme following the phone's light/dark; Plain
    // Light / Dark = the neutral scheme below for EVERY style (the wallpaper-
    // dynamic scheme washed Frost tiles pale peach in Phases 1–5, and plain
    // means plain here); Material You = dynamic scheme following the phone;
    // Material You Light / Dark = dynamic schemes forced to that variant.
    // The old PHONE value migrated to MATERIAL_YOU on load, so no stored
    // install changes look from this split. Dark mode type (Regular vs OLED)
    // applies to plain Dark, Material You Dark, and the dark variant of both
    // Follow options, across all styles. Browser PAGES stay unthemed
    // (Phase 3 correction) — this scheme is chrome only, and it flows into
    // the video player chrome too (its black scrims over video are untouched).
    val colors = when (appTheme) {
        AppTheme.LIGHT, AppTheme.DARK, AppTheme.FOLLOW_SYSTEM -> neutralFrostScheme(dark, darkType == DarkModeType.OLED)
        AppTheme.MATERIAL_YOU_LIGHT -> dynamicLightColorScheme(appContext)
        AppTheme.MATERIAL_YOU_DARK -> if (darkType == DarkModeType.OLED) oledDynamicDark(appContext) else dynamicDarkColorScheme(appContext)
        AppTheme.MATERIAL_YOU -> if (dark) {
            if (darkType == DarkModeType.OLED) oledDynamicDark(appContext) else dynamicDarkColorScheme(appContext)
        } else dynamicLightColorScheme(appContext)
    }
    val base = LocalDensity.current
    val insets by CarEnvironment.insets.collectAsState()
    val navigator = remember { CarNavigator(CarSettings.openOnConnect.value) }
    val controlsWake = remember { MapControlsWake() }
    LaunchedEffect(controlsWake) {
        while (true) {
            delay(250)
            controlsWake.tick()
        }
    }
    // The knob counts as a touch: the map's buttons can't be found while they're faded out.
    LaunchedEffect(controlsWake) { CarKeys.focusMoves.collect { controlsWake.touch() } }
    remember(appContext) { Prefetch.warm(appContext, force = true); CarServices.init(appContext) }
    DisposableEffect(Unit) { onDispose { CarServices.shutdown() } }
    // Ask for the phone's sound as soon as the car connects, not only when a media app opens:
    // a video in the web browser or a map's voice needs it too. Asks once, after the car offers
    // somewhere to play it.
    LaunchedEffect(Unit) { if (CarSettings.pipeAudio.value) app.autospace.audio.CarAudio.request() }
    // A new version gets one card in the top right, a few seconds in and only while parked.
    LaunchedEffect(Unit) { app.autospace.update.UpdateChecker.onCarConnected(appContext) }
    // The map follows the light outside, whatever the app's own theme is set to.
    LaunchedEffect(darkOutside) { CarServices.nav.pushConfiguration() }

    // The one place with a navigator and a frame to act a voice command on - CarAssistant itself
    // only knows how to listen and speak, not what "open Spotify" means.
    val frameForAssistant by CarEnvironment.frame.collectAsState()
    LaunchedEffect(navigator) {
        CarAssistant.pendingCommand.collect { heard ->
            if (heard != null) {
                val reply = AssistantCommands.handle(appContext, navigator, frameForAssistant, heard)
                CarAssistant.reply(reply)
            }
        }
    }

    // The car's own back, home, map, media and phone keys.
    LaunchedEffect(navigator) {
        CarKeys.screenKeys.collect { key ->
            when (key) {
                CarKeys.ScreenKey.BACK -> when {
                    navigator.appMenu != null -> navigator.dismissAppMenu()
                    VoiceReply.state.value.phase != VoiceReply.Phase.IDLE -> VoiceReply.cancel()
                    else -> navigator.back()
                }
                // Home is the dashboard, over the map, so Back from it lands on the map.
                CarKeys.ScreenKey.HOME -> { navigator.home(); navigator.dashboard() }
                CarKeys.ScreenKey.MAP -> navigator.home()
                CarKeys.ScreenKey.MEDIA ->
                    if (CarServices.mediaApp.value != null || CarServices.reopenLastMedia()) navigator.media()
                    else navigator.apps()
                CarKeys.ScreenKey.PHONE -> BuiltInApps.Phone.open(navigator)
            }
        }
    }

    // The car screen is drawn on the phone, so every long press or text-handle drag would buzz
    // the phone in its mount. Nobody feels that while driving; it's only noise.
    val view = LocalView.current
    DisposableEffect(view) {
        view.isHapticFeedbackEnabled = false
        onDispose { }
    }

    CompositionLocalProvider(
        LocalHapticFeedback provides NoHaptics,
        LocalDensity provides Density(base.density * scale, base.fontScale),
        LocalCarNavigator provides navigator,
        LocalDarkOutside provides darkOutside,
        LocalMapControlsWake provides controlsWake,
        LocalGsTheme provides GsThemes.byId(uiThemeId),
        // Phase 9 OLED flag for the kit — cards and controls draw a
        // hairline outline when this is true, so a dark surface on the
        // pure-black OLED background still reads as a surface, and the
        // frost gradient still has an edge to shade.
        LocalOledDark provides (dark && darkType == DarkModeType.OLED),
    ) {
        MaterialTheme(colorScheme = colors) {
          CompositionLocalProvider(LocalIndication provides FocusRingIndication(MaterialTheme.colorScheme.primary)) {
            Surface(
                Modifier
                    .fillMaxSize()
                    // Watches every touch without taking any: first in line, consumes nothing.
                    .pointerInput(controlsWake) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial)
                                controlsWake.touch()
                            }
                        }
                    },
                color = MaterialTheme.colorScheme.background,
            ) {
                // Insets are in video-frame pixels, so convert with the un-scaled density.
                val pad = with(base) {
                    PaddingValues(
                        start = insets.left.toDp(), top = insets.top.toDp(),
                        end = insets.right.toDp(), bottom = insets.bottom.toDp(),
                    )
                }
                val screen = navigator.current
                val safetyWarningSeen by CarSettings.hasSeenSafetyWarning.collectAsState()
                Box(Modifier.fillMaxSize().padding(pad)) {
                Column(Modifier.fillMaxSize()) {
                    if (!safetyWarningSeen) {
                        CarSafetyWizard(onDone = CarSettings::markSafetyWarningSeen)
                        return@Column
                    }
                    // Header navigation (breadcrumb trail) - off for now, kept to tweak later.
                    // BreadcrumbBar(navigator)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        // Home never leaves the composition; other screens cover it. Dropping it
                        // would tear down the map's surface, and coming back would then show black
                        // while the nav app redrew on a new one.
                        CarHome()
                        if (screen != CarScreen.Home) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background)
                                    // Keeps touches that miss this screen's controls off the map.
                                    .pointerInput(Unit) { detectTapGestures { } },
                            ) {
                                when (screen) {
                                    CarScreen.Home -> Unit
                                    CarScreen.Apps -> CarLauncher()
                                    CarScreen.Media -> mediaApp?.let { MediaScreen(it) { navigator.back() } }
                                    CarScreen.Browse -> BrowseAppScreen(navigator)
                                    CarScreen.Settings -> CarSettingsScreen()
                                    CarScreen.Dashboard -> CarDashboardScreen()
                                    CarScreen.Weather -> WeatherScreen()
                                    CarScreen.VehicleData -> VehicleDataScreen()
                                    is CarScreen.Notifications -> NotificationsScreen(screen.replyTo)
                                    is CarScreen.Messages -> MessagesScreen(screen.packageName)
                                    is CarScreen.VideoPlayer -> VideoPlayerScreen(urls = screen.urls, titles = screen.titles, startIndex = screen.index, onClose = { navigator.back() })
                                    is CarScreen.App -> CarApps.find(screen.id)?.content?.invoke()
                                }
                            }
                        }
                        CarToastOverlay(Modifier.align(Alignment.BottomCenter))
                        CallPromptOverlay()
                        BluetoothPairOverlay()
                        PopupTimer()
                        // The dashboard is already listing them, so it needs no popup.
                        NotificationPopup(
                            showNew = screen !is CarScreen.Notifications && screen != CarScreen.Dashboard && !mirrorActive,
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
                        // Above the popup: a call is more urgent than any notification.
                        VoiceHelpOverlay(Modifier.align(Alignment.Center))
                        CallOverlay(Modifier.align(Alignment.TopCenter))
                        AssistantOverlay(Modifier.align(Alignment.BottomCenter))
                        // Phase 8 audit fix: the debug badge no longer
                        // floats over content at all — it lives in the nav
                        // bar (see CarNavBar), where it can cover nothing.
                    }
                    // The keyboard takes the nav bar's row instead of squeezing in above it -
                    // nothing on the nav bar is useful mid-type, and the screen is too small to
                    // show both.
                    // Phase 9 immersive player: when the video player is the
                    // active screen AND its controls are hidden (3.5s
                    // auto-hide while playing), the bottom toolbar hides
                    // with them, and a tap — which re-shows the player
                    // controls — re-shows the bar too. The bar is NEVER
                    // hidden while controls are shown (Close/Stop must
                    // stay reachable), nor while paused/driving/error/
                    // listen mode, because the player never hides its
                    // controls in those states. CarUi has no persistent
                    // top bar (the breadcrumb is off, and call/notification
                    // overlays are urgent and transient, not chrome), and
                    // the player's own top capsule already follows
                    // controlsVisible, so the nav bar is the chrome that
                    // had to be lifted here; the video stays full-bleed.
                    val keyboardVisible by CarKeyboardVisibility.state
                    val playerImmersiveHide = screen is CarScreen.VideoPlayer && !playerControlsVisible
                    if (!keyboardVisible && !playerImmersiveHide && !mirrorActive) CarNavBar(navigator)
                }
                // Over everything, nav bar included: a long press on an app, anywhere.
                AppMenu(navigator, rememberRunningApps())
                if (safetyWarningSeen) CarWhatsNew()
                // Brightness dim, over everything except the brightness sheet itself:
                // a black overlay with no input handling, so touches pass straight
                // through to the screen below and audio is untouched. The sheet is
                // drawn after it, so it stays undimmed and tappable.
                val brightness by CarSettings.brightness.collectAsState()
                if (brightness < 1f) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 1f - brightness)))
                }
                if (BrightnessSheetVisibility.state.value) BrightnessSheet()
                }
            }
          }
        }
    }
}

/**
 * A single blocking screen, shown once per install before anything else the car screen can show -
 * Home included. The phone's own setup guide carries the same warning, but a driver who plugs in
 * without ever opening the phone app would otherwise never see it, on the one screen they are
 * actually about to use while driving. [onDone] fires once, from its own button; there is no way
 * to skip past this without tapping it.
 */
@Composable
private fun CarSafetyWizard(onDone: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // The card and its button share one width, capped so neither sprawls across a wide screen.
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Drive safely", style = ChromeType.headline, fontWeight = FontWeight.Bold)
                    Text(
                        "AutoSpace is unofficial software: it is not made or reviewed by any " +
                            "carmaker, and it can fail without warning.",
                        style = ChromeType.body,
                    )
                    Text(
                        "Never interact with this screen while driving. Set the route or the " +
                            "song before you go, then keep your attention on the road.",
                        style = ChromeType.body,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            GsButton(onClick = onDone, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("I understand", style = ChromeType.body)
            }
        }
    }
}

/**
 * A templated app that isn't navigation, full size with its own title bar and Stop button - the
 * same [CarAppStage] chrome the map uses on Home, just not embedded there. [CarServices.browse]
 * is a connection of its own, so opening one of these never bumps the map off Home.
 */
@Composable
private fun BrowseAppScreen(navigator: CarNavigator) {
    val status by CarServices.browse.status.collectAsState()
    val frame by CarEnvironment.frame.collectAsState()
    val template by CarServices.browse.template.collectAsState()
    val leave = {
        CarServices.stopBrowse()
        navigator.back()
    }
    // An app that never answers is as stuck as one that says no; don't leave the driver on "Loading".
    var timedOut by remember { mutableStateOf(false) }
    LaunchedEffect(template == null) {
        timedOut = false
        if (template == null) {
            delay(APP_START_TIMEOUT_MS)
            timedOut = true
        }
    }
    val failed = status.phase == CarAppConnection.Phase.REJECTED || status.phase == CarAppConnection.Phase.FAILED
    if (failed || timedOut) {
        AppWontStart(status.app ?: "This app", leave)
        return
    }
    CarAppStage(CarServices.browse, status, frame, onDisconnect = leave)
}

private const val APP_START_TIMEOUT_MS = 15_000L

/** What a car app that won't run under Gearslip gets instead of a loading screen that never ends. */
@Composable
private fun AppWontStart(app: String, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$app doesn't work with AutoSpace yet.", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "It didn't start. Some apps only run on Google's Android Auto.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GsButton(onClick = onBack, tone = GsTone.Tonal) { Text("Back") }
    }
}

/**
 * Marks a debug build on the car screen itself, not just the phone's Home screen - the one
 * that matters while driving, since a debug build is slower and less tested than what ships.
 */
@Composable
private fun DebugBuildBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(6.dp),
        color = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            "DEBUG BUILD",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * "Home > Apps > Settings", like a file manager's address bar: hidden at Home itself (nothing to
 * show yet), and grows by one crumb for every screen pushed from there. The back arrow undoes one
 * step; a crumb jumps straight to it, dropping everything after - this is the only on-screen way
 * back at all, since a car touchscreen has no hardware button for it.
 */
@Composable
private fun BreadcrumbBar(navigator: CarNavigator) {
    val trail = navigator.trail
    if (trail.size <= 1) return
    Row(
        Modifier.fillMaxWidth().height(40.dp).padding(start = 2.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GsIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", navigator::back, size = 32.dp, iconSize = 20.dp)
        Spacer(Modifier.width(2.dp))
        trail.forEachIndexed { index, crumb ->
            val isLast = index == trail.lastIndex
            Text(
                crumb.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isLast) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (isLast) Modifier else Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { navigator.jumpTo(index) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
            if (!isLast) {
                Text(
                    "›",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }
    }
}

/**
 * Apps and open/pinned shortcuts on the left; now-playing pill against them; clock (tap for
 * calendar and weather), phone battery and the darkness signal on the right. Icon-only: a driver
 * reads a glyph faster than a label, and it keeps the bar short enough to leave the app underneath
 * more room.
 */
@Composable
private fun CarNavBar(navigator: CarNavigator) {
    val battery by CarEnvironment.battery.collectAsState()
    val now by rememberNow()
    val weather by Weather.state.collectAsState()
    val weatherData = (weather as? WeatherState.Ready)?.data

    val pinnedIds by CarSettings.pinnedApps.collectAsState()
    val entries = rememberAllEntries()
    val pinnedEntries = remember(entries, pinnedIds) {
        pinnedIds.mapNotNull { id -> entries.find { it.componentId == id } }
    }
    val frame by CarEnvironment.frame.collectAsState()
    val running = rememberRunningApps()

    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 10.dp)) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                // Home and Apps are two different places now, not a toggle on one button - the
                // breadcrumb trail is how you retrace your steps, but reaching for either of
                // these two from three screens deep in a media app shouldn't mean reading it
                // first.
                NavHomeButton(navigator, entries, running)
                Spacer(Modifier.width(6.dp))
                NavItem(MediaIcons.Apps, navigator.current == CarScreen.Apps) { navigator.apps() }
                Spacer(Modifier.width(6.dp))
                NavAssistantIcon()

                // Pinned shortcuts, then whatever else is open right now. A pinned app that's also
                // open stays in its pinned spot, marked with a dot, instead of showing up twice.
                // The map app lives on the Home button, so it never gets a second icon here.
                val runningEntries = running.keys
                    .filter { it !in pinnedIds && running[it] != RunningSlot.NAV }
                    .mapNotNull { id -> entries.find { it.componentId == id } }
                val shownPins = pinnedEntries.filter { running[it.componentId] != RunningSlot.NAV }
                if (shownPins.isNotEmpty() || runningEntries.isNotEmpty()) NavDivider()
                shownPins.forEach { entry -> NavEntryIcon(entry, running, navigator, frame) }
                if (shownPins.isNotEmpty() && runningEntries.isNotEmpty()) NavDivider()
                runningEntries.forEach { entry -> NavEntryIcon(entry, running, navigator, frame) }

                // Next turn first, off the map only; the player shares the space when it fits,
                // and gives way to the turn when it doesn't.
                val turn = rememberNextTurn().takeIf { navigator.current != CarScreen.Home }
                BoxWithConstraints(Modifier.weight(1f).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    val roomForBoth = maxWidth >= TURN_AND_PLAYER_MIN_WIDTH
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        turn?.let { NavTurnPill(it) { navigator.home() } }
                        if (turn == null || roomForBoth) {
                            NavNowPlaying(navigator, selected = navigator.current == CarScreen.Media, modifier = Modifier.weight(1f, fill = false))
                        }
                    }
                }

                // Brightness, one tap to the slider sheet: the phone's brightness does
                // not reach the projected display, so this is the only brightness
                // control the driver has.
                GsIconButton(Icons.Filled.BrightnessMedium, "Brightness", BrightnessSheetVisibility::open)
                Spacer(Modifier.width(6.dp))
                if (app.autospace.BuildConfig.DEBUG) {
                    DebugBuildBadge()
                    Spacer(Modifier.width(6.dp))
                }
                // Weather no longer has its own chip - the temperature alone (no icon, no room
                // for one) sits where "Night"/"Day" used to, freeing a whole chip's width for the
                // Home button above.
                // The clock is also the notifications button: the dashboard lists them under the
                // calendar and weather, and a dot here marks anything unread.
                val unread by CarNotifications.unread.collectAsState()
                NavChip(
                    navigator.current == CarScreen.Dashboard || navigator.current is CarScreen.Notifications,
                    { navigator.dashboard() },
                ) {
                    if (unread > 0) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 8.dp, y = (-2).dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            if (battery.percent >= 0) {
                                Text(
                                    "${battery.percent}%",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (battery.charging) {
                                    ChargingGlyph(Modifier.size(13.dp))
                                }
                            }
                            weatherData?.let { w ->
                                Text(
                                    "  ·  ${w.temp}°",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Home: the map, with the player or lyrics beside it. While a map app runs, the button wears that
 * app's icon and a long press offers to close it, so the app needs no icon of its own on the bar.
 */
@Composable
private fun NavHomeButton(navigator: CarNavigator, entries: List<Entry>, running: Map<String, RunningSlot>) {
    val selected = navigator.current == CarScreen.Home
    val navId = running.entries.firstOrNull { it.value == RunningSlot.NAV }?.key
    val navEntry = navId?.let { id -> entries.find { it.componentId == id } }
    GsIconBox(
        { navigator.home() },
        colors = navColors(selected),
        latched = selected,
        onLongClick = navId?.let { id -> { navigator.showAppMenu(id) } },
    ) {
        val icon = navEntry?.icon
        if (icon != null) {
            Box(Modifier.padding(NAV_ITEM_PADDING).size(28.dp), contentAlignment = Alignment.Center) {
                Image(icon, navEntry.label, Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)))
            }
        } else {
            Icon(
                ImageVector.vectorResource(R.drawable.navigation_24), contentDescription = "Home",
                modifier = Modifier.padding(NAV_ITEM_PADDING).size(32.dp),
            )
        }
    }
}

/** Splits the nav bar's sections: Home and Apps, pinned apps, open apps. */
@Composable
private fun NavDivider() {
    Box(
        Modifier
            .padding(horizontal = 10.dp)
            .width(1.dp)
            .height(28.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/**
 * A pinned or open app on the nav bar. Tapping brings it back without reconnecting if it's
 * already open; a long press opens its menu (pin, unpin, close).
 */
@Composable
private fun NavEntryIcon(
    entry: Entry,
    running: Map<String, RunningSlot>,
    navigator: CarNavigator,
    frame: CarEnvironment.Frame,
) {
    val id = entry.componentId ?: return
    val slot = running[id]
    Box(Modifier.padding(horizontal = 3.dp)) {
        NavAppIcon(
            entry,
            open = slot != null,
            showing = slot != null && slot.isShowing(navigator.current),
            onLongClick = { navigator.showAppMenu(id) },
        ) { openApp(entry, slot, navigator, frame) }
    }
}

/** One open or pinned app's icon on the nav bar - the same fixed size and shape either way. */
@Composable
private fun NavAppIcon(
    entry: Entry,
    open: Boolean,
    showing: Boolean,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    GsIconBox(
        onClick,
        Modifier.size(52.dp),
        colors = if (showing) GsColors(scheme.secondaryContainer, scheme.onSecondaryContainer)
        else GsColors(scheme.surfaceVariant, scheme.onSurfaceVariant),
        latched = showing,
        onLongClick = onLongClick,
    ) {
        entry.icon?.let { Image(it, entry.label, Modifier.size(34.dp).clip(RoundedCornerShape(8.dp))) }
        entry.builtIn?.let { Icon(it.glyph, entry.label, Modifier.size(30.dp)) }
        // Only running apps get the line along the bottom edge, the usual taskbar mark for it.
        if (open) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 2.dp)
                    .size(width = 12.dp, height = 3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(scheme.primary),
            )
        }
    }
}

/** A tappable group in the bar that stays highlighted while its screen is open, like [NavItem]. */
@Composable
private fun NavChip(selected: Boolean, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    GsIconBox(onClick, colors = navColors(selected), latched = selected) {
        Box(Modifier.padding(horizontal = 10.dp, vertical = 2.dp)) { content() }
    }
}

/** Every nav bar item is raised, so it reads as pressable; the current screen's takes the accent colour. */
@Composable
private fun navColors(selected: Boolean): GsColors {
    val s = MaterialTheme.colorScheme
    return if (selected) GsColors(s.secondaryContainer, s.onSecondaryContainer)
    else GsColors(s.surfaceContainerHighest, s.onSurface)
}

/**
 * Between Apps and whatever's actually open, not with the clock/battery group on the right - a
 * driver reaches for it right after deciding "not a tap for this", same gesture family as the
 * two buttons beside it.
 */
@Composable
private fun NavAssistantIcon() {
    val state by CarAssistant.state.collectAsState()
    val active = state.phase != CarAssistant.Phase.IDLE
    GsIconBox({ CarAssistant.start() }, colors = navColors(active), latched = active) {
        MicGlyph(Modifier.padding(NAV_ITEM_PADDING).size(32.dp))
    }
}

/** Inner padding of every icon on the nav bar, leaving each button room inside the 56dp bar. */
private val NAV_ITEM_PADDING = 8.dp

@Composable
private fun NavItem(icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    GsIconBox(onClick, colors = navColors(selected), latched = selected) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(NAV_ITEM_PADDING).size(32.dp))
    }
}

/** Wall-clock time, ticking on the minute: no reason to redraw (and re-encode) more often. */
@Composable
internal fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        value = System.currentTimeMillis()
        delay(60_000 - value % 60_000)
    }
}

/** How long the pill shows a new song's title before settling to just the controls. */
private const val TITLE_SHOWN_MS = 3_000L

/** Below this, the nav bar's middle only has room for the turn, not the player beside it. */
private val TURN_AND_PLAYER_MIN_WIDTH = 520.dp

/** The next manoeuvre, as the running nav app last described it. */
private class NextTurn(val icon: CarIcon?, val distance: String, val road: String)

/**
 * Read straight off the nav app's own template: a navigation app keeps sending its routing card
 * while it's running, whether or not the map is the screen being shown.
 */
@Composable
private fun rememberNextTurn(): NextTurn? {
    val status by CarServices.nav.status.collectAsState()
    val template by CarServices.nav.template.collectAsState()
    // Read so a change of units redraws the distance straight away, not at the next update.
    CarSettings.units.collectAsState().value
    if (status.phase != CarAppConnection.Phase.RUNNING) return null
    val routing = (template as? NavigationTemplate)?.navigationInfo as? RoutingInfo ?: return null
    if (routing.isLoading) return null
    val step = routing.currentStep ?: return null
    return NextTurn(
        icon = step.maneuver?.icon,
        distance = routing.currentDistance.display(),
        road = step.road.text().ifEmpty { step.cue.text() },
    )
}

/** The next turn in the nav bar: arrow, how far, onto what. Tapping goes back to the map. */
@Composable
private fun NavTurnPill(turn: NextTurn, onClick: () -> Unit) {
    // Same finish as every other button on the bar, in the accent colour; the text runs a step
    // bigger than anything else there, since it's the one thing worth a glance mid-drive.
    GsIconBox(
        onClick,
        Modifier.widthIn(max = 340.dp).height(44.dp),
        colors = gsColors(GsTone.Primary),
    ) {
        Row(
            Modifier.padding(NAV_ITEM_PADDING),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CarGlyph(turn.icon, Modifier.size(28.dp))
            if (turn.distance.isNotEmpty()) {
                Text(turn.distance, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            if (turn.road.isNotEmpty()) {
                Text(
                    turn.road,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** "Title - Artist" on one line. The text itself cuts it off, and only where the pill runs out of room. */
private fun nowPlayingLabel(title: String, artist: String): String =
    listOf(title.trim(), artist.trim()).filter { it.isNotEmpty() }.joinToString(" - ")
        .ifEmpty { "Nothing playing" }

/**
 * The player in the nav bar, on every screen. Art and title open the full media screen;
 * play/pause and skip work right here. The dock button moves the player into the column beside
 * the map on Home (see [CarHome]), and the pill steps aside there while it's docked - unless the
 * column is showing lyrics instead, when the pill stays and its dock button swaps them back.
 */
@Composable
private fun NavNowPlaying(navigator: CarNavigator, selected: Boolean, modifier: Modifier = Modifier) {
    val media = CarServices.media
    val phase by media.phase.collectAsState()
    val now by media.now.collectAsState()
    if (phase != CarMedia.Phase.READY || !now.isActive) return
    // Docked beside the map, the player is already on screen - the pill steps aside for it.
    if (navigator.sidePanel == SidePanel.CONTROLS && navigator.current == CarScreen.Home) return
    // The media screen is the full player already; the pill would only repeat its controls.
    if (navigator.current == CarScreen.Media) return
    val context = LocalContext.current
    val art by produceState(now.art, now.art, now.artUri) { value = now.art ?: MediaArt.load(context, now.artUri) }
    val position by produceState(now.currentPosition(), now) {
        while (true) {
            value = now.currentPosition()
            if (!now.playing) break
            delay(1000)
        }
    }

    // A new song announces itself: title, play/pause and dock for a few seconds, then the title
    // goes and the pill settles to just the controls - unless the driver keeps the title up.
    val keepTitle by CarSettings.playerTitle.collectAsState()
    var announcing by remember { mutableStateOf(true) }
    LaunchedEffect(now.title, now.artist) {
        announcing = true
        delay(TITLE_SHOWN_MS)
        announcing = false
    }
    val showTitle = keepTitle || announcing

    val scheme = MaterialTheme.colorScheme
    val onPill = if (selected) scheme.onPrimaryContainer else scheme.onSecondaryContainer
    Surface(
        modifier = modifier
            .animateContentSize()
            // With the title up, all the room the bar has, so as much of it shows as can.
            .then(if (showTitle) Modifier.fillMaxWidth() else Modifier)
            .height(44.dp),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) scheme.primaryContainer else scheme.secondaryContainer,
        contentColor = onPill,
    ) {
        Box {
            Row(
                Modifier.fillMaxHeight().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Art (and the title while it shows) opens the full media screen.
                Row(
                    Modifier
                        .then(if (showTitle) Modifier.weight(1f) else Modifier)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { navigator.media() },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(scheme.surfaceVariant)) {
                        art?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    }
                    if (showTitle) {
                        Text(
                            nowPlayingLabel(now.title, now.artist),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (!showTitle) {
                    GsIconButton(
                        MediaIcons.Previous, "Previous", media::previous,
                        enabled = now.canDo(PlaybackState.ACTION_SKIP_TO_PREVIOUS), size = 36.dp, iconSize = 22.dp,
                    )
                }
                GsIconButton(
                    if (now.playing) MediaIcons.Pause else Icons.Filled.PlayArrow,
                    if (now.playing) "Pause" else "Play",
                    media::togglePlay,
                    tone = GsTone.Primary, size = 36.dp, iconSize = 22.dp,
                )
                if (!showTitle) {
                    GsIconButton(
                        MediaIcons.Next, "Next", media::next,
                        enabled = now.canDo(PlaybackState.ACTION_SKIP_TO_NEXT), size = 36.dp, iconSize = 22.dp,
                    )
                }
                GsIconButton(
                    MediaIcons.DockRight, "Move the player beside the map", navigator::togglePlayerDock,
                    size = 36.dp, iconSize = 22.dp,
                )
            }
            if (now.durationMs > 0) {
                // matchParentSize, so the line follows the pill's width rather than widening it.
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.BottomStart) {
                    Box(
                        Modifier
                            .fillMaxWidth((position.toFloat() / now.durationMs).coerceIn(0f, 1f))
                            .height(2.dp)
                            .background(onPill.copy(alpha = 0.7f)),
                    )
                }
            }
        }
    }
}

/**
 * A filled lightning bolt, next to the battery percentage instead of the word "charging" - the
 * core Material icon set this project ships (no extended pack, to keep the app small) has no
 * bolt, so this is drawn rather than a vector asset.
 */
@Composable
private fun ChargingGlyph(modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        val bolt = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.58f, 0f)
            lineTo(w * 0.12f, h * 0.58f)
            lineTo(w * 0.46f, h * 0.58f)
            lineTo(w * 0.42f, h)
            lineTo(w * 0.88f, h * 0.40f)
            lineTo(w * 0.54f, h * 0.40f)
            close()
        }
        drawPath(bolt, tint)
    }
}

/**
 * Dynamic dark, OLED (Phase 9, correcting Phase 8 — the middle path
 * between Phase 7 and Phase 8, do not ping-pong back to either): the
 * background is pure black #000000 (pixels off), but surfaces stay
 * DARK, not black — the dynamic Material You surfaces and accent are
 * RETAINED exactly, so OLED still has colour and the frost gradient
 * still has something to shade. Phase 8 forced every surface to black,
 * which killed the frost, killed the dynamic colour, and made
 * everything blend into one too-dark field. Separation comes from the
 * frost gradient (lighter top) plus the 0.5dp hairline (LocalOledDark),
 * not from grey fills and not from all-black. Dynamic dark + Regular
 * is left untouched: its surfaces are already dark grey, not black,
 * which is what Regular means.
 */
private fun oledDynamicDark(context: android.content.Context): androidx.compose.material3.ColorScheme {
    val black = androidx.compose.ui.graphics.Color.Black
    return dynamicDarkColorScheme(context).copy(
        background = black,
        surfaceDim = black,
        surfaceTint = black,
    )
}

/**
 * The shared plain scheme (Notscape Phase A tokens, car-adapted): since the
 * 2026-10-24 22:32 theme correction this is Light/Dark for every style, not
 * just Frost — see CarUi above for the six-way mapping.
 * neutral system backgrounds, label black/white, secondary label ~60%, accent
 * only on primary/selected controls (system blue). Deliberately NOT the
 * wallpaper-dynamic scheme: dynamic colour is what turned Frost tiles peach.
 * [oled] picks the dark surface family (Phase 9, correcting Phase 8):
 * OLED = pure-black BACKGROUND with surfaces DARK but not black
 * (#1C1C1E/#2C2C2E family, onSurfaceVariant lifted to #C7C7CC for
 * contrast on black), so the frost gradient and hairline still
 * separate surfaces from the background instead of everything
 * blending into one black field (Phase 8) or reading as grey blocks
 * (Phase 7, which never blackened the background properly); Regular
 * = dark-grey backgrounds, no pure black anywhere.
 */
private fun neutralFrostScheme(dark: Boolean, oled: Boolean = true): androidx.compose.material3.ColorScheme {
    val white = androidx.compose.ui.graphics.Color.White
    val black = androidx.compose.ui.graphics.Color.Black
    return if (!dark) androidx.compose.material3.lightColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF007AFF),
        onPrimary = white,
        primaryContainer = androidx.compose.ui.graphics.Color(0xFFD7E9FF),
        onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF001D36),
        secondary = androidx.compose.ui.graphics.Color(0xFF5A5A5E),
        onSecondary = white,
        background = white,
        onBackground = black,
        surface = white,
        onSurface = black,
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFFF2F2F7),
        onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF6E6E73),
        surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFFF2F2F7),
        surfaceContainer = white,
        surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFFF2F2F7),
        surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFFE5E5EA),
        surfaceBright = white,
        surfaceDim = androidx.compose.ui.graphics.Color(0xFFF2F2F7),
        outline = androidx.compose.ui.graphics.Color(0xFFC6C6C8),
        outlineVariant = androidx.compose.ui.graphics.Color(0xFFE5E5EA),
        error = androidx.compose.ui.graphics.Color(0xFFFF3B30),
        onError = white,
        surfaceTint = white,
    ) else if (oled) androidx.compose.material3.darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF0A84FF),
        onPrimary = white,
        primaryContainer = androidx.compose.ui.graphics.Color(0xFF003A6B),
        onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFD7E9FF),
        secondary = androidx.compose.ui.graphics.Color(0xFFAEAEB2),
        onSecondary = black,
        background = black,
        onBackground = white,
        surface = androidx.compose.ui.graphics.Color(0xFF1C1C1E),
        onSurface = white,
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFF1C1C1E),
        onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFC7C7CC),
        surfaceContainerLowest = androidx.compose.ui.graphics.Color(0xFF1C1C1E),
        surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFF1C1C1E),
        surfaceContainer = androidx.compose.ui.graphics.Color(0xFF2C2C2E),
        surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF2C2C2E),
        surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF3A3A3C),
        surfaceBright = androidx.compose.ui.graphics.Color(0xFF2C2C2E),
        surfaceDim = black,
        outline = androidx.compose.ui.graphics.Color(0xFF38383A),
        outlineVariant = androidx.compose.ui.graphics.Color(0xFF48484A),
        error = androidx.compose.ui.graphics.Color(0xFFFF453A),
        onError = white,
        surfaceTint = black,
    ) else androidx.compose.material3.darkColorScheme(
        // Regular dark: the same tokens, one step up — no pure black anywhere.
        primary = androidx.compose.ui.graphics.Color(0xFF0A84FF),
        onPrimary = white,
        primaryContainer = androidx.compose.ui.graphics.Color(0xFF003A6B),
        onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFD7E9FF),
        secondary = androidx.compose.ui.graphics.Color(0xFFAEAEB2),
        onSecondary = black,
        background = androidx.compose.ui.graphics.Color(0xFF1C1C1E),
        onBackground = white,
        surface = androidx.compose.ui.graphics.Color(0xFF1C1C1E),
        onSurface = white,
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFF2C2C2E),
        onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFAEAEB2),
        surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFF2C2C2E),
        surfaceContainer = androidx.compose.ui.graphics.Color(0xFF3A3A3C),
        surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF3A3A3C),
        surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF48484A),
        surfaceBright = androidx.compose.ui.graphics.Color(0xFF3A3A3C),
        surfaceDim = androidx.compose.ui.graphics.Color(0xFF1C1C1E),
        outline = androidx.compose.ui.graphics.Color(0xFF38383A),
        outlineVariant = androidx.compose.ui.graphics.Color(0xFF48484A),
        error = androidx.compose.ui.graphics.Color(0xFFFF453A),
        onError = white,
        surfaceTint = androidx.compose.ui.graphics.Color(0xFF1C1C1E),
    )
}

/** Stands in for the phone's vibration motor on the car screen: does nothing. */
private object NoHaptics : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {}
}
