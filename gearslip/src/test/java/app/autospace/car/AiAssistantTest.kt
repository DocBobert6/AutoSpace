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
        assertEquals(6, result.skipped)
        assertEquals(AssistantPreview.CANNED_CASES.size - 6, result.passed)
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

    // --- r27 grounding: request body, metadata counting, place rule, preview trace ---

    @Test fun requestBodyCarriesGroundingToolAndNoLocation() {
        val body = GeminiClient.buildRequestBody("weather in dry ridge")
        assertTrue(body.contains("\"tools\":[{\"googleSearch\":{}}]"))
        assertTrue(body.contains("\"maxOutputTokens\":300"))
        assertFalse(body.contains("userLocation"))
        assertFalse(body.contains("latLng"))
        assertFalse(body.contains("latitude"))
        assertFalse(body.contains("location"))
        // The utterance round-trips through the escaping: quotes and backslashes stay valid JSON.
        val escaped = GeminiClient.buildRequestBody("say \"hi\" \\ now")
        val parsed = JsonMini.parse(escaped) as JsonMini.Value.Obj
        val contents = (parsed.objField("contents") as JsonMini.Value.Arr).list
        val parts = ((contents[0] as JsonMini.Value.Obj).objField("parts") as JsonMini.Value.Arr).list
        assertEquals("say \"hi\" \\ now", (parts[0] as JsonMini.Value.Obj).objField("text")?.asString())
    }

    @Test fun groundingChunksCountedContentsNeverKept() {
        val grounded = """{"candidates": [{"content": {"parts": [{"text": "Sunny."}]}, "groundingMetadata": {"webSearchQueries": ["weather dry ridge"], "groundingChunks": [{"web": {"uri": "https://a.example", "title": "A"}}, {"web": {"uri": "https://b.example", "title": "B"}}, {"web": {"uri": "https://c.example", "title": "C"}}]}}]}"""
        val g = GeminiClient.extractGrounding(grounded)
        assertTrue(g.grounded)
        assertEquals(3, g.sourceCount)
        val queriesOnly = """{"candidates": [{"content": {"parts": [{"text": "Sunny."}]}, "groundingMetadata": {"webSearchQueries": ["weather dry ridge"]}}]}"""
        assertEquals(GeminiClient.Grounding(false, 0), GeminiClient.extractGrounding(queriesOnly))
        assertEquals(GeminiClient.Grounding(false, 0), GeminiClient.extractGrounding("""{"candidates": [{"content": {"parts": [{"text": "Hi"}]}}]}"""))
        assertEquals(GeminiClient.Grounding(false, 0), GeminiClient.extractGrounding("not json"))
    }

    @Test fun placeRuleOptionOne() {
        assertTrue(AiAssistant.isPlaceDependentQuestion("what is the weather today"))
        assertTrue(AiAssistant.isPlaceDependentQuestion("will it rain tomorrow"))
        assertTrue(AiAssistant.isPlaceDependentQuestion("how hot is it outside"))
        assertFalse(AiAssistant.isPlaceDependentQuestion("what is the capital of france"))
        assertFalse(AiAssistant.isPlaceDependentQuestion("how hot is the oven"))
        assertTrue(AiAssistant.hasPlace("weather in dry ridge"))
        assertTrue(AiAssistant.hasPlace("will it rain in cincinnati tomorrow"))
        assertTrue(AiAssistant.hasPlace("weather for dry ridge this weekend"))
        assertFalse(AiAssistant.hasPlace("what is the weather today"))
        assertFalse(AiAssistant.hasPlace("will it rain tomorrow"))
        assertFalse(AiAssistant.hasPlace("weather for today"))
    }

    @Test fun sourceLineOnlyForGrounded() {
        assertEquals("via Google Search", AiAssistant.sourceLine(true))
        assertEquals(null, AiAssistant.sourceLine(false))
    }

    @Test fun groundedTimeoutLongerThanPlain() {
        assertTrue(AiAssistant.GROUNDED_REQUEST_TIMEOUT_MS > AiAssistant.REQUEST_TIMEOUT_MS)
        assertEquals(25_000, AiAssistant.GROUNDED_REQUEST_TIMEOUT_MS)
    }

    @Test fun previewPlaceGuardAndGroundedFlags() {
        val withPlace = AssistantPreview.classify("weather in dry ridge", AssistantPreview.FIXTURE_KEYWORDS, aiGates = true)
        assertEquals(AssistantPreview.Handler.AI, withPlace.handler)
        assertTrue(withPlace.grounded)
        val noPlace = AssistantPreview.classify("what is the weather today", AssistantPreview.FIXTURE_KEYWORDS, aiGates = true)
        assertEquals(AssistantPreview.Handler.AI, noPlace.handler)
        assertFalse(noPlace.grounded)
        assertEquals("For which place?", noPlace.spoken)
        val news = AssistantPreview.classify("what is the latest news", AssistantPreview.FIXTURE_KEYWORDS, aiGates = true)
        assertTrue(news.grounded)
        val general = AssistantPreview.classify("what is the capital of france", AssistantPreview.FIXTURE_KEYWORDS, aiGates = true)
        assertFalse(general.grounded)
        // Gates closed: place-less weather is unmatched, never a guessed forecast.
        val closed = AssistantPreview.classify("what is the weather today", AssistantPreview.FIXTURE_KEYWORDS, aiGates = false)
        assertEquals(AssistantPreview.Handler.UNMATCHED, closed.handler)
    }

    @Test fun privacyNoticeNamesGroundingWithoutNewParty() {
        val notice = AiAssistant.PRIVACY_NOTICE
        assertTrue(notice.contains("Google Search"))
        assertTrue(notice.contains("No new company receives your words"))
        assertTrue(notice.contains("location is never sent"))
    }
}
