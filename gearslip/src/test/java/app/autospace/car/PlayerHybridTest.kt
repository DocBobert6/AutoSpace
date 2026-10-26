package app.autospace.car

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.autospace.media.ArtColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r51 hybrid tests, all on pure logic (no composables, no Android framework calls):
 * waveform fill math, accent fallback rules, morph endpoints, seek mapping.
 *
 * The no-swipe rule is structural, not unit-testable on JVM: no swipe gesture handler exists
 * in the player composables — the only drag handler is the scrubber's, and [seekFractionForX]
 * below proves it can only ever produce a seek fraction, never a source change. Verified by
 * inspection of PlayerParts.kt at push time, not by a test that would only restate itself.
 */
class PlayerHybridTest {

    @Test
    fun filledBarsAtZeroFiftyHundred() {
        assertEquals(0, filledWaveformBars(0f))
        assertEquals(24, filledWaveformBars(0.5f))
        assertEquals(48, filledWaveformBars(1f))
    }

    @Test
    fun filledBarsClampAtBoundaries() {
        assertEquals(48, filledWaveformBars(1.5f))
        assertEquals(0, filledWaveformBars(-0.25f))
        assertEquals(1, filledWaveformBars(1f / 48f))
        assertEquals(47, filledWaveformBars(47f / 48f))
    }

    @Test
    fun barHeightsStayInRangeAndDeterministic() {
        for (i in 0 until WAVEFORM_BAR_COUNT) {
            val h = waveformBarHeightFraction(i)
            assertTrue(h >= 0.35f && h <= 1.0f)
            assertTrue(h == waveformBarHeightFraction(i))
        }
    }

    @Test
    fun seekFractionMapsXToClampedFraction() {
        assertEquals(0.0, seekFractionForX(0f, 100f).toDouble(), 0.0)
        assertEquals(0.5, seekFractionForX(50f, 100f).toDouble(), 0.0)
        assertEquals(1.0, seekFractionForX(100f, 100f).toDouble(), 0.0)
        assertEquals(1.0, seekFractionForX(150f, 100f).toDouble(), 0.0)
        assertEquals(0.0, seekFractionForX(-10f, 100f).toDouble(), 0.0)
        assertEquals(0.0, seekFractionForX(50f, 0f).toDouble(), 0.0)
    }

    @Test
    fun accentFallsBackForMissingAndNeutralArt() {
        assertNull(playerAccentColor(null, true))
        assertNull(playerAccentColor(ArtColor.Tint(210f, 0.2f), true))
        assertNull(playerAccentColor(ArtColor.Tint(210f, 0.29f), false))
    }

    @Test
    fun accentUsableForColorfulArtAndDiffersByTheme() {
        val dark = playerAccentColor(ArtColor.Tint(210f, 0.8f), true)
        val light = playerAccentColor(ArtColor.Tint(210f, 0.8f), false)
        assertNotNull(dark)
        assertNotNull(light)
        assertTrue(dark != light)
    }

    @Test
    fun accentContentIsReadable() {
        assertEquals(Color.White, accentContentColor(Color.Black))
        assertEquals(Color.Black, accentContentColor(Color.White))
    }

    @Test
    fun morphCornerFollowsStyle() {
        assertEquals(48.dp, playMorphCorner(true, 6.dp, 96.dp))
        assertEquals(6.dp, playMorphCorner(false, 6.dp, 96.dp))
        assertEquals(24.dp, playMorphCorner(false, 24.dp, 96.dp))
    }
}
