package app.autospace.car

import android.content.Context
import app.autospace.AppSettings
import app.autospace.audio.CarAudio
import app.autospace.car.theme.GsTheme
import app.autospace.car.theme.GsThemes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class NightMode { AUTO, DAY, NIGHT }

/** Distances and temperatures. [AUTO] goes by the phone's region. */
enum class Units { AUTO, IMPERIAL, METRIC }

/** Where a new message shows: [NAV_BAR] turns the clock button into it, [CARD] floats a card top right. */

/**
 * The car UI's own colours. Separate from [NightMode], which is about the light outside (the map).
 * r35 (Robert 2026-10-26): the old single AppTheme value mixed palettes with modes (Follow
 * system / Light / Dark / Material You Light / Dark). It is split into two axes, which live in
 * SchemeMode.kt: [ColorScheme] (palettes only: Neutral, Material You, Night, Pitlane, Circuit,
 * Heritage) and [AppearanceMode] (Light / Dark / System). The old stored value migrates once
 * in init() below; the old key then retires unread.
 */

/**
 * What a dark theme's backgrounds are made of. REGULAR = dark-gray surfaces
 * (#1C1C1E/#2C2C2E family, no pure-black background); OLED = pure-black #000000
 * backgrounds AND surfaces since Phase 10 (the dark look AutoSpace shipped
 * before Phase 7, kept as the default so existing installs do not change look).
 * Display name is "Pitch" (the stored value stays OLED, so existing
 * installs keep their setting with no migration). Applies to plain Dark,
 * Material You Dark, and the dark variant of both Follow options, across all
 * styles. For dynamic dark + OLED, the dynamic accent and content colors are
 * kept and only backgrounds/surfaces are forced to black (see CarUi's
 * oledDynamicDark); dynamic dark + Regular is left untouched, since the
 * dynamic dark scheme's surfaces are already dark gray, not black. NIGHT
 * (the night color scheme) always uses a pure-black background no matter
 * which dark type is picked.
 */
enum class DarkModeType { REGULAR, OLED }

/** How screen sharing fits the phone into the car frame: Fill crops, Fit letterboxes. */
enum class MirrorFit { FILL, FIT }

/**
 * Settings changed from the car screen itself. Held as flows so the car UI reacts the moment a
 * button is tapped, and persisted through [AppSettings] so they survive the next connection.
 */
object CarSettings {

    private const val KEY_SCALE = "car_scale"
    private const val KEY_ICON_SIZE = "car_icon_size"
    private const val KEY_OPEN = "car_open_on_connect"
    private const val KEY_NIGHT = "car_night_mode"
    private const val KEY_PIPE_AUDIO = "car_pipe_audio"
private const val KEY_SPEAK_DIRECTIONS = "car_speak_directions"
private const val KEY_BLUETOOTH_WARNING = "car_bluetooth_warning"
private const val KEY_LOCATION_WARNING = "car_location_warning"
    private const val KEY_AUDIO_CHOICE_ASKED = "car_audio_choice_asked"
    private const val KEY_LAST_NAV = "car_last_nav"
    private const val KEY_LAST_MEDIA = "car_last_media"
    private const val KEY_AUTOPLAY = "car_autoplay"
    private const val KEY_LAST_MEDIA_USED_MS = "car_last_media_used_ms"
    private const val KEY_AUTOPLAY_STOPS = "car_autoplay_stops"
    // KEY_PLAYER_TITLE retired with playerTitle (see above); the stored
    // value is simply never read again.
    // KEY_APP_THEME retired in r35 with the scheme/mode split (see
    // SchemeMode.kt): the stored value migrates once to KEY_SCHEME +
    // KEY_APPEARANCE_MODE and is then never read again.
    private const val KEY_THEME = "car_app_theme"
    private const val KEY_SCHEME = "car_scheme"
    private const val KEY_APPEARANCE_MODE = "car_appearance_mode"
    private const val KEY_BRIGHTNESS = "car_brightness"
    private const val KEY_MIRROR_FIT = "car_mirror_fit"
    private const val KEY_UI_THEME = "car_ui_theme"
    private const val KEY_PINNED = "car_pinned_apps"
    private const val KEY_APP_ORDER = "car_app_order"
    private const val KEY_EXPERIMENTAL_FEATURES = "car_experimental_features"
    private const val KEY_SYSTEM_SPEECH = "car_system_speech"
    private const val KEY_PHONE_MIC = "car_phone_mic_voice"
    private const val KEY_HOTWORD = "car_hotword"
    private const val KEY_HOTWORD_PHONE_MIC = "car_hotword_phone_mic"
    private const val KEY_CALL_SPEAKER = "car_call_speaker"
    private const val KEY_DARK_TYPE = "car_dark_type"
    private const val KEY_AUTO_NIGHT_MODE = "car_auto_night_mode"
    private const val KEY_AUTO_NIGHT_START = "car_auto_night_start"
    private const val KEY_AUTO_NIGHT_END = "car_auto_night_end"
    private const val KEY_TILE_SIZE = "car_tile_size"
    private const val KEY_PLAYER_SPEED = "car_player_speed"
    private const val KEY_PLAYER_SKIP_SEC = "car_player_skip_sec"
    private const val KEY_SUB_FONT = "car_sub_font"
    private const val KEY_SUB_SIZE = "car_sub_size"
    private const val KEY_SUB_COLOR = "car_sub_color"
    private const val KEY_SUB_BG = "car_sub_bg"
    private const val KEY_SUB_EDGE = "car_sub_edge"
    private const val KEY_CAR_SENSORS = "car_sensors_enabled"
    private const val KEY_FLOATING_MEDIA = "car_floating_media"
    private const val KEY_HOME_WIDGETS = "car_home_widgets"
    private const val KEY_FROSTED_MAP_CHROME = "car_frosted_map_chrome"
    private const val KEY_MAP_CHROME = "car_map_chrome"
    private const val KEY_MERIDIAN_CHROME = "car_meridian_chrome"
    private const val KEY_FULL_WIDTH_GLOW = "car_full_width_glow"
    private const val KEY_WIDGETS_DISMISSED = "car_widgets_dismissed"
    private const val KEY_WIDGETS_HIDDEN = "car_widgets_hidden"
    private const val KEY_SEEN_SAFETY_WARNING = "car_seen_safety_warning"
    private const val KEY_UNITS = "car_units"
    private const val KEY_WHATS_NEW = "car_whats_new_after_updates"
    private const val KEY_WHATS_NEW_SEEN = "car_whats_new_seen_code"

