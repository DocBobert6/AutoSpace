package app.autospace.car

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.BoxScope
import androidx.car.app.model.CarIcon
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import app.autospace.host.CarAppConnection
import app.autospace.host.display
import app.autospace.host.text
import app.autospace.notify.CarMotion
import app.autospace.GearslipLog
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
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
import app.autospace.car.theme.GsKit
import app.autospace.car.theme.FrostedChromeAlpha
import app.autospace.car.theme.NavBarItemAlpha
import app.autospace.car.theme.GsIconBox
import app.autospace.car.theme.blueprintCardRing
import app.autospace.car.theme.LocalOledDark
import app.autospace.car.theme.GsIconButton
import app.autospace.car.theme.gsButtonShape
import app.autospace.car.theme.GsButton
import app.autospace.car.theme.GsTone
import app.autospace.car.theme.gsColors
import app.autospace.car.theme.GsThemes
import app.autospace.car.theme.gsButtonShape
import app.autospace.car.theme.gsShape
import app.autospace.car.theme.cardShapeForLook
import app.autospace.car.theme.morphCardColor
import app.autospace.car.theme.gsLook
import app.autospace.car.theme.pitLanePanelTexture
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
import kotlinx.coroutines.delay
import java.util.Date

/**
 * The whole car screen: the current screen on top, the nav bar along the bottom.
 *
 * Colours follow the split axes (SchemeMode.kt): the color scheme is the
 * palette (Neutral is plain, Material You uses the phone's wallpaper
 * colours), and the appearance mode — Light, Dark, or System — decides
 * the light/dark variant. The size setting scales everything by
 * changing the density, so text, icons and touch targets grow together.
 */
/**
 * A theme override for the car PREVIEW only (Robert, 2026-10-25).
 * Any field left null falls back to the car's own setting; a null
 * [PreviewThemeOverride] means no override at all. The real session
 * calls CarUi() with no argument, so its rendering is untouched —
 * the override is applied only where the preview passes one.
 */
data class PreviewThemeOverride(
    val colorScheme: ColorScheme?,
    val appearanceMode: AppearanceMode?,
    val uiThemeId: String?,
    val darkType: DarkModeType?,
)

