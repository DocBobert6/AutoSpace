package app.autospace.car

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.autospace.car.theme.GsThemes
import app.autospace.car.theme.HoldMarkerStyle
import app.autospace.car.theme.ShardLedSegmentCount
import app.autospace.car.theme.clusterLegendColor
import app.autospace.car.theme.clusterLegendReads
import app.autospace.car.theme.shardBarcodeBars
import app.autospace.car.theme.shardHexTag
import app.autospace.car.theme.shardSegmentBoundaries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** r226 (Neon Rain): the Neon Rain scheme + Shard style, pinned. */
class NeonRainTest {

    @Test
    fun neonRainSchemeIsAppendedLastAndNamedNeonRain() {
        assertEquals(ColorScheme.NEON_RAIN, ColorScheme.valueOf("NEON_RAIN"))
        assertEquals("Neon Rain", ColorScheme.NEON_RAIN.displayName)
        assertEquals(ColorScheme.NEON_RAIN, colorSchemes.last())
        assertTrue(colorSchemes.contains(ColorScheme.NEON_RAIN))
        // Dashboard is second from last, and nothing else moved.
        assertEquals(ColorScheme.DASHBOARD, colorSchemes[colorSchemes.size - 2])
        // No legacy install migrates onto Neon Rain by accident.
        assertFalse(isLegacyThemeName("NEON_RAIN"))
        // Neon Rain forces dark: every mode and phone state renders dark.
        assertTrue(resolveDark(ColorScheme.NEON_RAIN, AppearanceMode.LIGHT, false))
        assertTrue(resolveDark(ColorScheme.NEON_RAIN, AppearanceMode.LIGHT, true))
        assertTrue(resolveDark(ColorScheme.NEON_RAIN, AppearanceMode.DARK, false))
        assertTrue(resolveDark(ColorScheme.NEON_RAIN, AppearanceMode.DARK, true))
        assertTrue(resolveDark(ColorScheme.NEON_RAIN, AppearanceMode.SYSTEM, false))
        assertTrue(resolveDark(ColorScheme.NEON_RAIN, AppearanceMode.SYSTEM, true))
        assertTrue(schemeForcesDark(ColorScheme.NEON_RAIN))
    }

    @Test
    fun neonRainPaletteTokensMatchTheApprovedSpec() {
        // The settled spec values, exact — dark only, no light
        // variant: neon cyan primary, hot magenta secondary and
        // active colour, acid yellow tertiary used sparingly,
        // signal-red error. No amber token exists in the file.
        assertEquals(0xFF0A0A12.toInt(), NeonRainPalette.background)
        assertEquals(0xFFE8F6FF.toInt(), NeonRainPalette.onSurface)
        assertEquals(0xFF14141F.toInt(), NeonRainPalette.surfaceVariant)
        assertEquals(0xFF8FA3B8.toInt(), NeonRainPalette.onSurfaceVariant)
        assertEquals(0xFF00F0FF.toInt(), NeonRainPalette.primary)
        assertEquals(0xFF001318.toInt(), NeonRainPalette.onPrimary)
        assertEquals(0xFF00333D.toInt(), NeonRainPalette.primaryContainer)
        assertEquals(0xFFB8F4FF.toInt(), NeonRainPalette.onPrimaryContainer)
        assertEquals(0xFFFF2A6D.toInt(), NeonRainPalette.secondary)
        assertEquals(0xFF1A000A.toInt(), NeonRainPalette.onSecondary)
        assertEquals(0xFF3D0A1E.toInt(), NeonRainPalette.secondaryContainer)
        assertEquals(0xFFFFC2D6.toInt(), NeonRainPalette.onSecondaryContainer)
        assertEquals(0xFFFCEE0A.toInt(), NeonRainPalette.tertiary)
        assertEquals(0xFF1A1A00.toInt(), NeonRainPalette.onTertiary)
        assertEquals(0xFF3D3A00.toInt(), NeonRainPalette.tertiaryContainer)
        assertEquals(0xFFFFF9B8.toInt(), NeonRainPalette.onTertiaryContainer)
        assertEquals(0xFF2A3A4A.toInt(), NeonRainPalette.outline)
        assertEquals(0xFF1A2432.toInt(), NeonRainPalette.outlineVariant)
        assertEquals(0xFFFF003C.toInt(), NeonRainPalette.error)
        assertEquals(0xFFFFFFFF.toInt(), NeonRainPalette.onError)
        // The background is near-black, not pure black — so Neon
        // Rain stays out of oledBlackSchemes by design.
        assertFalse(NeonRainPalette.background == 0xFF000000.toInt())
    }