    /** How many shortcuts the nav bar makes room for; a driver reaching for one needs it to
     * still be on the bar, not off the edge of a long list. */
    const val MAX_PINNED = 3

    /** Id of the app to open on connect; [HOME] opens the launcher. */
    const val HOME = "home"

    private lateinit var app: Context

    private val scaleFlow = MutableStateFlow(1f)
    private val iconSizeFlow = MutableStateFlow(1f)
    private val tileSizeFlow = MutableStateFlow(1f)
    private val darkTypeFlow = MutableStateFlow(DarkModeType.OLED)
    private val openFlow = MutableStateFlow(HOME)
    private val nightFlow = MutableStateFlow(NightMode.AUTO)
    private val pipeAudioFlow = MutableStateFlow(true)
    private val speakDirectionsFlow = MutableStateFlow(true)
    private val bluetoothWarningFlow = MutableStateFlow(true)
    private val locationWarningFlow = MutableStateFlow(true)
    private val lastNavFlow = MutableStateFlow<String?>(null)
    private val lastMediaFlow = MutableStateFlow<String?>(null)
    private val autoplayFlow = MutableStateFlow(false)
    private val lastMediaUsedMsFlow = MutableStateFlow(0L)
    private val autoplayStopsFlow = MutableStateFlow(0)
    // (playerTitle removed: the nav pill shows title AND skip buttons
    // together now, so the old Skip-buttons-vs-Song-title choice is dead.)
    private val schemeFlow = MutableStateFlow(ColorScheme.NEUTRAL)
    /** The user's chosen palette; Auto night may override what renders (see [effectiveColorScheme]). */
    val colorScheme: StateFlow<ColorScheme> = schemeFlow
    private val modeFlow = MutableStateFlow(AppearanceMode.SYSTEM)
    /** Light / Dark / System; Night and Pitlane force dark while active, stored mode untouched. */
    val appearanceMode: StateFlow<AppearanceMode> = modeFlow
    private val autoNightModeFlow = MutableStateFlow(AutoNightMode.OFF)
    val autoNightMode: StateFlow<AutoNightMode> = autoNightModeFlow
    private val autoNightStartFlow = MutableStateFlow(21 * 60)
    val autoNightStart: StateFlow<Int> = autoNightStartFlow
    private val autoNightEndFlow = MutableStateFlow(7 * 60)
    val autoNightEnd: StateFlow<Int> = autoNightEndFlow
    private val autoNightActiveFlow = MutableStateFlow(false)
    val autoNightActive: StateFlow<Boolean> = autoNightActiveFlow
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /**
     * The scheme the car UI actually renders: the user's chosen scheme,
     * except that an active Auto night switches it to NIGHT (see NightAuto
     * for the precedence rule). The stored scheme never changes.
     */
    val effectiveColorScheme: StateFlow<ColorScheme> =
        combine(schemeFlow, autoNightActiveFlow) { scheme, active ->
            effectiveScheme(scheme, active)
        }.stateIn(settingsScope, SharingStarted.Eagerly, ColorScheme.NEUTRAL)
    /** Dark-mode surface type; see [DarkModeType]. Default OLED preserves the pre-Phase-7 dark look. */
    val darkModeType: StateFlow<DarkModeType> = darkTypeFlow
    private val brightnessFlow = MutableStateFlow(1f)

    /**
     * The car screen's brightness, 0..1, as a fraction of the head unit's backlight.
     * 1f is the head unit's own maximum — this slider cannot brighten past it, it can
     * only dim. A value the driver sets always wins: nothing auto-dims over it, and it
     * persists across drives. The phone's brightness does not affect the projected
     * virtual display, which is why this control exists at all.
     */
    val brightness: StateFlow<Float> = brightnessFlow
    private val uiThemeFlow = MutableStateFlow(GsThemes.default.id)

    /** Id of the [GsTheme] controls are drawn in; an unknown id falls back to the default. */
    val uiTheme: StateFlow<String> = uiThemeFlow
    private val pinnedFlow = MutableStateFlow<Set<String>>(emptySet())
    private val appOrderFlow = MutableStateFlow<List<String>>(emptyList())
    private val experimentalFeaturesFlow = MutableStateFlow(false)
    private val systemSpeechFlow = MutableStateFlow(false)
    private val phoneMicFlow = MutableStateFlow(false)
    private val hotwordFlow = MutableStateFlow(false)
    private val hotwordPhoneMicFlow = MutableStateFlow(false)
    private val callSpeakerFlow = MutableStateFlow(false)
    private val playerSpeedFlow = MutableStateFlow(1f)
    private val playerSkipSecFlow = MutableStateFlow(10)
    private val carSensorsFlow = MutableStateFlow(false)
    private val floatingMediaFlow = MutableStateFlow(false)
    private val homeWidgetsFlow = MutableStateFlow(false)
    private val frostedMapChromeFlow = MutableStateFlow(false)
    private val meridianChromeFlow = MutableStateFlow(false)
    private val fullWidthGlowFlow = MutableStateFlow(false)
    private val widgetsDismissedFlow = MutableStateFlow(false)
    private val hiddenWidgetsFlow = MutableStateFlow<Set<WidgetKind>>(emptySet())
    private val seenSafetyWarningFlow = MutableStateFlow(false)
    private val unitsFlow = MutableStateFlow(Units.AUTO)

