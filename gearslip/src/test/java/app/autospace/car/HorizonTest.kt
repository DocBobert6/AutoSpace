package app.autospace.car

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import app.autospace.car.theme.GsKit
import app.autospace.car.theme.GsThemes
import app.autospace.car.theme.buttonShapeForLook
import app.autospace.car.theme.cardShapeForLook
import app.autospace.car.theme.stateChangeMotionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Horizon (r79): the theme + button style pair, Verve's sibling. Scheme
 * tokens live in [HorizonPalette] as plain ARGB Ints, so the contrast
 * rules are JVM-testable; [CarUi.horizonScheme] maps them mechanically.
 */
class HorizonTest {

    @Test
    fun horizonSchemeIsNamedHorizonAndImmediatelyBeforeMeridian() {
        assertEquals(ColorScheme.HORIZON, ColorScheme.valueOf("HORIZON"))
        assertEquals("Horizon", ColorScheme.HORIZON.displayName)
        // r107 appends Meridian last (pinned by MeridianTest); Horizon
        // keeps the slot immediately before it, and nothing else moved.
        assertEquals(ColorScheme.HORIZON, colorSchemes[colorSchemes.size - 2])
        assertTrue(colorSchemes.contains(ColorScheme.HORIZON))
        assertEquals(ColorScheme.VERVE, colorSchemes[colorSchemes.size - 3])
        // No legacy install migrates onto Horizon by accident.
        assertFalse(isLegacyThemeName("HORIZON"))
    }

    @Test
    fun horizonPaletteTokensMatchTheApprovedSpec() {
        // Settled dark values, exact.
        assertEquals(0xFF0B1526.toInt(), HorizonPalette.backgroundDark)
        assertEquals(0xFFE9EEF7.toInt(), HorizonPalette.onSurfaceDark)
        assertEquals(0xFF1E6FD9.toInt(), HorizonPalette.primaryDark)
        assertEquals(0xFF7FE0C3.toInt(), HorizonPalette.secondaryDark)
        assertEquals(0xFF4CC06A.toInt(), HorizonPalette.tertiaryDark)
        // Settled light values, exact.
        assertEquals(0xFFF4F8FF.toInt(), HorizonPalette.backgroundLight)
        assertEquals(0xFF16233A.toInt(), HorizonPalette.onSurfaceLight)
        assertEquals(0xFF1D6FE0.toInt(), HorizonPalette.primaryLight)
    }

    @Test
    fun horizonSelectedChipContrastPassesBothVariants() {
        // The settled ratios are white-on-primary, exactly as Verve's 5.21 is.
        assertEquals(4.85, contrastRatio(HorizonPalette.onPrimaryDark, HorizonPalette.primaryDark), 0.02)
        assertTrue(contrastRatio(HorizonPalette.onPrimaryDark, HorizonPalette.primaryDark) >= 4.5)
        assertEquals(4.77, contrastRatio(HorizonPalette.onPrimaryLight, HorizonPalette.primaryLight), 0.02)
        assertTrue(contrastRatio(HorizonPalette.onPrimaryLight, HorizonPalette.primaryLight) >= 4.5)
        // Body text pairs, both variants, comfortably pass.
        assertTrue(contrastRatio(HorizonPalette.onSurfaceDark, HorizonPalette.backgroundDark) >= 4.5)
        assertTrue(contrastRatio(HorizonPalette.onSurfaceLight, HorizonPalette.backgroundLight) >= 4.5)
        // Dark accents take dark text (Verve's tertiary pattern), light
        // accents are deepened — every accent pair passes.
        assertTrue(contrastRatio(HorizonPalette.onSecondaryDark, HorizonPalette.secondaryDark) >= 4.5)
        assertTrue(contrastRatio(HorizonPalette.onTertiaryDark, HorizonPalette.tertiaryDark) >= 4.5)
        assertTrue(contrastRatio(HorizonPalette.onSecondaryLight, HorizonPalette.secondaryLight) >= 4.5)
        assertTrue(contrastRatio(HorizonPalette.onTertiaryLight, HorizonPalette.tertiaryLight) >= 4.5)
        // Container + surface-variant pairs, both variants, all pass.
        assertTrue(contrastRatio(HorizonPalette.onPrimaryContainerDark, HorizonPalette.primaryContainerDark) >= 4.5)
        assertTrue(contrastRatio(HorizonPalette.onSecondaryContainerDark, HorizonPalette.secondaryContainerDark) >= 4.5)
        assertTrue(contrastRatio(HorizonPalette.onTertiaryContainerDark, HorizonPalette.tertiaryContainerDark) >= 4.5)
        assertTrue(contrastRatio(HorizonPalette.onPrimaryContainerLight, HorizonPalette.primaryContainerLight) >= 4.5)
        assertTrue(contrastRatio(HorizonPalette.onSecondaryContainerLight, HorizonPalette.secondaryContainerLight) >= 4.5)
        assertTrue(contrastRatio(HorizonPalette.onTertiaryContainerLight, HorizonPalette.tertiaryContainerLight) >= 4.5)
        assertTrue(contrastRatio(HorizonPalette.onSurfaceVariantDark, HorizonPalette.surfaceVariantDark) >= 4.5)
        assertTrue(contrastRatio(HorizonPalette.onSurfaceVariantLight, HorizonPalette.surfaceVariantLight) >= 4.5)
    }

