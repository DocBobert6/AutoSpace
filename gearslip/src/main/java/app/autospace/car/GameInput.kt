package app.autospace.car

import android.view.KeyEvent
import android.view.MotionEvent

/**
 * r92: controller input, ported from Mariner's GamepadMap and
 * GameKeyRouter for the car screen. Libretro joypad ids are positions,
 * not letters: the south face button is libretro B, the east face
 * button is libretro A, west is Y, north is X. D-pad keys, the hat,
 * and the left stick all feed the same D-pad bits, so a controller
 * that reports any of the three drives movement the same way.
 */
object GamepadMap {
    fun bitForKeyCode(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> 0
        KeyEvent.KEYCODE_BUTTON_X -> 1
        KeyEvent.KEYCODE_BUTTON_SELECT -> 2
        KeyEvent.KEYCODE_BUTTON_START -> 3
        KeyEvent.KEYCODE_DPAD_UP -> 4
        KeyEvent.KEYCODE_DPAD_DOWN -> 5
        KeyEvent.KEYCODE_DPAD_LEFT -> 6
        KeyEvent.KEYCODE_DPAD_RIGHT -> 7
        KeyEvent.KEYCODE_BUTTON_B -> 8
        KeyEvent.KEYCODE_BUTTON_Y -> 9
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2 -> 10
        KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2 -> 11
        else -> -1
    }

    /** D-pad bits from a stick or hat deflection, with a 0.5
     * deadzone. X and Y are independent, so diagonals are two bits. */
    fun bitsForAxes(x: Float, y: Float): Int {
        var bits = 0
        if (x > 0.5f) bits = bits or (1 shl 7)
        if (x < -0.5f) bits = bits or (1 shl 6)
        if (y > 0.5f) bits = bits or (1 shl 5)
        if (y < -0.5f) bits = bits or (1 shl 4)
        return bits
    }
}

/**
 * r93: the one registered consumer of phone-side controller motion
 * events. The playback screen sets this while it is up (and clears
 * it on leave); ScreenProjector delivers activity-forwarded motion
 * here first. The handler takes every joystick or gamepad move,
 * including the return to center, which is what clears the
 * router's stick bits.
 */
object GameMotionSink {
    @Volatile
    var handler: ((MotionEvent) -> Boolean)? = null
}

/**
 * Routes controller input into a libretro button mask. The router
 * remembers which D-pad bits the hat and stick set, so returning to
 * center clears exactly what was set and never a held D-pad key.
 * In analog mode the hat still drives the D-pad bits, while the
 * sticks drive onAnalog instead of the mask.
 */
class GameKeyRouter(
    private val setBit: (bit: Int, on: Boolean) -> Unit,
    private val onActivity: () -> Unit = {}
) {
    var analogMode: Boolean = false
    var onAnalog: (lx: Int, ly: Int, rx: Int, ry: Int) -> Unit =
        { _, _, _, _ -> }

    private var hatBits = 0
    private var stickBits = 0
    private val heldKeys = HashSet<Int>()

    // Analog stick positions, kept apart so the right stick never
    // clobbers the left (r93): each stick update re-emits the
    // combined vector.
    private var leftX = 0f
    private var leftY = 0f
    private var rightX = 0f
    private var rightY = 0f

    private fun emitAnalog() {
        onAnalog(
            (leftX * 32767).toInt(),
            (leftY * 32767).toInt(),
            (rightX * 32767).toInt(),
            (rightY * 32767).toInt()
        )
    }

    /** Returns true when the key maps to a game button. */
    fun onKey(code: Int, down: Boolean): Boolean {
        val bit = GamepadMap.bitForKeyCode(code)
        if (bit < 0) return false
        if (down) heldKeys.add(code) else heldKeys.remove(code)
        setBit(bit, down)
        onActivity()
        return true
    }

    /** Feed a hat or stick deflection. In analog mode the sticks
     * report through onAnalog; the hat still feeds the D-pad bits. */
    fun onAxes(x: Float, y: Float, isStick: Boolean = false) {
        if (analogMode && isStick) {
            leftX = x
            leftY = y
            emitAnalog()
            return
        }
        val bits = GamepadMap.bitsForAxes(x, y)
        if (isStick) {
            applyAxisBits(bits, stickBits) { stickBits = it }
        } else {
            applyAxisBits(bits, hatBits) { hatBits = it }
        }
        onActivity()
    }

    /** Feed the right stick in analog mode: it updates only the
     * right axes and re-emits the combined vector, so the left
     * stick's position survives. Ignored in digital mode, where
     * only the left stick drives the D-pad bits. */
    fun onRightStick(x: Float, y: Float) {
        if (!analogMode) return
        rightX = x
        rightY = y
        emitAnalog()
    }

    private fun applyAxisBits(
        bits: Int,
        previous: Int,
        store: (Int) -> Unit
    ) {
        val changed = bits xor previous
        if (changed != 0) {
            for (bit in 4..7) {
                if (changed and (1 shl bit) != 0) {
                    setBit(bit, bits and (1 shl bit) != 0)
                }
            }
        }
        store(bits)
    }

    /** True when any game input is currently held. */
    fun anyHeld(): Boolean =
        heldKeys.isNotEmpty() || hatBits != 0 || stickBits != 0

    companion object {
        /** The two axes of a MotionEvent that carry a stick. */
        fun stickAxes(event: MotionEvent): Pair<Float, Float> =
            event.getAxisValue(MotionEvent.AXIS_X) to
                event.getAxisValue(MotionEvent.AXIS_Y)
    }
}

/** Per-console controller setup for the car screen. PlayStation is
 * the only analog-capable console here, so it alone gets the
 * DualShock device id; every other console is a plain digital
 * joypad. */
object GameInput {
    /** Controller device id for port 0: 261 is DualShock, 1 is joypad. */
    fun controllerDeviceFor(system: String): Int =
        if (system == "ps1") 261 else 1

    fun analogCapable(system: String): Boolean = system == "ps1"
}
