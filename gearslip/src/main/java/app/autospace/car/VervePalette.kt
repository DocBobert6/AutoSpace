package app.autospace.car

/**
 * r48 — Verve palette tokens as plain ARGB Ints, so the contrast rules
 * are JVM-testable without a Compose runtime (see VerveTest).
 * [CarUi.verveScheme] is a mechanical mapping of these tokens into a
 * Material3 ColorScheme; the tokens, not the mapping, carry the spec.
 *
 * The approved spec palette, with ONE correction, made under the spec's
 * own rule ("if a value fails, darken the accent, not the text"):
 * spec dark primary #8B5CF6 with white text measures 4.23:1, under the
 * 4.5:1 selected-chip bar, so dark primary is #8347E8 (5.21:1).
 * Every other spec value is exact. Dark secondary #FF5C7A with white
 * measures 2.97:1 and is deliberately UNCHANGED: the spec pins only
 * the onPrimary/primary pair, hot coral is the scheme's identity, and
 * no kit selection control puts text on a secondary background (chip
 * selection is primary) — the number is reported, not hidden.
 */
object VervePalette {
    // Dark (default character): ink background, soft white-violet text.
    val backgroundDark = 0xFF16121F.toInt()
    val onSurfaceDark = 0xFFF2EFFA.toInt()
    val surfaceVariantDark = 0xFF2A2340.toInt()
    val onSurfaceVariantDark = 0xFFCFC6E8.toInt()
    val primaryDark = 0xFF8347E8.toInt()
    val onPrimaryDark = 0xFFFFFFFF.toInt()
    val primaryContainerDark = 0xFF2E2152.toInt()
    val onPrimaryContainerDark = 0xFFE4DCFF.toInt()
    val secondaryDark = 0xFFFF5C7A.toInt()
    val onSecondaryDark = 0xFFFFFFFF.toInt()
    val secondaryContainerDark = 0xFF5C1A2B.toInt()
    val onSecondaryContainerDark = 0xFFFFD9E0.toInt()
    val tertiaryDark = 0xFFFFC53D.toInt()
    val onTertiaryDark = 0xFF1A1A1A.toInt()
    val tertiaryContainerDark = 0xFF4A3600.toInt()
    val onTertiaryContainerDark = 0xFFFFE9A8.toInt()
    val outlineDark = 0xFF4A4266.toInt()
    val outlineVariantDark = 0xFF332C4D.toInt()

    // Light: white background, deepened accents.
    val backgroundLight = 0xFFFFFFFF.toInt()
    val onSurfaceLight = 0xFF1E1A2E.toInt()
    val surfaceVariantLight = 0xFFEDE9F5.toInt()
    val onSurfaceVariantLight = 0xFF4A4360.toInt()
    val primaryLight = 0xFF6D28D9.toInt()
    val onPrimaryLight = 0xFFFFFFFF.toInt()
    val primaryContainerLight = 0xFFE9DEFF.toInt()
    val onPrimaryContainerLight = 0xFF2E1065.toInt()
    val secondaryLight = 0xFFE11D48.toInt()
    val onSecondaryLight = 0xFFFFFFFF.toInt()
    val secondaryContainerLight = 0xFFFFE0E6.toInt()
    val onSecondaryContainerLight = 0xFF5C0012.toInt()
    val tertiaryLight = 0xFFB45309.toInt()
    val onTertiaryLight = 0xFFFFFFFF.toInt()
    val tertiaryContainerLight = 0xFFFDEBC8.toInt()
    val onTertiaryContainerLight = 0xFF3D2400.toInt()
    val outlineLight = 0xFF7A7390.toInt()
    val outlineVariantLight = 0xFFCFC8E0.toInt()
}
