package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** r61 map chrome: stored-mode parsing, fixed Google colors, next-step strip rules. */
class MapChromeTest {

    @Test
    fun storedGoogleParses() {
        assertEquals(MapChrome.GOOGLE, mapChromeFromStored("GOOGLE"))
    }

    @Test
    fun storedThemedParses() {
        assertEquals(MapChrome.THEMED, mapChromeFromStored("THEMED"))
    }

    @Test
    fun blankStoredDefaultsToGoogle() {
        assertEquals(MapChrome.GOOGLE, mapChromeFromStored(""))
    }

    @Test
    fun unknownStoredDefaultsToGoogle() {
        assertEquals(MapChrome.GOOGLE, mapChromeFromStored("NEON"))
    }

    @Test
    fun googleCardColorIsSampledGreen() {
        assertEquals(0xFF3E6F74L, MapChromeColors.GOOGLE_CARD)
    }

    @Test
    fun googleStripColorIsDarkerSampledGreen() {
        assertEquals(0xFF335C5EL, MapChromeColors.GOOGLE_STRIP)
    }

    @Test
    fun googleMinutesColorIsSampledGreen() {
        assertEquals(0xFF789A75L, MapChromeColors.GOOGLE_MINUTES)
    }

    @Test
    fun googleEtaColorIsSampledDark() {
        assertEquals(0xFF131429L, MapChromeColors.GOOGLE_ETA)
    }

    @Test
    fun googleStopButtonIsWhiteOnDark() {
        assertEquals(0xFFFFFFFFL, MapChromeColors.GOOGLE_STOP_BACKGROUND)
        assertEquals(0xFF131429L, MapChromeColors.GOOGLE_STOP_CONTENT)
    }

    @Test
    fun stripShowsOnlyInGoogleModeWithNext() {
        assertTrue(showNextStrip(google = true, hasNext = true))
        assertFalse(showNextStrip(google = true, hasNext = false))
        assertFalse(showNextStrip(google = false, hasNext = true))
        assertFalse(showNextStrip(google = false, hasNext = false))
    }

    @Test
    fun stripTextPrefersCue() {
        assertEquals("Turn right", nextStripText("Turn right", "Main St"))
    }

    @Test
    fun stripTextFallsBackToRoad() {
        assertEquals("Main St", nextStripText("", "Main St"))
    }

    @Test
    fun stripTextNullWhenNeither() {
        assertNull(nextStripText("", ""))
    }
}
