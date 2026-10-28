package app.autospace.car

/**
 * r118: ONE shared live position for the mini video card and the full
 * video player. The two players are separate ExoPlayer instances with
 * no live handoff (the r75 shape: the mini always started at 0 and
 * the full player only ever offered its Resume prompt), so switching
 * between them either restarted or prompted. This holder is the
 * single place both players write to and read from, in memory only
 * (never persisted — the persisted resume in PlayerResume still owns
 * fresh opens after the app was closed, when this holder is empty).
 *
 * Three kinds of position live here, kept deliberately separate:
 * - LIVE: the position the currently-playing player last reported.
 *   Both players write it from their position polls, so whichever
 *   player is alive keeps it fresh. A fresh open in the same process
 *   reads it and seeks silently — the Resume prompt then appears
 *   ONLY when no live position exists, i.e. after the app was closed.
 * - SWITCH: a one-shot position set by an explicit switch (mini
 *   expand, full-player minimize) and consumed exactly once by the
 *   receiving player at creation. A switch always wins over the live
 *   position, and a switch for another URL never applies.
 * - The PlayerResume mirror in [saveLikeResume]: on dispose, a
 *   meaningful position updates the live position and a finished
 *   position (>= 95% of duration) clears the URL entirely, while a
 *   sub-threshold position leaves the stored live position exactly
 *   as it was — the same three-way rule PlayerResume.save applies
 *   to the persisted entry, so closing before pressing play
 *   (position 0) cannot erase a pending resume candidate.
 *
 * One player alive at a time is enforced by the switch paths, not by
 * this holder: expand clears the mini card before opening the full
 * player, and minimize pauses the full player before navigating
 * Home, so the mini player's creation can never overlap audible
 * playback from the full player even if its release lands a frame
 * later in the same recomposition.
 */
object VideoPositionSync {

    private var liveUrl: String? = null
    private var livePositionMs: Long = 0L
    private var switchUrl: String? = null
    private var switchPositionMs: Long? = null

    /** Live poll write from whichever player is alive. */
    fun update(url: String, positionMs: Long) {
        liveUrl = url
        livePositionMs = positionMs.coerceAtLeast(0L)
    }

    /** The live position for [url], or null when no player has reported one for it. */
    fun livePositionFor(url: String): Long? =
        if (url == liveUrl) livePositionMs else null

    /**
     * An explicit switch to [url] at [positionMs]. Always written,
     * even under 10 s — a switch is a deliberate handoff, not a
     * resume candidate, so the meaningful window does not apply.
     */
    fun setSwitch(url: String, positionMs: Long) {
        switchUrl = url
        switchPositionMs = positionMs.coerceAtLeast(0L)
        update(url, positionMs)
    }

    /**
     * The pending switch position for [url], consumed exactly once:
     * a second call for the same URL returns null. A switch for any
     * other URL is left untouched and returns null.
     */
    fun consumeSwitch(url: String): Long? {
        if (url != switchUrl) return null
        val pos = switchPositionMs
        switchUrl = null
        switchPositionMs = null
        return pos
    }

    /**
     * Dispose-time write mirroring PlayerResume.save's three-way
     * rule (see the class doc): meaningful updates, finished clears
     * the URL, sub-threshold non-finished leaves it untouched.
     */
    fun saveLikeResume(url: String, positionMs: Long, durationMs: Long) {
        if (PlayerResume.isMeaningful(positionMs, durationMs)) {
            update(url, positionMs)
        } else if (durationMs > 0L && positionMs >= durationMs * PlayerResume.FINISHED_FRACTION) {
            clearUrl(url)
        }
        // Sub-threshold, non-finished: deliberately no write.
    }

    /** Clears the live position for [url] but preserves a pending switch for it. */
    fun clearLive(url: String) {
        if (liveUrl == url) {
            liveUrl = null
            livePositionMs = 0L
        }
    }

    /** Clears everything held for [url], live and switch alike. */
    fun clearUrl(url: String) {
        clearLive(url)
        if (switchUrl == url) {
            switchUrl = null
            switchPositionMs = null
        }
    }

    /** Test + teardown helper: clears the whole holder. */
    fun clear() {
        liveUrl = null
        livePositionMs = 0L
        switchUrl = null
        switchPositionMs = null
    }
}
