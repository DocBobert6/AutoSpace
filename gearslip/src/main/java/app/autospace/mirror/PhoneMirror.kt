package app.autospace.mirror

import android.content.Context
import android.graphics.PointF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.view.Surface
import android.view.WindowManager
import app.autospace.GearslipLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mirrors the phone's screen into the head unit's video stream.
 *
 * The platform will not let a normal app render another app onto a display it owns (an
 * activity may only be placed on a virtual display whose owner shares its uid, and doing it
 * for someone else's app needs INTERNAL_SYSTEM_WINDOW - see ScreenProjector.launchApp).
 * Mirroring sidesteps that: the app runs where it always does, on the phone's own display,
 * and MediaProjection copies that display into the encoder Surface we are already feeding to
 * the car. Touch makes the return trip through [TouchRelayService].
 *
 * The MediaProjection token is produced by [MirrorService], because Android only hands one
 * over while a foreground service of type mediaProjection is running.
 */
object PhoneMirror {

    private val _active = MutableStateFlow(false)

    /** True while the car is showing the phone rather than Gearslip's own car UI. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private var display: VirtualDisplay? = null
    private var media: MediaProjection? = null
    private var frameWidth = 0
    private var frameHeight = 0
    private var fillRenderer: MirrorFillRenderer? = null

    /**
     * Whether touches and video use Fill geometry. Set by the activity from
     * [app.autospace.car.CarSettings.mirrorFit] before mirroring starts; Fill is
     * the default. Fit keeps the old letterbox behaviour exactly.
     */
    var fillEnabled: Boolean = false

    /**
     * Wired up by the session, which owns the encoder Surface and so is the only thing that
     * can swap what draws into it. All three are invoked on the main thread.
     */
    internal var onStartRequested: (() -> Unit)? = null
    internal var onTokenReady: ((MediaProjection) -> Unit)? = null
    internal var onEnded: (() -> Unit)? = null

    /** Asks the phone to put up the screen-capture consent dialog. Safe to call from the car. */
    fun requestStart() {
        // A null callback is itself diagnostic: the phone preview never wires
        // mirroring, so a start requested there silently does nothing — log it
        // loudly instead of letting the car sit on its own UI wondering why.
        if (onStartRequested == null) {
            GearslipLog.e("mirror start requested but no session wired (car session not running?)")
        } else {
            GearslipLog.i("mirror start requested, asking for capture consent")
            onStartRequested?.invoke()
        }
    }

    fun requestStop() = MirrorService.stop()

    private var pendingLaunch: String? = null

    /**
     * Shows [packageName] on the car screen.
     *
     * When mirroring has not started yet the app is remembered rather than launched, because
     * consent has to be granted while Gearslip is still the foreground app - starting the
     * other app first would push us into the background, where no dialog can be shown.
     */
    fun open(context: Context, packageName: String) {
        if (_active.value) {
            GearslipLog.i("mirror already live, launching $packageName")
            PhoneApps.launch(context, packageName)
        } else {
            GearslipLog.i("mirror open: remembering $packageName as pending launch, requesting start")
            pendingLaunch = packageName
            requestStart()
        }
    }

    /** Called by the session once the mirror is live. */
    fun launchPending(context: Context) {
        val packageName = pendingLaunch ?: run {
            GearslipLog.i("mirror live with no pending launch (phone screen as-is)")
            return
        }
        pendingLaunch = null
        GearslipLog.i("mirror live, launching pending $packageName")
        PhoneApps.launch(context, packageName)
    }

    /**
     * Points [surface] at the phone's screen. The caller must have released whatever else was
     * drawing into that Surface first - a Surface has one producer.
     */
    fun begin(token: MediaProjection, surface: Surface, width: Int, height: Int, densityDpi: Int) {
        GearslipLog.i("mirror begin (Fit) into ${width}x$height @ ${densityDpi}dpi")
        end()
        beginInternal(token, surface, width, height, densityDpi)
    }

    /**
     * The display-creation half of [begin], assuming the previous session
     * is already ended. [beginFill] calls this directly after ending first
     * — going through [begin] here would run [end] again and release the
     * renderer [beginFill] just created (the 2026-10-24 Fill self-destruct:
     * the virtual display was left on a released surface and no frame ever
     * arrived, while _active was already true so no fallback ran either).
     */
    private fun beginInternal(token: MediaProjection, surface: Surface, width: Int, height: Int, densityDpi: Int) {
        media = token
        frameWidth = width
        frameHeight = height

        token.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                GearslipLog.w("media projection stopped (revoked or replaced)")
                MirrorService.stop()
            }
        }, null)

        display = try {
            token.createVirtualDisplay(
                "gearslip-mirror", width, height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null,
            )
        } catch (t: Throwable) {
            GearslipLog.e("mirror display failed", t)
            null
        }

        _active.value = display != null
        if (display != null) {
            GearslipLog.i("mirroring the phone into ${width}x$height")
        } else {
            // Phase 8 token-lifecycle fix: a failed create must NOT leave the
            // token stored. The old code left media = token here, so the next
            // begin()'s end() STOPPED the very token the retry was about to
            // reuse — a failed Fill guaranteed a failed Fit retry too. The
            // token is NOT stopped here (stopping is end()'s job alone), so
            // it stays valid for the caller's retry with the same token.
            GearslipLog.e("mirror display create returned null/failed; token left unstopped for retry")
            media = null
        }
    }

    /**
     * Fill mirroring: the virtual display renders Fit frames into the GL
     * renderer's texture, which crops them to the full frame. Returns false when
     * the renderer could not start — the caller then falls back to [begin] (Fit)
     * rather than showing a black screen.
     */
    fun beginFill(context: Context, token: MediaProjection, output: Surface, width: Int, height: Int, densityDpi: Int): Boolean {
        // End the previous session FIRST: its renderer, display, and media
        // must be gone before the new renderer exists, so no end() call
        // below can ever touch the new renderer. (Previously the create
        // came first and begin()'s end() destroyed it immediately.)
        end()
        val renderer = MirrorFillRenderer.create(output, width, height, context) ?: run {
            GearslipLog.e("mirror Fill renderer failed to start (falling back to Fit)")
            return false
        }
        GearslipLog.i("mirror Fill renderer started into ${width}x$height")
        fillRenderer = renderer
        beginInternal(token, renderer.inputSurface, width, height, densityDpi)
        if (!_active.value) {
            // beginInternal already cleared media without stopping the token
            // (see above), so the caller's Fit retry reuses a LIVE token.
            runCatching { renderer.release() }
            fillRenderer = null
            GearslipLog.e("mirror Fill display failed; renderer released, token kept live for Fit retry")
            return false
        }
        return true
    }

    /** True for a touch on the Fill exit button (top-right corner of the frame). */
    fun isInExitRegion(x: Float, y: Float): Boolean {
        if (!fillEnabled || frameWidth <= 0 || frameHeight <= 0) return false
        return MirrorGeometry.exitRegion(frameWidth.toFloat(), frameHeight.toFloat()).contains(x, y)
    }

    fun end() {
        runCatching { display?.release() }
        runCatching { media?.stop() }
        display = null
        media = null
        runCatching { fillRenderer?.release() }
        fillRenderer = null
        _active.value = false
        GearslipLog.i("mirror ended")
    }

    /**
     * Maps a head-unit touch in projected coordinates onto the phone's screen.
     *
     * Fit letterboxes: the phone's display is scaled to fit the car frame with its
     * aspect kept, so the live content occupies a centred sub-rectangle and everything outside
     * it is padding that no window can receive — a touch there returns null. Fill
     * covers the frame, so every frame point maps inside the phone. Metrics are read
     * per call rather than cached because rotating the phone changes the shape of
     * the mirror underneath us. The geometry here must match the video geometry
     * ([MirrorGeometry]) or every tap lands wrong.
     */
    fun mapToPhone(context: Context, x: Float, y: Float): PointF? {
        if (frameWidth == 0 || frameHeight == 0) return null
        val bounds = context.getSystemService(WindowManager::class.java)
            ?.currentWindowMetrics?.bounds ?: return null
        val phoneW = bounds.width().toFloat()
        val phoneH = bounds.height().toFloat()
        val p = if (fillEnabled) {
            MirrorGeometry.mapFill(x, y, phoneW, phoneH, frameWidth.toFloat(), frameHeight.toFloat())
        } else {
            MirrorGeometry.mapFit(x, y, phoneW, phoneH, frameWidth.toFloat(), frameHeight.toFloat())
        } ?: return null
        return PointF(p.x, p.y)
    }
}
