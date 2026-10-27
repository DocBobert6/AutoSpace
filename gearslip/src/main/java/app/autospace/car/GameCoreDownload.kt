package app.autospace.car

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * r92: on-demand core downloads with size and SHA-256 verification,
 * ported from Mariner's CoreDownload for the car screen.
 *
 * A download lands in filesDir/cores-downloaded with a sidecar
 * "<file>.sha256" holding the manifest hash. Status is DOWNLOADED
 * only when the file exists, the sidecar exists, and the file's hash
 * matches the sidecar; a missing sidecar or a mismatch is CORRUPT,
 * and downloadedPath() deletes a corrupt pair so the next attempt
 * starts clean. A core is installed only after its size and hash
 * match the manifest entry, so a bad download is discarded, never
 * kept.
 *
 * Manifest parsing uses JsonMini, not org.json: the framework stub
 * on the JVM unit-test classpath cannot parse.
 */
object GameCoreDownload {
    enum class Status { MISSING, DOWNLOADED, CORRUPT }

    fun downloadedDir(context: Context): File =
        File(context.filesDir, "cores-downloaded").apply { mkdirs() }

    private fun downloadedFile(context: Context, fileName: String): File =
        File(downloadedDir(context), fileName)

    private fun sidecarFile(context: Context, fileName: String): File =
        File(downloadedDir(context), "$fileName.sha256")

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    fun sha256Hex(file: File): String =
        file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

    /** Parse a manifest document into entries, skipping malformed
     * rows rather than failing the whole fetch. JsonMini returns
     * its own Value tree, not raw maps, so each node is unwrapped
     * through Obj/Arr/Str/Num. */
    fun parseManifest(json: String): List<GameCoreEntry> {
        val root = JsonMini.parse(json) as? JsonMini.Value.Obj
            ?: return emptyList()
        val cores = root.map["cores"] as? JsonMini.Value.Arr
            ?: return emptyList()
        val entries = ArrayList<GameCoreEntry>()
        for (row in cores.list) {
            val map = (row as? JsonMini.Value.Obj)?.map ?: continue
            val key = (map["key"] as? JsonMini.Value.Str)?.s ?: continue
            val file = (map["file"] as? JsonMini.Value.Str)?.s ?: continue
            val size = (map["size"] as? JsonMini.Value.Num)?.raw?.toLongOrNull()
                ?: continue
            val sha = (map["sha256"] as? JsonMini.Value.Str)?.s ?: continue
            val consoles = (map["consoles"] as? JsonMini.Value.Arr)?.list
                ?.mapNotNull { (it as? JsonMini.Value.Str)?.s }
                ?: emptyList()
            entries.add(GameCoreEntry(key, file, size, sha, consoles))
        }
        return entries
    }

    /** Fetch the published manifest, with the pinned entries added
     * when the fetched document lacks any of them. */
    suspend fun fetchManifest(): Result<List<GameCoreEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val connection =
                    URL(GameCores.MANIFEST_URL).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 15000
                    connection.readTimeout = 30000
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        throw IllegalStateException(
                            "Core list failed: HTTP ${connection.responseCode}"
                        )
                    }
                    val body = connection.inputStream.bufferedReader().readText()
                    GameCores.manifestWithPinnedEntries(parseManifest(body))
                } finally {
                    connection.disconnect()
                }
            }
        }

    fun status(context: Context, system: String): Status {
        val fileName = GameCores.coreFileNameFor(system) ?: return Status.MISSING
        val file = downloadedFile(context, fileName)
        if (!file.exists()) return Status.MISSING
        val sidecar = sidecarFile(context, fileName)
        if (!sidecar.exists()) return Status.CORRUPT
        val expected = runCatching { sidecar.readText().trim() }.getOrNull()
            ?: return Status.CORRUPT
        return if (runCatching { sha256Hex(file) }.getOrNull() == expected) {
            Status.DOWNLOADED
        } else {
            Status.CORRUPT
        }
    }

    fun isDownloaded(context: Context, system: String): Boolean =
        status(context, system) == Status.DOWNLOADED

    /** The installed path when the core is verified, or null. A
     * corrupt pair is deleted on the way out. */
    fun downloadedPath(context: Context, system: String): String? {
        val fileName = GameCores.coreFileNameFor(system) ?: return null
        return when (status(context, system)) {
            Status.DOWNLOADED -> downloadedFile(context, fileName).absolutePath
            Status.CORRUPT -> {
                downloadedFile(context, fileName).delete()
                sidecarFile(context, fileName).delete()
                null
            }
            Status.MISSING -> null
        }
    }

    fun removeDownloaded(context: Context, system: String) {
        val fileName = GameCores.coreFileNameFor(system) ?: return
        downloadedFile(context, fileName).delete()
        sidecarFile(context, fileName).delete()
    }

    fun installedSha256(context: Context, system: String): String? {
        val fileName = GameCores.coreFileNameFor(system) ?: return null
        val sidecar = sidecarFile(context, fileName)
        if (!sidecar.exists()) return null
        return runCatching { sidecar.readText().trim() }.getOrNull()
    }

    /** True when an installed core's hash differs from the manifest's
     * (Mariner's isUpdateAvailable shape). */
    fun isUpdateAvailable(
        installedSha256: String?,
        manifestSha256: String
    ): Boolean =
        installedSha256 != null && !installedSha256.equals(manifestSha256, true)

    /**
     * Download one core, verifying size and SHA-256 before installing.
     * Progress is 0..100. A cancelled download deletes its temp file
     * and rethrows, so a later attempt starts clean.
     */
    suspend fun download(
        context: Context,
        entry: GameCoreEntry,
        onProgress: (Int) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = downloadedDir(context)
            val tmp = File(dir, entry.file + ".tmp")
            val target = File(dir, entry.file)
            try {
                val connection =
                    URL(GameCores.urlFor(entry)).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 15000
                    connection.readTimeout = 30000
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        throw IllegalStateException(
                            "Download failed: HTTP ${connection.responseCode}"
                        )
                    }
                    val total = connection.contentLengthLong
                    var received = 0L
                    var lastProgress = -1
                    connection.inputStream.use { input ->
                        tmp.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                received += read
                                if (total > 0) {
                                    val progress = (received * 100 / total).toInt()
                                    if (progress != lastProgress) {
                                        lastProgress = progress
                                        onProgress(progress)
                                    }
                                }
                            }
                        }
                    }
                    if (tmp.length() != entry.size) {
                        throw IllegalStateException(
                            "Download size mismatch: got ${tmp.length()}, " +
                                "expected ${entry.size}"
                        )
                    }
                    if (sha256Hex(tmp) != entry.sha256) {
                        throw IllegalStateException(
                            "Download failed verification."
                        )
                    }
                    if (target.exists()) target.delete()
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    sidecarFile(context, entry.file).writeText(entry.sha256)
                    onProgress(100)
                } finally {
                    connection.disconnect()
                }
            } catch (e: CancellationException) {
                tmp.delete()
                throw e
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
        }
    }
}
