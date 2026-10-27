package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RetroEngineTest {
    @Test
    fun nativeLibraryIsAbsentOnTheJvm() {
        assertFalse(RetroNative.loadOk)
    }

    @Test
    fun engineReportsAMissingLibraryHonestlyInsteadOfCrashing() {
        val error = RetroEngine.create(
            "/nonexistent/core.so",
            "/nonexistent/game.rom",
            "/nonexistent/system",
            "/nonexistent/saves",
            emptyArray()
        )
        assertEquals("The playback engine is not installed in this build.", error)
        // Nothing was created, so every other call is a safe no-op.
        assertFalse(RetroEngine.saveState("/nonexistent/state"))
        assertFalse(RetroEngine.loadState("/nonexistent/state"))
        assertFalse(RetroEngine.reset())
        assertEquals(0, RetroEngine.diskCount())
        assertEquals(0, RetroEngine.diskIndex())
        assertFalse(RetroEngine.diskSwap(1))
    }
}
