package app.autospace.car

/**
 * r107 — Meridian scheme palette tokens as plain ARGB Ints, so the
 * contrast rules are JVM-testable without a Compose runtime (see
 * MeridianTest). [CarUi.meridianScheme] is a mechanical mapping of
 * these tokens into a Material3 ColorScheme; the tokens, not the
 * mapping, carry the spec.
 *
 * Dark only: the Meridian scheme forces dark (see schemeForcesDark),
 * so there is no light variant in this round. The tokens extend the
 * four photo-picked Meridian colors (MapChromeColors — sampled from
 * a reference rendering photo, NOT Google's official tokens, which
 * are unpublished): turn-card teal #3E6F74 as primary (white on it
 * 5.63:1), strip band #335C5E as primaryContainer (white on it
 * 7.41:1), near-black navy #131429 as background/surface (white on
 * it 18.11:1), and sage minutes green #789A75 as secondary — a
 * LARGE-ACCENT color only (navy on it 5.76:1, but it fails as small
 * text on white at 3.14:1, and the teal fails as small text on navy
 * at 3.22:1, so neither is ever body text on the other's surface).
 * Surfaces step up from the navy in small increments (#1B1C33,
 * #232444, #2A2C50, #33355E; white on the highest step 11.59:1).
 * onSurfaceVariant is white at 0.75 alpha (MERIDIAN_DIM_ALPHA),
 * which blends to #C4C4CA over the navy background (~10.43:1).
 * Outline tokens are white at low alpha (borders, not text, so no
 * text-contrast bar applies). Error follows the app's existing dark
 * error red; the light map is unaffected (Vela draws map tiles).
 */
object MeridianPalette {
    val background = 0xFF131429.toInt()
    val onSurface = 0xFFFFFFFF.toInt()
    val surfaceVariant = 0xFF232444.toInt()
    val onSurfaceVariant = 0xBFFFFFFF.toInt()
    val surfaceContainerLowest = 0xFF131429.toInt()
    val surfaceContainerLow = 0xFF1B1C33.toInt()
    val surfaceContainer = 0xFF232444.toInt()
    val surfaceContainerHigh = 0xFF2A2C50.toInt()
    val surfaceContainerHighest = 0xFF33355E.toInt()
    val surfaceDim = 0xFF131429.toInt()
    val surfaceBright = 0xFF33355E.toInt()
    val primary = 0xFF3E6F74.toInt()
    val onPrimary = 0xFFFFFFFF.toInt()
    val primaryContainer = 0xFF335C5E.toInt()
    val onPrimaryContainer = 0xFFFFFFFF.toInt()
    val secondary = 0xFF789A75.toInt()
    val onSecondary = 0xFF131429.toInt()
    val secondaryContainer = 0xFF335C5E.toInt()
    val onSecondaryContainer = 0xFFFFFFFF.toInt()
    val tertiary = 0xFFFFFFFF.toInt()
    val onTertiary = 0xFF131429.toInt()
    val outline = 0x40FFFFFF.toInt()
    val outlineVariant = 0x1FFFFFFF.toInt()
    val error = 0xFFFF453A.toInt()
    val onError = 0xFFFFFFFF.toInt()
    val surfaceTint = 0xFF3E6F74.toInt()
    val inverseSurface = 0xFFFFFFFF.toInt()
    val inverseOnSurface = 0xFF131429.toInt()
    val inversePrimary = 0xFF3E6F74.toInt()
    val scrim = 0xFF000000.toInt()
}
