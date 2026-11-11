package app.autospace.car

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.autospace.car.theme.GaugeTickHeightFraction
import app.autospace.car.theme.GsThemes
import app.autospace.car.theme.HoldMarkerStyle
import app.autospace.car.theme.TrimDividerThickness
import app.autospace.car.theme.clusterLegendColor
import app.autospace.car.theme.clusterLegendReads
import app.autospace.car.theme.gaugeTickFractions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** r222 (Dashboard): the Dashboard scheme + Cluster style, pinned. */
class DashboardTest {

    @Test
    fun dashboardSchemeIsAppendedLastAndNamedDashboard() {
        assertEquals(ColorScheme.DASHBOARD, ColorScheme.valueOf("DASHBOARD"))
        assertEquals("Dashboard", ColorScheme.DASHBOARD.displayName)
        assertEquals(ColorScheme.DASHBOARD, colorSchemes.last())
        assertTrue(colorSchemes.contains(ColorScheme.DASHBOARD))
        // Aurora Field is second from last, and nothing else moved.
        assertEquals(ColorScheme.AURORA_FIELD, colorSchemes[colorSchemes.size - 2])
        // No legacy install migrates onto Dashboard by accident.
        assertFalse(isLegacyThemeName("DASHBOARD"))
        // Dashboard follows the mode control (it is not forced dark):
        // Light forces light, Dark forces dark, System defers to the phone.
        assertFalse(resolveDark(ColorScheme.DASHBOARD, AppearanceMode.LIGHT, true))
        assertTrue(resolveDark(ColorScheme.DASHBOARD, AppearanceMode.DARK, false))
        assertFalse(resolveDark(ColorScheme.DASHBOARD, AppearanceMode.SYSTEM, false))
        assertTrue(resolveDark(ColorScheme.DASHBOARD, AppearanceMode.SYSTEM, true))
        assertFalse(schemeForcesDark(ColorScheme.DASHBOARD))
    }

    @Test
    fun dashboardPaletteTokensMatchTheApprovedSpec() {
        // The settled spec values, exact, both variants — sampled
        // from Robert's Altima dash photos, not invented. r223: the
        // accents flipped (steel blue primary, amber secondary) and
        // light mode is two grey levels (field + near-black buttons).
        assertEquals(0xFF0E0E14.toInt(), DashboardPalette.backgroundDark)
        assertEquals(0xFF3E85A7.toInt(), DashboardPalette.primaryDark)
        assertEquals(0xFF0E0E14.toInt(), DashboardPalette.onPrimaryDark)
        assertEquals(0xFFCD8E48.toInt(), DashboardPalette.secondaryDark)
        assertEquals(0xFFA3541F.toInt(), DashboardPalette.tertiaryDark)
        assertEquals(0xFFFFFFFF.toInt(), DashboardPalette.onTertiaryDark)
        assertEquals(0xFFF5F2EC.toInt(), DashboardPalette.onSurfaceDark)
        assertEquals(0xFF2F3237.toInt(), DashboardPalette.backgroundLight)
        assertEquals(0xFFF5F2EC.toInt(), DashboardPalette.onSurfaceLight)
        assertEquals(0xFF1A1C1F.toInt(), DashboardPalette.buttonLight)
        assertEquals(0xFF7FB3C8.toInt(), DashboardPalette.primaryLight)
        assertEquals(0xFFA3541F.toInt(), DashboardPalette.tertiaryLight)
        // The dark background is near-black, not pure black — so
        // Dashboard stays out of oledBlackSchemes by design.
        assertFalse(DashboardPalette.backgroundDark == 0xFF000000.toInt())
    }

    @Test
    fun dashboardPaletteContrastClearsTheBars() {
        // The numbers the legend gate relies on (WCAG, opaque RGB).
        assertTrue(contrastRatio(DashboardPalette.onPrimaryDark, DashboardPalette.primaryDark) >= 4.5)
        assertTrue(contrastRatio(DashboardPalette.onSecondaryDark, DashboardPalette.secondaryDark) >= 4.5)
        assertTrue(contrastRatio(DashboardPalette.onSurfaceLight, DashboardPalette.backgroundLight) >= 4.5)
        assertTrue(contrastRatio(DashboardPalette.onTertiaryDark, DashboardPalette.tertiaryDark) >= 4.5)
        assertTrue(contrastRatio(DashboardPalette.onError, DashboardPalette.error) >= 4.5)
        // r223: on the new dark-grey light field the steel-blue primary
        // (5.63:1) and the light-amber secondary (4.63:1) both read, so
        // the legend stays its own colour in light mode too; only the
        // burnt-amber tertiary (2.36:1) still falls back (pinned below).
        assertTrue(contrastRatio(DashboardPalette.primaryLight, DashboardPalette.backgroundLight) >= 4.5)
        assertEquals(5.63, contrastRatio(DashboardPalette.primaryLight, DashboardPalette.backgroundLight), 0.02)
        assertTrue(contrastRatio(DashboardPalette.secondaryLight, DashboardPalette.backgroundLight) >= 4.5)
        assertEquals(4.63, contrastRatio(DashboardPalette.secondaryLight, DashboardPalette.backgroundLight), 0.02)
        assertTrue(contrastRatio(DashboardPalette.tertiaryLight, DashboardPalette.backgroundLight) < 4.5)
        assertEquals(2.36, contrastRatio(DashboardPalette.tertiaryLight, DashboardPalette.backgroundLight), 0.02)
        assertEquals(11.51, contrastRatio(DashboardPalette.onSurfaceLight, DashboardPalette.backgroundLight), 0.02)
        // The two light levels are a subtle step (1.33:1) — the levels
        // are told apart by consistency and the trim dividers, not by
        // a big jump (see the device check).
        assertEquals(1.33, contrastRatio(DashboardPalette.buttonLight, DashboardPalette.backgroundLight), 0.02)
    }

