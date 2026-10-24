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

    /**
     * Wired up by the session, which owns the encoder Surface and so is the only thing that
     * can swap what draws into it. All three are invoked on the main thread.
     */
    internal var onStartRequested: (() -> Unit)? = null
    internal var onTokenReady: ((MediaProjection) -> Unit)? = null
    internal var onEnded: (() -> Unit)? = null

    /** Asks the phone to put up the screen-capture consent dialog. Safe to call from the car. */
    fun requestStart() {
        onStartRequested?.invoke()
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
            PhoneApps.launch(context, packageName)
        } else {
            pendingLaunch = packageName
            requestStart()
        }
    }

    /** Called by the session once the mirror is live. */
    fun launchPending(context: Context) {
        val packageName = pendingLaunch ?: return
        pendingLaunch = null
        PhoneApps.launch(context, packageName)
    }

    /**
     * Points [surface] at the phone's screen. The caller must have released whatever else was
     * drawing into that Surface first - a Surface has one producer.
     */
    fun begin(token: MediaProjection, surface: Surface, width: Int, height: Int, densityDpi: Int) {
        end()
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
        if (display != null) GearslipLog.i("mirroring the phone into ${width}x$height")
    }

    fun end() {
        runCatching { display?.release() }
        runCatching { media?.stop() }
        display = null
        media = null
        _active.value = false
    }

    /**
     * Maps a head-unit touch in projected coordinates onto the phone's screen.
     *
     * Mirroring letterboxes: the phone's display is scaled to fit the car frame with its
     * aspect kept, so the live content occupies a centred sub-rectangle and everything outside
     * it is padding that no window can receive. Metrics are read per call rather than cached
     * because rotating the phone changes the shape of the mirror underneath us.
     *
     * Returns null for a touch that landed on the padding.
     */
    fun mapToPhone(context: Context, x: Float, y: Float): PointF? {
        if (frameWidth == 0 || frameHeight == 0) return null
        val bounds = context.getSystemService(WindowManager::class.java)
            ?.currentWindowMetrics?.bounds ?: return null
        val phoneW = bounds.width().toFloat()
        val phoneH = bounds.height().toFloat()
        if (phoneW <= 0f || phoneH <= 0f) return null

        val scale = minOf(frameWidth / phoneW, frameHeight / phoneH)
        val offsetX = (frameWidth - phoneW * scale) / 2f
        val offsetY = (frameHeight - phoneH * scale) / 2f

        val px = (x - offsetX) / scale
        val py = (y - offsetY) / scale
        if (px < 0f || py < 0f || px > phoneW || py > phoneH) return null
        return PointF(px, py)
    }
}
