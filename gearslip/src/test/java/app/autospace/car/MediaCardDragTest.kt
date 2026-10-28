package app.autospace.car

import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** r106 floating media card free-drag rules: gate, nav-low pair, aligned position, clamp. */
class MediaCardDragTest {

    @Test
    fun dragAppliesUndockedParked() {
        assertTrue(mediaCardDragApplies(dockEnabled = false, moving = false))
    }

    @Test
    fun dragBlockedDockedParked() {
        assertFalse(mediaCardDragApplies(dockEnabled = true, moving = false))
    }

    @Test
    fun dragBlockedUndockedMoving() {
        assertFalse(mediaCardDragApplies(dockEnabled = false, moving = true))
    }

    @Test
    fun dragBlockedDockedMoving() {
        assertFalse(mediaCardDragApplies(dockEnabled = true, moving = true))
    }

    @Test
    fun cardNavLowBindsDockedNavigating() {
        assertTrue(mediaCardDragNavLow(dockEnabled = true, navigating = true))
    }

    @Test
    fun cardNavLowNotBindUndockedNavigating() {
        assertFalse(mediaCardDragNavLow(dockEnabled = false, navigating = true))
    }

    @Test
    fun cardNavLowNotBindDockedNotNavigating() {
        assertFalse(mediaCardDragNavLow(dockEnabled = true, navigating = false))
    }

    @Test
    fun etaNavLowBindsNavigating() {
        assertTrue(mediaCardEtaNavLow(navigating = true))
    }

    @Test
    fun etaNavLowNotBindNotNavigating() {
        assertFalse(mediaCardEtaNavLow(navigating = false))
    }

    @Test
    fun alignedLeftEndIsFrameMinusPadMinusCard() {
        assertEquals(476f, mediaCardAlignedLeftDp(Alignment.BottomEnd, 800f, 300f, 24f))
    }

    @Test
    fun alignedLeftCenterIsCentered() {
        assertEquals(250f, mediaCardAlignedLeftDp(Alignment.BottomCenter, 800f, 300f, 24f))
    }

    @Test
    fun alignedLeftStartIsPad() {
        assertEquals(24f, mediaCardAlignedLeftDp(Alignment.BottomStart, 800f, 300f, 24f))
    }

    @Test
    fun clampKeepsZeroOffsetAtZero() {
        assertEquals(
            Offset.Zero,
            clampMediaCardDragOffset(0f, 0f, 476f, 226f, 800f, 400f, 300f, 150f, MediaCardDragMinVisibleDp),
        )
    }

    @Test
    fun clampLeavesInBoundsOffsetAlone() {
        assertEquals(
            Offset(100f, -50f),
            clampMediaCardDragOffset(100f, -50f, 476f, 226f, 800f, 400f, 300f, 150f, MediaCardDragMinVisibleDp),
        )
    }

    @Test
    fun clampHoldsMinVisibleStripFarRightBottom() {
        // Frame 800x400, card 300x150, pad 24, END-aligned default
        // left 476 / top 226; absolute left in [-252, 752], top in
        // [-102, 352]; offset ranges x [-728, 276], y [-328, 126].
        assertEquals(
            Offset(276f, 126f),
            clampMediaCardDragOffset(2000f, 2000f, 476f, 226f, 800f, 400f, 300f, 150f, MediaCardDragMinVisibleDp),
        )
    }

    @Test
    fun clampHoldsMinVisibleStripFarLeftTop() {
        assertEquals(
            Offset(-728f, -328f),
            clampMediaCardDragOffset(-2000f, -2000f, 476f, 226f, 800f, 400f, 300f, 150f, MediaCardDragMinVisibleDp),
        )
    }

    @Test
    fun clampLeavesUnknownSizeAloneSoFirstFrameNeverJumps() {
        assertEquals(
            Offset(100f, -50f),
            clampMediaCardDragOffset(100f, -50f, 476f, 226f, 800f, 400f, 0f, 0f, MediaCardDragMinVisibleDp),
        )
    }
}
