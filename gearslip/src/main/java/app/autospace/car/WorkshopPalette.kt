package app.autospace.car

/**
 * r142 — Workshop palette tokens as plain ARGB Ints, so the
 * contrast rules are JVM-testable without a Compose runtime (see
 * WorkshopTest). [CarUi.workshopScheme] is a mechanical mapping
 * of these tokens into a Material3 ColorScheme; the tokens, not
 * the mapping, carry the spec.
 *
 * SETTLED values (the Skeuomorphic scheme pick): dark background
 * deep espresso #2B1D16, light background parchment #F5EEDD,
 * primary brass #C9A227, secondary copper #B87333, tertiary
 * olive #6B7C3F; cards dark #3A2A1E, light #EFE6CF. Every other
 * token is DERIVED, following Verve's relationships, and the
 * KDoc says so rather than dressing derivation up as spec.
 *
 * Contrast, measured (see WorkshopTest): parchment on espresso
 * 14.07:1 both ways; dark primary pair is espresso text on
 * brass 6.73:1 (the Circuit yellow pattern — dark text on a
 * light accent); secondary pair is black on copper 5.54:1
 * (espresso on copper is only 4.29:1, so black, not espresso);
 * tertiary pair is white on olive 4.59:1. The light variant
 * keeps the same three accents (NOT deepened like Verve's):
 * the pick's own watch-out is that brass on parchment is only
 * 2.09:1, so brass stays on fills and accents in light mode,
 * never small text on parchment — the onPrimary pair
 * (espresso on brass, 6.73:1) is what carries primary-fill
 * text. All container pairs pass 4.5:1 (dark 8.45–11.12:1,
 * light 6.80–9.95:1). Workshop does NOT force dark (see
 * schemeForcesDark): it follows the mode control like Verve
 * and Horizon, so both variants ship.
 */
object WorkshopPalette {
    // Dark: deep espresso background, parchment text.
    val backgroundDark = 0xFF2B1D16.toInt()
    val onSurfaceDark = 0xFFF5EEDD.toInt()
    val surfaceVariantDark = 0xFF3A2A1E.toInt()
    val onSurfaceVariantDark = 0xFFD8CFC0.toInt()
    val primaryDark = 0xFFC9A227.toInt()
    val onPrimaryDark = 0xFF2B1D16.toInt()
    val primaryContainerDark = 0xFF4A3712.toInt()
    val onPrimaryContainerDark = 0xFFF5EEDD.toInt()
    val secondaryDark = 0xFFB87333.toInt()
    val onSecondaryDark = 0xFF000000.toInt()
    val secondaryContainerDark = 0xFF4A2A14.toInt()
    val onSecondaryContainerDark = 0xFFF5EEDD.toInt()
    val tertiaryDark = 0xFF6B7C3F.toInt()
    val onTertiaryDark = 0xFFFFFFFF.toInt()
    val tertiaryContainerDark = 0xFF333F1E.toInt()
    val onTertiaryContainerDark = 0xFFF5EEDD.toInt()
    val outlineDark = 0xFF6B5A4A.toInt()
    val outlineVariantDark = 0xFF4A3A2E.toInt()

    // Light: parchment background, espresso text, same accents.
    val backgroundLight = 0xFFF5EEDD.toInt()
    val onSurfaceLight = 0xFF2B1D16.toInt()
    val surfaceVariantLight = 0xFFEFE6CF.toInt()
    val onSurfaceVariantLight = 0xFF5C4A33.toInt()
    val primaryLight = 0xFFC9A227.toInt()
    val onPrimaryLight = 0xFF2B1D16.toInt()
    val primaryContainerLight = 0xFFEFE3B8.toInt()
    val onPrimaryContainerLight = 0xFF3D3208.toInt()
    val secondaryLight = 0xFFB87333.toInt()
    val onSecondaryLight = 0xFF000000.toInt()
    val secondaryContainerLight = 0xFFF0DDC8.toInt()
    val onSecondaryContainerLight = 0xFF4A2A14.toInt()
    val tertiaryLight = 0xFF6B7C3F.toInt()
    val onTertiaryLight = 0xFFFFFFFF.toInt()
    val tertiaryContainerLight = 0xFFE2E8C8.toInt()
    val onTertiaryContainerLight = 0xFF2E3A1A.toInt()
    val outlineLight = 0xFFC9BFA8.toInt()
    val outlineVariantLight = 0xFFD8CFC0.toInt()
}
