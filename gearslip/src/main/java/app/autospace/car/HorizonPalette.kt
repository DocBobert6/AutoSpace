package app.autospace.car

/**
 * r79 — Horizon palette tokens as plain ARGB Ints, so the contrast rules
 * are JVM-testable without a Compose runtime (see HorizonTest).
 * [CarUi.horizonScheme] is a mechanical mapping of these tokens into a
 * Material3 ColorScheme; the tokens, not the mapping, carry the spec.
 *
 * SETTLED values (approved 2026-10-26): dark bg #0B1526, dark onSurface
 * #E9EEF7, dark primary #1E6FD9 (white on it is 4.85:1), dark secondary
 * mint #7FE0C3, dark tertiary #4CC06A; light bg #F4F8FF, light onSurface
 * #16233A, light primary #1D6FE0 (white on it is 4.77:1). Every other
 * token is DERIVED, following Verve's relationships, and the KDoc says
 * so rather than dressing derivation up as spec: the dark accents take
 * dark text (mint on navy 11.62:1, green on navy 7.88:1 — Verve's own
 * tertiary pattern, dark text on a light accent), and the light accents
 * are deepened per Verve's pattern (mint #177A52 at 5.33:1 white, green
 * #1E7A45 at 5.35:1 white) instead of reusing the pale dark accents on a
 * pale background, where they would wash out.
 */
object HorizonPalette {
    // Dark: deep navy background, sky-tinted text.
    val backgroundDark = 0xFF0B1526.toInt()
    val onSurfaceDark = 0xFFE9EEF7.toInt()
    val surfaceVariantDark = 0xFF1E2E4A.toInt()
    val onSurfaceVariantDark = 0xFFB9C6DC.toInt()
    val primaryDark = 0xFF1E6FD9.toInt()
    val onPrimaryDark = 0xFFFFFFFF.toInt()
    val primaryContainerDark = 0xFF12294D.toInt()
    val onPrimaryContainerDark = 0xFFD6E6FB.toInt()
    val secondaryDark = 0xFF7FE0C3.toInt()
    val onSecondaryDark = 0xFF0B1526.toInt()
    val secondaryContainerDark = 0xFF14352B.toInt()
    val onSecondaryContainerDark = 0xFFC9F2DF.toInt()
    val tertiaryDark = 0xFF4CC06A.toInt()
    val onTertiaryDark = 0xFF0B1526.toInt()
    val tertiaryContainerDark = 0xFF12351F.toInt()
    val onTertiaryContainerDark = 0xFFC9EED6.toInt()
    val outlineDark = 0xFF35456A.toInt()
    val outlineVariantDark = 0xFF232F4A.toInt()

    // Light: sky-tinted white background, deep navy text, deepened accents.
    val backgroundLight = 0xFFF4F8FF.toInt()
    val onSurfaceLight = 0xFF16233A.toInt()
    val surfaceVariantLight = 0xFFE2EAF7.toInt()
    val onSurfaceVariantLight = 0xFF44546E.toInt()
    val primaryLight = 0xFF1D6FE0.toInt()
    val onPrimaryLight = 0xFFFFFFFF.toInt()
    val primaryContainerLight = 0xFFD6E6FB.toInt()
    val onPrimaryContainerLight = 0xFF0A2A5C.toInt()
    val secondaryLight = 0xFF177A52.toInt()
    val onSecondaryLight = 0xFFFFFFFF.toInt()
    val secondaryContainerLight = 0xFFC9F2DF.toInt()
    val onSecondaryContainerLight = 0xFF0A3527.toInt()
    val tertiaryLight = 0xFF1E7A45.toInt()
    val onTertiaryLight = 0xFFFFFFFF.toInt()
    val tertiaryContainerLight = 0xFFC9EED6.toInt()
    val onTertiaryContainerLight = 0xFF0A2E1A.toInt()
    val outlineLight = 0xFF7A8AA8.toInt()
    val outlineVariantLight = 0xFFC3D0E2.toInt()
}
