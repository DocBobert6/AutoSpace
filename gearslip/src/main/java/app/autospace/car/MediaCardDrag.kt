package app.autospace.car

import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset

/**
 * r106: floating media card free-drag rules. All geometry is in dp as
 * Floats, so the rules are JVM-testable with no Android dependency
 * (same convention as CardClearance). The composable slot in
 * CarHomeScreen owns the gesture and the offset state; this file owns
 * only the decisions, so the unit test pins them exactly.
 *
 * The three rules, and how they compose:
 * - Draggable BY DEFAULT in free placement: the drag gesture is
 *   active whenever the dock toggle is off and the car is parked
 *   ([mediaCardDragApplies]). Dock on means the card sits exactly at
 *   its dock position; the stored drag offset is ignored, not
 *   cleared, so undocking restores the dragged spot.
 * - Parked-only: the gate reads the same 1 s CarMotion poll the
 *   widget column and MiniVideo use. A drag in flight is cancelled
 *   the moment motion starts (the gesture detector restarts on the
 *   gate value), so a card can never be dragged while moving.
 * - Session-only: the offset lives in a CarHome remember, never in
 *   CarSettings, so a dragged position resets when Home leaves
 *   composition (process death / Home disposal) and survives card
 *   hide/show, mini video, and live-widget swaps.
 *
 * Gesture coexistence, stated plainly: the drag detector sits on
 * the card's own modifier, OUTSIDE the card's Box, so the card's
 * children win their areas — pointer dispatch runs innermost-first,
 * so a horizontal drag on the waveform seeks and a horizontal drag
 * on the info row swipes sessions, and the card drag only moves on
 * drags those detectors do not claim. A tap (no movement past slop)
 * still taps exactly as today.
 *
 * Navigation-low pair: during navigation the card sits low ONLY
 * when docked ([mediaCardDragNavLow] — a free card keeps its dragged
 * spot even while navigating), while the ETA pill sits low
 * unconditionally ([mediaCardEtaNavLow]). The pill half is already
 * built: TravelEstimateBar is BottomStart + 12dp in NavigationChrome
 * whenever guidance shows it, on CarAppStage's full-size chrome, so
 * no composable change was needed there — the rule is pinned here
 * so the pair can never drift apart.
 */

/** Minimum visible strip of a dragged card, so it can never be dragged fully off-screen and stays reachable to drag back. */
internal const val MediaCardDragMinVisibleDp = 48f

/** The floating slot's inset on every side, shared with the clamp math so the two can never drift (r105 lesson: one constant, not two numbers). */
internal const val MediaCardSlotPadDp = 24f

/** Drag gesture active only in free placement (dock off) while parked. */
fun mediaCardDragApplies(dockEnabled: Boolean, moving: Boolean): Boolean = !dockEnabled && !moving

/**
 * Whether the low-during-navigation rule binds the card: dock on +
 * navigating means the card sits at its low dock position and any
 * stored drag offset is ignored. Dock off means the card keeps its
 * dragged spot even during navigation.
 */
fun mediaCardDragNavLow(dockEnabled: Boolean, navigating: Boolean): Boolean = dockEnabled && navigating

/** Whether the ETA pill sits low during navigation: unconditionally — it does not depend on the dock toggle, the drag state, or anything else. */
fun mediaCardEtaNavLow(navigating: Boolean): Boolean = navigating

/**
 * Frame-space left of the card at its aligned (undragged) position:
 * bottom-anchored with [padDp] insets, horizontal from [dock]. The
 * padded slot box is inset by [padDp] on every side, so the
 * frame-space left is the pad plus the aligned position inside it.
 */
fun mediaCardAlignedLeftDp(dock: Alignment, frameWidthDp: Float, cardWidthDp: Float, padDp: Float): Float = when (dock) {
    Alignment.BottomStart -> padDp
    Alignment.BottomCenter -> (frameWidthDp - cardWidthDp) / 2f
    else -> frameWidthDp - padDp - cardWidthDp
}

/**
 * Clamps a dragged offset so the card rect stays within the frame
 * with at least [minVisibleDp] of the card on-screen on every side.
 * [defaultLeftDp]/[defaultTopDp] are the card's aligned (undragged)
 * frame-space position, so the clamp is computed on absolute
 * position, not on the offset — the offset alone cannot know where
 * the card started. Unknown card size (<= 0) returns the offset
 * unchanged: the first frame never jumps on a guess; the slot
 * reports the size on layout. Bounds are ordered before coercing,
 * so a degenerate frame can never throw.
 */
fun clampMediaCardDragOffset(
    offsetXDp: Float,
    offsetYDp: Float,
    defaultLeftDp: Float,
    defaultTopDp: Float,
    frameWidthDp: Float,
    frameHeightDp: Float,
    cardWidthDp: Float,
    cardHeightDp: Float,
    minVisibleDp: Float,
): Offset {
    if (cardWidthDp <= 0f || cardHeightDp <= 0f) return Offset(offsetXDp, offsetYDp)
    val minAbsLeft = -(cardWidthDp - minVisibleDp)
    val maxAbsLeft = frameWidthDp - minVisibleDp
    val minAbsTop = -(cardHeightDp - minVisibleDp)
    val maxAbsTop = frameHeightDp - minVisibleDp
    val loX = minOf(minAbsLeft, maxAbsLeft) - defaultLeftDp
    val hiX = maxOf(minAbsLeft, maxAbsLeft) - defaultLeftDp
    val loY = minOf(minAbsTop, maxAbsTop) - defaultTopDp
    val hiY = maxOf(minAbsTop, maxAbsTop) - defaultTopDp
    return Offset(offsetXDp.coerceIn(loX, hiX), offsetYDp.coerceIn(loY, hiY))
}
