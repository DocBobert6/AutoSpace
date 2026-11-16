package app.autospace.car

/**
 * r226 (Neon Rain): the palette tokens for the Neon Rain scheme —
 * the most detailed theme so far. No game is referenced anywhere:
 * the name is two genre words, and the colours are the genre's own
 * neon language, not any one work's.
 *
 * - Dark ONLY: the background is near-black with a blue tint
 *   (#0A0A12); neon cyan (#00F0FF) is the primary and the calm
 *   default; hot magenta (#FF2A6D) is the secondary and the active
 *   colour — magenta is what "on" looks like; acid yellow (#FCEE0A)
 *   is the tertiary, used SPARINGLY (hazard stripes + selected-
 *   marker accents only, never as a field fill); signal red
 *   (#FF003C) is the error. The yellow is ACID yellow, not amber:
 *   no amber token (#A3541F / #CD8E48 / #6B3410) lives in this
 *   file, and the test pins that none ever does.
 * - Red lives in exactly two spots — the tertiary role is yellow,
 *   so red is the error role only, plus the hazard-adjacent
 *   accents the scheme draws from it. No red backgrounds, no red
 *   text, and the thin red line Robert vetoed stays out.
 * - There is no light variant: Neon Rain forces dark
 *   (schemeForcesDark), exactly like Night, Pitlane, and Meridian,
 *   so the palette carries dark tokens only — no dead light
 *   tokens to drift.
 *
 * Contrast (WCAG sRGB, opaque RGB) — the numbers the legend gate
 * in GsControls relies on: cyan on the field 14.00:1, magenta on
 * the field 5.45:1, yellow on the field 16.31:1, on-surface on the
 * field 17.89:1. Every role pair clears 4.5:1 (onPrimary/primary
 * 13.48:1, onSecondary/secondary 5.54:1, onTertiary/tertiary
 * 14.58:1, containers 11.31:1 / 11.06:1 / 10.81:1) EXCEPT white
 * on error, 3.95:1 — modest, the same class as Dashboard's red
 * note (r224): error text on the field reads 4.99:1, and white
 * text on a red chip is the fix if it reads poorly on device.
 */
object NeonRainPalette {
    const val background: Int = 0xFF0A0A12.toInt()
    const val onSurface: Int = 0xFFE8F6FF.toInt()
    const val surfaceVariant: Int = 0xFF14141F.toInt()
    const val onSurfaceVariant: Int = 0xFF8FA3B8.toInt()
    const val primary: Int = 0xFF00F0FF.toInt()
    const val onPrimary: Int = 0xFF001318.toInt()
    const val primaryContainer: Int = 0xFF00333D.toInt()
    const val onPrimaryContainer: Int = 0xFFB8F4FF.toInt()
    const val secondary: Int = 0xFFFF2A6D.toInt()
    const val onSecondary: Int = 0xFF1A000A.toInt()
    const val secondaryContainer: Int = 0xFF3D0A1E.toInt()
    const val onSecondaryContainer: Int = 0xFFFFC2D6.toInt()
    const val tertiary: Int = 0xFFFCEE0A.toInt()
    const val onTertiary: Int = 0xFF1A1A00.toInt()
    const val tertiaryContainer: Int = 0xFF3D3A00.toInt()
    const val onTertiaryContainer: Int = 0xFFFFF9B8.toInt()
    const val outline: Int = 0xFF2A3A4A.toInt()
    const val outlineVariant: Int = 0xFF1A2432.toInt()
    const val error: Int = 0xFFFF003C.toInt()
    const val onError: Int = 0xFFFFFFFF.toInt()
}
