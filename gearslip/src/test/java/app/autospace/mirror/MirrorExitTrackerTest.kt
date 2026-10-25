package app.autospace.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorExitTrackerTest {

    // A portrait phone in a landscape car, Fit: content is the centred
    // 214px strip, the rest is padding (map returns null there).
    private val fitMap: (Float, Float) -> Pair<Float, Float>? = { x, _ ->
        if (x in 293f..507f) Pair(x, 240f) else null
    }
    // Fill: everything maps (the frame is fully covered).
    private val fillMap: (Float, Float) -> Pair<Float, Float>? = { x, y -> Pair(x, y) }

    @Test
    fun fillExitButtonTapStops() {
        val t = MirrorExitTracker()
        assertTrue(t.down(780f, 20f, true, fillMap) is MirrorExitTracker.Decision.Swallow)
        val up = t.up(780f, 20f, true, fillMap)
        assertEquals(MirrorExitTracker.Decision.Stop(MirrorExitTracker.StopReason.EXIT_BUTTON), up)
    }

    @Test
    fun fillContentTapRelays() {
        val t = MirrorExitTracker()
        val down = t.down(400f, 240f, false, fillMap)
        assertEquals(MirrorExitTracker.Decision.Relay(MirrorExitTracker.RelayAction.DOWN, 400f, 240f), down)
        val up = t.up(400f, 240f, false, fillMap)
        assertEquals(MirrorExitTracker.Decision.Relay(MirrorExitTracker.RelayAction.UP, 400f, 240f), up)
    }

    @Test
    fun fillDragFromContentIntoExitCornerDoesNotStop() {
        // The old bug: any UP in the corner stopped, even for a drag
        // that started on content. Now the drag completes as a relay
        // (DOWN/MOVE/UP all reach the phone) and never stops — in Fill
        // every point maps, so the UP lands on the phone at the lift
        // point, exactly like any other swipe ending there.
        val t = MirrorExitTracker()
        t.down(400f, 240f, false, fillMap)
        t.move(700f, 100f, false, fillMap)
        val up = t.up(780f, 20f, true, fillMap)
        assertEquals(MirrorExitTracker.Decision.Relay(MirrorExitTracker.RelayAction.UP, 780f, 20f), up)
    }

    @Test
    fun fillDragOutOfExitButtonDoesNotStop() {
        val t = MirrorExitTracker()
        t.down(780f, 20f, true, fillMap)
        t.move(400f, 240f, false, fillMap)
        val up = t.up(400f, 240f, false, fillMap)
        assertTrue(up is MirrorExitTracker.Decision.Swallow)
    }

    @Test
    fun fitPaddingTapStops() {
        val t = MirrorExitTracker()
        assertTrue(t.down(50f, 240f, false, fitMap) is MirrorExitTracker.Decision.Swallow)
        val up = t.up(50f, 240f, false, fitMap)
        assertEquals(MirrorExitTracker.Decision.Stop(MirrorExitTracker.StopReason.PADDING_TAP), up)
    }

    @Test
    fun fitDragFromContentOntoPaddingDoesNotStop() {
        // The old bug, Fit edition: a drag that started on content and
        // ended on the (very wide) bars closed the session. Now the UP
        // relays at the last content point.
        val t = MirrorExitTracker()
        t.down(400f, 240f, false, fitMap)
        t.move(450f, 240f, false, fitMap)
        val up = t.up(50f, 240f, false, fitMap)
        assertEquals(MirrorExitTracker.Decision.Relay(MirrorExitTracker.RelayAction.UP, 450f, 240f), up)
    }

    @Test
    fun fitPaddingToContentGestureIsSwallowedNotRelayed() {
        val t = MirrorExitTracker()
        t.down(50f, 240f, false, fitMap)
        t.move(400f, 240f, false, fitMap)
        val up = t.up(400f, 240f, false, fitMap)
        assertTrue(up is MirrorExitTracker.Decision.Swallow)
    }

    @Test
    fun cancelCompletesAnInFlightRelay() {
        val t = MirrorExitTracker()
        t.down(400f, 240f, false, fillMap)
        t.move(450f, 240f, false, fillMap)
        val cancel = t.cancel()
        assertEquals(MirrorExitTracker.Decision.Relay(MirrorExitTracker.RelayAction.UP, 450f, 240f), cancel)
    }
}
