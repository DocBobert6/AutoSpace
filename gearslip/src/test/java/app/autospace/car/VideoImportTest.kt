package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoImportTest {

    @Test
    fun gateAcceptsDirectVideoFiles() {
        for (name in listOf("clip.mp4", "movie.MKV", "a.webm", "b.mov", "c.avi")) {
            assertEquals(name, VideoImportGate.OK, VideoImport.gateForFileName(name))
        }
    }

    @Test
    fun gateRefusesAudioAndManifestsAndUnknown() {
        assertEquals(VideoImportGate.NOT_VIDEO, VideoImport.gateForFileName("song.mp3"))
        assertEquals(VideoImportGate.MANIFEST, VideoImport.gateForFileName("stream.m3u8"))
        assertEquals(VideoImportGate.MANIFEST, VideoImport.gateForFileName("stream.mpd"))
        assertEquals(VideoImportGate.NOT_VIDEO, VideoImport.gateForFileName("page.html"))
        assertEquals(VideoImportGate.NOT_VIDEO, VideoImport.gateForFileName("noextension"))
    }

    @Test
    fun importRecordPointsAtThePhoneFile() {
        val record = VideoImport.recordForImport("content://com.example/tree/video.mp4", "video.mp4", 1234L)
        assertEquals("import:content://com.example/tree/video.mp4", record.id)
        assertEquals("content://com.example/tree/video.mp4", record.url)
        assertEquals("content://com.example/tree/video.mp4", record.localSource)
        assertEquals("video.mp4", record.fileName)
        assertEquals(BrowserStore.DownloadState.COMPLETE, record.state)
        assertEquals(-1L, record.downloadManagerId)
        assertTrue(record.isMedia)
        assertFalse(record.isPrivate)
        assertEquals(BrowserStore.DownloadKind.PLAIN, record.kind)
        assertEquals(1234L, record.createdAtMs)
    }

    @Test
    fun importedRecordDetectionIsIdBased() {
        val imported = VideoImport.recordForImport("content://x/y.mp4", "y.mp4", 0L)
        assertTrue(VideoImport.isImportedRecord(imported))
        val plain = imported.copy(id = "something-else")
        assertFalse(VideoImport.isImportedRecord(plain))
    }

    @Test
    fun videoExtensionGateMatchesPlayerSet() {
        assertTrue(VideoUrl.isVideoExtension("mp4"))
        assertTrue(VideoUrl.isVideoExtension("mkv"))
        assertFalse(VideoUrl.isVideoExtension("mp3"))
        assertFalse(VideoUrl.isVideoExtension("m3u8"))
    }
}
