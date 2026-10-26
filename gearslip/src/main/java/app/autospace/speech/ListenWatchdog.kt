package app.autospace.speech

/**
 * r44 voice-wedge fix: the pure policy behind the listen watchdog.
 *
 * The wedge: one listen parked forever in an unbounded microphone read
 * held the single speech worker thread, so every later listen queued
 * behind it forever and the assistant sat in LISTENING until the
 * process died. The watchdog bounds the whole task: the wiring in
 * [SpeechEngine.listen] arms one per listen, and on expiry it bumps
 * the run (invalidating the wedged task), closes the task's mic, and
 * posts an error so the caller always terminates. The decision itself
 * lives here, without Android, so the unit tests can pin it.
 */
object ListenWatchdogPolicy {
    /**
     * Whole-listen deadline. A legit listen is at most ~15 s of audio
     * (the endpoint's MAX_S) plus a first model load plus the final
     * reread; 45 s clears all of that with room, and is a world away
     * from "forever".
     */
    const val LISTEN_WATCHDOG_MS = 45_000L

    /**
     * System-recognizer deadline (CarAssistant.systemListen). The
     * service adds its own processing on top of a listen, so it gets
     * longer than the bundled engine's watchdog — but it still ends.
     */
    const val SYSTEM_LISTEN_TIMEOUT_MS = 60_000L
}

/**
 * One listen's watchdog: armed at task start, checked once at the
 * deadline by the main thread. Fires at most once; cancelling a
 * finished task never fires. Thread-safe: armed on the worker thread,
 * checked on the main thread.
 */
class ListenWatchdog(
    private val deadlineMs: Long = ListenWatchdogPolicy.LISTEN_WATCHDOG_MS,
    private val onFire: () -> Unit,
) {
    @Volatile private var armedAtMs: Long = -1L
    @Volatile private var fired = false

    fun arm(nowMs: Long) {
        armedAtMs = nowMs
        fired = false
    }

    fun cancel() {
        armedAtMs = -1L
    }

    /** Returns true only on the firing check. */
    fun check(nowMs: Long): Boolean {
        val start = armedAtMs
        if (start < 0 || fired) return false
        if (nowMs - start < deadlineMs) return false
        fired = true
        onFire()
        return true
    }
}

/**
 * Whether a listen task that ended superseded (its run was bumped
 * before it finished) should end its caller with an error instead of
 * silence. Silence is correct when a newer listen replaced it — the
 * new listen drives the caller. An error is owed when no newer listen
 * exists (the bump came from cancel(), so nobody else will ever
 * answer this caller).
 */
internal fun shouldDeliverSupersedeError(taskId: Int, latestListenId: Int, run: Int): Boolean =
    taskId != run && latestListenId == taskId
