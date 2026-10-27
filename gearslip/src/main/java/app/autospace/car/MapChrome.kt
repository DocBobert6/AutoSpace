package app.autospace.car

/**
 * r61: which look the guidance chrome wears. The turn card and the trip pill
 * (the guidance chrome) can either follow the current theme or recreate
 * Google's navigation look. This is a normal Appearance choice, not an
 * experimental toggle: the choice itself is the opt-in, like a color
 * scheme or a button style. It covers the guidance chrome only; panes,
 * edge controls, and header buttons stay themed in both modes.
 */
enum class MapChrome {
    /** Google's navigation look: fixed colors, solid surfaces, fixed corners. */
    GOOGLE,

    /** Today's behavior: scheme colors, frosted treatment, style corners. */
    THEMED,
}

/**
 * r61: the Google-recreation palette, as ARGB longs so the pure mapping
 * stays free of Compose types (and JVM-testable). These values were
 * sampled from the reference rendering photo, exact to that photo —
 * they are NOT Google's official design tokens, which are unpublished,
 * and must never be presented as such. The minutes green was sampled
 * from small anti-aliased text, so it is the softest of the four.
 */
object MapChromeColors {
    /** The turn card's solid fill. */
    const val GOOGLE_CARD: Long = 0xFF3E6F74L

    /** The darker band behind the next-step strip on the turn card. */
    const val GOOGLE_STRIP: Long = 0xFF335C5EL

    /** The trip pill's minutes, large on top. */
    const val GOOGLE_MINUTES: Long = 0xFF789A75L

    /** The trip pill's solid fill. */
    const val GOOGLE_ETA: Long = 0xFF131429L

    /** The trip pill's Stop button: white disc, dark glyph, on the dark pill. */
    const val GOOGLE_STOP_BACKGROUND: Long = 0xFFFFFFFFL
    const val GOOGLE_STOP_CONTENT: Long = 0xFF131429L
}

/**
 * Parses a stored map-chrome value. Anything unrecognized — including a
 * blank value on a fresh install or an upgrade from before this setting
 * existed — lands on GOOGLE, which is the default.
 */
fun mapChromeFromStored(stored: String): MapChrome = when (stored) {
    MapChrome.THEMED.name -> MapChrome.THEMED
    else -> MapChrome.GOOGLE
}

/** The next-step strip exists only in Google mode, and only when a next step exists. */
fun showNextStrip(google: Boolean, hasNext: Boolean): Boolean = google && hasNext

/**
 * The strip's small text: the next step's instruction when it has one,
 * else the street it turns onto. Null when neither exists, so the strip
 * is glyphs alone rather than an empty line.
 */
fun nextStripText(nextCue: String, nextRoad: String): String? =
    nextCue.takeIf { it.isNotEmpty() } ?: nextRoad.takeIf { it.isNotEmpty() }
