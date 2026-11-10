package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r77: pins the card-clearance pure rules. Geometry fixtures use the
 * audited numbers: 72dp buttons, 14dp gaps, 12dp chrome padding, 24dp
 * card padding, 80dp nav-bar strip. The short frame is 800x480 — the
 * shape of a short head unit, below the ~550dp audio-card threshold
 * with three edge buttons.
 */
class CardClearanceTest {

    // 800x480, audio-size card 320x190, three-button column (extent 244, width 72).
    private val shortControls = EdgeControlsGeometry(extentDp = 244f, widthDp = 72f, visible = true)
    private val sleepingControls = EdgeControlsGeometry(extentDp = 244f, widthDp = 72f, visible = false)
    private val emptyControls = EdgeControlsGeometry(extentDp = 0f, widthDp = 72f, visible = true)


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
        // Tall frame: the card's top (600 - 104 - 190 = 306) clears the controls' bottom (256).
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
        // A 3-button actions row (width 244) on a 480-wide frame: START overlaps and CENTER
        // overlaps too (a Start overlap always implies a Center overlap at the same height),
        // so no destination clears and the card stays.
        val rowControls = EdgeControlsGeometry(extentDp = 244f, widthDp = 244f, visible = true)
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
        // controls' left edge (276) where CENTER's span ([60, 300]) does not.
        assertEquals(
            MediaCardDock.START,
            clearanceDock(MediaCardDock.CENTER, true, shortControls, true, 360f, 480f, 240f, 190f, 80f),
        )
    }

    @Test fun extentValues() {
        assertEquals(0f, controlsExtentDp(0))
        assertEquals(72f, controlsExtentDp(1))
        assertEquals(244f, controlsExtentDp(3))
        assertEquals(330f, controlsExtentDp(4))
    }

    @Test fun widthValues() {
        assertEquals(72f, controlsWidthDp(0))
        assertEquals(158f, controlsWidthDp(2))
        assertEquals(244f, controlsWidthDp(3))
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
        // Card [456, 776] x [186, 376]; controls [716, 788] x [12, 256]: intersect.
        assertTrue(cardOverlapsControls(MediaCardDock.END, 800f, 480f, 320f, 190f, shortControls, 80f))
    }

    @Test fun endClearsTallFrame() {
        assertFalse(cardOverlapsControls(MediaCardDock.END, 800f, 600f, 320f, 190f, shortControls, 80f))
    }

    @Test fun centerClearsShortFrame() {
        // Centered card [240, 560] never reaches the controls' left edge (716).
        assertFalse(cardOverlapsControls(MediaCardDock.CENTER, 800f, 480f, 320f, 190f, shortControls, 80f))
    }

    @Test fun centerOverlapsUltraNarrow() {
        assertTrue(cardOverlapsControls(MediaCardDock.CENTER, 360f, 480f, 240f, 190f, shortControls, 80f))
    }

    @Test fun edgeTouchingIsClear() {
        // Frame height exactly 104 + 190 + 256 = 550: the card's top touches the controls'
        // bottom without covering them.
        assertFalse(cardOverlapsControls(MediaCardDock.END, 800f, 550f, 320f, 190f, shortControls, 80f))
    }

    // r181 break 2: the widget column is a second obstacle. Passenger column
    // [508, 788] x [12, 302] (800-wide frame, 280dp column); driver column
    // [12, 212] x [12, 302] (200dp column).
    private val passengerColumn = WidgetColumnGeometry(leftDp = 508f, topDp = 12f, rightDp = 788f, bottomDp = 302f)
    private val driverColumn = WidgetColumnGeometry(leftDp = 12f, topDp = 12f, rightDp = 212f, bottomDp = 302f)

    @Test fun columnAloneStepsAsideToStart() {
        // No controls at all; the passenger-side column overlaps the END card, START is free.
        // Card END [456, 776] x [196, 456]; START [24, 344] clears the column's left edge (508).
        assertEquals(
            MediaCardDock.START,
            clearanceDock(MediaCardDock.END, true, null, true, 800f, 480f, 320f, 260f, 0f, passengerColumn),
        )
    }

    @Test fun columnAloneWithSleepingControlsStepsAside() {
        // Sleeping controls alone would return current; the column still drives the step-aside.
        assertEquals(
            MediaCardDock.START,
            clearanceDock(MediaCardDock.END, true, sleepingControls, true, 800f, 480f, 320f, 260f, 0f, passengerColumn),
        )
    }

    @Test fun narrowFrameColumnBlocksBothDestinations() {
        // 460-wide frame, driver column [12, 212]: START [24, 264] and CENTER [110, 350] both reach it.
        assertEquals(
            MediaCardDock.END,
            clearanceDock(MediaCardDock.END, true, null, true, 460f, 400f, 240f, 190f, 0f, driverColumn),
        )
    }

    @Test fun neitherDestinationClearsBothRectsStays() {
        // Controls row [544, 788] blocks CENTER; driver column [12, 212] blocks START;
        // END overlaps the controls, so the card stays rather than trading one cover for another.
        val rowControls = EdgeControlsGeometry(extentDp = 244f, widthDp = 244f, visible = true)
        assertEquals(
            MediaCardDock.END,
            clearanceDock(MediaCardDock.END, true, rowControls, true, 800f, 480f, 320f, 260f, 0f, driverColumn),
        )
    }

    @Test fun nullColumnBehavesLikeBreak1() {
        // Explicit null column: exactly today's break-1 decision.
        assertEquals(
            MediaCardDock.START,
            clearanceDock(MediaCardDock.END, true, shortControls, true, 800f, 480f, 320f, 190f, 80f, null),
        )
    }

    @Test fun degenerateColumnRectIsNoObstacle() {
        // Zero-height rect: presence without area is no obstacle.
        val flat = WidgetColumnGeometry(leftDp = 508f, topDp = 12f, rightDp = 788f, bottomDp = 12f)
        assertEquals(
            MediaCardDock.END,
            clearanceDock(MediaCardDock.END, true, null, true, 800f, 480f, 320f, 260f, 0f, flat),
        )
    }

    @Test fun columnOverlapValues() {
        assertTrue(cardOverlapsColumn(MediaCardDock.END, 800f, 480f, 320f, 260f, passengerColumn, 0f))
        assertFalse(cardOverlapsColumn(MediaCardDock.START, 800f, 480f, 320f, 260f, passengerColumn, 0f))
        // Unmeasured card: no overlap claimed, same first-frame rule as controls.
        assertFalse(cardOverlapsColumn(MediaCardDock.END, 800f, 480f, 320f, 0f, passengerColumn, 0f))
    }

    // r213: the widget column's span, cleared of the controls strip.
    // Fixtures use the 800x480 short frame: box width 776 (frame minus
    // the 12dp pads), natural column 280dp passenger / 233dp driver
    // (30% of 776), strip 72dp (no action row) or 244dp (three-button row).

    @Test fun clearedSpanNoStripPassengerIsToday() {
        // Box 776 - 280 = 496; frame left 12 + 496 = 508, right 788 —
        // exactly the passengerColumn fixture above.
        assertEquals(496f to 280f, widgetColumnClearedSpan(776f, true, 280f, null))
    }

    @Test fun clearedSpanNoStripDriverIsToday() {
        assertEquals(0f to 233f, widgetColumnClearedSpan(776f, false, 233f, null))
    }

    @Test fun clearedSpanPassengerShiftsLeftByStrip() {
        // Strip left (box) 704; the 280dp column keeps its width and its
        // right edge lands exactly on the strip's left edge (frame 716).
        assertEquals(424f to 280f, widgetColumnClearedSpan(776f, true, 280f, 72f))
    }

    @Test fun clearedSpanDriverKeepsWidthWhenItClears() {
        assertEquals(0f to 233f, widgetColumnClearedSpan(776f, false, 233f, 72f))
    }

    @Test fun clearedSpanWideRowNarrowsDriverColumn() {
        // 460-wide frame, three-button row (244dp): strip left (box) 192;
        // the driver column narrows 200 -> 192 so its right edge clears.
        assertEquals(0f to 192f, widgetColumnClearedSpan(436f, false, 200f, 244f))
    }

    @Test fun clearedSpanWideRowShiftsPassengerColumn() {
        // Same frame, passenger side: the column narrows to the 192dp
        // span and sits at the box's start — its right edge clears the strip.
        assertEquals(0f to 192f, widgetColumnClearedSpan(436f, true, 200f, 244f))
    }

    @Test fun clearedSpanRightEdgeTouchesStripLeft() {
        // The passenger span's right edge is exactly the strip's left
        // edge: edges merely touching is clear, not covered (rectsOverlap).
        val (left, width) = widgetColumnClearedSpan(776f, true, 280f, 72f)
        val stripLeftFrame = 800f - 12f - 72f
        assertEquals(stripLeftFrame, 12f + left + width)
    }
}
