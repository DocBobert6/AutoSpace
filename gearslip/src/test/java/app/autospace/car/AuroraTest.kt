package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Aurora Field (r143): the Frutiger Aero scheme — deep ocean
 * and pale aqua with a spring-green primary, the only
 * green-primary scheme in the tree — plus its Aurora style's
 * values (the style values themselves are pinned in
 * GsThemeTest). Scheme tokens live in [AuroraFieldPalette]
 * as plain ARGB Ints, so the contrast rules are
 * JVM-testable; [CarUi.auroraFieldScheme] maps them
 * mechanically. The pair is pinned in ThemePairsTest.
 */
class AuroraTest {

    @Test
    fun auroraFieldSchemeIsAppendedLastAndNamedAuroraField() {
        assertEquals(ColorScheme.AURORA_FIELD, ColorScheme.valueOf("AURORA_FIELD"))
        assertEquals("Aurora Field", ColorScheme.AURORA_FIELD.displayName)
        assertEquals(ColorScheme.AURORA_FIELD, colorSchemes.last())
        assertTrue(colorSchemes.contains(ColorScheme.AURORA_FIELD))
        // Workshop is second from last, and nothing else moved.
        assertEquals(ColorScheme.WORKSHOP, colorSchemes[colorSchemes.size - 2])
        // No legacy install migrates onto Aurora Field by accident.
        assertFalse(isLegacyThemeName("AURORA_FIELD"))
    }

    @Test
    fun auroraFieldPaletteTokensMatchTheApprovedSpec() {
        // The settled spec values, exact, both variants.
        assertEquals(0xFF0A1F2E.toInt(), AuroraFieldPalette.backgroundDark)
        assertEquals(0xFFE8F6F3.toInt(), AuroraFieldPalette.backgroundLight)
        assertEquals(0xFF00C896.toInt(), AuroraFieldPalette.primaryDark)
        assertEquals(0xFF00C896.toInt(), AuroraFieldPalette.primaryLight)
        assertEquals(0xFF38BDF8.toInt(), AuroraFieldPalette.secondaryDark)
        assertEquals(0xFF38BDF8.toInt(), AuroraFieldPalette.secondaryLight)
        assertEquals(0xFFA3E635.toInt(), AuroraFieldPalette.tertiaryDark)
        assertEquals(0xFFA3E635.toInt(), AuroraFieldPalette.tertiaryLight)
        assertEquals(0xFF122E3D.toInt(), AuroraFieldPalette.surfaceVariantDark)
        assertEquals(0xFFDFF2EC.toInt(), AuroraFieldPalette.surfaceVariantLight)
        // Paired on* colors, exact.
        assertEquals(0xFFE8F6F3.toInt(), AuroraFieldPalette.onSurfaceDark)
        assertEquals(0xFF0A1F2E.toInt(), AuroraFieldPalette.onSurfaceLight)
        assertEquals(0xFF0A1F2E.toInt(), AuroraFieldPalette.onPrimaryDark)
        assertEquals(0xFF0A1F2E.toInt(), AuroraFieldPalette.onPrimaryLight)
        assertEquals(0xFF0A1F2E.toInt(), AuroraFieldPalette.onSecondaryDark)
        assertEquals(0xFF0A1F2E.toInt(), AuroraFieldPalette.onSecondaryLight)
        assertEquals(0xFF000000.toInt(), AuroraFieldPalette.onTertiaryDark)
        assertEquals(0xFF000000.toInt(), AuroraFieldPalette.onTertiaryLight)
        assertEquals(0xFFC7DAD4.toInt(), AuroraFieldPalette.onSurfaceVariantDark)
        assertEquals(0xFF33505F.toInt(), AuroraFieldPalette.onSurfaceVariantLight)
    }

    @Test
    fun auroraFieldSchemeDoesNotForceDark() {
        // NOT forced-dark: schemeForcesDark stays NIGHT ||
        // PITLANE || MERIDIAN only, so Aurora Field follows
        // the mode control like Verve and Horizon.
        assertFalse(schemeForcesDark(ColorScheme.AURORA_FIELD))
        for (phoneDark in listOf(false, true)) {
            assertFalse(resolveDark(ColorScheme.AURORA_FIELD, AppearanceMode.LIGHT, phoneDark))
            assertTrue(resolveDark(ColorScheme.AURORA_FIELD, AppearanceMode.DARK, phoneDark))
        }
        assertFalse(resolveDark(ColorScheme.AURORA_FIELD, AppearanceMode.SYSTEM, false))
        assertTrue(resolveDark(ColorScheme.AURORA_FIELD, AppearanceMode.SYSTEM, true))
    }

