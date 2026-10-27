package app.autospace.car

import androidx.compose.ui.Alignment
import org.junit.Assert.assertEquals
import org.junit.Test

/** r74 steering-side app bar: experimental gate, side yields, alignment mapping. */
class SideBarTest {

    @Test
    fun gateBothOnShowsRail() {
        assertEquals(true, sideBarNow(true, true))
    }

    @Test
    fun gateExperimentalOffHidesRail() {
        assertEquals(false, sideBarNow(false, true))
    }

    @Test
    fun gateToggleOffHidesRail() {
        assertEquals(false, sideBarNow(true, false))
    }

    @Test
    fun gateBothOffHidesRail() {
        assertEquals(false, sideBarNow(false, false))
    }

    @Test
    fun sideAllClearIsDriver() {
        assertEquals(SideBarSide.DRIVER_SIDE, effectiveSideBarSide(false, false, false))
    }

    @Test
    fun sideTurnCardYieldsToPassenger() {
        assertEquals(SideBarSide.PASSENGER_SIDE, effectiveSideBarSide(true, false, false))
    }

    @Test
    fun sideColumnYieldsToPassenger() {
        assertEquals(SideBarSide.PASSENGER_SIDE, effectiveSideBarSide(false, true, false))
    }

    @Test
    fun sideCardYieldsToPassenger() {
        assertEquals(SideBarSide.PASSENGER_SIDE, effectiveSideBarSide(false, false, true))
    }

    @Test
    fun sideAllYieldIsPassenger() {
        assertEquals(SideBarSide.PASSENGER_SIDE, effectiveSideBarSide(true, true, true))
    }

    @Test
    fun alignmentDriverIsCenterStart() {
        assertEquals(Alignment.CenterStart, sideBarAlignment(SideBarSide.DRIVER_SIDE))
    }

    @Test
    fun alignmentPassengerIsCenterEnd() {
        assertEquals(Alignment.CenterEnd, sideBarAlignment(SideBarSide.PASSENGER_SIDE))
    }
}
