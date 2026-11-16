package app.autospace.car

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.autospace.car.theme.GsThemes
import app.autospace.car.theme.HoldMarkerStyle
import app.autospace.car.theme.clusterLegendColor
import app.autospace.car.theme.clusterLegendReads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** r228 (Crimson Rain): the Crimson Rain scheme + Ember style, pinned. */
class CrimsonRainTest {

    @Test
    fun crimsonRainSchemeIsAppendedLastAndNamedCrimsonRain() {
        assertEquals(ColorScheme.CRIMSON_RAIN, ColorScheme.valueOf("CRIMSON_RAIN"))
        assertEquals("Crimson Rain", ColorScheme.CRIMSON_RAIN.displayName)
        assertEquals(ColorScheme.CRIMSON_RAIN, colorSchemes.last())
        assertTrue(colorSchemes.contains(ColorScheme.CRIMSON_RAIN))
        // Neon Rain is second from last, Dashboard third, and nothing else moved.
        assertEquals(ColorScheme.NEON_RAIN, colorSchemes[colorSchemes.size - 2])
        assertEquals(ColorScheme.DASHBOARD, colorSchemes[colorSchemes.size - 3])
        // No legacy install migrates onto Crimson Rain by accident.
        assertFalse(isLegacyThemeName("CRIMSON_RAIN"))
        // Crimson Rain forces dark: every mode and phone state renders dark.
        assertTrue(resolveDark(ColorScheme.CRIMSON_RAIN, AppearanceMode.LIGHT, false))
        assertTrue(resolveDark(ColorScheme.CRIMSON_RAIN, AppearanceMode.LIGHT, true))
        assertTrue(resolveDark(ColorScheme.CRIMSON_RAIN, AppearanceMode.DARK, false))
        assertTrue(resolveDark(ColorScheme.CRIMSON_RAIN, AppearanceMode.DARK, true))
        assertTrue(resolveDark(ColorScheme.CRIMSON_RAIN, AppearanceMode.SYSTEM, false))
        assertTrue(resolveDark(ColorScheme.CRIMSON_RAIN, AppearanceMode.SYSTEM, true))
        assertTrue(schemeForcesDark(ColorScheme.CRIMSON_RAIN))
    }

    @Test
    fun crimsonRainPaletteTokensMatchTheApprovedSpec() {
        // The settled spec values, exact — dark only, no light
        // variant: signal-red primary and error, measured-teal
        // secondary, pale-ice tertiary. Measured from Robert's two
        // Quadra Type-66 interior photos, not picked by eye
        // (~/workspace/cyberpunk-photos/MEASURED.md): the teal is
        // the Crystal Dome bar's own #21B2B2, the red sits in the
        // middle of the photos' red range, the ice is the white
        // gauge numerals. No magenta token exists in the file,
        // and no amber token either — yellow appears in the
        // photos only as stitching.
        assertEquals(0xFF0B0B0D.toInt(), CrimsonRainPalette.background)
        assertEquals(0xFFE8F6FF.toInt(), CrimsonRainPalette.onSurface)
        assertEquals(0xFF141419.toInt(), CrimsonRainPalette.surfaceVariant)
        assertEquals(0xFF8FA3B8.toInt(), CrimsonRainPalette.onSurfaceVariant)
        assertEquals(0xFFFF003C.toInt(), CrimsonRainPalette.primary)
        assertEquals(0xFF0B0B0D.toInt(), CrimsonRainPalette.onPrimary)
        assertEquals(0xFF40060F.toInt(), CrimsonRainPalette.primaryContainer)
        assertEquals(0xFFFFC2CC.toInt(), CrimsonRainPalette.onPrimaryContainer)
        assertEquals(0xFF20B0B0.toInt(), CrimsonRainPalette.secondary)
        assertEquals(0xFF001414.toInt(), CrimsonRainPalette.onSecondary)
        assertEquals(0xFF003333.toInt(), CrimsonRainPalette.secondaryContainer)
        assertEquals(0xFFB8F4FF.toInt(), CrimsonRainPalette.onSecondaryContainer)
        assertEquals(0xFFE8F6FF.toInt(), CrimsonRainPalette.tertiary)
        assertEquals(0xFF0B0B0D.toInt(), CrimsonRainPalette.onTertiary)
        assertEquals(0xFF1A2833.toInt(), CrimsonRainPalette.tertiaryContainer)
        assertEquals(0xFFD6ECF5.toInt(), CrimsonRainPalette.onTertiaryContainer)
        assertEquals(0xFF2A3A4A.toInt(), CrimsonRainPalette.outline)
        assertEquals(0xFF1A2432.toInt(), CrimsonRainPalette.outlineVariant)
        assertEquals(0xFFFF003C.toInt(), CrimsonRainPalette.error)
        assertEquals(0xFFFFFFFF.toInt(), CrimsonRainPalette.onError)
        // The background is near-black, not pure black — so
        // Crimson Rain stays out of oledBlackSchemes by design.
        assertFalse(CrimsonRainPalette.background == 0xFF000000.toInt())
    }

