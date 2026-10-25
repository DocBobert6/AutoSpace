package app.autospace.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MirrorFillUvTest {

    private fun aspect(uv: FloatArray): Float {
        val w = uv[2] - uv[0]
        val h = uv[3] - uv[1]
        return w / h
    }

    @Test
    fun fillUvPortraitPhoneIsCentralCropNotStretch() {
        // Robert's case: portrait phone 1080x2424 into an 800x480 car frame.
        val uv = MirrorGeometry.fillUv(1080f, 2424f, 800f, 480f)
        assertEquals(0.3664f, uv[0], 0.001f)
        assertEquals(0.3664f, uv[1], 0.001f)
        assertEquals(0.6336f, uv[2], 0.001f)
        assertEquals(0.6336f, uv[3], 0.001f)
        // The sampled rect's aspect must equal the frame's aspect (1.667):
        // one scale on both axes, no distortion.
        assertEquals(800f / 480f, aspect(uv), 0.01f)
    }

    @Test
    fun fillUvLandscapePhoneCropsWidth() {
        // Landscape phone 2424x1080 into the same frame: content is wider
        // than the frame, so height is kept whole and width is cropped.
        val uv = MirrorGeometry.fillUv(2424f, 1080f, 800f, 480f)
        assertEquals(0.1288f, uv[0], 0.001f)
        assertEquals(0.1288f, uv[1], 0.001f)
        assertEquals(0.8713f, uv[2], 0.001f)
        assertEquals(0.8713f, uv[3], 0.001f)
        assertEquals(800f / 480f, aspect(uv), 0.01f)
    }

    @Test
    fun fillUvEqualsContentUvWhenAspectsMatch() {
        val fill = MirrorGeometry.fillUv(1600f, 900f, 800f, 450f)
        val fit = MirrorGeometry.contentUv(1600f, 900f, 800f, 450f)
        for (i in 0..3) assertEquals(fit[i], fill[i], 0.001f)
    }

    @Test
    fun oldContentUvWasAStretchForPortraitPhone() {
        // Regression anchor for the bug: the old Fill code sampled the
        // whole Fit rect, whose aspect (0.446) is nothing like the
        // frame's (1.667) — a 3.7x horizontal stretch.
        val fit = MirrorGeometry.contentUv(1080f, 2424f, 800f, 480f)
        assertEquals(0.3664f, fit[0], 0.001f)
        assertEquals(0f, fit[1], 0.001f)
        assertEquals(0.6336f, fit[2], 0.001f)
        assertEquals(1f, fit[3], 0.001f)
        assertNotEquals(800f / 480f, aspect(fit), 0.1f)
    }

    @Test
    fun fillVideoAndTouchAgreeAtFrameCorners() {
        // The frame's corners must map, through the video crop and
        // through touch mapping, to the same phone points (within 1px),
        // or taps land away from what the driver sees.
        val phoneW = 1080f
        val phoneH = 2424f
        val frameW = 800f
        val frameH = 480f
        val uv = MirrorGeometry.fillUv(phoneW, phoneH, frameW, frameH)
        val fit = MirrorGeometry.fit(phoneW, phoneH, frameW, frameH)
        // Phone point of the crop's top-left, via the Fit buffer:
        // bufferX = u0 * frameW, bufferY = v0 * frameH, then un-Fit.
        val cropTopLeftPhoneX = (uv[0] * frameW - fit.offsetX) / fit.scale
        val cropTopLeftPhoneY = (uv[1] * frameH - fit.offsetY) / fit.scale
        val touchTopLeft = MirrorGeometry.mapFill(0f, 0f, phoneW, phoneH, frameW, frameH)!!
        assertEquals(touchTopLeft.x, cropTopLeftPhoneX, 1f)
        assertEquals(touchTopLeft.y, cropTopLeftPhoneY, 1f)
        // And the bottom-right corner likewise.
        val cropBottomRightPhoneX = (uv[2] * frameW - fit.offsetX) / fit.scale
        val cropBottomRightPhoneY = (uv[3] * frameH - fit.offsetY) / fit.scale
        val touchBottomRight = MirrorGeometry.mapFill(frameW, frameH, phoneW, phoneH, frameW, frameH)!!
        assertEquals(touchBottomRight.x, cropBottomRightPhoneX, 1f)
        assertEquals(touchBottomRight.y, cropBottomRightPhoneY, 1f)
    }

    @Test
    fun fillUvDegenerateSizesFallBackToFullFrame() {
        val uv = MirrorGeometry.fillUv(0f, 0f, 800f, 480f)
        assertEquals(0f, uv[0], 0f)
        assertEquals(0f, uv[1], 0f)
        assertEquals(1f, uv[2], 0f)
        assertEquals(1f, uv[3], 0f)
    }
}