@Composable
fun CarUi(previewTheme: PreviewThemeOverride? = null) {
    val scale by CarSettings.scale.collectAsState()
    val phoneDark by CarEnvironment.phoneDark.collectAsState()
    val appTheme by CarSettings.effectiveColorScheme.collectAsState()
    val appearanceMode by CarSettings.appearanceMode.collectAsState()
    val uiThemeId by CarSettings.uiTheme.collectAsState()
    val mediaApp by CarServices.mediaApp.collectAsState()
    val darkOutside by CarEnvironment.darkOutside.collectAsState()
    val appContext = LocalContext.current.applicationContext
    val darkType by CarSettings.darkModeType.collectAsState()
    // Preview-only overrides, applied after the unconditional
    // collects so the real path (previewTheme == null) reads exactly
    // what it read before. A null field falls back to the car's own
    // setting, so a preview that overrides only the scheme still
    // follows the car's mode, and vice versa.
    val effScheme = previewTheme?.colorScheme ?: appTheme
    val effMode = previewTheme?.appearanceMode ?: appearanceMode
    val effUiThemeId = previewTheme?.uiThemeId ?: uiThemeId
    val effDarkType = previewTheme?.darkType ?: darkType
    // Phase 9 immersive player: CarUi reads the player's published
    // controls visibility and hides the nav bar with it (see below).
    val playerControlsVisible by PlayerImmersiveState.controlsVisible.collectAsState()
    // Browser immersive fullscreen (Robert 2026-10-25): same
    // pattern — the browser publishes, CarUi hides the nav bar
    // with it, but only while the browser itself is the active
    // screen (see browserImmersiveHide below).
    val browserImmersive by BrowserImmersiveState.immersive.collectAsState()
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
    // r35: dark follows the split axes through resolveDark (SchemeMode.kt),
    // the single place the rule lives. Night and Pitlane are always dark;
    // every other scheme follows the mode control, with System deferring
    // to the phone. Circuit and Heritage follow the mode control now, not
    // the phone directly — that is the split, not a behavior change for
    // any stored value (their old phone-following value migrates to
    // System, which defers to the phone exactly as before).
    val dark = resolveDark(effScheme, effMode, phoneDark)
    // Theme set, Phase 7 (Robert 2026-10-24 23:58, correcting the 22:32 five):
    // Neutral = the plain scheme; Material You = the dynamic scheme, whose
    // light/dark variant follows the mode control; Night = pure black,
    // always dark; Pitlane = carbon black, always dark. r23 added Pitlane,
    // Circuit and Heritage as further scheme choices in the same when
    // below; DarkModeType does not alter them, and Auto night still
    // overrides all three to NIGHT. Browser PAGES stay unthemed
    // (Phase 3 correction) — this scheme is chrome only, and it flows into
    // the video player chrome too (its black scrims over video are untouched).
    val colors = when (effScheme) {
        ColorScheme.NEUTRAL -> neutralFrostScheme(dark, effDarkType == DarkModeType.OLED)
        ColorScheme.NIGHT -> nightColorScheme()
        // r23: the new schemes ignore DarkModeType — their backgrounds
        // are scheme-defined. Auto night still overrides them to NIGHT
        // above (via effectiveColorScheme), exactly as for every scheme.
        ColorScheme.PITLANE -> pitlaneScheme()
        ColorScheme.CIRCUIT -> circuitScheme(dark)
        ColorScheme.HERITAGE -> heritageScheme(dark)
        ColorScheme.VERVE -> verveScheme(dark)
        ColorScheme.HORIZON -> horizonScheme(dark)
        ColorScheme.MERIDIAN -> meridianScheme()
        ColorScheme.MATERIAL_YOU -> if (dark) {
            if (effDarkType == DarkModeType.OLED) oledDynamicDark(appContext) else dynamicDarkColorScheme(appContext)
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
    // Auto night re-evaluates once a minute (sunrise/sunset and schedule
    // windows only change with the clock; no fresh location fix is taken).
    LaunchedEffect(Unit) {
        while (true) {
            CarSettings.refreshAutoNight()
            delay(60_000)
        }
    }
    remember(appContext) { Prefetch.warm(appContext, force = true); CarServices.init(appContext) }
    DisposableEffect(Unit) { onDispose { CarServices.shutdown() } }
    // Ask for the phone's sound as soon as the car connects, not only when a media app opens:
    // a video in the web browser or a map's voice needs it too. Asks once, after the car offers
    // somewhere to play it.
    LaunchedEffect(Unit) { if (CarSettings.pipeAudio.value) app.autospace.audio.CarAudio.request() }
    // A new version gets one card in the top right, a few seconds in and only while parked.
    LaunchedEffect(Unit) {
        app.autospace.update.UpdateChecker.onCarConnected(appContext)
    }
    // The map follows the light outside, whatever the app's own theme is set to.
    LaunchedEffect(darkOutside) { CarServices.nav.pushConfiguration() }

    // The one place with a navigator and a frame to act a voice command on - CarAssistant itself
    // only knows how to listen and speak, not what "open Spotify" means.
    val frameForAssistant by CarEnvironment.frame.collectAsState()
    LaunchedEffect(navigator) {
        CarAssistant.pendingCommand.collect { heard ->
            if (heard != null) {
                // r44: a throw here used to kill this effect forever
                // and park the assistant in THINKING. The command
                // fails spoken, aloud, and the effect lives for the
                // next command.
                // r72 barge-in: capture this turn's generation — a turn
                // superseded mid-flight (a fresh listen or a cancel
                // while its answer was still streaming) never speaks:
                // its reply is discarded below, and its late stream
                // callbacks already died on the generation guard.
                val generation = CarAssistant.turnGeneration
                val reply = try {
                    AssistantCommands.handle(appContext, navigator, frameForAssistant, heard)
                } catch (t: Throwable) {
                    GearslipLog.e("assistant: command failed", t)
                    "Sorry, that command failed."
                }
                if (CarAssistant.turnGeneration == generation) {
                    CarAssistant.reply(
                        reply,
                        fromAi = AssistantCommands.lastReplyWasAi,
                        grounded = AssistantCommands.lastReplyGrounded,
                        sourceCount = AssistantCommands.lastReplySourceCount,
                        provider = AssistantCommands.lastReplyProvider,
                    )
                    // r97 graduation: multi-turn session record — only
                    // a completed AI turn. A superseded
                    // turn never reaches here (its reply is discarded
                    // above), so a barged-in reply's tail can never
                    // enter the session; an unmatched, refused,
                    // or emergency reply is not an AI answer and is not
                    // recorded either.
                    // r117: a completed AI turn starts or extends the
                    // session and is marked AI, so the live widget can
                    // badge it; a built-in turn joins only through
                    // the multi-turn gate (an existing session), and
                    // a superseded turn still never reaches here.
                    if (AssistantCommands.lastReplyWasAi) {
                        ConversationSession.record(
                            AssistantCommands.lastAiUtterance ?: heard, reply, fromAi = true,
                        )
                    } else if (AssistantCommands.lastReplyWasBuiltin) {
                        ConversationSession.recordBuiltin(heard, reply)
                    }
                } else {
                    GearslipLog.i("assistant: superseded turn discarded")
                }
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
        LocalGsTheme provides GsThemes.byId(effUiThemeId),
        // Phase 9 OLED flag for the kit — cards and controls draw a
        // hairline outline when this is true, so a dark surface on the
        // pure-black OLED background still reads as a surface, and the
        // frost gradient still has an edge to shade.
        LocalOledDark provides ((dark && effDarkType == DarkModeType.OLED) || effScheme == ColorScheme.NIGHT),
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
                // The keyboard takes the rail's row instead of squeezing in above it -
                // nothing on the rail is useful mid-type, and the screen is too small to
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
                // controlsVisible, so the rail is the chrome that
                // had to be lifted here; the video stays full-bleed.
                val keyboardVisible by CarKeyboardVisibility.state
                val playerImmersiveHide = screen is CarScreen.VideoPlayer && !playerControlsVisible
                // Browser immersive: same hide, extended, not
                // restructured — the rail goes only while
                // the browser is the active screen AND its
                // immersive state is set; leaving the browser
                // clears that state, so the bar returns.
                val browserImmersiveHide = screen is CarScreen.App && screen.id == "web" && browserImmersive
                val barVisible = !keyboardVisible && !playerImmersiveHide && !browserImmersiveHide && !mirrorActive
                // r87: the steering-side rail no longer yields — it owns
                // a reserved strip at the frame edge (see
                // NAV_BAR_SIDE_STRIP_WIDTH), so it can never overlap the
                // turn card, the widget column, or the media card, and it
                // stays on its side permanently. The side type and the
                // alignment/strip mappings stay as the seam a future
                // steering-side setting would flip; nothing today moves
                // the rail off its side.
                val sideBarSide = SideBarSide.DRIVER_SIDE
                // r97 graduation: the rail is the only app bar — it
                // shows whenever a bar shows, with no toggle gating it.
                // r99: in Meridian mode the bar takes one of two floating
                // forms by display shape — a side rail on wide displays, a
                // bottom pill on narrow or short ones (see meridianBarForm).
                // Outside Meridian mode the bar is always the side rail, as
                // before. The two forms are exclusive, so the side strip and
                // the bottom strip are never both nonzero.
                val mapTheme by CarSettings.mapTheme.collectAsState()
                val meridianBar = mapTheme == MapChrome.MERIDIAN
                val frame by CarEnvironment.frame.collectAsState()
                val barForm = if (meridianBar) {
                    meridianBarForm(frame.width, frame.height, frame.densityDpi)
                } else {
                    MeridianBarForm.RAIL
                }
                val sideBarVisible = barVisible && barForm == MeridianBarForm.RAIL
                val pillVisible = barVisible && meridianBar && barForm == MeridianBarForm.PILL
                // r87: the content inset that keeps everything beside the
                // rail. When the rail hides (keyboard, immersive, mirror)
                // the inset is empty, so content takes the full width.
                // r99: in Meridian pill form the inset moves to the bottom,
                // keeping content clear of the pill.
                val sideBarContentPadding = when {
                    sideBarVisible -> if (sideBarStripOnStart(sideBarSide)) {
                        PaddingValues(start = NAV_BAR_SIDE_STRIP_WIDTH)
                    } else {
                        PaddingValues(end = NAV_BAR_SIDE_STRIP_WIDTH)
                    }
                    pillVisible -> PaddingValues(bottom = NAV_BAR_PILL_STRIP_WIDTH)
                    else -> PaddingValues()
                }
                // r97 graduation: the rail is the only bar, so Home's
                // map surface extends under its strip exactly when it
                // shows. r99: in Meridian pill form the map surface
                // extends under the pill's bottom strip instead; no
                // other form ever reserves a bottom strip (the r87
                // dead-80dp-strip quirk stays gone by construction).
                val mapSurfaceStrip = if (sideBarVisible) NAV_BAR_SIDE_STRIP_WIDTH else 0.dp
                val mapSurfaceBottomStrip = if (pillVisible) NAV_BAR_PILL_STRIP_WIDTH else 0.dp
                Box(Modifier.fillMaxSize().padding(pad)) {
                Column(Modifier.fillMaxSize().padding(sideBarContentPadding)) {
                    if (!safetyWarningSeen) {
                        CarSafetyWizard(onDone = CarSettings::markSafetyWarningSeen)
                        return@Column
                    }
                    // Header navigation (breadcrumb trail) - off for now, kept to tweak later.
                    // BreadcrumbBar(navigator)
                    // r97: the content box is always the full frame —
                    // the rail is a scaffold sibling in its own strip,
                    // never a row below the content.
                    Box(Modifier.fillMaxSize()) {
                        // Home never leaves the composition; other screens cover it. Dropping it
                        // would tear down the map's surface, and coming back would then show black
                        // while the nav app redrew on a new one.
                        CarHome(mapSurfaceStrip, mapSurfaceBottomStrip)
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
                        CarToastOverlay(
                            Modifier.align(Alignment.BottomCenter),
                        )
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
                        AssistantOverlay(
                            Modifier.align(Alignment.BottomCenter),
                        )
                        // Phase 8 audit fix: the debug badge no longer
                        // floats over content at all — it lives in the
                        // rail (see CarSideBar), where it can cover nothing.
                    }
                }
                // r87: the steering-side rail draws in its own reserved strip
                // at the frame edge, below AppMenu (which stays over everything,
                // rail included) and below the brightness dim. The content
                // Column carries the matching inset, so nothing but the rail
                // draws in the strip. It follows barVisible exactly, so it
                // hides for the keyboard and the immersive player/browser
                // and mirror states, like the old bar did.
                if (sideBarVisible) {
                    CarSideBar(
                        navigator,
                        Modifier.align(sideBarAlignment(sideBarSide)),
                        meridian = meridianBar,
                    )
                }
                if (pillVisible) {
                    CarMeridianPill(
                        navigator,
                        Modifier.align(Alignment.BottomCenter)
                            .padding(bottom = NAV_BAR_SIDE_FLOAT),
                    )
                }
                // Over everything, rail included: a long press on an app, anywhere.
                AppMenu(navigator, rememberRunningApps(), contentPadding = sideBarContentPadding)
                IconPickerSheet(navigator, contentPadding = sideBarContentPadding)
                if (safetyWarningSeen) CarWhatsNew(contentPadding = sideBarContentPadding)
                // Brightness dim, over everything except the brightness sheet itself:
                // a black overlay with no input handling, so touches pass straight
                // through to the screen below and audio is untouched. The sheet is
                // drawn after it, so it stays undimmed and tappable.
                val brightness by CarSettings.brightness.collectAsState()
                // Player-local brightness (r22): while the player holds an
                // override it REPLACES the global dim (same overlay-alpha
                // mechanism, never stacked, never a window attribute); the
                // global setting is untouched and returns when the player
                // leaves. See PlayerBrightnessOverride in
                // VideoPlayerScreen.kt.
                val playerBrightness by PlayerBrightnessOverride.override.collectAsState()
                val effectiveBrightness = playerBrightness ?: brightness
                if (effectiveBrightness < 1f) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 1f - effectiveBrightness)))
                }
                if (BrightnessSheetVisibility.state.value) BrightnessSheet(contentPadding = sideBarContentPadding)
                if (QuickTilesVisibility.state.value) QuickTilesSheet(contentPadding = sideBarContentPadding, onOpenSettings = { QuickTilesVisibility.close(); navigator.settings() })
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
                // r54 (D3): dialog shape through the style rule, so Pitlane squares it.
                shape = gsButtonShape(RoundedCornerShape(28.dp)),
                modifier = Modifier.fillMaxWidth(),
                border = if (LocalOledDark.current) BorderStroke(0.5.dp, GsKit.hairline()) else null,
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
    // Audit fix (2026-10-25, F4): the only on-screen back path
    // was 32dp, below the 44dp car target floor. Bar and button
    // are 44dp now, with larger crumb touch padding.
    Row(
        Modifier.fillMaxWidth().height(44.dp).padding(start = 2.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GsIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", navigator::back, size = 44.dp, iconSize = 24.dp)
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
                    .padding(horizontal = 12.dp, vertical = 8.dp),
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

/** r97: the rail reuses the old bar's 64dp width and 4dp elevation;
 * the bar itself is gone. */
internal val NAV_BAR_HEIGHT = 64.dp

/**
 * r103: the rail's icon width — GsIconButton's default size, and the
 * size NavItem, the mic, the switcher, brightness, and quick settings
 * all land on (app bitmap icons are 52dp, centered on the same line).
 * The rail clock block takes this width so it shares the icon
 * column's centerline instead of spanning the full 64dp rail.
 */
internal val NAV_BAR_ITEM_WIDTH = 48.dp
internal val NAV_BAR_FLOAT_ELEVATION = 4.dp


/**
 * r94: the strip the steering-side rail reserves at the frame edge —
 * the rail's own width plus its floating margin on both sides, so
 * the content inset and the rail can never disagree. The scaffold
 * pads the content Column by this amount on the rail's side while
 * the rail is visible; the rail draws in the strip, outside the
 * padded content. Pure Dp math so it is unit-testable.
 */
internal val NAV_BAR_SIDE_FLOAT = 12.dp
internal val NAV_BAR_SIDE_STRIP_WIDTH = NAV_BAR_HEIGHT + NAV_BAR_SIDE_FLOAT + NAV_BAR_SIDE_FLOAT


/**
 * r99: the strip the Meridian bottom pill reserves at the frame's
 * bottom edge — the pill's own height plus its floating margin on
 * both sides, mirroring the side strip's math, so the content
 * inset and the pill can never disagree. Pure Dp math so it is
 * unit-testable.
 */
internal val NAV_BAR_PILL_STRIP_WIDTH = NAV_BAR_HEIGHT + NAV_BAR_SIDE_FLOAT + NAV_BAR_SIDE_FLOAT

/**
 * r105: the Meridian rail's top and bottom float — it matches the
 * collapsed map pane logo's top inset (COLLAPSED_PANE_TILE_FLOAT,
 * TemplateChrome.kt), the little map-icon button that expands the
 * pane, so the rail's top edge sits level with that tile's top and
 * the bottom mirrors it exactly. The rail still floats: it never
 * touches an edge or a corner. The themed rail is untouched — it
 * keeps its own 12dp margins on every side.
 */
internal val MERIDIAN_RAIL_VERTICAL_FLOAT = COLLAPSED_PANE_TILE_FLOAT

/**
 * r107: the gap between the clock block and the icon list in
 * Meridian mode only. The first icon's white highlight used to sit
 * flush under the clock block's bottom edge (the battery line),
 * nearly touching the battery percentage. 12dp matches the rail's
 * side float, so the separation reads as deliberate, not a rounding
 * gap. Themed mode (clock pinned bottom) is untouched.
 */
internal val MERIDIAN_RAIL_CLOCK_GAP = 12.dp


/**
 * r87: the steering-side app bar as an edge dock. The bottom bar's
 * app row, stood on its side in a reserved strip at the frame edge:
 * Home, Apps, assistant, the widgets popup button (same conditions
 * as the bar), pinned and open apps, brightness, quick settings,
 * the debug badge in debug builds, and a clock that still opens
 * the dashboard. The turn pill and the now-playing pill do NOT move
 * to the rail: on Home the turn card and the floating media card
 * already show that glance, and off Home they are absent while the
 * rail is on — a known tradeoff of this layout. The rail clock
 * shows time + battery only: the bar's temperature line does not
 * fit the 64dp column.
 * The rail takes the overlay bar's floating treatment exactly
 * (frosted surfaceContainerLow at 0.88, 4dp elevation, Pitlane
 * texture, OLED hairline) — and, since r94, it floats: 12dp margins
 * on every side, so it touches no screen edge, and its corners
 * follow the look-fed card rule (square under Pitlane, the
 * style's 24dp corner under Verve, the card's rounded corner
 * under every other style).
 * The icon list
 * scrolls so a long pin/running list can never push the clock off
 * the frame; the clock stays pinned to the bottom. The clock is
 * stacked, not squeezed: the old 52dp cell cut "9:53 AM" down to
 * "9:53 A…", so digits sit on one line, the AM/PM marker under
 * them, battery under that — every part always fully shown, in
 * any locale.
 */
@Composable
private fun CarSideBar(
    navigator: CarNavigator,
    modifier: Modifier = Modifier,
    meridian: Boolean = false,
) {
    val pinnedIds by CarSettings.pinnedApps.collectAsState()
    val entries = rememberAllEntries()
    val frame by CarEnvironment.frame.collectAsState()
    val running = rememberRunningApps()

    // r94: the rail floats again — full frame height minus 12dp
    // margins on every side, so it touches no screen edge, and its
    // corners follow the look-fed card rule (square under
    // Pitlane, the style's 24dp corner under Verve, the card's
    // rounded corner under every other style). The scaffold pads the content Column by the
    // strip width (rail + both margins), so this Surface is the only
    // thing that draws in the strip. Treatment is otherwise the
    // overlay bar's exactly (frosted surfaceContainerLow at 0.88,
    // 4dp elevation, Pitlane texture, OLED hairline). r99: in
    // Meridian mode the fill is Meridian dark instead of the theme
    // surface, the Pitlane texture is dropped for the photo's clean
    // dark, and the vertical float grows to 32dp top and bottom, so
    // the rail never spans the full height. r105: the vertical float
    // now matches the collapsed map pane tile's top inset (12dp) top
    // and bottom, so the rail stretches closer to both edges while
    // still floating.
    Surface(
        modifier = if (meridian) {
            modifier.padding(
                start = NAV_BAR_SIDE_FLOAT,
                top = MERIDIAN_RAIL_VERTICAL_FLOAT,
                end = NAV_BAR_SIDE_FLOAT,
                bottom = MERIDIAN_RAIL_VERTICAL_FLOAT,
            ).fillMaxHeight()
        } else {
            modifier.padding(NAV_BAR_SIDE_FLOAT).fillMaxHeight()
        },
        shape = cardShapeForLook(gsLook(), GsKit.CardShape),
        color = if (meridian) {
            Color(MapChromeColors.MERIDIAN_ETA).copy(alpha = FrostedChromeAlpha)
        } else {
            // r114: the rail surface takes the card half-wash
            // under Morph (same frosted surfaceContainerLow
            // family as FrostedPanelSurface); the Meridian
            // branch above keeps its photo fill untouched.
            morphCardColor(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = FrostedChromeAlpha))
        },
        shadowElevation = NAV_BAR_FLOAT_ELEVATION,
        border = if (LocalOledDark.current) BorderStroke(0.5.dp, GsKit.hairline()) else null,
    ) {
        Column(
            Modifier.width(NAV_BAR_HEIGHT).fillMaxHeight()
                .then(if (meridian) Modifier else Modifier.pitLanePanelTexture())
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // r99: in Meridian mode the clock rides at the top of the
            // rail, as in the photo; otherwise it stays pinned to the
            // bottom, as r87 set it.
            if (meridian) RailClockBlock(navigator, meridian = true)
            // r107: Meridian-only gap so the first icon's highlight
            // clears the clock block's battery line (see
            // MERIDIAN_RAIL_CLOCK_GAP); themed mode has no clock
            // above the list, so nothing changes there.
            if (meridian) Spacer(Modifier.height(MERIDIAN_RAIL_CLOCK_GAP))
            // The icon list scrolls so a long pin/running list can
            // never push the clock (or, in Meridian mode, the
            // switcher square) off the frame.
            // r134: the icon list fits the screen it is on. The car
            // preview runs this same composition at the preview
            // frame (800x480 by default) while the head unit runs it
            // at its own frame, and the old top-packed column left a
            // void between the last icon and the bottom-pinned
            // switcher that grew with the frame — the spacing read
            // differently on every screen. Now the list measures
            // itself: when the stack fits, it distributes across
            // the whole area (6dp floor, surplus shared evenly, so
            // gaps never shrink below the scroll mode's); when it
            // overflows, it keeps scrolling with fixed 6dp gaps.
            // The measurement resets whenever the frame or the item
            // set changes, so a new pin or a frame change
            // re-measures instead of clipping.
            Box(Modifier.weight(1f).fillMaxWidth()) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val areaHeight = maxHeight
                    val density = LocalDensity.current
                    // One composite key (a list compares structurally),
                    // so the measurement resets on any frame or item
                    // change without depending on a multi-key overload.
                    var distribute by remember(
                        listOf(areaHeight, entries, pinnedIds, running, navigator.current, meridian),
                    ) { mutableStateOf<Boolean?>(null) }
                    if (distribute == true) {
                        Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = RailDistributedArrangement,
                        ) {
                            RailIconList(
                                navigator, entries, pinnedIds, running, frame,
                                meridian = meridian, includeAppsItem = !meridian,
                            )
                        }
                    } else {
                        // r103: the scrolling column takes the full box width
                        // so its centered children actually center in the rail —
                        // without it the wrapped column lands at the box's
                        // top-start and every icon sits left.
                        Column(
                            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                                .onSizeChanged { size ->
                                    distribute = size.height <= with(density) { areaHeight.roundToPx() }
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(RAIL_ICON_MIN_GAP),
                        ) {
                            RailIconList(
                                navigator, entries, pinnedIds, running, frame,
                                meridian = meridian, includeAppsItem = !meridian,
                            )
                        }
                    }
                }
            }
            if (meridian) {
                MeridianSwitcherSquare(navigator)
            } else {
                RailClockBlock(navigator, meridian = false)
            }
        }
    }
}

/**
 * The bar's clock, digits only by design (r103): in a car the day
 * marker is nearly always redundant, and digits-only is the
 * narrowest form, so the block takes the icon width (48dp)
 * instead of spanning the full rail — clock and icons share the
 * centerline. Time on one line, battery under it, in any locale;
 * 24-hour locales render exactly as before. Tapping opens the
 * dashboard; an unread dot rides on the corner. In Meridian mode
 * the block never takes the white square the active app gets — it
 * wears a faint white wash when selected, plain dark otherwise,
 * with white texts throughout.
 */
@Composable
private fun RailClockBlock(navigator: CarNavigator, meridian: Boolean) {
    val battery by CarEnvironment.battery.collectAsState()
    val now by rememberNow()
    val unread by CarNotifications.unread.collectAsState()
    val clockSelected = navigator.current == CarScreen.Dashboard ||
        navigator.current is CarScreen.Notifications
    val clockTime = railClockDigits(Date(now))
    val dimColor = if (meridian) {
        Color.White.copy(alpha = MERIDIAN_DIM_ALPHA)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    GsIconBox(
        { navigator.dashboard() },
        Modifier.width(NAV_BAR_ITEM_WIDTH),
        colors = if (meridian) {
            GsColors(
                if (clockSelected) Color.White.copy(alpha = 0.12f) else Color.Transparent,
                Color.White,
            )
        } else {
            navColors(clockSelected)
        },
        latched = clockSelected,
    ) {
        Box {
            if (unread > 0) {
                // r103: 4dp, not 8dp — on the narrowed 48dp block
                // the old offset pushed the dot off the button's edge.
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-2).dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (meridian) Color.White else MaterialTheme.colorScheme.primary),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    clockTime,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (battery.percent >= 0) {
                        Text(
                            "${battery.percent}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = dimColor,
                            maxLines = 1,
                        )
                        if (battery.charging) {
                            ChargingGlyph(Modifier.size(13.dp), tint = dimColor)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The rail's scrollable icon list, shared by the r87 rail and the
 * r99 Meridian rail — the two bars never drift apart in what they
 * show. In Meridian mode the Apps button is left out: the
 * app-switcher square at the rail's bottom opens the grid instead.
 */
@Composable
private fun RailIconList(
    navigator: CarNavigator,
    entries: List<Entry>,
    pinnedIds: Set<String>,
    running: Map<String, RunningSlot>,
    frame: CarEnvironment.Frame,
    meridian: Boolean,
    includeAppsItem: Boolean,
) {
    val (shownPins, runningEntries) = rememberBarAppLists(entries, pinnedIds, running)
    NavHomeButton(navigator, entries, running, meridian)
    if (includeAppsItem) {
        NavItem(MediaIcons.Apps, navigator.current == CarScreen.Apps) { navigator.apps() }
    }
    NavAssistantIcon(meridian)
    if (navigator.current == CarScreen.Home) {
        NavWidgetsPopupButton(navigator, meridian)
    }

    // Pinned shortcuts, then whatever else is open right now. A pinned app that's also
    // open stays in its pinned spot, marked with a dot, instead of showing up twice.
    // The map app lives on the Home button, so it never gets a second icon here.
    if (shownPins.isNotEmpty() || runningEntries.isNotEmpty()) NavDividerVertical(meridian)
    shownPins.forEach { entry -> NavEntryIcon(entry, running, navigator, frame, meridian) }
    if (shownPins.isNotEmpty() && runningEntries.isNotEmpty()) NavDividerVertical(meridian)
    runningEntries.forEach { entry -> NavEntryIcon(entry, running, navigator, frame, meridian) }

    // Brightness, one tap to the slider sheet: the phone's brightness does
    // not reach the projected display, so this is the only brightness
    // control the driver has.
    GsIconButton(
        Icons.Filled.BrightnessMedium, "Brightness", BrightnessSheetVisibility::open,
        colors = meridianBarButtonColors(meridian),
    )
    // r70: quick settings, next to brightness — same button as the bar.
    GsIconButton(
        Icons.Filled.Tune, "Quick settings", QuickTilesVisibility::open,
        colors = meridianBarButtonColors(meridian),
    )
    if (app.autospace.BuildConfig.DEBUG) {
        DebugBuildBadge()
    }
}

/**
 * The bar's pinned and running app lists, computed once and shared
 * by the rail and the Meridian pill so the two bars never disagree:
 * pinned shortcuts first (minus the map app, which lives on the
 * Home button), then open apps that aren't pinned. Returns the
 * pinned list first, the running list second.
 */
@Composable
private fun rememberBarAppLists(
    entries: List<Entry>,
    pinnedIds: Set<String>,
    running: Map<String, RunningSlot>,
): Pair<List<Entry>, List<Entry>> {
    val pinnedEntries = remember(entries, pinnedIds) {
        pinnedIds.mapNotNull { id -> entries.find { it.componentId == id } }
    }
    val runningEntries = running.keys
        .filter { it !in pinnedIds && running[it] != RunningSlot.NAV }
        .mapNotNull { id -> entries.find { it.componentId == id } }
    val shownPins = pinnedEntries.filter { running[it.componentId] != RunningSlot.NAV }
    return shownPins to runningEntries
}

/**
 * r99: a plain Meridian bar button's colors — transparent fill, white
 * glyph — or the theme default when not Meridian. Used by the
 * brightness, quick-settings, and widget buttons, which carry no
 * selection state of their own.
 */
@Composable
private fun meridianBarButtonColors(meridian: Boolean): GsColors =
    if (meridian) GsColors(Color.Transparent, Color.White) else gsColors(GsTone.Neutral)

/**
 * r102: the app-switcher glyph at the bottom of the Meridian bar
 * (and the trailing end of the Meridian pill — one component serves
 * both): a 2x2 grid of four filled squares, opening the app grid.
 * It wears the bar's selection treatment (white square when the
 * grid is open, plain dark otherwise); the squares follow the
 * content color, so they read white on dark and dark on the white
 * square. Deliberately not a copy of the widgets button above it:
 * that glyph (Icons.Filled.Widgets) carries a diamond in its
 * top-right cell, while this grid is four equal squares.
 */
@Composable
private fun MeridianSwitcherSquare(navigator: CarNavigator) {
    val selected = navigator.current == CarScreen.Apps
    GsIconBox(
        { navigator.apps() },
        colors = navColors(selected, meridian = true),
        latched = selected,
    ) {
        // r134: the grid shrank to the rail's standard button-glyph
        // size (24dp, the GsIconButton icon size): the old 32dp
        // footprint of solid squares read far bigger than the 24dp
        // gear/tune/widgets glyphs beside it, because solid squares
        // fill most of their box while vector glyphs are thin
        // strokes. The button itself stays 48dp — the grid centers
        // inside the same 32dp content box, so the hit area and
        // the button rhythm do not change. Shared with the
        // Meridian pill, so the pill's switcher shrinks too.
        Column(
            Modifier.padding(NAV_ITEM_PADDING).size(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SWITCHER_GRID_GAP, Alignment.CenterVertically),
        ) {
            repeat(2) {
                Row(horizontalArrangement = Arrangement.spacedBy(SWITCHER_GRID_GAP)) {
                    repeat(2) {
                        Box(
                            Modifier.size(SWITCHER_SQUARE)
                                .background(
                                    LocalContentColor.current,
                                    RoundedCornerShape(2.dp),
                                ),
                        )
                    }
                }
            }
        }
    }
}

/** The bar's divider, laid flat for the Meridian pill. */
@Composable
private fun NavDividerHorizontal() {
    Box(
        Modifier
            .padding(horizontal = 10.dp)
            .width(1.dp)
            .height(28.dp)
            .background(Color.White.copy(alpha = 0.25f)),
    )
}

/**
 * r99: the Meridian bar's narrow/short-display form — a centered
 * floating pill at the bottom, the same dark #131429 fill as the
 * rail, not full-width. The compact clock rides at the pill's
 * start end, the app icons scroll horizontally if there are more
 * than fit, and the app-switcher square closes the trailing end.
 * The scaffold pads content by the pill strip (pill height plus
 * a 12dp float on each side), so the pill never covers content.
 * When the icons fit, the row centers them; when they don't, the
 * pill caps at the frame minus its side floats and scrolls.
 */
@Composable
private fun CarMeridianPill(navigator: CarNavigator, modifier: Modifier = Modifier) {
    val entries = rememberAllEntries()
    val pinnedIds by CarSettings.pinnedApps.collectAsState()
    val frame by CarEnvironment.frame.collectAsState()
    val running = rememberRunningApps()
    val battery by CarEnvironment.battery.collectAsState()
    val now by rememberNow()
    val unread by CarNotifications.unread.collectAsState()
    val (shownPins, runningEntries) = rememberBarAppLists(entries, pinnedIds, running)
    val clockSelected = navigator.current == CarScreen.Dashboard ||
        navigator.current is CarScreen.Notifications
    val clockTime = railClockDigits(Date(now))
    val dimColor = Color.White.copy(alpha = MERIDIAN_DIM_ALPHA)
    Surface(
        modifier = modifier.blueprintCardRing(cardShapeForLook(gsLook(), GsKit.CardShape)),
        shape = cardShapeForLook(gsLook(), GsKit.CardShape),
        color = Color(MapChromeColors.MERIDIAN_ETA).copy(alpha = FrostedChromeAlpha),
        shadowElevation = NAV_BAR_FLOAT_ELEVATION,
        border = if (LocalOledDark.current && gsLook().ringAlpha <= 0f) BorderStroke(0.5.dp, GsKit.hairline()) else null,
    ) {
        Row(
            Modifier
                .height(NAV_BAR_HEIGHT)
                .widthIn(max = with(LocalDensity.current) { frame.width.toDp() } - NAV_BAR_SIDE_FLOAT * 2)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            // Compact clock (r103): digits-only time on one line,
            // battery beside it, unread dot on the corner.
            GsIconBox(
                { navigator.dashboard() },
                colors = GsColors(
                    if (clockSelected) Color.White.copy(alpha = 0.12f) else Color.Transparent,
                    Color.White,
                ),
                latched = clockSelected,
            ) {
                Box {
                    if (unread > 0) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 8.dp, y = (-2).dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color.White),
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            clockTime,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        if (battery.percent >= 0) {
                            Text(
                                "${battery.percent}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = dimColor,
                                maxLines = 1,
                            )
                            if (battery.charging) {
                                ChargingGlyph(Modifier.size(13.dp), tint = dimColor)
                            }
                        }
                    }
                }
            }
            NavDividerHorizontal()
            NavHomeButton(navigator, entries, running, meridian = true)
            NavAssistantIcon(meridian = true)
            if (navigator.current == CarScreen.Home) {
                NavWidgetsPopupButton(navigator, meridian = true)
            }
            if (shownPins.isNotEmpty() || runningEntries.isNotEmpty()) NavDividerHorizontal()
            shownPins.forEach { entry -> NavEntryIcon(entry, running, navigator, frame, meridian = true) }
            if (shownPins.isNotEmpty() && runningEntries.isNotEmpty()) NavDividerHorizontal()
            runningEntries.forEach { entry -> NavEntryIcon(entry, running, navigator, frame, meridian = true) }
            NavDividerHorizontal()
            GsIconButton(
                Icons.Filled.BrightnessMedium, "Brightness", BrightnessSheetVisibility::open,
                colors = meridianBarButtonColors(true),
            )
            GsIconButton(
                Icons.Filled.Tune, "Quick settings", QuickTilesVisibility::open,
                colors = meridianBarButtonColors(true),
            )
            if (app.autospace.BuildConfig.DEBUG) {
                DebugBuildBadge()
            }
            NavDividerHorizontal()
            MeridianSwitcherSquare(navigator)
        }
    }
}

/** The bar's divider, stood on its side for the rail. */
@Composable
private fun NavDividerVertical(meridian: Boolean = false) {
    Box(
        Modifier
            .padding(vertical = 10.dp)
            .height(1.dp)
            .width(28.dp)
            .background(
                if (meridian) Color.White.copy(alpha = 0.25f)
                else MaterialTheme.colorScheme.outlineVariant,
            ),
    )
}

/**
 * r58 widget popup button, shared by the bar and the r74 rail so the
 * press logic cannot drift between them. Callers gate the call on Home
 * + widgets + floating (the button could show); the moving poll then
 * runs only while the button could show. Toggles the SAME persisted
 * dismissal flag as the column's "Hide widgets" X; while driving it
 * recalls the column for a glance instead.
 */
@Composable
private fun NavWidgetsPopupButton(navigator: CarNavigator, meridian: Boolean = false) {
    // CarMotion.moving is a plain value, not a flow — poll it like
    // CarHome does (1 s).
    val movingBar by produceState(CarMotion.moving) {
        while (true) { value = CarMotion.moving; delay(1_000) }
    }
    val dismissedBar by CarSettings.widgetsDismissed.collectAsState()
    GsIconButton(
        Icons.Filled.Widgets, "Widgets",
        colors = meridianBarButtonColors(meridian),
        onClick = {
            val drivingHidden = shouldHideWhileDriving(movingBar, WidgetDrivingGlance.recalled)
            when (widgetPopupPress(dismissedBar, drivingHidden)) {
                WidgetPopupPress.DISMISS -> CarSettings.setWidgetsDismissed(true)
                WidgetPopupPress.UNDISMISS -> CarSettings.setWidgetsDismissed(false)
                WidgetPopupPress.GLANCE -> WidgetDrivingGlance.recall()
                WidgetPopupPress.UNDISMISS_AND_GLANCE -> {
                    CarSettings.setWidgetsDismissed(false)
                    WidgetDrivingGlance.recall()
                }
            }
        },
    )
}

/**
 * Home: the map, with the player or lyrics beside it. While a map app runs, the button wears that
 * app's icon and a long press offers to close it, so the app needs no icon of its own on the bar.
 */
@Composable
private fun NavHomeButton(navigator: CarNavigator, entries: List<Entry>, running: Map<String, RunningSlot>, meridian: Boolean = false) {
    val selected = navigator.current == CarScreen.Home
    val navId = running.entries.firstOrNull { it.value == RunningSlot.NAV }?.key
    val navEntry = navId?.let { id -> entries.find { it.componentId == id } }
    GsIconBox(
        { navigator.home() },
        colors = navColors(selected, meridian),
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
    meridian: Boolean = false,
) {
    val id = entry.componentId ?: return
    val slot = running[id]
    Box(Modifier.padding(horizontal = 3.dp)) {
        NavAppIcon(
            entry,
            open = slot != null,
            showing = slot != null && slot.isShowing(navigator.current),
            meridian = meridian,
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
    meridian: Boolean = false,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    GsIconBox(
        onClick,
        Modifier.size(52.dp),
        colors = if (meridian) {
            // r99: the photo's white rounded square behind the
            // active app, dark glyph on it; inactive apps sit on
            // plain dark with a white glyph, in their own colors
            // where they carry a bitmap icon.
            if (showing) GsColors(Color.White.copy(alpha = NavBarItemAlpha), Color(MapChromeColors.MERIDIAN_ETA))
            else GsColors(Color.Transparent, Color.White)
        } else if (showing) GsColors(scheme.primary.copy(alpha = NavBarItemAlpha), scheme.onPrimary)
        else GsColors(scheme.surfaceVariant.copy(alpha = NavBarItemAlpha), scheme.onSurfaceVariant),
        latched = showing,
        onLongClick = onLongClick,
    ) {
        // r116: a bitmap icon wins over the glyph — TileIcon's rule, now shared here. The
        // Browser entry carries the Mariner circle, so the rail (and the Meridian pill, which
        // shares this path) shows the circle and never the magnifier on top of it. No other
        // entry carries both a bitmap and a glyph, so nothing else changes. The 8dp
        // rounded-square clip is a no-op on the circular bitmap: its corners are already
        // transparent, so the circle stays circular. The remember runs unconditionally so the
        // hook order never shifts when the entry in this slot changes.
        val context = LocalContext.current
        val circleRes = entry.builtIn?.circleIconRes
        val circle = remember(context, circleRes) {
            circleRes?.let { BitmapFactory.decodeResource(context.resources, it)?.asImageBitmap() }
        }
        val icon = entry.icon ?: circle
        if (icon != null) Image(icon, entry.label, Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)))
        else entry.builtIn?.let { Icon(it.glyph, entry.label, Modifier.size(30.dp)) }
        // Only running apps get the line along the bottom edge, the usual taskbar mark for it.
        if (open) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 2.dp)
                    .size(width = 12.dp, height = 3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (meridian) Color.White else scheme.primary),
            )
        }
    }
}

/** Every nav bar item is raised, so it reads as pressable; the current screen's takes the accent colour. r29: the selected item takes the scheme primary itself (was secondaryContainer) — the active tab is the one thing on the bar that should carry the accent. */
@Composable
private fun navColors(selected: Boolean, meridian: Boolean = false): GsColors {
    val s = MaterialTheme.colorScheme
    // r56: item fills sit one fixed offset above the bar's frost level, so buttons and
    // tiles always read slightly less transparent than the bar beneath them, at any
    // future float level. Content colors stay full-strength; only fills go translucent.
    if (meridian) {
        // r99: on the Meridian bar the selected item is the photo's
        // white square with a dark glyph; an unselected item is
        // plain dark with a white glyph.
        return if (selected) GsColors(Color.White.copy(alpha = NavBarItemAlpha), Color(MapChromeColors.MERIDIAN_ETA))
        else GsColors(Color.Transparent, Color.White)
    }
    return if (selected) GsColors(s.primary.copy(alpha = NavBarItemAlpha), s.onPrimary)
    else GsColors(s.surfaceContainerHighest.copy(alpha = NavBarItemAlpha), s.onSurface)
}

/**
 * Between Apps and whatever's actually open, not with the clock/battery group on the right - a
 * driver reaches for it right after deciding "not a tap for this", same gesture family as the
 * two buttons beside it.
 */
@Composable
private fun NavAssistantIcon(meridian: Boolean = false) {
    val state by CarAssistant.state.collectAsState()
    val active = state.phase != CarAssistant.Phase.IDLE
    GsIconBox({ CarAssistant.start() }, colors = navColors(active, meridian), latched = active) {
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
/**
 * Phase 10, perfecting OLED (supersedes Phase 9's grey OLED): OLED is
 * black-black — background AND every surface token #000000, plain and
 * dynamic alike — so it reads visibly different from Regular dark's
 * grey family instead of differing by background only. Separation
 * comes from frost (GsKit.brush() is a white-alpha gradient over black
 * in OLED mode, since a surface-colour gradient is invisible on black)
 * plus the 0.5dp hairline (LocalOledDark borders on kit cards/sheets),
 * NOT from grey fills. Dynamic accent and content colours are
 * retained exactly (primary/secondary/tertiary and their containers),
 * and text is lifted (onSurfaceVariant #C7C7CC) for contrast on black.
 * Video controls keep frost + theme colour in OLED (playerCapsule).
 */
private fun oledDynamicDark(context: android.content.Context): androidx.compose.material3.ColorScheme {
    val black = androidx.compose.ui.graphics.Color.Black
    return dynamicDarkColorScheme(context).copy(
        background = black,
        surface = black,
        surfaceVariant = black,
        surfaceContainerLowest = black,
        surfaceContainerLow = black,
        surfaceContainer = black,
        surfaceContainerHigh = black,
        surfaceContainerHighest = PitchSurfaceStepColor,
        surfaceBright = black,
        surfaceDim = black,
        surfaceTint = black,
        onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFC7C7CC),
        outline = PitchOutlineColor,
        outlineVariant = androidx.compose.ui.graphics.Color(0xFF48484A),
    )
}

/**
 * Night: the dedicated night-driving color scheme (Robert, 2026-10-25).
 * Pure-black background no matter which dark type is picked (Night implies
 * the true-black background); warm near-black surfaces, amber primary
 * (#FF9F0A, ~10:1 on black), warm-gray text (#D8D0C8 family); blue and
 * white accents suppressed, so nothing on screen throws blue glare at
 * night. LocalOledDark is provided for Night too, so kit hairlines and
 * the white-alpha frost gradient still separate panels on the black.
 */
/**
 * Pitlane (r23, dark only): the stock-car paired scheme — carbon-black
 * surfaces, white text, red primary, yellow highlight, NO blue anywhere
 * (that absence is what distinguishes it from Circuit). Contrast notes:
 * white on carbon #0A0A0A is ~19:1, so all body text is white; red
 * #E10600 is never body text on black (~4.0:1) — it is primary buttons
 * (white on red ~5.0:1) and accents; yellow #FFD200 on black is ~14:1.
 * DarkModeType does not alter it: carbon black is the scheme, not a
 * Regular/Pitch variant.
 */
private fun pitlaneScheme(): androidx.compose.material3.ColorScheme {
    val black = androidx.compose.ui.graphics.Color.Black
    val white = androidx.compose.ui.graphics.Color.White
    val carbon = androidx.compose.ui.graphics.Color(0xFF0A0A0A)
    val red = androidx.compose.ui.graphics.Color(0xFFE10600)
    val yellow = androidx.compose.ui.graphics.Color(0xFFFFD200)
    return androidx.compose.material3.darkColorScheme(
        primary = red,
        onPrimary = white,
        primaryContainer = androidx.compose.ui.graphics.Color(0xFF5C0A0A),
        onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFFFD7D7),
        secondary = yellow,
        onSecondary = black,
        secondaryContainer = androidx.compose.ui.graphics.Color(0xFF4A3D00),
        onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFFFF3B0),
        tertiary = yellow,
        onTertiary = black,
        error = androidx.compose.ui.graphics.Color(0xFFFF453A),
        onError = white,
        background = carbon,
        onBackground = white,
        surface = carbon,
        onSurface = white,
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFF1C1C1E),
        onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFD4D4D8),
        outline = androidx.compose.ui.graphics.Color(0xFF3A3A3C),
        outlineVariant = androidx.compose.ui.graphics.Color(0xFF262628),
        surfaceContainerLowest = black,
        surfaceContainerLow = carbon,
        surfaceContainer = androidx.compose.ui.graphics.Color(0xFF141414),
        surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF1C1C1E),
        surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF2C2C2E),
        surfaceDim = carbon,
        surfaceBright = androidx.compose.ui.graphics.Color(0xFF242426),
        surfaceTint = red,
        inverseSurface = white,
        inverseOnSurface = black,
        inversePrimary = androidx.compose.ui.graphics.Color(0xFFFF6B6B),
        scrim = black,
    )
}

