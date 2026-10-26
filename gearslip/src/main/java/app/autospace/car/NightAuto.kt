package app.autospace.car

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Auto night: switches the effective color scheme to Night during night
 * hours and back afterwards (Robert, 2026-10-25). Precedence rule, stated
 * once and implemented exactly so: Auto night overrides the user's chosen
 * scheme at night; the chosen scheme returns in the morning. Picking Night
 * yourself just means Night all the time. To stop the switching, turn
 * Auto night off. Nothing here ever changes the stored scheme.
 */
enum class AutoNightMode { OFF, SCHEDULE, SUNRISE_SUNSET }

/** Sunrise/sunset for one local day, in minutes after local midnight. */
sealed interface SunResult {
    data class Times(val sunriseMin: Int, val sunsetMin: Int) : SunResult
    /** Sun never sets (polar day): never night. */
    data object AlwaysDay : SunResult
    /** Sun never rises (polar night): always night. */
    data object AlwaysNight : SunResult
}

object NightAuto {

    /** Night window for a user schedule: [startMin, endMin), wrapping past midnight. */
    fun isNightBySchedule(nowMin: Int, startMin: Int, endMin: Int): Boolean {
        if (startMin == endMin) return false
        return if (startMin < endMin) nowMin in startMin until endMin
        else nowMin >= startMin || nowMin < endMin
    }

    /**
     * Sunrise/sunset by the NOAA solar algorithm (no network, no server).
     * [latDeg]/[lonDeg] in degrees (east positive), [tzOffsetMin] = local
     * time minus UTC in minutes (DST included). Returns null only for
     * impossible inputs; polar days/nights come back as AlwaysDay/AlwaysNight.
     */
    fun sunTimes(latDeg: Double, lonDeg: Double, tzOffsetMin: Int, year: Int, month: Int, day: Int): SunResult {
        // Day number N (Jan 1 = 1), with the NOAA fractional-day correction.
        val n = dayOfYear(year, month, day)
        val lngHour = lonDeg / 15.0
        fun sunTime(isRise: Boolean): Double? {
            val t = n + ((if (isRise) 6.0 else 18.0) - lngHour) / 24.0
            val m = (0.9856 * t - 3.289) % 360.0
            val l = (m + 1.916 * sin(m * PI / 180.0) + 0.020 * sin(2.0 * m * PI / 180.0) + 282.634)
                .let { ((it % 360.0) + 360.0) % 360.0 }
            var ra = Math.toDegrees(kotlin.math.atan(0.91764 * tan(l * PI / 180.0)))
            ra = ((ra % 360.0) + 360.0) % 360.0
            // Put RA in the same quadrant as L.
            val lQuadrant = (l / 90.0).toInt() * 90.0
            val raQuadrant = (ra / 90.0).toInt() * 90.0
            ra += lQuadrant - raQuadrant
            ra /= 15.0
            val sinDec = 0.39782 * sin(l * PI / 180.0)
            val cosDec = cos(asin(sinDec))
            val latRad = latDeg * PI / 180.0
            val cosH = (cos(90.833 * PI / 180.0) - sinDec * sin(latRad)) / (cosDec * cos(latRad))
            // No rise/set at this latitude today (polar day or night — the
            // caller tells them apart from the solar-noon altitude below).
            if (cosH > 1.0 || cosH < -1.0) return null
            val h = if (isRise) 360.0 - Math.toDegrees(acos(cosH)) else Math.toDegrees(acos(cosH))
            val hHours = h / 15.0
            val tHours = hHours + ra - 0.06571 * t - 6.622
            var utc = (tHours - lngHour) % 24.0
            if (utc < 0) utc += 24.0
            return utc * 60.0 + tzOffsetMin
        }
        // Distinguish the two polar cases: cosH > 1 at sunrise means the sun
        // never rises; recompute the noon cosH sign to tell them apart.
        val rise = sunTime(true)
        val set = sunTime(false)
        if (rise == null || set == null) {
            // At solar noon the sun is up iff sinDec*sinLat + cosDec*cosLat > cos(zenith).
            val t = n + (12.0 - lngHour) / 24.0
            val m = (0.9856 * t - 3.289) % 360.0
            val l = ((m + 1.916 * sin(m * PI / 180.0) + 0.020 * sin(2.0 * m * PI / 180.0) + 282.634) % 360.0 + 360.0) % 360.0
            val sinDec = 0.39782 * sin(l * PI / 180.0)
            val latRad = latDeg * PI / 180.0
            val noonAlt = sinDec * sin(latRad) + cos(asin(sinDec)) * cos(latRad)
            return if (noonAlt > cos(90.833 * PI / 180.0)) SunResult.AlwaysDay else SunResult.AlwaysNight
        }
        fun norm(min: Double): Int {
            var m = min.toInt() % 1440
            if (m < 0) m += 1440
            return m
        }
        return SunResult.Times(norm(rise), norm(set))
    }

    private fun dayOfYear(year: Int, month: Int, day: Int): Int {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(Calendar.YEAR, year); set(Calendar.MONTH, month - 1); set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.get(Calendar.DAY_OF_YEAR)
    }

    /** Last-known device location (lat, lon), or null without permission or any fix. */
    fun lastKnownLocation(context: Context): Pair<Double, Double>? {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val loc = manager.allProviders
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time } ?: return null
        return loc.latitude to loc.longitude
    }

    /** Today's sunrise/sunset for the device location, or null when unknown. */
    fun todaySunTimes(context: Context, nowMs: Long = System.currentTimeMillis()): SunResult? {
        val (lat, lon) = lastKnownLocation(context) ?: return null
        val tz = TimeZone.getDefault()
        val cal = Calendar.getInstance(tz).apply { timeInMillis = nowMs }
        return sunTimes(
            lat, lon, tz.getOffset(nowMs) / 60000,
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
        )
    }

    /**
     * Is auto night active right now? OFF is never active. SCHEDULE uses the
     * user's window. SUNRISE_SUNSET with no location is INACTIVE (never
     * silently uses wrong times) — the UI must say so and offer the schedule.
     */
    fun isAutoNightNow(context: Context?, mode: AutoNightMode, startMin: Int, endMin: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        return when (mode) {
            AutoNightMode.OFF -> false
            AutoNightMode.SCHEDULE -> {
                val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
                isNightBySchedule(cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE), startMin, endMin)
            }
            AutoNightMode.SUNRISE_SUNSET -> {
                if (context == null) return false
                when (val sun = todaySunTimes(context, nowMs)) {
                    null -> false
                    SunResult.AlwaysDay -> false
                    SunResult.AlwaysNight -> true
                    is SunResult.Times -> {
                        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
                        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                        nowMin < sun.sunriseMin || nowMin >= sun.sunsetMin
                    }
                }
            }
        }
    }

    /** "7:12 AM" for a minute-of-day value. */
    fun formatMinutes(min: Int): String {
        val h24 = (min / 60) % 24
        val m = min % 60
        val amPm = if (h24 < 12) "AM" else "PM"
        val h12 = when (h24 % 12) { 0 -> 12; else -> h24 % 12 }
        return "$h12:${m.toString().padStart(2, '0')} $amPm"
    }
}
