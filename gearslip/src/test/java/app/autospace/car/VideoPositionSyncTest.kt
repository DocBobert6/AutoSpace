package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** r118: the shared live position between the mini card and the full player. */
class VideoPositionSyncTest {

    @Before
    fun reset() {
        VideoPositionSync.clear()
    }

    @Test
    fun livePositionFollowsUpdates() {
        VideoPositionSync.update("https://example.com/a.mp4", 42_000L)
        assertEquals(42_000L, VideoPositionSync.livePositionFor("https://example.com/a.mp4"))
        assertNull(VideoPositionSync.livePositionFor("https://example.com/b.mp4"))
    }

    @Test
    fun updateClampsNegativeToZero() {
        VideoPositionSync.update("https://example.com/a.mp4", -500L)
        assertEquals(0L, VideoPositionSync.livePositionFor("https://example.com/a.mp4"))
    }

    @Test
    fun switchConsumesExactlyOnce() {
        VideoPositionSync.setSwitch("https://example.com/a.mp4", 60_000L)
        assertEquals(60_000L, VideoPositionSync.consumeSwitch("https://example.com/a.mp4"))
        assertNull(VideoPositionSync.consumeSwitch("https://example.com/a.mp4"))
    }

    @Test
    fun switchForAnotherUrlLeavesItPending() {
        VideoPositionSync.setSwitch("https://example.com/a.mp4", 60_000L)
        assertNull(VideoPositionSync.consumeSwitch("https://example.com/b.mp4"))
        assertEquals(60_000L, VideoPositionSync.consumeSwitch("https://example.com/a.mp4"))
    }

    @Test
    fun setSwitchAlsoWritesLive() {
        VideoPositionSync.setSwitch("https://example.com/a.mp4", 60_000L)
        assertEquals(60_000L, VideoPositionSync.livePositionFor("https://example.com/a.mp4"))
    }

    @Test
    fun saveLikeResumeMeaningfulUpdates() {
        VideoPositionSync.saveLikeResume("https://example.com/a.mp4", 30_000L, 120_000L)
        assertEquals(30_000L, VideoPositionSync.livePositionFor("https://example.com/a.mp4"))
    }

    @Test
    fun saveLikeResumeFinishedClears() {
        VideoPositionSync.update("https://example.com/a.mp4", 100_000L)
        VideoPositionSync.saveLikeResume("https://example.com/a.mp4", 118_000L, 120_000L)
        assertNull(VideoPositionSync.livePositionFor("https://example.com/a.mp4"))
    }

    @Test
    fun saveLikeResumeSubThresholdLeavesUntouched() {
        VideoPositionSync.update("https://example.com/a.mp4", 50_000L)
        VideoPositionSync.saveLikeResume("https://example.com/a.mp4", 3_000L, 120_000L)
        assertEquals(50_000L, VideoPositionSync.livePositionFor("https://example.com/a.mp4"))
    }

    @Test
    fun saveLikeResumeSubThresholdWithNothingStaysEmpty() {
        VideoPositionSync.saveLikeResume("https://example.com/a.mp4", 3_000L, 120_000L)
        assertNull(VideoPositionSync.livePositionFor("https://example.com/a.mp4"))
    }

    @Test
    fun clearLivePreservesPendingSwitch() {
        VideoPositionSync.setSwitch("https://example.com/a.mp4", 60_000L)
        VideoPositionSync.clearLive("https://example.com/a.mp4")
        assertNull(VideoPositionSync.livePositionFor("https://example.com/a.mp4"))
        assertEquals(60_000L, VideoPositionSync.consumeSwitch("https://example.com/a.mp4"))
    }

    @Test
    fun clearUrlClearsBoth() {
        VideoPositionSync.setSwitch("https://example.com/a.mp4", 60_000L)
        VideoPositionSync.clearUrl("https://example.com/a.mp4")
        assertNull(VideoPositionSync.livePositionFor("https://example.com/a.mp4"))
        assertNull(VideoPositionSync.consumeSwitch("https://example.com/a.mp4"))
    }
}