/**
 * Verve (r48): the approved palette, mapped mechanically from
 * [VervePalette] — dark ink #16121F with vivid violet / hot coral /
 * sunny accents, light white with the deepened accents. One spec
 * correction lives in the palette itself: dark primary #8B5CF6 with
 * white text is 4.23:1, under the 4.5:1 selected-chip bar, so dark
 * primary is #8347E8 (5.21:1) per the spec's darken-the-accent rule.
 * Verve follows the mode control (light variant under Light/System-
 * light) and is overridden to NIGHT by Auto night like every scheme;
 * DarkModeType does not alter it (no Pitch variant — the kit's
 * Pitch-gated hairline on controls still applies, as it does for
 * every style under Pitch). Accents are for icons, selection and
 * controls, never body text except via the paired on* colors.
 */
private fun verveScheme(dark: Boolean): androidx.compose.material3.ColorScheme {
    return if (!dark) androidx.compose.material3.lightColorScheme(
        primary = androidx.compose.ui.graphics.Color(VervePalette.primaryLight),
        onPrimary = androidx.compose.ui.graphics.Color(VervePalette.onPrimaryLight),
        primaryContainer = androidx.compose.ui.graphics.Color(VervePalette.primaryContainerLight),
        onPrimaryContainer = androidx.compose.ui.graphics.Color(VervePalette.onPrimaryContainerLight),
        secondary = androidx.compose.ui.graphics.Color(VervePalette.secondaryLight),
        onSecondary = androidx.compose.ui.graphics.Color(VervePalette.onSecondaryLight),
        secondaryContainer = androidx.compose.ui.graphics.Color(VervePalette.secondaryContainerLight),
        onSecondaryContainer = androidx.compose.ui.graphics.Color(VervePalette.onSecondaryContainerLight),
        tertiary = androidx.compose.ui.graphics.Color(VervePalette.tertiaryLight),
        onTertiary = androidx.compose.ui.graphics.Color(VervePalette.onTertiaryLight),
        tertiaryContainer = androidx.compose.ui.graphics.Color(VervePalette.tertiaryContainerLight),
        onTertiaryContainer = androidx.compose.ui.graphics.Color(VervePalette.onTertiaryContainerLight),
        error = androidx.compose.ui.graphics.Color(0xFFBA1A1A),
        onError = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
        background = androidx.compose.ui.graphics.Color(VervePalette.backgroundLight),
        onBackground = androidx.compose.ui.graphics.Color(VervePalette.onSurfaceLight),
        surface = androidx.compose.ui.graphics.Color(VervePalette.backgroundLight),
        onSurface = androidx.compose.ui.graphics.Color(VervePalette.onSurfaceLight),
        surfaceVariant = androidx.compose.ui.graphics.Color(VervePalette.surfaceVariantLight),
        onSurfaceVariant = androidx.compose.ui.graphics.Color(VervePalette.onSurfaceVariantLight),
        outline = androidx.compose.ui.graphics.Color(VervePalette.outlineLight),
        outlineVariant = androidx.compose.ui.graphics.Color(VervePalette.outlineVariantLight),
        surfaceContainerLowest = androidx.compose.ui.graphics.Color(VervePalette.backgroundLight),
        surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFFF6F3FC),
        surfaceContainer = androidx.compose.ui.graphics.Color(0xFFEFEAF8),
        surfaceContainerHigh = androidx.compose.ui.graphics.Color(VervePalette.surfaceVariantLight),
        surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFFE2DCF0),
        surfaceDim = androidx.compose.ui.graphics.Color(0xFFD8D0E8),
        surfaceBright = androidx.compose.ui.graphics.Color(VervePalette.backgroundLight),
        surfaceTint = androidx.compose.ui.graphics.Color(VervePalette.primaryLight),
        inverseSurface = androidx.compose.ui.graphics.Color(VervePalette.onSurfaceLight),
        inverseOnSurface = androidx.compose.ui.graphics.Color(VervePalette.onSurfaceDark),
        inversePrimary = androidx.compose.ui.graphics.Color(VervePalette.primaryDark),
        scrim = androidx.compose.ui.graphics.Color(0xFF000000),
    ) else androidx.compose.material3.darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(VervePalette.primaryDark),
        onPrimary = androidx.compose.ui.graphics.Color(VervePalette.onPrimaryDark),
        primaryContainer = androidx.compose.ui.graphics.Color(VervePalette.primaryContainerDark),
        onPrimaryContainer = androidx.compose.ui.graphics.Color(VervePalette.onPrimaryContainerDark),
        secondary = androidx.compose.ui.graphics.Color(VervePalette.secondaryDark),
        onSecondary = androidx.compose.ui.graphics.Color(VervePalette.onSecondaryDark),
        secondaryContainer = androidx.compose.ui.graphics.Color(VervePalette.secondaryContainerDark),
        onSecondaryContainer = androidx.compose.ui.graphics.Color(VervePalette.onSecondaryContainerDark),
        tertiary = androidx.compose.ui.graphics.Color(VervePalette.tertiaryDark),
        onTertiary = androidx.compose.ui.graphics.Color(VervePalette.onTertiaryDark),
        tertiaryContainer = androidx.compose.ui.graphics.Color(VervePalette.tertiaryContainerDark),
        onTertiaryContainer = androidx.compose.ui.graphics.Color(VervePalette.onTertiaryContainerDark),
        error = androidx.compose.ui.graphics.Color(0xFFFF453A),
        onError = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
        background = androidx.compose.ui.graphics.Color(VervePalette.backgroundDark),
        onBackground = androidx.compose.ui.graphics.Color(VervePalette.onSurfaceDark),
        surface = androidx.compose.ui.graphics.Color(VervePalette.backgroundDark),
        onSurface = androidx.compose.ui.graphics.Color(VervePalette.onSurfaceDark),
        surfaceVariant = androidx.compose.ui.graphics.Color(VervePalette.surfaceVariantDark),
        onSurfaceVariant = androidx.compose.ui.graphics.Color(VervePalette.onSurfaceVariantDark),
        outline = androidx.compose.ui.graphics.Color(VervePalette.outlineDark),
        outlineVariant = androidx.compose.ui.graphics.Color(VervePalette.outlineVariantDark),
        surfaceContainerLowest = androidx.compose.ui.graphics.Color(0xFF0D0A16),
        surfaceContainerLow = androidx.compose.ui.graphics.Color(VervePalette.backgroundDark),
        surfaceContainer = androidx.compose.ui.graphics.Color(0xFF1E1930),
        surfaceContainerHigh = androidx.compose.ui.graphics.Color(VervePalette.surfaceVariantDark),
        surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF372E52),
        surfaceDim = androidx.compose.ui.graphics.Color(VervePalette.backgroundDark),
        surfaceBright = androidx.compose.ui.graphics.Color(0xFF2E2745),
        surfaceTint = androidx.compose.ui.graphics.Color(VervePalette.primaryDark),
        inverseSurface = androidx.compose.ui.graphics.Color(VervePalette.onSurfaceDark),
        inverseOnSurface = androidx.compose.ui.graphics.Color(VervePalette.onSurfaceLight),
        inversePrimary = androidx.compose.ui.graphics.Color(VervePalette.primaryLight),
        scrim = androidx.compose.ui.graphics.Color(0xFF000000),
    )
}

