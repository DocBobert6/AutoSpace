package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Test

/** r70: the DND tile state machine is pure booleans, so every branch pins here. */
class QuickTilesPolicyTest {

    @Test
    fun needsAccessWinsOverAnyFilter() {
        assertEquals(DndTileState.NEEDS_ACCESS, dndTileState(false, true))
        assertEquals(DndTileState.NEEDS_ACCESS, dndTileState(false, false))
    }

    @Test
    fun grantedAndSilencedReadsOn() {
        assertEquals(DndTileState.ON, dndTileState(true, true))
    }

    @Test
    fun grantedAndUnsilencedReadsOff() {
        assertEquals(DndTileState.OFF, dndTileState(true, false))
    }
}
