package app.autospace.car

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** r87 steering-side app bar, r94 float, r99 Meridian forms, r103 centered icons + digits-only clock: strip mapping, alignment mapping, clock text, rail/pill form. */
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
    fun clockDigitsUsNoonIsDigitsOnly() {
        assertEquals("12:00", railClockDigits(utc(2026, 9, 27, 12, 0), Locale.US, UTC))
    }

    @Test
    fun clockDigitsUsMorningDropsMarker() {
        assertEquals("9:07", railClockDigits(utc(2026, 9, 27, 9, 7), Locale.US, UTC))
    }

    @Test
    fun clockDigitsUsEveningConvertsToTwelveHour() {
        assertEquals("11:59", railClockDigits(utc(2026, 9, 27, 23, 59), Locale.US, UTC))
    }

    @Test
    fun clockDigitsUsMidnightIsTwelveThirty() {
        assertEquals("12:30", railClockDigits(utc(2026, 9, 27, 0, 30), Locale.US, UTC))
    }

    @Test
    fun clockDigitsGermanKeepsTwentyFourHour() {
        assertEquals("23:59", railClockDigits(utc(2026, 9, 27, 23, 59), Locale.GERMANY, UTC))
        assertEquals("09:07", railClockDigits(utc(2026, 9, 27, 9, 7), Locale.GERMANY, UTC))
    }

    @Test
    fun clockDigitsRespectsPassedTimeZone() {
        // The same instant reads 12:00 in UTC, 8:00 in New York.
        val instant = utc(2026, 9, 27, 12, 0)
        assertEquals("12:00", railClockDigits(instant, Locale.US, TimeZone.getTimeZone("UTC")))
        assertEquals("8:00", railClockDigits(instant, Locale.US, TimeZone.getTimeZone("America/New_York")))
    }

    private fun utc(year: Int, month0: Int, day: Int, hour: Int, minute: Int): Date {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply {
            set(year, month0, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.time
    }

    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

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

