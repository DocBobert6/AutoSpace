package app.autospace.car

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The Auto night decision through pure [NightAuto] functions: schedule
 * windows (including past-midnight wrap), the on-device solar algorithm
 * against known sunrise/sunset values, and the polar cases.
 */
class NightAutoTest {

    // The schedule path reads the device default timezone, so pin it for
    // the clock-based tests (solar tests pass their offset explicitly).
    private var savedTz: TimeZone? = null

    @Before
    fun pinTimezone() {
        savedTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTimezone() {
        TimeZone.setDefault(savedTz)
    }

    @Test
    fun scheduleWindowDaytime() {
        assertTrue(NightAuto.isNightBySchedule(nowMin = 22 * 60, startMin = 21 * 60, endMin = 7 * 60))
        assertFalse(NightAuto.isNightBySchedule(nowMin = 12 * 60, startMin = 21 * 60, endMin = 7 * 60))
        assertTrue(NightAuto.isNightBySchedule(nowMin = 6 * 60, startMin = 21 * 60, endMin = 7 * 60))
        assertFalse(NightAuto.isNightBySchedule(nowMin = 7 * 60, startMin = 21 * 60, endMin = 7 * 60))
        assertFalse(NightAuto.isNightBySchedule(nowMin = 21 * 60, startMin = 21 * 60, endMin = 21 * 60))
    }

    @Test
    fun scheduleWindowNonWrapping() {
        assertTrue(NightAuto.isNightBySchedule(nowMin = 23 * 60, startMin = 22 * 60, endMin = 23 * 60 + 30))
        assertFalse(NightAuto.isNightBySchedule(nowMin = 21 * 60, startMin = 22 * 60, endMin = 23 * 60 + 30))
    }

    @Test
    fun solarNewYorkSummer() {
        // New York City, 2026-06-21, EDT (UTC-4): sunrise ~5:25 AM, sunset ~8:30 PM.
        val sun = NightAuto.sunTimes(40.7128, -74.0060, -240, 2026, 6, 21)
        check(sun is SunResult.Times) { "expected Times, got $sun" }
        assertWithin("sunrise", expected = 5 * 60 + 25, actual = sun.sunriseMin, tolerance = 20)
        assertWithin("sunset", expected = 20 * 60 + 30, actual = sun.sunsetMin, tolerance = 20)
    }

    @Test
    fun solarNewYorkWinter() {
        // New York City, 2026-01-15, EST (UTC-5): sunrise ~7:19 AM, sunset ~4:53 PM.
        val sun = NightAuto.sunTimes(40.7128, -74.0060, -300, 2026, 1, 15)
        check(sun is SunResult.Times) { "expected Times, got $sun" }
        assertWithin("sunrise", expected = 7 * 60 + 19, actual = sun.sunriseMin, tolerance = 20)
        assertWithin("sunset", expected = 16 * 60 + 53, actual = sun.sunsetMin, tolerance = 20)
    }

    @Test
    fun solarPolarDayAndNight() {
        assertEquals(
            SunResult.AlwaysDay,
            NightAuto.sunTimes(78.0, 16.0, 120, 2026, 6, 21),
        )
        assertEquals(
            SunResult.AlwaysNight,
            NightAuto.sunTimes(78.0, 16.0, 60, 2026, 1, 15),
        )
    }

    @Test
    fun formatMinutes() {
        assertEquals("12:00 AM", NightAuto.formatMinutes(0))
        assertEquals("12:00 PM", NightAuto.formatMinutes(12 * 60))
        assertEquals("7:19 AM", NightAuto.formatMinutes(7 * 60 + 19))
        assertEquals("8:30 PM", NightAuto.formatMinutes(20 * 60 + 30))
    }

    @Test
    fun autoNightModes() {
        assertFalse(NightAuto.isAutoNightNow(null, AutoNightMode.OFF, 21 * 60, 7 * 60, timeMs(2026, 6, 21, 23, 0)))
        assertTrue(NightAuto.isAutoNightNow(null, AutoNightMode.SCHEDULE, 21 * 60, 7 * 60, timeMs(2026, 6, 21, 23, 0)))
        assertFalse(NightAuto.isAutoNightNow(null, AutoNightMode.SCHEDULE, 21 * 60, 7 * 60, timeMs(2026, 6, 21, 12, 0)))
        // Sunrise & sunset with no context (no location) is inactive, never wrong.
        assertFalse(NightAuto.isAutoNightNow(null, AutoNightMode.SUNRISE_SUNSET, 21 * 60, 7 * 60, timeMs(2026, 6, 21, 23, 0)))
    }

    private fun assertWithin(label: String, expected: Int, actual: Int, tolerance: Int) {
        assertTrue("$label: expected ~$expected, got $actual", kotlin.math.abs(actual - expected) <= tolerance)
    }

    private fun timeMs(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(Calendar.YEAR, year); set(Calendar.MONTH, month - 1); set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
