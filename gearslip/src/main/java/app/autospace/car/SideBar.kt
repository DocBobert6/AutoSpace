package app.autospace.car

import androidx.compose.ui.Alignment
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * r94: the steering-side app bar floats again. The r87 rail filled
 * the frame height, flush to the edge with square corners, and
 * Robert rejected that form on device: the rail must not touch the
 * corners and edges, and its corners follow the button style. The
 * r94 rail keeps r87's full height and reserved strip, but floats
 * inside it — 12dp margins on every side (see NAV_BAR_SIDE_FLOAT),
 * corners through gsButtonShape — so the strip is the rail's width
 * plus both margins (see NAV_BAR_SIDE_STRIP_WIDTH): the scaffold
 * pads the content Column by that amount on the rail's side, so
 * every content surface sits beside the rail instead of under it,
 * and the rail is the only thing that draws in the strip.
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
 * r97 graduation: the rail is the only app bar now — there is no gate
 * and no bottom bar to return to. The scaffold still hides it with the
 * bar (keyboard, immersive player/browser, mirror); see CarUi.
 */

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
 * r103: digits-only clock text for the bar: hour:minute, no day
 * marker. Locales whose SHORT form already has no marker (24-hour
 * locales, "21:07") render exactly as before; locales whose SHORT
 * form carries a marker render "h:mm" ("9:07", "12:00") — the
 * digits come from a real format pattern in both cases, never
 * from stripping a marker out of a formatted string. A SHORT form
 * that is not a SimpleDateFormat falls back to the locale SHORT
 * as-is. Pure, so the unit test pins it.
 */
fun railClockDigits(
    date: Date,
    locale: Locale = Locale.getDefault(),
    timeZone: TimeZone = TimeZone.getDefault(),
): String {
    val short = DateFormat.getTimeInstance(DateFormat.SHORT, locale)
    val hasMarker = (short as? SimpleDateFormat)?.toPattern()?.contains('a') == true
    val format = if (hasMarker) SimpleDateFormat("h:mm", locale) else short
    format.timeZone = timeZone
    return format.format(date)
}

/**
 * r99: which floating bar form the Meridian nav bar takes. The form
 * is exclusive: a display gets the side rail or the bottom pill,
 * never both. A display is "wide" only when it is landscape with
 * room for a vertical stack — at least 600dp wide and 400dp tall;
 * anything narrower, shorter, or portrait gets the bottom pill, so
 * the bar never crowds a small frame. Frames are pixels, so the
 * comparison converts through the frame's density; a frame with no
 * density (densityDpi <= 0) is treated as wide, keeping the rail
 * rather than dropping to the pill on a bad reading. Pure, so the
 * unit test pins it.
 */
enum class MeridianBarForm { RAIL, PILL }

fun meridianBarForm(widthPx: Int, heightPx: Int, densityDpi: Int): MeridianBarForm {
    if (densityDpi <= 0) return MeridianBarForm.RAIL
    val widthDp = widthPx * 160f / densityDpi
    val heightDp = heightPx * 160f / densityDpi
    return if (widthDp >= heightDp && widthDp >= 600f && heightDp >= 400f) {
        MeridianBarForm.RAIL
    } else {
        MeridianBarForm.PILL
    }
}
