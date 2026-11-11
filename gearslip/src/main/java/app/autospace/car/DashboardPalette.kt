package app.autospace.car

/**
 * r222 (Dashboard): the palette tokens for the Dashboard scheme, built
 * from Robert's own 2019 Nissan Altima dash photos (night + daylight),
 * sampled from the photos, not invented:
 *
 * - Dark: the unlit panel is a blue-black (#0E0E14); the backlight is
 *   burnt amber (#A3541F, the fill accent) to light amber (#CD8E48,
 *   the legend/highlight amber); the LCD is steel blue (#3E85A7);
 *   the hairlines are warm silver.
 * - Light: mid-to-dark greys instead of blacks (the daylight dash
 *   measures #404445-#8B929A), warm-white text (#F5F2EC), amber kept
 *   for glows/fills/large labels, steel blue lightened to #7FB3C8
 *   for text.
 *
 * HARD RULE (Robert, 2026-11-11): NO red anywhere in Dashboard, in
 * either mode — so the error role is a deep umber (#6B3410), the only
 * red-free way to keep a distinct destructive signal; standard red
 * error would violate the rule. One word from Robert restores
 * standard red error.
 *
 * Contrast (WCAG sRGB, opaque RGB) — the numbers the legend gate in
 * GsControls relies on: amber legend on dark 6.92:1, steel blue on
 * dark 4.69:1, warm white on light grey 5.16:1, white on burnt amber
 * 5.46:1, dark on light amber 6.15:1, dark on light steel blue
 * 7.72:1, white on umber 9.89:1. Amber on light grey reads only
 * 2.08:1 and steel blue on light grey only 2.52:1, so in light mode
 * the legend falls back to warm white and only the glow keeps the
 * accent colour (see clusterLegendColor).
 */
object DashboardPalette {
    // Dark — the night dash.
    const val backgroundDark: Int = 0xFF0E0E14.toInt()
    const val onSurfaceDark: Int = 0xFFF5F2EC.toInt()
    const val surfaceVariantDark: Int = 0xFF1C1C23.toInt()
    const val onSurfaceVariantDark: Int = 0xFFB9B2A4.toInt()
    const val primaryDark: Int = 0xFFA3541F.toInt()
    const val onPrimaryDark: Int = 0xFFFFFFFF.toInt()
    const val primaryContainerDark: Int = 0xFF4A2608.toInt()
    const val onPrimaryContainerDark: Int = 0xFFF2C896.toInt()
    const val secondaryDark: Int = 0xFFCD8E48.toInt()
    const val onSecondaryDark: Int = 0xFF0E0E14.toInt()
    const val secondaryContainerDark: Int = 0xFF3A2410.toInt()
    const val onSecondaryContainerDark: Int = 0xFFF2D3A8.toInt()
    const val tertiaryDark: Int = 0xFF3E85A7.toInt()
    const val onTertiaryDark: Int = 0xFF0E0E14.toInt()
    const val tertiaryContainerDark: Int = 0xFF12283A.toInt()
    const val onTertiaryContainerDark: Int = 0xFFCFE6F2.toInt()
    const val outlineDark: Int = 0xFF8A8378.toInt()
    const val outlineVariantDark: Int = 0xFF3A3A42.toInt()
    // Light — the daylight dash (mid-to-dark greys, never black).
    const val backgroundLight: Int = 0xFF62666D.toInt()
    const val onSurfaceLight: Int = 0xFFF5F2EC.toInt()
    const val surfaceVariantLight: Int = 0xFF45484F.toInt()
    const val onSurfaceVariantLight: Int = 0xFFD8D2C6.toInt()
    const val primaryLight: Int = 0xFFA3541F.toInt()
    const val onPrimaryLight: Int = 0xFFFFFFFF.toInt()
    const val primaryContainerLight: Int = 0xFFF2C896.toInt()
    const val onPrimaryContainerLight: Int = 0xFF4A2608.toInt()
    const val secondaryLight: Int = 0xFFCD8E48.toInt()
    const val onSecondaryLight: Int = 0xFF1A1C1F.toInt()
    const val secondaryContainerLight: Int = 0xFFE8C896.toInt()
    const val onSecondaryContainerLight: Int = 0xFF2A1A08.toInt()
    const val tertiaryLight: Int = 0xFF7FB3C8.toInt()
    const val onTertiaryLight: Int = 0xFF0E1A22.toInt()
    const val tertiaryContainerLight: Int = 0xFFCFE6F2.toInt()
    const val onTertiaryContainerLight: Int = 0xFF0E1A22.toInt()
    const val outlineLight: Int = 0xFF9A9DA2.toInt()
    const val outlineVariantLight: Int = 0xFF55585E.toInt()
    // Error, both modes: deep umber, not red (see the hard rule above).
    const val error: Int = 0xFF6B3410.toInt()
    const val onError: Int = 0xFFFFFFFF.toInt()
}
