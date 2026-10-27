package app.autospace.car

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * r92: BIOS file flows, ported from Mariner's BiosFiles and the BIOS
 * section of its games screen for the car screen.
 *
 * The app ships no BIOS files: the user supplies their own, and the
 * picked stream is saved AS the expected file name in the app's
 * system folder, where the core looks for it, regardless of the
 * picked file's name. Sega CD needs one region BIOS; the Genesis
 * BIOS is optional and only used when System Boot ROM is enabled in
 * the game's options. No boot claim is made for PlayStation here:
 * Mariner lists the files without one.
 */
object GameBios {
    /** Expected BIOS files for a console, in display order. */
    fun expectedFor(console: String): List<String> = when (console) {
        "genesis" -> listOf("bios_MD.bin")
        "segacd" -> listOf(
            "bios_CD_U.bin",
            "bios_CD_E.bin",
            "bios_CD_J.bin"
        )
        "ps1" -> listOf(
            "scph5500.bin",
            "scph5501.bin",
            "scph5502.bin"
        )
        else -> emptyList()
    }

    /** The explanatory note shown under a console's BIOS section, or
     * null when the console has no note. */
    fun noteFor(console: String): String? = when (console) {
        "segacd" ->
            "Sega CD needs a BIOS — without one of these files, " +
                "games won't boot."
        "genesis" ->
            "Use the System Boot ROM option in the game's options " +
                "to use the installed BIOS."
        else -> null
    }

    fun systemDir(context: Context): File =
        File(context.filesDir, "system").apply { mkdirs() }

    fun file(context: Context, name: String): File =
        File(systemDir(context), name)

    fun isInstalled(context: Context, name: String): Boolean =
        file(context, name).exists()

    /** Import a picked stream as the expected file name. Returns false
     * when the stream could not be opened or copied. */
    fun importFile(context: Context, targetName: String, uri: Uri): Boolean =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file(context, targetName).outputStream().use { output ->
                    input.copyTo(output)
                }
            } != null
        }.getOrDefault(false)

    fun delete(context: Context, name: String): Boolean =
        file(context, name).delete()
}
