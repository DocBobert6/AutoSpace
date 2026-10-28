package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Meridian (r107): the full theme — dark-locked scheme, flat 16dp
 * style (pinned in GsThemeTest), and pair (pinned in ThemePairsTest).
 * Scheme tokens live in [MeridianPalette] as plain ARGB Ints, so the
 * contrast rules are JVM-testable; [CarUi.meridianScheme] maps them
 * mechanically.
 */
class MeridianTest {

    @Test
    fun meridianSchemeIsAppendedLastAndNamedMeridian() {
        assertEquals(ColorScheme.MERIDIAN, ColorScheme.valueOf("MERIDIAN"))
        assertEquals("Meridian", ColorScheme.MERIDIAN.displayName)
        assertEquals(ColorScheme.MERIDIAN, colorSchemes.last())
        assertTrue(colorSchemes.contains(ColorScheme.MERIDIAN))
        // Horizon is no longer last, but nothing else moved.
        assertEquals(ColorScheme.HORIZON, colorSchemes[colorSchemes.size - 2])
        // No legacy install migrates onto Meridian by accident.
        assertFalse(isLegacyThemeName("MERIDIAN"))
    }

    @Test
    fun meridianPaletteTokensMatchTheApprovedSpec() {
        // The four photo-picked colors, exact.
        assertEquals(0xFF131429.toInt(), MeridianPalette.background)
        assertEquals(0xFF3E6F74.toInt(), MeridianPalette.primary)
        assertEquals(0xFF335C5E.toInt(), MeridianPalette.primaryContainer)
        assertEquals(0xFF789A75.toInt(), MeridianPalette.secondary)
        // Navy surface steps, exact.
        assertEquals(0xFF1B1C33.toInt(), MeridianPalette.surfaceContainerLow)
        assertEquals(0xFF232444.toInt(), MeridianPalette.surfaceContainer)
        assertEquals(0xFF2A2C50.toInt(), MeridianPalette.surfaceContainerHigh)
        assertEquals(0xFF33355E.toInt(), MeridianPalette.surfaceContainerHighest)
        // Dim secondary text is white at MERIDIAN_DIM_ALPHA 0.75.
        assertEquals(0xBFFFFFFF.toInt(), MeridianPalette.onSurfaceVariant)
        // Secondary takes navy text (large accents only, never body
        // text on a light surface); tertiary is plain white on navy.
        assertEquals(0xFF131429.toInt(), MeridianPalette.onSecondary)
        assertEquals(0xFFFFFFFF.toInt(), MeridianPalette.tertiary)
    }

    @Test
    fun meridianSchemeForcesDark() {
        // Forced dark like Night and Pitlane: every mode and phone
        // state renders dark, and the settings explainer agrees.
        assertTrue(schemeForcesDark(ColorScheme.MERIDIAN))
        for (phoneDark in listOf(false, true)) {
            for (mode in AppearanceMode.entries) {
                assertTrue(resolveDark(ColorScheme.MERIDIAN, mode, phoneDark))
            }
        }
        // Auto night still overrides to NIGHT like every scheme.
        assertEquals(ColorScheme.NIGHT, effectiveScheme(ColorScheme.MERIDIAN, true))
        assertEquals(ColorScheme.MERIDIAN, effectiveScheme(ColorScheme.MERIDIAN, false))
    }

    @Test
    fun meridianContrastPasses() {
        // Body text on the navy surfaces, comfortably.
        assertTrue(contrastRatio(MeridianPalette.onSurface, MeridianPalette.background) >= 4.5)
        assertEquals(18.11, contrastRatio(MeridianPalette.onSurface, MeridianPalette.background), 0.02)
        // Primary pair and primary-container pair.
        assertTrue(contrastRatio(MeridianPalette.onPrimary, MeridianPalette.primary) >= 4.5)
        assertEquals(5.63, contrastRatio(MeridianPalette.onPrimary, MeridianPalette.primary), 0.02)
        assertTrue(contrastRatio(MeridianPalette.onPrimaryContainer, MeridianPalette.primaryContainer) >= 4.5)
        assertEquals(7.41, contrastRatio(MeridianPalette.onPrimaryContainer, MeridianPalette.primaryContainer), 0.02)
        // Secondary pair (navy on sage): large accents only, but the
        // pair itself still passes AA.
        assertTrue(contrastRatio(MeridianPalette.onSecondary, MeridianPalette.secondary) >= 4.5)
        assertEquals(5.76, contrastRatio(MeridianPalette.onSecondary, MeridianPalette.secondary), 0.02)
        // Highest surface step still passes for body text.
        assertTrue(contrastRatio(MeridianPalette.onSurface, MeridianPalette.surfaceContainerHighest) >= 4.5)
        assertEquals(11.59, contrastRatio(MeridianPalette.onSurface, MeridianPalette.surfaceContainerHighest), 0.02)
        // Dim text: white at 0.75 alpha blends to #C4C4CA over navy.
        assertEquals(10.43, contrastRatio(0xFFC4C4CA.toInt(), MeridianPalette.background), 0.02)
        assertTrue(contrastRatio(0xFFC4C4CA.toInt(), MeridianPalette.background) >= 4.5)
    }
}
