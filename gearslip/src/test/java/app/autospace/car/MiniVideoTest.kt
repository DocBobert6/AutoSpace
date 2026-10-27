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
    fun gateBothOnShowsPip() {
        assertEquals(true, browserPipNow(true, true))
    }

    @Test
    fun gateExperimentalOffHidesPip() {
        assertEquals(false, browserPipNow(false, true))
    }

    @Test
    fun gateToggleOffHidesPip() {
        assertEquals(false, browserPipNow(true, false))
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
}
