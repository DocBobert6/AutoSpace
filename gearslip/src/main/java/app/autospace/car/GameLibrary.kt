package app.autospace.car

/**
 * r89: the Games library, ported from Mariner's games suite (its GameLibrary and
 * GameOrder) for the car screen. Round 1 covers the library only: a Games entry,
 * console pages, and folder/single-file import. No playback, no saves, no BIOS
 * flows, and nothing here names a core — console names appear in the app UI only.
 *
 * The library is a JSON list in a setting, exactly like Mariner's, and ROMs are
 * copies under filesDir/roms/<system>/ — the user's original files are never
 * touched, and removing a game deletes only the app's copy and its entry.
 * JSON goes through [JsonMini] (pure Kotlin): org.json is an Android-framework
 * stub on the JVM unit-test classpath, so it cannot be used here.
 */
data class GameConsole(val key: String, val label: String, val extensions: List<String>)

object GameConsoles {

    val NES = GameConsole("nes", "NES", listOf("nes"))
    val SG1000 = GameConsole("sg1000", "SG-1000", listOf("sg"))
    val SMS = GameConsole("sms", "Master System", listOf("sms"))
    val GENESIS = GameConsole("genesis", "Genesis", listOf("md", "gen"))
    val SNES = GameConsole("snes", "Super Nintendo", listOf("smc", "sfc"))
    val SEGACD = GameConsole("segacd", "Sega CD", listOf("cue", "m3u", "chd"))
    val X32 = GameConsole("32x", "32X", listOf("32x"))
    val PS1 = GameConsole("ps1", "PlayStation", listOf("cue", "m3u", "chd"))

    /**
     * Mariner's home order, with SG-1000 inserted before Master System (Sega's
     * chronology: SG-1000, then Master System, then Genesis, Sega CD, 32X).
     * Mariner's own map has no SG-1000 key; the .sg extension is added here
     * because the ordered console set includes it.
     */
    val ALL = listOf(NES, SG1000, SMS, GENESIS, SNES, SEGACD, X32, PS1)

    /** Disc extensions shared by two consoles: the extension alone cannot tell a
     * PlayStation disc from a Sega CD disc. */
    val DISC_EXTENSIONS = setOf("cue", "m3u", "chd")
    val DISC_CONSOLES = setOf("segacd", "ps1")

    fun byKey(key: String): GameConsole? = ALL.firstOrNull { it.key == key }

    fun extensionsFor(system: String): List<String> = byKey(system)?.extensions ?: emptyList()

    /**
     * Extension -> console key, or null. A disc extension with no target is
     * PlayStation (Mariner's first-mapping-wins); with a disc-console target,
     * the target wins, so a Sega CD .cue picked from the Sega CD page is a
     * Sega CD game, not a PlayStation one.
     */
    fun systemForExtension(ext: String, target: String?): String? {
        val lower = ext.lowercase()
        if (lower in DISC_EXTENSIONS) {
            if (target != null && target in DISC_CONSOLES) return target
            return "ps1"
        }
        return ALL.firstOrNull { lower in it.extensions }?.key
    }
}

data class GameEntry(
    val id: String,
    val title: String,
    val system: String,
    val fileName: String,
    val addedAt: Long,
    val lastPlayedAt: Long = 0L,
    val favorite: Boolean = false,
)

object GameLibrary {

    const val SETTING_KEY = "car_games_library"
    const val MAX_GAMES = 500

    /** Zip-entry caps, Mariner's shape: one entry over 64MB is skipped, and a
     * single zip holds at most 256MB total. Plain picked files stream uncapped. */
    const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 256L * 1024 * 1024

    /** Folder-walk bounds, Mariner's shape: 2000 files, 4 levels deep. */
    const val MAX_WALK_FILES = 2000
    const val MAX_WALK_DEPTH = 4

    fun newId(): String = java.util.UUID.randomUUID().toString()

    /** Display title for a file: the name without its extension, spaces kept
     * (Mariner titles from the picked display name, not the sanitized name). */
    fun titleForFileName(fileName: String): String = fileName.substringBeforeLast('.', fileName)

