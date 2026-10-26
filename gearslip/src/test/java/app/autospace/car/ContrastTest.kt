package app.autospace.car

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r33 Pitch/OLED audit: measured ratios for D4, R1, R3. Every ratio below is
 * COMPUTED by Contrast.kt from the literal token values, never copied from
 * the audit — if a token value changes, the test fails until the comment
 * and the BUILD_LOG are corrected with it.
 */
class ContrastTest {

    private fun assertRatio(expected: Double, actual: Double) {
        assertEquals(expected, actual, 0.02)
    }

    @Test
    fun d4_darkFixesSelectedChipContrast() {
        val primary = 0xFF0A84FF.toInt()
        // Was: white on primary (the value neutralFrostScheme shipped before r33).
        assertRatio(3.65, contrastRatio(0xFFFFFFFF.toInt(), primary))
        assertTrue(contrastRatio(0xFFFFFFFF.toInt(), primary) < 4.5)
        // Now: black on primary (r33 onPrimary = Black, all neutral branches).
        assertRatio(5.76, contrastRatio(0xFF000000.toInt(), primary))
        assertTrue(contrastRatio(0xFF000000.toInt(), primary) >= 4.5)
    }

    @Test
    fun d4_lightFixesSelectedChipContrast() {
        val primary = 0xFF007AFF.toInt()
        assertRatio(4.02, contrastRatio(0xFFFFFFFF.toInt(), primary))
        assertTrue(contrastRatio(0xFFFFFFFF.toInt(), primary) < 4.5)
        assertRatio(5.23, contrastRatio(0xFF000000.toInt(), primary))
        assertTrue(contrastRatio(0xFF000000.toInt(), primary) >= 4.5)
    }

    @Test
    fun r1_hairlineLiftMeasured() {
        val black = 0xFF000000.toInt()
        // Old: outline #38383A at 0.45 alpha over black (GsKit.hairline()).
        assertRatio(1.20, contrastRatio(blendOverBlack(0xFF38383A.toInt(), 0.45f), black))
        // New: Pitch outline #6E6E73 at the same 0.45 alpha (audit says 1.64;
        // measured 1.63 here — the test pins the MEASURED value).
        assertRatio(1.63, contrastRatio(blendOverBlack(0xFF6E6E73.toInt(), 0.45f), black))
        assertTrue(
            contrastRatio(blendOverBlack(0xFF6E6E73.toInt(), 0.45f), black) >
                contrastRatio(blendOverBlack(0xFF38383A.toInt(), 0.45f), black),
        )
    }

    @Test
    fun r3_stepIsAWhisperNotAGrey() {
        // Near-black step vs pure black: present, but far below a grey fill.
        assertRatio(1.06, contrastRatio(0xFF0A0A0C.toInt(), 0xFF000000.toInt()))
        assertTrue(contrastRatio(0xFF0A0A0C.toInt(), 0xFF000000.toInt()) < 1.10)
    }

    @Test
    fun pitchConstantsPinned() {
        assertEquals(Color(0xFF6E6E73), PitchOutlineColor)
        assertEquals(Color(0xFF0A0A0C), PitchSurfaceStepColor)
        // The constants are the values the scheme branches use; the ratios
        // above are computed from the same literals, so a drift in either
        // place fails here first.
        assertEquals(PitchOutlineColor.toArgb(), 0xFF6E6E73.toInt())
        assertEquals(PitchSurfaceStepColor.toArgb(), 0xFF0A0A0C.toInt())
    }
}
