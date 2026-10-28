package app.autospace.car

import android.content.Context
import android.view.KeyEvent
import app.autospace.AppSettings

/**
 * r147: per-console controller mapping, first of the deferred games
 * block. A mapping says which physical controller button drives each
 * libretro joypad bit for one console, and it is stored per console
 * — the mapping for NES never leaks into SNES, and so on.
 *
 * Storage follows the shape Mariner's core options already use and
 * AutoSpace ported (GameCoreOptions: "coreOpt:<console>:<key>"):
 * one AppSettings string per action, "padMap:<console>:<bit>",
 * holding the Android key code as a decimal string. A console with
 * no stored mapping behaves exactly as before: every action falls
 * back to its default below, which is the inverse of
 * GamepadMap.bitForKeyCode, so the defaults are today's behavior,
 * not a new one.
 *
 * Two deliberate limits, stated plainly:
 * - Buttons only. The hat and sticks keep driving the D-pad bits
 *   through GameKeyRouter.onAxes exactly as today, and the PS1
 *   analog vectors keep flowing through onAnalog unmapped. This
 *   round remaps the 12 joypad buttons, not the axes.
 * - Assignment is a touchscreen picker, not press-to-capture. The
 *   r95 scope lock swallows controller keys while no game is open
 *   (GearslipActivity forwards them only while the playback
 *   screen's motion sink is registered), so a menu cannot hear a
 *   button press to capture it. Weakening that lock to capture
 *   presses was not ordered, so the mapping screen lists the
 *   physical buttons and the user taps one. A mapping changed
 *   while a game is open applies the next time that console's
 *   game launches, like core options: the playback screen reads
 *   the stored mapping once, at launch.
 */
object GamePadMapping {

    /** One remappable action: a libretro joypad bit and its label. */
    data class PadAction(val bit: Int, val label: String)

    /**
     * Actions in the order the mapping screen shows them: D-pad,
     * face buttons, shoulders, then Start and Select. Labels name
     * the libretro position, so "A (east)" is the east face button
     * (libretro bit 8), not the controller's A button — the two
     * are different buttons, and the mapping is what connects them.
     */
    val ACTIONS = listOf(
        PadAction(4, "D-pad up"),
        PadAction(5, "D-pad down"),
        PadAction(6, "D-pad left"),
        PadAction(7, "D-pad right"),
        PadAction(8, "A (east)"),
        PadAction(0, "B (south)"),
        PadAction(9, "X (north)"),
        PadAction(1, "Y (west)"),
        PadAction(10, "L"),
        PadAction(11, "R"),
        PadAction(3, "Start"),
        PadAction(2, "Select"),
    )

    /**
     * The physical buttons a user may assign, in picker order.
     * Exactly the codes GamepadMap.bitForKeyCode recognizes today,
     * so no assignment can name a button the router never hears.
     */
    val CANDIDATE_CODES = listOf(
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_X,
        KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_L1,
        KeyEvent.KEYCODE_BUTTON_L2,
        KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_BUTTON_SELECT,
    )

    /** Today's behavior, as a default: the inverse of
     * GamepadMap.bitForKeyCode. L defaults to L1 and R to R1; L2
     * and R2 also drive those bits today, so they stay assignable. */
    fun defaultKeyCodeForBit(bit: Int): Int = when (bit) {
        0 -> KeyEvent.KEYCODE_BUTTON_A
        1 -> KeyEvent.KEYCODE_BUTTON_X
        2 -> KeyEvent.KEYCODE_BUTTON_SELECT
        3 -> KeyEvent.KEYCODE_BUTTON_START
        4 -> KeyEvent.KEYCODE_DPAD_UP
        5 -> KeyEvent.KEYCODE_DPAD_DOWN
        6 -> KeyEvent.KEYCODE_DPAD_LEFT
        7 -> KeyEvent.KEYCODE_DPAD_RIGHT
        8 -> KeyEvent.KEYCODE_BUTTON_B
        9 -> KeyEvent.KEYCODE_BUTTON_Y
        10 -> KeyEvent.KEYCODE_BUTTON_L1
        11 -> KeyEvent.KEYCODE_BUTTON_R1
        else -> KeyEvent.KEYCODE_UNKNOWN
    }

    /** The default mapping: every action on its default button. */
    fun defaultMapping(): Map<Int, Int> =
        ACTIONS.associate { it.bit to defaultKeyCodeForBit(it.bit) }

