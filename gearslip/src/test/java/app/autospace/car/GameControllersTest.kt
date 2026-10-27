package app.autospace.car

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameControllersTest {

    @Test
    fun controllerNamesAreRecognized() {
        assertTrue(looksLikeController("Xbox Wireless Controller"))
        assertTrue(looksLikeController("DualSense Wireless Controller"))
        assertTrue(looksLikeController("DualShock 4"))
        assertTrue(looksLikeController("8BitDo Pro 2"))
        assertTrue(looksLikeController("Pro Controller"))
        assertTrue(looksLikeController("Joy-Con (L)"))
        assertTrue(looksLikeController("controller"))
        assertTrue(looksLikeController("My Gamepad"))
    }

    @Test
    fun nonControllerNamesAreNotRecognized() {
        assertFalse(looksLikeController("MX Keys"))
        assertFalse(looksLikeController("WH-1000XM4"))
        assertFalse(looksLikeController("Galaxy Buds"))
        assertFalse(looksLikeController(null))
        assertFalse(looksLikeController(""))
        assertFalse(looksLikeController("   "))
    }
}
