package app.autospace.host

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * Apps that have been run through Gearslip from start to finish and behave. The launcher marks
 * them so a driver can pick something that works without finding out at the wheel.
 *
 * An app belongs here only after a real session: the app starts, draws, takes touches and
 * answers its buttons. Apps that connect but show no library, or refuse Gearslip as a host, stay
 * off the list.
 */
object KnownApps {

    /**
     * Apps whose media player works through Gearslip's own player even though their car screen
     * doesn't. Only the player tile gets the check, not the "· Browse" one.
     */
    private val workingPlayer = setOf(
        "com.spotify.music", // Spotify: play, browse, seek through Gearslip's player
    )

    fun playerWorks(packageName: String): Boolean = packageName in workingPlayer

    /**
     * Apps whose car screen refuses Gearslip but whose player works. When the player covers the
     * app (see [playerWorks]) the launcher drops the dead "· Browse" tile entirely rather than
     * show it; otherwise that tile gets a red X. Car library 1.9 and later accepts only Google's
     * host on a phone.
     */
    private val brokenScreen = setOf(
        "com.spotify.music", // Spotify 9.1.86 and later: car library 1.9 rejects Gearslip
    )

    fun screenBroken(packageName: String): Boolean = packageName in brokenScreen

    /**
     * Registers a car template service but never gives Gearslip a usable screen - left off the
     * launcher entirely rather than shown as a tile that goes nowhere.
     */
    private val hidden = setOf(
        "com.google.android.dialer",               // Google Phone
        "com.google.android.googlequicksearchbox",  // Google Assistant driving surfaces
        "com.google.android.apps.maps",             // Google Maps: shows as a messenger, and its car UI is Android Auto's own
    )

    fun isHidden(packageName: String): Boolean = packageName in hidden

    /** Shown on the launcher with a red X: known broken, but expected enough that hiding it outright would look like a bug. */
    private val broken = setOf(
        "com.google.android.apps.messaging", // Google Messages
        "com.waze",                          // Waze
        "com.generalmagic.magicearth",       // Magic Earth: never gets past connecting
    )

    fun isBroken(context: Context, packageName: String): Boolean =
        packageName in broken && !patched(context, packageName)

    /**
     * The certificate each broken app's own publisher signs it with. A copy signed with another
     * key has been patched to accept Gearslip, like Waze with the waze-gearslip Morphe patch, and
     * works.
     */
    private val officialSigners = mapOf(
        "com.waze" to "03637f6c5d8f604e6fdb79a6ffbfa578de4e318f8da22fc6106665247f8807d7",
    )

    /** Whether the installed copy is signed by someone other than its publisher. */
    private fun patched(context: Context, packageName: String): Boolean {
        val official = officialSigners[packageName] ?: return false
        val signers = runCatching {
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        }.getOrNull() ?: return false
        val sha256 = MessageDigest.getInstance("SHA-256")
        return signers.isNotEmpty() && signers.none { cert ->
            sha256.digest(cert.toByteArray()).joinToString("") { "%02x".format(it) } == official
        }
    }

}