    val units: StateFlow<Units> = unitsFlow


    private val whatsNewFlow = MutableStateFlow(true)
    private val whatsNewSeenFlow = MutableStateFlow(0)
    private val whatsNewOpenFlow = MutableStateFlow(false)

    /** Whether the car screen shows what's new once after each update. */
    val whatsNewAfterUpdates: StateFlow<Boolean> = whatsNewFlow

    /** Version code whose notes the car screen last showed. */
    val whatsNewSeen: StateFlow<Int> = whatsNewSeenFlow

    /** Opened by hand from Settings, whatever the two above say. Not saved. */
    val whatsNewOpen: StateFlow<Boolean> = whatsNewOpenFlow

    fun setWhatsNewAfterUpdates(on: Boolean) {
        whatsNewFlow.value = on
        AppSettings.putString(app, KEY_WHATS_NEW, on.toString())
    }

    fun openWhatsNew() { whatsNewOpenFlow.value = true }

    /** Closes the notes and marks [versionCode]'s as read. */
    fun closeWhatsNew(versionCode: Int) {
        whatsNewOpenFlow.value = false
        whatsNewSeenFlow.value = versionCode
        AppSettings.putString(app, KEY_WHATS_NEW_SEEN, versionCode.toString())
    }



    /** Whether to show miles, feet and °F; [Units.AUTO] follows the countries that still use them. */
    fun imperial(): Boolean = when (unitsFlow.value) {
        Units.IMPERIAL -> true
        Units.METRIC -> false
        Units.AUTO -> java.util.Locale.getDefault().country in setOf("US", "LR", "MM")
    }

    fun setUnits(units: Units) {
        unitsFlow.value = units
        AppSettings.putString(app, KEY_UNITS, units.name)
        Weather.refresh(app, force = true)
    }

    /** Flattened component names of the apps long-pressed onto the nav bar as shortcuts. */
    val pinnedApps: StateFlow<Set<String>> = pinnedFlow

    /** The launcher's order once the driver has moved a tile; empty keeps the grouped default. */
    val appOrder: StateFlow<List<String>> = appOrderFlow

    /** Flattened component names of the map and media apps used last, brought back on connect. */
    val lastNav: StateFlow<String?> = lastNavFlow
    val lastMedia: StateFlow<String?> = lastMediaFlow

    /** Whether the media app starts playing by itself when the car connects. */
    val autoplay: StateFlow<Boolean> = autoplayFlow

    /** Wall-clock ms the open media app was last opened or played by the driver (r62 A49); 0 when unknown. */
    val lastMediaUsedMs: StateFlow<Long> = lastMediaUsedMsFlow

    /** Prompt autoplay stops since the last offer or reset (r62 A50). */
    val autoplayStops: StateFlow<Int> = autoplayStopsFlow

    val scale: StateFlow<Float> = scaleFlow
    /** App-icon size fraction, 0..1: 1.0 is the Phase 6 large size, 0 hides the icon. Tiles do not shrink with it. */
    val iconSize: StateFlow<Float> = iconSizeFlow
    /**
     * Tile size fraction, 0.5..1.5 (Phase 7): 1.0 is the Phase 6 large tile size.
     * The tile graphic shrinks with it, but never below a car-usable tap area —
     * launcher tiles floor at 88dp high, pinned tiles at 72dp, and the grid
     * reflows with size. Labels stay readable; the tap area is the tile itself.
     */
    val tileSize: StateFlow<Float> = tileSizeFlow
    val openOnConnect: StateFlow<String> = openFlow
    val nightMode: StateFlow<NightMode> = nightFlow

    /**
     * Whether a media app's sound is captured and sent over the car link. On by default, since
     * sound in the car is the point of the exercise; the platform allows the capture only behind a
     * consent dialog, which [app.autospace.audio.CarAudio] raises once the car has
     * somewhere to play it. Turned off ("Bluetooth" in the settings), the app plays through the
     * phone's normal output instead, and Android never asks.
     */
    val pipeAudio: StateFlow<Boolean> = pipeAudioFlow

    /** Gearslip reads the map app's turns aloud on the car's directions channel; see DirectionsVoice. */
    val speakDirections: StateFlow<Boolean> = speakDirectionsFlow

    /** With Bluetooth audio, warn when the phone isn't connected to the car; see BluetoothAudioCheck. */
    val bluetoothWarning: StateFlow<Boolean> = bluetoothWarningFlow

    /** Warn on the car when the phone's location is off; see LocationCheck. */
    val locationWarning: StateFlow<Boolean> = locationWarningFlow

    /**
     * Whether the driver has already been offered Bluetooth after turning down the capture
     * dialog. Offered once only: someone who chose to keep the USB route shouldn't be nagged.
     */
    /** False until the driver picks USB or Bluetooth, in setup or in settings. */
    val pipeAudioChosen: Boolean
        get() = AppSettings.getString(app, KEY_PIPE_AUDIO, "") != ""

    val audioChoiceAsked: Boolean
        get() = AppSettings.getString(app, KEY_AUDIO_CHOICE_ASKED, "false") == "true"

    fun markAudioChoiceAsked() = AppSettings.putString(app, KEY_AUDIO_CHOICE_ASKED, "true")

