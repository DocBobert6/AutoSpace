package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r25 AI assistant tests: routing order, confirmation defaults, proposal validation,
 * answer cap, log redaction, Gemini response parsing, and the preview dry-run guarantee.
 * Everything here runs on the JVM — the Keystore-wrapped key storage itself is
 * Android-only and is covered by code review plus the redaction tests below, not by a
 * unit test that cannot see an AndroidKeyStore.
 */
class AiAssistantTest {

    // --- Routing order: built-in > keyword > fuzzy > AI > unmatched (spec §3) ---

    @Test fun builtinBeatsKeywordForNavigateForm() {
        val trace = AssistantPreview.classify("navigate to home", AssistantPreview.FIXTURE_KEYWORDS, aiGates = true)
        assertEquals(AssistantPreview.Handler.BUILTIN, trace.handler)
    }

    @Test fun keywordBeatsGenericOpen() {
        val trace = AssistantPreview.classify("open my tunes", AssistantPreview.FIXTURE_KEYWORDS, aiGates = false)
        assertEquals(AssistantPreview.Handler.KEYWORD, trace.handler)
    }

    @Test fun exactBuiltinBeatsFuzzy() {
        val trace = AssistantPreview.classify("brightness up", AssistantPreview.FIXTURE_KEYWORDS, aiGates = true)
        assertEquals(AssistantPreview.Handler.BUILTIN, trace.handler)
    }

    @Test fun aiIsLastAndGated() {
        val on = AssistantPreview.classify("blorp glorp", AssistantPreview.FIXTURE_KEYWORDS, aiGates = true)
        assertEquals(AssistantPreview.Handler.AI, on.handler)
        val off = AssistantPreview.classify("blorp glorp", AssistantPreview.FIXTURE_KEYWORDS, aiGates = false)
        assertEquals(AssistantPreview.Handler.UNMATCHED, off.handler)
        val local = AssistantPreview.classify("volume up", AssistantPreview.FIXTURE_KEYWORDS, aiGates = true)
        assertEquals(AssistantPreview.Handler.BUILTIN, local.handler)
    }

    @Test fun cannedSuitePassesWithGatesOpen() {
        val result = AssistantPreview.runSuite(AssistantPreview.FIXTURE_KEYWORDS, aiGates = true)
        assertEquals(0, result.failed)
        assertEquals(0, result.skipped)
        assertEquals(AssistantPreview.CANNED_CASES.size, result.passed)
    }

    @Test fun cannedSuiteSkipsAiCasesWithGatesClosed() {
        val result = AssistantPreview.runSuite(AssistantPreview.FIXTURE_KEYWORDS, aiGates = false)
        assertEquals(0, result.failed)
        assertEquals(3, result.skipped)
        assertEquals(AssistantPreview.CANNED_CASES.size - 3, result.passed)
    }

    // --- Confirmation defaults: silence / timeout / ambiguity / unknown = DECLINE ---

    @Test fun confirmationCasesAllPass() {
        val failures = AssistantPreview.runConfirmationCases().filter { !it.second }
        assertTrue("confirmation failures: $failures", failures.isEmpty())
    }

    @Test fun vagueAcceptanceNeverAccepts() {
        assertEquals(AiAssistant.ConfirmationDecision.UNKNOWN, AiAssistant.matchConfirmation("uh-huh"))
        assertEquals(AiAssistant.ConfirmationDecision.UNKNOWN, AiAssistant.matchConfirmation("maybe"))
        assertEquals(AiAssistant.ConfirmationDecision.DECLINE, AiAssistant.decideConfirmation("uh-huh", expired = false))
        assertEquals(AiAssistant.ConfirmationDecision.DECLINE, AiAssistant.decideConfirmation(null, expired = false))
        assertEquals(AiAssistant.ConfirmationDecision.DECLINE, AiAssistant.decideConfirmation("yes", expired = true))
    }

    // --- Proposal validation: invented targets refused, never confirmed ---

    @Test fun inventedDestinationRefused() {
        val parsed = AiAssistant.parseModelReply(
            """{"action": "navigate", "destination": "Paris, France"}""",
            "navigate to the store",
        )
        assertTrue(parsed is AiAssistant.ParsedReply.Refused)
    }

    @Test fun mentionedDestinationProposed() {
        val parsed = AiAssistant.parseModelReply(
            """{"action": "navigate", "destination": "the store"}""",
            "navigate to the store",
        )
        assertTrue(parsed is AiAssistant.ParsedReply.Proposal)
        val action = (parsed as AiAssistant.ParsedReply.Proposal).action
        assertEquals("Navigate to the store?", AiAssistant.confirmationText(action))
    }

    @Test fun emergencyCallViaAiBecomesLocalOffer() {
        val parsed = AiAssistant.parseModelReply("""{"action": "call", "contact": "911"}""", "call 911")
        assertEquals(AiAssistant.ParsedReply.EmergencyOffer, parsed)
    }

