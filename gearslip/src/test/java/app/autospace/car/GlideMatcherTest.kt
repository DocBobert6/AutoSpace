package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r50 unit tests for the glide matcher: anchors, subsequence with the double-letter
 * stay, skipped-key scoring, learned-first ranking, and the confidence gate. The
 * matcher takes its vocabulary as parameters, so no asset or context is needed here.
 */
class GlideMatcherTest {

    private val wordlist = listOf("hello", "helo", "help", "cat", "world", "word", "to", "two")

    @Test
    fun belowThresholdNeverMatches() {
        assertTrue(GlideMatcher.rank(listOf('h', 'e'), emptyList(), wordlist).isEmpty())
        assertEquals(
            GlideMatcher.Result.NoMatch,
            GlideMatcher.resolve(listOf('h', 'e'), emptyList(), wordlist),
        )
    }

    @Test
    fun doubleLetterStaysOnOneKey() {
        // "hello" traces h-e-l-o: the second l never leaves the l key.
        val ranked = GlideMatcher.rank(listOf('h', 'e', 'l', 'o'), emptyList(), wordlist)
        assertEquals("hello", ranked[0].word)
        assertEquals(0, ranked[0].skipped)
        assertEquals(0, ranked[0].anchorMissed)
    }

    @Test
    fun passedOverKeysCountAsSkipped() {
        // Tracing c-x-a-t to reach "cat": x is passed over, so skipped = 1.
        val ranked = GlideMatcher.rank(listOf('c', 'x', 'a', 't'), emptyList(), wordlist)
        assertEquals("cat", ranked[0].word)
        assertEquals(1, ranked[0].skipped)
        assertEquals(GlideMatcher.Result.Insert("cat"), GlideMatcher.resolve(listOf('c', 'x', 'a', 't'), emptyList(), wordlist))
    }

    @Test
    fun learnedWordWinsOverWordlist() {
        // "hello" is both learned and in the wordlist; the learned entry ranks first
        // and its signal inserts even though "helo" ties it on skipped keys.
        val ranked = GlideMatcher.rank(listOf('h', 'e', 'l', 'o'), listOf("hello"), wordlist)
        assertEquals("hello", ranked[0].word)
        assertTrue(ranked[0].learned)
        assertEquals(GlideMatcher.Result.Insert("hello"), GlideMatcher.resolve(listOf('h', 'e', 'l', 'o'), listOf("hello"), wordlist))
    }

    @Test
    fun tieBetweenWordlistWordsSuggestsInsteadOfInserting() {
        // "helo" and "hello" both match h-e-l-o with 0 skipped and perfect anchors:
        // no unique winner, so nothing inserts and the strip gets both for a tap.
        val result = GlideMatcher.resolve(listOf('h', 'e', 'l', 'o'), emptyList(), listOf("helo", "hello"))
        assertEquals(GlideMatcher.Result.Suggest(listOf("helo", "hello")), result)
    }

    @Test
    fun uniqueWinnerInserts() {
        assertEquals(
            GlideMatcher.Result.Insert("world"),
            GlideMatcher.resolve(listOf('w', 'o', 'r', 'l', 'd'), emptyList(), wordlist),
        )
    }

    @Test
    fun sloppyLiftPastLastLetterSuggestsButNeverInserts() {
        // Lifted from o onto x: "hello" still ranks (last letter in path, not last),
        // but its missed end anchor blocks auto-insert — the strip offers it for a tap.
        // (x, not p: lifting onto p would genuinely spell "help", which SHOULD insert.)
        val ranked = GlideMatcher.rank(listOf('h', 'e', 'l', 'o', 'x'), emptyList(), listOf("hello", "helo"))
        assertEquals("hello", ranked[0].word)
        assertEquals(1, ranked[0].anchorMissed)
        assertEquals(
            GlideMatcher.Result.Suggest(listOf("hello", "helo")),
            GlideMatcher.resolve(listOf('h', 'e', 'l', 'o', 'x'), emptyList(), listOf("hello", "helo")),
        )
    }

    @Test
    fun missingLetterCannotMatch() {
        // No word in the list uses only z, q, x — nothing ranks, not even weakly.
        assertEquals(
            GlideMatcher.Result.NoMatch,
            GlideMatcher.resolve(listOf('z', 'q', 'x'), emptyList(), wordlist),
        )
    }

    @Test
    fun twoLetterWordGlidesThroughACrossedKey() {
        // "to" traces t-y-o: the crossed y is skipped, anchors are perfect, unique winner.
        assertEquals(
            GlideMatcher.Result.Insert("to"),
            GlideMatcher.resolve(listOf('t', 'y', 'o'), emptyList(), wordlist),
        )
    }

    @Test
    fun accumulatePathCollapsesConsecutiveDuplicates() {
        val path = GlideMatcher.accumulatePath(emptyList(), 'h')
        val same = GlideMatcher.accumulatePath(path, 'h')
        assertEquals(path, same)
        assertEquals(listOf('h', 'e'), GlideMatcher.accumulatePath(same, 'e'))
    }

    @Test
    fun centreBestPutsBestInCentreSlotOnlyWithThree() {
        assertEquals(listOf("b", "a", "c"), GlideMatcher.centreBest(listOf("a", "b", "c")))
        assertEquals(listOf("a", "b"), GlideMatcher.centreBest(listOf("a", "b")))
        assertEquals(listOf("a"), GlideMatcher.centreBest(listOf("a")))
    }
}