    /**
     * Off by default: a driver has to opt into experimental features as a whole before any of
     * them - the voice assistant included - show up to turn on individually.
     */
    val experimentalFeaturesEnabled: StateFlow<Boolean> = experimentalFeaturesFlow

    /** Listen with the phone's own speech service instead of Gearslip's on-device model. */
    val systemSpeech: StateFlow<Boolean> = systemSpeechFlow
    /** Voice commands/replies use the phone mic even when the car has one. Default off; calls unaffected. */
    val phoneMicForVoice: StateFlow<Boolean> = phoneMicFlow
    /** Hotword listening ("Hey AutoSpace" / "Hey Gearslip") while connected. Default off. */
    val hotword: StateFlow<Boolean> = hotwordFlow
    /**
     * Hotword listening uses the phone microphone, always, regardless of
     * the voice-microphone setting. Default off (meaning: follow the
     * voice-microphone setting). Hotword-only: button-press commands,
     * calls, and everything else are untouched by this switch.
     */
    val hotwordPhoneMic: StateFlow<Boolean> = hotwordPhoneMicFlow
    /**
     * Calls go to the phone's speaker (and so the phone's microphone) instead of the car's
     * Bluetooth route, applied when a call starts and restored when it ends. Default off;
     * calls only — voice commands, assistant speech, and media are untouched. The car's
     * Bluetooth may still reassert its route, which this cannot override.
     */
    val callSpeaker: StateFlow<Boolean> = callSpeakerFlow
    /** Video player speed, persisted (Notscape playerSpeed). */
    val playerSpeed: StateFlow<Float> = playerSpeedFlow
    /** Video player skip interval in seconds, persisted (Notscape playerSkipSec). */
    val playerSkipSec: StateFlow<Int> = playerSkipSecFlow

    /**
     * Vehicle data is experimental and off by default: the launcher's tile for it, and the
     * voice assistant's "vehicle data" command, only show up once this and
     * [experimentalFeaturesEnabled] are both on. See [app.autospace.car.CarSensors]
     * for why the raw sensor feed it shows is worth gating - the field labels are unverified.
     */
    val carSensorsEnabled: StateFlow<Boolean> = carSensorsFlow

    /**
     * Floating Home (r34): Home shows the map full-bleed with the player
     * as a floating card over it instead of side-by-side. Experimental
     * and off by default; classic Home is byte-for-byte when off. It
     * graduates only on Robert's drive tests — never on a builder's say-so.
     */
    val floatingMediaEnabled: StateFlow<Boolean> = floatingMediaFlow

    /**
     * Widget layer (r37): the three-widget Home column, behind the
     * experimental-features Show switch, default OFF. Classic Home is
     * byte-for-byte when off; the column renders only over the floating
     * full-bleed map base.
     */
    val homeWidgetsEnabled: StateFlow<Boolean> = homeWidgetsFlow

    /**
     * Frosted map chrome (r46): the map pane, turn card, trip bar and
     * edge buttons draw the shared frosted treatment (the floating
     * media card's own surface at the pinned 0.92 alpha) instead of the
     * opaque chrome. Experimental and off by default; classic opaque
     * chrome returns when off. It joins the floating-look master suite:
     * the r53 master switch sets this ON alongside floating media and
     * home widgets (turning the master OFF leaves it as-is), and the
     * individual toggle keeps working after.
     */
    val frostedMapChromeEnabled: StateFlow<Boolean> = frostedMapChromeFlow

    /**
     * r65: the single Meridian toggle. One control for the whole
     * Meridian look — turn card, trip pill, panes, edge controls —
     * replacing the retired r63 Themed/Meridian choice row. Off by
     * default; a stored Meridian choice migrates it on once (see the
     * load migration below). It joins no master switch: the r53
     * master never touches it, and it never affects the master's
     * derived state.
     */
    val meridianChromeEnabled: StateFlow<Boolean> = meridianChromeFlow

    /**
     * r67: the full-width listening glow toggle. Off by default; it
     * joins no master switch: the r53 master never touches it, and it
     * never affects the master's derived state. It still sits behind
     * the general experimental opt-in: the band renders only when
     * [experimentalFeaturesEnabled] and this are both on (see
     * [fullWidthGlowNow]).
     */
    val fullWidthGlowEnabled: StateFlow<Boolean> = fullWidthGlowFlow

    /**
     * Column-level widget dismissal, REMEMBERED across drives (persisted
     * pref, §7(5)) — unlike the floating card's per-session dismissal.
     * Re-enabled only from the widget settings row, or by turning the
     * r53 "All experimental features" master switch ON — never by
     * cycling the experimental-features Hide/Show row or the floating
     * toggle. (The pre-r53 wording's "master switch" meant the Hide/Show
     * row; the r53 master is a different control and DOES clear this on ON.)
     */
    val widgetsDismissed: StateFlow<Boolean> = widgetsDismissedFlow

    /**
     * r58 B10: per-widget hide toggles — the set of widget kinds the
     * driver hid from the column. Empty by default (everything shows).
     * Persisted as comma-joined kind names. The master switch never
     * touches it (see ExperimentalMaster — deliberately not a member):
     * hiding one widget is a column preference, not a suite flag.
     */
    val hiddenWidgets: StateFlow<Set<WidgetKind>> = hiddenWidgetsFlow

    /**
     * Whether the car screen's own "drive safely" warning has already been shown and dismissed
     * once, from this same install. Separate from the phone side's own compat/safety dialogs -
     * a driver who only ever plugs in and never opens the phone app should still see this once,
     * on the screen they are actually about to use while driving.
     */
    val hasSeenSafetyWarning: StateFlow<Boolean> = seenSafetyWarningFlow

