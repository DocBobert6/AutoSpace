package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit round R3 (r165): assistant correctness + session tail.
 * Everything here pins a pure layer on the plain JVM. Wiring that needs
 * a Context, a live listen phase, or an AudioTrack (infoCommand's fetch
 * timeout, reply()'s callback plumbing, the CarUi collector) is verified
 * by code review plus the pure-layer pins below, and by device checks —
 * not pretended to be unit-pinned.
 */
class AssistantCorrectnessTest {

    // --- §6 N1: narrowed local weather trigger ---

    @Test fun howHotNeedsOutsideToken() {
        assertFalse(AiAssistant.isLocalWeatherTrigger("how hot is the oven"))
        assertFalse(AiAssistant.isLocalWeatherTrigger("how cold is the fridge"))
        assertTrue(AiAssistant.isLocalWeatherTrigger("how hot is it outside"))
        assertTrue(AiAssistant.isLocalWeatherTrigger("how hot is it out"))
        assertTrue(AiAssistant.isLocalWeatherTrigger("how cold is it outside today"))
        assertTrue(AiAssistant.isLocalWeatherTrigger("how hot out"))
    }

    @Test fun weatherIsATokenNotASubstring() {
        assertTrue(AiAssistant.isLocalWeatherTrigger("what's the weather"))
        assertTrue(AiAssistant.isLocalWeatherTrigger("weather in dry ridge"))
        assertTrue(AiAssistant.isLocalWeatherTrigger("what is the weather today"))
        assertTrue(AiAssistant.isLocalWeatherTrigger("tell me the weather"))
        // Substring inside another word no longer claims the branch.
        assertFalse(AiAssistant.isLocalWeatherTrigger("weathering the storm"))
        assertFalse(AiAssistant.isLocalWeatherTrigger("weathers"))
        // Honest N1 limit, pinned so it cannot drift silently: a standalone
        // "weather" token still claims idioms and product names that contain
        // one. Excluding those would need a content-purity rule that would
        // also break genuine bare questions ("tell me the weather" above),
        // so they stay claimed — see isLocalWeatherTrigger's KDoc.
        assertTrue(AiAssistant.isLocalWeatherTrigger("under the weather"))
        assertTrue(AiAssistant.isLocalWeatherTrigger("weather stripping"))
    }

    @Test fun otherWeatherPhrasesUnchanged() {
        assertTrue(AiAssistant.isLocalWeatherTrigger("is it going to rain"))
        assertTrue(AiAssistant.isLocalWeatherTrigger("is it gonna rain tomorrow"))
        assertTrue(AiAssistant.isLocalWeatherTrigger("temperature outside"))
        // Not in the branch: these reach the AI place rule, as before.
        assertFalse(AiAssistant.isLocalWeatherTrigger("what's the forecast"))
        assertFalse(AiAssistant.isLocalWeatherTrigger("will it rain"))
        assertFalse(AiAssistant.isLocalWeatherTrigger("how humid is it"))
    }

    // --- §6 N7: preview mirrors the narrowed trigger ---

    @Test fun previewWeatherIsBuiltinAndNarrowed() {
        val withPlace = AssistantPreview.classify("weather in dry ridge", AssistantPreview.FIXTURE_KEYWORDS, aiGates = true)
        assertEquals(AssistantPreview.Handler.BUILTIN, withPlace.handler)
        val bare = AssistantPreview.classify("what is the weather today", AssistantPreview.FIXTURE_KEYWORDS, aiGates = true)
        assertEquals(AssistantPreview.Handler.BUILTIN, bare.handler)
        val hotOutside = AssistantPreview.classify("how hot is it outside", AssistantPreview.FIXTURE_KEYWORDS, aiGates = true)
        assertEquals(AssistantPreview.Handler.BUILTIN, hotOutside.handler)
        // Narrowed out: these fall through to the AI fallback, as live does.
        val oven = AssistantPreview.classify("how hot is the oven", AssistantPreview.FIXTURE_KEYWORDS, aiGates = true)
        assertEquals(AssistantPreview.Handler.AI, oven.handler)
        val weathering = AssistantPreview.classify("weathering the storm", AssistantPreview.FIXTURE_KEYWORDS, aiGates = true)
        assertEquals(AssistantPreview.Handler.AI, weathering.handler)
        // Local weather needs no AI gate, exactly like live infoCommand.
        val closed = AssistantPreview.classify("weather in dry ridge", AssistantPreview.FIXTURE_KEYWORDS, aiGates = false)
        assertEquals(AssistantPreview.Handler.BUILTIN, closed.handler)
    }

    // --- §6 N4 + T6: session expiry, truncation, blank reporting ---

    @Test fun recordPastBoundaryStartsFreshInsteadOfResurrecting() {
        val history = ConversationHistory()
        history.record("first question", "first answer", 1000L, fromAi = true)
        history.record("second question", "second answer", 1000L + ConversationHistory.SESSION_TIMEOUT_MS + 1L, fromAi = true)
        val turns = history.snapshot(1000L + ConversationHistory.SESSION_TIMEOUT_MS + 2L)
        assertEquals(1, turns.size)
        assertEquals("second question", turns[0].user)
    }

    @Test fun recordWithinBoundaryExtends() {
        val history = ConversationHistory()
        history.record("first question", "first answer", 1000L, fromAi = true)
        // Exactly at the boundary is NOT expired (strict-greater check).
        history.record("second question", "second answer", 1000L + ConversationHistory.SESSION_TIMEOUT_MS, fromAi = true)
        val turns = history.snapshot(1000L + ConversationHistory.SESSION_TIMEOUT_MS + 1L)
        assertEquals(2, turns.size)
        assertEquals("first question", turns[0].user)
        assertEquals("second question", turns[1].user)
    }

    @Test fun oversizedSingleTurnTruncatesInsteadOfDropping() {
        val history = ConversationHistory()
        val big = "y".repeat(ConversationHistory.MAX_HISTORY_CHARS + 5000)
        assertTrue(history.record("question", big, 1000L, fromAi = true))
        val turns = history.snapshot(2000L)
        assertEquals(1, turns.size)
        assertEquals("question", turns[0].user)
        assertEquals(ConversationHistory.MAX_HISTORY_CHARS, turns[0].user.length + turns[0].assistant.length)
        assertTrue(turns[0].assistant.startsWith("yyyy"))
    }

    @Test fun recordReportsBlankAsNotAppended() {
        val history = ConversationHistory()
        assertFalse(history.record("", "answer", 1000L))
        assertFalse(history.record("question", "", 1000L))
        assertFalse(history.recordBuiltin("pause", "Paused.", 1000L))
        assertTrue(history.isEmpty())
        // T6: recordBuiltin reports the record's own result, so a blank
        // built-in inside a live session also reports false.
        history.record("tell me a joke", "Why did the chicken cross the road?", 1000L, fromAi = true)
        assertFalse(history.recordBuiltin("pause", "", 2000L))
        assertEquals(1, history.snapshot(3000L).size)
    }

    // --- §6 N5 (strict): held-nav firing rule ---

    @Test fun heldNavFiresOnlyOnOwnSuccessfulCompletion() {
        assertTrue(heldNavFiresOnCompletion(hasPending = true, ownedByCompletingTurn = true, speechSucceeded = true))
        assertFalse(heldNavFiresOnCompletion(hasPending = false, ownedByCompletingTurn = true, speechSucceeded = true))
        assertFalse(heldNavFiresOnCompletion(hasPending = true, ownedByCompletingTurn = false, speechSucceeded = true))
        assertFalse(heldNavFiresOnCompletion(hasPending = true, ownedByCompletingTurn = true, speechSucceeded = false))
        assertFalse(heldNavFiresOnCompletion(hasPending = false, ownedByCompletingTurn = false, speechSucceeded = false))
    }

    // --- §6 N3: playback-start gate ---

    @Test fun playbackStartGateFiresExactlyOnce() {
        var calls = 0
        val gate = PlaybackStartGate { calls++ }
        gate.onAudioStarted()
        gate.onAudioStarted()
        assertEquals(1, calls)
    }

    // --- §2 CLEAN-N13: toggle refuses a missing "on" field ---

    @Test fun toggleWithoutOnFieldIsRefused() {
        val parsed = AiAssistant.parseModelReply("""{"action": "toggle", "setting": "phone_mic"}""", "toggle phone mic")
        assertTrue(parsed is AiAssistant.ParsedReply.Refused)
        val on = AiAssistant.parseModelReply("""{"action": "toggle", "setting": "phone_mic", "on": true}""", "turn phone mic on")
        assertTrue(on is AiAssistant.ParsedReply.Proposal)
        val off = AiAssistant.parseModelReply("""{"action": "toggle", "setting": "phone_mic", "on": false}""", "turn phone mic off")
        assertTrue(off is AiAssistant.ParsedReply.Proposal)
    }
}
