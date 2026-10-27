package app.autospace.car

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameInputTest {
    @Test
    fun bitForKeyCodeFollowsLibretroPositions() {
        // South is B(0), west is Y(1), east is A(8), north is X(9).
        assertEquals(0, GamepadMap.bitForKeyCode(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(8, GamepadMap.bitForKeyCode(KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(1, GamepadMap.bitForKeyCode(KeyEvent.KEYCODE_BUTTON_X))
        assertEquals(9, GamepadMap.bitForKeyCode(KeyEvent.KEYCODE_BUTTON_Y))
        assertEquals(2, GamepadMap.bitForKeyCode(KeyEvent.KEYCODE_BUTTON_SELECT))
        assertEquals(3, GamepadMap.bitForKeyCode(KeyEvent.KEYCODE_BUTTON_START))
        assertEquals(4, GamepadMap.bitForKeyCode(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(5, GamepadMap.bitForKeyCode(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(6, GamepadMap.bitForKeyCode(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(7, GamepadMap.bitForKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(10, GamepadMap.bitForKeyCode(KeyEvent.KEYCODE_BUTTON_L1))
        assertEquals(10, GamepadMap.bitForKeyCode(KeyEvent.KEYCODE_BUTTON_L2))
        assertEquals(11, GamepadMap.bitForKeyCode(KeyEvent.KEYCODE_BUTTON_R1))
        assertEquals(11, GamepadMap.bitForKeyCode(KeyEvent.KEYCODE_BUTTON_R2))
        assertEquals(-1, GamepadMap.bitForKeyCode(KeyEvent.KEYCODE_UNKNOWN))
    }

    @Test
    fun bitsForAxesRespectsDeadzoneAndDiagonals() {
        assertEquals(1 shl 7, GamepadMap.bitsForAxes(0.6f, 0f))
        assertEquals(1 shl 6, GamepadMap.bitsForAxes(-0.6f, 0f))
        assertEquals(1 shl 5, GamepadMap.bitsForAxes(0f, 0.6f))
        assertEquals(1 shl 4, GamepadMap.bitsForAxes(0f, -0.6f))
        assertEquals((1 shl 7) or (1 shl 4), GamepadMap.bitsForAxes(0.9f, -0.9f))
        assertEquals(0, GamepadMap.bitsForAxes(0.4f, 0.4f))
        assertEquals(0, GamepadMap.bitsForAxes(0.5f, -0.5f))
    }

    @Test
    fun routerSetsAndClearsButtonBits() {
        var mask = 0
        val router = GameKeyRouter(setBit = { bit, on ->
            mask = if (on) mask or (1 shl bit) else mask and (1 shl bit).inv()
        })
        assertTrue(router.onKey(KeyEvent.KEYCODE_BUTTON_A, true))
        assertEquals(1 shl 0, mask)
        assertTrue(router.onKey(KeyEvent.KEYCODE_DPAD_UP, true))
        assertEquals((1 shl 0) or (1 shl 4), mask)
        assertTrue(router.onKey(KeyEvent.KEYCODE_BUTTON_A, false))
        assertEquals(1 shl 4, mask)
        assertFalse(router.onKey(KeyEvent.KEYCODE_UNKNOWN, true))
        assertEquals(1 shl 4, mask)
    }

    @Test
    fun routerStickCentersClearExactlyWhatTheySet() {
        var mask = 0
        val router = GameKeyRouter(setBit = { bit, on ->
            mask = if (on) mask or (1 shl bit) else mask and (1 shl bit).inv()
        })
        router.onKey(KeyEvent.KEYCODE_DPAD_UP, true)
        router.onAxes(0.9f, 0f, isStick = true)
        assertEquals((1 shl 4) or (1 shl 7), mask)
        router.onAxes(0f, 0f, isStick = true)
        // The held D-pad key survives; the stick's bit clears.
        assertEquals(1 shl 4, mask)
        router.onAxes(0f, -0.9f, isStick = false)
        assertEquals((1 shl 4), mask)
        // Last writer wins: the hat returning to center clears the
        // bit even though the D-pad key is still held (Mariner's
        // shape — axis bits are tracked apart from key bits).
        router.onAxes(0f, 0f, isStick = false)
        assertEquals(0, mask)
    }

    @Test
    fun analogModeDrivesOnAnalogNotTheMask() {
        var mask = 0
        var analog = listOf(0, 0, 0, 0)
        val router = GameKeyRouter(setBit = { bit, on ->
            mask = if (on) mask or (1 shl bit) else mask and (1 shl bit).inv()
        })
        router.analogMode = true
        router.onAnalog = { lx, ly, rx, ry -> analog = listOf(lx, ly, rx, ry) }
        router.onAxes(0.5f, -0.5f, isStick = true)
        assertEquals(0, mask)
        assertEquals(listOf(16383, -16383, 0, 0), analog)
        // The hat still feeds the D-pad bits in analog mode.
        router.onAxes(0f, -0.9f, isStick = false)
        assertEquals(1 shl 4, mask)
    }

    @Test
    fun controllerDeviceIsDualShockOnlyForPlayStation() {
        assertEquals(261, GameInput.controllerDeviceFor("ps1"))
        assertTrue(GameInput.analogCapable("ps1"))
        for (console in listOf("nes", "snes", "genesis", "sms", "sg1000", "segacd", "32x")) {
            assertEquals(1, GameInput.controllerDeviceFor(console))
            assertFalse(GameInput.analogCapable(console))
        }
    }
}
