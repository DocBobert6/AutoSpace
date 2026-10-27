package app.autospace.car

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import app.autospace.AppSettings
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Import counts, Mariner's shape: imported games and skipped files. */
data class ImportResult(val imported: Int, val skipped: Int)

/**
 * r89: ROM import for the Games library. Import copies files into
 * filesDir/roms/<system>/ and records them in the library; the user's original
 * files are never moved or modified, and a file already in the library
 * (same system + file name) is skipped, never duplicated. A folder import
 * walks the picked folder breadth-first, Mariner's shape: 2000 files, 4 levels
 * deep, zips opened in place, disc track lists pulling their sibling files
 * beside them. One deliberate correction to Mariner: disc siblings copy into
 * their track list's own console folder (target-aware), not always the
 * PlayStation folder.
 *
 * No playback lives here: a game with no core round yet simply sits in the
 * library until one arrives.
 */
object GameImport {

    fun romsDir(context: Context, system: String): File =
        File(context.filesDir, "roms/$system").apply { mkdirs() }

    fun load(context: Context): List<GameEntry> =
        GameLibrary.parse(AppSettings.getString(context, GameLibrary.SETTING_KEY, ""))

    fun save(context: Context, games: List<GameEntry>) {
        AppSettings.putString(
            context,
            GameLibrary.SETTING_KEY,
            GameLibrary.toJson(games.takeLast(GameLibrary.MAX_GAMES)),
        )
    }

    /** Remove a game: the library entry and the app's copy go; the user's
     * original file was never here, so it is untouched. */
    fun remove(context: Context, id: String) {
        val games = load(context)
        val target = games.firstOrNull { it.id == id } ?: return
        save(context, games.filter { it.id != id })
        runCatching { File(romsDir(context, target.system), target.fileName).delete() }
    }

    fun setFavorite(context: Context, id: String, favorite: Boolean) {
        save(context, load(context).map { if (it.id == id) it.copy(favorite = favorite) else it })
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    /** Copy a disc sibling file beside its track list: a file only, never a
     * library entry — .bin and .wav files match no console on their own. */
    private fun copyFileOnly(
        context: Context,
        system: String,
        fileName: String,
        openStream: () -> InputStream?,
    ) {
        val safe = GameLibrary.safeFileName(fileName)
        val dest = File(romsDir(context, system), safe)
        if (dest.exists()) return
        runCatching {
            openStream()?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    /** Copy a stream into a console folder under a safe name, unless already known. */
    private fun storeStream(
        context: Context,
        system: String,
        fileName: String,
        known: MutableSet<String>,
        added: MutableList<GameEntry>,
        openStream: () -> InputStream?,
    ): Boolean {
        val safe = GameLibrary.safeFileName(fileName)
        val key = "$system/$safe"
        if (key in known) return false
        val ok = runCatching {
            openStream()?.use { input ->
                File(romsDir(context, system), safe).outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: error("unreadable")
        }.isSuccess
        if (!ok) return false
        known += key
        added += GameEntry(
            GameLibrary.newId(),
            GameLibrary.titleForFileName(safe),
            system,
            safe,
            System.currentTimeMillis(),
        )
        return true
    }

    /** One zip, opened in place: every matching ROM inside imports under the
     * existing duplicate rules; entries over the per-entry cap are skipped,
     * and the total cap stops the zip. Returns (imported, skipped). */
    private fun importZipStream(
        context: Context,
        input: InputStream,
        target: String?,
        known: MutableSet<String>,
        added: MutableList<GameEntry>,
    ): Pair<Int, Int> {
        var imported = 0
        var skipped = 0
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = runCatching { zip.nextEntry }.getOrNull() ?: break
                val system = GameLibrary.matchZipEntry(entry.name, target)
                if (system == null) {
                    skipped++
                    continue
                }
                val bare = entry.name.substringAfterLast('/')
                val safe = GameLibrary.safeFileName(bare)
                val key = "$system/$safe"
                if (key in known) {
                    skipped++
                    continue
                }
                val dest = File(romsDir(context, system), safe)
                var bytes = 0L
                var overCap = false
                val ok = runCatching {
                    dest.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            bytes += read
                            total += read
                            if (bytes > GameLibrary.MAX_ENTRY_BYTES || total > GameLibrary.MAX_TOTAL_BYTES) {
                                overCap = true
                                break
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }.isSuccess && !overCap
                if (!ok) {
                    runCatching { dest.delete() }
                    skipped++
                    if (total > GameLibrary.MAX_TOTAL_BYTES) break
                    continue
                }
                known += key
                added += GameEntry(
                    GameLibrary.newId(),
                    GameLibrary.titleForFileName(safe),
                    system,
                    safe,
                    System.currentTimeMillis(),
                )
                imported++
            }
        }
        return imported to skipped
    }

    /** One picked document: a zip opens in place, anything else imports as a
     * ROM for the target console (a disc extension on a disc console's page
     * belongs to that console). */
    fun importSingle(context: Context, uri: Uri, target: String): ImportResult {
        val name = displayName(context, uri) ?: return ImportResult(0, 1)
        val known = GameLibrary.knownKeys(load(context)).toMutableSet()
        val added = mutableListOf<GameEntry>()
        var imported = 0
        var skipped = 0
        if (name.endsWith(".zip", ignoreCase = true)) {
            val (imp, skip) = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    importZipStream(context, input, target, known, added)
                } ?: (0 to 1)
            }.getOrDefault(0 to 1)
            imported += imp
            skipped += skip
        } else {
            val system = GameLibrary.matchFile(name, target)
            if (system == null) {
                skipped++
            } else if (storeStream(context, system, name, known, added) {
                context.contentResolver.openInputStream(uri)
            }) {
                imported++
            } else {
                skipped++
            }
        }
        if (added.isNotEmpty()) save(context, load(context) + added)
        return ImportResult(imported, skipped)
    }

    private data class ChildDoc(val id: String, val name: String, val isDir: Boolean)

    private fun queryChildren(context: Context, childrenUri: Uri): List<ChildDoc> = runCatching {
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val out = mutableListOf<ChildDoc>()
            while (cursor.moveToNext()) {
                out += ChildDoc(
                    cursor.getString(idIndex),
                    cursor.getString(nameIndex) ?: continue,
                    cursor.getString(mimeIndex) == DocumentsContract.Document.MIME_TYPE_DIR,
                )
            }
            out
        } ?: emptyList()
    }.getOrDefault(emptyList())

