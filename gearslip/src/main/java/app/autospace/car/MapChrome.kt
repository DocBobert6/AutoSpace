package app.autospace.car

/**
 * r65: which look the map chrome wears. The turn card, the trip pill,
 * the map panes, and the edge controls can either follow the current
 * theme or wear the Meridian look: fixed colors, solid surfaces, fixed
 * corners. Meridian is experimental and gated by ONE toggle: it renders
 * only when experimental features are on AND the Meridian toggle is on
 * (see [effectiveMapChrome]); Themed is the default. The r63
 * Themed/Meridian choice row is retired: the toggle absorbs it, so one
 * control decides the whole look and no stored choice can disagree
 * with it. Standalone map header buttons stay themed in both modes.
 */
enum class MapChrome {
    /** The fixed-color look: solid surfaces, fixed corners, same in every theme. */
    MERIDIAN,

    /** Today's behavior: scheme colors, frosted treatment, style corners. */
    THEMED,
}

/**
 * r61: the Meridian palette, as ARGB longs so the pure mapping stays free
 * of Compose types (and JVM-testable). These values were sampled from a
 * reference rendering photo, exact to that photo — they are NOT Google's
 * official design tokens, which are unpublished, and must never be
 * presented as such. The minutes green was sampled from small
 * anti-aliased text, so it is the softest of the four. r65 adds the
 * pane and edge values in the same language: they are our own design
 * choice, not a Google look — Google's treatment of panes and edge
 * controls is publicly undocumented — so they carry the same honesty
 * note.
 */
object MapChromeColors {
    /** The turn card's solid fill. */
    const val MERIDIAN_CARD: Long = 0xFF3E6F74L

    /** The darker band behind the next-step strip on the turn card. */
    const val MERIDIAN_STRIP: Long = 0xFF335C5EL

    /** The trip pill's minutes, large on top. */
    const val MERIDIAN_MINUTES: Long = 0xFF789A75L

    /** The trip pill's solid fill. */
    const val MERIDIAN_ETA: Long = 0xFF131429L

    /** The trip pill's Stop button: white disc, dark glyph, on the dark pill. */
    const val MERIDIAN_STOP_BACKGROUND: Long = 0xFFFFFFFFL
    const val MERIDIAN_STOP_CONTENT: Long = 0xFF131429L

    /** r65: a map pane's solid fill — the trip pill's dark, by design. */
    const val MERIDIAN_PANE: Long = 0xFF131429L

    /** r65: an edge control's solid disc — the same dark, by design. */
    const val MERIDIAN_EDGE: Long = 0xFF131429L
}

/** r65: secondary text on Meridian dark surfaces — white, dimmed to keep the title/detail hierarchy. */
const val MERIDIAN_DIM_ALPHA = 0.75f

/**
 * Parses a stored map-chrome value. r63: a stored r61/r62 "GOOGLE" value
 * migrates to MERIDIAN, so the rename itself never resets anyone's
 * choice. Anything else unrecognized — including a blank value on a
 * fresh install or an upgrade from before this setting existed —
 * lands on THEMED, which is the default. r65: kept only for the
 * one-time toggle migration ([migrateMeridianToggle]); nothing renders
 * from a stored choice anymore.
 */
fun mapChromeFromStored(stored: String): MapChrome = when (stored) {
    MapChrome.MERIDIAN.name -> MapChrome.MERIDIAN
    "GOOGLE" -> MapChrome.MERIDIAN
    MapChrome.THEMED.name -> MapChrome.THEMED
    else -> MapChrome.THEMED
}

/**
 * r65: the one-time migration from the retired choice to the toggle. A
 * stored Meridian choice — including a legacy "GOOGLE" value, which
 * parses to MERIDIAN above — carries the toggle on; anything else
 * (Themed, blank, unrecognized) leaves it off. Pure so it is
 * unit-testable; [CarSettings] owns running it once and clearing the
 * old key so it can never re-run and overwrite a later choice.
 */
fun migrateMeridianToggle(stored: String): Boolean =
    mapChromeFromStored(stored) == MapChrome.MERIDIAN

/**
 * r65: the experimental gate, and the ONLY gate. Meridian renders only
 * when experimental features are on AND the Meridian toggle is on;
 * anything else renders Themed. There is no per-surface toggle and no
 * stored choice anymore, so the turn card, trip pill, panes, and edge
 * controls can never disagree about which look they wear.
 */
fun effectiveMapChrome(meridianToggle: Boolean, experimentalOn: Boolean): MapChrome =
    if (experimentalOn && meridianToggle) MapChrome.MERIDIAN else MapChrome.THEMED

/** The next-step strip exists only in Meridian mode, and only when a next step exists. */
fun showNextStrip(meridian: Boolean, hasNext: Boolean): Boolean = meridian && hasNext

/**
 * The strip's small text: the next step's instruction when it has one,
 * else the street it turns onto. Null when neither exists, so the strip
 * is glyphs alone rather than an empty line.
 */
fun nextStripText(nextCue: String, nextRoad: String): String? =
    nextCue.takeIf { it.isNotEmpty() } ?: nextRoad.takeIf { it.isNotEmpty() }
