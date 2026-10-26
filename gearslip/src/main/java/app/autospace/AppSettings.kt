package app.autospace

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** User-facing preferences. Read fresh on every session, so changes apply on the next connect. */
object AppSettings {

    private const val PREFS = "gearslip_settings"
    private const val KEY_STARTUP_URL = "startup_url"
    private const val KEY_SKIPPED_CERT_SETUP = "skipped_cert_setup"
    private const val KEY_SEEN_PERMISSIONS_SETUP = "seen_permissions_setup"
    private const val KEY_GEARSLIP_ENABLED = "gearslip_enabled"

    /** r30: learned words typed on the car keyboard (newline-joined, lowercase). */
    const val KEY_KEYBOARD_LEARNED_WORDS = "car_keyboard_learned_words"
    private const val KEY_KEYBOARD_SUGGESTIONS = "car_keyboard_suggestions"
    private const val KEY_KEYBOARD_GLIDE = "car_keyboard_glide"
    private const val KEY_KEYBOARD_GLIDE_TRAIL = "car_keyboard_glide_trail"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getString(context: Context, key: String, default: String): String =
        prefs(context).getString(key, default) ?: default

    fun putString(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).apply()
    }

    /** Blank means "use the built-in test page". */
    fun startupUrl(context: Context): String =
        prefs(context).getString(KEY_STARTUP_URL, "").orEmpty()

    fun setStartupUrl(context: Context, value: String) {
        prefs(context).edit().putString(KEY_STARTUP_URL, value.trim()).apply()
    }

    /** Whether the certificate setup screen has been deliberately skipped. */
    fun hasSkippedCertSetup(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SKIPPED_CERT_SETUP, false)

    fun setSkippedCertSetup(context: Context) {
        prefs(context).edit().putBoolean(KEY_SKIPPED_CERT_SETUP, true).apply()
    }

    /** Whether the one-by-one permissions wizard has already run once. */
    fun hasSeenPermissionsSetup(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SEEN_PERMISSIONS_SETUP, false)

    fun setSeenPermissionsSetup(context: Context) {
        prefs(context).edit().putBoolean(KEY_SEEN_PERMISSIONS_SETUP, true).apply()
    }

    /** r30: suggestion strip on the car keyboard. On by default. */
    fun keyboardSuggestionsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEYBOARD_SUGGESTIONS, true)

    fun setKeyboardSuggestionsEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEYBOARD_SUGGESTIONS, value).apply()
    }

    /** r50: glide typing on the car keyboard. On by default. */
    fun keyboardGlideEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEYBOARD_GLIDE, true)

    fun setKeyboardGlideEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEYBOARD_GLIDE, value).apply()
    }

    /** r50: the glide trace line. On by default; separate from the glide switch. */
    fun keyboardGlideTrailEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEYBOARD_GLIDE_TRAIL, true)

    fun setKeyboardGlideTrailEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEYBOARD_GLIDE_TRAIL, value).apply()
    }

    /**
     * Whether Gearslip is allowed to claim the USB accessory connection. Off leaves the app
     * installed but disables the GearslipUsbAccessory alias that answers
     * USB_ACCESSORY_ATTACHED, so a real Android Auto head unit (or Google's own app) gets the
     * connection instead - the two fight over it otherwise, since only one app can win the
     * accessory handoff. The alias is separate from GearslipActivity itself - the activity that
     * shows this very switch - so turning it off can't disable the screen you're looking at.
     * On by default.
     */
    fun gearslipEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GEARSLIP_ENABLED, true)

    fun setGearslipEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_GEARSLIP_ENABLED, value).apply()
        applyGearslipEnabled(context)
    }

    /**
     * Makes the USB alias match the saved switch. Called at every launch as well, so a switch
     * saved without the alias following it (an older build crashed right there) heals itself.
     * Only the alias changes: the driver's "always open with Gearslip" choice for the car is kept.
     */
    fun applyGearslipEnabled(context: Context) {
        val value = gearslipEnabled(context)
        val appContext = context.applicationContext
        // The class name comes from the code's namespace, not the package: a debug build installs
        // as ".dev", but the alias is still app.autospace.GearslipUsbAccessory.
        val component = ComponentName(appContext.packageName, "${GearslipActivity::class.java.`package`!!.name}.GearslipUsbAccessory")
        val state = if (value) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        val pm = appContext.packageManager
        if (pm.getComponentEnabledSetting(component) == state) return
        runCatching { pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP) }
            .onFailure { GearslipLog.e("could not switch the USB connection ${if (value) "on" else "off"}", it) }
    }
}
