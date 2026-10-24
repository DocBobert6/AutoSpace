package app.autospace.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import app.autospace.GearslipLog

/**
 * Keeps the phone's notifications readable while audio is being sent to the car.
 *
 * Playback capture hangs off a MediaProjection, and since Android 15 any full-screen projection
 * makes SystemUI hide notification contents, on the assumption that someone is watching the
 * screen. Nobody is: the audio projection never makes a display. The one exemption an ordinary app
 * can reach is the developer setting that switches the protection off, so Gearslip turns it off for
 * the length of the capture and back on afterwards.
 *
 * Writing it needs WRITE_SECURE_SETTINGS, granted once over adb. Without the grant this does
 * nothing and notifications stay hidden while music plays, which is the platform's default.
 *
 * Phone mirroring deliberately does not use this: there the screen really is being shown.
 */
internal object ScreenShareGuard {

    /** Settings.Global.DISABLE_SCREEN_SHARE_PROTECTIONS_FOR_APPS_AND_NOTIFICATIONS, hidden API. */
    private const val SETTING = "disable_screen_share_protections_for_apps_and_notifications"
    private const val PREFS = "gearslip_screen_share_guard"

    /** Set while Gearslip has the protection switched off, so a crash can be undone at next launch. */
    private const val KEY_LIFTED = "lifted"

    fun canLift(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** Call before the projection starts: the system decides what to hide at that moment. */
    fun lift(context: Context) {
        if (!canLift(context)) {
            GearslipLog.i("audio: notifications will be hidden while capturing (no WRITE_SECURE_SETTINGS)")
            return
        }
        val resolver = context.contentResolver
        // Already off, whether by the driver in Developer options or by us: leave it as found.
        if (Settings.Global.getInt(resolver, SETTING, 0) != 0) return
        runCatching {
            prefs(context).edit().putBoolean(KEY_LIFTED, true).commit()
            Settings.Global.putInt(resolver, SETTING, 1)
            GearslipLog.i("audio: screen share protection off for the drive, notifications stay visible")
        }.onFailure { GearslipLog.w("audio: could not lift screen share protection: ${it.message}") }
    }

    /** Put the protection back, if Gearslip is the one that took it away. */
    fun restore(context: Context) {
        if (!prefs(context).getBoolean(KEY_LIFTED, false)) return
        runCatching {
            Settings.Global.putInt(context.contentResolver, SETTING, 0)
            prefs(context).edit().remove(KEY_LIFTED).commit()
            GearslipLog.i("audio: screen share protection back on")
        }.onFailure { GearslipLog.w("audio: could not restore screen share protection: ${it.message}") }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