    /**
     * Walk a picked folder, Mariner's shape. Files sort by extension into
     * consoles (a disc extension with no target is PlayStation); zips open in
     * place; disc track lists pull their referenced siblings from the same
     * directory into the track list's console folder. Duplicates skip.
     */
    fun importFolder(context: Context, treeUri: Uri, target: String?): ImportResult {
        val known = GameLibrary.knownKeys(load(context)).toMutableSet()
        val added = mutableListOf<GameEntry>()
        var imported = 0
        var skipped = 0
        var scanned = 0
        val stack = ArrayDeque<Pair<String, Int>>()
        stack.add(DocumentsContract.getTreeDocumentId(treeUri) to 0)
        while (stack.isNotEmpty() && scanned < GameLibrary.MAX_WALK_FILES) {
            val (dirId, depth) = stack.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId)
            val children = queryChildren(context, childrenUri)
            // Disc siblings first: a .cue or .m3u names its disc files, which
            // match no system on their own and would otherwise be skipped.
            val byLower = children.associateBy { it.name.lowercase() }
            for (child in children) {
                if (child.isDir) continue
                val isCue = child.name.endsWith(".cue", ignoreCase = true)
                val isM3u = child.name.endsWith(".m3u", ignoreCase = true)
                if (!isCue && !isM3u) continue
                val system = GameConsoles.systemForExtension(
                    child.name.substringAfterLast('.', ""),
                    target,
                ) ?: continue
                val text = runCatching {
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.id)
                    context.contentResolver.openInputStream(docUri)?.use { it.readBytes().decodeToString() }
                }.getOrNull() ?: continue
                val refs = if (isCue) GameLibrary.cueFiles(text) else GameLibrary.m3uFiles(text)
                for (ref in refs) {
                    val sibling = byLower[ref.lowercase()] ?: continue
                    if (sibling.isDir) continue
                    val siblingUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, sibling.id)
                    copyFileOnly(context, system, sibling.name) {
                        context.contentResolver.openInputStream(siblingUri)
                    }
                }
            }
            for (child in children) {
                if (child.isDir) {
                    if (depth < GameLibrary.MAX_WALK_DEPTH) stack.add(child.id to depth + 1)
                    continue
                }
                scanned++
                if (child.name.endsWith(".zip", ignoreCase = true)) {
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.id)
                    val (imp, skip) = runCatching {
                        context.contentResolver.openInputStream(docUri)?.use { input ->
                            importZipStream(context, input, target, known, added)
                        } ?: (0 to 1)
                    }.getOrDefault(0 to 1)
                    imported += imp
                    skipped += skip
                    continue
                }
                val system = GameLibrary.matchFile(child.name, target)
                if (system == null) {
                    skipped++
                    continue
                }
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.id)
                if (storeStream(context, system, child.name, known, added) {
                    context.contentResolver.openInputStream(docUri)
                }) {
                    imported++
                } else {
                    skipped++
                }
            }
        }
        if (added.isNotEmpty()) save(context, load(context) + added)
        return ImportResult(imported, skipped)
    }
}