    @Test
    fun dashboardPaletteHasNoRedAnywhere() {
        // Robert's hard rule: no red anywhere in Dashboard, either
        // mode. A token counts as red when its red channel dominates
        // (red > 150 while green and blue stay under 100) AND it is
        // far more red than green (red - green > 100). The second bar
        // is what keeps the approved burnt amber (#A3541F, red -
        // green = 79) from reading as red, while a true red such as
        // the old error #BA1A1A (red - green = 160) still fails.
        // The umber error (#6B3410, red 107) is not red by either bar.
        val tokens = listOf(
            DashboardPalette.backgroundDark, DashboardPalette.onSurfaceDark,
            DashboardPalette.surfaceVariantDark, DashboardPalette.onSurfaceVariantDark,
            DashboardPalette.primaryDark, DashboardPalette.onPrimaryDark,
            DashboardPalette.primaryContainerDark, DashboardPalette.onPrimaryContainerDark,
            DashboardPalette.secondaryDark, DashboardPalette.onSecondaryDark,
            DashboardPalette.secondaryContainerDark, DashboardPalette.onSecondaryContainerDark,
            DashboardPalette.tertiaryDark, DashboardPalette.onTertiaryDark,
            DashboardPalette.tertiaryContainerDark, DashboardPalette.onTertiaryContainerDark,
            DashboardPalette.outlineDark, DashboardPalette.outlineVariantDark,
            DashboardPalette.backgroundLight, DashboardPalette.onSurfaceLight,
            DashboardPalette.buttonLight, DashboardPalette.onSurfaceVariantLight,
            DashboardPalette.primaryLight, DashboardPalette.onPrimaryLight,
            DashboardPalette.primaryContainerLight, DashboardPalette.onPrimaryContainerLight,
            DashboardPalette.secondaryLight, DashboardPalette.onSecondaryLight,
            DashboardPalette.secondaryContainerLight, DashboardPalette.onSecondaryContainerLight,
            DashboardPalette.tertiaryLight, DashboardPalette.onTertiaryLight,
            DashboardPalette.tertiaryContainerLight, DashboardPalette.onTertiaryContainerLight,
            DashboardPalette.outlineLight, DashboardPalette.outlineVariantLight,
            DashboardPalette.error, DashboardPalette.onError,
        )
        for (token in tokens) {
            val r = (token shr 16) and 0xFF
            val g = (token shr 8) and 0xFF
            val b = token and 0xFF
            assertFalse("token #" + Integer.toHexString(token) + " reads as red", r > 150 && g < 100 && b < 100 && (r - g) > 100)
        }
        // And the error role really is the umber, not standard red.
        assertEquals(0xFF6B3410.toInt(), DashboardPalette.error)
    }

    @Test
    fun clusterStyleIsAppendedLastAndNamedCluster() {
        assertEquals("cluster", GsThemes.Cluster.id)
        assertEquals("Cluster", GsThemes.Cluster.name)
        assertEquals(GsThemes.Cluster, GsThemes.builtIn.last())
        assertEquals("cluster", GsThemes.byId("cluster").id)
        // Aurora is second from last, and nothing else moved.
        assertEquals(GsThemes.Aurora, GsThemes.builtIn[GsThemes.builtIn.size - 2])
    }

