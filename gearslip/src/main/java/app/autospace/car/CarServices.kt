package app.autospace.car

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import app.autospace.audio.CarAudio
import app.autospace.host.CarAppCatalog
import app.autospace.host.CarAppConnection
import app.autospace.host.KnownApps
import app.autospace.host.TemplateApp
import app.autospace.media.CarMedia
import app.autospace.media.MediaApp
import app.autospace.media.MediaCatalog
import app.autospace.notify.GearslipNotificationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The two long-lived things the car keeps running: the map app and the media app.
 *
 * They belong to the car session rather than to whichever screen is showing, so the home
 * screen, the Car apps list and the media screen all look at the same connection. Leaving a
 * screen must not drop the route being followed or the song being played.
 */
object CarServices {

    private var context: Context? = null

    val nav: CarAppConnection by lazy { CarAppConnection(requireNotNull(context)) }
    val media: CarMedia by lazy { CarMedia(requireNotNull(context)) }

    /** [nav], or null before the car screen has set things up. */
    fun navOrNull(): CarAppConnection? = if (context != null) nav else null

    /** [media], or null before the car screen has set things up: for code outside the car UI. */
    fun mediaOrNull(): CarMedia? = if (context != null) media else null

    /**
     * A second, independent connection for templated apps that aren't navigation apps - Spotify's
     * own Car App Library service is the first of these. Kept apart from [nav] so opening one
     * can never bump the map off Home: this one gets its own screen ([CarScreen.Browse]) instead.
     */
    val browse: CarAppConnection by lazy { CarAppConnection(requireNotNull(context)) }

    private val _mediaApp = MutableStateFlow<MediaApp?>(null)
    val mediaApp: StateFlow<MediaApp?> = _mediaApp.asStateFlow()

    /**
     * Whether the phone has any navigation app installed at all. True until [autostart] has
     * actually checked, so the home screen never flashes "none found" before it knows.
     */
    private val _hasNavApps = MutableStateFlow(true)
    val hasNavApps: StateFlow<Boolean> = _hasNavApps.asStateFlow()

    /** Set when the driver stops the map by hand, so going Home doesn't immediately restart it. */
    private var navStopped = false
    private var autoplayPending = false

    /**
     * r62 (A50): elapsedRealtime when autoplay last started playback, so a
     * prompt pause can be recognized as stopping autoplay. Null when no
     * autoplay session is live. Cleared on any user pause or play, on
     * close, and on shutdown — a stop only ever counts against the
     * autoplay that started it, never against a later session.
     */
    private var autoplaySessionStartMs: Long? = null

    /** r62 (A50): true while the turn-autoplay-off offer is up on Home. */
    private val _autoplayOfferVisible = MutableStateFlow(false)
    val autoplayOfferVisible: StateFlow<Boolean> = _autoplayOfferVisible.asStateFlow()

    fun init(appContext: Context) {
        context = appContext.applicationContext
        app.autospace.notify.CarMotion.start(appContext)
        app.autospace.call.CarCalls.start(appContext)
        CarAssistant.init(appContext)
        DirectionsVoice.init(appContext)
        CarBluetooth.init(appContext)
        BluetoothAudioCheck.init(appContext)
        LocationCheck.init(appContext)
        CarKeys.init(appContext)
        VoiceReply.init(appContext)
        // Loaded now, while nothing's waiting on it, so the first reply isn't slower than the rest.
        app.autospace.speech.SpeechEngine.warm(appContext)
        // Hotword: wired here, gated inside HotwordListener itself. The session check
        // is a real session (CarEnvironment.isRunning — also true in the phone
        // preview, since the preview calls CarEnvironment.start) OR test mode
        // (see ensureTestHotword). The loop logic itself is unchanged by
        // test mode; it only ever starts under a session source.
        configureHotword()
        app.autospace.speech.HotwordListener.start(appContext)
    }

