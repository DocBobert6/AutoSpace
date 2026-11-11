package app.autospace.car

/**
 * r222 (Dashboard): the palette tokens for the Dashboard scheme, built
 * from Robert's own 2019 Nissan Altima dash photos (night + daylight),
 * sampled from the photos, not invented:
 *
 * r223: the accents flipped — steel blue is the primary now,
 *   amber the secondary — and light mode is two grey levels only.
 * r224: amber is OUT entirely — the only amber on his dash is
 *   warning lights, and he does not want it. White takes the
 *   highlight role (warm white #F5F2EC); signal red (#BA1A1A)
 *   takes the deep-accent role and the error role.
 * - Dark: the unlit panel is a blue-black (#0E0E14); the primary
 *   accent is steel blue (#3E85A7, the LCD colour — blue means a
 *   control); highlights are warm white; signal red (#BA1A1A) is
 *   the deep accent and the error; the hairlines are warm silver.
 * - Light: two grey levels only — the dark-grey field (#2F3237) and
 *   near-black buttons (#1A1C1F) — warm-white text (#F5F2EC),
 *   steel blue lightened to #7FB3C8 for text and legends, white
 *   highlights, signal red for the deep accent and the error.
 *
 * RED RULE (Robert, 2026-11-11): red lives in exactly two spots —
 * the tertiary role (the redline cap on bars, selected marks) and
 * the error role. No red backgrounds, no red text, and the thin
 * red line he vetoed stays out. The no-red rule is gone; the pin
 * in DashboardTest (red only in the approved tokens) is what
 * keeps the rule from drifting back.
 *
 * Contrast (WCAG sRGB, opaque RGB) — the numbers the legend gate in
 * GsControls relies on: steel blue on dark 4.69:1, light steel blue
 * on the light field 5.63:1, warm white on the light field 11.51:1,
 * dark on warm white 17.22:1, white on signal red 6.46:1, dark on
 * light steel blue 7.72:1. Signal red reads only 2.98:1 on dark and
 * 1.99:1 on the light field, so it still falls back to warm white
 * there (see clusterLegendColor); the old pale light greys (#62666D,
 * amber 2.08:1, steel blue 2.52:1) are gone, and so is every amber.
 */
object DashboardPalette {
    // Dark — the night dash.
    const val backgroundDark: Int = 0xFF0E0E14.toInt()
    const val onSurfaceDark: Int = 0xFFF5F2EC.toInt()
    const val surfaceVariantDark: Int = 0xFF1C1C23.toInt()
    const val onSurfaceVariantDark: Int = 0xFFB9B2A4.toInt()
    const val primaryDark: Int = 0xFF3E85A7.toInt()
    const val onPrimaryDark: Int = 0xFF0E0E14.toInt()
    const val primaryContainerDark: Int = 0xFF12283A.toInt()
    const val onPrimaryContainerDark: Int = 0xFFCFE6F2.toInt()
    const val secondaryDark: Int = 0xFFF5F2EC.toInt()
    const val onSecondaryDark: Int = 0xFF0E0E14.toInt()
    const val secondaryContainerDark: Int = 0xFF453F38.toInt()
    const val onSecondaryContainerDark: Int = 0xFFF5F2EC.toInt()
    const val tertiaryDark: Int = 0xFFBA1A1A.toInt()
    const val onTertiaryDark: Int = 0xFFFFFFFF.toInt()
    const val tertiaryContainerDark: Int = 0xFF401212.toInt()
    const val onTertiaryContainerDark: Int = 0xFFF6CFCF.toInt()
    const val outlineDark: Int = 0xFF8A8378.toInt()
    const val outlineVariantDark: Int = 0xFF3A3A42.toInt()
    // Light — the daylight dash, two grey levels only: the dark-grey
    // field (#2F3237) and near-black buttons (#1A1C1F). Panels stay on
    // the field grey; the trim dividers do the separating, not grey steps.
    const val backgroundLight: Int = 0xFF2F3237.toInt()
    const val onSurfaceLight: Int = 0xFFF5F2EC.toInt()
    const val buttonLight: Int = 0xFF1A1C1F.toInt()
    const val onSurfaceVariantLight: Int = 0xFFD8D2C6.toInt()
    const val primaryLight: Int = 0xFF7FB3C8.toInt()
    const val onPrimaryLight: Int = 0xFF0E1A22.toInt()
    const val primaryContainerLight: Int = 0xFFCFE6F2.toInt()
    const val onPrimaryContainerLight: Int = 0xFF0E1A22.toInt()
    const val secondaryLight: Int = 0xFFF5F2EC.toInt()
    const val onSecondaryLight: Int = 0xFF1A1C1F.toInt()
    const val secondaryContainerLight: Int = 0xFFE8E2D6.toInt()
    const val onSecondaryContainerLight: Int = 0xFF2A2622.toInt()
    const val tertiaryLight: Int = 0xFFBA1A1A.toInt()
    const val onTertiaryLight: Int = 0xFFFFFFFF.toInt()
    const val tertiaryContainerLight: Int = 0xFFF6D5D5.toInt()
    const val onTertiaryContainerLight: Int = 0xFF401212.toInt()
    const val outlineLight: Int = 0xFF9A9DA2.toInt()
    const val outlineVariantLight: Int = 0xFF55585E.toInt()
    // Error, both modes: signal red (see the red rule above).
    const val error: Int = 0xFFBA1A1A.toInt()
    const val onError: Int = 0xFFFFFFFF.toInt()
}
