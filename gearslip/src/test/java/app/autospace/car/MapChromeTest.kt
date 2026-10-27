package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** r63 map chrome: stored-mode parsing + migration, Themed default, experimental gate, strip rules. */
class MapChromeTest {

    @Test
    fun storedMeridianParses() {
        assertEquals(MapChrome.MERIDIAN, mapChromeFromStored("MERIDIAN"))
    }

    @Test
    fun storedGoogleMigratesToMeridian() {
        assertEquals(MapChrome.MERIDIAN, mapChromeFromStored("GOOGLE"))
    }

    @Test
    fun storedThemedParses() {
        assertEquals(MapChrome.THEMED, mapChromeFromStored("THEMED"))
    }

    @Test
    fun blankStoredDefaultsToThemed() {
        assertEquals(MapChrome.THEMED, mapChromeFromStored(""))
    }

    @Test
    fun unknownStoredDefaultsToThemed() {
        assertEquals(MapChrome.THEMED, mapChromeFromStored("NEON"))
    }

    @Test
    fun meridianCardColorIsSampledGreen() {
        assertEquals(0xFF3E6F74L, MapChromeColors.MERIDIAN_CARD)
    }

    @Test
    fun meridianStripColorIsDarkerSampledGreen() {
        assertEquals(0xFF335C5EL, MapChromeColors.MERIDIAN_STRIP)
    }

    @Test
    fun meridianMinutesColorIsSampledGreen() {
        assertEquals(0xFF789A75L, MapChromeColors.MERIDIAN_MINUTES)
    }

    @Test
    fun meridianEtaColorIsSampledDark() {
        assertEquals(0xFF131429L, MapChromeColors.MERIDIAN_ETA)
    }

    @Test
    fun meridianStopButtonIsWhiteOnDark() {
        assertEquals(0xFFFFFFFFL, MapChromeColors.MERIDIAN_STOP_BACKGROUND)
        assertEquals(0xFF131429L, MapChromeColors.MERIDIAN_STOP_CONTENT)
    }

    @Test
    fun gateOffForcesThemed() {
        assertEquals(MapChrome.THEMED, effectiveMapChrome(MapChrome.MERIDIAN, experimentalOn = false))
        assertEquals(MapChrome.THEMED, effectiveMapChrome(MapChrome.THEMED, experimentalOn = false))
    }

    @Test
    fun gateOnHonorsStoredChoice() {
        assertEquals(MapChrome.MERIDIAN, effectiveMapChrome(MapChrome.MERIDIAN, experimentalOn = true))
        assertEquals(MapChrome.THEMED, effectiveMapChrome(MapChrome.THEMED, experimentalOn = true))
    }

    @Test
    fun stripShowsOnlyInMeridianModeWithNext() {
        assertTrue(showNextStrip(meridian = true, hasNext = true))
        assertFalse(showNextStrip(meridian = true, hasNext = false))
        assertFalse(showNextStrip(meridian = false, hasNext = true))
        assertFalse(showNextStrip(meridian = false, hasNext = false))
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
