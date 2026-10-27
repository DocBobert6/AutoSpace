package app.autospace.car

import android.content.Context
import app.autospace.AppSettings

/**
 * Gboard-look keyboard suggestions (r30): prefix completion over the field's
 * current word, plus whole-field copy/paste rules and the strip's dismiss
 * placement rule. Everything here is pure except the small prefs/asset
 * helpers at the bottom, so the decision logic is JVM-testable with no
 * Android context (see KeyboardSuggestionsTest).
 *
 * Sources, in order: (1) learned words the user actually typed on this
 * keyboard (on-device prefs only, never logged, cleared from keyboard
 * settings); (2) a bundled English frequency wordlist
 * (assets/keyboard_words.txt: first20hours/google-10000-english,
 * USA no-swears variant, frequency order, 9,884 words, 75,071 bytes).
 *
 * Deliberately NOT here: autocorrect/auto-replace (tap-to-accept only),
 * next-word prediction (needs a model), contact-name suggestions (privacy),
 * clipboard history (current clipboard item only).
 *
 * r82: learned words carry a use count (see [LearnedEntry]) and rank by
 * frequency, then recency; the strip also offers distance-1 typo
 * corrections as tap-to-accept candidates, never as auto-replace.
 */
object KeyboardSuggestions {

    const val MAX_CANDIDATES = 3
    const val MIN_WORD_LENGTH = 2
    const val MAX_LEARNED_WORDS = 500

    /** Typed words shorter than this get prefix matches only, never near-misses. */
    const val NEAR_MISS_MIN_LENGTH = 3

    private val BOUNDARY_CHARS = setOf(' ', '.', ',', '!', '?', ';', ':')

    /** The word being typed: the trailing run of letters plus internal apostrophes. */
    fun currentWord(text: String): String {
        var end = text.length
        while (end > 0 && (text[end - 1].isLetter() || text[end - 1] == '\'')) end--
        return text.substring(end).trim('\'')
    }

    /** Start index of [currentWord] inside [text], or text.length when there is none. */
    private fun currentWordStart(text: String): Int {
        var start = text.length
        while (start > 0 && (text[start - 1].isLetter() || text[start - 1] == '\'')) start--
        // A leading apostrophe belongs to the quote, not the word ("say 'hi" -> "hi").
        while (start < text.length && text[start] == '\'') start++
        return start
    }

    /** Replace only the current word, preserving all preceding text verbatim. */
    fun replaceCurrentWord(text: String, candidate: String): String {
        val word = currentWord(text)
        if (word.isEmpty()) return text
        val start = currentWordStart(text)
        return text.substring(0, start) + matchCase(word, candidate)
    }

    /** Case-match a candidate to what the user typed: ALL-CAPS stays caps, initial cap stays capped. */
    fun matchCase(typed: String, candidate: String): String {
        // r50: a glide candidate can be offered with an empty current word
        // (traced after a space); first() on "" would throw, so empty passes through.
        if (typed.isEmpty()) return candidate
        if (typed.length > 1 && typed.all { it.isUpperCase() }) return candidate.uppercase()
        if (typed.first().isUpperCase()) return candidate.replaceFirstChar { it.uppercaseChar() }
        return candidate
    }

