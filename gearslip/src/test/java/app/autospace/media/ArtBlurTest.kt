package app.autospace.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun cardWorkingSizeIsLargerThanTheDefaultWash() {
        assertEquals(256, ArtBlur.CARD_SAMPLE_MAX)
        assertTrue(ArtBlur.CARD_SAMPLE_MAX > ArtBlur.SAMPLE_MAX)
    }

    @Test
    fun sampleSizeHonorsACustomCap() {
        assertEquals(256 to 192, ArtBlur.sampleSize(1024, 768, ArtBlur.CARD_SAMPLE_MAX))
        assertEquals(100 to 50, ArtBlur.sampleSize(100, 50, ArtBlur.CARD_SAMPLE_MAX))
        // Default still caps at 64.
        assertEquals(64 to 48, ArtBlur.sampleSize(1024, 768))
    }

    @Test
    fun cacheKeyPrefersUriAndRejectsBlanks() {
        assertEquals("content://art/1", ArtBlur.cacheKey("content://art/1"))
        assertNull(ArtBlur.cacheKey(null))
        assertNull(ArtBlur.cacheKey(""))
    }

    @Test
    fun cardBlurCacheKeyBindsToBitmapIdentity() {
        // r138: the bug was a URI-keyed cache hit returning a previous
        // track's blurred image for a new bitmap when the URI arriving
        // with the new bitmap still named the previous track. The same
        // URI with a different bitmap instance must therefore key
        // differently, and the same URI with the same bitmap must key
        // identically (no needless recompute for the same art).
        val first = ArtBlur.cacheKeyFor("content://art/1", ArtBlur.CARD_BLUR_RADIUS, ArtBlur.CARD_SAMPLE_MAX, 111, 7)
        val same = ArtBlur.cacheKeyFor("content://art/1", ArtBlur.CARD_BLUR_RADIUS, ArtBlur.CARD_SAMPLE_MAX, 111, 7)
        val newBitmap = ArtBlur.cacheKeyFor("content://art/1", ArtBlur.CARD_BLUR_RADIUS, ArtBlur.CARD_SAMPLE_MAX, 222, 7)
        assertEquals(first, same)
        assertNotEquals(first, newBitmap)
    }

    @Test
    fun cardBlurCacheKeyBindsToBitmapGeneration() {
        // r138: a bitmap mutated in place is a new image even though
        // its instance is unchanged, so a risen generation id must
        // also change the key.
        val before = ArtBlur.cacheKeyFor("content://art/1", ArtBlur.CARD_BLUR_RADIUS, ArtBlur.CARD_SAMPLE_MAX, 111, 7)
        val mutated = ArtBlur.cacheKeyFor("content://art/1", ArtBlur.CARD_BLUR_RADIUS, ArtBlur.CARD_SAMPLE_MAX, 111, 8)
        assertNotEquals(before, mutated)
    }

    @Test
    fun cardBlurCacheKeyRejectsBlankUriLikeCacheKey() {
        // r138: URI-less art bypasses the cache, exactly as before.
        assertNull(ArtBlur.cacheKeyFor(null, ArtBlur.CARD_BLUR_RADIUS, ArtBlur.CARD_SAMPLE_MAX, 111, 7))
        assertNull(ArtBlur.cacheKeyFor("", ArtBlur.CARD_BLUR_RADIUS, ArtBlur.CARD_SAMPLE_MAX, 111, 7))
    }
}
