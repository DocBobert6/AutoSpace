package app.autospace.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorGeometryTest {
    // Portrait phone (1080x2400) on a landscape car frame (1920x1080): the heavy-crop case.
    private val phoneW = 1080f
    private val phoneH = 2400f
    private val frameW = 1920f
    private val frameH = 1080f

    @Test
    fun fitLetterboxesWithSideBars() {
        val r = MirrorGeometry.fit(phoneW, phoneH, frameW, frameH)
        assertEquals(0.45f, r.scale, 0.001f)
        assertEquals(717f, r.offsetX, 0.5f)
        assertEquals(0f, r.offsetY, 0.001f)
    }

    @Test
    fun fillCropsTopAndBottom() {
        val r = MirrorGeometry.fill(phoneW, phoneH, frameW, frameH)
        assertEquals(1920f / 1080f, r.scale, 0.001f)
        assertEquals(0f, r.offsetX, 0.5f)
        assertTrue(r.offsetY < 0f)
    }

    @Test
    fun mapFitMapsContentAndRejectsPadding() {
        val centre = MirrorGeometry.mapFit(960f, 540f, phoneW, phoneH, frameW, frameH)
        assertNotNull(centre)
        assertEquals(540f, centre!!.x, 0.5f)
        assertEquals(1200f, centre.y, 0.5f)
        assertNull(MirrorGeometry.mapFit(100f, 540f, phoneW, phoneH, frameW, frameH))
        assertNull(MirrorGeometry.mapFit(1900f, 540f, phoneW, phoneH, frameW, frameH))
    }

    @Test
    fun mapFillMapsEveryFramePointInsideThePhone() {
        val centre = MirrorGeometry.mapFill(960f, 540f, phoneW, phoneH, frameW, frameH)
        assertNotNull(centre)
        assertEquals(540f, centre!!.x, 0.5f)
        assertEquals(1200f, centre.y, 0.5f)
        // Corners still land inside the phone (the frame shows a cropped sub-rect of it).
        val corner = MirrorGeometry.mapFill(0f, 0f, phoneW, phoneH, frameW, frameH)
        assertNotNull(corner)
        assertTrue(corner!!.x >= 0f && corner.x <= phoneW)
        assertTrue(corner.y >= 0f && corner.y <= phoneH)
    }

    @Test
    fun contentUvIsTheFitContentRect() {
        val uv = MirrorGeometry.contentUv(phoneW, phoneH, frameW, frameH)
        assertEquals(717f / 1920f, uv[0], 0.001f)
        assertEquals(0f, uv[1], 0.001f)
        assertEquals(1203f / 1920f, uv[2], 0.001f)
        assertEquals(1f, uv[3], 0.001f)
    }

    @Test
    fun exitRegionIsTopRightAndContainsItsCorner() {
        val r = MirrorGeometry.exitRegion(frameW, frameH)
        assertEquals(frameW, r.right, 0.001f)
        assertEquals(0f, r.top, 0.001f)
        assertEquals(129.6f, r.bottom, 0.5f)
        assertTrue(r.contains(1900f, 60f))
        assertTrue(!r.contains(100f, 100f))
    }

    @Test
    fun sameAspectFitEqualsFill() {
        val f = MirrorGeometry.fit(1920f, 1080f, 1920f, 1080f)
        val g = MirrorGeometry.fill(1920f, 1080f, 1920f, 1080f)
        assertEquals(1f, f.scale, 0.001f)
        assertEquals(f, g)
    }
}