    /** Disk-safe file name: letters, digits, dot, dash, underscore, and space
     * survive; anything else becomes an underscore. Blank becomes "game". */
    fun safeFileName(displayName: String): String {
        val cleaned = displayName.map { c ->
            if (c.isLetterOrDigit() || c in "._- ") c else '_'
        }.joinToString("").trim()
        return cleaned.ifEmpty { "game" }
    }

    /** Dedupe keys, Mariner's shape: "system/fileName". */
    fun knownKeys(games: List<GameEntry>): Set<String> =
        games.map { "${it.system}/${it.fileName}" }.toSet()

    /** Match a plain file (not a zip) to a console, or null when the extension
     * is unknown, missing, or a zip (zips open in place instead). */
    fun matchFile(fileName: String, target: String?): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty() || ext == "zip") return null
        return GameConsoles.systemForExtension(ext, target)
    }

    /**
     * Match one zip entry: directory entries and nested zips are skipped, never
     * recursed into, and only the bare file name is ever used, so an entry path
     * cannot escape its console's folder.
     */
    fun matchZipEntry(entryName: String, target: String?): String? {
        if (entryName.endsWith('/')) return null
        val bare = entryName.substringAfterLast('/')
        if (bare.isEmpty()) return null
        val ext = bare.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty() || ext == "zip") return null
        return GameConsoles.systemForExtension(ext, target)
    }

    /** .cue FILE lines, bare names only. */
    fun cueFiles(text: String): List<String> {
        val quoted = Regex("\"([^\"]+)\"")
        return text.lineSequence().mapNotNull { line ->
            val t = line.trim()
            if (!t.startsWith("FILE", ignoreCase = true)) return@mapNotNull null
            quoted.find(t)?.groupValues?.get(1)
                ?: t.split(Regex("\\s+")).getOrNull(1)
        }.map { it.replace('\\', '/').substringAfterLast('/') }
            .filter { it.isNotEmpty() }.toList()
    }

    /** .m3u track lines: non-blank, non-comment, bare names only. */
    fun m3uFiles(text: String): List<String> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { it.replace('\\', '/').substringAfterLast('/') }
        .filter { it.isNotEmpty() }.toList()

    private fun escape(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

    fun toJson(games: List<GameEntry>): String = buildString {
        append('[')
        games.forEachIndexed { index, game ->
            if (index > 0) append(',')
            append("{\"id\":\"${escape(game.id)}\",")
            append("\"title\":\"${escape(game.title)}\",")
            append("\"system\":\"${escape(game.system)}\",")
            append("\"fileName\":\"${escape(game.fileName)}\",")
            append("\"addedAt\":${game.addedAt},")
            append("\"lastPlayedAt\":${game.lastPlayedAt},")
            append("\"favorite\":${game.favorite}}")
        }
        append(']')
    }

    /** Malformed input parses to an empty list, never a crash; malformed entries
     * inside a good list are skipped. */
    fun parse(json: String): List<GameEntry> {
        val top = JsonMini.parse(json) as? JsonMini.Value.Arr ?: return emptyList()
        return top.list.mapNotNull { value ->
            val obj = value as? JsonMini.Value.Obj ?: return@mapNotNull null
            val map = obj.map
            val id = (map["id"] as? JsonMini.Value.Str)?.s ?: return@mapNotNull null
            val title = (map["title"] as? JsonMini.Value.Str)?.s ?: return@mapNotNull null
            val system = (map["system"] as? JsonMini.Value.Str)?.s ?: return@mapNotNull null
            val fileName = (map["fileName"] as? JsonMini.Value.Str)?.s ?: return@mapNotNull null
            val addedAt = (map["addedAt"] as? JsonMini.Value.Num)?.raw?.toLongOrNull() ?: return@mapNotNull null
            val lastPlayedAt = (map["lastPlayedAt"] as? JsonMini.Value.Num)?.raw?.toLongOrNull() ?: 0L
            val favorite = (map["favorite"] as? JsonMini.Value.Bool)?.b ?: false
            GameEntry(id, title, system, fileName, addedAt, lastPlayedAt, favorite)
        }
    }
}
