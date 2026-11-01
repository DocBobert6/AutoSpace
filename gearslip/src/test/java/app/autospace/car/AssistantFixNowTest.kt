package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit round R2 (§6 F1/F2/F4/F5) tests. Everything here runs on the JVM: the
 * shared ambiguous check and the emergency predicate are pure, and the preview
 * classifier is a dry run. The Android-only wiring — handle()'s F1/F2 positions,
 * setEnabled(false)/removeKey clearing the session, startOnMainThread's
 * SPEAKING-entry clear, and the CarUi discard branch — is covered by code review
 * plus the pure-layer pins below, not by a unit test that cannot see a Context,
 * a live listen phase, or a Compose collector.
 */
class AssistantFixNowTest {

    // --- §6 F2: the shared ambiguous check ---

    @Test fun twoActionsJoinedByAndAreAmbiguous() {
        assertTrue(AssistantPreview.isAmbiguous("call mom and navigate home"))
        assertTrue(AssistantPreview.isAmbiguous("navigate home and call mom"))
        assertTrue(AssistantPreview.isAmbiguous("text mom that hi and call dad"))
        assertTrue(AssistantPreview.isAmbiguous("search for pizza and text mom that hi"))
        assertTrue(AssistantPreview.isAmbiguous("call mom and message dad that hi"))
    }

    @Test fun andInsideANameIsNeverAmbiguous() {
        assertFalse(AssistantPreview.isAmbiguous("navigate to fish and chips"))
        assertFalse(AssistantPreview.isAmbiguous("call dan and dave"))
        assertFalse(AssistantPreview.isAmbiguous("navigate to rock and roll hall of fame"))
        assertFalse(AssistantPreview.isAmbiguous("navigate to sand and sea"))
    }

    @Test fun singleActionsAndBareAndAreNeverAmbiguous() {
        assertFalse(AssistantPreview.isAmbiguous("call mom"))
        assertFalse(AssistantPreview.isAmbiguous("navigate home"))
        assertFalse(AssistantPreview.isAmbiguous("call mom and dad"))
        assertFalse(AssistantPreview.isAmbiguous("fish and chips"))
        assertFalse(AssistantPreview.isAmbiguous(""))
        assertFalse(AssistantPreview.isAmbiguous("call mom and"))
    }

    @Test fun previewAndLiveShareOneCheck() {
        val ambiguous = AssistantPreview.classify(
            "call mom and navigate home", AssistantPreview.FIXTURE_KEYWORDS, aiGates = true,
        )
        assertEquals(AssistantPreview.Handler.AMBIGUOUS, ambiguous.handler)
        assertEquals(
            "That's two things. Say the first one on its own and confirm it, then the second.",
            ambiguous.spoken,
        )
        val place = AssistantPreview.classify(
            "navigate to fish and chips", AssistantPreview.FIXTURE_KEYWORDS, aiGates = true,
        )
        assertEquals(AssistantPreview.Handler.BUILTIN, place.handler)
        val contact = AssistantPreview.classify(
            "call dan and dave", AssistantPreview.FIXTURE_KEYWORDS, aiGates = true,
        )
        assertEquals(AssistantPreview.Handler.BUILTIN, contact.handler)
    }

    // --- §6 F1: the vague-emergency predicate (handle() wires it after the local
    // chain and before the AI fallback; the predicate itself is the pure seam) ---

    @Test fun vagueEmergencyPhrasingMatches() {
        assertTrue(AiAssistant.isEmergencyLike("i need an ambulance"))
        assertTrue(AiAssistant.isEmergencyLike("this is an emergency"))
        assertTrue(AiAssistant.isEmergencyLike("call the police"))
        assertTrue(AiAssistant.isEmergencyLike("someone get help"))
        assertTrue(AiAssistant.isEmergencyLike("call nine one one"))
    }

    @Test fun ordinaryUtterancesDoNotMatchEmergency() {
        assertFalse(AiAssistant.isEmergencyLike("call mom"))
        assertFalse(AiAssistant.isEmergencyLike("what is the weather today"))
        assertFalse(AiAssistant.isEmergencyLike("navigate home"))
        assertFalse(AiAssistant.isEmergencyLike(""))
    }

    @Test fun previewOffersVagueEmergencyLocally() {
        val trace = AssistantPreview.classify(
            "i need an ambulance", AssistantPreview.FIXTURE_KEYWORDS, aiGates = false,
        )
        assertEquals(AssistantPreview.Handler.EMERGENCY_OFFER, trace.handler)
        assertEquals(
            "Do you want me to start an emergency call? That needs your confirmation.",
            trace.spoken,
        )
    }

    // --- §6 F4: the pure session layer clear() obeys (setEnabled(false) and
    // active-provider removeKey call this; those need a Context, so the wiring is
    // code-reviewed and the pure clear is pinned here) ---

    @Test fun sessionClearEmptiesAndRecordAfterClearStartsEmpty() {
        val history = ConversationHistory()
        history.record("hello", "hi there", nowMs = 1000L, fromAi = true)
        assertFalse(history.isEmpty())
        history.clear()
        assertTrue(history.isEmpty())
        assertTrue(history.snapshot(nowMs = 2000L).isEmpty())
        // A built-in after a clear must not join a session that no longer exists.
        assertFalse(history.recordBuiltin("volume up", "Volume up.", nowMs = 3000L))
    }
}
