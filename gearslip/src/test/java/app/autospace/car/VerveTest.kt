package app.autospace.car

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import app.autospace.car.theme.GsThemes
import app.autospace.car.theme.cardShapeForLook
import app.autospace.car.theme.stateChangeMotionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verve (r48): the theme + button style pair. Scheme tokens live in
 * [VervePalette] as plain ARGB Ints, so the contrast rules are
 * JVM-testable; [CarUi.verveScheme] maps them mechanically.
 */
class VerveTest {

    @Test
    fun verveSchemeIsNamedVerveAndBeforeHorizon() {
        assertEquals(ColorScheme.VERVE, ColorScheme.valueOf("VERVE"))
        assertEquals("Verve", ColorScheme.VERVE.displayName)
        // r79 appends Horizon last (pinned by HorizonTest) and r107
        // appends Meridian last (pinned by MeridianTest); Verve keeps
        // its order before both, and nothing else moved.
        assertEquals(ColorScheme.VERVE, colorSchemes[colorSchemes.size - 3])
        assertTrue(colorSchemes.contains(ColorScheme.VERVE))
        // No legacy install migrates onto Verve by accident.
        assertFalse(isLegacyThemeName("VERVE"))
    }

    @Test
    fun vervePaletteTokensMatchTheApprovedSpec() {
        // Dark (default character): ink, soft white-violet, violet/coral/sunny.
        assertEquals(0xFF16121F.toInt(), VervePalette.backgroundDark)
        assertEquals(0xFFF2EFFA.toInt(), VervePalette.onSurfaceDark)
        assertEquals(0xFF2A2340.toInt(), VervePalette.surfaceVariantDark)
        assertEquals(0xFFFF5C7A.toInt(), VervePalette.secondaryDark)
        assertEquals(0xFFFFC53D.toInt(), VervePalette.tertiaryDark)
        assertEquals(0xFF1A1A1A.toInt(), VervePalette.onTertiaryDark)
        // Light: white, deepened accents.
        assertEquals(0xFFFFFFFF.toInt(), VervePalette.backgroundLight)
        assertEquals(0xFF1E1A2E.toInt(), VervePalette.onSurfaceLight)
        assertEquals(0xFFEDE9F5.toInt(), VervePalette.surfaceVariantLight)
        assertEquals(0xFF6D28D9.toInt(), VervePalette.primaryLight)
        assertEquals(0xFFE11D48.toInt(), VervePalette.secondaryLight)
        assertEquals(0xFFB45309.toInt(), VervePalette.tertiaryLight)
    }

    @Test
    fun verveSelectedChipContrastPassesBothVariants() {
        // The spec's own rule: darken the accent, never the text.
        // Spec dark primary #8B5CF6 with white is 4.23:1 — under the
        // 4.5:1 bar — so dark primary ships darkened to #8347E8.
        assertTrue(contrastRatio(0xFFFFFFFF.toInt(), 0xFF8B5CF6.toInt()) < 4.5)
        assertEquals(0xFF8347E8.toInt(), VervePalette.primaryDark)
        assertEquals(5.21, contrastRatio(VervePalette.onPrimaryDark, VervePalette.primaryDark), 0.02)
        assertTrue(contrastRatio(VervePalette.onPrimaryDark, VervePalette.primaryDark) >= 4.5)
        assertEquals(7.10, contrastRatio(VervePalette.onPrimaryLight, VervePalette.primaryLight), 0.02)
        assertTrue(contrastRatio(VervePalette.onPrimaryLight, VervePalette.primaryLight) >= 4.5)
        // Body text pairs, both variants, comfortably pass.
        assertTrue(contrastRatio(VervePalette.onSurfaceDark, VervePalette.backgroundDark) >= 4.5)
        assertTrue(contrastRatio(VervePalette.onSurfaceLight, VervePalette.backgroundLight) >= 4.5)
    }

    @Test
    fun verveFollowsTheModeAndAutoNightOverridesIt() {
        // Verve does not force dark (unlike Night/Pitlane): the mode
        // control decides, and Auto night still overrides it to NIGHT.
        assertFalse(schemeForcesDark(ColorScheme.VERVE))
        assertFalse(resolveDark(ColorScheme.VERVE, AppearanceMode.LIGHT, true))
        assertTrue(resolveDark(ColorScheme.VERVE, AppearanceMode.DARK, false))
        assertTrue(resolveDark(ColorScheme.VERVE, AppearanceMode.SYSTEM, true))
        assertFalse(resolveDark(ColorScheme.VERVE, AppearanceMode.SYSTEM, false))
        assertEquals(ColorScheme.NIGHT, effectiveScheme(ColorScheme.VERVE, true))
        assertEquals(ColorScheme.VERVE, effectiveScheme(ColorScheme.VERVE, false))
    }

