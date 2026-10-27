package app.autospace.media

import android.media.session.PlaybackState

/**
 * r71 (A28): the floating media card swipes between active media sessions.
 *
 * The session model, grounded: [CarMedia] drives exactly ONE app at a time
 * (one `connecting` MediaApp, one controller), so there is no in-app list of
 * sessions to swipe through. The list comes from the system instead, through
 * the same [android.media.session.MediaSessionManager.getActiveSessions]
 * call [CarMedia.attachToSession] already uses, joined against
 * [MediaCatalog.installed]: only car media apps the car can actually connect
 * to are ever listed, and only when they currently publish a session. A
 * session that was never playing is never invented into the list.
 *
 * PlaybackState's state values are compile-time int constants, so the
 * predicate below stays JVM-testable under unitTests.isReturnDefaultValues.
 */

/** One switchable session: a car media app that currently has an active session. */
data class SwipeSession(val packageName: String, val label: String)

/**
 * Whether a session counts as active, mirroring [NowPlaying.isActive]
 * without needing a NowPlaying: it must have named a track, and its state
 * must not be none, stopped, or errored. Paused and buffering sessions
 * count — they are real sessions with a track, just not playing right now.
 */
fun isSwipeSessionActive(title: String, state: Int): Boolean =
    title.isNotEmpty() && state != PlaybackState.STATE_NONE &&
        state != PlaybackState.STATE_STOPPED && state != PlaybackState.STATE_ERROR

/** Swipe order: the catalog's label order, so the list is stable across swipes. */
fun orderedSwipeSessions(sessions: List<SwipeSession>): List<SwipeSession> =
    sessions.sortedBy { it.label.lowercase() }

/**
 * The session a swipe lands on, or null when there is nowhere to go: fewer
 * than two sessions, no current app, or the current app is not in the list
 * (its session ended between render and swipe — never jump to an arbitrary
 * app). Forward = swipe left, the pager convention; backward = swipe right.
 * The list wraps: with two sessions a swipe always has somewhere to go, and
 * a clamped list would leave one direction dead half the time.
 */
fun nextSwipeSession(
    currentPackage: String?,
    ordered: List<SwipeSession>,
    forward: Boolean,
): SwipeSession? {
    if (ordered.size < 2 || currentPackage == null) return null
    val index = ordered.indexOfFirst { it.packageName == currentPackage }
    if (index < 0) return null
    val step = if (forward) 1 else -1
    return ordered[(index + step + ordered.size) % ordered.size]
}