/**
 * Horizon (r79, Verve's sibling): deep navy dark (#0B1526 bg,
 * #E9EEF7 text, blue #1E6FD9 primary at 4.85:1 white) and sky-tinted
 * light (#F4F8FF bg, #16233A text, blue #1D6FE0 primary at 4.77:1
 * white), with a mint secondary and a green tertiary. The dark
 * accents take dark text (mint on navy 11.62:1, green on navy
 * 7.88:1); the light accents are deepened per Verve's pattern
 * (mint #177A52, green #1E7A45, both over 4.5:1 white). Horizon
 * follows the mode control (light variant under Light/System-
 * light) and is overridden to NIGHT by Auto night like every scheme;
 * DarkModeType does not alter it (no Pitch variant — the kit's
 * Pitch-gated hairline on controls still applies, as it does for
 * every style under Pitch). Accents are for icons, selection and
 * controls, never body text except via the paired on* colors.
 */
private fun horizonScheme(dark: Boolean): androidx.compose.material3.ColorScheme {
    return if (!dark) androidx.compose.material3.lightColorScheme(
        primary = androidx.compose.ui.graphics.Color(HorizonPalette.primaryLight),
        onPrimary = androidx.compose.ui.graphics.Color(HorizonPalette.onPrimaryLight),
        primaryContainer = androidx.compose.ui.graphics.Color(HorizonPalette.primaryContainerLight),
        onPrimaryContainer = androidx.compose.ui.graphics.Color(HorizonPalette.onPrimaryContainerLight),
        secondary = androidx.compose.ui.graphics.Color(HorizonPalette.secondaryLight),
        onSecondary = androidx.compose.ui.graphics.Color(HorizonPalette.onSecondaryLight),
        secondaryContainer = androidx.compose.ui.graphics.Color(HorizonPalette.secondaryContainerLight),
        onSecondaryContainer = androidx.compose.ui.graphics.Color(HorizonPalette.onSecondaryContainerLight),
        tertiary = androidx.compose.ui.graphics.Color(HorizonPalette.tertiaryLight),
        onTertiary = androidx.compose.ui.graphics.Color(HorizonPalette.onTertiaryLight),
        tertiaryContainer = androidx.compose.ui.graphics.Color(HorizonPalette.tertiaryContainerLight),
        onTertiaryContainer = androidx.compose.ui.graphics.Color(HorizonPalette.onTertiaryContainerLight),
        error = androidx.compose.ui.graphics.Color(0xFFBA1A1A),
        onError = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
        background = androidx.compose.ui.graphics.Color(HorizonPalette.backgroundLight),
        onBackground = androidx.compose.ui.graphics.Color(HorizonPalette.onSurfaceLight),
        surface = androidx.compose.ui.graphics.Color(HorizonPalette.backgroundLight),
        onSurface = androidx.compose.ui.graphics.Color(HorizonPalette.onSurfaceLight),
        surfaceVariant = androidx.compose.ui.graphics.Color(HorizonPalette.surfaceVariantLight),
        onSurfaceVariant = androidx.compose.ui.graphics.Color(HorizonPalette.onSurfaceVariantLight),
        outline = androidx.compose.ui.graphics.Color(HorizonPalette.outlineLight),
        outlineVariant = androidx.compose.ui.graphics.Color(HorizonPalette.outlineVariantLight),
        surfaceContainerLowest = androidx.compose.ui.graphics.Color(HorizonPalette.backgroundLight),
        surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFFEAF1FC),
        surfaceContainer = androidx.compose.ui.graphics.Color(0xFFDFE9F6),
        surfaceContainerHigh = androidx.compose.ui.graphics.Color(HorizonPalette.surfaceVariantLight),
        surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFFD5E0F0),
        surfaceDim = androidx.compose.ui.graphics.Color(0xFFCBD8EA),
        surfaceBright = androidx.compose.ui.graphics.Color(HorizonPalette.backgroundLight),
        surfaceTint = androidx.compose.ui.graphics.Color(HorizonPalette.primaryLight),
        inverseSurface = androidx.compose.ui.graphics.Color(HorizonPalette.onSurfaceLight),
        inverseOnSurface = androidx.compose.ui.graphics.Color(HorizonPalette.onSurfaceDark),
        inversePrimary = androidx.compose.ui.graphics.Color(HorizonPalette.primaryDark),
        scrim = androidx.compose.ui.graphics.Color(0xFF000000),
    ) else androidx.compose.material3.darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(HorizonPalette.primaryDark),
        onPrimary = androidx.compose.ui.graphics.Color(HorizonPalette.onPrimaryDark),
        primaryContainer = androidx.compose.ui.graphics.Color(HorizonPalette.primaryContainerDark),
        onPrimaryContainer = androidx.compose.ui.graphics.Color(HorizonPalette.onPrimaryContainerDark),
        secondary = androidx.compose.ui.graphics.Color(HorizonPalette.secondaryDark),
        onSecondary = androidx.compose.ui.graphics.Color(HorizonPalette.onSecondaryDark),
        secondaryContainer = androidx.compose.ui.graphics.Color(HorizonPalette.secondaryContainerDark),
        onSecondaryContainer = androidx.compose.ui.graphics.Color(HorizonPalette.onSecondaryContainerDark),
        tertiary = androidx.compose.ui.graphics.Color(HorizonPalette.tertiaryDark),
        onTertiary = androidx.compose.ui.graphics.Color(HorizonPalette.onTertiaryDark),
        tertiaryContainer = androidx.compose.ui.graphics.Color(HorizonPalette.tertiaryContainerDark),
        onTertiaryContainer = androidx.compose.ui.graphics.Color(HorizonPalette.onTertiaryContainerDark),
        error = androidx.compose.ui.graphics.Color(0xFFFF453A),
        onError = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
        background = androidx.compose.ui.graphics.Color(HorizonPalette.backgroundDark),
        onBackground = androidx.compose.ui.graphics.Color(HorizonPalette.onSurfaceDark),
        surface = androidx.compose.ui.graphics.Color(HorizonPalette.backgroundDark),
        onSurface = androidx.compose.ui.graphics.Color(HorizonPalette.onSurfaceDark),
        surfaceVariant = androidx.compose.ui.graphics.Color(HorizonPalette.surfaceVariantDark),
        onSurfaceVariant = androidx.compose.ui.graphics.Color(HorizonPalette.onSurfaceVariantDark),
        outline = androidx.compose.ui.graphics.Color(HorizonPalette.outlineDark),
        outlineVariant = androidx.compose.ui.graphics.Color(HorizonPalette.outlineVariantDark),
        surfaceContainerLowest = androidx.compose.ui.graphics.Color(0xFF060D1B),
        surfaceContainerLow = androidx.compose.ui.graphics.Color(HorizonPalette.backgroundDark),
        surfaceContainer = androidx.compose.ui.graphics.Color(0xFF16233C),
        surfaceContainerHigh = androidx.compose.ui.graphics.Color(HorizonPalette.surfaceVariantDark),
        surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF2A3D5E),
        surfaceDim = androidx.compose.ui.graphics.Color(HorizonPalette.backgroundDark),
        surfaceBright = androidx.compose.ui.graphics.Color(0xFF24344F),
        surfaceTint = androidx.compose.ui.graphics.Color(HorizonPalette.primaryDark),
        inverseSurface = androidx.compose.ui.graphics.Color(HorizonPalette.onSurfaceDark),
        inverseOnSurface = androidx.compose.ui.graphics.Color(HorizonPalette.onSurfaceLight),
        inversePrimary = androidx.compose.ui.graphics.Color(HorizonPalette.primaryLight),
        scrim = androidx.compose.ui.graphics.Color(0xFF000000),
    )
}