    @Test
    fun horizonFollowsTheModeAndAutoNightOverridesIt() {
        // Horizon does not force dark (unlike Night/Pitlane): the mode
        // control decides, and Auto night still overrides it to NIGHT.
        assertFalse(schemeForcesDark(ColorScheme.HORIZON))
        assertFalse(resolveDark(ColorScheme.HORIZON, AppearanceMode.LIGHT, true))
        assertTrue(resolveDark(ColorScheme.HORIZON, AppearanceMode.DARK, false))
        assertTrue(resolveDark(ColorScheme.HORIZON, AppearanceMode.SYSTEM, true))
        assertFalse(resolveDark(ColorScheme.HORIZON, AppearanceMode.SYSTEM, false))
        assertEquals(ColorScheme.NIGHT, effectiveScheme(ColorScheme.HORIZON, true))
        assertEquals(ColorScheme.HORIZON, effectiveScheme(ColorScheme.HORIZON, false))
    }

    @Test
    fun horizonStyleIsBuiltInBeforeMeridianWith28dpCornerPillButtonsAnd065Spring() {
        assertTrue(GsThemes.builtIn.any { it.id == "horizon" && it.name == "Horizon" })
        // r107 appends Meridian last (pinned by MeridianTest), r108
        // appends Heirloom last, r110 appends Blueprint last,
        // r112 appends Sector last, and r113 appends Silhouette
        // last; Horizon keeps its slot, sixth from last, and
        // nothing else moved.
        assertEquals(GsThemes.Horizon, GsThemes.builtIn[GsThemes.builtIn.size - 6])
        assertEquals("horizon", GsThemes.byId("horizon").id)
        val look = GsThemes.Horizon.control
        assertEquals(28, look.corner.value.toInt())
        assertTrue(look.springMotion)
        assertEquals(0.65f, look.springDamping, 0.001f)
        assertTrue(look.cardsUseStyleCorner)
        assertTrue(look.pillButtons)
        assertEquals(0.97f, look.pressedScale, 0.001f)
        assertFalse(look.rectangularButtons)
        assertFalse(look.checkerStrip)
        assertEquals(0f, look.weaveAlpha, 0.001f)
        assertEquals(0f, look.grainAlpha, 0.001f)
        assertFalse(look.rivets)
        assertFalse(look.ledDot)
        assertFalse(look.batToggle)
        assertFalse(look.engravedHeader)
        assertFalse(look.bevelOnLatched)
        val light = checkNotNull(look.light)
        assertEquals(28, light.corner.value.toInt())
        assertTrue(light.springMotion)
        assertEquals(0.65f, light.springDamping, 0.001f)
        assertTrue(light.cardsUseStyleCorner)
        assertTrue(light.pillButtons)
        assertEquals(0.97f, light.pressedScale, 0.001f)
        // Frost is still the default; no other style carries Horizon's flags.
        assertEquals("frost", GsThemes.default.id)
        for (other in GsThemes.builtIn.filter { it.id != "horizon" }) {
            assertFalse(other.control.pillButtons)
            assertFalse(other.control.light?.pillButtons == true)
        }
        // r100 (Verve Refined): Verve passes its own 1.0 damping
        // (critically damped, no overshoot); the shared default stays 0.7.
        assertEquals(1.0f, GsThemes.Verve.control.springDamping, 0.001f)
    }

    @Test
    fun cardShapeForLookTakes28dpUnderHorizon() {
        val fallback = RoundedCornerShape(20.dp)
        assertEquals(RoundedCornerShape(28.dp), cardShapeForLook(GsThemes.Horizon.control, fallback))
        assertEquals(RoundedCornerShape(28.dp), cardShapeForLook(GsThemes.Horizon.control, RoundedCornerShape(0.dp)))
        assertEquals(RoundedCornerShape(28.dp), cardShapeForLook(checkNotNull(GsThemes.Horizon.control.light), fallback))
        assertEquals(fallback, cardShapeForLook(GsThemes.Frost.control, fallback))
        assertEquals(RoundedCornerShape(24.dp), cardShapeForLook(GsThemes.Verve.control, fallback))
    }

    @Test
    fun buttonShapeForLookIsPillUnderHorizonOnly() {
        assertEquals(GsKit.PillShape, buttonShapeForLook(GsThemes.Horizon.control))
        assertEquals(GsKit.PillShape, buttonShapeForLook(checkNotNull(GsThemes.Horizon.control.light)))
        assertEquals(RoundedCornerShape(24.dp), buttonShapeForLook(GsThemes.Verve.control))
        assertEquals(RoundedCornerShape(20.dp), buttonShapeForLook(GsThemes.Frost.control))
        assertEquals(RoundedCornerShape(6.dp), buttonShapeForLook(GsThemes.Pitlane.control))
    }

    @Test
    fun stateChangeMotionIs065SpringParkedAndSnapMovingForHorizon() {
        val parked = stateChangeMotionSpec<androidx.compose.ui.unit.Dp>(false, 0.65f)
        assertTrue(parked is SpringSpec)
        val spring = parked as SpringSpec<androidx.compose.ui.unit.Dp>
        assertEquals(0.65f, spring.dampingRatio, 0.001f)
        assertEquals(Spring.StiffnessMedium, spring.stiffness, 0.001f)
        assertTrue(stateChangeMotionSpec<androidx.compose.ui.unit.Dp>(true, 0.65f) is SnapSpec)
    }

    @Test
    fun stateChangeMotionDefaultDampingIsStillVerveSpring() {
        // The new damping parameter defaults to Verve's approved 0.7, so
        // every existing caller — and VerveTest's pin — is unchanged.
        val parked = stateChangeMotionSpec<androidx.compose.ui.unit.Dp>(false)
        assertTrue(parked is SpringSpec)
        assertEquals(0.7f, (parked as SpringSpec<androidx.compose.ui.unit.Dp>).dampingRatio, 0.001f)
    }
}
