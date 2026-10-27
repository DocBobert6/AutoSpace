package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** r86 mirrored-Maps removal: a stale last-nav value falls back, never a dead tile. */
class LastNavTest {

    private val candidates = listOf("com.example.vela/.NavActivity", "com.example.orgmaps/.NavActivity")

    @Test
    fun lastUsedAppWins() {
        assertEquals(candidates[1], resolveLastNav(candidates[1], candidates))
    }

    @Test
    fun firstRunFallsBackToFirstCandidate() {
        assertEquals(candidates[0], resolveLastNav(null, candidates))
    }

    @Test
    fun retiredMirroredTileFallsBackToFirstCandidate() {
        assertEquals(candidates[0], resolveLastNav("maps-mirrored", candidates))
    }

    @Test
    fun unknownValueFallsBackToFirstCandidate() {
        assertEquals(candidates[0], resolveLastNav("com.example.gone/.NavActivity", candidates))
    }

    @Test
    fun noCandidatesResolvesToNull() {
        assertNull(resolveLastNav("maps-mirrored", emptyList()))
        assertNull(resolveLastNav(null, emptyList()))
    }
}
