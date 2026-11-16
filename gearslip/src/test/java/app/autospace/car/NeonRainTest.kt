package app.autospace.car

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.autospace.car.theme.ChoiceRowDisabledContentAlpha
import app.autospace.car.theme.GsThemes
import app.autospace.car.theme.HoldMarkerStyle
import app.autospace.car.theme.ShardAngularThumbCut
import app.autospace.car.theme.ShardAngularThumbSize
import app.autospace.car.theme.ShardAngularTrackHeight
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
import kotlin.math.roundToInt

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
        // variant: acid yellow primary, neon cyan secondary and
        // active colour, signal-red tertiary and error. No magenta
        // token exists in the file, and no amber token either.
        assertEquals(0xFF0A0A12.toInt(), NeonRainPalette.background)
        assertEquals(0xFFE8F6FF.toInt(), NeonRainPalette.onSurface)
        assertEquals(0xFF14141F.toInt(), NeonRainPalette.surfaceVariant)
        assertEquals(0xFF8FA3B8.toInt(), NeonRainPalette.onSurfaceVariant)
        assertEquals(0xFFFCEE0A.toInt(), NeonRainPalette.primary)
        assertEquals(0xFF1A1A00.toInt(), NeonRainPalette.onPrimary)
        assertEquals(0xFF3D3A00.toInt(), NeonRainPalette.primaryContainer)
        assertEquals(0xFFFFF9B8.toInt(), NeonRainPalette.onPrimaryContainer)
        assertEquals(0xFF00F0FF.toInt(), NeonRainPalette.secondary)
        assertEquals(0xFF001318.toInt(), NeonRainPalette.onSecondary)
        assertEquals(0xFF00333D.toInt(), NeonRainPalette.secondaryContainer)
        assertEquals(0xFFB8F4FF.toInt(), NeonRainPalette.onSecondaryContainer)
        assertEquals(0xFFFF003C.toInt(), NeonRainPalette.tertiary)
        assertEquals(0xFFFFFFFF.toInt(), NeonRainPalette.onTertiary)
        assertEquals(0xFF40060F.toInt(), NeonRainPalette.tertiaryContainer)
        assertEquals(0xFFFFC2CC.toInt(), NeonRainPalette.onTertiaryContainer)
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
        assertEquals(14.58, contrastRatio(NeonRainPalette.onPrimary, NeonRainPalette.primary), 0.02)
        assertTrue(contrastRatio(NeonRainPalette.onSecondary, NeonRainPalette.secondary) >= 4.5)
        assertEquals(13.48, contrastRatio(NeonRainPalette.onSecondary, NeonRainPalette.secondary), 0.02)
        assertTrue(contrastRatio(NeonRainPalette.onPrimaryContainer, NeonRainPalette.primaryContainer) >= 4.5)
        assertEquals(10.81, contrastRatio(NeonRainPalette.onPrimaryContainer, NeonRainPalette.primaryContainer), 0.02)
        assertTrue(contrastRatio(NeonRainPalette.onSecondaryContainer, NeonRainPalette.secondaryContainer) >= 4.5)
        assertEquals(11.31, contrastRatio(NeonRainPalette.onSecondaryContainer, NeonRainPalette.secondaryContainer), 0.02)
        // White on tertiary is 3.95:1 — below the bar, the same
        // modest class as white on error (pinned exact, no bar).
        assertEquals(3.95, contrastRatio(NeonRainPalette.onTertiary, NeonRainPalette.tertiary), 0.02)
        assertTrue(contrastRatio(NeonRainPalette.onTertiaryContainer, NeonRainPalette.tertiaryContainer) >= 4.5)
        assertEquals(11.06, contrastRatio(NeonRainPalette.onTertiaryContainer, NeonRainPalette.tertiaryContainer), 0.02)
        assertTrue(contrastRatio(NeonRainPalette.onSurface, NeonRainPalette.background) >= 4.5)
        assertEquals(17.89, contrastRatio(NeonRainPalette.onSurface, NeonRainPalette.background), 0.02)
        assertTrue(contrastRatio(NeonRainPalette.onSurfaceVariant, NeonRainPalette.surfaceVariant) >= 4.5)
        assertEquals(7.05, contrastRatio(NeonRainPalette.onSurfaceVariant, NeonRainPalette.surfaceVariant), 0.02)
        // The legend colours on the field: yellow 16.31:1, cyan
        // 14.00:1, red 4.99:1 — all read, so the legend stays its
        // own colour in both states.
        assertEquals(16.31, contrastRatio(NeonRainPalette.primary, NeonRainPalette.background), 0.02)
        assertEquals(14.00, contrastRatio(NeonRainPalette.secondary, NeonRainPalette.background), 0.02)
        assertEquals(4.99, contrastRatio(NeonRainPalette.tertiary, NeonRainPalette.background), 0.02)
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
        // Red lives in exactly two spots, the tertiary role and
        // the error role — both signal red #FF003C, like
        // Dashboard's two (tertiary + error). A token counts as red
        // when its red channel dominates (red > 150 while green
        // and blue stay under 100) AND it is far more red than
        // green (red - green > 100). The approved red is signal
        // red #FF003C.
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
        assertEquals(2, redTokens.size)
        for (token in redTokens) {
            assertEquals(0xFFFF003C.toInt(), token)
        }
        assertEquals(0xFFFF003C.toInt(), NeonRainPalette.error)
        assertEquals(0xFFFF003C.toInt(), NeonRainPalette.tertiary)
        // And no amber anywhere: neither old amber value survives,
        // and no token reads as amber (red high, green mid, blue low,
        // red clearly ahead of green but not far enough to be red).
        // The primary is acid yellow (#FCEE0A, green 238) — it
        // fails the amber rule on its green channel by design.
        for (token in tokens) {
            assertFalse("token #" + Integer.toHexString(token) + " is an old amber", token == 0xFFA3541F.toInt() || token == 0xFFCD8E48.toInt() || token == 0xFF6B3410.toInt())
            val r = (token shr 16) and 0xFF
            val g = (token shr 8) and 0xFF
            val b = token and 0xFF
            assertFalse("token #" + Integer.toHexString(token) + " reads as amber", r > 120 && g > 40 && g < 170 && b < 110 && (r - g) > 40 && (r - g) < 130)
        }
    }

    @Test
    fun neonRainHasNoMagentaAnywhere() {
        // r227: hot magenta (#FF2A6D) is gone entirely — a third
        // neon competing with the yellow and cyan. No token holds
        // it, and no token reads as magenta (red high, green low,
        // blue mid, red clearly ahead of blue).
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
        for (token in tokens) {
            assertFalse("token #" + Integer.toHexString(token) + " is magenta", token == 0xFFFF2A6D.toInt())
            val r = (token shr 16) and 0xFF
            val g = (token shr 8) and 0xFF
            val b = token and 0xFF
            assertFalse("token #" + Integer.toHexString(token) + " reads as magenta", r > 200 && g < 110 && b in 90..150 && (r - b) > 100)
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
    fun shardLegendGateKeepsYellowDefaultAndCyanLatched() {
        // r227: the legend is acid yellow by default and neon cyan
        // when latched. Yellow reads on the field at 16.31:1 and
        // cyan at 14.00:1 — so on a dark face the legend stays its
        // own colour in both states. On a BRIGHT face the gate
        // falls back to the default content colour instead:
        // yellow on yellow is 1.00:1, cyan on yellow is 1.17:1,
        // and the old magenta-on-yellow bug was 2.99:1 — all three
        // fall back to near-black (14.58:1 on yellow). On a dark
        // chip face (#181824) yellow reads 14.54:1 and cyan
        // 12.48:1, so both stay.
        assertTrue(clusterLegendReads(Color(0xFFFCEE0A), Color(0xFF0A0A12)))
        assertEquals(Color(0xFFFCEE0A), clusterLegendColor(Color(0xFF0A0A12), Color(0xFFFCEE0A), Color(0xFF000000)))
        assertTrue(clusterLegendReads(Color(0xFF00F0FF), Color(0xFF0A0A12)))
        assertEquals(Color(0xFF00F0FF), clusterLegendColor(Color(0xFF0A0A12), Color(0xFF00F0FF), Color(0xFF000000)))
        assertEquals(1.00, contrastRatio(0xFFFCEE0A.toInt(), 0xFFFCEE0A.toInt()), 0.02)
        assertEquals(Color(0xFF1A1A00), clusterLegendColor(Color(0xFFFCEE0A), Color(0xFFFCEE0A), Color(0xFF1A1A00)))
        assertEquals(1.17, contrastRatio(0xFF00F0FF.toInt(), 0xFFFCEE0A.toInt()), 0.02)
        assertEquals(Color(0xFF1A1A00), clusterLegendColor(Color(0xFFFCEE0A), Color(0xFF00F0FF), Color(0xFF1A1A00)))
        assertEquals(2.99, contrastRatio(0xFFFF2A6D.toInt(), 0xFFFCEE0A.toInt()), 0.02)
        assertEquals(Color(0xFF1A1A00), clusterLegendColor(Color(0xFFFCEE0A), Color(0xFFFF2A6D), Color(0xFF1A1A00)))
        assertEquals(14.54, contrastRatio(0xFFFCEE0A.toInt(), 0xFF181824.toInt()), 0.02)
        assertEquals(Color(0xFFFCEE0A), clusterLegendColor(Color(0xFF181824), Color(0xFFFCEE0A), Color(0xFF1A1A00)))
        assertEquals(12.48, contrastRatio(0xFF00F0FF.toInt(), 0xFF181824.toInt()), 0.02)
        assertEquals(Color(0xFF00F0FF), clusterLegendColor(Color(0xFF181824), Color(0xFF00F0FF), Color(0xFF1A1A00)))
    }

    @Test
    fun shardAngularSliderPins() {
        // r227: the settings sliders are thin angular tracks —
        // 8dp, sharp — with a 28dp chamfered-square thumb cut at
        // 4dp, and tick marks at the shared 10% graduations. The
        // LED-segmented bar is untouched: still 12 segments for
        // seek and buffering (pinned in shardHelpersArePinned).
        assertEquals(8, ShardAngularTrackHeight.value.toInt())
        assertEquals(28, ShardAngularThumbSize.value.toInt())
        assertEquals(4, ShardAngularThumbCut.value.toInt())
    }

    @Test
    fun choiceRowDisabledContentIsLegibleWhenDimmed() {
        // r227 (Appearance audit): a disabled choice chip at the
        // usual 0.38 alpha reads 3.34:1 on the chip face (grey on
        // grey — the Start/Center bug). At 0.60 it reads 6.41:1 on
        // surfaceContainerHigh and 6.08:1 on
        // surfaceContainerHighest, legible while still dimmed.
        assertEquals(0.60f, ChoiceRowDisabledContentAlpha, 0.001f)
        val onSurface = 0xFFE8F6FF.toInt()
        assertEquals(6.41, contrastRatio(blendOver(onSurface, 0xFF181824.toInt(), 0.60f), 0xFF181824.toInt()), 0.02)
        assertEquals(6.08, contrastRatio(blendOver(onSurface, 0xFF20202E.toInt(), 0.60f), 0xFF20202E.toInt()), 0.02)
        assertTrue(contrastRatio(blendOver(onSurface, 0xFF181824.toInt(), 0.38f), 0xFF181824.toInt()) < 4.5)
    }

    private fun blendOver(foregroundArgb: Int, backgroundArgb: Int, alpha: Float): Int {
        val r = (alpha * ((foregroundArgb shr 16) and 0xFF) + (1 - alpha) * ((backgroundArgb shr 16) and 0xFF)).roundToInt()
        val g = (alpha * ((foregroundArgb shr 8) and 0xFF) + (1 - alpha) * ((backgroundArgb shr 8) and 0xFF)).roundToInt()
        val b = (alpha * (foregroundArgb and 0xFF) + (1 - alpha) * (backgroundArgb and 0xFF)).roundToInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
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