    /** A physical button's label for the mapping rows and picker. */
    fun buttonLabel(code: Int): String = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> "D-pad up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "D-pad down"
        KeyEvent.KEYCODE_DPAD_LEFT -> "D-pad left"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "D-pad right"
        KeyEvent.KEYCODE_BUTTON_A -> "Button A"
        KeyEvent.KEYCODE_BUTTON_B -> "Button B"
        KeyEvent.KEYCODE_BUTTON_X -> "Button X"
        KeyEvent.KEYCODE_BUTTON_Y -> "Button Y"
        KeyEvent.KEYCODE_BUTTON_L1 -> "Button L1"
        KeyEvent.KEYCODE_BUTTON_L2 -> "Button L2"
        KeyEvent.KEYCODE_BUTTON_R1 -> "Button R1"
        KeyEvent.KEYCODE_BUTTON_R2 -> "Button R2"
        KeyEvent.KEYCODE_BUTTON_START -> "Button Start"
        KeyEvent.KEYCODE_BUTTON_SELECT -> "Button Select"
        else -> "Key $code"
    }

    private fun settingKey(console: String, bit: Int) =
        "padMap:$console:$bit"

    /**
     * The effective mapping for raw stored values (bit to raw
     * string, as storedMapping reads them). A raw value that is
     * not a decimal code for a candidate button falls back to
     * that action's default, exactly like
     * GameCoreOptions.storedValue falls back to an option default.
     * Pure, so tests pin it without a Context.
     */
    fun effectiveMapping(raw: Map<Int, String>): Map<Int, Int> =
        ACTIONS.associate { action ->
            val code = raw[action.bit]?.toIntOrNull()
            action.bit to if (code != null && code in CANDIDATE_CODES) {
                code
            } else {
                defaultKeyCodeForBit(action.bit)
            }
        }

    /**
     * Which libretro bit a physical key code drives under this
     * mapping. A stored assignment wins; a candidate code with
     * no stored assignment falls back to GamepadMap, so the two
     * shoulder aliases keep working exactly as today (L2 drives
     * L, R2 drives R) unless the user assigns them elsewhere.
     * A code GamepadMap does not recognize maps to -1. This is
     * what the playback screen hands the router, so the router
     * itself stays mapping-agnostic.
     *
     * One honest consequence of the fallback, pinned by tests:
     * assigning an alias-only button (L2 or R2) to a new bit
     * leaves the button it replaces still driving that bit
     * through the fallback — the same aliasing today's tree
     * already has for the shoulders, now generalized. There are
     * 14 candidate codes and 12 bits, so some aliasing is
     * unavoidable; the fallback keeps it on the shoulders by
     * default instead of dropping buttons.
     */
    fun bitForKeyCode(mapping: Map<Int, Int>, code: Int): Int =
        mapping.entries.firstOrNull { it.value == code }?.key
            ?: GamepadMap.bitForKeyCode(code)

    /**
     * Assign [code] to [bit], swapping with whatever action held
     * that code, so the mapping stays bijective: no button ever
     * goes dead and no two actions ever share one button. Pure.
     */
    fun withAssignment(mapping: Map<Int, Int>, bit: Int, code: Int): Map<Int, Int> {
        val next = mapping.toMutableMap()
        val displaced = next.entries.firstOrNull { it.value == code && it.key != bit }?.key
        if (displaced != null) {
            next[displaced] = next.getValue(bit)
        }
        next[bit] = code
        return next
    }

    /** The stored mapping for a console, defaults filled in. */
    fun storedMapping(context: Context, console: String): Map<Int, Int> {
        val raw = ACTIONS.associate { action ->
            action.bit to AppSettings.getString(context, settingKey(console, action.bit), "")
        }
        return effectiveMapping(raw)
    }

    /** Assign one button for one console, swapping on conflict. */
    fun setKeyCode(context: Context, console: String, bit: Int, code: Int) {
        val next = withAssignment(storedMapping(context, console), bit, code)
        for ((mappedBit, mappedCode) in next) {
            AppSettings.putString(context, settingKey(console, mappedBit), mappedCode.toString())
        }
    }

    /**
     * Reset one console to the defaults. Implemented as writing
     * the defaults, not deleting keys: AppSettings has no remove,
     * and a stored default reads back identically to no stored
     * value, so the two states are indistinguishable afterwards.
     */
    fun resetConsole(context: Context, console: String) {
        for (action in ACTIONS) {
            AppSettings.putString(
                context,
                settingKey(console, action.bit),
                defaultKeyCodeForBit(action.bit).toString(),
            )
        }
    }
}