    @Test
    fun crimsonRainPaletteContrastClearsTheBars() {
        // The numbers the legend gate relies on (WCAG, opaque RGB).
        // Red + near-black is 4.98:1 — the research report's 5.32:1
        // was measured against pure black; against the real
        // background it is 4.98:1, still past the bar.
        assertTrue(contrastRatio(CrimsonRainPalette.onPrimary, CrimsonRainPalette.primary) >= 4.5)
        assertEquals(4.98, contrastRatio(CrimsonRainPalette.onPrimary, CrimsonRainPalette.primary), 0.02)
        assertTrue(contrastRatio(CrimsonRainPalette.onSecondary, CrimsonRainPalette.secondary) >= 4.5)
        assertEquals(7.11, contrastRatio(CrimsonRainPalette.onSecondary, CrimsonRainPalette.secondary), 0.02)
        assertTrue(contrastRatio(CrimsonRainPalette.onPrimaryContainer, CrimsonRainPalette.primaryContainer) >= 4.5)
        assertEquals(11.06, contrastRatio(CrimsonRainPalette.onPrimaryContainer, CrimsonRainPalette.primaryContainer), 0.02)
        assertTrue(contrastRatio(CrimsonRainPalette.onSecondaryContainer, CrimsonRainPalette.secondaryContainer) >= 4.5)
        assertEquals(11.45, contrastRatio(CrimsonRainPalette.onSecondaryContainer, CrimsonRainPalette.secondaryContainer), 0.02)
        assertTrue(contrastRatio(CrimsonRainPalette.onTertiary, CrimsonRainPalette.tertiary) >= 4.5)
        assertEquals(17.85, contrastRatio(CrimsonRainPalette.onTertiary, CrimsonRainPalette.tertiary), 0.02)
        assertTrue(contrastRatio(CrimsonRainPalette.onTertiaryContainer, CrimsonRainPalette.tertiaryContainer) >= 4.5)
        assertEquals(12.31, contrastRatio(CrimsonRainPalette.onTertiaryContainer, CrimsonRainPalette.tertiaryContainer), 0.02)
        assertTrue(contrastRatio(CrimsonRainPalette.onSurface, CrimsonRainPalette.background) >= 4.5)
        assertEquals(17.85, contrastRatio(CrimsonRainPalette.onSurface, CrimsonRainPalette.background), 0.02)
        assertTrue(contrastRatio(CrimsonRainPalette.onSurfaceVariant, CrimsonRainPalette.surfaceVariant) >= 4.5)
        assertEquals(7.08, contrastRatio(CrimsonRainPalette.onSurfaceVariant, CrimsonRainPalette.surfaceVariant), 0.02)
        // The legend colours on the field: red 4.98:1, teal
        // 7.40:1, pale ice 17.85:1 — all read, so the legend
        // stays its own colour on the field in both states.
        assertEquals(4.98, contrastRatio(CrimsonRainPalette.primary, CrimsonRainPalette.background), 0.02)
        assertEquals(7.40, contrastRatio(CrimsonRainPalette.secondary, CrimsonRainPalette.background), 0.02)
        assertEquals(17.85, contrastRatio(CrimsonRainPalette.tertiary, CrimsonRainPalette.background), 0.02)
        // Error text on the field reads (4.98:1); white on error
        // is modest at 3.95:1 — pinned exact, the same class as
        // Dashboard's red note (r224): white text on a red chip
        // is the fix if it reads poorly on device.
        assertTrue(contrastRatio(CrimsonRainPalette.error, CrimsonRainPalette.background) >= 4.5)
        assertEquals(4.98, contrastRatio(CrimsonRainPalette.error, CrimsonRainPalette.background), 0.02)
        assertEquals(3.95, contrastRatio(CrimsonRainPalette.onError, CrimsonRainPalette.error), 0.02)
    }

