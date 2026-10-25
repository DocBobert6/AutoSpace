package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoUrlTest {

    @Test
    fun hlsAndDashArePlayableWithManifestMimeTypes() {
        assertTrue(VideoUrl.isPlayableUrl("https://example.com/stream.m3u8"))
        assertEquals(VideoUrl.Kind.HLS, VideoUrl.kindOf("https://example.com/stream.m3u8?token=abc"))
        assertEquals("application/x-mpegURL", VideoUrl.mimeTypeFor("https://example.com/stream.m3u8"))
        assertTrue(VideoUrl.isPlayableUrl("https://example.com/manifest.mpd"))
        assertEquals(VideoUrl.Kind.DASH, VideoUrl.kindOf("https://example.com/manifest.mpd"))
        assertEquals("application/dash+xml", VideoUrl.mimeTypeFor("https://example.com/manifest.mpd"))
    }

    @Test
    fun directVideoAndAudioArePlayableWithoutMimeType() {
        assertTrue(VideoUrl.isPlayableUrl("https://example.com/clip.mp4"))
        assertEquals(VideoUrl.Kind.VIDEO, VideoUrl.kindOf("https://example.com/clip.MP4"))
        assertNull(VideoUrl.mimeTypeFor("https://example.com/clip.mp4"))
        assertTrue(VideoUrl.isPlayableUrl("https://example.com/song.mp3"))
        assertEquals(VideoUrl.Kind.AUDIO, VideoUrl.kindOf("https://example.com/song.mp3"))
    }

    @Test
    fun youtubeBlobDataAndExtensionlessUrlsAreNotPlayable() {
        assertFalse(VideoUrl.isPlayableUrl("https://www.youtube.com/watch?v=abc123"))
        assertFalse(VideoUrl.isPlayableUrl("https://youtu.be/abc123"))
        assertFalse(VideoUrl.isPlayableUrl("blob:https://example.com/1234"))
        assertFalse(VideoUrl.isPlayableUrl("data:video/mp4;base64,AAAA"))
        assertFalse(VideoUrl.isPlayableUrl("https://example.com/watch"))
        assertFalse(VideoUrl.isPlayableUrl("https://example.com/page.html"))
        assertFalse(VideoUrl.isPlayableUrl("https://example.com/image.png"))
    }

    @Test
    fun labelsMatchKinds() {
        assertEquals("HLS", VideoUrl.labelFor("https://example.com/s.m3u8"))
        assertEquals("DASH", VideoUrl.labelFor("https://example.com/s.mpd"))
        assertEquals("Video", VideoUrl.labelFor("https://example.com/s.webm"))
        assertEquals("Audio", VideoUrl.labelFor("https://example.com/s.m4a"))
    }
}
