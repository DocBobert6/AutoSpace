package app.autospace.car

/**
 * r96: one held voice-navigation request, as pure hold/supersede/drop/fire
 * rules with no assistant machinery, so they are unit-testable on the JVM.
 * [CarAssistant] owns the single instance; [AssistantCommands.navigateCommand]
 * holds the request instead of firing it, and the window lifecycle in
 * [CarAssistant] fires or drops it. Every method is main-thread only,
 * matching every caller (the CarUi collector, the window expiry runnable,
 * cancel()). The held action runs at most once: fire() clears before
 * invoking, so a re-entrant hold during firing cannot double-fire.
 */
class NavHold {
    private var pending: (() -> Unit)? = null

    /** Holds [action], replacing any request still held (a newer navigate supersedes). */
    fun hold(action: () -> Unit) {
        pending = action
    }

    /** Runs the held action once, if any, then clears it. */
    fun fire() {
        val action = pending
        pending = null
        action?.invoke()
    }

    /** Drops the held action, if any; true when something was dropped. */
    fun drop(): Boolean {
        val had = pending != null
        pending = null
        return had
    }

    val hasPending: Boolean get() = pending != null
}
