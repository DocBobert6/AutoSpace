package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The tap / long-press / swipe-down decision for [LetterKey], through the pure
 * [KeyGestureTracker]: one outcome per press, ever.
 */
class KeyGestureTrackerTest {

    private fun tracker() = KeyGestureTracker(swipeThresholdPx = 20f, longPressTimeoutMs = 400L)

    @Test
    fun tapInsertsLetterOnly() {
        val t = tracker()
        t.onDown()
        assertNull(t.onMove(2f, 1f, 100L))
        assertEquals(KeyPressOutcome.TAP, t.onUp())
    }

    @Test
    fun longPressInsertsShortcutOnce() {
        val t = tracker()
        t.onDown()
        assertEquals(KeyPressOutcome.SHORTCUT, t.onTimeout())
        // Lift after a fired shortcut inserts nothing further.
        assertEquals(KeyPressOutcome.NONE, t.onUp())
        // A second timeout cannot fire again either.
        assertNull(t.onTimeout())
    }

    @Test
    fun swipeDownInsertsShortcutOnly() {
        val t = tracker()
        t.onDown()
        assertEquals(KeyPressOutcome.SHORTCUT, t.onMove(25f, 3f, 150L))
        assertEquals(KeyPressOutcome.NONE, t.onUp())
    }

    @Test
    fun swipeDownNeedsDownwardDominance() {
        val t = tracker()
        t.onDown()
        // Mostly sideways: not a swipe, and (r47) a sideways roll inside
        // the key no longer cancels either — it still taps.
        assertNull(t.onMove(10f, 25f, 150L))
        assertEquals(KeyPressOutcome.TAP, t.onUp())
    }

    @Test
    fun driftInsideKeyStillTaps() {
        val t = tracker()
        t.onDown()
        // r47: the old rule cancelled on any past-threshold up/sideways
        // move, which dropped ordinary rolling taps on car screens.
        // Drift that stays on the key is a tap, up or sideways.
        assertNull(t.onMove(-25f, 0f, 150L))
        assertNull(t.onMove(5f, 30f, 200L))
        assertEquals(KeyPressOutcome.TAP, t.onUp())
    }

    @Test
    fun leavingKeyCancels() {
        val t = tracker()
        t.onDown()
        // r47: cancellation is leaving the key, not drifting on it.
        assertNull(t.onMove(5f, 30f, 150L, insideKey = false))
        assertEquals(KeyPressOutcome.NONE, t.onUp())
    }

    @Test
    fun leavingThenReturningStaysCancelled() {
        val t = tracker()
        t.onDown()
        assertNull(t.onMove(5f, 30f, 150L, insideKey = false))
        assertNull(t.onMove(0f, 0f, 200L, insideKey = true))
        assertEquals(KeyPressOutcome.NONE, t.onUp())
    }

    @Test
    fun slowDownwardRollIsTapNotSwipe() {
        val t = tracker()
        t.onDown()
        // r47: a swipe-down is a quick flick. A slow downward roll of
        // the finger is a press, so it taps instead of inserting a hint.
        assertNull(t.onMove(25f, 3f, 350L))
        assertEquals(KeyPressOutcome.TAP, t.onUp())
    }

    @Test
    fun consumedLiftCancels() {
        val t = tracker()
        t.onDown()
        // r47: a lift consumed elsewhere (torn-down stream, another
        // detector — e.g. the passenger gate popping mid-press) inserts
        // nothing, never a phantom letter.
        t.onCancel()
        assertEquals(KeyPressOutcome.NONE, t.onUp())
    }

    @Test
    fun slowDragPastTimeoutIsLongPressNotSwipe() {
        val t = tracker()
        t.onDown()
        // After the timeout, the move path yields to the timeout path.
        assertNull(t.onMove(30f, 0f, 500L))
        assertEquals(KeyPressOutcome.SHORTCUT, t.onTimeout())
        assertEquals(KeyPressOutcome.NONE, t.onUp())
    }

    @Test
    fun jitterBelowThresholdIsStillTap() {
        val t = tracker()
        t.onDown()
        assertNull(t.onMove(19f, 19f, 100L))
        assertNull(t.onMove(-19f, -19f, 200L))
        assertEquals(KeyPressOutcome.TAP, t.onUp())
    }

    @Test
    fun swipeExactlyAtThresholdIsNotEnough() {
        val t = tracker()
        t.onDown()
        assertNull(t.onMove(20f, 0f, 100L))
        assertEquals(KeyPressOutcome.TAP, t.onUp())
    }
}
