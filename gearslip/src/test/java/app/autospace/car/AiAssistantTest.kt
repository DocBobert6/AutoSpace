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
        assertEquals(8, result.skipped)
        assertEquals(AssistantPreview.CANNED_CASES.size - 8, result.passed)
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

    // --- r41: grounded answers get a glyph beside the badge, never a text line ---
    // Replaces sourceLineOnlyForGrounded: sourceLine itself was removed with the
    // text line (the overlay was its only consumer; preview never used it), and
    // the grounded rule now lives in groundedGlyph, provider-specific.

    @Test fun groundedGlyphIsProviderSpecific() {
        assertEquals(GroundedGlyph.GLOBE, groundedGlyph(true, true, AiAssistant.Provider.GROQ))
        assertEquals(GroundedGlyph.GOOGLE_G, groundedGlyph(true, true, AiAssistant.Provider.GEMINI))
    }

    @Test fun groundedGlyphHiddenUnlessAiAndGrounded() {
        assertEquals(GroundedGlyph.NONE, groundedGlyph(true, false, AiAssistant.Provider.GROQ))
        assertEquals(GroundedGlyph.NONE, groundedGlyph(true, false, AiAssistant.Provider.GEMINI))
        assertEquals(GroundedGlyph.NONE, groundedGlyph(false, true, AiAssistant.Provider.GROQ))
        assertEquals(GroundedGlyph.NONE, groundedGlyph(false, true, AiAssistant.Provider.GEMINI))
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

    // --- r38 model re-pin: current stable Flash + 404 as its own outcome ---

    @Test fun modelIdIsCurrentStableFlash() {
        assertEquals("gemini-3.8-flash", AiAssistant.MODEL_ID)
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent",
            AiAssistant.GENERATE_URL,
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash",
            AiAssistant.MODEL_URL,
        )
    }

    @Test fun httpCodeMappingPins404AsModelUnavailable() {
        assertEquals(GeminiClient.Result.ModelUnavailable, GeminiClient.resultForHttpCode(404))
        assertEquals(GeminiClient.Result.InvalidKey, GeminiClient.resultForHttpCode(400))
        assertEquals(GeminiClient.Result.InvalidKey, GeminiClient.resultForHttpCode(401))
        assertEquals(GeminiClient.Result.InvalidKey, GeminiClient.resultForHttpCode(403))
        assertEquals(GeminiClient.Result.Quota, GeminiClient.resultForHttpCode(429))
        assertEquals(GeminiClient.Result.Unavailable, GeminiClient.resultForHttpCode(500))
        assertEquals(GeminiClient.Result.Unavailable, GeminiClient.resultForHttpCode(503))
    }

    @Test fun modelUnavailableMessageIsDistinctFromGeneric() {
        assertEquals("That AI model isn't available.", AiAssistant.failureMessage(GeminiClient.Result.ModelUnavailable))
        assertEquals("AI answer isn't available right now.", AiAssistant.failureMessage(GeminiClient.Result.InvalidKey))
        // r40: quota gets its own retryable message (absorbs the pending 429 proposal).
        assertEquals("AI answers are rate-limited right now — try again in a bit.", AiAssistant.failureMessage(GeminiClient.Result.Quota))
        assertEquals("AI answer isn't available right now.", AiAssistant.failureMessage(GeminiClient.Result.Unavailable))
    }

    // --- r40 Groq provider: default, model config, errors, two-call grounding, redaction ---

    @Test fun providerDefaultIsGroqAndGeminiIsExperimental() {
        assertEquals(AiAssistant.Provider.GROQ, AiAssistant.Provider.DEFAULT)
        assertFalse(AiAssistant.Provider.GROQ.isExperimental)
        assertTrue(AiAssistant.Provider.GEMINI.isExperimental)
        assertEquals("Groq", AiAssistant.Provider.GROQ.displayName)
        assertEquals("Gemini", AiAssistant.Provider.GEMINI.displayName)
    }

    @Test fun groqModelConfigPinnedWithFallback() {
        assertEquals("openai/gpt-oss-20b", GroqClient.MODEL_ID)
        assertEquals(listOf("openai/gpt-oss-120b"), GroqClient.MODEL_FALLBACKS)
        // No Llama model may ever be pinned: they left Free/Developer tiers in Aug 2026.
        (listOf(GroqClient.MODEL_ID) + GroqClient.MODEL_FALLBACKS).forEach { id ->
            assertFalse(id.lowercase().contains("llama"))
        }
        assertEquals("https://api.groq.com/openai/v1/chat/completions", GroqClient.CHAT_URL)
        assertEquals("https://api.groq.com/openai/v1/models", GroqClient.MODELS_URL)
        assertEquals("low", GroqClient.REASONING_EFFORT)
    }

    @Test fun groqHttpCodeMapping() {
        assertEquals(GroqClient.Result.InvalidKey, GroqClient.resultForHttpCode(401))
        assertEquals(GroqClient.Result.ModelUnavailable, GroqClient.resultForHttpCode(404))
        assertEquals(GroqClient.Result.Quota, GroqClient.resultForHttpCode(429))
        // Deliberately unlike Gemini: a Groq 400 is a bad request, not a bad key.
        assertEquals(GroqClient.Result.Unavailable, GroqClient.resultForHttpCode(400))
        assertEquals(GroqClient.Result.Unavailable, GroqClient.resultForHttpCode(403))
        assertEquals(GroqClient.Result.Unavailable, GroqClient.resultForHttpCode(500))
    }

    @Test fun groqFailureMessagesMatchGeminiMapping() {
        assertEquals("That AI model isn't available.", AiAssistant.failureMessage(GroqClient.Result.ModelUnavailable))
        assertEquals("AI answers are rate-limited right now — try again in a bit.", AiAssistant.failureMessage(GroqClient.Result.Quota))
        assertEquals("AI answer isn't available right now.", AiAssistant.failureMessage(GroqClient.Result.InvalidKey))
        assertEquals("AI answer isn't available right now.", AiAssistant.failureMessage(GroqClient.Result.Unavailable))
    }

    @Test fun groqSearchBodyHasBrowserSearchAndNoStructuredOutput() {
        val body = GroqClient.buildSearchRequestBody("what is the weather in dry ridge")
        assertTrue(body.contains("\"tools\":[{\"type\":\"browser_search\"}]"))
        assertFalse(body.contains("response_format"))
        assertTrue(body.contains("\"reasoning_effort\":\"low\""))
        assertTrue(body.contains("\"include_reasoning\":false"))
        // gpt-oss does not support reasoning_format: it must never be sent.
        assertFalse(body.contains("reasoning_format"))
        assertTrue(body.contains("\"max_completion_tokens\":600"))
        assertTrue(body.contains("\"model\":\"openai/gpt-oss-20b\""))
        val parsed = JsonMini.parse(body) as JsonMini.Value.Obj
        val contents = (parsed.objField("messages") as JsonMini.Value.Arr).list
        val user = (contents[1] as JsonMini.Value.Obj).objField("content")?.asString()
        assertEquals("what is the weather in dry ridge", user)
    }

    @Test fun groqAnswerBodyInjectsResultsAndHasNoTools() {
        val body = GroqClient.buildAnswerRequestBody("what is the weather in dry ridge", "Sunny, 72 degrees.")
        assertTrue(body.contains("Web search results:"))
        assertTrue(body.contains("Sunny, 72 degrees."))
        assertTrue(body.contains("User question: what is the weather in dry ridge"))
        // The word browser_search legitimately appears: the system prompt's action
        // schema names it. What must be absent is a tools FIELD on the request.
        assertTrue(body.contains("browser_search"))
        assertFalse(body.contains("\"tools\""))
        assertTrue(body.contains("\"max_completion_tokens\":300"))
        val plain = GroqClient.buildAnswerRequestBody("tell me a joke", null)
        assertTrue(plain.contains("tell me a joke"))
        assertFalse(plain.contains("Web search results:"))
    }

    @Test fun groqExtractContentIgnoresReasoning() {
        val withReasoning = """{"choices": [{"message": {"role": "assistant", "content": "Hello", "reasoning": "secret chain of thought"}}]}"""
        assertEquals("Hello", GroqClient.extractContent(withReasoning))
        assertEquals(null, GroqClient.extractContent("""{"choices": [{"message": {"role": "assistant"}}]}"""))
        assertEquals(null, GroqClient.extractContent("""{"choices": []}"""))
        assertEquals(null, GroqClient.extractContent("not json"))
    }

    @Test fun groqCitationsCounted() {
        assertEquals(2, GroqClient.countCitations("a 〖1†L1-L2〗 b 〖2†L3〗 c"))
        assertEquals(0, GroqClient.countCitations("no markers here"))
    }

    @Test fun groqKeyTokenScrubbedFromLogLines() {
        val line = "assistant: something gsk_abc1234567890XYZ_abcdefg happened"
        val scrubbed = AiAssistant.AiRedaction.scrub(line)
        assertFalse(scrubbed.contains("gsk_abc1234567890XYZ_abcdefg"))
        assertTrue(scrubbed.contains("[api key redacted]"))
        assertTrue(AiAssistant.AiRedaction.mustDrop(line))
        assertEquals("assistant: nothing secret here", AiAssistant.AiRedaction.scrub("assistant: nothing secret here"))
        assertFalse(AiAssistant.AiRedaction.mustDrop("assistant: nothing secret here"))
    }

    @Test fun groqPrivacyNoticeStatesNoTraining() {
        val notice = AiAssistant.GROQ_PRIVACY_NOTICE
        assertTrue(notice.contains("does not train"))
        assertTrue(notice.contains("does not permanently retain"))
        assertFalse(notice.contains("Google"))
        assertTrue(notice.contains("location is never sent"))
    }

    @Test fun previewProviderDetailNamesGroq() {
        val groq = AssistantPreview.classify(
            "what is the capital of france", AssistantPreview.FIXTURE_KEYWORDS,
            aiGates = true, provider = AiAssistant.Provider.GROQ,
        )
        assertTrue(groq.detail.contains("Groq"))
        val gemini = AssistantPreview.classify(
            "what is the capital of france", AssistantPreview.FIXTURE_KEYWORDS,
            aiGates = true, provider = AiAssistant.Provider.GEMINI,
        )
        assertTrue(gemini.detail.contains("Gemini"))
    }

    // --- r41: no spoken "From AI:" prefix, ever (user feedback on r40) ---
    // No test pinned the old first-answer rule (verified by grep when it was
    // removed), so these pin the new rule instead of silently relying on the
    // deletion: the spoken answer is the capped text, and the old machinery
    // must stay deleted.

    @Test fun spokenAiAnswerIsCappedTextWithNoPrefix() {
        assertEquals("The score was 35 to 13.", AiAssistant.capAnswer("The score was 35 to 13."))
        val capped = AiAssistant.capAnswer((1..80).joinToString(" ") { "word" })
        assertFalse(capped.startsWith("From AI:"))
        assertTrue(capped.endsWith("That's the short version."))
    }

    @Test fun spokenPrefixMachineryIsGone() {
        // If any of these members come back, the removal has to be
        // re-decided, not silently re-shipped.
        val methods = AiAssistant::class.java.declaredMethods.map { it.name }.toSet()
        val fields = AiAssistant::class.java.declaredFields.map { it.name }.toSet()
        assertFalse("needsAiPrefix must stay deleted", "needsAiPrefix" in methods)
        assertFalse("markAiAnswerSpoken must stay deleted", "markAiAnswerSpoken" in methods)
        assertFalse("resetSession must stay deleted", "resetSession" in methods)
        assertFalse("firstAnswerSpoken must stay deleted", "firstAnswerSpoken" in fields)
    }
}