    @Test
    fun neonRainPaletteContrastClearsTheBars() {
        // The numbers the legend gate relies on (WCAG, opaque RGB).
        assertTrue(contrastRatio(NeonRainPalette.onPrimary, NeonRainPalette.primary) >= 4.5)
        assertEquals(13.48, contrastRatio(NeonRainPalette.onPrimary, NeonRainPalette.primary), 0.02)
        assertTrue(contrastRatio(NeonRainPalette.onSecondary, NeonRainPalette.secondary) >= 4.5)
        assertEquals(5.54, contrastRatio(NeonRainPalette.onSecondary, NeonRainPalette.secondary), 0.02)
        assertTrue(contrastRatio(NeonRainPalette.onPrimaryContainer, NeonRainPalette.primaryContainer) >= 4.5)
        assertEquals(11.31, contrastRatio(NeonRainPalette.onPrimaryContainer, NeonRainPalette.primaryContainer), 0.02)
        assertTrue(contrastRatio(NeonRainPalette.onSecondaryContainer, NeonRainPalette.secondaryContainer) >= 4.5)
        assertEquals(11.06, contrastRatio(NeonRainPalette.onSecondaryContainer, NeonRainPalette.secondaryContainer), 0.02)
        assertTrue(contrastRatio(NeonRainPalette.onTertiary, NeonRainPalette.tertiary) >= 4.5)
        assertEquals(14.58, contrastRatio(NeonRainPalette.onTertiary, NeonRainPalette.tertiary), 0.02)
        assertTrue(contrastRatio(NeonRainPalette.onTertiaryContainer, NeonRainPalette.tertiaryContainer) >= 4.5)
        assertEquals(10.81, contrastRatio(NeonRainPalette.onTertiaryContainer, NeonRainPalette.tertiaryContainer), 0.02)
        assertTrue(contrastRatio(NeonRainPalette.onSurface, NeonRainPalette.background) >= 4.5)
        assertEquals(17.89, contrastRatio(NeonRainPalette.onSurface, NeonRainPalette.background), 0.02)
        assertTrue(contrastRatio(NeonRainPalette.onSurfaceVariant, NeonRainPalette.surfaceVariant) >= 4.5)
        assertEquals(7.05, contrastRatio(NeonRainPalette.onSurfaceVariant, NeonRainPalette.surfaceVariant), 0.02)
        // The legend colours on the field: cyan 14.00:1, magenta
        // 5.45:1, yellow 16.31:1 — all read, so the legend stays
        // its own colour in both states.
        assertEquals(14.00, contrastRatio(NeonRainPalette.primary, NeonRainPalette.background), 0.02)
        assertEquals(5.45, contrastRatio(NeonRainPalette.secondary, NeonRainPalette.background), 0.02)
        assertEquals(16.31, contrastRatio(NeonRainPalette.tertiary, NeonRainPalette.background), 0.02)
        // Error text on the field reads (4.99:1); white on error
        // is modest at 3.95:1 — pinned exact, the same class as
        // Dashboard's red note (r224): white text on a red chip
        // is the fix if it reads poorly on device.
        assertTrue(contrastRatio(NeonRainPalette.error, NeonRainPalette.background) >= 4.5)
        assertEquals(4.99, contrastRatio(NeonRainPalette.error, NeonRainPalette.background), 0.02)
        assertEquals(3.95, contrastRatio(NeonRainPalette.onError, NeonRainPalette.error), 0.02)
    }

