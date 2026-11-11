package app.autospace.car

import androidx.compose.ui.graphics.Color
import app.autospace.car.theme.GsThemes
import app.autospace.car.theme.HoldMarkerStyle
import app.autospace.car.theme.clusterLegendColor
import app.autospace.car.theme.clusterLegendReads
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
        // from Robert's Altima dash photos, not invented.
        assertEquals(0xFF0E0E14.toInt(), DashboardPalette.backgroundDark)
        assertEquals(0xFFA3541F.toInt(), DashboardPalette.primaryDark)
        assertEquals(0xFFCD8E48.toInt(), DashboardPalette.secondaryDark)
        assertEquals(0xFF3E85A7.toInt(), DashboardPalette.tertiaryDark)
        assertEquals(0xFFF5F2EC.toInt(), DashboardPalette.onSurfaceDark)
        assertEquals(0xFF62666D.toInt(), DashboardPalette.backgroundLight)
        assertEquals(0xFFF5F2EC.toInt(), DashboardPalette.onSurfaceLight)
        assertEquals(0xFF45484F.toInt(), DashboardPalette.surfaceVariantLight)
        assertEquals(0xFF7FB3C8.toInt(), DashboardPalette.tertiaryLight)
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
        // Amber and steel blue on the light greys do NOT read — the
        // legend gate must fall back there (pinned below).
        assertTrue(contrastRatio(DashboardPalette.secondaryLight, DashboardPalette.backgroundLight) < 4.5)
        assertTrue(contrastRatio(DashboardPalette.tertiaryLight, DashboardPalette.backgroundLight) < 4.5)
    }

    @Test
    fun dashboardPaletteHasNoRedAnywhere() {
        // Robert's hard rule: no red anywhere in Dashboard, either
        // mode. A token counts as red when its red channel dominates
        // (red > 150 while green and blue stay under 100); the umber
        // error (#6B3410, red 107) is not red by that bar.
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
            DashboardPalette.surfaceVariantLight, DashboardPalette.onSurfaceVariantLight,
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
            assertFalse("token #" + Integer.toHexString(token) + " reads as red", r > 150 && g < 100 && b < 100)
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
        val light = GsThemes.Cluster.light
        assertTrue(light != null)
        assertEquals(look.containerAlpha, light!!.containerAlpha, 0.001f)
        assertEquals(look.pressedScale, light.pressedScale, 0.001f)
        assertEquals(look.holdMarker, light.holdMarker)
    }

    @Test
    fun clusterLegendGateKeepsAmberInDarkAndFallsBackInLight() {
        // Amber on the dark dash reads (6.92:1) — the legend stays amber.
        assertTrue(clusterLegendReads(Color(0xFFCD8E48), Color(0xFF0E0E14)))
        assertEquals(Color(0xFFCD8E48), clusterLegendColor(Color(0xFF0E0E14), Color(0xFFCD8E48), Color(0xFFF5F2EC)))
        // Steel blue on the dark dash reads too (4.69:1) — latched stays blue.
        assertTrue(clusterLegendReads(Color(0xFF3E85A7), Color(0xFF0E0E14)))
        assertEquals(Color(0xFF3E85A7), clusterLegendColor(Color(0xFF0E0E14), Color(0xFF3E85A7), Color(0xFFF5F2EC)))
        // Amber on the light greys does not read (2.08:1) — fallback to warm white.
        assertFalse(clusterLegendReads(Color(0xFFCD8E48), Color(0xFF62666D)))
        assertEquals(Color(0xFFF5F2EC), clusterLegendColor(Color(0xFF62666D), Color(0xFFCD8E48), Color(0xFFF5F2EC)))
        // Steel blue on the light greys does not read either (2.52:1).
        assertFalse(clusterLegendReads(Color(0xFF7FB3C8), Color(0xFF62666D)))
        assertEquals(Color(0xFFF5F2EC), clusterLegendColor(Color(0xFF62666D), Color(0xFF7FB3C8), Color(0xFFF5F2EC)))
    }
}