    /**
     * Up to [MAX_CANDIDATES] completions for [word], in tier order:
     * exact-prefix learned words (frequency, then recency), exact-prefix
     * wordlist words (frequency order), then — only when [word] is at
     * least [NEAR_MISS_MIN_LENGTH] letters — distance-1 typo corrections
     * (learned first, then wordlist). Only words LONGER than the typed
     * prefix qualify for the prefix tiers — suggesting the word already
     * typed is noise. A near-miss correction MAY be shorter than the
     * typed word (an extra typed letter). Case-insensitive; learned
     * words are stored lowercase. Every tier is tap-to-accept only:
     * nothing here ever replaces text unasked (autocorrect stays off).
     */
    fun candidates(word: String, learned: List<LearnedEntry>, wordlist: List<String>): List<String> {
        if (word.length < MIN_WORD_LENGTH) return emptyList()
        val lower = word.lowercase()
        val out = ArrayList<String>(MAX_CANDIDATES)
        fun offer(w: String) {
            if (out.size < MAX_CANDIDATES && w !in out) out.add(w)
        }
        for (w in rankedLearnedWords(learned)) {
            if (out.size >= MAX_CANDIDATES) break
            if (w.length > word.length && w.startsWith(lower)) offer(w)
        }
        for (w in wordlist) {
            if (out.size >= MAX_CANDIDATES) break
            if (w.length > word.length && w.startsWith(lower)) offer(w)
        }
        if (word.length >= NEAR_MISS_MIN_LENGTH) {
            for (w in rankedLearnedWords(learned)) {
                if (out.size >= MAX_CANDIDATES) break
                if (editDistanceOne(lower, w)) offer(w)
            }
            for (w in wordlist) {
                if (out.size >= MAX_CANDIDATES) break
                if (editDistanceOne(lower, w)) offer(w)
            }
        }
        return out
    }

    /**
     * True when [a] and [b] differ by exactly one edit: one insertion,
     * deletion, substitution, or adjacent transposition, at any
     * position. Inputs are lowercased here, so callers need not be.
     * Exact equality is false (not a miss); anything beyond one edit
     * is false. The length pre-check plus early exits keep the
     * per-keystroke scan of the wordlist cheap.
     */
    fun editDistanceOne(a: String, b: String): Boolean {
        val x = a.lowercase()
        val y = b.lowercase()
        if (x == y) return false
        val n = x.length
        val m = y.length
        if (kotlin.math.abs(n - m) > 1) return false
        if (n == m) {
            var diffs = 0
            var i = 0
            while (i < n) {
                if (x[i] != y[i]) {
                    diffs++
                    if (diffs == 1 && i + 1 < n && x[i] == y[i + 1] && x[i + 1] == y[i]) {
                        i += 2
                        continue
                    }
                    if (diffs > 1) return false
                }
                i++
            }
            return diffs == 1
        }
        val short = if (n < m) x else y
        val long = if (n < m) y else x
        var i = 0
        var j = 0
        var skipped = false
        while (i < short.length && j < long.length) {
            if (short[i] == long[j]) {
                i++
                j++
            } else if (!skipped) {
                skipped = true
                j++
            } else {
                return false
            }
        }
        return true
    }

    /**
     * The word completed when [oldText] becomes [newText]: exactly one
     * boundary character appended after a letter run of length >= 2. Covers
     * car-keyboard keys AND phone typing (both flow through onTextChange);
     * backspace, multi-character pastes, and edits elsewhere return null so
     * nothing half-typed is ever learned.
     */
    fun completedWord(oldText: String, newText: String): String? {
        if (newText.length != oldText.length + 1) return null
        if (!newText.startsWith(oldText)) return null
        val boundary = newText.last()
        if (boundary !in BOUNDARY_CHARS) return null
        val word = currentWord(oldText)
        if (word.length < MIN_WORD_LENGTH) return null
        return word.lowercase()
    }

    /** Where the dismiss (down-arrow) key lives. Null when no dismiss is offered at all. */
    fun dismissPlacement(stripEnabled: Boolean, dismissOffered: Boolean): DismissPlacement? {
        if (!dismissOffered) return null
        return if (stripEnabled) DismissPlacement.IN_STRIP else DismissPlacement.IN_BOTTOM_ROW
    }

    /** Paste rule: append the current clipboard item at the end; empty clipboard changes nothing. */
    fun pasteResult(text: String, clip: String?): String =
        if (clip.isNullOrEmpty()) text else text + clip

    // ---- Prefs + asset helpers (thin; decision logic above stays pure) ----

    fun isStripEnabled(context: Context): Boolean = AppSettings.keyboardSuggestionsEnabled(context)

    fun setStripEnabled(context: Context, enabled: Boolean) {
        AppSettings.setKeyboardSuggestionsEnabled(context, enabled)
    }

    fun learnedWords(context: Context): List<String> =
        learnedEntries(context).map { it.word }

