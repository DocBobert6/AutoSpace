package app.autospace.mirror

/**
 * Mirror geometry, kept pure (no Android types) so unit tests can cover it.
 *
 * Fit scales the phone display to fit the car frame with aspect kept (letterbox
 * bars); Fill scales it to cover the frame (edges cropped). The platform's
 * AUTO_MIRROR scaling produces Fit video — Fill video needs the GL renderer
 * ([MirrorFillRenderer]), which samples only the content rect of the Fit image.
 * Touch mapping must use the same geometry as the video, or every tap lands wrong.
 */
data class PhonePoint(val x: Float, val y: Float)
data class FrameRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom
}

data class ScaleRect(val scale: Float, val offsetX: Float, val offsetY: Float)

object MirrorGeometry {
    /** Fit: scale = min, centred, with padding. */
    fun fit(phoneW: Float, phoneH: Float, frameW: Float, frameH: Float): ScaleRect {
        val scale = minOf(frameW / phoneW, frameH / phoneH)
        return ScaleRect(scale, (frameW - phoneW * scale) / 2f, (frameH - phoneH * scale) / 2f)
    }

    /** Fill: scale = max, centred, edges cropped (offsets go negative). */
    fun fill(phoneW: Float, phoneH: Float, frameW: Float, frameH: Float): ScaleRect {
        val scale = maxOf(frameW / phoneW, frameH / phoneH)
        return ScaleRect(scale, (frameW - phoneW * scale) / 2f, (frameH - phoneH * scale) / 2f)
    }

    /** Fit touch mapping: null for a touch on the letterbox padding. */
    fun mapFit(x: Float, y: Float, phoneW: Float, phoneH: Float, frameW: Float, frameH: Float): PhonePoint? {
        if (frameW <= 0f || frameH <= 0f || phoneW <= 0f || phoneH <= 0f) return null
        val r = fit(phoneW, phoneH, frameW, frameH)
        val px = (x - r.offsetX) / r.scale
        val py = (y - r.offsetY) / r.scale
        if (px < 0f || py < 0f || px > phoneW || py > phoneH) return null
        return PhonePoint(px, py)
    }

    /**
     * Fill touch mapping: every frame point maps inside the phone (the frame shows
     * a cropped sub-rect of it), so this only returns null for degenerate sizes.
     */
    fun mapFill(x: Float, y: Float, phoneW: Float, phoneH: Float, frameW: Float, frameH: Float): PhonePoint? {
        if (frameW <= 0f || frameH <= 0f || phoneW <= 0f || phoneH <= 0f) return null
        val r = fill(phoneW, phoneH, frameW, frameH)
        val px = ((x - r.offsetX) / r.scale).coerceIn(0f, phoneW)
        val py = ((y - r.offsetY) / r.scale).coerceIn(0f, phoneH)
        return PhonePoint(px, py)
    }

    /**
     * The content rect of a Fit image, as texture fractions (u0, v0, u1, v1) of the
     * frame-sized buffer: the GL Fill renderer samples exactly this rect, stretched
     * to the full frame, which is the crop. v is measured from the buffer's top.
     */
    fun contentUv(phoneW: Float, phoneH: Float, frameW: Float, frameH: Float): FloatArray {
        val r = fit(phoneW, phoneH, frameW, frameH)
        return floatArrayOf(
            r.offsetX / frameW,
            r.offsetY / frameH,
            (r.offsetX + phoneW * r.scale) / frameW,
            (r.offsetY + phoneH * r.scale) / frameH,
        )
    }

    /**
     * The Fill exit button's region, in frame pixels: a square in the top-right
     * corner, drawn by the GL renderer and hit-tested by the touch relay with this
     * same function, so the button and its tap target can never drift apart.
     * Fill has no letterbox bars to tap for an exit, so this is the way out.
     */
    fun exitRegion(frameW: Float, frameH: Float): FrameRect {
        val size = (minOf(frameW, frameH) * 0.12f).coerceIn(72f, 160f)
        return FrameRect(frameW - size, 0f, frameW, size)
    }
}
