package app.autospace.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import app.autospace.AppSettings
import java.io.File

/** Import outcome: added, refused at the gate, or failed mid-import. */
sealed interface BookImportResult {
    data class Added(val record: BookRecord, val chapters: Int) : BookImportResult
    data class Refused(val reason: String) : BookImportResult
    data class Failed(val reason: String) : BookImportResult
}

/**
 * r161: bookshelf import and storage. Import copies a picked file into
 * filesDir/books/ and records it; the user's original is never moved or
 * modified. Picker and Open-with imports allow duplicates (explicit user
 * action, Mariner-faithful); auto-shelf dedups by download file name in
 * [BookAutoShelf] before calling here.
 *
 * The shelf copy IS the file: delete removes the copy, the record, and
 * the cover, and there is no keep-file option, because there is no
 * original elsewhere to keep.
 *
 * Parsed books are cached in memory, capped at 3 entries (a parsed book
 * holds its whole text plus images, Mariner's shape); the reader
 * re-parses on open past the cache.
 */
object BookImport {

    fun booksDir(context: Context): File =
        File(context.filesDir, "books").apply { mkdirs() }

    fun load(context: Context): List<BookRecord> =
        BookLibrary.parse(AppSettings.getString(context, BookLibrary.SETTING_KEY, ""))

    fun save(context: Context, books: List<BookRecord>) {
        // Deliberately NOT truncated to MAX_BOOKS here, unlike the games
        // save: the shelf cap refuses imports, it never silently drops
        // books that are already on the shelf.
        AppSettings.putString(context, BookLibrary.SETTING_KEY, BookLibrary.toJson(books))
    }

    /** Remove a book: the library entry, the app's copy, and its cover go. */
    fun remove(context: Context, id: String) {
        val books = load(context)
        val target = books.firstOrNull { it.id == id } ?: return
        save(context, books.filter { it.id != id })
        runCatching { File(booksDir(context), target.filePath).delete() }
        target.coverPath?.let { cover ->
            runCatching { File(booksDir(context), cover).delete() }
        }
    }

    /** Save a reading position: chapter plus a 0..1 fraction within it. */
    fun updateProgress(context: Context, id: String, chapterIndex: Int, scrollFraction: Float) {
        val books = load(context)
        if (books.none { it.id == id }) return
        save(
            context,
            books.map {
                if (it.id == id) {
                    it.copy(
                        chapterIndex = chapterIndex.coerceAtLeast(0),
                        scrollFraction = scrollFraction.coerceIn(0f, 1f),
                    )
                } else {
                    it
                }
            },
        )
    }

    fun bookFile(context: Context, record: BookRecord): File =
        File(booksDir(context), record.filePath)

    fun coverFile(context: Context, record: BookRecord): File? =
        record.coverPath?.let { File(booksDir(context), it) }

    /**
     * Decode image bytes for display, downsampled so the longest side
     * is at most [maxSide] pixels (1024, Mariner's shape). Null when
     * the bytes are not a decodable image.
     */
    fun decodeImage(bytes: ByteArray, maxSide: Int = 1024): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }.getOrNull()

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    /**
     * Import one file from a content or file URI: gate, copy, parse,
     * cover, record. A parse failure deletes the copy and reports a
     * readable reason; the DRM refusal passes through verbatim, and
     * anything else reports generically rather than leaking parser
     * internals.
     */
    fun importFromUri(
        context: Context,
        uri: Uri,
        allowDuplicate: Boolean = true,
        displayNameOverride: String? = null,
    ): BookImportResult {
        val name = displayNameOverride ?: displayName(context, uri)
            ?: return BookImportResult.Failed("Could not read that file.")
        return when (BookLibrary.gateForFileName(name)) {
            BookGate.MOBI -> BookImportResult.Refused("MOBI files are not supported. EPUB books only.")
            BookGate.OTHER -> BookImportResult.Refused("Only EPUB books can be added to the bookshelf.")
            BookGate.EPUB -> importEpub(context, uri, name, allowDuplicate)
        }
    }

    private fun importEpub(
        context: Context,
        uri: Uri,
        displayName: String,
        allowDuplicate: Boolean,
    ): BookImportResult {
        val books = load(context)
        if (!BookLibrary.canAdd(books.size)) {
            return BookImportResult.Refused("Your bookshelf is full (500 books). Remove one to add another.")
        }
        if (!allowDuplicate) {
            // Picker and Open-with pass true; this branch exists for
            // callers that want same-name dedup without the auto-shelf
            // name set. The shelf file itself is millis-named, so the
            // check is on the stored title, the only name that shows.
            val title = BookLibrary.titleForFileName(displayName)
            if (books.any { it.title == title }) {
                return BookImportResult.Refused("Already on your shelf")
            }
        }
        val dir = booksDir(context)
        var dest = File(dir, "${System.currentTimeMillis()}.epub")
        var suffix = 1
        while (dest.exists()) {
            dest = File(dir, "${System.currentTimeMillis()}-$suffix.epub")
            suffix++
        }
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: error("unreadable")
        }.isSuccess
        if (!copied) {
            runCatching { dest.delete() }
            return BookImportResult.Failed("Could not read that file.")
        }
        val parsed = try {
            EpubParser.parse(dest)
        } catch (e: Exception) {
            runCatching { dest.delete() }
            return BookImportResult.Failed(readableParseReason(e))
        }
        val id = BookLibrary.newId()
        val coverName = parsed.cover?.let { bytes ->
            val coverFile = File(dir, "$id-cover.img")
            val written = runCatching { coverFile.writeBytes(bytes) }.isSuccess
            if (written) coverFile.name else null
        }
        val record = BookRecord(
            id = id,
            title = parsed.title.ifBlank { BookLibrary.titleForFileName(displayName) },
            author = parsed.author,
            filePath = dest.name,
            format = "EPUB",
            chapterIndex = 0,
            scrollFraction = 0f,
            addedAt = System.currentTimeMillis(),
            coverPath = coverName,
        )
        save(context, books + record)
        return BookImportResult.Added(record, parsed.chapters.size)
    }

    private fun readableParseReason(e: Exception): String {
        val msg = e.message.orEmpty()
        return when {
            msg == EpubParser.DRM_MESSAGE -> msg
            msg.startsWith("Not an EPUB") -> msg
            msg.startsWith("Missing OPF") -> msg
            msg.startsWith("EPUB entry too large") -> "That EPUB is too large to open here."
            msg.startsWith("EPUB expands") -> "That EPUB is too large to open here."
            msg.startsWith("EPUB had no readable") -> "That EPUB has no readable chapters."
            else -> "That book could not be opened."
        }
    }
}

/** Parsed-book cache, capped at 3 entries, most-recently-used first. */
object ParsedBookCache {
    private const val MAX_ENTRIES = 3
    private val cache = object : LinkedHashMap<String, ParsedBook>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ParsedBook>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    @Synchronized
    fun get(key: String): ParsedBook? = cache[key]

    @Synchronized
    fun put(key: String, book: ParsedBook) {
        cache[key] = book
    }

    @Synchronized
    fun size(): Int = cache.size
}
