package app.autospace.speech

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * r44: the phone microphone's frame queue, behind [SpeechEngine]'s
 * phone mic. A producer thread blocks on AudioRecord (the call with
 * no timeout that used to wedge the whole listen); the consumer polls
 * with a deadline, so the listen loop always gets back to its run
 * check. Polling returns 0 on a timeout — the loop just continues,
 * exactly as it does for a zero-frame car read — and -1 once the mic
 * has ended and the queue has drained.
 */
internal class MicFrameQueue(private val pollMs: Long) {
    private val queue = LinkedBlockingQueue<ShortArray>()
    private val ended = AtomicBoolean(false)

    fun offer(chunk: ShortArray) {
        if (!ended.get()) queue.offer(chunk)
    }

    fun markEnded() {
        ended.set(true)
    }

    /** Copies one chunk into [frame]: its size, 0 on a poll timeout, -1 when ended and drained. */
    fun poll(frame: ShortArray): Int {
        if (ended.get() && queue.isEmpty()) return -1
        val chunk = try {
            queue.poll(pollMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return 0
        } ?: return 0
        val n = minOf(chunk.size, frame.size)
        System.arraycopy(chunk, 0, frame, 0, n)
        return n
    }
}
