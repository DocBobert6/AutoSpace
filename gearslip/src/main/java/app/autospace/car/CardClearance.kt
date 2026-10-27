package app.autospace.car

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * r77: card-vs-controls clearance (all-experimental-on audit break 1).
 *
 * The problem, grounded at the parent ref: on short frames the floating
 * media card can cover the template's right-edge controls. The controls
 * are a TopEnd column of 64dp buttons with 12dp gaps (TemplateParts
 * RightEdgeControls), 12dp in from the frame's top and right edges, so
 * three of them reach ~228dp down from the top; the card is bottom-
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
 * either destination may overlap a visible widget column's bottom on
 * short frames with tall columns — the same class as r59's disclosed
 * column-over-controls compromise, and the column's dismiss row sits
 * at its top, so dismissal is never covered.
 *
 * What is NOT fixed here, deliberately: audit breaks 2–3 (column x
 * card on narrow frames; column under the bar on very short frames)
 * were proposed to fold into this round and are still unanswered, so
 * they are untouched. Frames so narrow that CENTER also reaches the
 * controls are break-2 territory and the card stays put — the fix
 * never invents a destination that trades one cover for another.
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
internal const val CardClearanceButtonDp = 64f
internal const val CardClearanceGapDp = 12f
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

/** Vertical extent of a column of [verticalItems] 64dp buttons with 12dp gaps. */
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
): MediaCardDock {
    if (!gateOn) return current
    val geometry = controls ?: return current
    if (!geometry.visible || geometry.extentDp <= 0f) return current
    if (!cardOverlapsControls(current, frameWidthDp, frameHeightDp, cardWidthDp, cardHeightDp, geometry, stripDp)) return current
    if (startAllowed && !cardOverlapsControls(MediaCardDock.START, frameWidthDp, frameHeightDp, cardWidthDp, cardHeightDp, geometry, stripDp)) return MediaCardDock.START
    if (!cardOverlapsControls(MediaCardDock.CENTER, frameWidthDp, frameHeightDp, cardWidthDp, cardHeightDp, geometry, stripDp)) return MediaCardDock.CENTER
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

    fun reportControls(geometry: EdgeControlsGeometry) {
        controls = geometry
    }

    fun clearControls() {
        controls = null
    }

    fun reportCardHeight(heightDp: Float) {
        cardHeightDp = heightDp
    }
}
