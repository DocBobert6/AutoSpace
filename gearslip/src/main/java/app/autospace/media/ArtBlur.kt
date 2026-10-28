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
 * box-blurred once per art change, and drawn behind the card's content.
 *
 * r129: the card's art is NO LONGER a heavy color wash. The old treatment
 * (radius 12 on a 64px working copy, drawn at the 0.88 float alpha over
 * the map) averaged each pixel over a 25px window and then let the map
 * show through — the result read as a scheme tint, not as album art,
 * which is exactly what the device report said. The card now uses a
 * very slight blur ([CARD_BLUR_RADIUS]) and draws the art opaque, with
 * no scheme fill underneath and no float alpha. The heavy radius
 * ([BLUR_RADIUS]) stays as the default for any other backdrop caller.
 * r133: the card's art STILL washed out on device (0.4.78), and the
 * cause was not the radius — the card passes [CARD_BLUR_RADIUS] = 2,
 * but the working copy is only 64px and the card draws it at
 * 240–380dp wide, a 6–12x bilinear upscale. The upscale, not the
 * 5-tap blur, is what turns the art into a wash. The card now uses
 * its own working size ([CARD_SAMPLE_MAX]) so the upscale is ~2–3x
 * and the art reads as art; the radius stays 2.
 * The pure pieces (sample size, box blur, cache key) carry no Android
 * imports in their signatures and are unit-tested; only [forArt]
 * touches Bitmaps.
 */
object ArtBlur {

    /** Longest side of the working copy: detail is pointless under heavy blur. */
    const val SAMPLE_MAX = 64

    /** Box-blur radius on the working copy; heavy, because this is a backdrop, not a photo. */
    const val BLUR_RADIUS = 12

    /**
     * r129: the floating media card's own radius — VERY slight, so the
     * art stays recognizable instead of washing out to a flat tint.
     * r133: the radius was never the wash — the 64px working copy was
     * (see [CARD_SAMPLE_MAX]). On the card's own working copy a 5-tap
     * window softens edges without moving shapes or colors. One word
     * changes it if the card wants sharper or softer art.
     */
    const val CARD_BLUR_RADIUS = 2

    /**
     * r133: the floating media card's own working size — longest side
     * of the working copy, replacing [SAMPLE_MAX] on the card path
     * only. 64px upscaled 6–12x to card size (240–380dp wide) is what
     * washed the art out on device; at 256px the upscale is ~2–3x and
     * the art stays recognizable. Still computed once per art change
     * and cached, so the cost stays trivial.
     */
    const val CARD_SAMPLE_MAX = 256

    /** Blurred backgrounds kept by URI, so re-entering composition never recomputes. */
    const val CACHE_SIZE = 8

    /**
     * Downscaled dimensions preserving aspect ratio, longest side capped
     * at [max] ([SAMPLE_MAX] by default); art already smaller stays
     * exactly as it is. Pure so it is unit-testable.
     */
    fun sampleSize(srcWidth: Int, srcHeight: Int, max: Int = SAMPLE_MAX): Pair<Int, Int> {
        if (srcWidth <= 0 || srcHeight <= 0) return 1 to 1
        val longest = maxOf(srcWidth, srcHeight)
        if (longest <= max) return srcWidth to srcHeight
        val scale = max.toFloat() / longest
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
     * r138: cache key for a blurred image, bound to the bitmap it was
     * blurred from as well as to its URI, radius, and working size.
     * [bitmapIdentity] is System.identityHashCode of the source bitmap
     * (a new track's bitmap is a new instance); [bitmapGeneration] is
     * the bitmap's generation id (it rises when a bitmap is mutated in
     * place). Either changing must change the key, so a URI that is
     * unchanged or lags one track behind can never return a previous
     * track's blurred image for a new bitmap. Null when the URI is
     * null or blank, exactly like [cacheKey] — URI-less art bypasses
     * the cache. Pure so it is unit-testable.
     */
    fun cacheKeyFor(artUri: String?, radius: Int, sampleMax: Int, bitmapIdentity: Int, bitmapGeneration: Int): String? =
        cacheKey(artUri)?.let { "$it#r$radius#s$sampleMax#i$bitmapIdentity#g$bitmapGeneration" }

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
     *
     * [radius] defaults to [BLUR_RADIUS]; the floating media card
     * passes [CARD_BLUR_RADIUS]. [sampleMax] defaults to [SAMPLE_MAX];
     * the card passes [CARD_SAMPLE_MAX]. The cache key carries both,
     * so the same art at two radii or two working sizes never collides
     * in the cache.
     */
    fun forArt(art: Bitmap?, artUri: Uri?, radius: Int = BLUR_RADIUS, sampleMax: Int = SAMPLE_MAX): ImageBitmap? {
        if (art == null) return null
        // r138: bind the cache key to the bitmap, not only to its URI.
        // A URI-keyed hit could return a previous track's blurred image
        // for a new bitmap when the URI arriving with the new bitmap
        // still named the previous track — the floating card's backdrop
        // lagging exactly one track behind its sharp cover on 0.4.83.
        val cacheKeyWithRadius = cacheKeyFor(artUri?.toString(), radius, sampleMax, System.identityHashCode(art), art.generationId)
        if (cacheKeyWithRadius != null) cache.get(cacheKeyWithRadius)?.let { return it }
        val readable = (if (art.config == Bitmap.Config.HARDWARE) {
            art.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            art
        }) ?: return null
        val (w, h) = sampleSize(readable.width, readable.height, sampleMax)
        val small = Bitmap.createScaledBitmap(readable, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        val blurred = boxBlurPixels(pixels, w, h, radius)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(blurred, 0, w, 0, 0, w, h)
        val image = out.asImageBitmap()
        if (small !== readable) small.recycle()
        if (readable !== art) readable.recycle()
        if (cacheKeyWithRadius != null) cache.put(cacheKeyWithRadius, image)
        return image
    }
}
