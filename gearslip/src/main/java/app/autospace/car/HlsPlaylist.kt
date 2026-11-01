package app.autospace.car

import java.net.URI

/**
 * Pure HLS playlist parsing (r159, downloads slice 3). No Android
 * framework here, so a plain JVM test pins every rule. The worker
 * ([HlsDownloadWorker]) owns fetching, segment downloads, joining,
 * and MediaStore publishing; this file only answers questions about
 * playlist text.
 *
 * Variant selection is best-bandwidth-always: this port has no quality
 * picker, by decision. A variant with no BANDWIDTH attribute counts as
 * bandwidth zero, so a tagged variant always beats an untagged one;
 * ties resolve to the first listed variant (maxByOrNull's rule), which
 * keeps selection deterministic for an unchanged playlist.
 *
 * A media playlist is fMP4 when it carries an EXT-X-MAP init segment
 * or any .m4s segment; otherwise its segments are MPEG-TS. An
 * EXT-X-KEY line means encryption (METHOD=NONE excepted) — joined
 * ciphertext would be a broken file, so the worker refuses encrypted
 * playlists outright rather than shipping one. A playlist without
 * EXT-X-ENDLIST is a live stream, not a finished video; the worker
 * refuses it too, for the same reason — a sliding-window clip labeled
 * as the video would be a broken promise.
 */
object HlsPlaylist {

    /** One parsed media playlist: segment URIs in play order, plus its flags. */
    data class MediaPlaylist(
        val segments: List<String>,
        val initSegmentUri: String?,
        val encrypted: Boolean,
        val hasEndList: Boolean,
    ) {
        /** True when the segments are fMP4 rather than MPEG-TS. */
        val isFmp4: Boolean
            get() = initSegmentUri != null ||
                segments.any { it.substringBefore('?').substringBefore('#').lowercase().endsWith(".m4s") }
    }

    private val bandwidthPattern = Regex("""BANDWIDTH=(\d+)""")
    private val mapUriPattern = Regex("""URI="([^"]+)"""")

    /**
     * The best-bandwidth variant URI from a master playlist, or null
     * when the text carries no STREAM-INF entry — in which case the
     * text is itself a media playlist and the caller uses its own URL.
     * A STREAM-INF entry with no following URI line is skipped.
     */
    fun selectVariant(masterText: String): String? {
        val lines = masterText.lines()
        var bestUri: String? = null
        var bestBandwidth = -1L
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bandwidth = bandwidthPattern.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                // The URI is the next non-blank line, and only that
                // line: a following tag line means this entry has no
                // URI and is skipped, not donated the next entry's.
                var j = i + 1
                var uri: String? = null
                while (j < lines.size) {
                    val candidate = lines[j].trim()
                    if (candidate.isEmpty()) {
                        j++
                        continue
                    }
                    if (!candidate.startsWith("#")) uri = candidate
                    break
                }
                if (uri != null && bandwidth > bestBandwidth) {
                    bestBandwidth = bandwidth
                    bestUri = uri
                }
                i = j
            } else {
                i++
            }
        }
        return bestUri
    }

    /**
     * Parses a media playlist. Segment URIs are the non-comment,
     * non-blank lines in order. EXT-X-MAP contributes the init
     * segment URI; any EXT-X-KEY with a method other than NONE marks
     * the playlist encrypted; EXT-X-ENDLIST marks it finished.
     */
    fun parseMediaPlaylist(text: String): MediaPlaylist {
        val segments = mutableListOf<String>()
        var initSegmentUri: String? = null
        var encrypted = false
        var hasEndList = false
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit
                line == "#EXT-X-ENDLIST" -> hasEndList = true
                line.startsWith("#EXT-X-MAP:") -> {
                    initSegmentUri = mapUriPattern.find(line)?.groupValues?.get(1)
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    if (!line.contains("METHOD=NONE")) encrypted = true
                }
                line.startsWith("#") -> Unit
                else -> segments.add(line)
            }
        }
        return MediaPlaylist(segments, initSegmentUri, encrypted, hasEndList)
    }

    /**
     * Resolves a playlist-relative URI against its playlist URL:
     * relative paths stay beside the playlist, absolute paths stay on
     * its host, absolute URLs pass through. Falls back to the
     * relative URI itself when either side is malformed.
     */
    fun resolveUrl(baseUrl: String, relative: String): String =
        runCatching { URI(baseUrl).resolve(relative).toString() }.getOrDefault(relative)
}
