package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r96: the held-navigation rules, without any assistant machinery: hold
 * replaces, fire runs once, drop reports whether anything was held.
 */
class NavHoldTest {

    @Test
    fun startsWithNothingPending() {
        assertFalse(NavHold().hasPending)
    }

    @Test
    fun holdMakesPending() {
        val hold = NavHold()
        hold.hold {}
        assertTrue(hold.hasPending)
    }

    @Test
    fun holdReplacesPreviousSoNewNavigateSupersedes() {
        val hold = NavHold()
        var fired = ""
        hold.hold { fired = "old" }
        hold.hold { fired = "new" }
        hold.fire()
        assertEquals("new", fired)
    }

    @Test
    fun fireRunsOnce() {
        val hold = NavHold()
        var count = 0
        hold.hold { count++ }
        hold.fire()
        hold.fire()
        assertEquals(1, count)
    }

    @Test
    fun fireClearsPending() {
        val hold = NavHold()
        hold.hold {}
        hold.fire()
        assertFalse(hold.hasPending)
    }

    @Test
    fun dropReportsHeldAndClears() {
        val hold = NavHold()
        hold.hold {}
        assertTrue(hold.drop())
        assertFalse(hold.hasPending)
    }

    @Test
    fun dropReportsEmptyAndFireAfterDropRunsNothing() {
        val hold = NavHold()
        assertFalse(hold.drop())
        var count = 0
        hold.hold { count++ }
        hold.drop()
        hold.fire()
        assertEquals(0, count)
    }
}
