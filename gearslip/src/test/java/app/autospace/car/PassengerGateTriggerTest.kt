package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The r47 passenger-gate trigger: sustained motion before opening, never
 * over an active typing session, stops close an unanswered gate.
 */
class PassengerGateTriggerTest {

    private fun poll(t: PassengerGateTrigger, moving: Boolean, typing: Boolean = false, allowed: Boolean = false) =
        t.onPoll(moving, typing, allowed)

    @Test
    fun driftSpikeNeverOpensGate() {
        val t = PassengerGateTrigger()
        // One bad GPS fix reads as moving for ~2-3 polls in preview.
        assertEquals(GateSignal.NONE, poll(t, true))
        assertEquals(GateSignal.NONE, poll(t, true))
        assertEquals(GateSignal.NONE, poll(t, true))
        // Car "stops" (the spike clears): no gate, no pending state left.
        assertEquals(GateSignal.NONE, poll(t, false))
        // A fresh streak still needs the full count.
        assertEquals(GateSignal.NONE, poll(t, true))
        assertEquals(GateSignal.NONE, poll(t, true))
        assertEquals(GateSignal.NONE, poll(t, true))
        assertEquals(GateSignal.NONE, poll(t, false))
    }

    @Test
    fun sustainedMotionOpensGate() {
        val t = PassengerGateTrigger()
        assertEquals(GateSignal.NONE, poll(t, true))
        assertEquals(GateSignal.NONE, poll(t, true))
        assertEquals(GateSignal.NONE, poll(t, true))
        assertEquals(GateSignal.OPEN, poll(t, true))
    }

    @Test
    fun gateDefersWhileTypingThenOpensWhenTypingEnds() {
        val t = PassengerGateTrigger()
        assertEquals(GateSignal.NONE, poll(t, true))
        assertEquals(GateSignal.NONE, poll(t, true))
        // Streak completes mid-typing: deferred, not dropped, not opened.
        assertEquals(GateSignal.NONE, poll(t, true, typing = true))
        assertEquals(GateSignal.NONE, poll(t, true, typing = true))
        // Typing ends while still moving: the gate appears now.
        assertEquals(GateSignal.OPEN, poll(t, true, typing = false))
    }

    @Test
    fun stopClearsPendingDeferral() {
        val t = PassengerGateTrigger()
        assertEquals(GateSignal.NONE, poll(t, true))
        assertEquals(GateSignal.NONE, poll(t, true))
        assertEquals(GateSignal.NONE, poll(t, true, typing = true))
        assertEquals(GateSignal.NONE, poll(t, true, typing = true))
        // Parked before typing ends: no stale gate when typing ends.
        assertEquals(GateSignal.NONE, poll(t, false, typing = true))
        assertEquals(GateSignal.NONE, poll(t, false, typing = false))
        // Moving again needs a fresh full streak.
        assertEquals(GateSignal.NONE, poll(t, true))
        assertEquals(GateSignal.NONE, poll(t, true))
        assertEquals(GateSignal.NONE, poll(t, true))
        assertEquals(GateSignal.OPEN, poll(t, true))
    }

    @Test
    fun stopClosesOpenGate() {
        val t = PassengerGateTrigger()
        repeat(3) { poll(t, true) }
        assertEquals(GateSignal.OPEN, poll(t, true))
        assertEquals(GateSignal.CLOSE, poll(t, false))
        assertEquals(GateSignal.NONE, poll(t, false))
    }

    @Test
    fun allowanceSuppressesEverything() {
        val t = PassengerGateTrigger()
        repeat(3) { poll(t, true) }
        assertEquals(GateSignal.OPEN, poll(t, true))
        // Allowance granted: no gate, and the open flag clears.
        assertEquals(GateSignal.NONE, poll(t, true, allowed = true))
        assertEquals(GateSignal.NONE, poll(t, true, allowed = true))
        assertEquals(GateSignal.NONE, poll(t, false, allowed = true))
    }
}
