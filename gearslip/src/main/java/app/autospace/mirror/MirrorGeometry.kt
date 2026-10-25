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
     * frame-sized buffer: where the phone's display lands when it is scaled to
     * fit. v is measured from the buffer's top. NOTE: this is the FIT rect,
     * not a Fill crop — sampling this whole rect stretched to the full
     * frame distorts by fit/fill in the limiting dimension (that was the
     * Phase 10 Fill stretch bug: a portrait phone's narrow Fit rect blown
     * up to a landscape frame). Fill sampling must use [fillUv].
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
     * The Fill sampling rect (Phase 10 mirror fix): the CENTRAL sub-rect
     * of the Fit content rect whose aspect equals the frame's aspect,
     * as texture fractions (u0, v0, u1, v1) with v from the buffer's top.
     * The dimension that already matches is kept whole; the dimension
     * that is too long is cropped centrally by the fit/fill fraction.
     * Sampling THIS rect stretched to the full frame is a true crop --
     * same scale on both axes, no distortion -- and it agrees exactly
     * with [mapFill]: the frame's corners map to this rect's corners
     * in phone coordinates (see MirrorGeometryTest).
     *
     * Worked example, portrait phone 1080x2424 into an 800x480 car
     * frame: Fit scale = 480/2424 = 0.1980, content = 213.9x480 at
     * offsetX 293.1 (letterbox bars left/right). Content aspect 0.446
     * is narrower than frame aspect 1.667, so width is kept whole and
     * height is cropped centrally: crop = 213.9 x (213.9 x 480/800) =
     * 213.9x128.3, top = (480-128.3)/2 = 175.8. UV = (0.3664, 0.3664,
     * 0.6336, 0.6336). The old code sampled the whole Fit rect
     * (0.3664, 0, 0.6336, 1.0) stretched to 800x480 -- a 3.7x horizontal
     * stretch, which is what Robert saw.
     */
    fun fillUv(phoneW: Float, phoneH: Float, frameW: Float, frameH: Float): FloatArray {
        if (frameW <= 0f || frameH <= 0f || phoneW <= 0f || phoneH <= 0f) {
            return floatArrayOf(0f, 0f, 1f, 1f)
        }
        val r = fit(phoneW, phoneH, frameW, frameH)
        val contentW = phoneW * r.scale
        val contentH = phoneH * r.scale
        val frameAspect = frameW / frameH
        val contentAspect = contentW / contentH
        val (cropW, cropH) = if (contentAspect > frameAspect) {
            // Content wider than the frame: keep full height, crop width.
            (contentH * frameAspect) to contentH
        } else {
            // Content narrower (or equal): keep full width, crop height.
            contentW to (contentW / frameAspect)
        }
        val left = r.offsetX + (contentW - cropW) / 2f
        val top = r.offsetY + (contentH - cropH) / 2f
        return floatArrayOf(
            left / frameW,
            top / frameH,
            (left + cropW) / frameW,
            (top + cropH) / frameH,
        )
    }

    /**
     * The phone content's rect in frame pixels under the full display
     * transform: the base Fit/Fill rect, scaled by [zoom] about the
     * frame centre, then shifted by [panX]/[panY]. Zoom/pan are LOCAL
     * display transforms only: they change what the car shows, never
     * what the phone does — a car-side pinch is detected locally and
     * never relayed as a pinch (mirror relay is single-finger only).
     * Pan is clamped so the zoomed content always covers the frame
     * where it can: where the scaled content is narrower than the
     * frame on an axis, that axis cannot pan at all (there is nothing
     * to pan into — the letterbox bar is already showing).
     */
    fun displayRect(
        phoneW: Float, phoneH: Float, frameW: Float, frameH: Float,
        fill: Boolean, zoom: Float, panX: Float, panY: Float,
    ): FrameRect {
        val base = if (fill) fill(phoneW, phoneH, frameW, frameH) else fit(phoneW, phoneH, frameW, frameH)
        val scaledW = phoneW * base.scale * zoom
        val scaledH = phoneH * base.scale * zoom
        val (cx, cy) = clampPan(panX, panY, scaledW, scaledH, frameW, frameH)
        val left = (frameW - scaledW) / 2f + cx
        val top = (frameH - scaledH) / 2f + cy
        return FrameRect(left, top, left + scaledW, top + scaledH)
    }

    /** Clamps a pan so scaled content never leaves a gap over the frame on that axis. */
    fun clampPan(
        panX: Float, panY: Float, scaledW: Float, scaledH: Float, frameW: Float, frameH: Float,
    ): Pair<Float, Float> {
        val maxX = maxOf(0f, (scaledW - frameW) / 2f)
        val maxY = maxOf(0f, (scaledH - frameH) / 2f)
        return panX.coerceIn(-maxX, maxX) to panY.coerceIn(-maxY, maxY)
    }

    /**
     * Touch mapping under the full display transform: the inverse of
     * [displayRect]. Null for a touch that lands on no phone content
     * (letterbox padding, or outside the zoomed rect). At zoom 1 and
     * pan 0 this agrees exactly with [mapFit]/[mapFill].
     */
    fun mapDisplay(
        x: Float, y: Float, phoneW: Float, phoneH: Float, frameW: Float, frameH: Float,
        fill: Boolean, zoom: Float, panX: Float, panY: Float,
    ): PhonePoint? {
        if (frameW <= 0f || frameH <= 0f || phoneW <= 0f || phoneH <= 0f) return null
        val base = if (fill) fill(phoneW, phoneH, frameW, frameH) else fit(phoneW, phoneH, frameW, frameH)
        val r = displayRect(phoneW, phoneH, frameW, frameH, fill, zoom, panX, panY)
        val scale = base.scale * zoom
        if (scale <= 0f) return null
        val px = (x - r.left) / scale
        val py = (y - r.top) / scale
        if (px < 0f || py < 0f || px > phoneW || py > phoneH) return null
        return PhonePoint(px, py)
    }

    /**
     * The chrome pill's region, in frame pixels: a small floating pill
     * centred at the top, like the browser's "Exit fullscreen" pill.
     * A tap on the mirror relays to the phone, so tap-to-toggle chrome
     * (as the video player does) would flash chrome on every phone
     * tap — the pill is the only chrome entry point instead. It never
     * overlaps the Fill exit button (top-right): the pill is centred
     * and capped at 360px wide, the exit button lives in the corner.
     */
    fun pillRegion(frameW: Float, frameH: Float): FrameRect {
        val w = minOf(frameW * 0.28f, 360f).coerceAtLeast(120f)
        val h = 64f
        val top = 16f
        return FrameRect((frameW - w) / 2f, top, (frameW + w) / 2f, top + h)
    }

    /**
     * The chrome button row, in frame pixels, bottom-centre: Fit/Fill
     * toggle, zoom out, zoom in, reset (in that order). No play/pause,
     * transport, scrubber, speed, or sleep — the mirror has no media
     * of its own to control. Drawn by the GL renderer (the mirror owns
     * the encoder surface; no Compose layer exists over it), so these
     * are hit-tested rects, not Compose buttons; touches on them never
     * relay to the phone.
     */
    fun chromeButtons(frameW: Float, frameH: Float): List<FrameRect> {
        val size = (minOf(frameW, frameH) * 0.14f).coerceIn(72f, 144f)
        val gap = 16f
        val total = 4 * size + 3 * gap
        val top = frameH - size - 24f
        val start = (frameW - total) / 2f
        return (0 until 4).map { i ->
            val left = start + i * (size + gap)
            FrameRect(left, top, left + size, top + size)
        }
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
