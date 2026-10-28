package app.autospace.car

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.autospace.car.theme.GsThemes
import app.autospace.car.theme.morphContrastRatio
import app.autospace.car.theme.morphCornerTarget
import app.autospace.car.theme.morphLatchedWashPasses
import app.autospace.car.theme.morphWashBlend
import app.autospace.car.theme.roundedCornerDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** r114 Morph: the Chroma wash blend, the latched contrast guard, and the morph corner targets — all pure, all pinned here. */
class MorphTest {

    @Test
    fun washBlendPreservesAlphaAndInterpolatesTowardPrimary() {
        val container = Color(0xFF141218)
        val primary = Color(0xFFD0BCFF)
        // Wash 0 is the container itself (component-wise:
        // a lerp round-trip need not repack to the identical
        // packed value, so equality is asserted per channel);
        // wash 1 is the primary's RGB under the container's
        // alpha (never the primary's 1.0, which would un-pin
        // frosted fills).
        val zero = morphWashBlend(container, primary, 0f)
        assertEquals(container.red, zero.red, 0.001f)
        assertEquals(container.green, zero.green, 0.001f)
        assertEquals(container.blue, zero.blue, 0.001f)
        assertEquals(container.alpha, zero.alpha, 0.001f)
        val full = morphWashBlend(container, primary, 1f)
        assertEquals(primary.red, full.red, 0.001f)
        assertEquals(primary.green, full.green, 0.001f)
        assertEquals(primary.blue, full.blue, 0.001f)
        assertEquals(container.alpha, full.alpha, 0.001f)
        val translucent = Color(0x80141218)
        assertEquals(0.5f, morphWashBlend(translucent, primary, 0.30f).alpha, 0.01f)
    }

    @Test
    fun contrastRatioMatchesWcagAnchors() {
        assertEquals(21f, morphContrastRatio(Color.Black, Color.White), 0.01f)
        assertEquals(1f, morphContrastRatio(Color(0xFF6750A4), Color(0xFF6750A4)), 0.01f)
    }

    @Test
    fun latchedGuardFailsDarkTabsAtFullWashAndPassesAtTint() {
        // M3 baseline dynamic roles (purple seed), dark mode:
        // primaryContainer tab, onPrimaryContainer text. At the
        // full 0.30 wash the ratio is ~4.05 (below 4.5:1), so
        // the guard falls back to the 0.12 tint (~5.74).
        val darkPrimary = Color(0xFFD0BCFF)
        val darkContainer = Color(0xFF4F378B)
        val darkText = Color(0xFFEADDFF)
        assertFalse(morphLatchedWashPasses(darkText, darkContainer, darkPrimary, 0.30f))
        assertTrue(morphLatchedWashPasses(darkText, darkContainer, darkPrimary, 0.12f))
        assertEquals(4.05f, morphContrastRatio(darkText, morphWashBlend(darkContainer, darkPrimary, 0.30f)), 0.1f)
        // Dark Neutral surface, onSurfaceVariant text: 3.66 at
        // full wash (fails), 5.53 at the tint (passes).
        assertFalse(morphLatchedWashPasses(Color(0xFFCAC4D0), Color(0xFF36343B), darkPrimary, 0.30f))
        assertTrue(morphLatchedWashPasses(Color(0xFFCAC4D0), Color(0xFF36343B), darkPrimary, 0.12f))
    }

    @Test
    fun latchedGuardPassesLightModeAtFullWash() {
        // M3 baseline dynamic roles, light mode: the same
        // primaryContainer tab passes at the full 0.30 wash
        // (~4.84), and so does Neutral (~4.81) — light mode
        // never triggers the fallback on the baseline seed.
        // (Other seeds can still trigger it; the runtime
        // guard, not this table, decides per device.)
        val lightPrimary = Color(0xFF6750A4)
        assertTrue(morphLatchedWashPasses(Color(0xFF4F378B), Color(0xFFEADDFF), lightPrimary, 0.30f))
        assertTrue(morphLatchedWashPasses(Color(0xFF49454F), Color(0xFFE6E0E9), lightPrimary, 0.30f))
    }

    @Test
    fun latchedGuardPassesPrimaryFillsAtAnyWash() {
        // A primary fill washed toward primary is itself, so
        // onPrimary text passes by construction — nav items
        // and settings options never trigger the fallback.
        val primary = Color(0xFFD0BCFF)
        val onPrimary = Color(0xFF381E72)
        assertTrue(morphLatchedWashPasses(onPrimary, primary, primary, 0.30f))
    }

    @Test
    fun morphCornerTargetUsesFullRangeOnStyleCorner() {
        val look = GsThemes.Morph.control
        assertEquals(20.dp, morphCornerTarget(20.dp, look, pressed = false, latched = false))
        assertEquals(16.dp, morphCornerTarget(20.dp, look, pressed = true, latched = false))
        assertEquals(24.dp, morphCornerTarget(20.dp, look, pressed = false, latched = true))
        // Pressed wins over latched: pressing a latched
        // button tightens it, and releasing returns it to
        // the latched corner.
        assertEquals(16.dp, morphCornerTarget(20.dp, look, pressed = true, latched = true))
    }

    @Test
    fun morphCornerTargetUsesSmallerRangeOnOtherRoundedShapes() {
        val look = GsThemes.Morph.control
        // Keys' 12dp fallback: ±2dp, not the buttons' ±4dp.
        assertEquals(12.dp, morphCornerTarget(12.dp, look, pressed = false, latched = false))
        assertEquals(10.dp, morphCornerTarget(12.dp, look, pressed = true, latched = false))
        assertEquals(14.dp, morphCornerTarget(12.dp, look, pressed = false, latched = true))
        // A 1dp corner cannot tighten below zero.
        assertEquals(0.dp, morphCornerTarget(1.dp, look, pressed = true, latched = false))
    }

    @Test
    fun roundedCornerDpRejectsPillsCirclesAndMixedCorners() {
        assertEquals(12.dp, roundedCornerDp(RoundedCornerShape(12.dp)))
        assertEquals(20.dp, roundedCornerDp(RoundedCornerShape(20.dp)))
        // Percent corners (pills) never morph.
        assertNull(roundedCornerDp(RoundedCornerShape(50)))
        assertNull(roundedCornerDp(CircleShape))
        assertNull(
            roundedCornerDp(
                RoundedCornerShape(
                    topStart = 12.dp,
                    topEnd = 16.dp,
                    bottomEnd = 12.dp,
                    bottomStart = 12.dp,
                ),
            ),
        )
    }
}
