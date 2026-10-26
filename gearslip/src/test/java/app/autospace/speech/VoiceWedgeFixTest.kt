package app.autospace.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r44 voice-wedge fix: the pure decision layer behind the watchdog,
 * the superseded-listen rule, and the phone mic's polled reads. The
 * Android wiring (Handler timing, AudioRecord, the recognizer) cannot
 * run on the plain JVM, so these pin the decisions that wiring
 * consults — the same split the r28 MicSelection tests use.
 */
class VoiceWedgeFixTest {

    // --- ListenWatchdog: expiry, cancel, exactly-once --------------------------

    @Test
    fun watchdogDoesNotFireBeforeDeadline() {
        var fired = 0
        val w = ListenWatchdog(deadlineMs = 45_000L) { fired++ }
        w.arm(1_000L)
        assertFalse(w.check(1_000L + 44_999L))
        assertEquals(0, fired)
    }

    @Test
    fun watchdogFiresAtDeadlineExactlyOnce() {
        var fired = 0
        val w = ListenWatchdog(deadlineMs = 45_000L) { fired++ }
        w.arm(1_000L)
        assertTrue(w.check(1_000L + 45_000L))
        assertFalse(w.check(1_000L + 90_000L))
        assertEquals(1, fired)
    }

    @Test
    fun watchdogCancelledNeverFires() {
        var fired = 0
        val w = ListenWatchdog(deadlineMs = 45_000L) { fired++ }
        w.arm(1_000L)
        w.cancel()
        assertFalse(w.check(1_000L + 90_000L))
        assertEquals(0, fired)
    }

    @Test
    fun watchdogReArmResetsDeadline() {
        var fired = 0
        val w = ListenWatchdog(deadlineMs = 45_000L) { fired++ }
        w.arm(1_000L)
        w.cancel()
        w.arm(5_000L)
        assertFalse(w.check(5_000L + 44_999L))
        assertTrue(w.check(5_000L + 45_000L))
        assertEquals(1, fired)
    }

    @Test
    fun watchdogDeadlineConstantIs45Seconds() {
        assertEquals(45_000L, ListenWatchdogPolicy.LISTEN_WATCHDOG_MS)
    }

    // --- shouldDeliverSupersedeError: silence vs error ------------------------

    @Test
    fun supersededWithoutReplacementDeliversError() {
        // Bump came from cancel(): nobody else will answer this caller.
        assertTrue(shouldDeliverSupersedeError(taskId = 5, latestListenId = 5, run = 6))
    }

    @Test
    fun supersededWithReplacementStaysSilent() {
        // A newer listen replaced it: the new listen drives the caller.
        assertFalse(shouldDeliverSupersedeError(taskId = 5, latestListenId = 6, run = 6))
    }

    @Test
    fun liveTaskNeverDeliversSupersedeError() {
        assertFalse(shouldDeliverSupersedeError(taskId = 6, latestListenId = 6, run = 6))
    }

    // --- MicFrameQueue: poll, timeout, drain-then-end --------------------------

    @Test
    fun queueDeliversChunksInOrder() {
        val q = MicFrameQueue(pollMs = 50L)
        q.offer(shortArrayOf(1, 2, 3))
        q.offer(shortArrayOf(4, 5))
        val frame = ShortArray(3)
        assertEquals(3, q.poll(frame))
        assertEquals(1, frame[0].toInt())
        assertEquals(3, frame[2].toInt())
        assertEquals(2, q.poll(frame))
        assertEquals(4, frame[0].toInt())
        assertEquals(5, frame[1].toInt())
    }

    @Test
    fun queuePollTimesOutToZero() {
        val q = MicFrameQueue(pollMs = 50L)
        assertEquals(0, q.poll(ShortArray(3)))
    }

    @Test
    fun queueEndedDrainsThenNegative() {
        val q = MicFrameQueue(pollMs = 50L)
        q.offer(shortArrayOf(7))
        q.markEnded()
        val frame = ShortArray(3)
        assertEquals(1, q.poll(frame))
        assertEquals(7, frame[0].toInt())
        assertEquals(-1, q.poll(frame))
    }

    @Test
    fun queueEndedEmptyIsNegativeImmediately() {
        val q = MicFrameQueue(pollMs = 50L)
        q.markEnded()
        assertEquals(-1, q.poll(ShortArray(3)))
    }

    @Test
    fun queueDropsOffersAfterEnd() {
        val q = MicFrameQueue(pollMs = 50L)
        q.markEnded()
        q.offer(shortArrayOf(9))
        assertEquals(-1, q.poll(ShortArray(3)))
    }
}