/**
 * Meridian (r107): the Meridian chrome's own color scheme, forced
 * dark like Night and Pitlane (see schemeForcesDark) — there is NO
 * light variant in this round, and the light MAP is unaffected:
 * map tiles are Vela-drawn and AutoSpace never touches a map pixel,
 * so a light map under dark Meridian chrome needs no work and no
 * flag. The palette extends the four photo-picked Meridian colors
 * (MapChromeColors — sampled from a reference rendering photo, NOT
 * Google's official tokens, which are unpublished): the turn-card
 * teal #3E6F74 as primary (white on it 5.63:1), the strip band
 * #335C5E as primaryContainer (white on it 7.41:1), the near-black
 * navy #131429 as background/surface (white on it 18.11:1), and the
 * sage minutes green #789A75 as secondary — a LARGE-ACCENT color
 * only (navy on it 5.76:1, but it fails as small text on white at
 * 3.14:1 and the teal fails as small text on navy at 3.22:1, so
 * neither is ever body text on the other's surface). Surfaces step
 * up from the navy in small increments (#1B1C33, #232444, #2A2C50,
 * #33355E; white on the highest step 11.59:1). onSurfaceVariant is
 * white at MERIDIAN_DIM_ALPHA 0.75 (blended ~10.43:1). Tertiary is
 * plain white with navy text (no new color introduced); outline is
 * white at low alpha over navy (a border, not text). Error follows
 * the app's existing dark error red. Auto night still overrides to
 * NIGHT like every scheme; DarkModeType does not alter it.
 */
