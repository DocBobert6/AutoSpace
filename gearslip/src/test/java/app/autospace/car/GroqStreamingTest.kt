package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r55 Groq streaming wire shape: the stream flag, delta extraction,
 * and SSE line classification. The network loop itself is not
 * JVM-testable (live sockets), so these pure pieces carry the
 * contract: what bytes the client sends, and what each line means.
 */
class GroqStreamingTest {

    @Test fun answerBodyHasNoStreamFlagByDefault() {
        val body = GroqClient.buildAnswerRequestBody("hello")
        assertFalse("\"stream\"" in body)
    }

    @Test fun answerBodyHasStreamFlagWhenStreaming() {
        val body = GroqClient.buildAnswerRequestBody("hello", stream = true)
        assertTrue("\"stream\":true" in body)
    }

    @Test fun deltaContentExtractsFromDeltaShape() {
        val chunk = """{"choices":[{"delta":{"content":"Hello"}}]}"""
        assertEquals("Hello", GroqClient.extractDeltaContent(chunk))
    }

    @Test fun deltaContentIsNullForRoleOnlyChunk() {
        val chunk = """{"choices":[{"delta":{"role":"assistant"}}]}"""
        assertNull(GroqClient.extractDeltaContent(chunk))
    }

    @Test fun deltaContentIsNullForFullBodyMessageShape() {
        val chunk = """{"choices":[{"message":{"content":"Hello"}}]}"""
        assertNull(GroqClient.extractDeltaContent(chunk))
    }

    @Test fun deltaContentIsNullForMalformedOrEmptyChoices() {
        assertNull(GroqClient.extractDeltaContent("{not json"))
        assertNull(GroqClient.extractDeltaContent("""{"choices":[]}"""))
        assertNull(GroqClient.extractDeltaContent("""{"choices":[{"delta":{}}]}"""))
    }

    @Test fun sseDoneLineIsDone() {
        assertEquals(GroqClient.SseEvent.Done, GroqClient.parseSseLine("data: [DONE]"))
    }

    @Test fun sseBlankCommentAndNonDataLinesAreIgnored() {
        assertEquals(GroqClient.SseEvent.Ignored, GroqClient.parseSseLine(""))
        assertEquals(GroqClient.SseEvent.Ignored, GroqClient.parseSseLine(": ping"))
        assertEquals(GroqClient.SseEvent.Ignored, GroqClient.parseSseLine("event: message"))
    }

    @Test fun sseDataLineCarriesDeltaContent() {
        val event = GroqClient.parseSseLine("""data: {"choices":[{"delta":{"content":"Hi"}}]}""")
        assertEquals(GroqClient.SseEvent.Data("Hi"), event)
    }

    @Test fun sseDataLineWithRoleOnlyCarriesNullContent() {
        val event = GroqClient.parseSseLine("""data: {"choices":[{"delta":{"role":"assistant"}}]}""")
        assertEquals(GroqClient.SseEvent.Data(null), event)
    }
}
