package app.autospace.host

import android.content.Context
import android.content.pm.PackageManager

/**
 * Google's own Android Auto, in whatever guise it appears. Gearslip replaces it, so its entries
 * (there are many: one per screen it registers) are noise in every list of apps to open.
 */
object AndroidAuto {

    /** Kept in sync with the `<queries><package>` entries in the manifest: package visibility
     * hides an app's presence entirely unless one names it (or a broader query matches it), and
     * this one has no launcher icon on current builds for a launcher-category query to find. */
    private val KNOWN_PACKAGES = listOf("com.google.android.projection.gearhead")

    private val PACKAGE_MARKERS = listOf("gearhead", "projection.gearhead", "android.auto", "androidauto")

    fun matches(packageName: String, label: String): Boolean {
        val pkg = packageName.lowercase()
        if (PACKAGE_MARKERS.any { it in pkg }) return true
        val name = label.lowercase().replace(Regex("[^a-z ]"), " ")
        return "android auto" in name || "androidauto" in name.replace(" ", "")
    }

    /** The package name of Google's Android Auto, if it is installed. */
    fun installedPackage(context: Context): String? {
        val manager = context.packageManager
        return KNOWN_PACKAGES.firstOrNull { pkg ->
            runCatching { manager.getPackageInfo(pkg, 0) }.isSuccess
        }
    }

    /**
     * Whether [packageName] can still fight Gearslip for the USB connection: installed, and not
     * already disabled for this user. `ApplicationInfo.enabled` only reflects the manifest's
     * static default, not a driver's own Settings toggle, so this reads the actual runtime
     * setting instead - a driver who disabled it instead of uninstalling it (the app info screen
     * offers both) has already done what this step asks.
     */
    fun isActive(context: Context, packageName: String): Boolean {
        val setting = runCatching { context.packageManager.getApplicationEnabledSetting(packageName) }
            .getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        return setting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ||
            setting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }
}
