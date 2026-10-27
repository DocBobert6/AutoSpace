package app.autospace.car

import android.view.Surface

/**
 * r93: JNI bridge to the libretro frontend shim. libautospace_retro
 * hosts a core loaded by path, presents through EGL/GLES3 onto the
 * playback screen's SurfaceView, and plays audio through oboe.
 *
 * The library is absent on the JVM (unit tests) and in any build
 * that skipped the native step, so loading is guarded: [loadOk]
 * is false there, and [RetroEngine] reports that honestly instead
 * of crashing on first use.
 */
object RetroNative {
    /** True once libautospace_retro loaded. False on the JVM. */
    val loadOk: Boolean = runCatching {
        System.loadLibrary("autospace_retro")
        true
    }.getOrDefault(false)

    /** Loads the core and the ROM. Null on success; a
     * human-readable reason on failure. */
    external fun nativeCreate(
        corePath: String,
        romPath: String,
        systemDir: String,
        saveDir: String,
        options: Array<String>
    ): String?

    external fun nativeSetSurface(surface: Surface?)

    /** Starts the emu loop; the render thread starts with it
     * when a surface is already set, or later via
     * [nativeStartRender] when one arrives. */
    external fun nativeStart()

    /** Starts the render thread for a surface that arrived after
     * [nativeStart]. A no-op when the game never started or the
     * render thread is already running. */
    external fun nativeStartRender()

    external fun nativeStop()

    /** Pause: the emu loop idles and the audio callback silences. */
    external fun nativeSetPaused(paused: Boolean)

    /** The joypad bitmask (libretro device ids): B=0, Y=1,
     * Select=2, Start=3, Up=4, Down=5, Left=6, Right=7, A=8, X=9,
     * L=10, R=11. */
    external fun nativeSetInput(mask: Int)

    /** Analogue sticks: int16 axes, -32767..32767, negative Y =
     * up, in left-X, left-Y, right-X, right-Y order. */
    external fun nativeSetAnalog(lx: Int, ly: Int, rx: Int, ry: Int)

    /** Controller device for port 0: 1 = joypad, 261 = DualShock.
     * A no-op when the core lacks the entry point. */
    external fun nativeSetControllerDevice(device: Int)

    external fun nativeSaveState(path: String): Boolean
    external fun nativeLoadState(path: String): Boolean

    /** Reset the running game. */
    external fun nativeReset()

    /** Disc control: count, current index, and the atomic
     * eject -> set -> uneject swap. */
    external fun nativeDiskGetCount(): Int
    external fun nativeDiskGetIndex(): Int
    external fun nativeDiskSwap(index: Int): Boolean

    external fun nativeDestroy()
}
