package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic coverage for the r30 Gboard-look suggestion strip: no Android context needed. */
class KeyboardSuggestionsTest {

    private val wordlist = listOf(
        "the", "them", "there", "hello", "help", "held", "world", "word", "work", "wonder",
    )

    @Test
    fun currentWordTakesTrailingLettersOnly() {
        assertEquals("hello", KeyboardSuggestions.currentWord("say hello"))
        assertEquals("", KeyboardSuggestions.currentWord("say hello "))
        assertEquals("", KeyboardSuggestions.currentWord(""))
        assertEquals("known", KeyboardSuggestions.currentWord("well-known"))
    }

    @Test
    fun currentWordKeepsInternalApostrophes() {
        assertEquals("don't", KeyboardSuggestions.currentWord("I don't"))
        assertEquals("hi", KeyboardSuggestions.currentWord("say 'hi"))
    }

    @Test
    fun replaceCurrentWordPreservesPrecedingText() {
        assertEquals("say hello", KeyboardSuggestions.replaceCurrentWord("say hel", "hello"))
        assertEquals("say Hello", KeyboardSuggestions.replaceCurrentWord("say Hel", "hello"))
        assertEquals("hello", KeyboardSuggestions.replaceCurrentWord("hel", "hello"))
        assertEquals("say ", KeyboardSuggestions.replaceCurrentWord("say ", "hello"))
    }

    @Test
    fun matchCaseFollowsTypedCase() {
        assertEquals("hello", KeyboardSuggestions.matchCase("hel", "hello"))
        assertEquals("Hello", KeyboardSuggestions.matchCase("Hel", "hello"))
        assertEquals("HELLO", KeyboardSuggestions.matchCase("HEL", "hello"))
        // r50: a glide candidate offered with an empty current word (traced
        // after a space) must pass through, not throw on first().
        assertEquals("hello", KeyboardSuggestions.matchCase("", "hello"))
    }

    @Test
    fun candidatesPreferLearnedMostRecentFirstThenWordlistOrder() {
        // Equal counts: recency breaks the tie (stored order is oldest
        // last-use first, so helmet, stored later, ranks first).
        val learned = listOf(LearnedEntry("helios", 1), LearnedEntry("helmet", 1))
        assertEquals(listOf("helmet", "helios", "hello"), KeyboardSuggestions.candidates("hel", learned, wordlist))
    }

    @Test
    fun candidatesPreferFrequentLearnedOverRecent() {
        // r82: frequency beats recency — helios, used five times but
        // stored earlier, outranks helmet, used once but more recently.
        val learned = listOf(LearnedEntry("helios", 5), LearnedEntry("helmet", 1))
        assertEquals(listOf("helios", "helmet", "hello"), KeyboardSuggestions.candidates("hel", learned, wordlist))
    }

    @Test
    fun decodeLearnedEntriesLegacyAndMalformed() {
        assertEquals(
            listOf(LearnedEntry("hello", 1), LearnedEntry("world", 3)),
            KeyboardSuggestions.decodeLearnedEntries("hello\nworld=3"),
        )
        // Malformed lines are skipped, never crash the strip.
        assertEquals(
            listOf(LearnedEntry("ok", 2)),
            KeyboardSuggestions.decodeLearnedEntries("=5\nbad=x\nzero=0\n\nok=2"),
        )
        assertEquals(emptyList<LearnedEntry>(), KeyboardSuggestions.decodeLearnedEntries(""))
        assertEquals("a=1\nb=12", KeyboardSuggestions.encodeLearnedEntries(listOf(LearnedEntry("a", 1), LearnedEntry("b", 12))))
    }

    @Test
    fun editDistanceOneRules() {
        assertTrue(KeyboardSuggestions.editDistanceOne("hllo", "hello")) // insertion
        assertTrue(KeyboardSuggestions.editDistanceOne("teh", "the")) // transposition
        assertTrue(KeyboardSuggestions.editDistanceOne("bokk", "book")) // substitution
        assertTrue(KeyboardSuggestions.editDistanceOne("helllo", "hello")) // deletion (extra letter)
        assertTrue(!KeyboardSuggestions.editDistanceOne("hello", "hello")) // identical is not a miss
        assertTrue(!KeyboardSuggestions.editDistanceOne("hello", "world")) // far apart
        assertTrue(!KeyboardSuggestions.editDistanceOne("he", "hello")) // length gap > 1
    }

