package app.autospace.car

/**
 * r161: the bookshelf record and its JSON codec, following the games
 * library pattern exactly: a JSON list in a single settings key, no
 * database. Book files are copies under filesDir/books/; removing a
 * book deletes only the app's copy, its record, and its cover.
 *
 * JSON goes through [JsonMini] (pure Kotlin): org.json is an
 * Android-framework stub on the JVM unit-test classpath, so it cannot
 * be used here.
 */
data class BookRecord(
    val id: String,
    val title: String,
    val author: String,
    val filePath: String,
    val format: String,
    val chapterIndex: Int,
    val scrollFraction: Float,
    val addedAt: Long,
    val coverPath: String?,
)

/** The import gate: EPUB only. Everything else is refused, never parsed. */
enum class BookGate { EPUB, MOBI, OTHER }

object BookLibrary {

    const val SETTING_KEY = "car_books_library"
    const val MAX_BOOKS = 500

    /** Download auto-shelf switch, ON by default (string pattern, as in BrowserStore). */
    const val KEY_AUTO_SHELF = "car_epub_auto_shelf"

    /** Download file names already auto-shelved, for the by-name dedup. */
    const val KEY_AUTO_SHELF_NAMES = "car_books_autoshelf_names"

    fun newId(): String = java.util.UUID.randomUUID().toString()

    /** Display title for a file: the name without its extension, spaces kept. */
    fun titleForFileName(fileName: String): String = fileName.substringBeforeLast('.', fileName)

    /** Disk-safe file name, the games rule: letters, digits, dot, dash, underscore, space. */
    fun safeFileName(displayName: String): String {
        val cleaned = displayName.map { c ->
            if (c.isLetterOrDigit() || c in "._- ") c else '_'
        }.joinToString("").trim()
        return cleaned.ifEmpty { "book" }
    }

    /**
     * The EPUB-only gate, applied at all three import entries before any
     * copy or parse. MOBI and its Kindle siblings are refused as a
     * class; anything else (TXT, PDF, anything binary) is refused as
     * non-EPUB, never parsed as text.
     */
    fun gateForFileName(fileName: String): BookGate {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "epub" -> BookGate.EPUB
            "mobi", "azw", "azw3", "prc" -> BookGate.MOBI
            else -> BookGate.OTHER
        }
    }

    /** Shelf-cap rule: at the cap an import refuses with a clear note, never silently. */
    fun canAdd(currentCount: Int): Boolean = currentCount < MAX_BOOKS

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

    fun toJson(books: List<BookRecord>): String = buildString {
        append('[')
        books.forEachIndexed { index, book ->
            if (index > 0) append(',')
            append("{\"id\":\"${escape(book.id)}\",")
            append("\"title\":\"${escape(book.title)}\",")
            append("\"author\":\"${escape(book.author)}\",")
            append("\"filePath\":\"${escape(book.filePath)}\",")
            append("\"format\":\"${escape(book.format)}\",")
            append("\"chapterIndex\":${book.chapterIndex},")
            append("\"scrollFraction\":${book.scrollFraction},")
            append("\"addedAt\":${book.addedAt},")
            append("\"coverPath\":\"${escape(book.coverPath ?: "")}\"}")
        }
        append(']')
    }

    /** Malformed input parses to an empty list, never a crash; malformed entries are skipped. */
    fun parse(json: String): List<BookRecord> {
        val top = JsonMini.parse(json) as? JsonMini.Value.Arr ?: return emptyList()
        return top.list.mapNotNull { value ->
            val obj = value as? JsonMini.Value.Obj ?: return@mapNotNull null
            val map = obj.map
            val id = (map["id"] as? JsonMini.Value.Str)?.s ?: return@mapNotNull null
            val title = (map["title"] as? JsonMini.Value.Str)?.s ?: return@mapNotNull null
            val author = (map["author"] as? JsonMini.Value.Str)?.s ?: return@mapNotNull null
            val filePath = (map["filePath"] as? JsonMini.Value.Str)?.s ?: return@mapNotNull null
            val format = (map["format"] as? JsonMini.Value.Str)?.s ?: return@mapNotNull null
            val chapterIndex = (map["chapterIndex"] as? JsonMini.Value.Num)?.raw?.toIntOrNull() ?: return@mapNotNull null
            val scrollFraction = (map["scrollFraction"] as? JsonMini.Value.Num)?.raw?.toFloatOrNull() ?: 0f
            val addedAt = (map["addedAt"] as? JsonMini.Value.Num)?.raw?.toLongOrNull() ?: return@mapNotNull null
            val coverPath = (map["coverPath"] as? JsonMini.Value.Str)?.s?.takeIf { it.isNotBlank() }
            BookRecord(id, title, author, filePath, format, chapterIndex, scrollFraction.coerceIn(0f, 1f), addedAt, coverPath)
        }
    }

    /** Auto-shelf name set codec: a JSON array of strings, malformed input reads empty. */
    fun encodeNames(names: List<String>): String = buildString {
        append('[')
        names.forEachIndexed { index, name ->
            if (index > 0) append(',')
            append("\"${escape(name)}\"")
        }
        append(']')
    }

    fun decodeNames(json: String): List<String> {
        val top = JsonMini.parse(json) as? JsonMini.Value.Arr ?: return emptyList()
        return top.list.mapNotNull { (it as? JsonMini.Value.Str)?.s }
    }

    // ---- Reader position helpers (pure; the reader screen maps a
    // LazyColumn position to a chapter plus a within-chapter fraction,
    // and back, so progress survives as chapterIndex + scrollFraction.
    // The fraction is an item-count approximation, not a pixel offset:
    // it is stable across type sizes, which a pixel offset is not. ----

    /**
     * Chapter and within-chapter fraction for the visible item:
     * the chapter that item belongs to, and how far through that
     * chapter's items it sits (0 at the chapter head, approaching 1
     * at its tail).
     */
    fun progressForVisibleItem(itemChapters: List<Int>, visibleIndex: Int): Pair<Int, Float> {
        if (itemChapters.isEmpty()) return 0 to 0f
        val clamped = visibleIndex.coerceIn(0, itemChapters.lastIndex)
        val chapter = itemChapters[clamped]
        val first = itemChapters.indexOfFirst { it == chapter }
        val last = itemChapters.indexOfLast { it == chapter }
        val fraction = if (last > first) {
            (clamped - first).toFloat() / (last - first).toFloat()
        } else {
            0f
        }
        return chapter to fraction.coerceIn(0f, 1f)
    }

    /** Item index to scroll to for a saved chapter plus fraction. */
    fun itemIndexForProgress(itemChapters: List<Int>, chapter: Int, fraction: Float): Int {
        if (itemChapters.isEmpty()) return 0
        val first = itemChapters.indexOfFirst { it == chapter }.takeIf { it >= 0 } ?: 0
        val last = itemChapters.indexOfLast { it == chapter }.takeIf { it >= 0 } ?: first
        val clampedFraction = fraction.coerceIn(0f, 1f)
        return (first + ((last - first) * clampedFraction).toInt()).coerceIn(0, itemChapters.lastIndex)
    }
}
