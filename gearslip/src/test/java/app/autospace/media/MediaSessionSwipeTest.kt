package app.autospace.media

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** r71 (A28): active-session predicate, swipe order, and wrap navigation. */
class MediaSessionSwipeTest {

    @Test
    fun playingWithTitleIsActive() {
        assertEquals(true, isSwipeSessionActive("Song", PlaybackState.STATE_PLAYING))
    }

    @Test
    fun pausedWithTitleIsActive() {
        assertEquals(true, isSwipeSessionActive("Song", PlaybackState.STATE_PAUSED))
    }

    @Test
    fun bufferingWithTitleIsActive() {
        assertEquals(true, isSwipeSessionActive("Song", PlaybackState.STATE_BUFFERING))
    }

    @Test
    fun emptyTitleIsNotActive() {
        assertEquals(false, isSwipeSessionActive("", PlaybackState.STATE_PLAYING))
    }

    @Test
    fun stoppedIsNotActive() {
        assertEquals(false, isSwipeSessionActive("Song", PlaybackState.STATE_STOPPED))
    }

    @Test
    fun noneIsNotActive() {
        assertEquals(false, isSwipeSessionActive("Song", PlaybackState.STATE_NONE))
    }

    @Test
    fun errorIsNotActive() {
        assertEquals(false, isSwipeSessionActive("Song", PlaybackState.STATE_ERROR))
    }

    @Test
    fun orderSortsByLabelCaseInsensitive() {
        val ordered = orderedSwipeSessions(
            listOf(
                SwipeSession("zeta", "zeta"),
                SwipeSession("alpha", "Alpha"),
                SwipeSession("mid", "Mid"),
            ),
        )
        assertEquals(listOf("alpha", "mid", "zeta"), ordered.map { it.packageName })
    }

    private fun three() = orderedSwipeSessions(
        listOf(
            SwipeSession("b", "B"),
            SwipeSession("a", "A"),
            SwipeSession("c", "C"),
        ),
    )

    @Test
    fun forwardStepsToNext() {
        assertEquals("b", nextSwipeSession("a", three(), forward = true)?.packageName)
    }

    @Test
    fun backwardStepsToPrevious() {
        assertEquals("c", nextSwipeSession("a", three(), forward = false)?.packageName)
    }

    @Test
    fun forwardWrapsAtEnd() {
        assertEquals("a", nextSwipeSession("c", three(), forward = true)?.packageName)
    }

    @Test
    fun backwardWrapsAtStart() {
        assertEquals("c", nextSwipeSession("a", three(), forward = false)?.packageName)
    }

    @Test
    fun singleSessionReturnsNull() {
        assertNull(nextSwipeSession("a", listOf(SwipeSession("a", "A")), forward = true))
    }

    @Test
    fun currentNotFoundReturnsNull() {
        assertNull(nextSwipeSession("gone", three(), forward = true))
    }

    @Test
    fun emptyListReturnsNull() {
        assertNull(nextSwipeSession("a", emptyList(), forward = true))
    }

    @Test
    fun nullCurrentReturnsNull() {
        assertNull(nextSwipeSession(null, three(), forward = true))
    }
}