    @Test
    fun neonRainRedLivesOnlyInApprovedTokens() {
        // Red lives in exactly one spot, the error role, and
        // nowhere else — unlike Dashboard, whose tertiary is signal
        // red, Neon Rain's tertiary is acid yellow. A token counts
        // as red
        // when its red channel dominates (red > 150 while green
        // and blue stay under 100) AND it is far more red than
        // green (red - green > 100). The approved red is signal
        // red #FF003C; the tertiary is acid yellow, not red.
        val tokens = listOf(
            NeonRainPalette.background, NeonRainPalette.onSurface,
            NeonRainPalette.surfaceVariant, NeonRainPalette.onSurfaceVariant,
            NeonRainPalette.primary, NeonRainPalette.onPrimary,
            NeonRainPalette.primaryContainer, NeonRainPalette.onPrimaryContainer,
            NeonRainPalette.secondary, NeonRainPalette.onSecondary,
            NeonRainPalette.secondaryContainer, NeonRainPalette.onSecondaryContainer,
            NeonRainPalette.tertiary, NeonRainPalette.onTertiary,
            NeonRainPalette.tertiaryContainer, NeonRainPalette.onTertiaryContainer,
            NeonRainPalette.outline, NeonRainPalette.outlineVariant,
            NeonRainPalette.error, NeonRainPalette.onError,
        )
        val redTokens = tokens.filter { token ->
            val r = (token shr 16) and 0xFF
            val g = (token shr 8) and 0xFF
            val b = token and 0xFF
            r > 150 && g < 100 && b < 100 && (r - g) > 100
        }
        assertEquals(1, redTokens.size)
        for (token in redTokens) {
            assertEquals(0xFFFF003C.toInt(), token)
        }
        assertEquals(0xFFFF003C.toInt(), NeonRainPalette.error)
        // And no amber anywhere: neither old amber value survives,
        // and no token reads as amber (red high, green mid, blue low,
        // red clearly ahead of green but not far enough to be red).
        // The tertiary is acid yellow (#FCEE0A, green 238) — it
        // fails the amber rule on its green channel by design.
        assertEquals(0xFFFCEE0A.toInt(), NeonRainPalette.tertiary)
        for (token in tokens) {
            assertFalse("token #" + Integer.toHexString(token) + " is an old amber", token == 0xFFA3541F.toInt() || token == 0xFFCD8E48.toInt() || token == 0xFF6B3410.toInt())
            val r = (token shr 16) and 0xFF
            val g = (token shr 8) and 0xFF
            val b = token and 0xFF
            assertFalse("token #" + Integer.toHexString(token) + " reads as amber", r > 120 && g > 40 && g < 170 && b < 110 && (r - g) > 40 && (r - g) < 130)
        }
    }

    @Test
    fun shardStyleIsAppendedLastAndNamedShard() {
        assertEquals("shard", GsThemes.Shard.id)
        assertEquals("Shard", GsThemes.Shard.name)
        assertEquals(GsThemes.Shard, GsThemes.builtIn.last())
        assertEquals("shard", GsThemes.byId("shard").id)
        // Cluster is second from last, Aurora third, and nothing else moved.
        assertEquals(GsThemes.Cluster, GsThemes.builtIn[GsThemes.builtIn.size - 2])
        assertEquals(GsThemes.Aurora, GsThemes.builtIn[GsThemes.builtIn.size - 3])
    }

