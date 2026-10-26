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
 */
object KeyboardSuggestions {

    const val MAX_CANDIDATES = 3
    const val MIN_WORD_LENGTH = 2
    const val MAX_LEARNED_WORDS = 500

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
     * Up to [MAX_CANDIDATES] completions for [word]: learned words first
     * (most-recent-first), then the frequency wordlist in order. Only words
     * LONGER than the typed prefix qualify — suggesting the word already
     * typed is noise. Case-insensitive; learned words are stored lowercase.
     */
    fun candidates(word: String, learned: List<String>, wordlist: List<String>): List<String> {
        if (word.length < MIN_WORD_LENGTH) return emptyList()
        val lower = word.lowercase()
        val out = ArrayList<String>(MAX_CANDIDATES)
        for (w in learned.asReversed()) {
            if (out.size >= MAX_CANDIDATES) break
            if (w.length > word.length && w.startsWith(lower) && w !in out) out.add(w)
        }
        for (w in wordlist) {
            if (out.size >= MAX_CANDIDATES) break
            if (w.length > word.length && w.startsWith(lower) && w !in out) out.add(w)
        }
        return out
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

    fun learnedWords(context: Context): List<String> {
        val raw = AppSettings.getString(context, AppSettings.KEY_KEYBOARD_LEARNED_WORDS, "")
        if (raw.isEmpty()) return emptyList()
        return raw.split('\n').filter { it.isNotEmpty() }
    }

    /** Learn [word] (already lowercased by [completedWord]); most-recent-last, capped. True if changed. */
    fun learnWord(context: Context, word: String): Boolean {
        if (word.length < MIN_WORD_LENGTH) return false
        val words = learnedWords(context).toMutableList()
        words.remove(word)
        words.add(word)
        while (words.size > MAX_LEARNED_WORDS) words.removeAt(0)
        val joined = words.joinToString("\n")
        if (joined == AppSettings.getString(context, AppSettings.KEY_KEYBOARD_LEARNED_WORDS, "")) return false
        AppSettings.putString(context, AppSettings.KEY_KEYBOARD_LEARNED_WORDS, joined)
        return true
    }

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

enum class DismissPlacement { IN_STRIP, IN_BOTTOM_ROW }
