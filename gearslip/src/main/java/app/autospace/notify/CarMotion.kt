package app.autospace.notify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import app.autospace.GearslipLog
import app.autospace.car.CarEnvironment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the car is moving, judged from the phone's GPS speed.
 *
 * Read by the notification popup guard, the browser passenger gate
 * and the video player's audio-only branch. With no permission or
 * no recent fix it answers "not moving", so a missing signal never
 * hides anything for good.
 *
 * Simulated driving (Robert, 2026-10-25, couch testing): the
 * preview settings page can force MOVING or PARKED on demand, so
 * the passenger gates and the audio-only branch can be tested from
 * the couch. The override is in-memory only, defaults to AUTO, and
 * is NEVER consulted during a real session: while
 * [CarEnvironment.realSession] is true, [moving] is the real GPS
 * answer regardless of the override, and entering a real session
 * resets the override to AUTO. So it can drive the preview and
 * phone-side readers, but never a real drive.
 */
object CarMotion {

    private const val MOVING_MS = 2.0f          // about 7 km/h
    private const val STALE_MS = 15_000L

    enum class SimMode { AUTO, MOVING, PARKED }

    private val simModeFlow = MutableStateFlow(SimMode.AUTO)
    val simMode: StateFlow<SimMode> = simModeFlow.asStateFlow()

    fun setSimMode(mode: SimMode) {
        simModeFlow.value = mode
        GearslipLog.i(
            when (mode) {
                SimMode.AUTO -> "motion: simulated driving off (real GPS)"
                SimMode.MOVING -> "motion: simulated driving MOVING (test only, never in a real session)"
                SimMode.PARKED -> "motion: simulated driving PARKED (test only, never in a real session)"
            }
        )
    }

    private var listener: LocationListener? = null
    private var speed = 0f
    private var fixAt = 0L

    val moving: Boolean
        get() {
            if (CarEnvironment.realSession) return realMoving()
            return when (simModeFlow.value) {
                SimMode.MOVING -> true
                SimMode.PARKED -> false
                SimMode.AUTO -> realMoving()
            }
        }

    private fun realMoving(): Boolean =
        SystemClock.elapsedRealtime() - fixAt < STALE_MS && speed > MOVING_MS

    fun start(context: Context) {
        if (listener != null) return
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(LocationManager::class.java) ?: return
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                speed = if (location.hasSpeed()) location.speed else 0f
                fixAt = SystemClock.elapsedRealtime()
            }
        }
        listener = l
        runCatching { manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 0f, l) }
    }

    fun stop(context: Context) {
        listener?.let { l -> context.getSystemService(LocationManager::class.java)?.removeUpdates(l) }
        listener = null
    }
}