    fun markSafetyWarningSeen() {
        seenSafetyWarningFlow.value = true
        AppSettings.putString(app, KEY_SEEN_SAFETY_WARNING, "true")
    }

    fun init(context: Context) {
        app = context.applicationContext
        scaleFlow.value = AppSettings.getString(app, KEY_SCALE, "1.0").toFloatOrNull()?.coerceIn(0.5f, 1.5f) ?: 1f
        iconSizeFlow.value = AppSettings.getString(app, KEY_ICON_SIZE, "1.0").toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
        openFlow.value = AppSettings.getString(app, KEY_OPEN, HOME)
        lastNavFlow.value = AppSettings.getString(app, KEY_LAST_NAV, "").ifEmpty { null }
        lastMediaFlow.value = AppSettings.getString(app, KEY_LAST_MEDIA, "").ifEmpty { null }
        lastMediaUsedMsFlow.value = AppSettings.getString(app, KEY_LAST_MEDIA_USED_MS, "0").toLongOrNull() ?: 0L
        // r35 migration, same one-time pattern as PHONE -> MATERIAL_YOU
        // above: the old car_app_theme value mixed a palette with a mode,
        // so it splits into car_scheme + car_appearance_mode via
        // migrateLegacyTheme (see SchemeMode.kt) and the old key is
        // cleared, so the migration can never re-run and overwrite a
        // later choice. Both new keys are always written together here,
        // so a later init that finds either one present trusts the new
        // keys and never looks at the old key again. A fresh install
        // (no keys at all) lands on NEUTRAL + SYSTEM, which is exactly
        // the old FOLLOW_SYSTEM default look.
        val storedScheme = AppSettings.getString(app, KEY_SCHEME, "")
        val storedMode = AppSettings.getString(app, KEY_APPEARANCE_MODE, "")
        if (storedScheme.isEmpty() && storedMode.isEmpty()) {
            val legacy = AppSettings.getString(app, KEY_THEME, "")
            val (scheme, mode) = if (legacy.isEmpty()) {
                ColorScheme.NEUTRAL to AppearanceMode.SYSTEM
            } else {
                migrateLegacyTheme(legacy)
            }
            schemeFlow.value = scheme
            modeFlow.value = mode
            AppSettings.putString(app, KEY_SCHEME, scheme.name)
            AppSettings.putString(app, KEY_APPEARANCE_MODE, mode.name)
            if (legacy.isNotEmpty()) AppSettings.putString(app, KEY_THEME, "")
        } else {
            schemeFlow.value = runCatching { ColorScheme.valueOf(storedScheme) }
                .getOrDefault(ColorScheme.NEUTRAL)
            modeFlow.value = runCatching { AppearanceMode.valueOf(storedMode) }
                .getOrDefault(AppearanceMode.SYSTEM)
        }
        darkTypeFlow.value = runCatching {
            DarkModeType.valueOf(AppSettings.getString(app, KEY_DARK_TYPE, DarkModeType.OLED.name))
        }.getOrDefault(DarkModeType.OLED)
        autoNightModeFlow.value = runCatching {
            AutoNightMode.valueOf(AppSettings.getString(app, KEY_AUTO_NIGHT_MODE, AutoNightMode.OFF.name))
        }.getOrDefault(AutoNightMode.OFF)
        autoNightStartFlow.value = AppSettings.getString(app, KEY_AUTO_NIGHT_START, "${21 * 60}").toIntOrNull()?.coerceIn(0, 1439) ?: 21 * 60
        autoNightEndFlow.value = AppSettings.getString(app, KEY_AUTO_NIGHT_END, "${7 * 60}").toIntOrNull()?.coerceIn(0, 1439) ?: 7 * 60
        tileSizeFlow.value = AppSettings.getString(app, KEY_TILE_SIZE, "1.0").toFloatOrNull()?.coerceIn(0.5f, 1.5f) ?: 1f
        // r20 migration, same pattern as PHONE -> MATERIAL_YOU above: Keycap was
        // replaced by Edge, so a stored "keycap" becomes "edge" (and the migrated
        // value is persisted) instead of falling through byId to Frost.
        val storedUiTheme = AppSettings.getString(app, KEY_UI_THEME, GsThemes.default.id)
        uiThemeFlow.value = if (storedUiTheme == "keycap") {
            GsThemes.Edge.id.also { AppSettings.putString(app, KEY_UI_THEME, GsThemes.Edge.id) }
        } else {
            storedUiTheme
        }
        brightnessFlow.value = AppSettings.getString(app, KEY_BRIGHTNESS, "1.0").toFloatOrNull()?.coerceIn(0.05f, 1f) ?: 1f
        // 2026-10-25: the default is FIT, reversing the earlier
        // Fill default — Fill on a portrait phone crops the top and
        // bottom off, which is the "way too zoomed in" complaint. A
        // stored FILL is migrated to FIT once as well (Fill stays one
        // tap away in the mirror chrome and in Settings below), so an
        // existing install that never chose Fill explicitly gets the
        // fixed default instead of keeping the broken one silently.
        val storedFit = AppSettings.getString(app, KEY_MIRROR_FIT, "")
        mirrorFitFlow.value = when (storedFit) {
            "FILL" -> MirrorFit.FIT.also { AppSettings.putString(app, KEY_MIRROR_FIT, MirrorFit.FIT.name) }
            "FIT" -> MirrorFit.FIT
            else -> MirrorFit.FIT
        }
        autoplayFlow.value = AppSettings.getString(app, KEY_AUTOPLAY, "false") == "true"
        autoplayStopsFlow.value = AppSettings.getString(app, KEY_AUTOPLAY_STOPS, "0").toIntOrNull() ?: 0
        pipeAudioFlow.value = AppSettings.getString(app, KEY_PIPE_AUDIO, "true") == "true"
        // Until the driver sets it, on for USB audio and off for Bluetooth, where the map app's
        // own voice already reaches the car.
        speakDirectionsFlow.value = AppSettings.getString(app, KEY_SPEAK_DIRECTIONS, pipeAudioFlow.value.toString()) == "true"
        bluetoothWarningFlow.value = AppSettings.getString(app, KEY_BLUETOOTH_WARNING, "true") == "true"
        locationWarningFlow.value = AppSettings.getString(app, KEY_LOCATION_WARNING, "true") == "true"
        nightFlow.value = runCatching {
            NightMode.valueOf(AppSettings.getString(app, KEY_NIGHT, NightMode.AUTO.name))
        }.getOrDefault(NightMode.AUTO)
        pinnedFlow.value = AppSettings.getString(app, KEY_PINNED, "")
            .split(",").filter { it.isNotEmpty() }.toSet()
        appOrderFlow.value = AppSettings.getString(app, KEY_APP_ORDER, "")
            .split(",").filter { it.isNotEmpty() }
        experimentalFeaturesFlow.value = AppSettings.getString(app, KEY_EXPERIMENTAL_FEATURES, "false") == "true"
        systemSpeechFlow.value = AppSettings.getString(app, KEY_SYSTEM_SPEECH, "false") == "true"
        phoneMicFlow.value = AppSettings.getString(app, KEY_PHONE_MIC, "false") == "true"
        hotwordFlow.value = AppSettings.getString(app, KEY_HOTWORD, "false") == "true"
        hotwordPhoneMicFlow.value = AppSettings.getString(app, KEY_HOTWORD_PHONE_MIC, "false") == "true"
        callSpeakerFlow.value = AppSettings.getString(app, KEY_CALL_SPEAKER, "false") == "true"
        playerSpeedFlow.value = AppSettings.getString(app, KEY_PLAYER_SPEED, "1.0").toFloatOrNull() ?: 1f
        playerSkipSecFlow.value = AppSettings.getString(app, KEY_PLAYER_SKIP_SEC, "10").toIntOrNull() ?: 10
        carSensorsFlow.value = AppSettings.getString(app, KEY_CAR_SENSORS, "false") == "true"
        floatingMediaFlow.value = AppSettings.getString(app, KEY_FLOATING_MEDIA, "false") == "true"
        homeWidgetsFlow.value = AppSettings.getString(app, KEY_HOME_WIDGETS, "false") == "true"
        frostedMapChromeFlow.value = AppSettings.getString(app, KEY_FROSTED_MAP_CHROME, "false") == "true"
        // r65 one-time migration, same pattern as the r35 theme split:
        // the retired car_map_chrome choice becomes the single Meridian
        // toggle, and the old key is cleared so the migration can never
        // re-run and overwrite a later choice. A stored Meridian choice
        // (including a legacy "GOOGLE" value, which parses to Meridian)
        // carries the toggle on; Themed, blank, or unrecognized leaves
        // it off. A fresh install (no keys at all) lands off, which is
        // exactly the old Themed default.
        val legacyChrome = AppSettings.getString(app, KEY_MAP_CHROME, "")
        if (legacyChrome.isNotEmpty()) {
            meridianChromeFlow.value = migrateMeridianToggle(legacyChrome)
            AppSettings.putString(app, KEY_MERIDIAN_CHROME, meridianChromeFlow.value.toString())
            AppSettings.putString(app, KEY_MAP_CHROME, "")
        } else {
            meridianChromeFlow.value = AppSettings.getString(app, KEY_MERIDIAN_CHROME, "false") == "true"
        }
        fullWidthGlowFlow.value = AppSettings.getString(app, KEY_FULL_WIDTH_GLOW, "false") == "true"
        widgetsDismissedFlow.value = AppSettings.getString(app, KEY_WIDGETS_DISMISSED, "false") == "true"
        hiddenWidgetsFlow.value = AppSettings.getString(app, KEY_WIDGETS_HIDDEN, "")
            .split(",").mapNotNull { runCatching { WidgetKind.valueOf(it) }.getOrNull() }.toSet()
        seenSafetyWarningFlow.value = AppSettings.getString(app, KEY_SEEN_SAFETY_WARNING, "false") == "true"
        unitsFlow.value = runCatching {
            Units.valueOf(AppSettings.getString(app, KEY_UNITS, Units.AUTO.name))
        }.getOrDefault(Units.AUTO)
        whatsNewFlow.value = AppSettings.getString(app, KEY_WHATS_NEW, "true") == "true"
        whatsNewSeenFlow.value = AppSettings.getString(app, KEY_WHATS_NEW_SEEN, "0").toIntOrNull() ?: 0
        // Voice shortcuts (r19) live in the same prefs; loading them here means the car
        // assistant sees phone-side edits on the next connection.
        VoiceShortcuts.ensureLoaded(app)
        refreshAutoNight()
    }