    @Test
    fun clusterLookHasNoFaceAtRest() {
        // No visible container, no border, no bevel, no shadow, no
        // rim, no lip, no gloss, no sheen, no wash, no halo — every
        // decoration field keeps its zero/false default.
        val look = GsThemes.Cluster.control
        assertEquals(0f, look.containerAlpha, 0.001f)
        assertEquals(0f, look.topLight, 0.001f)
        assertEquals(0f, look.bottomShade, 0.001f)
        assertEquals(0f, look.rimAlpha, 0.001f)
        assertEquals(0f, look.hairlineAlpha, 0.001f)
        assertEquals(0, look.lip.value.toInt())
        assertEquals(0, look.shadow.value.toInt())
        assertEquals(0f, look.shadowAlpha, 0.001f)
        assertEquals(0f, look.bevelTop, 0.001f)
        assertEquals(0f, look.bevelBottom, 0.001f)
        assertEquals(0f, look.glossAlpha, 0.001f)
        assertEquals(0f, look.sheenAlpha, 0.001f)
        assertEquals(0f, look.washAlpha, 0.001f)
        assertEquals(0f, look.haloAlpha, 0.001f)
        assertEquals(0f, look.pressedDim, 0.001f)
        assertEquals(0.95f, look.pressedScale, 0.001f)
        assertEquals(12, look.corner.value.toInt())
        // The face is flat with no container, so the hold marker is
        // DOT, like Ink.
        assertEquals(HoldMarkerStyle.DOT, look.holdMarker)
        // The light variant is identical on purpose (see GsTheme.kt):
        // the dark/light difference lives in the legend rule.
        val light = GsThemes.Cluster.control.light
        assertTrue(light != null)
        assertEquals(look.containerAlpha, light!!.containerAlpha, 0.001f)
        assertEquals(look.pressedScale, light.pressedScale, 0.001f)
        assertEquals(look.holdMarker, light.holdMarker)
    }

    @Test
    fun clusterLegendGateKeepsBlueInBothModes() {
        // r223: the legend is the steel-blue primary in both modes.
        // Steel blue on the dark dash reads (4.69:1) — the legend stays blue.
        assertTrue(clusterLegendReads(Color(0xFF3E85A7), Color(0xFF0E0E14)))
        assertEquals(Color(0xFF3E85A7), clusterLegendColor(Color(0xFF0E0E14), Color(0xFF3E85A7), Color(0xFFF5F2EC)))
        // Light steel blue on the new dark-grey light field reads (5.63:1).
        assertTrue(clusterLegendReads(Color(0xFF7FB3C8), Color(0xFF2F3237)))
        assertEquals(Color(0xFF7FB3C8), clusterLegendColor(Color(0xFF2F3237), Color(0xFF7FB3C8), Color(0xFFF5F2EC)))
        // Light amber reads on both fields now (6.92:1 dark, 4.63:1 light) —
        // it stays its own colour wherever the rule picks it.
        assertTrue(clusterLegendReads(Color(0xFFCD8E48), Color(0xFF0E0E14)))
        assertTrue(clusterLegendReads(Color(0xFFCD8E48), Color(0xFF2F3237)))
        // Burnt amber on the light field still does not read (2.36:1) —
        // the fallback to warm white still has a live in-palette case.
        assertFalse(clusterLegendReads(Color(0xFFA3541F), Color(0xFF2F3237)))
        assertEquals(Color(0xFFF5F2EC), clusterLegendColor(Color(0xFF2F3237), Color(0xFFA3541F), Color(0xFFF5F2EC)))
    }

    @Test
    fun dashboardSchemeMappingPinsItsTokens() {
        // r223: the direct mapping test the first build lacked — a wrong
        // token here used to show only on device.
        val dark = dashboardScheme(true)
        assertEquals(Color(DashboardPalette.primaryDark), dark.primary)
        assertEquals(Color(DashboardPalette.secondaryDark), dark.secondary)
        assertEquals(Color(DashboardPalette.tertiaryDark), dark.tertiary)
        assertEquals(Color(DashboardPalette.backgroundDark), dark.background)
        assertEquals(Color(DashboardPalette.backgroundDark), dark.surface)
        assertEquals(Color(DashboardPalette.error), dark.error)
        val light = dashboardScheme(false)
        assertEquals(Color(DashboardPalette.primaryLight), light.primary)
        assertEquals(Color(DashboardPalette.secondaryLight), light.secondary)
        assertEquals(Color(DashboardPalette.tertiaryLight), light.tertiary)
        assertEquals(Color(DashboardPalette.backgroundLight), light.background)
        assertEquals(Color(DashboardPalette.backgroundLight), light.surface)
        // Two grey levels only in light mode: the field grey and the
        // near-black buttons — every surface slot is one of the two.
        assertEquals(Color(DashboardPalette.backgroundLight), light.surfaceVariant)
        assertEquals(Color(DashboardPalette.buttonLight), light.surfaceContainerLowest)
        assertEquals(Color(DashboardPalette.buttonLight), light.surfaceContainerLow)
        assertEquals(Color(DashboardPalette.buttonLight), light.surfaceContainer)
        assertEquals(Color(DashboardPalette.buttonLight), light.surfaceContainerHigh)
        assertEquals(Color(DashboardPalette.buttonLight), light.surfaceContainerHighest)
        assertEquals(Color(DashboardPalette.backgroundLight), light.surfaceDim)
        assertEquals(Color(DashboardPalette.backgroundLight), light.surfaceBright)
        assertEquals(Color(DashboardPalette.error), light.error)
    }

    @Test
    fun gaugeTicksAndTrimDividerArePinned() {
        // r223: nine graduations at 10% intervals, a third of the bar tall.
        assertEquals(listOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f), gaugeTickFractions())
        assertEquals(0.3f, GaugeTickHeightFraction, 0.001f)
        // The trim strip is a slightly heavier warm-silver hairline.
        assertEquals(2.dp, TrimDividerThickness)
    }
}