private fun meridianScheme(): androidx.compose.material3.ColorScheme {
    return androidx.compose.material3.darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(MeridianPalette.primary),
        onPrimary = androidx.compose.ui.graphics.Color(MeridianPalette.onPrimary),
        primaryContainer = androidx.compose.ui.graphics.Color(MeridianPalette.primaryContainer),
        onPrimaryContainer = androidx.compose.ui.graphics.Color(MeridianPalette.onPrimaryContainer),
        secondary = androidx.compose.ui.graphics.Color(MeridianPalette.secondary),
        onSecondary = androidx.compose.ui.graphics.Color(MeridianPalette.onSecondary),
        secondaryContainer = androidx.compose.ui.graphics.Color(MeridianPalette.secondaryContainer),
        onSecondaryContainer = androidx.compose.ui.graphics.Color(MeridianPalette.onSecondaryContainer),
        tertiary = androidx.compose.ui.graphics.Color(MeridianPalette.tertiary),
        onTertiary = androidx.compose.ui.graphics.Color(MeridianPalette.onTertiary),
        error = androidx.compose.ui.graphics.Color(MeridianPalette.error),
        onError = androidx.compose.ui.graphics.Color(MeridianPalette.onError),
        background = androidx.compose.ui.graphics.Color(MeridianPalette.background),
        onBackground = androidx.compose.ui.graphics.Color(MeridianPalette.onSurface),
        surface = androidx.compose.ui.graphics.Color(MeridianPalette.background),
        onSurface = androidx.compose.ui.graphics.Color(MeridianPalette.onSurface),
        surfaceVariant = androidx.compose.ui.graphics.Color(MeridianPalette.surfaceVariant),
        onSurfaceVariant = androidx.compose.ui.graphics.Color(MeridianPalette.onSurfaceVariant),
        outline = androidx.compose.ui.graphics.Color(MeridianPalette.outline),
        outlineVariant = androidx.compose.ui.graphics.Color(MeridianPalette.outlineVariant),
        surfaceContainerLowest = androidx.compose.ui.graphics.Color(MeridianPalette.surfaceContainerLowest),
        surfaceContainerLow = androidx.compose.ui.graphics.Color(MeridianPalette.surfaceContainerLow),
        surfaceContainer = androidx.compose.ui.graphics.Color(MeridianPalette.surfaceContainer),
        surfaceContainerHigh = androidx.compose.ui.graphics.Color(MeridianPalette.surfaceContainerHigh),
        surfaceContainerHighest = androidx.compose.ui.graphics.Color(MeridianPalette.surfaceContainerHighest),
        surfaceDim = androidx.compose.ui.graphics.Color(MeridianPalette.surfaceDim),
        surfaceBright = androidx.compose.ui.graphics.Color(MeridianPalette.surfaceBright),
        surfaceTint = androidx.compose.ui.graphics.Color(MeridianPalette.surfaceTint),
        inverseSurface = androidx.compose.ui.graphics.Color(MeridianPalette.inverseSurface),
        inverseOnSurface = androidx.compose.ui.graphics.Color(MeridianPalette.inverseOnSurface),
        inversePrimary = androidx.compose.ui.graphics.Color(MeridianPalette.inversePrimary),
        scrim = androidx.compose.ui.graphics.Color(MeridianPalette.scrim),
    )
}

