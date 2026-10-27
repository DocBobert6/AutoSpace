package app.autospace.car

import androidx.compose.ui.Alignment

/**
 * r74: steering-side floating app bar (A1, experimental). The bottom
 * bar's app row, stood on its side on the driver's side of the screen.
 *
 * Driver side is layout Start, the same mapping the widget column
 * already uses ("driver side (start in LTR)") and the card dock uses
 * (Start/End follow layout direction, they are not absolute left and
 * right). No steering-side setting exists anywhere in the app
 * (full-tree check at the parent ref, plus the settings key list), so
 * in a right-hand-drive car with a left-to-right locale the rail lands
 * on the passenger side — a named limitation, whose proper fix is a
 * steering-side setting, not this round. In a left-to-right locale the
 * driver side is the left edge, which is the concrete case (LHD car).
 *
 * The rail yields to established surfaces rather than covering them
 * or shifting them: it steps to the passenger side while any of these
 * holds, and returns to the driver side when all clear. The caller
 * gates all three inputs to Home, where those surfaces live (other
 * screens cover Home, so the rail never yields for a hidden surface):
 * - the turn card is showing (NavigationTemplate routing info present
 *   with a current step or loading — the same condition the widget
 *   column's placement rule uses for "the turn card"; the turn card is
 *   TopStart, 320dp, and guidance chrome must never be covered);
 * - the widget column is actually showing on the driver side
 *   (top-anchored, same edge; a driving-hidden column renders nothing
 *   and must not push the rail aside);
 * - the floating media card is actually showing docked on the driver
 *   side (bottom corner, same edge; the effective dock, including the
 *   r69 trip-bar fallback, so a Start dock stepped aside to End during
 *   guidance does not push the rail aside).
 *
 * Disclosed compromise, same class as the r59 passenger-side column:
 * on the passenger side the rail can overlap TemplateChrome's
 * right-edge control stack at the top corner while those controls are
 * awake. The controls sleep when idle, and the rail never covers the
 * turn card or the ETA bar — both are driver-side chrome the rail
 * yields from. The quick-tiles shade is transient and draws over
 * everything, rail included, exactly as it draws over the bottom bar.
 */
enum class SideBarSide {
    DRIVER_SIDE,
    PASSENGER_SIDE,
}

/**
 * r74: the side-bar gate. Pure, so the unit test pins it: the rail
 * renders only when the driver has opted into experimental features
 * as a whole AND the side-bar toggle is on. Either off returns exactly
 * today's bottom bar, and the stored toggle is preserved, never
 * rewritten, while the gate is off.
 */
fun sideBarNow(experimentalOn: Boolean, toggleOn: Boolean): Boolean =
    experimentalOn && toggleOn

/**
 * r74: the effective rail side, gate aside. The rail sits on the
 * driver side only when that side is free; any occupied driver-side
 * surface steps it to the passenger side. Pure, so the unit test pins
 * every yield.
 */
fun effectiveSideBarSide(
    turnCardShowing: Boolean,
    columnOnDriverSide: Boolean,
    cardOnDriverSide: Boolean,
): SideBarSide = if (turnCardShowing || columnOnDriverSide || cardOnDriverSide) {
    SideBarSide.PASSENGER_SIDE
} else {
    SideBarSide.DRIVER_SIDE
}

/** Maps a rail side to the frame Box alignment. Pure, so the unit test pins it. */
fun sideBarAlignment(side: SideBarSide): Alignment = when (side) {
    SideBarSide.DRIVER_SIDE -> Alignment.CenterStart
    SideBarSide.PASSENGER_SIDE -> Alignment.CenterEnd
}