    @Test
    fun shardLookPins() {
        // The cut corner is the style: a 6dp chamfer, deeper than
        // Pitlane's 4dp. Rivets on (corner bolts), no weave, no
        // hairline, no rim of its own (the chrome rim is drawn,
        // not a ControlLook field). No light variant — dark only.
        val look = GsThemes.Shard.control
        assertEquals(1f, look.containerAlpha, 0.001f)
        assertEquals(12, look.corner.value.toInt())
        assertEquals(6, look.chamferCut.value.toInt())
        assertTrue(look.rivets)
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
    fun shardLegendGateKeepsCyanDefaultAndMagentaLatched() {
        // r226: the legend is neon cyan by default and hot magenta
        // when latched. Cyan reads on the field at 14.00:1 and
        // magenta at 5.45:1 — so the legend stays its own colour
        // in both states, and the latched rule (secondary when
        // latched, primary otherwise) never has to fall back.
        assertTrue(clusterLegendReads(Color(0xFF00F0FF), Color(0xFF0A0A12)))
        assertEquals(Color(0xFF00F0FF), clusterLegendColor(Color(0xFF0A0A12), Color(0xFF00F0FF), Color(0xFF000000)))
        assertTrue(clusterLegendReads(Color(0xFFFF2A6D), Color(0xFF0A0A12)))
        assertEquals(Color(0xFFFF2A6D), clusterLegendColor(Color(0xFF0A0A12), Color(0xFFFF2A6D), Color(0xFF000000)))
        // Acid yellow reads too (16.31:1) — it is never a legend,
        // but the gate has a live third case if one ever is.
        assertTrue(clusterLegendReads(Color(0xFFFCEE0A), Color(0xFF0A0A12)))
    }

    @Test
    fun neonRainSchemeMappingPinsItsTokens() {
        val scheme = neonRainScheme()
        assertEquals(Color(NeonRainPalette.primary), scheme.primary)
        assertEquals(Color(NeonRainPalette.secondary), scheme.secondary)
        assertEquals(Color(NeonRainPalette.tertiary), scheme.tertiary)
        assertEquals(Color(NeonRainPalette.background), scheme.background)
        assertEquals(Color(NeonRainPalette.background), scheme.surface)
        assertEquals(Color(NeonRainPalette.error), scheme.error)
        assertEquals(Color(NeonRainPalette.onSurface), scheme.onSurface)
        assertEquals(Color(0xFF06060B), scheme.surfaceContainerLowest)
        assertEquals(Color(0xFF12121C), scheme.surfaceContainer)
        assertEquals(Color(NeonRainPalette.onSurface), scheme.inverseSurface)
    }

    @Test
    fun shardHelpersArePinned() {
        // Twelve LED segments, eleven cuts at 1/12 intervals.
        assertEquals(12, ShardLedSegmentCount)
        assertEquals(listOf(0.083333336f, 0.16666667f, 0.25f, 0.33333334f, 0.41666667f, 0.5f, 0.5833333f, 0.6666667f, 0.75f, 0.8333333f, 0.9166667f), shardSegmentBoundaries())
        // The hex tag is the section name's hash as 4 hex digits.
        assertEquals("0xF112", shardHexTag("Voice"))
        assertEquals("0x8CE6", shardHexTag("AI assistant"))
        assertEquals(shardHexTag("Voice"), shardHexTag("Voice"))
        assertTrue(shardHexTag("Voice").startsWith("0x"))
        assertEquals(6, shardHexTag("Voice").length)
        // The barcode is 24 bars, widths 1..3, deterministic.
        val bars = shardBarcodeBars("Voice")
        assertEquals(24, bars.size)
        assertTrue(bars.all { it in 1..3 })
        assertEquals(bars, shardBarcodeBars("Voice"))
    }

    @Test
    fun neonRainPairIsAppendedLast() {
        val pair = themePairs.last()
        assertEquals("Neon Rain + Shard", pair.label)
        assertEquals("shard", pair.styleId)
        assertEquals(ColorScheme.NEON_RAIN, pair.scheme)
        assertEquals(pair, themePairFor("shard", ColorScheme.NEON_RAIN))
        // Dashboard + Cluster is second from last, and nothing else moved.
        assertEquals("Dashboard + Cluster", themePairs[themePairs.size - 2].label)
        assertEquals("Pitlane + Pitlane", themePairs.first().label)
    }
}
