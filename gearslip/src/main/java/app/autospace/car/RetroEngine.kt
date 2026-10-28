package app.autospace.car

import android.view.Surface

/**
 * r93: the real [GameEngine], replacing r92's NoEngine. The JNI
 * shim does the work; this wrapper owns the lifecycle ordering
 * the playback screen cannot see:
 *
 * - create() before start(), and every other call is a safe
 *   no-op until create() succeeds, so a failed launch can never
 *   drive the native side into a half-started state.
 * - A surface that arrives after start() (the SurfaceView is
 *   created after the game launch kicks off) starts the render
 *   thread via nativeStartRender; without it the game would run
 *   blind until the next surface change.
 * - A missing native library never crashes: create() returns
 *   the honest reason, and the playback screen shows it.
 */
object RetroEngine : GameEngine {
    private var created = false
    private var started = false

    override fun create(
        corePath: String,
        romPath: String,
        systemDir: String,
        saveDir: String,
        options: Array<String>
    ): String? {
        if (!RetroNative.loadOk) {
            return "The playback engine is not installed in this build."
        }
        val error = RetroNative.nativeCreate(
            corePath, romPath, systemDir, saveDir, options
        )
        created = error == null
        return error
    }

    override fun setSurface(surface: Surface?) {
        if (!RetroNative.loadOk) return
        RetroNative.nativeSetSurface(surface)
        if (surface != null && started) {
            RetroNative.nativeStartRender()
        }
    }

    override fun start() {
        if (!created) return
        RetroNative.nativeStart()
        started = true
    }

    override fun stop() {
        if (!RetroNative.loadOk) return
        started = false
        RetroNative.nativeStop()
    }

    override fun setPaused(paused: Boolean) {
        if (!created) return
        RetroNative.nativeSetPaused(paused)
    }

    override fun setInput(mask: Int) {
        if (!created) return
        RetroNative.nativeSetInput(mask)
    }

    override fun setAnalog(lx: Int, ly: Int, rx: Int, ry: Int) {
        if (!created) return
        RetroNative.nativeSetAnalog(lx, ly, rx, ry)
    }

    override fun setControllerDevice(device: Int) {
        if (!created) return
        RetroNative.nativeSetControllerDevice(device)
    }

    override fun saveState(path: String): Boolean {
        if (!created) return false
        return RetroNative.nativeSaveState(path)
    }

    override fun loadState(path: String): Boolean {
        if (!created) return false
        return RetroNative.nativeLoadState(path)
    }

    override fun reset(): Boolean {
        if (!created) return false
        RetroNative.nativeReset()
        return true
    }

    override fun diskCount(): Int {
        if (!created) return 0
        return RetroNative.nativeDiskGetCount()
    }

    override fun diskIndex(): Int {
        if (!created) return 0
        return RetroNative.nativeDiskGetIndex()
    }

    override fun diskSwap(index: Int): Boolean {
        if (!created) return false
        return RetroNative.nativeDiskSwap(index)
    }

    override fun setTopInset(px: Int) {
        if (!created) return
        RetroNative.nativeSetTopInset(px)
    }

    override fun setShader(on: Boolean, scan: Float, grid: Float, blur: Float) {
        if (!created) return
        RetroNative.nativeSetShader(on, scan, grid, blur)
    }

    override fun setLcdShader(on: Boolean, brightScan: Float, brightLcd: Float) {
        if (!created) return
        RetroNative.nativeSetLcdShader(on, brightScan, brightLcd)
    }

    override fun setCrtShader(on: Boolean) {
        if (!created) return
        RetroNative.nativeSetCrtShader(on)
    }

    override fun setColorProfile(
        saturation: Float,
        warm: Float,
        gamma: Float,
        bright: Float,
        black: Float,
        segaFix: Boolean
    ) {
        if (!created) return
        RetroNative.nativeSetColorProfile(
            saturation, warm, gamma, bright, black, segaFix
        )
    }

    override fun setCurve(k: Float) {
        if (!created) return
        RetroNative.nativeSetCurve(k)
    }

    override fun setRound(strength: Float) {
        if (!created) return
        RetroNative.nativeSetRound(strength)
    }

    override fun setSpeed(mul: Int) {
        if (!created) return
        RetroNative.nativeSetSpeed(mul)
    }

    override fun setIntegerScale(on: Boolean) {
        if (!created) return
        RetroNative.nativeSetIntegerScale(on)
    }

    override fun frameGrab(): ByteArray? {
        if (!created || !RetroNative.loadOk) return null
        return runCatching { RetroNative.nativeFrameGrab() }.getOrNull()
    }

    override fun presentedFrames(): Long {
        if (!created || !RetroNative.loadOk) return 0
        return runCatching { RetroNative.nativeGetPresentedFrames() }.getOrDefault(0)
    }

    override fun destroy() {
        if (!RetroNative.loadOk) return
        created = false
        started = false
        RetroNative.nativeDestroy()
    }
}
