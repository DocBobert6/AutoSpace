package app.autospace.car

/**
 * r142 — Workshop palette tokens as plain ARGB Ints, so the
 * contrast rules are JVM-testable without a Compose runtime (see
 * WorkshopTest). [CarUi.workshopScheme] is a mechanical mapping
 * of these tokens into a Material3 ColorScheme; the tokens, not
 * the mapping, carry the spec.
 *
 * r145 REVISION (Robert's verdict on r142: too much brown in
 * dark mode, the cream in light mode is not good, and the pair
 * does not read skeuomorphic enough — he wants the iOS 5 feel
 * captured better without being an exact clone). The brown
 * stops being the room and becomes the jewelry in it:
 *
 * Dark is now CHARCOAL LINEN, not espresso: background #252423
 * (a dark gray with only the faintest warmth — R37, G36, B35 —
 * clearly not brown), cards a warm dark gray #3B3733 that is
 * distinct from the background, not another espresso step.
 * Light is now COOL OFF-WHITE LINEN, almost silver:
 * background #F1F2F2, cards a matching cool light gray
 * #DFE2E1, with a subtle vertical gradient on the app
 * background from #F1F2F2 at the top to #E3E6E5 at the bottom
 * (see backgroundLightGradientBottom and the gradient sites
 * in CarUi), so the surface reads as a surface rather than a
 * flat tint.
 *
 * ACCENT-ONLY RULE (r145): brass #C9A227, copper #B87333,
 * and olive #6B7C3F stay exactly as the accent colors, and
 * from this round they appear ONLY as accents — fills on
 * buttons and highlights, toggles, latched rims, and primary
 * text on fills — never as broad background or container
 * fills. Every container token below is therefore a neutral
 * gray step of its variant's background/card ladder, NOT an
 * accent tint: the r142 accent-tinted containers (dark
 * #4A3712 / #4A2A14 / #333F1E, light #EFE3B8 / #F0DDC8 /
 * #E2E8C8) are gone.
 *
 * Contrast, measured (see WorkshopTest): dark text on
 * charcoal 13.93:1, on the card 10.60:1 (card's own onSurface
 * 8.06:1); light text on off-white 13.84:1, on the gradient
 * bottom 12.35:1, on the card 11.90:1 (card's own onSurface
 * 8.32:1). The three accent pairs are unchanged: espresso
 * text on brass 6.73:1, black on copper 5.54:1 (espresso on
 * copper is only 4.29:1, so black, not espresso), white on
 * olive 4.59:1. The light watch-out is re-measured, not
 * assumed: brass on the new off-white is 2.16:1 (brass on the
 * new card 1.86:1), so brass stays on fills and accents in
 * light mode, never small text on the light background or
 * card — the onPrimary pair (espresso on brass, 6.73:1) is
 * what carries primary-fill text. All container pairs pass
 * 4.5:1 (dark 8.95–12.51:1, light 11.13–12.94:1). Workshop
 * does NOT force dark (see schemeForcesDark): it follows the
 * mode control like Verve and Horizon, so both variants ship.
 */
object WorkshopPalette {
    // Dark: charcoal linen background, cool off-white text.
    val backgroundDark = 0xFF252423.toInt()
    val onSurfaceDark = 0xFFF2F3F2.toInt()
    val surfaceVariantDark = 0xFF3B3733.toInt()
    val onSurfaceVariantDark = 0xFFD8D5D0.toInt()
    val primaryDark = 0xFFC9A227.toInt()
    val onPrimaryDark = 0xFF2B1D16.toInt()
    val primaryContainerDark = 0xFF47423B.toInt()
    val onPrimaryContainerDark = 0xFFF2F3F2.toInt()
    val secondaryDark = 0xFFB87333.toInt()
    val onSecondaryDark = 0xFF000000.toInt()
    val secondaryContainerDark = 0xFF3B3733.toInt()
    val onSecondaryContainerDark = 0xFFF2F3F2.toInt()
    val tertiaryDark = 0xFF6B7C3F.toInt()
    val onTertiaryDark = 0xFFFFFFFF.toInt()
    val tertiaryContainerDark = 0xFF2E2C29.toInt()
    val onTertiaryContainerDark = 0xFFF2F3F2.toInt()
    val outlineDark = 0xFF6E6A63.toInt()
    val outlineVariantDark = 0xFF45413B.toInt()

    // Light: cool off-white linen background, charcoal text.
    // backgroundLightGradientBottom is the foot of the subtle
    // vertical gradient on the app background (CarUi draws it
    // under Workshop in light mode only); it is a background
    // color, never a text surface on its own.
    val backgroundLight = 0xFFF1F2F2.toInt()
    val backgroundLightGradientBottom = 0xFFE3E6E5.toInt()
    val onSurfaceLight = 0xFF232427.toInt()
    val surfaceVariantLight = 0xFFDFE2E1.toInt()
    val onSurfaceVariantLight = 0xFF3A3E3D.toInt()
    val primaryLight = 0xFFC9A227.toInt()
    val onPrimaryLight = 0xFF2B1D16.toInt()
    val primaryContainerLight = 0xFFD8DBDA.toInt()
    val onPrimaryContainerLight = 0xFF232427.toInt()
    val secondaryLight = 0xFFB87333.toInt()
    val onSecondaryLight = 0xFF000000.toInt()
    val secondaryContainerLight = 0xFFDFE2E1.toInt()
    val onSecondaryContainerLight = 0xFF232427.toInt()
    val tertiaryLight = 0xFF6B7C3F.toInt()
    val onTertiaryLight = 0xFFFFFFFF.toInt()
    val tertiaryContainerLight = 0xFFE8EBEA.toInt()
    val onTertiaryContainerLight = 0xFF232427.toInt()
    val outlineLight = 0xFFB9BDBB.toInt()
    val outlineVariantLight = 0xFFD5D8D7.toInt()
}
