package app.autospace

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import android.content.res.Configuration
import app.autospace.audio.AudioCaptureService
import app.autospace.audio.CarAudio
import app.autospace.mirror.MirrorDisplayState
import app.autospace.mirror.MirrorExitTracker
import app.autospace.mirror.MirrorGeometry
import app.autospace.mirror.MirrorService
import app.autospace.mirror.PhoneMirror
import app.autospace.mirror.TouchRelayService
import app.autospace.car.CarEnvironment
import app.autospace.car.CarSettings
import app.autospace.car.MirrorFit
import app.autospace.car.CarUi
import app.autospace.car.Prefetch
import app.autospace.stats.UsageStats
import app.autospace.ui.GearslipApp
import app.autospace.ui.GearslipTheme
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Hosts the phone UI and, for now, the car session itself.
 *
 * Launched by the system when the head unit switches the phone into accessory mode. The
 * session lives in this activity, so [android:configChanges] in the manifest keeps a
 * rotation or theme change from recreating it and dropping the connection.
 */
class GearslipActivity : ComponentActivity(), Projection {
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())


    private var descriptor: ParcelFileDescriptor? = null
    /** The wireless session's socket, when the car came over Wi-Fi instead of USB. */
    private var wirelessSocket: java.net.Socket? = null
    private var runner: GearslipRunner? = null
    private var screenProjector: ScreenProjector? = null
    private var worker: Thread? = null

    /** The encoder's input Surface, kept so the car's picture can be switched to the mirror. */
    private var surface: Surface? = null
    private var frameWidth = 0
    private var frameHeight = 0
    private var frameDensity = 0

    /**
     * Screen capture always begins with a consent dialog, which only an activity can show -
     * so the car UI asks, and the request surfaces here on the phone.
     */
    private val captureConsent = registerForActivityResult(StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            GearslipLog.i("mirror capture consent granted, starting mirror service")
            MirrorService.start(this, result.resultCode, data)
        } else {
            GearslipLog.w("screen capture consent declined (result=${result.resultCode}, data present=${data != null})")
        }
    }

    /** Media audio needs the microphone permission (a platform rule for playback capture) first. */
    private val audioPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) askAudioCapture()
        else if (!shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO)) {
            // Refused twice: Android now says no without showing anything, so the phone must.
            GearslipLog.w("audio: record permission blocked - Android no longer asks")
            CarAudio.micBlocked()
            micBlockedNotice.value = true
        } else {
            GearslipLog.w("audio: record permission declined")
            CarAudio.denied()
        }
    }

    private val audioConsent = registerForActivityResult(StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            AudioCaptureService.start(this, result.resultCode, data)
        } else {
            GearslipLog.w("audio: capture consent declined")
            CarAudio.denied()
            if (!CarSettings.audioChoiceAsked) offerBluetooth.value = true
        }
    }

    /** Raised when the microphone permission is blocked; see [MicBlockedDialog]. */
    private val micBlockedNotice = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Raised once, after the first "no" to the capture dialog; see [AudioChoiceDialog]. */
    private val offerBluetooth = kotlinx.coroutines.flow.MutableStateFlow(false)

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_DETACHED) {
                GearslipLog.w("accessory detached")
                // A wireless session has no accessory to lose; leave it running.
                if (wirelessSocket == null) closeAccessory()
            }
        }
    }

    /** Setting Gearslip as the phone's caller ID and spam app - what lets it decline a ringing call. */
    private val callScreeningRole = registerForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) GearslipLog.w("calls: call screening role declined")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Location, calendar, the phone/contacts group, and the microphone are asked for one at
        // a time by PermissionsSetupScreen instead of here, each only after the driver taps
        // Allow on a screen explaining what it is for. Firing them all from onCreate, unprompted,
        // is both a bad first impression and unreliable: Android does not consistently show more
        // than one system permission dialog fired back-to-back, which was silently dropping some.
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // CrashReporter first: its unclean-shutdown check must see
        // the PREVIOUS session's log file as the newest one, which is
        // only true before GearslipLog.init creates this run's file;
        // and its Java handler must be installed before
        // GearslipLog.installCrashHandler so the chain runs
        // log-recording first, .txt second, system termination last.
        CrashReporter.install(this)
        GearslipLog.init(this)
        GearslipLog.installCrashHandler()
        GearslipLog.i("AutoSpace ready")
        // One-shot launch voice trace: only when armed in phone
        // Settings, and only on this fresh process start — the armed
        // flag plus VoiceTrace's own guard keep later onCreate calls
        // from restarting the 45-second window.
        app.autospace.speech.VoiceTrace.beginIfArmed(this)
        app.autospace.wireless.WirelessLink.onConnected = { socket ->
            runOnUiThread { startWireless(socket) }
        }
        CarSettings.init(this)
        // Private browsing (r21): a process that died with private tabs
        // open leaves its profile on disk — deleting it at startup is
        // the second half of "deleted when the last private tab closes".
        // A no-op where the WebView lacks multi-profile support.
        app.autospace.car.PrivateProfile.wipe()
        AppSettings.applyGearslipEnabled(this)
        // A capture that died without tidying up would leave notifications exposed to every
        // later screen share. Nothing is capturing if the service is not running, so undo it.
        if (!app.autospace.audio.AudioCaptureService.running) {
            app.autospace.audio.ScreenShareGuard.restore(this)
        }
        Prefetch.warm(this)
        CarEnvironment.setPhoneTheme(resources.configuration)

        setContent {
            GearslipTheme {
                GearslipApp(onDisconnect = { closeAccessory() }, onRequestCallScreening = ::requestCallScreeningRole)
                val offer by offerBluetooth.collectAsState()
                if (offer) {
                    fun close() {
                        CarSettings.markAudioChoiceAsked()
                        offerBluetooth.value = false
                    }
                    app.autospace.ui.AudioChoiceDialog(
                        onUseBluetooth = {
                            CarSettings.setPipeAudio(false)
                            close()
                        },
                        onKeepAsking = ::close,
                    )
                }
                val micNotice by micBlockedNotice.collectAsState()
                if (micNotice) {
                    app.autospace.ui.MicBlockedDialog(
                        onOpenSettings = {
                            micBlockedNotice.value = false
                            startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
                            )
                        },
                        onDismiss = { micBlockedNotice.value = false },
                    )
                }
            }
        }

        PhoneMirror.onStartRequested = { runOnUiThread { requestCapture() } }
        PhoneMirror.onTokenReady = { token -> runOnUiThread { showMirror(token) } }
        PhoneMirror.onEnded = { runOnUiThread { showCarUi() } }
        CarAudio.onConsentRequested = { runOnUiThread { requestAudioCapture() } }

        registerReceiver(detachReceiver, IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED), RECEIVER_NOT_EXPORTED)
        handleIntent(intent)
    }

    // The manifest opts out of recreation on uiMode changes, so a light/dark switch on the phone
    // arrives here, and the car UI follows it.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        CarEnvironment.setPhoneTheme(newConfig)
    }

    // Throttled inside Prefetch, so this only does work if the last run was a while ago.
    override fun onStart() {
        super.onStart()
        Prefetch.warm(this)
        UsageStats.onAppOpened(this)
        app.autospace.update.UpdateChecker.onAppOpened(this)
        // Back from the app's settings with the microphone turned on: try car audio again.
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            CarAudio.micAllowed()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        app.autospace.wireless.WirelessLink.onConnected = null
        runCatching { unregisterReceiver(detachReceiver) }
        PhoneMirror.onStartRequested = null
        PhoneMirror.onTokenReady = null
        PhoneMirror.onEnded = null
        CarAudio.onConsentRequested = null
        CarAudio.release()
        PhoneMirror.requestStop()
        closeAccessory()
        GearslipLog.flush()
        // A clean activity destroy is the one clean exit the app can
        // actually observe: without this the next start would file an
        // unclean-shutdown report for a run that ended normally.
        CrashReporter.markCleanExit(this)
    }

    /** Launches the system dialog that offers Gearslip the call-screening role, if it isn't held already. */
    private fun requestCallScreeningRole() {
        val roleManager = getSystemService(android.app.role.RoleManager::class.java)
        if (roleManager == null || !roleManager.isRoleAvailable(android.app.role.RoleManager.ROLE_CALL_SCREENING)) {
            GearslipLog.w("calls: call screening role unavailable on this phone")
            return
        }
        if (roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING)) return
        runCatching {
            callScreeningRole.launch(roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_CALL_SCREENING))
        }.onFailure { GearslipLog.e("could not ask for the call screening role", it) }
    }

    // --- Media audio --------------------------------------------------------------------

    private fun requestAudioCapture() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            askAudioCapture()
        } else {
            audioPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun askAudioCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        runCatching { audioConsent.launch(manager.createScreenCaptureIntent()) }
            .onFailure {
                GearslipLog.e("could not ask for audio capture", it)
                CarAudio.denied()
            }
    }

    // --- Phone mirroring ----------------------------------------------------------------

    private fun requestCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        GearslipLog.i("mirror requesting capture consent dialog")
        runCatching { captureConsent.launch(manager.createScreenCaptureIntent()) }
            .onFailure { GearslipLog.e("could not ask for screen capture", it) }
    }

    /** A Surface has a single producer, so our own display must go before the mirror arrives. */
    private fun showMirror(token: MediaProjection) {
        // Phase 8: log every link of the common path, because the 2026-10-25
        // report (neither Fit nor Fill works, car back on fully functional
        // AutoSpace UI) is consistent with ANY of: no surface, a failed
        // display create, or a token that never arrived — and the old code
        // returned silently on a null surface, leaving no trace at all.
        // This runs in a real car session only: the phone preview never wires
        // PhoneMirror, so mirror logs here prove a real session, and their
        // absence in a test proves the request never reached the session.
        GearslipLog.i(
            "mirror showMirror: surface=${if (surface != null) "present" else "NULL"} " +
                "frame=${frameWidth}x${frameHeight}@${frameDensity}dpi " +
                "fit=${CarSettings.mirrorFit.value}"
        )
        val target = surface ?: run {
            GearslipLog.e("mirror showMirror aborted: no encoder surface yet; AutoSpace UI stays")
            return
        }
        screenProjector?.stop()
        screenProjector = null
        attemptMirror(token, target, retry = false)
    }

    /**
     * One mirror attempt: Fill first (the default, GL-cropped, with an exit
     * button), direct Fit if Fill cannot start. A failed begin never stops
     * its token (see PhoneMirror.beginInternal), so the Fit retry after a
     * failed Fill reuses a LIVE token — the old chain stopped it and the
     * retry was doomed before it ran. If the first attempt fails on BOTH
     * paths, one delayed retry runs before falling back to the car UI: the
     * old virtual display's producer may not have disconnected from the
     * encoder Surface yet when the mirror display is created immediately
     * after ScreenProjector.stop(), and that race fails Fit and Fill alike.
     */
    private fun attemptMirror(token: MediaProjection, target: Surface, retry: Boolean) {
        PhoneMirror.fillEnabled = CarSettings.mirrorFit.value == MirrorFit.FILL
        var ok = false
        if (PhoneMirror.fillEnabled) {
            ok = PhoneMirror.beginFill(this, token, target, frameWidth, frameHeight, frameDensity)
            if (!ok) PhoneMirror.fillEnabled = false
        }
        // Fit now ALSO goes through the GL renderer when it can: the
        // renderer is what draws the pill/chrome and applies zoom/pan,
        // and the direct path can draw nothing over the video. Only if
        // the renderer cannot start at all does Fit fall back to the
        // direct path (chrome/zoom then unavailable, but the mirror
        // still works — the old behaviour, not a black screen).
        if (!ok) {
            ok = PhoneMirror.beginDisplay(this, token, target, frameWidth, frameHeight, frameDensity, fill = false)
            if (ok) PhoneMirror.fillEnabled = false
        }
        if (!ok) PhoneMirror.begin(token, target, frameWidth, frameHeight, frameDensity)
        resetMirrorGestures()
        if (PhoneMirror.active.value) {
            GearslipLog.i("mirror live (${if (PhoneMirror.fillEnabled) "Fill" else "Fit"})${if (retry) " after retry" else ""}")
            PhoneMirror.launchPending(this)
        } else if (!retry) {
            GearslipLog.w("mirror first attempt failed on both paths, retrying once in 500ms")
            Handler(Looper.getMainLooper()).postDelayed(
                { attemptMirror(token, target, retry = true) },
                500,
            )
        } else {
            GearslipLog.e("mirror retry failed too; falling back to AutoSpace UI")
            showCarUi()
        }
    }

    private fun showCarUi() {
        resetMirrorGestures()
        val target = surface ?: return
        if (screenProjector != null) return
        val projector = ScreenProjector(this).also { screenProjector = it }
        projector.start(target, frameWidth, frameHeight, frameDensity) { CarUi() }
    }

    /**
     * Sends a head-unit touch to the phone, through [MirrorExitTracker].
     * Phase 10 mirror fix: the mirror stops ONLY on a deliberate tap --
     * DOWN and UP both in the Fill exit button, or both on Fit
     * letterbox padding (which maps to nothing on the phone). The old
     * code stopped on ANY finger-up over padding or the exit corner,
     * so a drag from content that ended on padding, or in the corner,
     * closed the session mid-gesture -- on a portrait phone in a
     * landscape car the Fit bars are most of the frame, so nearly any
     * touch outside the narrow content strip did it. The stop reason
     * is logged, so the next test shows which exit fired. The phone
     * notification's Stop action and the head-unit BACK key remain as
     * backup exits.
     */
    private val mirrorTouch = MirrorExitTracker()

    // --- Mirror chrome / zoom gestures (2026-10-25) -------------------------------
    //
    // Touch rules, in priority order, for a mirroring session on the
    // GL renderer path (direct-Fit fallback has no chrome and no zoom,
    // so it keeps the old single-finger relay exactly):
    //  1. Two fingers = a pinch: zoom 1x-3x about the frame centre,
    //     detected locally, never relayed to the phone as a pinch
    //     (mirror relay is single-finger only, and stays that way).
    //  2. The pill (top-centre) opens the chrome; chrome buttons
    //     (Fit/Fill, zoom out/in, reset) act locally. Chrome touches
    //     NEVER relay. Chrome auto-hides 3.5s after the last chrome
    //     interaction, like the video player's controls.
    //  3. While zoomed (> 1x), a single-finger drag PANS the view
    //     (local only); a tap without a drag still relays to the
    //     phone, mapped through the zoom/pan transform so it lands on
    //     the right control. At zoom 1x a drag relays as a swipe,
    //     exactly as before — pan exists only while zoomed.
    //  4. Otherwise (zoom 1x, no chrome touch): the old deliberate-tap
    //     exit + relay behaviour, untouched.
    private var pinchActive = false
    private var pinchStartDist = 0f
    private var pinchStartZoom = 1f
    private var pendingTapX = 0f
    private var pendingTapY = 0f
    private var pendingTap = false
    private var panning = false
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var chromePending = -1 // button index, or -2 = dismiss-tap, or -1 = none
    private var pillPending = false

    private val chromeHideRunnable = Runnable {
        MirrorDisplayState.chromeVisible = false
        PhoneMirror.setDisplayTransform()
    }

    private fun showMirrorChrome() {
        MirrorDisplayState.chromeVisible = true
        PhoneMirror.setDisplayTransform()
        mainHandler.removeCallbacks(chromeHideRunnable)
        mainHandler.postDelayed(chromeHideRunnable, 3_500)
    }

    private fun hideMirrorChrome() {
        mainHandler.removeCallbacks(chromeHideRunnable)
        MirrorDisplayState.chromeVisible = false
        PhoneMirror.setDisplayTransform()
    }

    private fun resetMirrorGestures() {
        pinchActive = false
        pendingTap = false
        panning = false
        chromePending = -1
        pillPending = false
        mainHandler.removeCallbacks(chromeHideRunnable)
    }

    private fun pushMirrorTransform() = PhoneMirror.setDisplayTransform()

    /** Handles a whole multi-touch event while mirroring; true when it was consumed here. */
    private fun handleMirrorTouch(action: Int, points: List<TouchPoint>): Boolean {
        if (!PhoneMirror.rendererActive) {
            // Direct-Fit fallback: no chrome, no zoom — old path, first finger only.
            points.firstOrNull()?.let { relayTouch(action, it.x, it.y) }
            return true
        }
        val (fw, fh) = PhoneMirror.frameSize()
        val frameW = fw.toFloat()
        val frameH = fh.toFloat()
        if (frameW <= 0f || frameH <= 0f) return true
        if (points.size >= 2) {
            // Pinch: zoom only, never relayed. A pinch cancels any
            // pending tap/pan/chrome gesture rather than completing it.
            val dx = points[0].x - points[1].x
            val dy = points[0].y - points[1].y
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            if (!pinchActive) {
                pinchActive = true
                pinchStartDist = dist
                pinchStartZoom = MirrorDisplayState.zoom
                pendingTap = false
                panning = false
                chromePending = -1
                pillPending = false
                // The first finger may already have relayed a DOWN to
                // the phone (zoom 1x path): end that gesture cleanly
                // with an UP instead of leaving a stuck touch down
                // while the pinch zooms locally. cancel() returns the
                // UP itself when one is owed, Swallow otherwise.
                when (val d = mirrorTouch.cancel()) {
                    is MirrorExitTracker.Decision.Relay -> TouchRelayService.instance?.up(d.x, d.y)
                    else -> Unit
                }
            } else if (pinchStartDist > 0f && dist > 0f) {
                MirrorDisplayState.setZoomClamped(pinchStartZoom * dist / pinchStartDist)
                pushMirrorTransform()
            }
            return true
        }
        if (pinchActive) {
            // The second finger just lifted: end the pinch, swallow
            // this event so it cannot become a stray tap.
            pinchActive = false
            return true
        }
        val pt = points.firstOrNull() ?: return true
        val x = pt.x
        val y = pt.y
        val pill = MirrorGeometry.pillRegion(frameW, frameH)
        val buttons = MirrorGeometry.chromeButtons(frameW, frameH)
        if (MirrorDisplayState.chromeVisible) {
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    when {
                        pill.contains(x, y) -> { pillPending = true }
                        else -> {
                            val idx = buttons.indexOfFirst { it.contains(x, y) }
                            chromePending = if (idx >= 0) idx else -2
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (chromePending >= 0 && !buttons[chromePending].contains(x, y)) chromePending = -1
                    if (pillPending && !pill.contains(x, y)) pillPending = false
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    when {
                        pillPending -> { pillPending = false; hideMirrorChrome() }
                        chromePending >= 0 -> {
                            val idx = chromePending
                            chromePending = -1
                            when (idx) {
                                0 -> {
                                    val fill = !MirrorDisplayState.fill
                                    MirrorDisplayState.fill = fill
                                    PhoneMirror.fillEnabled = fill
                                    CarSettings.setMirrorFit(if (fill) MirrorFit.FILL else MirrorFit.FIT)
                                }
                                1 -> MirrorDisplayState.setZoomClamped(MirrorDisplayState.zoom - 0.25f)
                                2 -> MirrorDisplayState.setZoomClamped(MirrorDisplayState.zoom + 0.25f)
                                3 -> {
                                    MirrorDisplayState.setZoomClamped(1f)
                                    MirrorDisplayState.panX = 0f
                                    MirrorDisplayState.panY = 0f
                                }
                            }
                            pushMirrorTransform()
                            showMirrorChrome() // acting restarts the 3.5s hide timer
                        }
                        chromePending == -2 -> { chromePending = -1; hideMirrorChrome() }
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    chromePending = -1
                    pillPending = false
                    return true
                }
            }
            return true
        }
        // Chrome closed: the pill is the only chrome entry point.
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (pill.contains(x, y)) {
                    pillPending = true
                    return true
                }
                if (MirrorDisplayState.zoom > 1f) {
                    // Zoomed: hold the DOWN — a drag from here pans,
                    // a tap without a drag relays (mapped through the
                    // zoom/pan transform) on UP.
                    pendingTap = true
                    panning = false
                    pendingTapX = x
                    pendingTapY = y
                    lastPanX = x
                    lastPanY = y
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pillPending) {
                    if (!pill.contains(x, y)) pillPending = false
                    return true
                }
                if (pendingTap) {
                    val dx = x - pendingTapX
                    val dy = y - pendingTapY
                    if (!panning && dx * dx + dy * dy > 12f * 12f) panning = true
                    if (panning) {
                        val phone = PhoneMirror.phoneSize(this)
                        if (phone != null) {
                            val base = if (MirrorDisplayState.fill) {
                                MirrorGeometry.fill(phone.x, phone.y, frameW, frameH)
                            } else {
                                MirrorGeometry.fit(phone.x, phone.y, frameW, frameH)
                            }
                            val scaledW = phone.x * base.scale * MirrorDisplayState.zoom
                            val scaledH = phone.y * base.scale * MirrorDisplayState.zoom
                            val (cx, cy) = MirrorGeometry.clampPan(
                                MirrorDisplayState.panX + (x - lastPanX),
                                MirrorDisplayState.panY + (y - lastPanY),
                                scaledW, scaledH, frameW, frameH,
                            )
                            MirrorDisplayState.panX = cx
                            MirrorDisplayState.panY = cy
                            pushMirrorTransform()
                        }
                        lastPanX = x
                        lastPanY = y
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (pillPending) {
                    pillPending = false
                    showMirrorChrome()
                    return true
                }
                if (pendingTap) {
                    pendingTap = false
                    if (!panning) {
                        // A tap while zoomed: relay it as a down+up at
                        // the zoom-mapped phone point (exit region and
                        // padding rules do not apply mid-tap: the tap
                        // started on content, so it ends on content).
                        val relay = TouchRelayService.instance
                        PhoneMirror.mapToPhone(this, x, y)?.let { p ->
                            relay?.down(p.x, p.y)
                            relay?.up(p.x, p.y)
                        }
                    }
                    panning = false
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pillPending = false
                pendingTap = false
                panning = false
                return true
            }
        }
        relayTouch(action, x, y)
        return true
    }

    private fun relayTouch(action: Int, x: Float, y: Float) {
        val inExit = PhoneMirror.isInExitRegion(x, y)
        val map: (Float, Float) -> Pair<Float, Float>? = { fx, fy ->
            PhoneMirror.mapToPhone(this, fx, fy)?.let { it.x to it.y }
        }
        val decision = when (action) {
            MotionEvent.ACTION_DOWN -> mirrorTouch.down(x, y, inExit, map)
            MotionEvent.ACTION_MOVE -> mirrorTouch.move(x, y, inExit, map)
            MotionEvent.ACTION_UP -> mirrorTouch.up(x, y, inExit, map)
            MotionEvent.ACTION_CANCEL -> mirrorTouch.cancel()
            else -> MirrorExitTracker.Decision.Swallow
        }
        when (decision) {
            is MirrorExitTracker.Decision.Stop -> {
                GearslipLog.i(
                    "mirror: stopping on deliberate tap (" +
                        if (decision.reason == MirrorExitTracker.StopReason.EXIT_BUTTON) "fill exit button" else "fit letterbox padding" +
                        ")"
                )
                PhoneMirror.requestStop()
            }
            is MirrorExitTracker.Decision.Swallow -> Unit
            is MirrorExitTracker.Decision.Relay -> {
                val relay = TouchRelayService.instance ?: return
                when (decision.action) {
                    MirrorExitTracker.RelayAction.DOWN -> relay.down(decision.x, decision.y)
                    MirrorExitTracker.RelayAction.MOVE -> relay.move(decision.x, decision.y)
                    MirrorExitTracker.RelayAction.UP -> relay.up(decision.x, decision.y)
                }
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED) return
        // Switched off in Settings: the system shouldn't route the car here at all, but if it
        // does, leave the accessory alone.
        if (!AppSettings.gearslipEnabled(this)) {
            GearslipLog.i("accessory attached, but AutoSpace is switched off - not connecting")
            return
        }
        val accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        if (accessory == null) {
            GearslipLog.e("attach intent carried no accessory")
            SessionStatus.failed("Accessory missing", "The system reported a USB attach but did not include the accessory.")
            return
        }
        GearslipLog.i("accessory attached by the system - permission is implicit on this path")
        start(accessory)
    }

    private fun start(accessory: UsbAccessory) {
        // Test mode auto-OFF (Robert, 2026-10-25): a real accessory
        // connection ends any simulated session at once, so a test
        // flag can never ride into a real drive.
        app.autospace.car.TestMode.deactivate()
        // A fresh ATTACHED intent means the system wants a new session, whatever state the old
        // one is in - closing first guarantees openAccessory() below never fights a descriptor
        // this same process forgot to release. The old worker thread often isn't alive by this
        // point anyway (its read loop already died when the head unit dropped the transport),
        // but the accessory it opened was never explicitly closed, which is what actually blocks
        // a re-open - a dead thread does not release the file descriptor on its own.
        closeAccessory()
        describe(accessory)

        val manager = getSystemService(UsbManager::class.java)
        val pfd = manager.openAccessory(accessory)
        if (pfd == null) {
            GearslipLog.e("openAccessory returned null - the session was claimed by someone else")
            SessionStatus.failed("Accessory unavailable", "Another app claimed the USB accessory before AutoSpace could open it.")
            return
        }
        descriptor = pfd

        val runner = GearslipRunner(
            input = FileInputStream(pfd.fileDescriptor),
            output = FileOutputStream(pfd.fileDescriptor),
            identityProvider = { CertProvider.load(this) },
            projection = this,
            vehicleProfileFor = { info -> VehicleProfiles.find(VehicleProfiles.load(this), info) },
        )
        this.runner = runner
        worker = Thread { runner.run() }.apply { name = "gearslip"; start() }
    }

    /** A wireless session: the same runner, on the socket [WirelessLink] opened to the car. */
    private fun startWireless(socket: java.net.Socket) {
        closeAccessory(stopWireless = false)
        if (!AppSettings.gearslipEnabled(this)) {
            GearslipLog.i("wireless: AutoSpace is switched off - not connecting")
            runCatching { socket.close() }
            return
        }
        // Test mode auto-OFF, same as the wired path above.
        app.autospace.car.TestMode.deactivate()
        GearslipLog.i("wireless: starting a session over Wi-Fi to ${socket.inetAddress?.hostAddress}:${socket.port}")
        wirelessSocket = socket
        val runner = GearslipRunner(
            input = socket.getInputStream(),
            output = socket.getOutputStream(),
            identityProvider = { CertProvider.load(this) },
            projection = this,
            vehicleProfileFor = { info -> VehicleProfiles.find(VehicleProfiles.load(this), info) },
            wireless = true,
        )
        this.runner = runner
        worker = Thread { runner.run() }.apply { name = "gearslip"; start() }
    }

    private fun describe(accessory: UsbAccessory) {
        GearslipLog.i("accessory: manufacturer=${accessory.manufacturer} model=${accessory.model}")
        GearslipLog.i("           version=${accessory.version} description=${accessory.description}")
    }

    private fun closeAccessory() = closeAccessory(stopWireless = true)

    /** Ends the session, wired or wireless. [stopWireless] also drops the car's Bluetooth and Wi-Fi. */
    private fun closeAccessory(stopWireless: Boolean) {
        runner?.stop()
        SessionReport.print()
        runCatching { descriptor?.close() }
        descriptor = null
        runCatching { wirelessSocket?.close() }
        wirelessSocket = null
        if (stopWireless) app.autospace.wireless.WirelessLink.stop(this)
        runner = null
    }

    // --- Projection ---------------------------------------------------------------------

    override fun onSurfaceReady(surface: Surface, width: Int, height: Int, densityDpi: Int) {
        runOnUiThread {
            this.surface = surface
            frameWidth = width
            frameHeight = height
            frameDensity = densityDpi
            CarEnvironment.setDisplay(width, height, densityDpi)
            CarEnvironment.start(this)
            // This is a REAL session now (the preview never calls
            // onSurfaceReady): mark it, and drop any simulated-driving
            // override back to Automatic — belt and braces with the
            // realSession gate in CarMotion.moving.
            CarEnvironment.realSession = true
            app.autospace.notify.CarMotion.setSimMode(app.autospace.notify.CarMotion.SimMode.AUTO)
            showCarUi()
            app.autospace.car.CarKeys.focusSink = { code, down ->
                runOnUiThread { screenProjector?.dispatchKey(code, down) }
            }
        }
    }

    override fun onTouch(action: Int, actionIndex: Int, points: List<TouchPoint>) {
        // To the front of the main thread's queue: a touch waiting behind other work is lag the
        // driver feels, while the work it jumps ahead of can wait a frame.
        mainHandler.postAtFrontOfQueue {
            // Mirroring: the multi-touch handler owns pinch, pill,
            // chrome, and zoomed pan; single-finger relay inside it
            // still drives one phone touch at a time, as before.
            if (PhoneMirror.active.value) handleMirrorTouch(action, points)
            else screenProjector?.dispatchTouch(action, actionIndex, points)
        }
    }

    // --- Phone-paired controller input (r93) -------------------------------
    //
    // A Bluetooth controller pairs with the PHONE, so its keys and
    // motion arrive at this activity's window, not at the virtual
    // display's: the car composition never sees them on its own.
    // While a car session is projecting, gamepad-class input is
    // forwarded to the projector, where keys reach the focused row
    // or the playback screen and motion reaches the playback
    // screen's registered handler. Gated on a live projector, so
    // controller input on the phone's own screens is untouched,
    // and a session with no projector never forwards anything.
    //
    // Scope lock (r95): the controller only ever drives a game
    // that is actually open. The playback screen registers its
    // motion sink while it is up and clears it on leave, so the
    // sink's presence is the "a game is open" signal: with it,
    // keys and motion go to the playback screen; without it, the
    // events are swallowed here, so the controller never moves
    // UI focus, backs out of Games, or closes anything. With no
    // projector, phone screens are untouched exactly as before.
    private fun isGamepadInput(source: Int): Boolean =
        source and android.view.InputDevice.SOURCE_GAMEPAD ==
            android.view.InputDevice.SOURCE_GAMEPAD ||
            source and android.view.InputDevice.SOURCE_JOYSTICK ==
                android.view.InputDevice.SOURCE_JOYSTICK ||
            source and android.view.InputDevice.SOURCE_DPAD ==
                android.view.InputDevice.SOURCE_DPAD

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val projector = screenProjector
        if (projector != null && isGamepadInput(event.source)) {
            if (app.autospace.car.GameMotionSink.handler != null) {
                projector.dispatchControllerKey(keyCode, true, event.source)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val projector = screenProjector
        if (projector != null && isGamepadInput(event.source)) {
            if (app.autospace.car.GameMotionSink.handler != null) {
                projector.dispatchControllerKey(keyCode, false, event.source)
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val projector = screenProjector
        if (projector != null && isGamepadInput(event.source)) {
            if (app.autospace.car.GameMotionSink.handler != null) {
                projector.dispatchMotion(event)
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onProjectionStopped() {
        // Before the car UI goes, while the media connection is still up.
        app.autospace.car.CarServices.pausePhoneMedia()
        runOnUiThread {
            PhoneMirror.requestStop()
            app.autospace.car.CarKeys.focusSink = null
            screenProjector?.stop()
            screenProjector = null
            surface = null
            CarEnvironment.stop()
            CarEnvironment.realSession = false
        }
    }
}
