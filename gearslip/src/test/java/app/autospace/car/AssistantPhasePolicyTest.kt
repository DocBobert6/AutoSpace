package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r44 voice-wedge fix: the stale-phase predicate behind the
 * assistant's start() restart. Pure, so it runs on the plain JVM
 * without the assistant object (whose main-thread handler cannot).
 */
class AssistantPhasePolicyTest {

    @Test
    fun listeningStaleOnlyWhenEngineIdleAndOld() {
        assertTrue(isStalePhase(CarAssistant.Phase.LISTENING, listeningActive = false, ageMs = 10_001L))
        assertFalse(isStalePhase(CarAssistant.Phase.LISTENING, listeningActive = true, ageMs = 60_000L))
        assertFalse(isStalePhase(CarAssistant.Phase.LISTENING, listeningActive = false, ageMs = 10_000L))
        assertFalse(isStalePhase(CarAssistant.Phase.LISTENING, listeningActive = false, ageMs = 1_000L))
    }

    @Test
    fun thinkingStaleOnlyWhenOld() {
        assertTrue(isStalePhase(CarAssistant.Phase.THINKING, listeningActive = false, ageMs = 90_001L))
        // THINKING never holds a live listen — the listen ended when
        // the result posted — so engine activity does not save it.
        assertTrue(isStalePhase(CarAssistant.Phase.THINKING, listeningActive = true, ageMs = 120_000L))
        assertFalse(isStalePhase(CarAssistant.Phase.THINKING, listeningActive = false, ageMs = 90_000L))
        assertFalse(isStalePhase(CarAssistant.Phase.THINKING, listeningActive = false, ageMs = 30_000L))
    }

    @Test
    fun idleSpeakingErrorNeverStale() {
        assertFalse(isStalePhase(CarAssistant.Phase.IDLE, listeningActive = false, ageMs = 999_999L))
        assertFalse(isStalePhase(CarAssistant.Phase.SPEAKING, listeningActive = false, ageMs = 999_999L))
        assertFalse(isStalePhase(CarAssistant.Phase.ERROR, listeningActive = false, ageMs = 999_999L))
    }

    @Test
    fun staleBoundsAreTheDocumentedValues() {
        assertEquals(10_000L, STALE_LISTENING_MS)
        assertEquals(90_000L, STALE_THINKING_MS)
    }

    @Test
    fun navHeldSkipsFollowUpWindow() {
        assertTrue(skipFollowUpWindow(navHeld = true))
    }

    @Test
    fun noHeldNavOpensFollowUpWindow() {
        assertFalse(skipFollowUpWindow(navHeld = false))
    }
}
