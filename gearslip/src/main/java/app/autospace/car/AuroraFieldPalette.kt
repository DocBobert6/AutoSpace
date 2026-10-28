package app.autospace.car

/**
 * Aurora Field (2026-10-28, r143; the Frutiger Aero scheme
 * picked for the Aurora Field + Aurora pair): deep ocean dark
 * and pale aqua light, with a spring-green primary — the only
 * green-primary scheme in the tree — sky secondary, and lime
 * tertiary. Tokens are plain ARGB Ints, so the contrast rules
 * are JVM-testable; [CarUi.auroraFieldScheme] maps them
 * mechanically. Contrast (WCAG): pale aqua on deep ocean is
 * 15.15:1 both ways; ocean on spring green is 7.77:1; ocean
 * on sky is 7.85:1; black on lime is 13.93:1; card variants
 * carry the same on* colors and stay above 7:1. The one
 * flagged pair: lime on pale aqua is only 1.36:1, so tertiary
 * stays on small accents only in light mode, never small text
 * on the pale aqua background. Aurora Field does NOT force
 * dark (see schemeForcesDark): it follows the mode control
 * like Verve and Horizon, so both variants ship; Auto night
 * still overrides to NIGHT like every scheme. Accents are for
 * icons, selection and controls, never body text except via
 * the paired on* colors.
 */
object AuroraFieldPalette {
    // Dark variant.
    const val backgroundDark = 0xFF0A1F2E.toInt()
    const val onSurfaceDark = 0xFFE8F6F3.toInt()
    const val surfaceVariantDark = 0xFF122E3D.toInt()
    const val onSurfaceVariantDark = 0xFFC7DAD4.toInt()
    const val primaryDark = 0xFF00C896.toInt()
    const val onPrimaryDark = 0xFF0A1F2E.toInt()
    const val primaryContainerDark = 0xFF0B3D2E.toInt()
    const val onPrimaryContainerDark = 0xFFE8F6F3.toInt()
    const val secondaryDark = 0xFF38BDF8.toInt()
    const val onSecondaryDark = 0xFF0A1F2E.toInt()
    const val secondaryContainerDark = 0xFF12384D.toInt()
    const val onSecondaryContainerDark = 0xFFE8F6F3.toInt()
    const val tertiaryDark = 0xFFA3E635.toInt()
    const val onTertiaryDark = 0xFF000000.toInt()
    const val tertiaryContainerDark = 0xFF2E3D12.toInt()
    const val onTertiaryContainerDark = 0xFFE8F6F3.toInt()
    const val outlineDark = 0xFF3D5A6B.toInt()
    const val outlineVariantDark = 0xFF22394A.toInt()
    // Light variant.
    const val backgroundLight = 0xFFE8F6F3.toInt()
    const val onSurfaceLight = 0xFF0A1F2E.toInt()
    const val surfaceVariantLight = 0xFFDFF2EC.toInt()
    const val onSurfaceVariantLight = 0xFF33505F.toInt()
    const val primaryLight = 0xFF00C896.toInt()
    const val onPrimaryLight = 0xFF0A1F2E.toInt()
    const val primaryContainerLight = 0xFFC8F0DF.toInt()
    const val onPrimaryContainerLight = 0xFF063B2A.toInt()
    const val secondaryLight = 0xFF38BDF8.toInt()
    const val onSecondaryLight = 0xFF0A1F2E.toInt()
    const val secondaryContainerLight = 0xFFD3ECF9.toInt()
    const val onSecondaryContainerLight = 0xFF0B2E44.toInt()
    const val tertiaryLight = 0xFFA3E635.toInt()
    const val onTertiaryLight = 0xFF000000.toInt()
    const val tertiaryContainerLight = 0xFFE4F3C8.toInt()
    const val onTertiaryContainerLight = 0xFF2A3D08.toInt()
    const val outlineLight = 0xFF9DB8AC.toInt()
    const val outlineVariantLight = 0xFFC4D8D0.toInt()
}