    @Test
    fun crimsonRainRedLivesOnlyInApprovedTokens() {
        // Red lives in exactly two spots, the primary role and
        // the error role — both signal red #FF003C. The tertiary
        // is pale ice, NOT red: a red tertiary would put red text
        // on red fills everywhere the assistant reads it. A token
        // counts as red when its red channel dominates (red > 150
        // while green and blue stay under 100) AND it is far more
        // red than green (red - green > 100).
        val tokens = listOf(
            CrimsonRainPalette.background, CrimsonRainPalette.onSurface,
            CrimsonRainPalette.surfaceVariant, CrimsonRainPalette.onSurfaceVariant,
            CrimsonRainPalette.primary, CrimsonRainPalette.onPrimary,
            CrimsonRainPalette.primaryContainer, CrimsonRainPalette.onPrimaryContainer,
            CrimsonRainPalette.secondary, CrimsonRainPalette.onSecondary,
            CrimsonRainPalette.secondaryContainer, CrimsonRainPalette.onSecondaryContainer,
            CrimsonRainPalette.tertiary, CrimsonRainPalette.onTertiary,
            CrimsonRainPalette.tertiaryContainer, CrimsonRainPalette.onTertiaryContainer,
            CrimsonRainPalette.outline, CrimsonRainPalette.outlineVariant,
            CrimsonRainPalette.error, CrimsonRainPalette.onError,
        )
        val redTokens = tokens.filter { token ->
            val r = (token shr 16) and 0xFF
            val g = (token shr 8) and 0xFF
            val b = token and 0xFF
            r > 150 && g < 100 && b < 100 && (r - g) > 100
        }
        assertEquals(2, redTokens.size)
        for (token in redTokens) {
            assertEquals(0xFFFF003C.toInt(), token)
        }
        assertEquals(0xFFFF003C.toInt(), CrimsonRainPalette.error)
        assertEquals(0xFFFF003C.toInt(), CrimsonRainPalette.primary)
        // And no amber anywhere: neither old amber value survives,
        // and no token reads as amber (red high, green mid, blue low,
        // red clearly ahead of green but not far enough to be red).
        for (token in tokens) {
            assertFalse("token #" + Integer.toHexString(token) + " is an old amber", token == 0xFFA3541F.toInt() || token == 0xFFCD8E48.toInt() || token == 0xFF6B3410.toInt())
            val r = (token shr 16) and 0xFF
            val g = (token shr 8) and 0xFF
            val b = token and 0xFF
            assertFalse("token #" + Integer.toHexString(token) + " reads as amber", r > 120 && g > 40 && g < 170 && b < 110 && (r - g) > 40 && (r - g) < 130)
        }
    }

