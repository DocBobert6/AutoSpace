package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** r75 browser PiP: experimental gate, card visibility, branch rule, state transitions. */
class MiniVideoTest {

    @Before
    fun reset() {
        MiniVideoState.clear()
    }




    @Test
    fun cardShowsWhenGatedActiveAndOnHome() {
        assertEquals(true, shouldShowMiniVideoCard(true, true, true))
    }

    @Test
    fun cardHiddenWhenGateOff() {
        assertEquals(false, shouldShowMiniVideoCard(false, true, true))
    }

    @Test
    fun cardHiddenWhenNothingHandedOff() {
        assertEquals(false, shouldShowMiniVideoCard(true, false, true))
    }

    @Test
    fun cardHiddenOffHome() {
        assertEquals(false, shouldShowMiniVideoCard(true, true, false))
    }

    @Test
    fun branchParkedIsVideo() {
        assertEquals(MiniVideoBranch.VIDEO, miniVideoBranch(false, false, false))
    }

    @Test
    fun branchMovingWithPassengerIsVideo() {
        assertEquals(MiniVideoBranch.VIDEO, miniVideoBranch(true, true, false))
    }

    @Test
    fun branchMovingWithoutPassengerIsAudioOnly() {
        assertEquals(MiniVideoBranch.AUDIO_ONLY, miniVideoBranch(true, false, false))
    }

    @Test
    fun branchGateOpenWins() {
        assertEquals(MiniVideoBranch.VIDEO_BEHIND_GATE, miniVideoBranch(true, false, true))
    }

    @Test
    fun popOutSetsActiveAndResetsGate() {
        MiniVideoState.onPassengerAnswer()
        MiniVideoState.popOut("https://example.com/v.mp4", "Clip")
        assertEquals("https://example.com/v.mp4", MiniVideoState.active.value?.url)
        assertEquals(false, MiniVideoState.passengerAllowed)
        assertEquals(false, MiniVideoState.gateOpen.value)
    }

    @Test
    fun drivingAnswerClosesGateAndMarksStretch() {
        MiniVideoState.popOut("https://example.com/v.mp4", null)
        MiniVideoState.onMovingUnanswered()
        assertEquals(true, MiniVideoState.gateOpen.value)
        MiniVideoState.onDrivingAnswer()
        assertEquals(false, MiniVideoState.gateOpen.value)
        assertEquals(true, MiniVideoState.driverAnswered)
    }

    @Test
    fun parkedLapsesDriverAnswerButKeepsPassenger() {
        MiniVideoState.popOut("https://example.com/v.mp4", null)
        MiniVideoState.onPassengerAnswer()
        MiniVideoState.onParked()
        assertEquals(true, MiniVideoState.passengerAllowed)
        assertEquals(false, MiniVideoState.gateOpen.value)
        MiniVideoState.onMovingUnanswered()
        assertEquals(false, MiniVideoState.gateOpen.value)
    }

    @Test
    fun movingReasksAfterDriverAnswerAndPark() {
        MiniVideoState.popOut("https://example.com/v.mp4", null)
        MiniVideoState.onMovingUnanswered()
        MiniVideoState.onDrivingAnswer()
        MiniVideoState.onParked()
        assertEquals(false, MiniVideoState.driverAnswered)
        MiniVideoState.onMovingUnanswered()
        assertEquals(true, MiniVideoState.gateOpen.value)
    }

    @Test
    fun clearResetsEverything() {
        MiniVideoState.popOut("https://example.com/v.mp4", null)
        MiniVideoState.onPassengerAnswer()
        MiniVideoState.clear()
        assertEquals(null, MiniVideoState.active.value)
        assertEquals(false, MiniVideoState.passengerAllowed)
        assertEquals(false, MiniVideoState.driverAnswered)
        assertEquals(false, MiniVideoState.gateOpen.value)
    }

    // r118: title line, sizes, drag gate, size state.

    @Test
    fun displayTitlePrefersTitle() {
        assertEquals("Clip", miniVideoDisplayTitle("Clip", "https://example.com/v.mp4"))
    }

    @Test
    fun displayTitleDerivesFromUrlWhenMissing() {
        assertEquals("my cool clip", miniVideoDisplayTitle(null, "https://example.com/videos/my_cool-clip.mp4?x=1"))
    }

    @Test
    fun displayTitleDerivesWhenBlank() {
        assertEquals("trailer", miniVideoDisplayTitle("   ", "https://example.com/trailer.webm#frag"))
    }

    @Test
    fun displayTitleDecodesAndStripsExtension() {
        assertEquals("my video", miniVideoDisplayTitle(null, "https://example.com/my%20video.mp4"))
    }

    @Test
    fun displayTitleFallsBackToHost() {
        assertEquals("example.com", miniVideoDisplayTitle(null, "https://example.com/videos/"))
        assertEquals("example.com", miniVideoDisplayTitle(null, "https://example.com"))
    }

    @Test
    fun displayTitleNeverShowsRawUrl() {
        val shown = miniVideoDisplayTitle(null, "https://example.com/videos/my_cool-clip.mp4?x=1")
        assertEquals(false, shown.startsWith("http"))
    }

    @Test
    fun cardWidthSmallMirrorsSlotWidth() {
        assertEquals(320f, miniVideoCardWidthDp(MiniVideoSize.SMALL, 800f), 0.001f)
        assertEquals(240f, miniVideoCardWidthDp(MiniVideoSize.SMALL, 400f), 0.001f)
        assertEquals(380f, miniVideoCardWidthDp(MiniVideoSize.SMALL, 1200f), 0.001f)
        assertEquals(176f, miniVideoCardWidthDp(MiniVideoSize.SMALL, 200f), 0.001f)
    }

    @Test
    fun cardWidthLargeGrows() {
        assertEquals(480f, miniVideoCardWidthDp(MiniVideoSize.LARGE, 800f), 0.001f)
        assertEquals(360f, miniVideoCardWidthDp(MiniVideoSize.LARGE, 400f), 0.001f)
        assertEquals(560f, miniVideoCardWidthDp(MiniVideoSize.LARGE, 1200f), 0.001f)
    }

    @Test
    fun videoHeightBySize() {
        assertEquals(180f, miniVideoVideoHeightDp(MiniVideoSize.SMALL), 0.001f)
        assertEquals(320f, miniVideoVideoHeightDp(MiniVideoSize.LARGE), 0.001f)
    }

    @Test
    fun sizeCycleSmallGrowsLargeThenNull() {
        assertEquals(MiniVideoSize.LARGE, cycleMiniVideoSize(MiniVideoSize.SMALL))
        assertEquals(null, cycleMiniVideoSize(MiniVideoSize.LARGE))
    }

    @Test
    fun dragGateParkedOnly() {
        assertEquals(true, miniVideoDragApplies(false))
        assertEquals(false, miniVideoDragApplies(true))
    }

    @Test
    fun sizeStateStartsSmallAndResetsOnPopOut() {
        MiniVideoState.popOut("https://example.com/v.mp4", null)
        assertEquals(MiniVideoSize.SMALL, MiniVideoState.size.value)
        assertEquals(true, MiniVideoState.advanceSize())
        assertEquals(MiniVideoSize.LARGE, MiniVideoState.size.value)
        assertEquals(false, MiniVideoState.advanceSize())
        assertEquals(MiniVideoSize.LARGE, MiniVideoState.size.value)
        MiniVideoState.popOut("https://example.com/other.mp4", null)
        assertEquals(MiniVideoSize.SMALL, MiniVideoState.size.value)
    }
}
