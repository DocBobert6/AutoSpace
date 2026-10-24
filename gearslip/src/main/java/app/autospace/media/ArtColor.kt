package app.autospace.media

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.scale

/**
 * The colour a piece of album art reads as, for tinting what sits around it. Not the plain
 * average, which turns most covers brown-grey: the art is shrunk to a few hundred pixels,
 * sorted into hue buckets weighted by how saturated and bright each pixel is, and the heaviest
 * bucket wins. Art with no real colour in it (black and white, near-grey) gives null.
 */
object ArtColor {

    private const val SAMPLE = 24
    private const val BUCKETS = 24

    /** Hue in degrees and saturation, 0..1; null when the art has no colour worth picking. */
    class Tint(val hue: Float, val saturation: Float)

    fun of(art: Bitmap): Tint? {
        // A hardware bitmap can't be read pixel by pixel; copy it into memory first.
        val readable = if (art.config == Bitmap.Config.HARDWARE) art.copy(Bitmap.Config.ARGB_8888, false) else art
        val small = runCatching { readable?.scale(SAMPLE, SAMPLE) }.getOrNull() ?: return null
        val weight = FloatArray(BUCKETS)
        val hueSum = FloatArray(BUCKETS)
        val satSum = FloatArray(BUCKETS)
        val hsv = FloatArray(3)
        for (y in 0 until small.height) for (x in 0 until small.width) {
            Color.colorToHSV(small.getPixel(x, y), hsv)
            val (h, s, v) = Triple(hsv[0], hsv[1], hsv[2])
            // Near-grey and near-black pixels (letterbox bars among them) say nothing about the art's colour.
            if (s < 0.1f || v < 0.08f) continue
            val w = s * v
            val b = (h / 360f * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
            weight[b] += w
            hueSum[b] += h * w
            satSum[b] += s * w
        }
        if (small !== readable) small.recycle()
        if (readable !== art) readable?.recycle()
        val best = weight.indices.maxBy { weight[it] }
        // A sliver of colour in an otherwise grey cover shouldn't tint the whole panel.
        if (weight[best] < SAMPLE * SAMPLE * 0.005f) return null
        return Tint(hueSum[best] / weight[best], satSum[best] / weight[best])
    }
}
