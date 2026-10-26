package app.autospace.car

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** r34 floating Home: card visibility, pill step-aside, and card width rules. */
class FloatingHomeTest {

    @Test
    fun cardShowsOnlyWhenEveryGatePasses() {
        assertTrue(shouldShowFloatingCard(true, true, false, SidePanel.NONE, true))
        assertTrue(shouldShowFloatingCard(true, true, false, SidePanel.CONTROLS, true))
    }

    @Test
    fun cardHidesWhenAnyGateFails() {
        // Toggle off (the default): classic Home, no card.
        assertFalse(shouldShowFloatingCard(false, true, false, SidePanel.NONE, true))
        // Nothing playing: full-bleed map, no card.
        assertFalse(shouldShowFloatingCard(true, false, false, SidePanel.NONE, true))
        // Dismissed this session: map only, pill comes back.
        assertFalse(shouldShowFloatingCard(true, true, true, SidePanel.NONE, true))
        // Lyrics owns the side: classic split instead.
        assertFalse(shouldShowFloatingCard(true, true, false, SidePanel.LYRICS, true))
        // Not on Home: the card lives in CarHome only.
        assertFalse(shouldShowFloatingCard(true, true, false, SidePanel.NONE, false))
    }

    @Test
    fun pillStepsAsideForTheCardAndReturnsOnDismiss() {
        assertTrue(shouldStepAsidePillForFloatingCard(true, false, true))
        assertFalse(shouldStepAsidePillForFloatingCard(true, true, true))
        assertFalse(shouldStepAsidePillForFloatingCard(false, false, true))
        assertFalse(shouldStepAsidePillForFloatingCard(true, false, false))
    }

    @Test
    fun cardWidthIs45PercentCappedAndFloored() {
        // 800dp frame: 45% = 360dp, inside the band.
        assertEquals(360.dp, floatingCardWidth(800.dp))
        // Wide frame: capped at 420dp, not 45%.
        assertEquals(420.dp, floatingCardWidth(1000.dp))
        assertEquals(420.dp, floatingCardWidth(1920.dp))
        // Narrow frame: floored at 240dp so transport still fits.
        assertEquals(240.dp, floatingCardWidth(400.dp))
        // Tiny frame: never wider than the frame minus its 12dp margins.
        assertEquals(176.dp, floatingCardWidth(200.dp))
    }
}
