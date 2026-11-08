package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** r195: video V3 pure seams — controls lock, persist scale/brightness, sidecar discovery. */
class VideoPlayerTest {

    @Test fun scaleModeFromLabel_mapsAllModes() {
        assertEquals(ScaleMode.FIT, scaleModeFromLabel("Fit"))
        assertEquals(ScaleMode.STRETCH, scaleModeFromLabel("Stretch"))
        assertEquals(ScaleMode.FILL, scaleModeFromLabel("Fill"))
        assertEquals(ScaleMode.CROP, scaleModeFromLabel("Crop"))
    }

    @Test fun scaleModeFromLabel_unknownIsFit() {
        assertEquals(ScaleMode.FIT, scaleModeFromLabel(""))
        assertEquals(ScaleMode.FIT, scaleModeFromLabel("bogus"))
        assertEquals(ScaleMode.FIT, scaleModeFromLabel("fit"))
    }

    @Test fun playerChromeVisible_needsVisibleAndUnlocked() {
        assertTrue(playerChromeVisible(true, false))
        assertFalse(playerChromeVisible(false, false))
        assertFalse(playerChromeVisible(true, true))
        assertFalse(playerChromeVisible(false, true))
    }

    @Test fun lockCoverVisible_neverCoversError() {
        assertTrue(lockCoverVisible(true, null))
        assertFalse(lockCoverVisible(true, "This video could not be played."))
        assertFalse(lockCoverVisible(false, null))
    }

    @Test fun playerBrightnessFromStored_systemIsNull() {
        assertNull(playerBrightnessFromStored("system"))
        assertNull(playerBrightnessFromStored(""))
        assertNull(playerBrightnessFromStored("bogus"))
    }

    @Test fun playerBrightnessFromStored_parsesAndClamps() {
        assertEquals(0.5f, playerBrightnessFromStored("0.5") ?: -1f, 0.001f)
        assertEquals(0.05f, playerBrightnessFromStored("0.01") ?: -1f, 0.001f)
        assertEquals(1f, playerBrightnessFromStored("2.0") ?: -1f, 0.001f)
    }

    @Test fun playerBrightnessToStored_roundTrip() {
        assertEquals("system", playerBrightnessToStored(null))
        assertEquals("0.5", playerBrightnessToStored(0.5f))
        assertEquals("1.0", playerBrightnessToStored(1f))
    }

    @Test fun sidecarPathFor_plainPathsOnly() {
        assertEquals(
            "/storage/emulated/0/Download/video.mp4",
            sidecarPathFor("file:///storage/emulated/0/Download/video.mp4"),
        )
        assertEquals("/storage/emulated/0/Download/video.mp4", sidecarPathFor("/storage/emulated/0/Download/video.mp4"))
        assertNull(sidecarPathFor("https://example.com/video.mp4"))
        assertNull(sidecarPathFor("content://downloads/public_downloads/42"))
    }

    @Test fun sidecarCandidates_marinerList() {
        assertEquals(
            listOf(
                "/sd/Download/video.vtt", "/sd/Download/video.srt", "/sd/Download/video.ttml",
                "/sd/Download/video.sub1.vtt", "/sd/Download/video.sub2.vtt", "/sd/Download/video.sub3.vtt",
                "/sd/Download/video.sub1.ttml", "/sd/Download/video.sub2.ttml", "/sd/Download/video.sub3.ttml",
            ),
            sidecarCandidates("/sd/Download/video"),
        )
    }

    @Test fun sidecarCandidatesInDir_stripsExtension() {
        val got = sidecarCandidatesInDir("/sd/Download", "video.mp4")
        assertTrue(got.contains("/sd/Download/video.vtt"))
        assertTrue(got.contains("/sd/Download/video.srt"))
        assertFalse(got.any { it.contains("video.mp4") })
    }

    @Test fun sidecarCandidatesInDir_noExtensionKeepsName() {
        assertTrue(sidecarCandidatesInDir("/sd/Download", "video").contains("/sd/Download/video.vtt"))
    }

    @Test fun sidecarMimeFor_knownExtensions() {
        assertEquals("text/vtt", sidecarMimeFor("/sd/Download/video.vtt"))
        assertEquals("application/x-subrip", sidecarMimeFor("/sd/Download/video.srt"))
        assertEquals("application/ttml+xml", sidecarMimeFor("/sd/Download/video.ttml"))
        assertNull(sidecarMimeFor("/sd/Download/video.mp4"))
        assertNull(sidecarMimeFor("/sd/Download/video"))
    }
}
