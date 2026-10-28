package app.autospace.car

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.dp
import app.autospace.car.theme.FrostedChromeAlpha
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** r34 floating Home: card visibility, pill step-aside, and card width rules; r36 restore fix; r42 narrower card + smaller dismiss + style-shaped transport; r43 map behind the nav bar in floating mode only. */
class FloatingHomeTest {

    @Test
    fun cardShowsOnlyWhenEveryGatePasses() {
        assertTrue(shouldShowFloatingCard(true, false, SidePanel.NONE, true))
    }

    @Test
    fun cardHidesWhenAnyGateFails() {
        // Toggle off (the default): classic Home, no card.
        // Nothing playing: full-bleed map, no card.
        assertFalse(shouldShowFloatingCard(false, false, SidePanel.NONE, true))
        // Dismissed this session: map only, pill comes back.
        assertFalse(shouldShowFloatingCard(true, true, SidePanel.NONE, true))
        // Lyrics owns the side: classic split instead.
        assertFalse(shouldShowFloatingCard(true, false, SidePanel.LYRICS, true))
        // Not on Home: the card lives in CarHome only.
        assertFalse(shouldShowFloatingCard(true, false, SidePanel.NONE, false))
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
    fun artCardDrawsNoPerimeterButPlainCardKeepsItsEdge() {
        // r133: with art the art IS the face, so no kit edge strokes
        // over it; without art the card keeps exactly today's edge.
        assertFalse(artCardDrawsPerimeter(true))
        assertTrue(artCardDrawsPerimeter(false))
    }

    @Test
    fun transportRowDefaultsToCircles() {
        // r42: callers that pass no shape (full player, CompactPlayer)
        // keep circles; only the floating card passes gsShape().
        assertEquals(CircleShape, DefaultTransportShape)
    }




}