    @Test
    fun auroraFieldContrastPassesExceptFlaggedLimeOnAqua() {
        // Every paired on* color passes 4.5:1, both variants.
        assertTrue(contrastRatio(AuroraFieldPalette.onSurfaceDark, AuroraFieldPalette.backgroundDark) >= 4.5)
        assertEquals(15.15, contrastRatio(AuroraFieldPalette.onSurfaceDark, AuroraFieldPalette.backgroundDark), 0.02)
        assertTrue(contrastRatio(AuroraFieldPalette.onSurfaceLight, AuroraFieldPalette.backgroundLight) >= 4.5)
        assertEquals(15.15, contrastRatio(AuroraFieldPalette.onSurfaceLight, AuroraFieldPalette.backgroundLight), 0.02)
        assertTrue(contrastRatio(AuroraFieldPalette.onPrimaryDark, AuroraFieldPalette.primaryDark) >= 4.5)
        assertEquals(7.77, contrastRatio(AuroraFieldPalette.onPrimaryDark, AuroraFieldPalette.primaryDark), 0.02)
        assertTrue(contrastRatio(AuroraFieldPalette.onPrimaryLight, AuroraFieldPalette.primaryLight) >= 4.5)
        assertEquals(7.77, contrastRatio(AuroraFieldPalette.onPrimaryLight, AuroraFieldPalette.primaryLight), 0.02)
        assertTrue(contrastRatio(AuroraFieldPalette.onSecondaryDark, AuroraFieldPalette.secondaryDark) >= 4.5)
        assertEquals(7.85, contrastRatio(AuroraFieldPalette.onSecondaryDark, AuroraFieldPalette.secondaryDark), 0.02)
        assertTrue(contrastRatio(AuroraFieldPalette.onSecondaryLight, AuroraFieldPalette.secondaryLight) >= 4.5)
        assertEquals(7.85, contrastRatio(AuroraFieldPalette.onSecondaryLight, AuroraFieldPalette.secondaryLight), 0.02)
        assertTrue(contrastRatio(AuroraFieldPalette.onTertiaryDark, AuroraFieldPalette.tertiaryDark) >= 4.5)
        assertEquals(13.93, contrastRatio(AuroraFieldPalette.onTertiaryDark, AuroraFieldPalette.tertiaryDark), 0.02)
        assertTrue(contrastRatio(AuroraFieldPalette.onTertiaryLight, AuroraFieldPalette.tertiaryLight) >= 4.5)
        assertEquals(13.93, contrastRatio(AuroraFieldPalette.onTertiaryLight, AuroraFieldPalette.tertiaryLight), 0.02)
        assertTrue(contrastRatio(AuroraFieldPalette.onSurfaceVariantDark, AuroraFieldPalette.surfaceVariantDark) >= 4.5)
        assertEquals(9.71, contrastRatio(AuroraFieldPalette.onSurfaceVariantDark, AuroraFieldPalette.surfaceVariantDark), 0.02)
        assertTrue(contrastRatio(AuroraFieldPalette.onSurfaceVariantLight, AuroraFieldPalette.surfaceVariantLight) >= 4.5)
        assertEquals(7.36, contrastRatio(AuroraFieldPalette.onSurfaceVariantLight, AuroraFieldPalette.surfaceVariantLight), 0.02)
        assertTrue(contrastRatio(AuroraFieldPalette.onPrimaryContainerDark, AuroraFieldPalette.primaryContainerDark) >= 4.5)
        assertTrue(contrastRatio(AuroraFieldPalette.onSecondaryContainerDark, AuroraFieldPalette.secondaryContainerDark) >= 4.5)
        assertTrue(contrastRatio(AuroraFieldPalette.onTertiaryContainerDark, AuroraFieldPalette.tertiaryContainerDark) >= 4.5)
        assertTrue(contrastRatio(AuroraFieldPalette.onPrimaryContainerLight, AuroraFieldPalette.primaryContainerLight) >= 4.5)
        assertTrue(contrastRatio(AuroraFieldPalette.onSecondaryContainerLight, AuroraFieldPalette.secondaryContainerLight) >= 4.5)
        assertTrue(contrastRatio(AuroraFieldPalette.onTertiaryContainerLight, AuroraFieldPalette.tertiaryContainerLight) >= 4.5)
        // The flagged pair, pinned as a failure on purpose:
        // lime on pale aqua is only 1.36:1, so tertiary stays
        // on small accents only in light mode, never small
        // text on the pale aqua background.
        assertTrue(contrastRatio(AuroraFieldPalette.tertiaryLight, AuroraFieldPalette.backgroundLight) < 4.5)
        assertEquals(1.36, contrastRatio(AuroraFieldPalette.tertiaryLight, AuroraFieldPalette.backgroundLight), 0.02)
    }
}