    @Test
    fun crimsonRainHasNoMagentaAnywhere() {
        // r228: hot magenta (#FF2A6D) is absent from the start —
        // the palette was measured from the photos, and no
        // magenta lives in them. No token holds it, and no token
        // reads as magenta (red high, green low, blue mid, red
        // clearly ahead of blue).
        val tokens = listOf(
            CrimsonRainPalette.background, CrimsonRainPalette.onSurface,
            CrimsonRainPalette.surfaceVariant, CrimsonRainPalette.onSurfaceVariant,
            CrimsonRainPalette.primary, CrimsonRainPalette.onPrimary,
            CrimsonRainPalette.primaryContainer, CrimsonRainPalette.onPrimaryContainer,
            CrimsonRainPalette.secondary, CrimsonRainPalette.onSecondary,
            CrimsonRainPalette.secondaryContainer, CrimsonRainPalette.onSecondaryContainer,
            CrimsonRainPalette.tertiary, CrimsonRainPalette.onTertiary,
            CrimsonRainPalette.tertiaryContainer, CrimsonRainPalette.onTertiaryContainer,
            CrimsonRainPalette.outline, CrimsonRainPalette.outlineVariant,
            CrimsonRainPalette.error, CrimsonRainPalette.onError,
        )
        for (token in tokens) {
            assertFalse("token #" + Integer.toHexString(token) + " is magenta", token == 0xFFFF2A6D.toInt())
            val r = (token shr 16) and 0xFF
            val g = (token shr 8) and 0xFF
            val b = token and 0xFF
            assertFalse("token #" + Integer.toHexString(token) + " reads as magenta", r > 200 && g < 110 && b in 90..150 && (r - b) > 100)
        }
    }

    @Test
    fun emberStyleIsAppendedLastAndNamedEmber() {
        assertEquals("ember", GsThemes.Ember.id)
        assertEquals("Ember", GsThemes.Ember.name)
        assertEquals(GsThemes.Ember, GsThemes.builtIn.last())
        assertEquals("ember", GsThemes.byId("ember").id)
        // Shard is second from last, Cluster third, Aurora fourth, and nothing else moved.
        assertEquals(GsThemes.Shard, GsThemes.builtIn[GsThemes.builtIn.size - 2])
        assertEquals(GsThemes.Cluster, GsThemes.builtIn[GsThemes.builtIn.size - 3])
        assertEquals(GsThemes.Aurora, GsThemes.builtIn[GsThemes.builtIn.size - 4])
    }

    @Test
    fun emberLookPins() {
        // The squared slab is the style: chamferCut 0, corner
        // 2dp — the structural opposite of Shard's 6dp chamfer.
        // Rocker switches on (bolts light the scheme primary),
        // LED dot on (role-driven, not the old fixed yellow),
        // engraved headers on (red mono under Crimson Rain). No
        // rivets: the slab is clean. No light variant — dark only.
        val look = GsThemes.Ember.control
        assertEquals(1f, look.containerAlpha, 0.001f)
        assertEquals(2, look.corner.value.toInt())
        assertEquals(0, look.chamferCut.value.toInt())
        assertTrue(look.rockerToggle)
        assertTrue(look.ledDot)
        assertTrue(look.engravedHeader)
        assertFalse(look.rivets)
        assertEquals(0f, look.weaveAlpha, 0.001f)
        assertEquals(0f, look.topLight, 0.001f)
        assertEquals(0f, look.hairlineAlpha, 0.001f)
        assertEquals(0f, look.rimAlpha, 0.001f)
        assertEquals(2, look.shadow.value.toInt())
        assertEquals(0.30f, look.shadowAlpha, 0.001f)
        assertEquals(0.12f, look.pressedDim, 0.001f)
        assertEquals(0.95f, look.pressedScale, 0.001f)
        assertEquals(HoldMarkerStyle.DOT, look.holdMarker)
        assertEquals(null, look.light)
    }

