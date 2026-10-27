package app.autospace.car

import androidx.compose.ui.Alignment

/**
 * r87: the steering-side app bar is an edge dock, not a floating
 * pill. The r74 rail floated over the frame — a content-height
 * column, 64dp wide with 12dp margins, vertically centered — so it
 * covered the map pane's left edge, the apps grid's first column,
 * and settings pages' left text. The r87 rail fills the frame
 * height, flush to the edge, and owns a reserved strip exactly its
 * own width (see NAV_BAR_SIDE_STRIP_WIDTH): the scaffold pads the
 * content Column by that amount on the rail's side, so every
 * content surface sits beside the rail instead of under it, and
 * the rail is the only thing that draws in the strip.
 *
 * The r74 passenger-side yields are gone with the overlay: a rail
 * that never overlaps the turn card, the widget column, or the
 * media card has nothing to yield from, so it stays on its side
 * permanently. The side type and the alignment/strip mappings stay
 * as the seam a future steering-side setting would flip; nothing
 * today moves the rail off its side.
 *
 * Driver side is layout Start, the same mapping the widget column
 * already uses and the card dock uses (Start/End follow layout
 * direction, they are not absolute left and right). No
 * steering-side setting exists anywhere in the app, so in a
 * right-hand-drive car with a left-to-right locale the rail lands
 * on the passenger side — the named r74 limitation, unchanged by
 * this round; its proper fix is still a steering-side setting.
 *
 * The quick-tiles shade is transient and draws over everything,
 * rail included, exactly as it draws over the bottom bar — its
 * scrim stays full-screen, but its sheet content is constrained
 * to the content inset, so the sheet itself never draws in the
 * strip. The same split (full-screen scrim, inset-constrained
 * content) holds for the long-press menu, the icon picker, the
 * What's New card, and the brightness sheet. The brightness dim is
 * a scrim, not content, and keeps dimming the rail as today.
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

/** Maps a rail side to the frame Box alignment. Pure, so the unit test pins it. */
fun sideBarAlignment(side: SideBarSide): Alignment = when (side) {
    SideBarSide.DRIVER_SIDE -> Alignment.CenterStart
    SideBarSide.PASSENGER_SIDE -> Alignment.CenterEnd
}

/**
 * r87: which side of the content the rail's strip insets. The driver
 * side is layout Start, the passenger side layout End — the scaffold
 * builds PaddingValues(start/end) from this, so it never hardcodes
 * left or right. Pure, so the unit test pins it.
 */
fun sideBarStripOnStart(side: SideBarSide): Boolean = side == SideBarSide.DRIVER_SIDE

/**
 * r87: split a formatted short time into its digits and its day
 * marker, for the rail clock's stacked layout. "9:53 AM" becomes
 * ("9:53", "AM"); a 24-hour time like "23:59" becomes ("23:59",
 * null); a multi-word marker like "12:00 p. m." becomes ("12:00",
 * "p. m."). The split is on the first space, including the
 * non-breaking spaces some locales use between time and marker, so
 * a locale's marker can never hide inside the digits line. Pure, so
 * the unit test pins it.
 */
fun railClockParts(formatted: String): Pair<String, String?> {
    val trimmed = formatted.trim()
    val split = trimmed.indexOfFirst { it == ' ' || it == '\u00A0' || it == '\u202F' }
    if (split < 0) return trimmed to null
    val marker = trimmed.substring(split + 1).trim()
    return trimmed.substring(0, split) to marker.ifEmpty { null }
}
