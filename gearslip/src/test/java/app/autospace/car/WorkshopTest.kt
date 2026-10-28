package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Workshop (r142): the Skeuomorphic scheme — espresso and
 * parchment with brass, copper, and olive accents — plus its
 * Foundry style's gloss-lightness rule (the style values
 * themselves are pinned in GsThemeTest). Scheme tokens live
 * in [WorkshopPalette] as plain ARGB Ints, so the contrast
 * rules are JVM-testable; [CarUi.workshopScheme] maps them
 * mechanically. The pair is pinned in ThemePairsTest.
 */
class WorkshopTest {

    @Test
    fun workshopSchemeIsAppendedBeforeAuroraAndNamedWorkshop() {
        assertEquals(ColorScheme.WORKSHOP, ColorScheme.valueOf("WORKSHOP"))
        assertEquals("Workshop", ColorScheme.WORKSHOP.displayName)
        assertEquals(ColorScheme.WORKSHOP, colorSchemes[colorSchemes.size - 2])
        assertTrue(colorSchemes.contains(ColorScheme.WORKSHOP))
        // r143 appends Aurora Field last (pinned by AuroraTest);
        // Meridian is third from last, and nothing else moved.
        assertEquals(ColorScheme.MERIDIAN, colorSchemes[colorSchemes.size - 3])
        // No legacy install migrates onto Workshop by accident.
        assertFalse(isLegacyThemeName("WORKSHOP"))
    }

    @Test
    fun workshopPaletteTokensMatchTheApprovedSpec() {
        // The settled spec values, exact, both variants.
        assertEquals(0xFF2B1D16.toInt(), WorkshopPalette.backgroundDark)
        assertEquals(0xFFF5EEDD.toInt(), WorkshopPalette.backgroundLight)
        assertEquals(0xFFC9A227.toInt(), WorkshopPalette.primaryDark)
        assertEquals(0xFFC9A227.toInt(), WorkshopPalette.primaryLight)
        assertEquals(0xFFB87333.toInt(), WorkshopPalette.secondaryDark)
        assertEquals(0xFFB87333.toInt(), WorkshopPalette.secondaryLight)
        assertEquals(0xFF6B7C3F.toInt(), WorkshopPalette.tertiaryDark)
        assertEquals(0xFF6B7C3F.toInt(), WorkshopPalette.tertiaryLight)
        // Cards, exact.
        assertEquals(0xFF3A2A1E.toInt(), WorkshopPalette.surfaceVariantDark)
        assertEquals(0xFFEFE6CF.toInt(), WorkshopPalette.surfaceVariantLight)
    }

    @Test
    fun workshopDoesNotForceDark() {
        // Workshop is NOT forced-dark (unlike Night/Pitlane/
        // Meridian): the mode control decides, and Auto night
        // still overrides it to NIGHT like every scheme.
        assertFalse(schemeForcesDark(ColorScheme.WORKSHOP))
        assertFalse(resolveDark(ColorScheme.WORKSHOP, AppearanceMode.LIGHT, true))
        assertTrue(resolveDark(ColorScheme.WORKSHOP, AppearanceMode.DARK, false))
        assertFalse(resolveDark(ColorScheme.WORKSHOP, AppearanceMode.SYSTEM, false))
        assertTrue(resolveDark(ColorScheme.WORKSHOP, AppearanceMode.SYSTEM, true))
        assertEquals(ColorScheme.NIGHT, effectiveScheme(ColorScheme.WORKSHOP, true))
        assertEquals(ColorScheme.WORKSHOP, effectiveScheme(ColorScheme.WORKSHOP, false))
    }

    @Test
    fun workshopContrastPassesExceptTheFlaggedBrassOnParchment() {
        // Body text on both backgrounds, comfortably.
        assertTrue(contrastRatio(WorkshopPalette.onSurfaceDark, WorkshopPalette.backgroundDark) >= 4.5)
        assertEquals(14.07, contrastRatio(WorkshopPalette.onSurfaceDark, WorkshopPalette.backgroundDark), 0.02)
        assertTrue(contrastRatio(WorkshopPalette.onSurfaceLight, WorkshopPalette.backgroundLight) >= 4.5)
        // Primary pair: espresso text on brass, both variants.
        assertTrue(contrastRatio(WorkshopPalette.onPrimaryDark, WorkshopPalette.primaryDark) >= 4.5)
        assertEquals(6.73, contrastRatio(WorkshopPalette.onPrimaryDark, WorkshopPalette.primaryDark), 0.02)
        assertTrue(contrastRatio(WorkshopPalette.onPrimaryLight, WorkshopPalette.primaryLight) >= 4.5)
        // The flagged watch-out, pinned as a failure on purpose:
        // brass as small text on parchment is only 2.09:1, so
        // brass stays on fills and accents in light mode, never
        // small text on parchment.
        assertTrue(contrastRatio(WorkshopPalette.primaryLight, WorkshopPalette.backgroundLight) < 4.5)
        assertEquals(2.09, contrastRatio(WorkshopPalette.primaryLight, WorkshopPalette.backgroundLight), 0.02)
        // Secondary pair: black on copper (espresso on copper is
        // only 4.29:1, so black, not espresso).
        assertTrue(contrastRatio(WorkshopPalette.onSecondaryDark, WorkshopPalette.secondaryDark) >= 4.5)
        assertEquals(5.54, contrastRatio(WorkshopPalette.onSecondaryDark, WorkshopPalette.secondaryDark), 0.02)
        assertTrue(contrastRatio(WorkshopPalette.onSecondaryLight, WorkshopPalette.secondaryLight) >= 4.5)
        // Tertiary pair: white on olive, both variants.
        assertTrue(contrastRatio(WorkshopPalette.onTertiaryDark, WorkshopPalette.tertiaryDark) >= 4.5)
        assertEquals(4.59, contrastRatio(WorkshopPalette.onTertiaryDark, WorkshopPalette.tertiaryDark), 0.02)
        assertTrue(contrastRatio(WorkshopPalette.onTertiaryLight, WorkshopPalette.tertiaryLight) >= 4.5)
        // Container pairs, both variants, all pass.
        assertTrue(contrastRatio(WorkshopPalette.onPrimaryContainerDark, WorkshopPalette.primaryContainerDark) >= 4.5)
        assertTrue(contrastRatio(WorkshopPalette.onSecondaryContainerDark, WorkshopPalette.secondaryContainerDark) >= 4.5)
        assertTrue(contrastRatio(WorkshopPalette.onTertiaryContainerDark, WorkshopPalette.tertiaryContainerDark) >= 4.5)
        assertTrue(contrastRatio(WorkshopPalette.onPrimaryContainerLight, WorkshopPalette.primaryContainerLight) >= 4.5)
        assertTrue(contrastRatio(WorkshopPalette.onSecondaryContainerLight, WorkshopPalette.secondaryContainerLight) >= 4.5)
        assertTrue(contrastRatio(WorkshopPalette.onTertiaryContainerLight, WorkshopPalette.tertiaryContainerLight) >= 4.5)
        // Body text on the card fills, both variants.
        assertTrue(contrastRatio(WorkshopPalette.onSurfaceDark, WorkshopPalette.surfaceVariantDark) >= 4.5)
        assertTrue(contrastRatio(WorkshopPalette.onSurfaceLight, WorkshopPalette.surfaceVariantLight) >= 4.5)
    }
}
