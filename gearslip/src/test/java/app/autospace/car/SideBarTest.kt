package app.autospace.car

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** r87 steering-side app bar, r94 float, r99 Meridian forms: strip mapping, alignment mapping, clock split, rail/pill form. */
class SideBarTest {





    @Test
    fun stripWidthIsRailWidthPlusFloat() {
        assertEquals(88.dp, NAV_BAR_SIDE_STRIP_WIDTH)
    }

    @Test
    fun sideFloatIsTwelveDp() {
        assertEquals(12.dp, NAV_BAR_SIDE_FLOAT)
    }

    @Test
    fun stripWidthIsRailPlusBothFloats() {
        assertEquals(NAV_BAR_HEIGHT + NAV_BAR_SIDE_FLOAT + NAV_BAR_SIDE_FLOAT, NAV_BAR_SIDE_STRIP_WIDTH)
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

    @Test
    fun meridianFormWideLandscapeIsRail() {
        assertEquals(MeridianBarForm.RAIL, meridianBarForm(800, 480, 160))
    }

    @Test
    fun meridianFormPortraitIsPill() {
        assertEquals(MeridianBarForm.PILL, meridianBarForm(400, 800, 160))
    }

    @Test
    fun meridianFormShortLandscapeIsPill() {
        assertEquals(MeridianBarForm.PILL, meridianBarForm(800, 320, 160))
    }

    @Test
    fun meridianFormNarrowLandscapeIsPill() {
        assertEquals(MeridianBarForm.PILL, meridianBarForm(500, 300, 160))
    }

    @Test
    fun meridianFormDensityConvertsPxToDp() {
        // The same pixels are a rail at 160dpi but a pill at 320dpi,
        // where the height is only 360dp.
        assertEquals(MeridianBarForm.PILL, meridianBarForm(1280, 720, 320))
    }

    @Test
    fun meridianFormSquareIsRail() {
        assertEquals(MeridianBarForm.RAIL, meridianBarForm(600, 600, 160))
    }

    @Test
    fun meridianFormNoDensityIsRail() {
        assertEquals(MeridianBarForm.RAIL, meridianBarForm(400, 800, 0))
    }

    @Test
    fun pillStripIsPillHeightPlusFloats() {
        assertEquals(NAV_BAR_HEIGHT + NAV_BAR_SIDE_FLOAT + NAV_BAR_SIDE_FLOAT, NAV_BAR_PILL_STRIP_WIDTH)
    }
}