    @Test
    fun verveStyleIsBuiltInWith24dpCornerAndSpringFlags() {
        assertTrue(GsThemes.builtIn.any { it.id == "verve" && it.name == "Verve" })
        // r79 appends Horizon last (pinned by HorizonTest), r107
        // appends Meridian last (pinned by MeridianTest), r108
        // appends Heirloom last, r110 appends Blueprint last,
        // r112 appends Sector last, and r113 appends Silhouette
        // last; Verve keeps its order before all six, and
        // nothing else moved.
        assertEquals(GsThemes.Verve, GsThemes.builtIn[GsThemes.builtIn.size - 7])
        assertEquals("verve", GsThemes.byId("verve").id)
        val look = GsThemes.Verve.control
        assertEquals(24, look.corner.value.toInt())
        assertTrue(look.springMotion)
        assertTrue(look.cardsUseStyleCorner)
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
        assertEquals(24, light.corner.value.toInt())
        assertTrue(light.springMotion)
        assertTrue(light.cardsUseStyleCorner)
        // Frost is still the default; besides Horizon (r79, Verve's
        // sibling, which shares the spring flags deliberately) and Meridian
        // (r107, whose critically damped pressed spring and 16dp
        // cardsUseStyleCorner are deliberate), no other style carries
        // Verve's spring flags.
        assertEquals("frost", GsThemes.default.id)
        for (other in GsThemes.builtIn.filter { it.id != "verve" && it.id != "horizon" && it.id != "meridian" }) {
            assertFalse(other.control.springMotion)
            assertFalse(other.control.cardsUseStyleCorner)
            assertFalse(other.control.light?.springMotion == true)
            assertFalse(other.control.light?.cardsUseStyleCorner == true)
        }
    }

    @Test
    fun cardShapeForLookTakesTheStyleCornerOnlyUnderVerveAndPitlane() {
        val fallback = RoundedCornerShape(20.dp)
        assertEquals(RoundedCornerShape(24.dp), cardShapeForLook(GsThemes.Verve.control, fallback))
        assertEquals(RoundedCornerShape(24.dp), cardShapeForLook(GsThemes.Verve.control, RoundedCornerShape(0.dp)))
        assertEquals(RoundedCornerShape(6.dp), cardShapeForLook(GsThemes.Pitlane.control, fallback))
        assertEquals(fallback, cardShapeForLook(GsThemes.Frost.control, fallback))
        assertEquals(fallback, cardShapeForLook(GsThemes.Flat.control, fallback))
        assertEquals(fallback, cardShapeForLook(GsThemes.Edge.control, fallback))
    }

    @Test
    fun stateChangeMotionIsSpringParkedAndSnapMoving() {
        val parked = stateChangeMotionSpec<androidx.compose.ui.unit.Dp>(false)
        assertTrue(parked is SpringSpec)
        val spring = parked as SpringSpec<androidx.compose.ui.unit.Dp>
        assertEquals(0.7f, spring.dampingRatio, 0.001f)
        assertEquals(Spring.StiffnessMedium, spring.stiffness, 0.001f)
        assertTrue(stateChangeMotionSpec<androidx.compose.ui.unit.Dp>(true) is SnapSpec)
    }

    @Test
    fun verveRefinedDampingAndLatchedTint() {
        // r100 (Verve Refined): the spring is retuned to critically
        // damped — the fastest settle with no overshoot — and latched
        // controls carry a faint primary tint. Both variants, and no
        // other style gains the tint — besides Blueprint (r110,
        // whose latched primary ring + faint tint are deliberate)
        // and Sector (r112, whose latched trio brighten + faint
        // tint are deliberate).
        val look = GsThemes.Verve.control
        assertEquals(1.0f, look.springDamping, 0.001f)
        assertTrue(look.latchedPrimaryTint)
        val light = checkNotNull(look.light)
        assertEquals(1.0f, light.springDamping, 0.001f)
        assertTrue(light.latchedPrimaryTint)
        for (other in GsThemes.builtIn.filter { it.id != "verve" && it.id != "blueprint" && it.id != "sector" }) {
            assertFalse(other.control.latchedPrimaryTint)
            assertFalse(other.control.light?.latchedPrimaryTint == true)
        }
        // Horizon keeps its own 0.65 damping; the shared default is untouched.
        assertEquals(0.65f, GsThemes.Horizon.control.springDamping, 0.001f)
        assertEquals(0.7f, GsThemes.Frost.control.springDamping, 0.001f)
    }
}
