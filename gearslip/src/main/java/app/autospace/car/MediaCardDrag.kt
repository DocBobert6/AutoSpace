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
 * - Press-and-hold to drag (r109): a press-and-hold anywhere on the
 *   card arms dragging; while armed, drags move the card; release
 *   ends the drag, and the next press-and-hold arms it again. The
 *   detector is attached unconditionally and the gate is enforced
 *   inside the gesture callbacks, never by removing the detector —
 *   the r106 conditional chain removed the detector node whenever
 *   the gate closed, which is the one-and-done death: one drag with
 *   the gate open, then nothing from any grab point once the gate
 *   closed. Dock on means the card sits exactly at its dock
 *   position; the stored drag offset is ignored, not cleared, so
 *   undocking restores the dragged spot.
 * - r111 live gate + lifecycle audit: the gate inputs the detector
 *   enforces (dock toggle, motion poll) are refreshed with
 *   rememberUpdatedState on every composition and read at arm time
 *   and on every move, so no stale gate value can live in the
 *   once-attached detector. (Both inputs are delegated State reads,
 *   which already re-read live; r111 makes the liveness explicit
 *   rather than relying on delegate semantics.) The audit of the
 *   whole drag path at the r110 base found no other one-and-done
 *   mechanism, path by path: arm is set only in onDragStart and
 *   cleared in onDragEnd, onDragCancel, and before the early return
 *   on a mid-drag gate closure, and a pre-arm cancellation never
 *   sets it; node disposal kills the arm flag with the block, so
 *   re-entry starts unarmed; the audio slot is the only slot with
 *   a detector (the live-widget and mini-video slots have none and
 *   do not share the offset), and slot disposal only re-creates a
 *   fresh detector; the clamp is pure with ordered bounds, so a
 *   card at a bound still moves in the opposite direction; the
 *   offset state has exactly one writer (the detector) and one
 *   reader (the .offset modifier) and is never reset; the file's
 *   only pointerInput is the card drag, the frame overlay takes
 *   none, and the rail sits outside the inset content area so the
 *   card cannot slide under it; the info-row swipe is info-row-only
 *   and horizontal-only and the waveform detectors never consume
 *   the down event, so a long-press anywhere arms the card drag.
 *   What the audit cannot rule out from code alone: a repeat
 *   attempt without the hold (a quick drag moves nothing by r109
 *   design), or a transient gate closure (a single noisy GPS speed
 *   sample over 2 m/s closes the gate for that poll second — the
 *   gate is deliberately single-sample with no hysteresis, and
 *   softening a parked-only safety gate is not done silently).
 *   The device retest discriminates both.
 * - Parked-only: the gate reads the same 1 s CarMotion poll the
 *   widget column and MiniVideo use. It is enforced at arm time and
 *   re-checked on every move, so a drag in flight is cancelled the
 *   moment motion starts — but the detector itself never leaves
 *   the chain, so it works again the moment the gate reopens.
 * - Session-only: the offset lives in a CarHome remember, never in
 *   CarSettings, so a dragged position resets when Home leaves
 *   composition (process death / Home disposal) and survives card
 *   hide/show, mini video, and live-widget swaps.
 *
 * Gesture coexistence, stated plainly: the drag detector sits on
 * the card's own modifier, OUTSIDE the card's Box. Ordinary
 * (non-long-press) gestures are untouched: a quick horizontal drag
 * on the waveform seeks and on the info row swipes sessions, because
 * movement before the long-press timeout cancels the arm wait before
 * the card detector can claim the gesture. A long-press anywhere on
 * the card — including on the waveform and info row — arms the card
 * drag instead, since neither child consumes the down event. A tap
 * (no movement past slop) still taps exactly as today, and a
 * long-press released without movement moves nothing.
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