    /**
     * Recomputes whether Auto night is active right now. Cheap (last-known
     * location only, no fresh fix): call it on a timer from the car UI and
     * the phone Settings screen, plus on every setter below. Sunrise &
     * sunset with no location stays inactive, never silently wrong.
     */
    fun refreshAutoNight() {
        if (!::app.isInitialized) {
            autoNightActiveFlow.value = false
            return
        }
        autoNightActiveFlow.value = NightAuto.isAutoNightNow(
            app, autoNightModeFlow.value, autoNightStartFlow.value, autoNightEndFlow.value,
        )
    }

    fun setAutoNightMode(mode: AutoNightMode) {
        autoNightModeFlow.value = mode
        AppSettings.putString(app, KEY_AUTO_NIGHT_MODE, mode.name)
        refreshAutoNight()
    }

    fun setAutoNightStart(minutes: Int) {
        val v = minutes.coerceIn(0, 1439)
        autoNightStartFlow.value = v
        AppSettings.putString(app, KEY_AUTO_NIGHT_START, v.toString())
        refreshAutoNight()
    }

    fun setAutoNightEnd(minutes: Int) {
        val v = minutes.coerceIn(0, 1439)
        autoNightEndFlow.value = v
        AppSettings.putString(app, KEY_AUTO_NIGHT_END, v.toString())
        refreshAutoNight()
    }

