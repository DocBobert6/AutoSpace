package app.autospace.car

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** r34 floating Home: card visibility, pill step-aside, and card width rules; r36 restore fix; r42 narrower card + smaller dismiss + style-shaped transport. */
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
    fun dockedColumnStepsAsideOnlyInClassicMode() {
        // Classic mode, docked beside the map: pill steps aside for the column.
        assertTrue(shouldStepAsidePillForDockedColumn(false, SidePanel.CONTROLS, true))
        // r36: floating mode, docked panel, on Home — the classic column never
        // renders here, so the docked gate must NOT hide the pill.
        assertFalse(shouldStepAsidePillForDockedColumn(true, SidePanel.CONTROLS, true))
        // Not docked, or not on Home: no step-aside either way.
        assertFalse(shouldStepAsidePillForDockedColumn(false, SidePanel.NONE, true))
        assertFalse(shouldStepAsidePillForDockedColumn(false, SidePanel.CONTROLS, false))
        assertFalse(shouldStepAsidePillForDockedColumn(false, SidePanel.LYRICS, true))
    }

    @Test
    fun pillShowsInFloatingModeWithControlsPanel() {
        // r36, the reported loss: floating on, card dismissed, panel CONTROLS
        // (docked before floating was enabled, or dock from another screen).
        // No card AND no pill was the bug; the pill must show now.
        assertTrue(shouldShowNavPill(true, true, SidePanel.CONTROLS, true, false))
        // Card visible in floating mode: pill still steps aside for the card.
        assertFalse(shouldShowNavPill(true, false, SidePanel.NONE, true, false))
        // Classic docked column: pill steps aside.
        assertFalse(shouldShowNavPill(false, false, SidePanel.CONTROLS, true, false))
        // Classic, not docked: pill shows.
        assertTrue(shouldShowNavPill(false, false, SidePanel.NONE, true, false))
        // Full media screen: pill repeats the player, stays hidden.
        assertFalse(shouldShowNavPill(false, false, SidePanel.NONE, true, true))
        assertFalse(shouldShowNavPill(true, true, SidePanel.NONE, true, true))
        // Not on Home, floating card not on screen: pill shows.
        assertTrue(shouldShowNavPill(true, false, SidePanel.NONE, false, false))
    }

    @Test
    fun pillDockButtonRestoresDismissedCardOnlyInFloatingModeOnHome() {
        assertTrue(pillDockRestoresFloatingCard(true, true, true))
        // Card not dismissed: pill is hidden by the floating gate anyway; the
        // button is unreachable, and it must not claim restore semantics.
        assertFalse(pillDockRestoresFloatingCard(true, false, true))
        // Floating off: classic dock behavior, never restore.
        assertFalse(pillDockRestoresFloatingCard(false, true, true))
        // Not on Home: the card lives in CarHome only; dock keeps its behavior.
        assertFalse(pillDockRestoresFloatingCard(true, true, false))
    }

    @Test
    fun playbackStartTransitionResurfacesButLevelDoesNot() {
        // Paused/stopped -> playing: the edge that brings the card back.
        assertTrue(shouldResurfaceOnPlayTransition(false, true))
        // Dismiss-while-playing + track change: playing stays true, no edge,
        // card stays hidden.
        assertFalse(shouldResurfaceOnPlayTransition(true, true))
        // Pausing: no resurface.
        assertFalse(shouldResurfaceOnPlayTransition(true, false))
        // Staying paused: no resurface.
        assertFalse(shouldResurfaceOnPlayTransition(false, false))
    }

    @Test
    fun cardWidthIs40PercentCappedAndFloored() {
        // r42: 800dp frame: 40% = 320dp, inside the band.
        assertEquals(320.dp, floatingCardWidth(800.dp))
        // Wide frame: capped at 380dp, not 40%.
        assertEquals(380.dp, floatingCardWidth(1000.dp))
        assertEquals(380.dp, floatingCardWidth(1920.dp))
        // Narrow frame: floored at 240dp so transport still fits.
        assertEquals(240.dp, floatingCardWidth(400.dp))
        // Tiny frame: never wider than the frame minus 24dp.
        assertEquals(176.dp, floatingCardWidth(200.dp))
    }

    @Test
    fun dismissButtonShrinksVisuallyButKeeps44dpTouchTarget() {
        // r42: the touch target never drops below the 44dp floor, the
        // drawn box sits inside it, and the glyph sits inside the box.
        assertEquals(44.dp, FloatingCardDismissTouchSize)
        assertEquals(36.dp, FloatingCardDismissDrawnSize)
        assertEquals(20.dp, FloatingCardDismissGlyphSize)
        assertTrue(FloatingCardDismissDrawnSize < FloatingCardDismissTouchSize)
        assertTrue(FloatingCardDismissGlyphSize < FloatingCardDismissDrawnSize)
    }

    @Test
    fun transportRowDefaultsToCircles() {
        // r42: callers that pass no shape (full player, CompactPlayer)
        // keep circles; only the floating card passes gsShape().
        assertEquals(CircleShape, DefaultTransportShape)
    }
}
