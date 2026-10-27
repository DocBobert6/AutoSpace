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
 */
internal fun skipFollowUpWindow(navHeld: Boolean): Boolean = navHeld
