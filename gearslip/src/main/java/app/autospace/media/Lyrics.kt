package app.autospace.media

import android.content.Context
import app.autospace.GearslipLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

class LyricLine(val timeMs: Long, val text: String)

/** [synced] means every line carries a time, so the current one can be followed along. */
class LyricsResult(val lines: List<LyricLine>, val synced: Boolean)

/**
 * Looks lyrics up on LRCLIB (lrclib.net), a free community database that needs no key.
 *
 * Media apps almost never publish lyrics in their session metadata, so this is the only general
 * source. It sends the track's title, artist, album and length to lrclib.net, which is why the
 * lookup happens only when the driver opens the lyrics view and never in the background.
 *
 * Results are cached twice over: in memory for the life of the process, and on disk under the
 * cache directory so a song looked up on a past drive doesn't need the network again on this one.
 * A miss (no lyrics found) is cached in memory only - not on disk - so a transient lookup failure
 * doesn't harden into a permanent "no lyrics" the next time the same track plays.
 */
object Lyrics {

    private val cache = HashMap<String, LyricsResult?>()

    suspend fun find(context: Context, title: String, artist: String, album: String, durationMs: Long): LyricsResult? {
        if (title.isBlank()) return null
        val key = "$title|$artist|$album|$durationMs"
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }

        val fromDisk = withContext(Dispatchers.IO) { readDisk(context, key) }
        if (fromDisk != null) {
            synchronized(cache) { cache[key] = fromDisk }
            return fromDisk
        }

        val result = withContext(Dispatchers.IO) {
            runCatching {
                exact(title, artist, album, durationMs) ?: search(title, artist)
            }.onFailure { GearslipLog.w("lyrics: lookup failed: ${it.message}") }.getOrNull()
        }
        synchronized(cache) { cache[key] = result }
        if (result != null) withContext(Dispatchers.IO) { writeDisk(context, key, result) }
        return result
    }

    private fun cacheFile(context: Context, key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(context.cacheDir, "lyrics").apply { mkdirs() }.resolve("$name.json")
    }

    private fun readDisk(context: Context, key: String): LyricsResult? = runCatching {
        val file = cacheFile(context, key)
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        val lines = json.getJSONArray("lines").let { arr ->
            (0 until arr.length()).map { i ->
                val line = arr.getJSONObject(i)
                LyricLine(line.getLong("t"), line.getString("x"))
            }
        }
        LyricsResult(lines, json.getBoolean("synced"))
    }.getOrNull()

    private fun writeDisk(context: Context, key: String, result: LyricsResult) {
        runCatching {
            val lines = JSONArray()
            result.lines.forEach { line ->
                lines.put(JSONObject().put("t", line.timeMs).put("x", line.text))
            }
            val json = JSONObject().put("synced", result.synced).put("lines", lines)
            cacheFile(context, key).writeText(json.toString())
        }.onFailure { GearslipLog.w("lyrics: could not write disk cache: ${it.message}") }
    }

    private fun exact(title: String, artist: String, album: String, durationMs: Long): LyricsResult? {
        if (artist.isBlank()) return null
        val query = buildString {
            append("track_name=").append(enc(title)).append("&artist_name=").append(enc(artist))
            if (album.isNotBlank()) append("&album_name=").append(enc(album))
            if (durationMs > 0) append("&duration=").append(durationMs / 1000)
        }
        return get("https://lrclib.net/api/get?$query")?.let { parse(JSONObject(it)) }
    }

    private fun search(title: String, artist: String): LyricsResult? {
        val body = get("https://lrclib.net/api/search?q=" + enc("$artist $title".trim())) ?: return null
        val hits = JSONArray(body)
        for (i in 0 until hits.length()) parse(hits.getJSONObject(i))?.let { return it }
        return null
    }

    private fun parse(json: JSONObject): LyricsResult? {
        val synced = json.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        if (synced != null) {
            val lines = parseLrc(synced)
            if (lines.isNotEmpty()) return LyricsResult(lines, synced = true)
        }
        val plain = json.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" } ?: return null
        return LyricsResult(plain.lines().map { LyricLine(0, it) }, synced = false)
    }

    /** `[01:23.45] words` - a line may carry several stamps when a chorus repeats. */
    private fun parseLrc(text: String): List<LyricLine> {
        val stamp = Regex("""\[(\d+):(\d+(?:\.\d+)?)]""")
        return text.lines().flatMap { raw ->
            val stamps = stamp.findAll(raw).toList()
            val words = raw.replace(stamp, "").trim()
            stamps.map {
                val ms = ((it.groupValues[1].toLong() * 60 + it.groupValues[2].toDouble()) * 1000).toLong()
                LyricLine(ms, words)
            }
        }.sortedBy { it.timeMs }
    }

    private fun get(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", "Gearslip (car media host)")
        }
        return try {
            if (connection.responseCode != 200) null else connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}
