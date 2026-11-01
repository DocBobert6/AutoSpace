package app.autospace.car

/**
 * r44 voice-wedge fix: when a parked assistant phase counts as stale
 * and a new start() may restart it instead of silently no-op-ing
 * forever. Pure, so the unit tests can pin it without the assistant
 * object (whose main-thread handler cannot run on the JVM).
 *
 * LISTENING with no live listen behind it means the listen task
 * ended without delivering (superseded, or a dead consumer) — a real
 * listen always holds the engine's listening flag from task start,
 * and the only gap (task queued behind another) is seconds, so 10 s
 * is safe. THINKING covers the AI call (25 s read timeout) plus
 * command handling, so it gets 90 s; anything older has no live work
 * behind it. Bounds are strict-greater: exactly at the bound is not
 * stale yet.
 */
internal const val STALE_LISTENING_MS = 10_000L
internal const val STALE_THINKING_MS = 90_000L

internal fun isStalePhase(phase: CarAssistant.Phase, listeningActive: Boolean, ageMs: Long): Boolean =
    when (phase) {
        CarAssistant.Phase.LISTENING -> !listeningActive && ageMs > STALE_LISTENING_MS
        CarAssistant.Phase.THINKING -> ageMs > STALE_THINKING_MS
        else -> false
    }

/**
 * r98 nav-window exemption: whether a reply completion skips the
 * post-response listen window. True exactly when a navigation request is
 * held — a navigation turn ends at its own reply completion (fire at once,
 * then IDLE), so no live window exists for car noise to cancel through.
 * False for every other turn, which opens the window exactly as before.
 * Pure, so the unit tests pin it without the assistant object.
 *
 * Audit §6 N5 (strict) qualification: skipping the window is NOT firing
 * the hold. Firing additionally requires the hold to belong to this
 * completing turn (see [heldNavFiresOnCompletion]) — a stale hold is
 * dropped at completion, never fired.
 */
internal fun skipFollowUpWindow(navHeld: Boolean): Boolean = navHeld

/**
 * Audit §6 N5 (strict): whether a held navigation fires at a reply
 * completion. All three must hold: a request is pending, it was armed by
 * the turn now completing, and that turn's speech completed successfully.
 * A hold left over from an earlier turn — a non-navigation turn
 * completing, or any carried hold — drops instead of firing, and a
 * blank/failed/unplayable reply drops it too (those paths never reach a
 * successful completion). Pure, so the unit tests pin the rule without
 * the assistant object.
 */
internal fun heldNavFiresOnCompletion(
    hasPending: Boolean,
    ownedByCompletingTurn: Boolean,
    speechSucceeded: Boolean,
): Boolean = hasPending && ownedByCompletingTurn && speechSucceeded

/**
 * Audit §6 N3: exactly-once gate for the "playback started" callback
 * [CarAssistant.reply] reports to its caller (the session record). Audio
 * start is asynchronous — the track starts on the playback thread, and a
 * failed render never starts one — so the record must wait for the start
 * signal, not for [CarAssistant.reply] to return. The gate fires the
 * callback at most once, and a failure path simply never calls it, so an
 * unheard answer never enters the session. Pure, so the unit tests pin
 * it without the voice engine.
 */
internal class PlaybackStartGate(private val onStarted: () -> Unit) {
    private var fired = false

    /** Call when audio actually starts playing; fires the callback once. */
    fun onAudioStarted() {
        if (!fired) {
            fired = true
            onStarted()
        }
    }
}