    /**
     * The one hotword wiring, shared by a real session ([init]) and by
     * test mode ([ensureTestHotword]) so the two can never drift.
     */
    private fun configureHotword() {
        app.autospace.speech.HotwordListener.configure(
            sessionCheck = { CarEnvironment.isRunning || TestMode.active.value },
            // Suspended only while the assistant is actively busy or a call is live.
            // ERROR is deliberately not a suspension (2026-10-25): it is a settled
            // state, not activity — the old `phase != IDLE` check kept the hotword
            // dead for the rest of a session after any listen error ("Didn't catch
            // that", a silent render, no voice), while a button press could still
            // start the assistant from ERROR. cancel() is otherwise the only way
            // out of ERROR, and it runs only at session shutdown.
            suspendCheck = {
                val phase = CarAssistant.state.value.phase
                phase == CarAssistant.Phase.LISTENING ||
                    phase == CarAssistant.Phase.THINKING ||
                    phase == CarAssistant.Phase.SPEAKING ||
                    app.autospace.call.CarCalls.state.value is app.autospace.call.CallUiState.Active
            },
            onHotword = { CarAssistant.start() },
        )
    }

    /**
     * Test mode's entry point (Robert, 2026-10-25): (re)applies the
     * shared hotword wiring and starts the loop under test mode.
     * [HotwordListener.start] is start-guarded, so calling this when
     * a real or preview session already started the loop is a
     * no-op. It does NOT change the loop: the gates, the recognizer
     * and the trigger are exactly the real session's.
     */
    fun ensureTestHotword(context: android.content.Context) {
        configureHotword()
        app.autospace.speech.HotwordListener.start(context.applicationContext)
    }

    // --- map -----------------------------------------------------------------------------

    /**
     * [destination] is a search query to start navigating to right away - "navigate to X with Y"
     * from the voice assistant - left null for a plain app launch. An app that's already the one
     * running gets the destination sent to its live session instead of being torn down and
     * rebuilt, same as [CarAppConnection.navigateTo] documents.
     */
    fun connectNav(app: TemplateApp, frame: CarEnvironment.Frame, destination: String? = null) {
        navStopped = false
        CarSettings.setLastNav(app.component.flattenToString())
        if (destination != null && nav.connectedComponent == app.component) {
            nav.navigateTo(destination)
        } else {
            // Another map app taking over ends the old one's route, or it keeps guiding unseen.
            if (nav.connectedComponent != app.component) endRoute()
            nav.connect(app, frame.width, frame.height, frame.densityDpi, destination)
        }
    }

    fun stopNav() {
        navStopped = true
        endRoute()
        nav.disconnect()
    }

    private fun endRoute() {
        if (nav.navigating) nav.stopNavigating()
    }

    /** Undoes [stopNav]'s hold so the last map app comes back, same as it would after a restart. */
    suspend fun reconnectNav(frame: CarEnvironment.Frame) {
        navStopped = false
        autostart(frame)
    }

    // --- browse (non-navigation templated apps) -------------------------------------------

    /** Opens a templated app that isn't navigation, on its own screen - never on Home. */
    fun connectBrowse(app: TemplateApp, frame: CarEnvironment.Frame) {
        browse.connect(app, frame.width, frame.height, frame.densityDpi)
    }

    fun stopBrowse() {
        browse.disconnect()
    }

    // --- media ---------------------------------------------------------------------------

    fun openMedia(app: MediaApp, autoplay: Boolean = false, userInitiated: Boolean = true) {
        CarSettings.setLastMedia(app.component.flattenToString())
        // r62 (A49): only a real use refreshes recency — autostart's automatic
        // reopen must not, or a stale app could never go stale.
        if (userInitiated) CarSettings.setLastMediaUsedMs(System.currentTimeMillis())
        val phase = media.phase.value
        val live = phase == CarMedia.Phase.READY || phase == CarMedia.Phase.CONNECTING
        if (_mediaApp.value?.component == app.component && live) return

        _mediaApp.value = app
        autoplayPending = autoplay
        media.connect(app)
        // Not torn down and rebuilt per app: the capture covers whatever the phone plays, so
        // switching media apps never costs the driver another consent dialog.
        if (CarSettings.pipeAudio.value) CarAudio.request()
    }

