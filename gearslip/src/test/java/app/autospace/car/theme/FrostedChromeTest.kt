package app.autospace.car.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * r46: pins for the frosted map chrome. The see-through level is
 * constant across styles and schemes (the card's 0.92), the surface
 * rule draws the fill exactly once per pane, and panel corners follow
 * the style rule — so a style or scheme change can never silently
 * re-tint, re-alpha, or double-stack the chrome.
 */
class FrostedChromeTest {

    @Test
    fun frostedAlphaIsCardParity() {
        assertEquals(0.92f, FrostedChromeAlpha, 0.0f)
    }

    @Test
    fun scaledContainerAlphaKeepsFrostedExactly() {
        // Frost (0.97) must not dilute the pinned 0.92; every other
        // style (1.0) passes it through unchanged either way.
        assertEquals(0.92f, scaledContainerAlpha(0.92f, 0.97f, true), 0.0f)
        assertEquals(0.92f, scaledContainerAlpha(0.92f, 1f, true), 0.0f)
        assertEquals(0.92f, scaledContainerAlpha(0.92f, 1f, false), 0.0f)
    }

    @Test
    fun scaledContainerAlphaPreservesTodayOtherwise() {
        assertEquals(0.8924f, scaledContainerAlpha(0.92f, 0.97f, false), 0.0001f)
        assertEquals(0.97f, scaledContainerAlpha(1f, 0.97f, false), 0.0001f)
    }

    @Test
    fun chromeSurfaceModeTruthTable() {
        // Outside a frosted pane: only toggle-on map chrome frosts.
        assertEquals(ChromeSurfaceMode.OPAQUE, chromeSurfaceMode(false, false, false))
        assertEquals(ChromeSurfaceMode.OPAQUE, chromeSurfaceMode(false, true, false))
        assertEquals(ChromeSurfaceMode.OPAQUE, chromeSurfaceMode(true, false, false))
        assertEquals(ChromeSurfaceMode.FROSTED, chromeSurfaceMode(true, true, false))
        // Inside a frosted pane: every further surface passes through,
        // whatever the toggle and the frosted flag say — the fill is
        // drawn exactly once per pane, never stacked.
        assertEquals(ChromeSurfaceMode.PASSTHROUGH, chromeSurfaceMode(false, false, true))
        assertEquals(ChromeSurfaceMode.PASSTHROUGH, chromeSurfaceMode(false, true, true))
        assertEquals(ChromeSurfaceMode.PASSTHROUGH, chromeSurfaceMode(true, false, true))
        assertEquals(ChromeSurfaceMode.PASSTHROUGH, chromeSurfaceMode(true, true, true))
    }

    @Test
    fun chromeCornersFollowTheStyleRule() {
        // Pitlane squares to its own 6dp corner; every other style
        // keeps the call-site fallback exactly (20dp pane, 16dp card,
        // square inner surfaces alike).
        assertEquals(RoundedCornerShape(6.dp), cardShapeForLook(true, 6.dp, RoundedCornerShape(20.dp)))
        assertEquals(RoundedCornerShape(6.dp), cardShapeForLook(true, 6.dp, RoundedCornerShape(0.dp)))
        assertEquals(RoundedCornerShape(20.dp), cardShapeForLook(false, 20.dp, RoundedCornerShape(20.dp)))
        assertEquals(RoundedCornerShape(16.dp), cardShapeForLook(false, 20.dp, RoundedCornerShape(16.dp)))
        assertEquals(RoundedCornerShape(0.dp), cardShapeForLook(false, 20.dp, RoundedCornerShape(0.dp)))
    }
}