    fun setScale(value: Float) {
        // Phase 9: UI size is a 50%-150% slider (Phase 8's 100%-150% put
        // the default 100% at the slider's zero end, so the user could
        // never go smaller). Coerce both the setter and the loaded value
        // to that range; the old presets (100/125/150) still survive in it.
        val v = value.coerceIn(0.5f, 1.5f)
        scaleFlow.value = v
        AppSettings.putString(app, KEY_SCALE, v.toString())
    }

    fun setIconSize(value: Float) {
        val v = value.coerceIn(0f, 1f)
        iconSizeFlow.value = v
        AppSettings.putString(app, KEY_ICON_SIZE, v.toString())
    }

    fun setTileSize(value: Float) {
        val v = value.coerceIn(0.5f, 1.5f)
        tileSizeFlow.value = v
        AppSettings.putString(app, KEY_TILE_SIZE, v.toString())
    }

    fun setDarkModeType(type: DarkModeType) {
        darkTypeFlow.value = type
        AppSettings.putString(app, KEY_DARK_TYPE, type.name)
    }

    fun setOpenOnConnect(id: String) {
        openFlow.value = id
        AppSettings.putString(app, KEY_OPEN, id)
    }

    fun setLastNav(component: String) {
        lastNavFlow.value = component
        AppSettings.putString(app, KEY_LAST_NAV, component)
    }

    fun setLastMedia(component: String) {
        lastMediaFlow.value = component
        AppSettings.putString(app, KEY_LAST_MEDIA, component)
    }

    fun setColorScheme(scheme: ColorScheme) {
        schemeFlow.value = scheme
        AppSettings.putString(app, KEY_SCHEME, scheme.name)
    }

    fun setMeridianChromeEnabled(on: Boolean) {
        meridianChromeFlow.value = on
        AppSettings.putString(app, KEY_MERIDIAN_CHROME, on.toString())
    }

    fun setFullWidthGlowEnabled(on: Boolean) {
        fullWidthGlowFlow.value = on
        AppSettings.putString(app, KEY_FULL_WIDTH_GLOW, on.toString())
    }

    fun setAppearanceMode(mode: AppearanceMode) {
        modeFlow.value = mode
        AppSettings.putString(app, KEY_APPEARANCE_MODE, mode.name)
    }

    /** Persists a driver-set brightness; clamped so the screen can never go fully black. */
    fun setBrightness(value: Float) {
        brightnessFlow.value = value.coerceIn(0.05f, 1f)
        AppSettings.putString(app, KEY_BRIGHTNESS, brightnessFlow.value.toString())
    }

    // Audit fix (2026-10-25, M1): the flow used to initialise to
    // FILL while init() migrates any stored value to FIT, so any
    // reader before init() saw the reversed default. FIT from birth.
    private val mirrorFitFlow = MutableStateFlow(MirrorFit.FIT)

    /** Screen-sharing fit; Fit is the default. Applies the next time mirroring starts. */
    val mirrorFit: StateFlow<MirrorFit> = mirrorFitFlow

    fun setMirrorFit(fit: MirrorFit) {
        mirrorFitFlow.value = fit
        AppSettings.putString(app, KEY_MIRROR_FIT, fit.name)
    }

    fun setUiTheme(id: String) {
        uiThemeFlow.value = id
        AppSettings.putString(app, KEY_UI_THEME, id)
    }

    fun setAutoplay(on: Boolean) {
        autoplayFlow.value = on
        AppSettings.putString(app, KEY_AUTOPLAY, on.toString())
    }

    fun setLastMediaUsedMs(ms: Long) {
        lastMediaUsedMsFlow.value = ms
        AppSettings.putString(app, KEY_LAST_MEDIA_USED_MS, ms.toString())
    }

    fun setAutoplayStops(n: Int) {
        autoplayStopsFlow.value = n
        AppSettings.putString(app, KEY_AUTOPLAY_STOPS, n.toString())
    }

    /**
     * Takes effect at once: switching to Bluetooth mid-drive stops the capture, and switching
     * back asks for it again (or waits for the car, if none is connected).
     */
    fun setPipeAudio(on: Boolean) {
        // Saved even when unchanged, so picking the default still counts as a choice.
        AppSettings.putString(app, KEY_PIPE_AUDIO, on.toString())
        if (pipeAudioFlow.value == on) return
        pipeAudioFlow.value = on
        // Audit note (2026-10-25, B10): this coupling runs ONCE —
        // only while speak-directions was never set — so toggling
        // pipe audio later never silently changes it again.
        if (AppSettings.getString(app, KEY_SPEAK_DIRECTIONS, "") == "") speakDirectionsFlow.value = on
        if (on) CarAudio.request() else CarAudio.release()
    }

    fun setSpeakDirections(on: Boolean) {
        speakDirectionsFlow.value = on
        AppSettings.putString(app, KEY_SPEAK_DIRECTIONS, on.toString())
    }

    fun setBluetoothWarning(on: Boolean) {
        bluetoothWarningFlow.value = on
        AppSettings.putString(app, KEY_BLUETOOTH_WARNING, on.toString())
    }

