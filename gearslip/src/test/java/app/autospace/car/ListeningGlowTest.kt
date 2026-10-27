package app.autospace.car

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** r67: the full-width listening glow gate — the band renders only when the driver has opted into experimental features as a whole AND switched this glow on. */
class ListeningGlowTest {

    @Test
    fun bandNeedsBothExperimentsAndToggle() {
        assertTrue(fullWidthGlowNow(true, true))
    }

    @Test
    fun bandOffWhenExperimentsOff() {
        assertFalse(fullWidthGlowNow(false, true))
    }

    @Test
    fun bandOffWhenToggleOff() {
        assertFalse(fullWidthGlowNow(true, false))
    }

    @Test
    fun bandOffWhenBothOff() {
        assertFalse(fullWidthGlowNow(false, false))
    }
}
