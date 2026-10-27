package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * r83 unit tests for the nav-bar treatment decision: the floating nav
 * bar's own switch, the steering-side rail, Home vs other screens, and
 * the lyrics split. The rule is pure, so no Compose runtime is needed.
 * The switch value here is what the setting holds after r83's one-time
 * seeding from floating media — the seeding itself lives in
 * CarSettings init, not in this pure rule.
 */
class NavBarTreatmentTest {

    @Test
    fun railWinsOverEverything() {
        assertEquals(NavBarTreatment.RAIL, navBarTreatment(true, true, true, SidePanel.NONE))
        assertEquals(NavBarTreatment.RAIL, navBarTreatment(true, true, true, SidePanel.LYRICS))
        assertEquals(NavBarTreatment.RAIL, navBarTreatment(false, true, false, SidePanel.NONE))
    }

    @Test
    fun switchOnFloatsOnHomeExceptLyrics() {
        assertEquals(NavBarTreatment.FLOATING, navBarTreatment(true, false, true, SidePanel.NONE))
        assertEquals(NavBarTreatment.FLOATING, navBarTreatment(true, false, true, SidePanel.CONTROLS))
        assertEquals(NavBarTreatment.IN_FLOW, navBarTreatment(true, false, true, SidePanel.LYRICS))
    }

    @Test
    fun switchOffIsAlwaysInFlow() {
        assertEquals(NavBarTreatment.IN_FLOW, navBarTreatment(false, false, true, SidePanel.NONE))
        assertEquals(NavBarTreatment.IN_FLOW, navBarTreatment(false, false, true, SidePanel.CONTROLS))
        assertEquals(NavBarTreatment.IN_FLOW, navBarTreatment(false, false, false, SidePanel.NONE))
    }

    @Test
    fun offHomeIsAlwaysInFlow() {
        assertEquals(NavBarTreatment.IN_FLOW, navBarTreatment(true, false, false, SidePanel.NONE))
        assertEquals(NavBarTreatment.IN_FLOW, navBarTreatment(true, false, false, SidePanel.CONTROLS))
    }

    @Test
    fun floatingCaseMatchesOverlayRule() {
        // The treatment's FLOATING case is exactly the overlay rule
        // with the bar switch in place of the floating-media flag it
        // replaces — the two can never disagree.
        for (panel in listOf(SidePanel.NONE, SidePanel.CONTROLS, SidePanel.LYRICS)) {
            for (onHome in listOf(true, false)) {
                val floating = navBarTreatment(true, false, onHome, panel) == NavBarTreatment.FLOATING
                assertEquals(shouldOverlayNavBar(onHome, true, panel), floating)
                assertEquals(false, navBarTreatment(false, false, onHome, panel) == NavBarTreatment.FLOATING)
            }
        }
    }
}