    @Test
    fun candidatesOfferNearMissAfterExactPrefix() {
        // "hllo": no exact-prefix match; "hello" is a distance-1 insertion.
        assertEquals(listOf("hello"), KeyboardSuggestions.candidates("hllo", emptyList(), wordlist))
        // "teh": transposition of "the"; "them"/"there" are two edits away.
        assertEquals(listOf("the"), KeyboardSuggestions.candidates("teh", emptyList(), wordlist))
    }

    @Test
    fun candidatesPrefixTierFillsBeforeNearMiss() {
        // "hell": exact prefix "hello" first, then near-misses "help"/"held"
        // (one substitution each), cap still 3.
        assertEquals(listOf("hello", "help", "held"), KeyboardSuggestions.candidates("hell", emptyList(), wordlist))
    }

    @Test
    fun candidatesNearMissLearnedBeforeWordlist() {
        // Learned "hallo" (distance 1 from "hllo") outranks wordlist "hello".
        val learned = listOf(LearnedEntry("hallo", 2))
        assertEquals(listOf("hallo", "hello"), KeyboardSuggestions.candidates("hllo", learned, wordlist))
    }

    @Test
    fun candidatesTwoLetterInputStaysPrefixOnly() {
        // "hy" is distance 1 from "hey", but two-letter input never gets
        // near-misses — too ambiguous. Three-letter "hye" does.
        assertEquals(emptyList<String>(), KeyboardSuggestions.candidates("hy", emptyList(), listOf("hey")))
        assertEquals(listOf("hey"), KeyboardSuggestions.candidates("hye", emptyList(), listOf("hey")))
    }

    @Test
    fun candidatesCapAtThreeSkipExactAndShortWords() {
        assertEquals(
            listOf("them", "there"),
            KeyboardSuggestions.candidates("the", emptyList(), wordlist),
        )
        assertEquals(emptyList<String>(), KeyboardSuggestions.candidates("t", emptyList(), wordlist))
        assertEquals(emptyList<String>(), KeyboardSuggestions.candidates("zzz", emptyList(), wordlist))
    }

    @Test
    fun candidatesAreCaseInsensitive() {
        assertEquals(
            listOf("hello", "help", "held"),
            KeyboardSuggestions.candidates("Hel", emptyList(), wordlist),
        )
    }

    @Test
    fun completedWordFiresOnlyOnSingleBoundaryAfterAWord() {
        assertEquals("hello", KeyboardSuggestions.completedWord("say hello", "say hello "))
        assertEquals("hello", KeyboardSuggestions.completedWord("hello", "hello."))
        assertNull(KeyboardSuggestions.completedWord("say hello", "say hello"))
        assertNull(KeyboardSuggestions.completedWord("say hell", "say hello"))
        assertNull(KeyboardSuggestions.completedWord("say hello", "say hello  "))
        assertNull(KeyboardSuggestions.completedWord("say a", "say a "))
        assertNull(KeyboardSuggestions.completedWord("say hello", "say hell"))
        assertNull(KeyboardSuggestions.completedWord("say hello", "say hello world "))
    }

    @Test
    fun dismissPlacementFollowsStripAndOffer() {
        assertEquals(DismissPlacement.IN_STRIP, KeyboardSuggestions.dismissPlacement(true, true))
        assertEquals(DismissPlacement.IN_BOTTOM_ROW, KeyboardSuggestions.dismissPlacement(false, true))
        assertNull(KeyboardSuggestions.dismissPlacement(true, false))
        assertNull(KeyboardSuggestions.dismissPlacement(false, false))
    }

    @Test
    fun pasteAppendsOnlyNonEmptyClip() {
        assertEquals("hello world", KeyboardSuggestions.pasteResult("hello ", "world"))
        assertEquals("hello", KeyboardSuggestions.pasteResult("hello", null))
        assertEquals("hello", KeyboardSuggestions.pasteResult("hello", ""))
    }

    @Test
    fun constantsHold() {
        assertEquals(3, KeyboardSuggestions.MAX_CANDIDATES)
        assertEquals(2, KeyboardSuggestions.MIN_WORD_LENGTH)
        assertTrue(KeyboardSuggestions.MAX_LEARNED_WORDS >= 100)
    }
}
