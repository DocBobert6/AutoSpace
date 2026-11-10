package app.autospace.car

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * r77: card-vs-controls clearance (all-experimental-on audit break 1).
 *
 * The problem, grounded at the parent ref: on short frames the floating
 * media card can cover the template's right-edge controls. The controls
 * are a TopEnd column of 72dp buttons with 14dp gaps (TemplateParts
 * RightEdgeControls), 12dp in from the frame's top and right edges, so
 * three of them reach ~256dp down from the top; the card is bottom-
 * anchored with 24dp plus the nav-bar strip below it, so on frames
 * shorter than ~104dp + card height + controls extent the two meet on
 * the right edge — and the card, composed after the map pane in the
 * window layer above it, covers the controls and takes their touches.
 * Real, not just visual, and it exists with or without widgets on.
 *
 * The fix is a step-aside, not a resize or a hide: when the clearance
 * gate is on and the slot's card would cover controls that are actually
 * showing, the slot's alignment moves so the card clears them. Sleeping
 * controls need no clearance — they are invisible (alpha 0) and the
 * card stays exactly where it is until they wake. The chrome reports
 * its own visibility, so the card uses the exact condition the chrome
 * draws by (awake, panning, or holding focus), not a re-derivation.
 *
 * Destination order, and why: START first when it is allowed (the
 * opposite corner, the codebase's own separation principle), else
 * CENTER. START is allowed only when no trip bar is showing (a Start
 * card would land exactly on the trip bar's Stop control — the r69
 * fallback exists for this) and no driver-side rail is showing (the
 * rail occupies the left edge; a Start card would slide under it).
 * CENTER never covers the trip bar's Stop button — the button sits at
 * the far left of the bar and a centered card starts well right of it
 * on every realistic frame — so CENTER stays available during
 * guidance. Two overlaps are tolerated and disclosed, both display-
 * only or pre-compromised: a centered card may cover trip-bar detail
 * text (never Stop) and may clip the turn card's bottom-right corner
 * on very short guidance frames (the turn card is display-only), and
 * r181 break 2: the widget column is now a second obstacle beside
 * the edge controls (see WidgetColumnGeometry below), so a destination
 * must clear BOTH rects. The column's dismiss row sits at its top and
 * the whole column rect is the obstacle, so dismissal is never covered.
 * Frames so narrow that no destination clears both rects keep the card
 * put — the fix never invents a destination that trades one cover for
 * another. Break 3 (column under the bar on very short frames) is fixed
 * in WidgetLayer, by height-degradation mirroring width-degradation.
 *
 * Measurement, not estimates, for the card height: the slot's card
 * reports its laid-out height via onSizeChanged, so long titles,
 * two-line info rows, the mini video's video/audio branches, and the
 * live widget's turn counts are all handled exactly. Height does not
 * depend on alignment (width is fixed by floatingCardWidth), so there
 * is no feedback loop. The one transient: a surface switch reuses the
 * previous surface's height for a single frame until its own layout
 * reports — self-correcting, never stuck.
 *
 * The first-tap race, stated plainly: a tap on the card region wakes
 * the controls (the outer touch watcher fires on every touch), so on
 * a short frame the card steps aside mid-tap and that tap is cancelled
 * — nothing fires, nothing wrong triggers. The next tap lands on the
 * intended surface. When the controls are already awake the card is
 * already aside and taps land normally. Reaching a covered control
 * therefore takes at most two taps, where today it takes dismissal.
 *
 * All geometry below is in dp as Floats, so the rules are JVM-
 * testable with no Compose dependency. The call sites convert Dp via
 * .value under the same scaled density the chrome lays out in, so
 * every number is in the same space.
 */

// Verified against TemplateParts.kt RightEdgeControls at the parent ref.
// r178: button 64dp -> 72dp, gap 12dp -> 14dp; chrome pad stays 12dp.
internal const val CardClearanceButtonDp = 72f
internal const val CardClearanceGapDp = 14f
internal const val CardClearanceChromePadDp = 12f
internal const val CardClearanceCardPadDp = 24f

/**
 * What the host's own edge-controls column reports about itself, so
 * the card never re-derives chrome geometry from the template (every
 * template type hands the chrome different strips; the chrome is the
 * one place that already filtered them to drawable actions). Single
 * writer: RightEdgeControls reports on every composition whose inputs
 * change and clears on dispose, so a bare map or a cleared template
 * can never leave stale geometry behind.
 */
data class EdgeControlsGeometry(
    /** Vertical extent of the column below its 12dp top padding, in dp. */
    val extentDp: Float,
    /** Horizontal width of the column, in dp: the actions row if any, else one button. */
    val widthDp: Float,
    /** The chrome's own visibility: awake, panning, or holding focus. */
    val visible: Boolean,
)

/** Vertical extent of a column of [verticalItems] 72dp buttons with 14dp gaps. */
fun controlsExtentDp(verticalItems: Int): Float =
    if (verticalItems <= 0) 0f
    else verticalItems * CardClearanceButtonDp + (verticalItems - 1) * CardClearanceGapDp

/** Width of the column: the actions row if it exists, else a single button. */
fun controlsWidthDp(rowButtons: Int): Float =
    if (rowButtons <= 0) CardClearanceButtonDp
    else maxOf(rowButtons * CardClearanceButtonDp + (rowButtons - 1) * CardClearanceGapDp, CardClearanceButtonDp)

/** Left edge of a card docked to [side] in a frame [frameWidthDp] wide. */
fun cardLeftDp(side: MediaCardDock, frameWidthDp: Float, cardWidthDp: Float): Float = when (side) {
    MediaCardDock.END -> frameWidthDp - CardClearanceCardPadDp - cardWidthDp
    MediaCardDock.START -> CardClearanceCardPadDp
    MediaCardDock.CENTER -> (frameWidthDp - cardWidthDp) / 2f
}

/** Strict rectangle intersection: edges merely touching is clear, not covered. */
fun rectsOverlap(l1: Float, t1: Float, r1: Float, b1: Float, l2: Float, t2: Float, r2: Float, b2: Float): Boolean =
    l1 < r2 && l2 < r1 && t1 < b2 && t2 < b1

/**
 * Whether a card docked to [side] covers any of the edge controls.
 * A zero (or negative) card height means unmeasured: no overlap is
 * claimed until the card's first layout reports, so the first frame
 * never jumps on a guess.
 */
fun cardOverlapsControls(
    side: MediaCardDock,
    frameWidthDp: Float,
    frameHeightDp: Float,
    cardWidthDp: Float,
    cardHeightDp: Float,
    controls: EdgeControlsGeometry,
    stripDp: Float,
): Boolean {
    if (cardHeightDp <= 0f || controls.extentDp <= 0f) return false
    val cardLeft = cardLeftDp(side, frameWidthDp, cardWidthDp)
    val cardTop = frameHeightDp - CardClearanceCardPadDp - stripDp - cardHeightDp
    val cardRight = cardLeft + cardWidthDp
    val cardBottom = frameHeightDp - CardClearanceCardPadDp - stripDp
    val controlsLeft = frameWidthDp - CardClearanceChromePadDp - controls.widthDp
    val controlsTop = CardClearanceChromePadDp
    val controlsRight = frameWidthDp - CardClearanceChromePadDp
    val controlsBottom = CardClearanceChromePadDp + controls.extentDp
    return rectsOverlap(cardLeft, cardTop, cardRight, cardBottom, controlsLeft, controlsTop, controlsRight, controlsBottom)
}

/**
 * r181 break 2: the widget column's laid-out rect, in frame dp, as
 * reported by the Home slot. Presence means visible AND measured: the
 * slot reports only after the column's first layout and clears on
 * dispose, so an absent, hidden, or not-yet-measured column is never
 * an obstacle. The left edge follows the placement side inside the
 * slot's 12dp-padded box (start edge on the driver side, end edge on
 * the passenger side); the top is that same 12dp padding; the height
 * is measured with onSizeChanged, never estimated from widget counts.
 */
data class WidgetColumnGeometry(
    val leftDp: Float,
    val topDp: Float,
    val rightDp: Float,
    val bottomDp: Float,
)

/**
 * Whether a card docked to [side] covers any of the widget column.
 * A zero (or negative) card height means unmeasured: no overlap is
 * claimed until the card's first layout reports, so the first frame
 * never jumps on a guess. A degenerate (zero-area) column rect is
 * likewise no obstacle.
 */
fun cardOverlapsColumn(
    side: MediaCardDock,
    frameWidthDp: Float,
    frameHeightDp: Float,
    cardWidthDp: Float,
    cardHeightDp: Float,
    column: WidgetColumnGeometry,
    stripDp: Float,
): Boolean {
    if (cardHeightDp <= 0f) return false
    if (column.bottomDp <= column.topDp || column.rightDp <= column.leftDp) return false
    val cardLeft = cardLeftDp(side, frameWidthDp, cardWidthDp)
    val cardTop = frameHeightDp - CardClearanceCardPadDp - stripDp - cardHeightDp
    val cardRight = cardLeft + cardWidthDp
    val cardBottom = frameHeightDp - CardClearanceCardPadDp - stripDp
    return rectsOverlap(cardLeft, cardTop, cardRight, cardBottom, column.leftDp, column.topDp, column.rightDp, column.bottomDp)
}

/**
 * r213: the widget column's horizontal span, cleared of the template's
 * right-edge controls strip (photo 3, 2026-10-28: the passenger-side
 * column sat under RightEdgeControls at the top corner).
 *
 * The strip is the chrome's own column: 12dp in from the frame's right
 * edge and [stripWidthDp] wide (72dp with no action row, up to 244dp
 * with a three-button row — see controlsWidthDp). The column's right
 * edge must not extend under it. Pure, so the unit test pins it.
 *
 * Inputs are in the 12dp-padded box's coordinates (see the Home slot):
 * [boxWidthDp] is the BoxWithConstraints maxWidth — the frame minus the
 * 12dp pads — so the box's left edge is frame 12dp and its right edge is
 * frame width minus 12dp. [stripWidthDp] is null when the chrome has no
 * controls at all (bare map, or a template with no drawable actions);
 * then the span is exactly today's.
 *
 * The bound is applied to the layout itself, awake or asleep: the
 * strip's buttons are laid out there whether or not they are faded in
 * (they still take the waking touch), so a wake-dependent shift would
 * slide the whole column under the driver's finger and cancel the
 * waking tap — the r77 first-tap race, applied to widgets. Reserving
 * the strip permanently costs nothing on the bare map (no strip, no
 * shift) and keeps the column still.
 *
 * The passenger side shifts left by the strip width, keeping its width;
 * the driver side caps the width — the same bound either way, so both
 * placements clear. On frames too narrow for the full width the column
 * narrows to the space between the left pad and the strip; that only
 * happens below ~224dp + strip width, where the template's own chrome
 * (336dp turn card + strip) cannot fit either, so no frame the app
 * actually renders on shrinks a card. Returns (leftDp, widthDp) in box
 * coordinates; the call site adds the 12dp box pad for frame coordinates.
 */
fun widgetColumnClearedSpan(
    boxWidthDp: Float,
    passengerSide: Boolean,
    columnWidthDp: Float,
    stripWidthDp: Float?,
): Pair<Float, Float> {
    if (stripWidthDp == null) {
        return if (passengerSide) Pair(boxWidthDp - columnWidthDp, columnWidthDp)
        else Pair(0f, columnWidthDp)
    }
    val stripLeftDp = boxWidthDp - stripWidthDp
    val widthDp = minOf(columnWidthDp, maxOf(0f, stripLeftDp))
    val leftDp = if (passengerSide) maxOf(0f, stripLeftDp - widthDp) else 0f
    return Pair(leftDp, widthDp)
}


/**
 * The clearance-adjusted dock for the slot's card. Pure, so the unit
 * test pins it. [startAllowed] is false while a trip bar is showing
 * (a Start card would cover its Stop control) or a driver-side rail
 * is showing (a Start card would slide under it); the call site
 * computes it conservatively — over-vetoing only ever picks CENTER
 * or stays, never a cover. CENTER is tried whenever START is not
 * allowed or does not clear; if neither clears, the card stays put
 * rather than trading one cover for another.
 */
fun clearanceDock(
    current: MediaCardDock,
    gateOn: Boolean,
    controls: EdgeControlsGeometry?,
    startAllowed: Boolean,
    frameWidthDp: Float,
    frameHeightDp: Float,
    cardWidthDp: Float,
    cardHeightDp: Float,
    stripDp: Float,
    column: WidgetColumnGeometry? = null,
): MediaCardDock {
    if (!gateOn) return current
    // r181 break 2: the two obstacles are independent. The controls
    // gate is exactly today's (a null, sleeping, or empty column of
    // controls is no obstacle); the column gate is its presence as a
    // measured, non-degenerate rect. With no column, every decision
    // below reduces to today's break-1 logic, so break-1 behavior is
    // byte-identical when the column is absent or hidden.
    val activeControls =
        if (controls != null && controls.visible && controls.extentDp > 0f) controls else null
    val activeColumn =
        if (column != null && column.bottomDp > column.topDp && column.rightDp > column.leftDp) column else null
    if (activeControls == null && activeColumn == null) return current
    fun overlaps(side: MediaCardDock): Boolean =
        (activeControls != null && cardOverlapsControls(side, frameWidthDp, frameHeightDp, cardWidthDp, cardHeightDp, activeControls, stripDp)) ||
            (activeColumn != null && cardOverlapsColumn(side, frameWidthDp, frameHeightDp, cardWidthDp, cardHeightDp, activeColumn, stripDp))
    if (!overlaps(current)) return current
    if (startAllowed && !overlaps(MediaCardDock.START)) return MediaCardDock.START
    if (!overlaps(MediaCardDock.CENTER)) return MediaCardDock.CENTER
    return current
}

/**
 * Shared slot state for the clearance fix. The chrome writes the
 * controls geometry; the slot's card writes its measured height; the
 * slot's alignment reads both. Neither writer depends on the card's
 * alignment, so no feedback loop exists.
 */
object CardClearanceState {
    var controls by mutableStateOf<EdgeControlsGeometry?>(null)
        private set
    var cardHeightDp by mutableStateOf<Float?>(null)
        private set
    // r181 break 2: the widget column's laid-out rect, reported by the
    // Home slot and cleared when the column leaves composition. The
    // card slot reads it alongside the controls geometry; neither
    // writer depends on the card's alignment, so no feedback loop exists.
    var column by mutableStateOf<WidgetColumnGeometry?>(null)
        private set

    fun reportControls(geometry: EdgeControlsGeometry) {
        controls = geometry
    }

    fun clearControls() {
        controls = null
    }

    fun reportCardHeight(heightDp: Float) {
        cardHeightDp = heightDp
    }

    fun reportColumn(geometry: WidgetColumnGeometry) {
        column = geometry
    }

    fun clearColumn() {
        column = null
    }
}