/**
 * Circuit (r23, final name decided 2026-10-25): Robert's confirmed palette,
 * exactly — dark background #000000, primary #007AC2, secondary #E4002B,
 * tertiary #FFD659; light variant white background with the same three
 * accents. Blue primary is what distinguishes it from Pitlane. Contrast:
 * white on #007AC2 and #007AC2 on black/white are both ~4.6:1 (AA);
 * tertiary #FFD659 always takes black text, never white.
 */
private fun circuitScheme(dark: Boolean): androidx.compose.material3.ColorScheme {
    val black = androidx.compose.ui.graphics.Color.Black
    val white = androidx.compose.ui.graphics.Color.White
    val blue = androidx.compose.ui.graphics.Color(0xFF007AC2)
    val red = androidx.compose.ui.graphics.Color(0xFFE4002B)
    val yellow = androidx.compose.ui.graphics.Color(0xFFFFD659)
    return if (!dark) androidx.compose.material3.lightColorScheme(
        primary = blue,
        onPrimary = white,
        primaryContainer = androidx.compose.ui.graphics.Color(0xFFD0E8FF),
        onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF001D36),
        secondary = red,
        onSecondary = white,
        secondaryContainer = androidx.compose.ui.graphics.Color(0xFFFFD7DE),
        onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF5C0012),
        tertiary = yellow,
        onTertiary = black,
        error = androidx.compose.ui.graphics.Color(0xFFFF3B30),
        onError = white,
        background = white,
        onBackground = black,
        surface = white,
        onSurface = black,
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFFF2F2F7),
        onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF6E6E73),
        outline = androidx.compose.ui.graphics.Color(0xFFC6C6C8),
        outlineVariant = androidx.compose.ui.graphics.Color(0xFFE5E5EA),
        surfaceContainerLowest = white,
        surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFFF2F2F7),
        surfaceContainer = white,
        surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFFF2F2F7),
        surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFFE5E5EA),
        surfaceDim = androidx.compose.ui.graphics.Color(0xFFF2F2F7),
        surfaceBright = white,
        surfaceTint = blue,
        inverseSurface = androidx.compose.ui.graphics.Color(0xFF1C1C1E),
        inverseOnSurface = white,
        inversePrimary = blue,
        scrim = black,
    ) else androidx.compose.material3.darkColorScheme(
        primary = blue,
        onPrimary = white,
        primaryContainer = androidx.compose.ui.graphics.Color(0xFF003A5C),
        onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFD0E8FF),
        secondary = red,
        onSecondary = white,
        secondaryContainer = androidx.compose.ui.graphics.Color(0xFF5C0012),
        onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFFFD7DE),
        tertiary = yellow,
        onTertiary = black,
        error = androidx.compose.ui.graphics.Color(0xFFFF453A),
        onError = white,
        background = black,
        onBackground = white,
        surface = black,
        onSurface = white,
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFF1A1A1A),
        onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFC7C7CC),
        outline = androidx.compose.ui.graphics.Color(0xFF38383A),
        outlineVariant = androidx.compose.ui.graphics.Color(0xFF242426),
        surfaceContainerLowest = black,
        surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFF0A0A0A),
        surfaceContainer = androidx.compose.ui.graphics.Color(0xFF121212),
        surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF1A1A1A),
        surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF222222),
        surfaceDim = black,
        surfaceBright = androidx.compose.ui.graphics.Color(0xFF1A1A1A),
        surfaceTint = blue,
        inverseSurface = white,
        inverseOnSurface = black,
        inversePrimary = androidx.compose.ui.graphics.Color(0xFF4DB2E8),
        scrim = black,
    )
}