    /**
     * Reopens the media app used last, for the car's media button when nothing is open. False
     * when there's none on record or it's been uninstalled since.
     */
    suspend fun reopenLastMedia(): Boolean {
        val ctx = context ?: return false
        val last = CarSettings.lastMedia.value?.let(ComponentName::unflattenFromString) ?: return false
        val app = withContext(Dispatchers.IO) { MediaCatalog.installed(ctx) }
            .firstOrNull { it.component == last } ?: return false
        openMedia(app)
        return true
    }

    /** Closes the media app from the car: stops what it's playing and drops the connection. */
    fun closeMedia() {
        // r62 (A50): closing a playing app stops autoplay just as pausing
        // does; closing a silent one just ends the session's attribution.
        if (media.now.value.playing) recordAutoplayStopIfDue() else autoplaySessionStartMs = null
        if (media.now.value.playing) media.togglePlay()
        media.disconnect()
        _mediaApp.value = null
        autoplayPending = false
    }

    /** Called once the media app is connected: starts playback if that was asked for. */
    fun autoplayIfDue() {
        if (!autoplayPending) return
        autoplayPending = false
        if (!media.now.value.playing) {
            media.togglePlay()
            // r62 (A50): the session starts here, when autoplay actually
            // starts playback — not when it was merely asked for.
            autoplaySessionStartMs = SystemClock.elapsedRealtime()
        }
    }

    /**
     * r62 (A49/A50): the car UI's play/pause button, for the card, the nav
     * pill, the steering-wheel keys and voice resume — every driver
     * press, never the app's own state changes. A pause within
     * [AutoplayPolicy.STOP_WINDOW_MS] of an autoplay start counts as
     * stopping autoplay; three such stops raise the offer. A play press
     * ends the session's attribution and refreshes recency: what plays
     * now is the driver's choice, not autoplay's.
     */
    fun userTogglePlay() {
        if (media.now.value.playing) {
            recordAutoplayStopIfDue()
        } else {
            autoplaySessionStartMs = null
            if (_mediaApp.value != null) CarSettings.setLastMediaUsedMs(System.currentTimeMillis())
        }
        media.togglePlay()
    }

    /**
     * r62 (A50): voice "pause" — a real pause, not a toggle. The
     * assistant's own after-release pause stays on media.pause()
     * directly: that one is the system keeping music down, not the
     * driver stopping autoplay.
     */
    fun userPause() {
        if (media.now.value.playing) recordAutoplayStopIfDue() else autoplaySessionStartMs = null
        media.pause()
    }

    private fun recordAutoplayStopIfDue() {
        val start = autoplaySessionStartMs
        autoplaySessionStartMs = null
        if (!AutoplayPolicy.countsAsAutoplayStop(start, SystemClock.elapsedRealtime())) return
        val stops = CarSettings.autoplayStops.value + 1
        CarSettings.setAutoplayStops(stops)
        if (AutoplayPolicy.shouldOffer(stops) && CarSettings.autoplay.value) {
            _autoplayOfferVisible.value = true
        }
    }

    /** r62 (A50): offer answer — turn autoplay off and clear the count. */
    fun autoplayOfferTurnOff() {
        _autoplayOfferVisible.value = false
        CarSettings.setAutoplayStops(0)
        CarSettings.setAutoplay(false)
    }

    /**
     * r62 (A50): offer dismissed or kept — the count resets either way,
     * so the driver is asked again only after three more prompt stops,
     * never every drive.
     */
    fun autoplayOfferKeep() {
        _autoplayOfferVisible.value = false
        CarSettings.setAutoplayStops(0)
    }

    // --- start and stop ------------------------------------------------------------------

