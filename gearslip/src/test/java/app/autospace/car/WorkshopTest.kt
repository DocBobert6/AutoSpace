package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Workshop (r142, revised r145): the Skeuomorphic scheme —
 * charcoal linen and cool off-white linen with brass, copper,
 * and olive accents used accent-only — plus its Foundry
 * style's gloss-lightness rule (the style values themselves
 * are pinned in GsThemeTest). Scheme tokens live in
 * [WorkshopPalette] as plain ARGB Ints, so the contrast
 * rules are JVM-testable; [CarUi.workshopScheme] maps them
 * mechanically. The pair is pinned in ThemePairsTest.
 * r145 replaces the r142 espresso/parchment backgrounds and
 * accent-tinted containers with neutral gray ladders; the
 * three accents themselves are unchanged.
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
        // The r145 revised values, exact, both variants.
        // Dark: charcoal linen, not espresso.
        assertEquals(0xFF252423.toInt(), WorkshopPalette.backgroundDark)
        assertEquals(0xFFF2F3F2.toInt(), WorkshopPalette.onSurfaceDark)
        // Light: cool off-white linen, not parchment, with a
        // subtle vertical gradient foot for the app background.
        assertEquals(0xFFF1F2F2.toInt(), WorkshopPalette.backgroundLight)
        assertEquals(0xFFE3E6E5.toInt(), WorkshopPalette.backgroundLightGradientBottom)
        assertEquals(0xFF232427.toInt(), WorkshopPalette.onSurfaceLight)
        // The three accents are unchanged from r142.
        assertEquals(0xFFC9A227.toInt(), WorkshopPalette.primaryDark)
        assertEquals(0xFFC9A227.toInt(), WorkshopPalette.primaryLight)
        assertEquals(0xFFB87333.toInt(), WorkshopPalette.secondaryDark)
        assertEquals(0xFFB87333.toInt(), WorkshopPalette.secondaryLight)
        assertEquals(0xFF6B7C3F.toInt(), WorkshopPalette.tertiaryDark)
        assertEquals(0xFF6B7C3F.toInt(), WorkshopPalette.tertiaryLight)
        // Cards, exact: warm dark gray / cool light gray.
        assertEquals(0xFF3B3733.toInt(), WorkshopPalette.surfaceVariantDark)
        assertEquals(0xFFDFE2E1.toInt(), WorkshopPalette.surfaceVariantLight)
        // Accent-only rule: no container token is an accent
        // tint — every container is a neutral gray step.
        assertEquals(0xFF47423B.toInt(), WorkshopPalette.primaryContainerDark)
        assertEquals(0xFF3B3733.toInt(), WorkshopPalette.secondaryContainerDark)
        assertEquals(0xFF2E2C29.toInt(), WorkshopPalette.tertiaryContainerDark)
        assertEquals(0xFFD8DBDA.toInt(), WorkshopPalette.primaryContainerLight)
        assertEquals(0xFFDFE2E1.toInt(), WorkshopPalette.secondaryContainerLight)
        assertEquals(0xFFE8EBEA.toInt(), WorkshopPalette.tertiaryContainerLight)
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
    fun workshopContrastPassesExceptTheFlaggedBrassOnOffWhite() {
        // Body text on both backgrounds, comfortably.
        assertTrue(contrastRatio(WorkshopPalette.onSurfaceDark, WorkshopPalette.backgroundDark) >= 4.5)
        assertEquals(13.93, contrastRatio(WorkshopPalette.onSurfaceDark, WorkshopPalette.backgroundDark), 0.02)
        assertTrue(contrastRatio(WorkshopPalette.onSurfaceLight, WorkshopPalette.backgroundLight) >= 4.5)
        assertEquals(13.84, contrastRatio(WorkshopPalette.onSurfaceLight, WorkshopPalette.backgroundLight), 0.02)
        // Body text on the gradient foot, too.
        assertTrue(contrastRatio(WorkshopPalette.onSurfaceLight, WorkshopPalette.backgroundLightGradientBottom) >= 4.5)
        assertEquals(12.35, contrastRatio(WorkshopPalette.onSurfaceLight, WorkshopPalette.backgroundLightGradientBottom), 0.02)
        // Primary pair: espresso text on brass, both variants.
        assertTrue(contrastRatio(WorkshopPalette.onPrimaryDark, WorkshopPalette.primaryDark) >= 4.5)
        assertEquals(6.73, contrastRatio(WorkshopPalette.onPrimaryDark, WorkshopPalette.primaryDark), 0.02)
        assertTrue(contrastRatio(WorkshopPalette.onPrimaryLight, WorkshopPalette.primaryLight) >= 4.5)
        // The flagged watch-out, re-measured for r145 and
        // pinned as a failure on purpose: brass as small text
        // on the new off-white is only 2.16:1 (on the new card
        // 1.86:1), so brass stays on fills and accents in light
        // mode, never small text on the light background or
        // card.
        assertTrue(contrastRatio(WorkshopPalette.primaryLight, WorkshopPalette.backgroundLight) < 4.5)
        assertEquals(2.16, contrastRatio(WorkshopPalette.primaryLight, WorkshopPalette.backgroundLight), 0.02)
        assertTrue(contrastRatio(WorkshopPalette.primaryLight, WorkshopPalette.surfaceVariantLight) < 4.5)
        assertEquals(1.86, contrastRatio(WorkshopPalette.primaryLight, WorkshopPalette.surfaceVariantLight), 0.02)
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
        assertEquals(10.60, contrastRatio(WorkshopPalette.onSurfaceDark, WorkshopPalette.surfaceVariantDark), 0.02)
        assertTrue(contrastRatio(WorkshopPalette.onSurfaceLight, WorkshopPalette.surfaceVariantLight) >= 4.5)
        assertEquals(11.90, contrastRatio(WorkshopPalette.onSurfaceLight, WorkshopPalette.surfaceVariantLight), 0.02)
    }
}