    @Test
    fun emberLegendGateKeepsRedDefaultAndTealLatched() {
        // r228: the legend is signal red by default and measured
        // teal when latched. Red reads on the field at 4.98:1 and
        // teal at 7.40:1 — so on the field the legend stays its
        // own colour in both states. On a red fill the gate falls
        // back to the default content colour instead: red on red
        // is 1.00:1 and teal on red is 1.49:1. On a dark chip face
        // (#18181C) red reads only 4.48:1 — BELOW the bar — so
        // red falls back there too, while teal at 6.66:1 stays.
        // This mirrors Dashboard/Cluster's r224 red rule: the
        // text falls back, the glow keeps the accent.
        assertTrue(clusterLegendReads(Color(0xFFFF003C), Color(0xFF0B0B0D)))
        assertEquals(Color(0xFFFF003C), clusterLegendColor(Color(0xFF0B0B0D), Color(0xFFFF003C), Color(0xFFE8F6FF)))
        assertTrue(clusterLegendReads(Color(0xFF20B0B0), Color(0xFF0B0B0D)))
        assertEquals(Color(0xFF20B0B0), clusterLegendColor(Color(0xFF0B0B0D), Color(0xFF20B0B0), Color(0xFFE8F6FF)))
        assertEquals(1.00, contrastRatio(0xFFFF003C.toInt(), 0xFFFF003C.toInt()), 0.02)
        assertEquals(Color(0xFFE8F6FF), clusterLegendColor(Color(0xFFFF003C), Color(0xFFFF003C), Color(0xFFE8F6FF)))
        assertEquals(1.49, contrastRatio(0xFF20B0B0.toInt(), 0xFFFF003C.toInt()), 0.02)
        assertEquals(Color(0xFFE8F6FF), clusterLegendColor(Color(0xFFFF003C), Color(0xFF20B0B0), Color(0xFFE8F6FF)))
        assertEquals(4.48, contrastRatio(0xFFFF003C.toInt(), 0xFF18181C.toInt()), 0.02)
        assertTrue(contrastRatio(0xFFFF003C.toInt(), 0xFF18181C.toInt()) < 4.5)
        assertEquals(Color(0xFFE8F6FF), clusterLegendColor(Color(0xFF18181C), Color(0xFFFF003C), Color(0xFFE8F6FF)))
        assertEquals(6.66, contrastRatio(0xFF20B0B0.toInt(), 0xFF18181C.toInt()), 0.02)
        assertEquals(Color(0xFF20B0B0), clusterLegendColor(Color(0xFF18181C), Color(0xFF20B0B0), Color(0xFFE8F6FF)))
    }

    @Test
    fun crimsonRainSchemeMappingPinsItsTokens() {
        val scheme = crimsonRainScheme()
        assertEquals(Color(CrimsonRainPalette.primary), scheme.primary)
        assertEquals(Color(CrimsonRainPalette.secondary), scheme.secondary)
        assertEquals(Color(CrimsonRainPalette.tertiary), scheme.tertiary)
        assertEquals(Color(CrimsonRainPalette.background), scheme.background)
        assertEquals(Color(CrimsonRainPalette.background), scheme.surface)
        assertEquals(Color(CrimsonRainPalette.error), scheme.error)
        assertEquals(Color(CrimsonRainPalette.onSurface), scheme.onSurface)
        assertEquals(Color(0xFF060608), scheme.surfaceContainerLowest)
        assertEquals(Color(0xFF121216), scheme.surfaceContainer)
        assertEquals(Color(CrimsonRainPalette.onSurface), scheme.inverseSurface)
    }

    @Test
    fun crimsonRainPairIsAppendedLast() {
        val pair = themePairs.last()
        assertEquals("Crimson Rain + Ember", pair.label)
        assertEquals("ember", pair.styleId)
        assertEquals(ColorScheme.CRIMSON_RAIN, pair.scheme)
        assertEquals(pair, themePairFor("ember", ColorScheme.CRIMSON_RAIN))
        // Neon Rain + Shard is second from last, and nothing else moved.
        assertEquals("Neon Rain + Shard", themePairs[themePairs.size - 2].label)
        assertEquals("Pitlane + Pitlane", themePairs.first().label)
    }
}
