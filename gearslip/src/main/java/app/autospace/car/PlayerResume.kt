package app.autospace.car

import android.content.Context
import app.autospace.AppSettings

/**
 * Per-video resume positions, on-device only (r22 player batch).
 *
 * Ported from Notscape's Store.playbackPositions (per video URL, saved on
 * leave, cleared on completion) with two deliberate, spec-serving changes:
 * (1) the meaningful window is 10 seconds in to 95% of duration, not
 * Notscape's flat >5s rule — a position past ~95% counts as finished and
 * is discarded, and a position under 10s never prompts; (2) a save of a
 * sub-threshold position leaves any existing entry untouched instead of
 * deleting it, so closing the player before pressing play (position 0)
 * cannot erase a pending resume candidate. Completion still clears
 * explicitly, via [clear], and a save at/over 95% removes the entry.
 *
 * Storage is one SharedPreferences string in the prefs AppSettings
 * already uses (no new permission, no new file, no cloud): one line per
 * video, `URLEncoded-url|positionMs|durationMs`, most recent last, capped
 * at 100 entries. The codec is pure (encode/decode over strings) so it is
 * unit-testable without Android; [load]/[save]/[clear] are the thin
 * SharedPreferences wrappers the player calls.
 */
object PlayerResume {
    private const val KEY = "player_resume_positions"
    private const val MAX_ENTRIES = 100

    /** Shortest position that ever prompts, in milliseconds. */
    const val MIN_PROMPT_MS = 10_000L

    /** A position at or past this fraction of duration counts as finished. */
    const val FINISHED_FRACTION = 0.95

    data class Entry(val positionMs: Long, val durationMs: Long)

    /**
     * True when reopening the video should offer Resume: at least 10s in,
     * and (when a duration is known) under 95% of it. Unknown duration
     * (<= 0, live/undetermined) prompts on the 10s rule alone.
     */
    fun isMeaningful(positionMs: Long, durationMs: Long): Boolean {
        if (positionMs < MIN_PROMPT_MS) return false
        if (durationMs <= 0L) return true
        return positionMs < durationMs * FINISHED_FRACTION
    }

    fun encode(entries: LinkedHashMap<String, Entry>): String {
        val sb = StringBuilder()
        for ((url, entry) in entries) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(java.net.URLEncoder.encode(url, "UTF-8"))
                .append('|').append(entry.positionMs)
                .append('|').append(entry.durationMs)
        }
        return sb.toString()
    }

    fun decode(raw: String): LinkedHashMap<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        for (line in raw.lines()) {
            if (line.isBlank()) continue
            val parts = line.split('|')
            if (parts.size != 3) continue
            val url = runCatching { java.net.URLDecoder.decode(parts[0], "UTF-8") }.getOrNull()
                ?: continue
            val pos = parts[1].toLongOrNull() ?: continue
            val dur = parts[2].toLongOrNull() ?: continue
            if (url.isBlank() || pos < 0L || dur < 0L) continue
            out[url] = Entry(pos, dur)
        }
        return out
    }

    fun load(context: Context): LinkedHashMap<String, Entry> =
        decode(AppSettings.getString(context, KEY, ""))

    fun loadPosition(context: Context, url: String): Entry? = load(context)[url]

    /**
     * Records the position for [url]. A meaningful position is stored; a
     * position at/over 95% of a known duration removes the entry (the
     * video counts as finished); anything under 10s leaves the stored
     * entry exactly as it was (see the class doc for why).
     */
    fun save(context: Context, url: String, positionMs: Long, durationMs: Long) {
        val entries = load(context)
        if (isMeaningful(positionMs, durationMs)) {
            entries.remove(url)
            entries[url] = Entry(positionMs, durationMs)
            while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
            AppSettings.putString(context, KEY, encode(entries))
        } else if (durationMs > 0L && positionMs >= durationMs * FINISHED_FRACTION) {
            if (entries.remove(url) != null) {
                AppSettings.putString(context, KEY, encode(entries))
            }
        }
        // Sub-threshold, non-finished: deliberately no write.
    }

    fun clear(context: Context, url: String) {
        val entries = load(context)
        if (entries.remove(url) != null) {
            AppSettings.putString(context, KEY, encode(entries))
        }
    }
}