    /** Brings back whatever was in use last time. Safe to call repeatedly. */
    suspend fun autostart(frame: CarEnvironment.Frame) {
        val ctx = context ?: return

        if (!navStopped && nav.status.value.phase == CarAppConnection.Phase.IDLE) {
            val navApps = withContext(Dispatchers.IO) { CarAppCatalog.installed(ctx) }
                .filter { it.isNavigation }
            _hasNavApps.value = navApps.isNotEmpty()
            // Never auto-pick one already known to fail outright - a driver who plugs in should
            // land on a map, not on the one tile marked with a red X.
            val candidates = navApps.filterNot { KnownApps.isBroken(ctx, it.component.packageName) }
            val last = CarSettings.lastNav.value
            // The one used last, or - first run, nothing on record yet - whichever comes first:
            // a driver who plugs in for the first time shouldn't have to go pick one by hand
            // before the map shows up at all.
            val lastComponent = last?.let(ComponentName::unflattenFromString)
            val app = candidates.firstOrNull { it.component == lastComponent } ?: candidates.firstOrNull()
            if (app != null) connectNav(app, frame)
        }

        if (_mediaApp.value == null) {
            val catalog = withContext(Dispatchers.IO) { MediaCatalog.installed(ctx) }
            // Something already playing on the phone wins over whatever was last used in the car -
            // a driver who started a song before plugging in shouldn't have to go open its tile by
            // hand just to see what's already going.
            val playing = withContext(Dispatchers.IO) { currentlyPlaying(ctx, catalog) }
            if (playing != null) {
                openMedia(playing)
            } else {
                val last = CarSettings.lastMedia.value
                val app = last?.let { id -> catalog.firstOrNull { it.component == ComponentName.unflattenFromString(id) } }
                // r62 (A49): a stale last app still opens, but it is no
                // longer selected for autoplay — playback only auto-starts
                // when the app was used recently. Unknown recency (0, e.g.
                // upgraded installs) counts as stale: one manual open
                // re-establishes it.
                val recent = AutoplayPolicy.isRecent(
                    CarSettings.lastMediaUsedMs.value, System.currentTimeMillis(),
                )
                if (app != null) openMedia(
                    app,
                    autoplay = CarSettings.autoplay.value && recent,
                    userInitiated = false,
                )
            }
        }
    }

    /**
     * Whichever known car media app has an actively playing session right now, system-wide - not
     * just the one Gearslip itself last connected to. Reading another app's session needs
     * notification access, the same permission [app.autospace.media.CarMedia]
     * already asks for to control apps that won't share their library.
     */
    private fun currentlyPlaying(ctx: Context, catalog: List<MediaApp>): MediaApp? {
        val manager = ctx.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(ctx, GearslipNotificationListener::class.java)
        val sessions = try {
            manager.getActiveSessions(listener)
        } catch (e: SecurityException) {
            return null
        }
        val playingPackages = sessions
            .filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            .map { it.packageName }
        return playingPackages.firstNotNullOfOrNull { pkg -> catalog.firstOrNull { it.component.packageName == pkg } }
    }

    /**
     * The car went away mid-song: pause it, as Android Auto does, so it doesn't carry on out of
     * the phone's speaker. Unplugging a USB accessory isn't "audio becoming noisy" the way
     * headphones are, so the apps won't pause themselves. Every playing session is paused, not
     * just the one Gearslip opened: anything the car was playing was going through the car.
     */
    fun pausePhoneMedia(why: String = "the car left") {
        val ctx = context ?: return
        val manager = ctx.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(ctx, GearslipNotificationListener::class.java)
        val playing = try {
            manager.getActiveSessions(listener).filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        } catch (e: SecurityException) {
            null
        }
        if (playing == null) {
            // No notification access: the app Gearslip itself is driving is all it can reach.
            mediaOrNull()?.let { if (it.now.value.playing) it.togglePlay() }
            return
        }
        playing.forEach { runCatching { it.transportControls.pause() } }
        if (playing.isNotEmpty()) {
            app.autospace.GearslipLog.i("media: paused ${playing.joinToString { it.packageName }}: $why")
        }
    }

    /** The car went away: nothing should keep running for a screen that no longer exists. */
    fun shutdown() {
        // Hotword first: its microphone must close the moment the session ends, even
        // if something below throws. The loop thread exits on its own.
        app.autospace.speech.HotwordListener.stop()
        context?.let { app.autospace.notify.CarMotion.stop(it) }
        app.autospace.call.CarCalls.stop()
        runCatching { nav.disconnect() }
        runCatching { media.disconnect() }
        runCatching { browse.disconnect() }
        CarAssistant.cancel()
        // About 900 MB that nothing needs once the car's gone.
        app.autospace.speech.SpeechEngine.unload()
        CarAudio.release()
        _mediaApp.value = null
        navStopped = false
        autoplaySessionStartMs = null
        _autoplayOfferVisible.value = false
    }
}
