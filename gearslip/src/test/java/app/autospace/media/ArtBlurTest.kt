package app.autospace.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** r60 (A27): pure blur math, sample sizing, and cache-key selection for the card background. */
class ArtBlurTest {

    @Test
    fun uniformImageSurvivesBlurUnchanged() {
        val px = IntArray(8 * 8) { 0xFF804020.toInt() }
        assertTrue(ArtBlur.boxBlurPixels(px, 8, 8, ArtBlur.BLUR_RADIUS).contentEquals(px))
    }

    @Test
    fun impulseSpreadsEvenlyAcrossTheWindow() {
        val px = IntArray(9)
        px[4] = 0xFFFFFFFF.toInt()
        val out = ArtBlur.boxBlurPixels(px, 3, 3, 1)
        // Separable 3-tap blur: 255 -> 85 after the horizontal pass,
        // 85 -> 28 after the vertical pass, at every pixel.
        assertEquals(9, out.size)
        for (v in out) assertEquals(0x1C1C1C1C, v)
    }

    @Test
    fun radiusZeroReturnsAnEqualCopy() {
        val px = intArrayOf(1, 2, 3, 4)
        val out = ArtBlur.boxBlurPixels(px, 2, 2, 0)
        assertTrue(out.contentEquals(px))
        assertNotSame(px, out)
    }

    @Test
    fun singlePixelImageBlurIsANoop() {
        val px = intArrayOf(0xFF102030.toInt())
        assertTrue(ArtBlur.boxBlurPixels(px, 1, 1, 12).contentEquals(px))
    }

    @Test
    fun blurPreservesDimensions() {
        val px = IntArray(5 * 7) { it }
        assertEquals(35, ArtBlur.boxBlurPixels(px, 5, 7, 3).size)
    }

    @Test
    fun cardBlurRadiusIsSlightAndBelowTheHeavyDefault() {
        assertEquals(2, ArtBlur.CARD_BLUR_RADIUS)
        assertTrue(ArtBlur.CARD_BLUR_RADIUS < ArtBlur.BLUR_RADIUS)
    }

    @Test
    fun slightBlurSoftensAnEdgeWithoutFlatteningIt() {
        // A hard step, blurred at the card radius: the edge spreads
        // one step each way but the gradient is kept, unlike the heavy
        // radius which would wash it toward flat. Row of 5, height 1,
        // so the vertical pass is the identity.
        val px = intArrayOf(0, 0, 0, 255, 255)
        val out = ArtBlur.boxBlurPixels(px, 5, 1, ArtBlur.CARD_BLUR_RADIUS)
        assertTrue(out.contentEquals(intArrayOf(0, 51, 102, 153, 204)))
    }

    @Test
    fun sampleSizeKeepsAspectAndCapsTheLongestSide() {
        assertEquals(64 to 32, ArtBlur.sampleSize(400, 200))
        assertEquals(8 to 64, ArtBlur.sampleSize(100, 800))
        assertEquals(32 to 32, ArtBlur.sampleSize(32, 32))
        assertEquals(1 to 1, ArtBlur.sampleSize(0, 0))
    }

    @Test
    fun cacheKeyPrefersUriAndRejectsBlanks() {
        assertEquals("content://art/1", ArtBlur.cacheKey("content://art/1"))
        assertNull(ArtBlur.cacheKey(null))
        assertNull(ArtBlur.cacheKey(""))
    }
}
