package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r77: pins the card-clearance pure rules. Geometry fixtures use the
 * audited numbers: 64dp buttons, 12dp gaps, 12dp chrome padding, 24dp
 * card padding, 80dp nav-bar strip. The short frame is 800x480 — the
 * shape of a short head unit, below the ~512dp audio-card threshold
 * with three edge buttons.
 */
class CardClearanceTest {

    // 800x480, audio-size card 320x190, three-button column (extent 216, width 64).
    private val shortControls = EdgeControlsGeometry(extentDp = 216f, widthDp = 64f, visible = true)
    private val sleepingControls = EdgeControlsGeometry(extentDp = 216f, widthDp = 64f, visible = false)
    private val emptyControls = EdgeControlsGeometry(extentDp = 0f, widthDp = 64f, visible = true)

    @Test fun gateRequiresBoth() {
        assertTrue(cardClearanceNow(true, true))
        assertFalse(cardClearanceNow(false, true))
        assertFalse(cardClearanceNow(true, false))
    }

    @Test fun gateOffReturnsCurrent() {
        assertEquals(
            MediaCardDock.END,
            clearanceDock(MediaCardDock.END, false, shortControls, true, 800f, 480f, 320f, 190f, 80f),
        )
    }

    @Test fun nullControlsReturnsCurrent() {
        assertEquals(
            MediaCardDock.END,
            clearanceDock(MediaCardDock.END, true, null, true, 800f, 480f, 320f, 190f, 80f),
        )
    }

    @Test fun sleepingControlsReturnsCurrent() {
        assertEquals(
            MediaCardDock.END,
            clearanceDock(MediaCardDock.END, true, sleepingControls, true, 800f, 480f, 320f, 190f, 80f),
        )
    }

    @Test fun emptyControlsReturnsCurrent() {
        assertEquals(
            MediaCardDock.END,
            clearanceDock(MediaCardDock.END, true, emptyControls, true, 800f, 480f, 320f, 190f, 80f),
        )
    }

    @Test fun noOverlapReturnsCurrent() {
        // Tall frame: the card's top (600 - 104 - 190 = 306) clears the controls' bottom (228).
        assertEquals(
            MediaCardDock.END,
            clearanceDock(MediaCardDock.END, true, shortControls, true, 800f, 600f, 320f, 190f, 80f),
        )
    }

    @Test fun unmeasuredCardReturnsCurrent() {
        // Zero height means the card has not laid out yet: no jump on a guess.
        assertEquals(
            MediaCardDock.END,
            clearanceDock(MediaCardDock.END, true, shortControls, true, 800f, 480f, 320f, 0f, 80f),
        )
    }

    @Test fun endOverlapMovesToStart() {
        // Short frame, START allowed: the opposite corner is the first destination.
        assertEquals(
            MediaCardDock.START,
            clearanceDock(MediaCardDock.END, true, shortControls, true, 800f, 480f, 320f, 190f, 80f),
        )
    }

    @Test fun endOverlapMovesToCenterWhenStartVetoed() {
        // Same overlap, START vetoed (trip bar or driver-side rail): CENTER clears.
        assertEquals(
            MediaCardDock.CENTER,
            clearanceDock(MediaCardDock.END, true, shortControls, false, 800f, 480f, 320f, 190f, 80f),
        )
    }

    @Test fun centerOverlapStays() {
        // Ultra-narrow frame (360 wide): CENTER also reaches the controls, START vetoed — stay.
        assertEquals(
            MediaCardDock.END,
            clearanceDock(MediaCardDock.END, true, shortControls, false, 360f, 480f, 240f, 190f, 80f),
        )
    }

    @Test fun startCurrentOverlapStays() {
        // A 3-button actions row (width 216) on a 480-wide frame: START overlaps and CENTER
        // overlaps too (a Start overlap always implies a Center overlap at the same height),
        // so no destination clears and the card stays.
        val rowControls = EdgeControlsGeometry(extentDp = 216f, widthDp = 216f, visible = true)
        assertEquals(
            MediaCardDock.START,
            clearanceDock(MediaCardDock.START, true, rowControls, true, 480f, 480f, 240f, 190f, 80f),
        )
    }

    @Test fun centerCurrentOverlapStaysWhenStartVetoed() {
        assertEquals(
            MediaCardDock.CENTER,
            clearanceDock(MediaCardDock.CENTER, true, shortControls, false, 360f, 480f, 240f, 190f, 80f),
        )
    }

    @Test fun centerCurrentOverlapMovesToStartWhenAllowed() {
        // Same ultra-narrow overlap, START allowed: START's left edge (24) clears the
        // controls' left edge (284) where CENTER's span ([60, 300]) does not.
        assertEquals(
            MediaCardDock.START,
            clearanceDock(MediaCardDock.CENTER, true, shortControls, true, 360f, 480f, 240f, 190f, 80f),
        )
    }

    @Test fun extentValues() {
        assertEquals(0f, controlsExtentDp(0))
        assertEquals(64f, controlsExtentDp(1))
        assertEquals(216f, controlsExtentDp(3))
        assertEquals(292f, controlsExtentDp(4))
    }

    @Test fun widthValues() {
        assertEquals(64f, controlsWidthDp(0))
        assertEquals(140f, controlsWidthDp(2))
        assertEquals(216f, controlsWidthDp(3))
    }

    @Test fun cardLeftValues() {
        assertEquals(456f, cardLeftDp(MediaCardDock.END, 800f, 320f))
        assertEquals(24f, cardLeftDp(MediaCardDock.START, 800f, 320f))
        assertEquals(240f, cardLeftDp(MediaCardDock.CENTER, 800f, 320f))
    }

    @Test fun rectsOverlapValues() {
        assertFalse(rectsOverlap(0f, 0f, 100f, 100f, 100f, 0f, 200f, 100f))
        assertTrue(rectsOverlap(0f, 0f, 100f, 100f, 99f, 0f, 200f, 100f))
        assertFalse(rectsOverlap(0f, 0f, 100f, 100f, 200f, 200f, 300f, 300f))
    }

    @Test fun endOverlapsShortFrame() {
        // Card [456, 776] x [186, 376]; controls [724, 788] x [12, 228]: intersect.
        assertTrue(cardOverlapsControls(MediaCardDock.END, 800f, 480f, 320f, 190f, shortControls, 80f))
    }

    @Test fun endClearsTallFrame() {
        assertFalse(cardOverlapsControls(MediaCardDock.END, 800f, 600f, 320f, 190f, shortControls, 80f))
    }

    @Test fun centerClearsShortFrame() {
        // Centered card [240, 560] never reaches the controls' left edge (724).
        assertFalse(cardOverlapsControls(MediaCardDock.CENTER, 800f, 480f, 320f, 190f, shortControls, 80f))
    }

    @Test fun centerOverlapsUltraNarrow() {
        assertTrue(cardOverlapsControls(MediaCardDock.CENTER, 360f, 480f, 240f, 190f, shortControls, 80f))
    }

    @Test fun edgeTouchingIsClear() {
        // Frame height exactly 104 + 190 + 228 = 522: the card's top touches the controls'
        // bottom without covering them.
        assertFalse(cardOverlapsControls(MediaCardDock.END, 800f, 522f, 320f, 190f, shortControls, 80f))
    }
}