    /**
     * Learned words in stored order (oldest last-use first), with use
     * counts. A legacy bare `word` line decodes as count 1, so installs
     * from before r82 migrate on their next learn — no migration step,
     * and the next learnWord write rewrites the whole list in the new
     * format. Malformed lines (empty word, bad or < 1 count) are skipped.
     */
    fun learnedEntries(context: Context): List<LearnedEntry> =
        decodeLearnedEntries(AppSettings.getString(context, AppSettings.KEY_KEYBOARD_LEARNED_WORDS, ""))

    /** Pure decode of the learned-words pref value (see [learnedEntries]). */
    fun decodeLearnedEntries(raw: String): List<LearnedEntry> {
        if (raw.isEmpty()) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            if (line.isEmpty()) return@mapNotNull null
            val eq = line.indexOf('=')
            if (eq < 0) {
                LearnedEntry(line, 1)
            } else {
                val word = line.substring(0, eq)
                val count = line.substring(eq + 1).toIntOrNull()
                if (word.isEmpty() || count == null || count < 1) null else LearnedEntry(word, count)
            }
        }
    }

    /** Pure encode of learned entries to the pref value (one `word=count` line each). */
    fun encodeLearnedEntries(entries: List<LearnedEntry>): String =
        entries.joinToString("\n") { "${it.word}=${it.count}" }

    /**
     * Learn [word] (already lowercased by [completedWord]). An existing
     * word gains a use AND moves to the end (most recent); a new word
     * enters at count 1. The 500 cap still drops from the front, so the
     * cap is a least-recently-used eviction — frequency affects RANKING
     * only, never retention. True if the stored value changed.
     */
    fun learnWord(context: Context, word: String): Boolean {
        if (word.length < MIN_WORD_LENGTH) return false
        val entries = learnedEntries(context).toMutableList()
        val at = entries.indexOfFirst { it.word == word }
        if (at >= 0) {
            val entry = entries.removeAt(at)
            entries.add(entry.copy(count = entry.count + 1))
        } else {
            entries.add(LearnedEntry(word, 1))
        }
        while (entries.size > MAX_LEARNED_WORDS) entries.removeAt(0)
        val joined = encodeLearnedEntries(entries)
        if (joined == AppSettings.getString(context, AppSettings.KEY_KEYBOARD_LEARNED_WORDS, "")) return false
        AppSettings.putString(context, AppSettings.KEY_KEYBOARD_LEARNED_WORDS, joined)
        return true
    }

    /**
     * Learned words best-first for ranking: use count first, then
     * recency (stored order is oldest last-use first, so later entries
     * are more recent). A ten-times-used word outranks a once-used one
     * however old; among equal counts the more recently used wins.
     */
    fun rankedLearnedWords(entries: List<LearnedEntry>): List<String> =
        entries.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<LearnedEntry>> { it.value.count }
                    .thenByDescending { it.index },
            )
            .map { it.value.word }

    fun clearLearnedWords(context: Context) {
        AppSettings.putString(context, AppSettings.KEY_KEYBOARD_LEARNED_WORDS, "")
    }

    @Volatile
    private var cachedWordlist: List<String>? = null

    /** Bundled frequency list, loaded once per process; missing/unreadable asset -> empty (learned only). */
    fun loadWordlist(context: Context): List<String> {
        cachedWordlist?.let { return it }
        val loaded = runCatching {
            context.assets.open("keyboard_words.txt").bufferedReader().useLines { lines ->
                lines.map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() && it.all { c -> c.isLetter() } }
                    .toList()
            }
        }.getOrElse { emptyList() }
        cachedWordlist = loaded
        return loaded
    }
}

/**
 * A learned word with its use count. Stored as one `word=count` line
 * per word in the learned-words pref (see
 * [KeyboardSuggestions.learnedEntries]); a legacy bare `word` line
 * decodes as count 1.
 */
data class LearnedEntry(val word: String, val count: Int)

enum class DismissPlacement { IN_STRIP, IN_BOTTOM_ROW }