    @Test fun hangupRefusedPlainly() {
        val parsed = AiAssistant.parseModelReply("""{"action": "hangup"}""", "hang up")
        assertTrue(parsed is AiAssistant.ParsedReply.Refused)
        assertTrue((parsed as AiAssistant.ParsedReply.Refused).reason.contains("dialer"))
    }

    @Test fun malformedAndUnknownProposalsRefused() {
        assertTrue(AiAssistant.parseModelReply("{not json", "do something") is AiAssistant.ParsedReply.Refused)
        assertTrue(AiAssistant.parseModelReply("""{"action": "buy_stocks", "symbol": "X"}""", "buy stocks") is AiAssistant.ParsedReply.Refused)
        assertTrue(AiAssistant.parseModelReply("""{"action": "timer", "minutes": 500}""", "set a timer") is AiAssistant.ParsedReply.Refused)
        assertTrue(AiAssistant.parseModelReply("""{"action": "brightness", "percent": 150}""", "brightness") is AiAssistant.ParsedReply.Refused)
        assertTrue(AiAssistant.parseModelReply("""{"action": "reply_message", "contact": "Mom", "text": ""}""", "text mom") is AiAssistant.ParsedReply.Refused)
    }

    @Test fun plainAnswerPassesThrough() {
        val parsed = AiAssistant.parseModelReply("The sky is blue because of Rayleigh scattering.", "why is the sky blue")
        assertTrue(parsed is AiAssistant.ParsedReply.Answer)
    }

    @Test fun messageConfirmationReadsBackFullText() {
        val parsed = AiAssistant.parseModelReply(
            """{"action": "reply_message", "contact": "Mom", "text": "On my way home now"}""",
            "text mom that I'm on my way home now",
        )
        assertTrue(parsed is AiAssistant.ParsedReply.Proposal)
        val action = (parsed as AiAssistant.ParsedReply.Proposal).action
        assertEquals("Send this to Mom: 'On my way home now'?", AiAssistant.confirmationText(action))
    }

    // --- Answer cap: sentence boundary, never mid-sentence ---

    @Test fun shortAnswerUntouched() {
        assertEquals("Paris is the capital of France.", AiAssistant.capAnswer("Paris is the capital of France."))
    }

    @Test fun longAnswerTruncatedAtSentenceBoundary() {
        val sentences = (1..10).joinToString(" ") { "Sentence number $it has a few words in it." }
        val capped = AiAssistant.capAnswer(sentences)
        assertTrue(capped.endsWith("That's the short version."))
        val body = capped.removeSuffix(" That's the short version.")
        assertTrue(body.endsWith("."))
        assertTrue(body.split(Regex("\\s+")).size <= AiAssistant.ANSWER_WORD_CAP)
        assertTrue(body.startsWith("Sentence number 1"))
    }

    // --- Log redaction ---

    @Test fun keyTokenScrubbedFromLogLines() {
        val line = "assistant: something AIzaSyAbc1234567890XYZ_abcdefg happened"
        val scrubbed = AiAssistant.AiRedaction.scrub(line)
        assertFalse(scrubbed.contains("AIzaSyAbc1234567890XYZ_abcdefg"))
        assertTrue(scrubbed.contains("[api key redacted]"))
        assertTrue(AiAssistant.AiRedaction.mustDrop(line))
        assertEquals("assistant: nothing secret here", AiAssistant.AiRedaction.scrub("assistant: nothing secret here"))
        assertFalse(AiAssistant.AiRedaction.mustDrop("assistant: nothing secret here"))
    }

    // --- Gemini response parsing ---

    @Test fun extractTextJoinsParts() {
        val json = """{"candidates": [{"content": {"parts": [{"text": "Hello"}, {"text": "world"}]}}]}"""
        assertEquals("Hello world", GeminiClient.extractText(json))
    }

    @Test fun extractTextRejectsEmptyAndMalformed() {
        assertEquals(null, GeminiClient.extractText("""{"candidates": []}"""))
        assertEquals(null, GeminiClient.extractText("""{"promptFeedback": {"blockReason": "SAFETY"}}"""))
        assertEquals(null, GeminiClient.extractText("not json"))
    }

    // --- Preview dry-run guarantee ---

    @Test fun previewNeverExecutes() {
        AssistantPreview.CANNED_CASES.forEach { case ->
            val trace = AssistantPreview.classify(case.utterance, AssistantPreview.FIXTURE_KEYWORDS, aiGates = true)
            assertFalse("preview executed for ${case.utterance}", trace.executes)
        }
    }

    @Test fun readOnlyTracesLabeledRunnable() {
        val trace = AssistantPreview.classify("phone battery", AssistantPreview.FIXTURE_KEYWORDS, aiGates = false)
        assertTrue(trace.detail.contains("read-only"))
    }

    @Test fun systemPromptIsShortAndGuarded() {
        val prompt = AiAssistant.SYSTEM_PROMPT
        assertTrue(prompt.isNotBlank())
        assertTrue(prompt.contains("Never invent addresses"))
        assertTrue(prompt.contains("JSON object"))
    }
}
