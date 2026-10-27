package app.autospace.car

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** r87 steering-side app bar: experimental gate, strip mapping, alignment mapping, clock split. */
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
    fun stripWidthIsRailWidth() {
        assertEquals(64.dp, NAV_BAR_SIDE_STRIP_WIDTH)
    }

    @Test
    fun stripDriverSideIsStart() {
        assertTrue(sideBarStripOnStart(SideBarSide.DRIVER_SIDE))
    }

    @Test
    fun stripPassengerSideIsEnd() {
        assertFalse(sideBarStripOnStart(SideBarSide.PASSENGER_SIDE))
    }

    @Test
    fun alignmentDriverIsCenterStart() {
        assertEquals(Alignment.CenterStart, sideBarAlignment(SideBarSide.DRIVER_SIDE))
    }

    @Test
    fun alignmentPassengerIsCenterEnd() {
        assertEquals(Alignment.CenterEnd, sideBarAlignment(SideBarSide.PASSENGER_SIDE))
    }

    @Test
    fun clockPartsSplitAmPm() {
        assertEquals("9:53" to "AM", railClockParts("9:53 AM"))
    }

    @Test
    fun clockParts24HourHasNoMarker() {
        assertEquals("23:59" to null, railClockParts("23:59"))
    }

    @Test
    fun clockPartsMultiWordMarker() {
        assertEquals("12:00" to "p. m.", railClockParts("12:00 p. m."))
    }

    @Test
    fun clockPartsNonBreakingSpaceSplits() {
        assertEquals("9:53" to "AM", railClockParts("9:53\u00A0AM"))
    }
}
