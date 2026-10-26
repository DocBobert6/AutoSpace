package app.autospace.car

/**
 * Glide typing matcher (r50): pure, JVM-testable, no Android context.
 *
 * A glide is a finger trace across letter keys without lifting. The matcher turns the
 * traced key sequence into a word, or into candidates for the strip when the trace is
 * too weak to insert. It is deliberately NOT a language model: no frequencies are
 * invented, no next-word prediction happens (still declined), and the only vocabulary
 * is the user's own learned words plus the bundled wordlist — the same sources the
 * suggestion strip already uses.
 *
 * Matching rules, in order:
 * - The word's letters must appear in the traced path in order (subsequence), with one
 *   allowance: a doubled letter may sit on the same key the finger already rests on
 *   ("hello" traces h-e-l-o, not h-e-l-l-o, because the finger never leaves the l key
 *   for the second l). Keys the finger passes over between letters are skipped and
 *   counted against the match.
 * - Start/end key anchors score strongest: a candidate whose first letter is the
 *   path's first key and whose last letter is the path's last key is anchor-perfect.
 *   A sloppy lift past the last letter (path ends on a neighbour) still ranks the
 *   intended word for a strip tap, but never auto-inserts it.
 * - Ranking: learned words first (most-recent-first input; within learned, and within
 *   the wordlist, anchor-perfect beats anchor-missed, fewer skipped keys beats more,
 *   and the input order — recency for learned, frequency for the wordlist — breaks
 *   ties, via a stable sort).
 * - Confidence gate ([resolve]): insert only when the best candidate is
 *   anchor-perfect AND either it is a learned word (the user's own vocabulary is a
 *   strong signal) or it is the unique anchor-perfect winner by skipped keys.
 *   Anything else with candidates becomes a strip suggestion; no candidates at all
 *   is [Result.NoMatch]. The gate is what keeps weak guesses from becoming
 *   unwanted inserts — the strip plus backspace are the correction path.
 *
 * Honest ceiling, same as the strip's: good on common words, weaker on unusual ones,
 * and a trace that never lands on a needed letter cannot match at all (subsequence
 * is strict). The 3-candidate strip is the safety net, by design.
 */
object GlideMatcher {

    /** A press becomes a glide only once the finger has entered this many distinct keys. */
    const val GLIDE_THRESHOLD_KEYS = 3

    /** Candidates ever offered, matching the strip cap (never raised for glide). */
    const val MAX_CANDIDATES = 3

    /** A ranked candidate: [skipped] counts path keys passed over, [anchorMissed] counts missed anchors (0-2). */
    data class Scored(val word: String, val skipped: Int, val anchorMissed: Int, val learned: Boolean)

    sealed interface Result {
        /** High-confidence match: insert [word] (the caller replaces the current word). */
        data class Insert(val word: String) : Result

        /** Weak trace: insert nothing, offer [candidates] (best-first) on the strip for a tap. */
        data class Suggest(val candidates: List<String>) : Result

        /** No candidate at all: insert nothing, strip stays normal. */
        data object NoMatch : Result
    }

    /**
     * Append [key] to a traced path, collapsing consecutive duplicates: the finger
     * resting or rolling inside one key adds nothing, so a path is distinct keys only.
     */
    fun accumulatePath(path: List<Char>, key: Char): List<Char> =
        if (path.isEmpty() || path.last() != key) path + key else path

    /**
     * Greedy subsequence match of [word] against [path], with the double-letter stay:
     * a letter equal to the previously consumed path key matches without advancing
     * (so "hello" traces h-e-l-o). Keys the finger passes over between letters are
     * skipped and counted. Returns the total skipped count — passed-over keys plus
     * any trailing keys after the last letter — or null when the word cannot be
     * traced (a needed letter never appears in the path).
     */
    fun matchSkipped(word: String, path: List<Char>): Int? {
        var p = 0
        var skipped = 0
        for (ch in word) {
            if (p < path.size && path[p] == ch) {
                p++
                continue
            }
            if (p > 0 && path[p - 1] == ch) continue
            while (p < path.size && path[p] != ch) {
                p++
                skipped++
            }
            if (p >= path.size) return null
            p++
        }
        skipped += path.size - p
        return skipped
    }

    /** Ranked best-first, or empty when the path is below the glide threshold. */
    fun rank(
        path: List<Char>,
        learnedMostRecentFirst: List<String>,
        wordlist: List<String>,
    ): List<Scored> {
        if (path.size < GLIDE_THRESHOLD_KEYS) return emptyList()
        val seen = HashSet<String>()
        val learnedRanked = ArrayList<Scored>()
        for (w in learnedMostRecentFirst) {
            val word = w.lowercase()
            if (word in seen) continue
            val skipped = matchSkipped(word, path) ?: continue
            seen.add(word)
            learnedRanked.add(Scored(word, skipped, anchorMissed(word, path), learned = true))
        }
        learnedRanked.sortWith(compareBy({ it.anchorMissed }, { it.skipped }))
        val wordlistRanked = ArrayList<Scored>()
        for (w in wordlist) {
            val word = w.lowercase()
            if (word in seen) continue
            val skipped = matchSkipped(word, path) ?: continue
            seen.add(word)
            wordlistRanked.add(Scored(word, skipped, anchorMissed(word, path), learned = false))
        }
        wordlistRanked.sortWith(compareBy({ it.anchorMissed }, { it.skipped }))
        return learnedRanked + wordlistRanked
    }

    /** Confidence-gated resolution of a finished trace. */
    fun resolve(
        path: List<Char>,
        learnedMostRecentFirst: List<String>,
        wordlist: List<String>,
    ): Result {
        val ranked = rank(path, learnedMostRecentFirst, wordlist)
        if (ranked.isEmpty()) return Result.NoMatch
        val best = ranked[0]
        if (best.anchorMissed != 0) return Result.Suggest(ranked.take(MAX_CANDIDATES).map { it.word })
        val perfect = ranked.filter { it.anchorMissed == 0 }
        val highConfidence = best.learned || perfect.size == 1 || best.skipped < perfect[1].skipped
        return if (highConfidence) Result.Insert(best.word)
        else Result.Suggest(ranked.take(MAX_CANDIDATES).map { it.word })
    }

    /**
     * Centre-best order for the strip: with exactly 3 candidates the best sits in the
     * centre slot (the live-preview slot while tracing); fewer candidates stay
     * best-first, since there is no centre to fill.
     */
    fun centreBest(candidates: List<String>): List<String> =
        if (candidates.size == 3) listOf(candidates[1], candidates[0], candidates[2]) else candidates

    private fun anchorMissed(word: String, path: List<Char>): Int {
        var missed = 0
        if (word.isEmpty() || word.first() != path.first()) missed++
        if (word.isEmpty() || word.last() != path.last()) missed++
        return missed
    }
}