    fun setLocationWarning(on: Boolean) {
        locationWarningFlow.value = on
        AppSettings.putString(app, KEY_LOCATION_WARNING, on.toString())
    }

    fun setExperimentalFeaturesEnabled(on: Boolean) {
        experimentalFeaturesFlow.value = on
        AppSettings.putString(app, KEY_EXPERIMENTAL_FEATURES, on.toString())
    }

    fun setSystemSpeech(on: Boolean) {
        systemSpeechFlow.value = on
        AppSettings.putString(app, KEY_SYSTEM_SPEECH, on.toString())
    }

    fun setPhoneMicForVoice(on: Boolean) {
        phoneMicFlow.value = on
        AppSettings.putString(app, KEY_PHONE_MIC, on.toString())
    }

    fun setHotword(on: Boolean) {
        hotwordFlow.value = on
        AppSettings.putString(app, KEY_HOTWORD, on.toString())
    }

    fun setHotwordPhoneMic(on: Boolean) {
        hotwordPhoneMicFlow.value = on
        AppSettings.putString(app, KEY_HOTWORD_PHONE_MIC, on.toString())
    }

    fun setCallSpeaker(on: Boolean) {
        callSpeakerFlow.value = on
        AppSettings.putString(app, KEY_CALL_SPEAKER, on.toString())
    }

    fun setPlayerSpeed(speed: Float) {
        playerSpeedFlow.value = speed
        AppSettings.putString(app, KEY_PLAYER_SPEED, speed.toString())
    }

    fun setPlayerSkipSec(sec: Int) {
        playerSkipSecFlow.value = sec
        AppSettings.putString(app, KEY_PLAYER_SKIP_SEC, sec.toString())
    }

    /**
     * Subtitle styling values (Notscape subFont/subSize/subColor/subBg/
     * subEdge), written by the Subtitles section in car Settings
     * (audit fix, 2026-10-25). Until any is set, every read returns
     * its default and the player's subtitle view keeps the system
     * caption style untouched, exactly as Notscape does when nothing
     * is set. Guarded: the player screen can compose before init in
     * principle, and must never crash.
     */
    fun subtitleSetting(key: String, default: String): String = runCatching {
        AppSettings.getString(app, key, default)
    }.getOrDefault(default)
    fun subtitleFont(): String = subtitleSetting(KEY_SUB_FONT, "")
    fun subtitleSize(): String = subtitleSetting(KEY_SUB_SIZE, "")
    fun subtitleColor(): String = subtitleSetting(KEY_SUB_COLOR, "")
    fun subtitleBg(): String = subtitleSetting(KEY_SUB_BG, "")
    fun subtitleEdge(): String = subtitleSetting(KEY_SUB_EDGE, "")
    // Audit fix (2026-10-25, B3/F5): the Settings page below
    // finally writes these; until it existed every read returned
    // its default and the player kept the system caption style.
    fun setSubtitleFont(value: String) = AppSettings.putString(app, KEY_SUB_FONT, value)
    fun setSubtitleSize(value: String) = AppSettings.putString(app, KEY_SUB_SIZE, value)
    fun setSubtitleColor(value: String) = AppSettings.putString(app, KEY_SUB_COLOR, value)
    fun setSubtitleBg(value: String) = AppSettings.putString(app, KEY_SUB_BG, value)
    fun setSubtitleEdge(value: String) = AppSettings.putString(app, KEY_SUB_EDGE, value)

    fun setCarSensorsEnabled(on: Boolean) {
        carSensorsFlow.value = on
        AppSettings.putString(app, KEY_CAR_SENSORS, on.toString())
    }

    fun setFloatingMediaEnabled(on: Boolean) {
        floatingMediaFlow.value = on
        AppSettings.putString(app, KEY_FLOATING_MEDIA, on.toString())
    }

    fun setHomeWidgetsEnabled(on: Boolean) {
        homeWidgetsFlow.value = on
        AppSettings.putString(app, KEY_HOME_WIDGETS, on.toString())
    }

    fun setFrostedMapChromeEnabled(on: Boolean) {
        frostedMapChromeFlow.value = on
        AppSettings.putString(app, KEY_FROSTED_MAP_CHROME, on.toString())
    }

    fun setWidgetsDismissed(dismissed: Boolean) {
        widgetsDismissedFlow.value = dismissed
        AppSettings.putString(app, KEY_WIDGETS_DISMISSED, dismissed.toString())
    }

    fun setWidgetHidden(kind: WidgetKind, hidden: Boolean) {
        val updated = if (hidden) hiddenWidgetsFlow.value + kind else hiddenWidgetsFlow.value - kind
        hiddenWidgetsFlow.value = updated
        AppSettings.putString(app, KEY_WIDGETS_HIDDEN, updated.joinToString(",") { it.name })
    }

    fun setNightMode(mode: NightMode) {
        nightFlow.value = mode
        AppSettings.putString(app, KEY_NIGHT, mode.name)
        CarEnvironment.refresh()
    }

    /** Long-pressed onto or off the nav bar. Silently does nothing past [MAX_PINNED] rather than
     * bumping the oldest pin - a driver who filled the bar on purpose shouldn't lose one by accident. */
    fun togglePin(component: String) {
        val current = pinnedFlow.value
        val next = when {
            component in current -> current - component
            current.size >= MAX_PINNED -> return
            else -> current + component
        }
        pinnedFlow.value = next
        AppSettings.putString(app, KEY_PINNED, next.joinToString(","))
    }

    fun setAppOrder(ids: List<String>) {
        appOrderFlow.value = ids
        AppSettings.putString(app, KEY_APP_ORDER, ids.joinToString(","))
    }
}
