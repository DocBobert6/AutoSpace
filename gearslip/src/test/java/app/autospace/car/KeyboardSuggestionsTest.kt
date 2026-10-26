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
    }

    @Test
    fun candidatesPreferLearnedMostRecentFirstThenWordlistOrder() {
        val learned = listOf("helios", "helmet")
        assertEquals(
            listOf("helmet", "helios", "hello"),
            KeyboardSuggestions.candidates("hel", learned, wordlist),
        )
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
