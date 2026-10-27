package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r55 streaming-speech helpers: sentence segmentation must match the
 * answer cap's own rule, and the cap emitter must reconstruct the
 * capped answer exactly — otherwise a streamed reply would speak
 * different words than the non-streamed one.
 */
class SentenceStreamTest {

    @Test fun splitterEmitsSentencesInOrderAcrossFeeds() {
        val splitter = SentenceSplitter()
        assertEquals(listOf("Hello world."), splitter.feed("Hello world. "))
        assertEquals(listOf("How are you?"), splitter.feed("How are you? "))
        assertEquals(emptyList<String>(), splitter.feed("Fine!"))
        assertEquals("Fine!", splitter.finish())
    }

    @Test fun splitterHoldsPartialUntilTerminatorPlusSpace() {
        val splitter = SentenceSplitter()
        assertEquals(emptyList<String>(), splitter.feed("Hello world"))
        assertEquals(listOf("Hello world."), splitter.feed(". Next"))
        assertEquals("Next", splitter.finish())
    }

    @Test fun splitterDoesNotSplitDecimalsOrTrailingSentenceWithoutSpace() {
        val splitter = SentenceSplitter()
        assertEquals(
            listOf("It costs 3.5 dollars."),
            splitter.feed("It costs 3.5 dollars. Done."),
        )
        assertEquals("Done.", splitter.finish())
    }

    @Test fun splitterTreatsNewlinesAsSeparators() {
        val splitter = SentenceSplitter()
        assertEquals(listOf("One."), splitter.feed("One.\nTwo."))
        assertEquals("Two.", splitter.finish())
    }

    @Test fun splitterFinishOnEmptyIsNull() {
        val splitter = SentenceSplitter()
        assertEquals(emptyList<String>(), splitter.feed(""))
        assertNull(splitter.finish())
    }

    @Test fun emitterFirstSentenceAlwaysSpeaksEvenOverCap() {
        val emitter = CappedSentenceEmitter(capWords = 5)
        assertTrue(emitter.offer("one two three four five six seven eight"))
        assertEquals(8, emitter.emittedWords)
        assertEquals("one two three four five six seven eight", emitter.spokenText)
    }

    @Test fun emitterDropsSentencesPastCapAndStaysDropped() {
        val emitter = CappedSentenceEmitter(capWords = 5)
        assertTrue(emitter.offer("one two"))
        assertTrue(emitter.offer("three four five"))
        assertEquals(5, emitter.emittedWords)
        assertFalse(emitter.offer("six seven"))
        assertTrue(emitter.capped)
        assertFalse(emitter.offer("eight"))
        assertEquals("one two three four five", emitter.spokenText)
    }

    @Test fun emitterBlankSentenceChangesNothing() {
        val emitter = CappedSentenceEmitter(capWords = 5)
        assertFalse(emitter.offer("   "))
        assertEquals(0, emitter.emittedWords)
        assertEquals("", emitter.spokenText)
    }

    @Test fun emitterFinishRemainderIsCapSuffixWhenCapped() {
        val emitter = CappedSentenceEmitter(capWords = 5)
        emitter.offer("one two three four five")
        emitter.offer("six seven")
        assertEquals(
            "That's the short version.",
            emitter.finishRemainder("one two three four five That's the short version."),
        )
    }

    @Test fun emitterFinishRemainderIsBlankWhenFullySpoken() {
        val emitter = CappedSentenceEmitter(capWords = 60)
        emitter.offer("Hello world.")
        emitter.offer("How are you?")
        assertEquals("", emitter.finishRemainder("Hello world. How are you?"))
    }
}
