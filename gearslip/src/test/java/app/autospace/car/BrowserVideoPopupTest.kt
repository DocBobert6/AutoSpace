package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserVideoPopupTest {

    private val known = listOf(
        FoundMedia("https://example.com/clip.mp4", "Clip"),
        FoundMedia("https://example.com/stream.m3u8", "Stream"),
        FoundMedia("https://example.com/song.mp3", "Song"),
    )

    @Test
    fun allowCheckMatchesAKnownVideoUrl() {
        assertEquals(known[0], VideoPopup.allowCheck("https://example.com/clip.mp4", known))
    }

    @Test
    fun allowCheckRejectsBlobUrls() {
        assertNull(VideoPopup.allowCheck("blob:https://example.com/abc", known))
    }

    @Test
    fun allowCheckRejectsNonHttpUrls() {
        assertNull(VideoPopup.allowCheck("file:///sdcard/clip.mp4", known))
    }

    @Test
    fun allowCheckRejectsUnknownUrls() {
        assertNull(VideoPopup.allowCheck("https://example.com/other.mp4", known))
    }

    @Test
    fun allowCheckRejectsAudioOnlyUrls() {
        assertNull(VideoPopup.allowCheck("https://example.com/song.mp3", known))
    }

    @Test
    fun allowCheckRejectsUnplayableKnownUrls() {
        val page = listOf(FoundMedia("https://example.com/page", null))
        assertNull(VideoPopup.allowCheck("https://example.com/page", page))
    }

    @Test
    fun popupOffJsSetsTheWatcherFlag() {
        assertEquals("window.__autospaceVideoPopupOff = true;", VideoPopup.popupOffJs(true))
        assertEquals("window.__autospaceVideoPopupOff = false;", VideoPopup.popupOffJs(false))
    }

    @Test
    fun watcherMentionsTheBridgeAndFlagAndNeverTheOldName() {
        assertTrue(VideoPopup.WATCHER_JS.contains("autospaceVideoPopup"))
        assertTrue(VideoPopup.WATCHER_JS.contains("__autospaceVideoPopupOff"))
        assertFalse(VideoPopup.WATCHER_JS.contains("notscape"))
    }

    @Test
    fun bridgeNameIsTheWatcherBridge() {
        assertEquals("autospaceVideoPopup", VideoPopup.BRIDGE_NAME)
    }
}
