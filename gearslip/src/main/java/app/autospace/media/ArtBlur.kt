package app.autospace.media

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.roundToInt

/**
 * r60 (A27): blurred album-art background for the floating media card.
 *
 * The art is our own image, so blurring it is nothing like live map blur
 * (impossible over a SurfaceView): it is shrunk to a small working copy,
 * box-blurred once per art change, and drawn behind the card's existing
 * frosted fill — never over it. The 0.88 frosted fill stays the only
 * translucent layer, which is why legibility is exactly today's: the art
 * reads as a color wash through the frost, and no second translucent
 * stack is ever built on top. The pure pieces (sample size, box blur,
 * cache key) carry no Android imports in their signatures and are
 * unit-tested; only [forArt] touches Bitmaps.
 */
object ArtBlur {

    /** Longest side of the working copy: detail is pointless under heavy blur. */
    const val SAMPLE_MAX = 64

    /** Box-blur radius on the working copy; heavy, because this is a backdrop, not a photo. */
    const val BLUR_RADIUS = 12

    /** Blurred backgrounds kept by URI, so re-entering composition never recomputes. */
    const val CACHE_SIZE = 8

    /**
     * Downscaled dimensions preserving aspect ratio, longest side capped
     * at [SAMPLE_MAX]; art already smaller stays exactly as it is. Pure
     * so it is unit-testable.
     */
    fun sampleSize(srcWidth: Int, srcHeight: Int): Pair<Int, Int> {
        if (srcWidth <= 0 || srcHeight <= 0) return 1 to 1
        val longest = maxOf(srcWidth, srcHeight)
        if (longest <= SAMPLE_MAX) return srcWidth to srcHeight
        val scale = SAMPLE_MAX.toFloat() / longest
        return maxOf(1, (srcWidth * scale).roundToInt()) to maxOf(1, (srcHeight * scale).roundToInt())
    }

    /**
     * Cache key for a piece of art: its URI string when present, else null
     * — URI-less art bypasses the cache and is keyed by the bitmap
     * instance in the caller's remember instead. Pure so it is
     * unit-testable.
     */
    fun cacheKey(artUri: String?): String? = artUri?.takeIf { it.isNotEmpty() }

    /**
     * Separable box blur over ARGB pixels, edges clamped (the border
     * repeats rather than darkening, so a bright cover edge stays bright).
     * Two sliding-window passes, horizontal then vertical, so cost is
     * linear in pixels, not in radius. Radius <= 0 returns an equal copy.
     * Pure so it is unit-testable.
     */
    fun boxBlurPixels(src: IntArray, width: Int, height: Int, radius: Int): IntArray {
        require(width > 0 && height > 0 && src.size == width * height) {
            "pixel buffer must match dimensions"
        }
        if (radius <= 0) return src.copyOf()
        val tmp = IntArray(src.size)
        blurHorizontal(src, tmp, width, height, radius)
        val out = IntArray(src.size)
        blurVertical(tmp, out, width, height, radius)
        return out
    }

    private fun blurHorizontal(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
        val div = radius * 2 + 1
        for (y in 0 until height) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val px = src[y * width + i.coerceIn(0, width - 1)]
                a += px ushr 24
                r += (px shr 16) and 0xFF
                g += (px shr 8) and 0xFF
                b += px and 0xFF
            }
            for (x in 0 until width) {
                dst[y * width + x] = (a / div shl 24) or (r / div shl 16) or (g / div shl 8) or (b / div)
                val remove = src[y * width + (x - radius).coerceIn(0, width - 1)]
                val add = src[y * width + (x + radius + 1).coerceIn(0, width - 1)]
                a += (add ushr 24) - (remove ushr 24)
                r += ((add shr 16) and 0xFF) - ((remove shr 16) and 0xFF)
                g += ((add shr 8) and 0xFF) - ((remove shr 8) and 0xFF)
                b += (add and 0xFF) - (remove and 0xFF)
            }
        }
    }

    private fun blurVertical(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
        val div = radius * 2 + 1
        for (x in 0 until width) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val px = src[i.coerceIn(0, height - 1) * width + x]
                a += px ushr 24
                r += (px shr 16) and 0xFF
                g += (px shr 8) and 0xFF
                b += px and 0xFF
            }
            for (y in 0 until height) {
                dst[y * width + x] = (a / div shl 24) or (r / div shl 16) or (g / div shl 8) or (b / div)
                val remove = src[(y - radius).coerceIn(0, height - 1) * width + x]
                val add = src[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                a += (add ushr 24) - (remove ushr 24)
                r += ((add shr 16) and 0xFF) - ((remove shr 16) and 0xFF)
                g += ((add shr 8) and 0xFF) - ((remove shr 8) and 0xFF)
                b += (add and 0xFF) - (remove and 0xFF)
            }
        }
    }

    private val cache = LruCache<String, ImageBitmap>(CACHE_SIZE)

    /**
     * Blurred background for [art], or null when there is no art — the
     * caller then keeps exactly today's background (no blank, no
     * placeholder). The input is never recycled: it may belong to
     * MediaArt's cache or the media session. Only copies made here are
     * recycled. A hardware bitmap is copied into memory first, same as
     * ArtColor does, since its pixels cannot be read directly.
     */
    fun forArt(art: Bitmap?, artUri: Uri?): ImageBitmap? {
        if (art == null) return null
        val key = artUri?.toString()?.let { cacheKey(it) }
        if (key != null) cache.get(key)?.let { return it }
        val readable = (if (art.config == Bitmap.Config.HARDWARE) {
            art.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            art
        }) ?: return null
        val (w, h) = sampleSize(readable.width, readable.height)
        val small = Bitmap.createScaledBitmap(readable, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        val blurred = boxBlurPixels(pixels, w, h, BLUR_RADIUS)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(blurred, 0, w, 0, 0, w, h)
        val image = out.asImageBitmap()
        if (small !== readable) small.recycle()
        if (readable !== art) readable.recycle()
        if (key != null) cache.put(key, image)
        return image
    }
}
