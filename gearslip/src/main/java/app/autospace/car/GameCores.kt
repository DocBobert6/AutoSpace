package app.autospace.car

/**
 * r92: the core registry for the Games section, ported from Mariner's
 * CoreStore/CoreDownload mapping for the car screen.
 *
 * The manifest is Mariner's published cores.json (see MANIFEST_URL);
 * the entries below are its contents for this app's five cores,
 * kept here so the car can resolve a console to a verified file
 * even before the manifest has been fetched. The live manifest now
 * carries the 32X entry too; its size and hash below were verified
 * against the release asset itself, and they match the manifest.
 * The Game Boy and DS entries are not carried: those consoles are
 * not in this app's set.
 */
data class GameCoreEntry(
    val key: String,
    val file: String,
    val size: Long,
    val sha256: String,
    val consoles: List<String>
)

object GameCores {
    const val MANIFEST_URL =
        "https://github.com/DocBobert6/NotscapeMariner/releases/download/cores/cores.json"
    const val DOWNLOAD_BASE_URL =
        "https://github.com/DocBobert6/NotscapeMariner/releases/download/cores/"

    val MANIFEST_ENTRIES = listOf(
        GameCoreEntry(
            "fceumm",
            "fceumm_libretro_android.so",
            2878224L,
            "d6231558b5a843cce41cb1aafbe696497acf0650cbf68f14e54d452e58c24420",
            listOf("nes")
        ),
        GameCoreEntry(
            "genesis_plus_gx",
            "genesis_plus_gx_libretro_android.so",
            11116376L,
            "e7d5df89d2053b285facceb89e7f5eda751af8b848b08a5701d972d3064f2e1a",
            listOf("genesis", "sms", "gg")
        ),
        GameCoreEntry(
            "snes9x",
            "snes9x_libretro_android.so",
            2601680L,
            "7ebeeafcc9d8b637275205217b310ab19795dce477a3a22b199adf8964aea5bf",
            listOf("snes")
        ),
        GameCoreEntry(
            "swanstation",
            "swanstation_libretro_android.so",
            26295656L,
            "c8cb174c1363d993f54ded30001cbb526ef497957ef05185b20919f1abf72ca8",
            listOf("ps1")
        ),
        GameCoreEntry(
            "picodrive",
            "picodrive_libretro_android.so",
            1619096L,
            "8d9158d751cb6a5b07c10c2017759a81875d29acbc97d340a7ec75f995c7fe93",
            listOf("32x")
        )
    )

    /** The core file for a console, or null when the console has none. */
    fun coreFileNameFor(system: String): String? = when (system) {
        "nes" -> "fceumm_libretro_android.so"
        "snes" -> "snes9x_libretro_android.so"
        "genesis", "sms", "sg1000", "segacd" -> "genesis_plus_gx_libretro_android.so"
        "32x" -> "picodrive_libretro_android.so"
        "ps1" -> "swanstation_libretro_android.so"
        else -> null
    }

    /** The manifest key for a console. Mariner has no SG-1000 key; the
     * SG-1000 runs on the same hardware family, so it maps to the
     * same core as Genesis and Master System. */
    fun coreKeyFor(system: String): String? = when (system) {
        "nes" -> "fceumm"
        "snes" -> "snes9x"
        "genesis", "sms", "sg1000", "segacd" -> "genesis_plus_gx"
        "32x" -> "picodrive"
        "ps1" -> "swanstation"
        else -> null
    }

    /** The manifest entry for a console, looked up by core key (Mariner
     * resolves entries the same way, not through the consoles list). */
    fun entryForSystem(
        manifest: List<GameCoreEntry>,
        system: String
    ): GameCoreEntry? {
        val key = coreKeyFor(system) ?: return null
        return manifest.firstOrNull { it.key == key }
    }

    /** A fetched manifest with the pinned entries added when missing,
     * so an older manifest without the 32X entry still resolves it. */
    fun manifestWithPinnedEntries(
        fetched: List<GameCoreEntry>
    ): List<GameCoreEntry> {
        val present = fetched.map { it.key }.toSet()
        return fetched + MANIFEST_ENTRIES.filter { it.key !in present }
    }

    /** Core keys for a set of consoles, each key once, in first-seen
     * order (Mariner's dedupeKeys shape). */
    fun dedupeKeys(systems: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (system in systems) {
            val key = coreKeyFor(system) ?: continue
            seen.add(key)
        }
        return seen.toList()
    }

    fun urlFor(entry: GameCoreEntry): String = DOWNLOAD_BASE_URL + entry.file
}
