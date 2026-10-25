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
    fun mapDisplayAtZoom1AgreesWithFitAndFill() {
        val fitPoint = MirrorGeometry.mapDisplay(960f, 540f, phoneW, phoneH, frameW, frameH, false, 1f, 0f, 0f)
        val fitLegacy = MirrorGeometry.mapFit(960f, 540f, phoneW, phoneH, frameW, frameH)
        assertNotNull(fitPoint)
        assertEquals(fitLegacy!!.x, fitPoint!!.x, 0.001f)
        assertEquals(fitLegacy.y, fitPoint.y, 0.001f)
        val fillPoint = MirrorGeometry.mapDisplay(960f, 540f, phoneW, phoneH, frameW, frameH, true, 1f, 0f, 0f)
        val fillLegacy = MirrorGeometry.mapFill(960f, 540f, phoneW, phoneH, frameW, frameH)
        assertNotNull(fillPoint)
        assertEquals(fillLegacy!!.x, fillPoint!!.x, 0.001f)
        assertEquals(fillLegacy.y, fillPoint.y, 0.001f)
        // Fit padding still maps to nothing under the new function.
        assertNull(MirrorGeometry.mapDisplay(100f, 540f, phoneW, phoneH, frameW, frameH, false, 1f, 0f, 0f))
    }

    @Test
    fun mapDisplayZoomKeepsCentreAndPanShifts() {
        // The frame centre always maps to the phone centre, at any zoom.
        val zoomed = MirrorGeometry.mapDisplay(960f, 540f, phoneW, phoneH, frameW, frameH, false, 2f, 0f, 0f)
        assertNotNull(zoomed)
        assertEquals(540f, zoomed!!.x, 0.5f)
        assertEquals(1200f, zoomed.y, 0.5f)
        // Panning right by 100px moves the mapped point left by 100 / scale px.
        val panned = MirrorGeometry.mapDisplay(960f, 540f, phoneW, phoneH, frameW, frameH, false, 2f, 100f, 0f)
        assertNotNull(panned)
        assertTrue(panned!!.x < zoomed.x)
        // A point dragged off the zoomed content maps to nothing, not to a clamped edge.
        assertNull(MirrorGeometry.mapDisplay(0f, 540f, phoneW, phoneH, frameW, frameH, false, 2f, 0f, 0f))
    }

    @Test
    fun pillAndChromeStayInsideFrameAndOffTheExitButton() {
        for ((w, h) in listOf(800f to 480f, 1920f to 1080f)) {
            val pill = MirrorGeometry.pillRegion(w, h)
            assertTrue(pill.left >= 0f && pill.top >= 0f && pill.right <= w && pill.bottom <= h)
            val exit = MirrorGeometry.exitRegion(w, h)
            assertTrue(pill.right <= exit.left)
            val buttons = MirrorGeometry.chromeButtons(w, h)
            assertEquals(4, buttons.size)
            buttons.forEach { b ->
                assertTrue(b.left >= 0f && b.top >= 0f && b.right <= w && b.bottom <= h)
            }
            for (i in 0 until buttons.size - 1) assertTrue(buttons[i].right <= buttons[i + 1].left)
        }
    }

    @Test
    fun sameAspectFitEqualsFill() {
        val f = MirrorGeometry.fit(1920f, 1080f, 1920f, 1080f)
        val g = MirrorGeometry.fill(1920f, 1080f, 1920f, 1080f)
        assertEquals(1f, f.scale, 0.001f)
        assertEquals(f, g)
    }
}
