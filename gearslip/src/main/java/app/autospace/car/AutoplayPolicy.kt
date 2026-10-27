package app.autospace.car

/**
 * r62 (A49/A50): the pure autoplay rules, kept free of Android so they
 * are unit-testable without a device.
 *
 * A49: a media app counts as recently used for autoplay for
 * [RECENT_WINDOW_MS] after its last use. Recency is wall-clock time
 * (persisted); the autoplay session below is monotonic time (a single
 * drive), so the two never mix.
 *
 * A50: a pause counts as "stopping autoplay" only when it lands within
 * [STOP_WINDOW_MS] of an autoplay start, and only the first such pause
 * per session counts — the session is cleared on any user pause or
 * play, so a later pause in the same session cannot count twice.
 * [STOPS_TO_OFFER] such stops raise the turn-autoplay-off offer.
 */
object AutoplayPolicy {
    /** A media app counts as recently used for autoplay this long after its last use. */
    const val RECENT_WINDOW_MS = 30L * 24L * 60L * 60L * 1000L

    /** A pause counts as stopping autoplay only this soon after autoplay started it. */
    const val STOP_WINDOW_MS = 2L * 60L * 1000L

    /** Prompt autoplay stops that trigger the turn-autoplay-off offer. */
    const val STOPS_TO_OFFER = 3

    /**
     * True when [lastUsedMs] is a known use within the recent window.
     * Unknown recency (0, e.g. upgraded installs that never recorded
     * one) is NOT recent: autoplay must not start on a guess.
     */
    fun isRecent(lastUsedMs: Long, nowMs: Long): Boolean =
        lastUsedMs > 0L && nowMs - lastUsedMs <= RECENT_WINDOW_MS

    /**
     * True when a pause at [pauseMs] stops the autoplay session that
     * started at [autoplayStartedMs]. No session, or a pause outside
     * the window (or before the start), never counts.
     */
    fun countsAsAutoplayStop(autoplayStartedMs: Long?, pauseMs: Long): Boolean {
        if (autoplayStartedMs == null) return false
        val elapsed = pauseMs - autoplayStartedMs
        return elapsed in 0..STOP_WINDOW_MS
    }

    /** True once enough prompt stops have accumulated to offer turning autoplay off. */
    fun shouldOffer(stops: Int): Boolean = stops >= STOPS_TO_OFFER
}