/**
 * Heritage (r23): vintage motorsport — deep racing green + cream +
 * brass/gold, dark and light variants. Contrast notes: cream #F5EEDD on
 * racing green #004225 is ~10:1, so dark body text is cream; brass
 * #C9A227 on that green is ~4.8:1, so it is safe as an accent there but
 * is NEVER body text on cream — on cream it is only ~2.1:1 — which is
 * why the light variant darkens the brass primary to #6E5613 (~5.4:1
 * as text on cream, white on it ~6.2:1) and keeps body text deep green
 * on cream (~11:1). Gold is accent-only in both variants, never body
 * text on cream.
 */
private fun heritageScheme(dark: Boolean): androidx.compose.material3.ColorScheme {
    val black = androidx.compose.ui.graphics.Color.Black
    val white = androidx.compose.ui.graphics.Color.White
    val green = androidx.compose.ui.graphics.Color(0xFF004225)
    val deepGreen = androidx.compose.ui.graphics.Color(0xFF0A3527)
    val cream = androidx.compose.ui.graphics.Color(0xFFF5EEDD)
    val brass = androidx.compose.ui.graphics.Color(0xFFC9A227)
    return if (!dark) androidx.compose.material3.lightColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF6E5613),
        onPrimary = white,
        primaryContainer = androidx.compose.ui.graphics.Color(0xFFEFE3B8),
        onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF3D3208),
        secondary = green,
        onSecondary = cream,
        secondaryContainer = androidx.compose.ui.graphics.Color(0xFFD3E4D8),
        onSecondaryContainer = deepGreen,
        tertiary = androidx.compose.ui.graphics.Color(0xFF8A6D1B),
        onTertiary = white,
        error = androidx.compose.ui.graphics.Color(0xFFB3261E),
        onError = white,
        background = androidx.compose.ui.graphics.Color(0xFFF7F1DE),
        onBackground = deepGreen,
        surface = androidx.compose.ui.graphics.Color(0xFFF7F1DE),
        onSurface = deepGreen,
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFFEFE8D0),
        onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF3D4A42),
        outline = androidx.compose.ui.graphics.Color(0xFFC9BFA3),
        outlineVariant = androidx.compose.ui.graphics.Color(0xFFE5DCC2),
        surfaceContainerLowest = white,
        surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFFF7F1DE),
        surfaceContainer = androidx.compose.ui.graphics.Color(0xFFF7F1DE),
        surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFFEFE8D0),
        surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFFE5DCC2),
        surfaceDim = androidx.compose.ui.graphics.Color(0xFFEFE8D0),
        surfaceBright = white,
        surfaceTint = androidx.compose.ui.graphics.Color(0xFF8A6D1B),
        inverseSurface = deepGreen,
        inverseOnSurface = cream,
        inversePrimary = brass,
        scrim = black,
    ) else androidx.compose.material3.darkColorScheme(
        primary = brass,
        onPrimary = black,
        primaryContainer = androidx.compose.ui.graphics.Color(0xFF3D3208),
        onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFF0DFA8),
        secondary = cream,
        onSecondary = black,
        secondaryContainer = androidx.compose.ui.graphics.Color(0xFF1E5440),
        onSecondaryContainer = cream,
        tertiary = androidx.compose.ui.graphics.Color(0xFFE6C87A),
        onTertiary = black,
        error = androidx.compose.ui.graphics.Color(0xFFFF8A7A),
        onError = black,
        background = green,
        onBackground = cream,
        surface = green,
        onSurface = cream,
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFF0A4A30),
        onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFD8E8DC),
        outline = androidx.compose.ui.graphics.Color(0xFF2E6B52),
        outlineVariant = androidx.compose.ui.graphics.Color(0xFF1E5440),
        surfaceContainerLowest = androidx.compose.ui.graphics.Color(0xFF00331C),
        surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFF083A24),
        surfaceContainer = androidx.compose.ui.graphics.Color(0xFF0A4A30),
        surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF0E5A3C),
        surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF125A40),
        surfaceDim = androidx.compose.ui.graphics.Color(0xFF00331C),
        surfaceBright = androidx.compose.ui.graphics.Color(0xFF0E5A3C),
        surfaceTint = brass,
        inverseSurface = cream,
        inverseOnSurface = deepGreen,
        inversePrimary = androidx.compose.ui.graphics.Color(0xFF8A6D1B),
        scrim = black,
    )
}

private fun nightColorScheme(): androidx.compose.material3.ColorScheme {
    val black = androidx.compose.ui.graphics.Color.Black
    val amber = androidx.compose.ui.graphics.Color(0xFFFF9F0A)
    val amberLight = androidx.compose.ui.graphics.Color(0xFFFFB340)
    val warmText = androidx.compose.ui.graphics.Color(0xFFF2EBE0)
    val warmDim = androidx.compose.ui.graphics.Color(0xFFD8D0C8)
    return androidx.compose.material3.darkColorScheme(
        primary = amber,
        onPrimary = black,
        primaryContainer = androidx.compose.ui.graphics.Color(0xFF5C3A00),
        onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFFFDCA8),
        secondary = amberLight,
        onSecondary = black,
        secondaryContainer = androidx.compose.ui.graphics.Color(0xFF4A3200),
        onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFFFDCA8),
        tertiary = warmDim,
        onTertiary = black,
        error = androidx.compose.ui.graphics.Color(0xFFFF8A7A),
        onError = black,
        background = black,
        onBackground = warmText,
        surface = black,
        onSurface = warmText,
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFF1E1812),
        onSurfaceVariant = warmDim,
        outline = androidx.compose.ui.graphics.Color(0xFF5C5348),
        outlineVariant = androidx.compose.ui.graphics.Color(0xFF3A332B),
        surfaceContainerLowest = androidx.compose.ui.graphics.Color(0xFF0A0806),
        surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFF14100C),
        surfaceContainer = androidx.compose.ui.graphics.Color(0xFF1E1812),
        surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF2A211A),
        surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF33291F),
        surfaceDim = black,
        surfaceBright = androidx.compose.ui.graphics.Color(0xFF2A211A),
        surfaceTint = amber,
        inverseSurface = androidx.compose.ui.graphics.Color(0xFFE8E0D2),
        inverseOnSurface = androidx.compose.ui.graphics.Color(0xFF1A140E),
        inversePrimary = androidx.compose.ui.graphics.Color(0xFF8A5200),
        scrim = black,
    )
}

/**
 * The shared plain scheme (Notscape Phase A tokens, car-adapted): since the
 * 2026-10-24 22:32 theme correction this is Light/Dark for every style, not
 * just Frost — see CarUi above for the six-way mapping.
 * neutral system backgrounds, label black/white, secondary label ~60%, accent
 * only on primary/selected controls (system blue). Deliberately NOT the
 * wallpaper-dynamic scheme: dynamic colour is what turned Frost tiles peach.
 * [oled] picks the dark surface family (Phase 10, perfecting Phase 9):
 * OLED = background AND all surfaces pure #000000, plain and dynamic
 * alike, so OLED reads black against Regular's grey family (Phase 9's
 * grey #1C1C1E/#2C2C2E OLED surfaces read no different from Regular
 * under a dynamic theme). Separation is by frost (a white-alpha
 * gradient over black, see GsKit.brush()) plus the 0.5dp hairline,
 * with onSurfaceVariant lifted to #C7C7CC for contrast on black;
 * Regular = dark-grey backgrounds, no pure black anywhere.
 */
/**
 * Pitch (r33, audit #3): Pitch-gated values. PitchOutline lifts the Pitch
 * outline token (#38383A -> #6E6E73, hairline 1.20:1 -> ~1.63:1 blended at
 * 0.45 alpha over black); PitchSurfaceStep is the single near-black step
 * token (surfaceContainerHighest in Pitch only, ~1.06:1 vs black — a whisper
 * step that reads on OLED because black pixels are fully off). Used ONLY by
 * the Pitch (OLED) branches below; Regular dark, light, Night, Pitlane,
 * Circuit, Heritage keep their own tokens. Stored DarkModeType.OLED is
 * untouched; only these display values change.
 */
internal val PitchOutlineColor = androidx.compose.ui.graphics.Color(0xFF6E6E73)
internal val PitchSurfaceStepColor = androidx.compose.ui.graphics.Color(0xFF0A0A0C)

private fun neutralFrostScheme(dark: Boolean, oled: Boolean = true): androidx.compose.material3.ColorScheme {
    val white = androidx.compose.ui.graphics.Color.White
    val black = androidx.compose.ui.graphics.Color.Black
    return if (!dark) androidx.compose.material3.lightColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF007AFF),
        onPrimary = black,
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
        onPrimary = black,
        primaryContainer = androidx.compose.ui.graphics.Color(0xFF003A6B),
        onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFD7E9FF),
        secondary = androidx.compose.ui.graphics.Color(0xFFAEAEB2),
        onSecondary = black,
        background = black,
        onBackground = white,
        surface = black,
        onSurface = white,
        surfaceVariant = black,
        onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFC7C7CC),
        surfaceContainerLowest = black,
        surfaceContainerLow = black,
        surfaceContainer = black,
        surfaceContainerHigh = black,
        surfaceContainerHighest = PitchSurfaceStepColor,
        surfaceBright = black,
        surfaceDim = black,
        outline = PitchOutlineColor,
        outlineVariant = androidx.compose.ui.graphics.Color(0xFF48484A),
        error = androidx.compose.ui.graphics.Color(0xFFFF453A),
        onError = white,
        surfaceTint = black,
    ) else androidx.compose.material3.darkColorScheme(
        // Regular dark: the same tokens, one step up — no pure black anywhere.
        primary = androidx.compose.ui.graphics.Color(0xFF0A84FF),
        onPrimary = black,
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

