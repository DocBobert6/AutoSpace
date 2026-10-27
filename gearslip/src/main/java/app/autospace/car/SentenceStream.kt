package app.autospace.car

/**
 * Streaming-speech helpers (r55): pure, Android-free, JVM-testable.
 *
 * The AI answer call streams tokens; speech must start on the first
 * complete sentence, never on a half-sentence, and the spoken result
 * must stay identical to the non-streamed reply — same words, same
 * answer cap, only earlier. These two helpers carry exactly the
 * segmentation and cap rules that make that true, so the Android
 * wiring ([CarAssistant], [VoiceEngine]) stays thin.
 */

/**
 * Splits a token stream into complete sentences. Segmentation matches
 * [AiAssistant.capAnswer] exactly — a sentence ends at a `.`, `!`, or
 * `?` followed by whitespace — so a streamed reply re-segments into
 * the same sentences the cap logic would keep. A sentence with no
 * terminator-plus-whitespace after it stays buffered until [finish];
 * "3.5" never splits (no whitespace after the dot), and neither does
 * a trailing "Hello." with nothing after it — it arrives via [finish].
 */
class SentenceSplitter {
    private val buffer = StringBuilder()
    private val boundary = Regex("(?<=[.!?])\\s+")

    /** Feeds one streamed chunk; returns every sentence completed by it, in order. */
    fun feed(chunk: String): List<String> {
        if (chunk.isEmpty()) return emptyList()
        buffer.append(chunk)
        val out = mutableListOf<String>()
        while (true) {
            val text = buffer.toString()
            val match = boundary.find(text) ?: break
            val sentence = text.substring(0, match.range.first).trim()
            buffer.delete(0, match.range.last + 1)
            if (sentence.isNotBlank()) out.add(sentence)
        }
        return out
    }

    /** The trailing partial sentence, if any — spoken only when the stream ends. */
    fun finish(): String? {
        val rest = buffer.toString().trim()
        buffer.clear()
        return rest.ifBlank { null }
    }
}

/**
 * Enforces [AiAssistant.ANSWER_WORD_CAP] on streamed sentences, with
 * the cap logic's own rule: the first sentence always speaks, later
 * ones only while the running word total stays within the cap. Word
 * counting matches [AiAssistant.capAnswer] (whitespace split, blanks
 * dropped), so the spoken prefix plus [finishRemainder] reconstructs
 * the capped answer exactly.
 */
class CappedSentenceEmitter(private val capWords: Int) {
    /** Sentences accepted so far, joined with single spaces — the spoken prefix. */
    var spokenText: String = ""
        private set
    var emittedWords: Int = 0
        private set
    /** True once a sentence was dropped for the cap; later ones drop too. */
    var capped: Boolean = false
        private set

    /** True when [sentence] should be spoken; false drops it (and all later ones). */
    fun offer(sentence: String): Boolean {
        val clean = sentence.trim()
        if (clean.isEmpty()) return false
        if (capped) return false
        val words = clean.split(Regex("\\s+")).count { it.isNotEmpty() }
        if (spokenText.isNotEmpty() && emittedWords + words > capWords) {
            capped = true
            return false
        }
        if (spokenText.isNotEmpty()) spokenText += " "
        spokenText += clean
        emittedWords += words
        return true
    }

    /**
     * Whatever [finalText] still owes beyond the spoken prefix — the
     * cap suffix ("That's the short version.") when capped, blank
     * when the whole answer already spoke. The caller speaks a
     * non-blank remainder as one final chunk before finishing.
     */
    fun finishRemainder(finalText: String): String =
        finalText.removePrefix(spokenText).trim()
}
