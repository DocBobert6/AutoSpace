package app.autospace.car

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for the r159 HLS worker slice (no Android
 * framework needed): variant selection, media-playlist parsing,
 * URL resolution, the enqueue routing rule, library action gating,
 * segment joining, and file naming. The WorkManager scheduling
 * itself and the network/MediaStore halves rest on CI compilation
 * plus the device checks in the round report.
 */
class HlsDownloadTest {

    private fun workerRecord(state: BrowserStore.DownloadState) =
        BrowserStore.DownloadRecord(
            id = "hls-1",
            url = "https://example.com/hls/master.m3u8",
            fileName = "master",
            state = state,
            downloadManagerId = -1L,
            localSource = null,
            isPrivate = false,
            createdAtMs = 1_000_000L,
            isMedia = true,
            progress = -1,
            kind = BrowserStore.DownloadKind.HLS,
        )

    private fun plainRecord(state: BrowserStore.DownloadState) =
        BrowserStore.DownloadRecord(
            id = "dl-1",
            url = "https://example.com/video.mp4",
            fileName = "video.mp4",
            state = state,
            downloadManagerId = 42L,
            localSource = null,
            isPrivate = false,
            createdAtMs = 1_000_000L,
            isMedia = true,
            progress = -1,
            kind = BrowserStore.DownloadKind.PLAIN,
        )

    // ---- Variant selection ----

    @Test
    fun selectVariantPicksBestBandwidthAndFirstOnTie() {
        val master = "#EXTM3U\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\n" +
            "low.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080\n" +
            "high.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080\n" +
            "high2.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720\n" +
            "mid.m3u8\n"
        assertEquals("high.m3u8", HlsPlaylist.selectVariant(master))
    }

    @Test
    fun selectVariantMissingBandwidthCountsAsZero() {
        val master = "#EXTM3U\n" +
            "#EXT-X-STREAM-INF:RESOLUTION=640x360\n" +
            "untagged.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=1000\n" +
            "tagged.m3u8\n"
        assertEquals("tagged.m3u8", HlsPlaylist.selectVariant(master))
    }

    @Test
    fun selectVariantReturnsNullForMediaPlaylist() {
        val media = "#EXTM3U\n" +
            "#EXT-X-VERSION:3\n" +
            "#EXTINF:10.0,\n" +
            "seg0.ts\n" +
            "#EXT-X-ENDLIST\n"
        assertNull(HlsPlaylist.selectVariant(media))
    }

    @Test
    fun selectVariantSkipsEntryWithoutFollowingUri() {
        val master = "#EXTM3U\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=9000000\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=1000\n" +
            "only.m3u8\n"
        assertEquals("only.m3u8", HlsPlaylist.selectVariant(master))
    }

    // ---- Media playlist parsing ----

    @Test
    fun parseMediaPlaylistSegmentsAndEndList() {
        val text = "#EXTM3U\n" +
            "#EXT-X-VERSION:3\n" +
            "#EXT-X-TARGETDURATION:10\n" +
            "#EXTINF:10.0,\n" +
            "seg0.ts\n" +
            "#EXTINF:10.0,\n" +
            "sub/seg1.ts\n" +
            "#EXT-X-ENDLIST\n"
        val parsed = HlsPlaylist.parseMediaPlaylist(text)
        assertEquals(listOf("seg0.ts", "sub/seg1.ts"), parsed.segments)
        assertTrue(parsed.hasEndList)
        assertFalse(parsed.encrypted)
        assertFalse(parsed.isFmp4)
        assertNull(parsed.initSegmentUri)
    }

    @Test
    fun parseMediaPlaylistLiveHasNoEndList() {
        val text = "#EXTM3U\n#EXTINF:10.0,\nseg0.ts\n"
        val parsed = HlsPlaylist.parseMediaPlaylist(text)
        assertFalse(parsed.hasEndList)
        assertEquals(listOf("seg0.ts"), parsed.segments)
    }

    @Test
    fun parseMediaPlaylistDetectsEncryptionExceptMethodNone() {
        val encrypted = "#EXTM3U\n" +
            "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n" +
            "#EXTINF:10.0,\nseg0.ts\n#EXT-X-ENDLIST\n"
        assertTrue(HlsPlaylist.parseMediaPlaylist(encrypted).encrypted)
        val none = "#EXTM3U\n" +
            "#EXT-X-KEY:METHOD=NONE\n" +
            "#EXTINF:10.0,\nseg0.ts\n#EXT-X-ENDLIST\n"
        assertFalse(HlsPlaylist.parseMediaPlaylist(none).encrypted)
    }

    @Test
    fun parseMediaPlaylistDetectsFmp4ByMapOrM4s() {
        val mapped = "#EXTM3U\n" +
            "#EXT-X-MAP:URI=\"init.mp4\"\n" +
            "#EXTINF:10.0,\nseg0.m4s\n#EXT-X-ENDLIST\n"
        val parsedMap = HlsPlaylist.parseMediaPlaylist(mapped)
        assertTrue(parsedMap.isFmp4)
        assertEquals("init.mp4", parsedMap.initSegmentUri)
        val m4sOnly = "#EXTM3U\n#EXTINF:10.0,\nseg0.m4s\n#EXT-X-ENDLIST\n"
        assertTrue(HlsPlaylist.parseMediaPlaylist(m4sOnly).isFmp4)
        val ts = "#EXTM3U\n#EXTINF:10.0,\nseg0.ts\n#EXT-X-ENDLIST\n"
        assertFalse(HlsPlaylist.parseMediaPlaylist(ts).isFmp4)
    }

    // ---- URL resolution ----

    @Test
    fun resolveUrlCases() {
        assertEquals(
            "https://cdn.example.com/hls/low/seg.ts",
            HlsPlaylist.resolveUrl("https://cdn.example.com/hls/master.m3u8", "low/seg.ts"),
        )
        assertEquals(
            "https://cdn.example.com/seg.ts",
            HlsPlaylist.resolveUrl("https://cdn.example.com/hls/master.m3u8", "/seg.ts"),
        )
        assertEquals(
            "https://other.example.com/v.m3u8",
            HlsPlaylist.resolveUrl("https://cdn.example.com/hls/master.m3u8", "https://other.example.com/v.m3u8"),
        )
    }

    // ---- Routing rule (r159, extended r160) ----

    @Test
    fun routingRuleMatrix() {
        // Listener path (media unknown): HLS and DASH go to their workers.
        assertEquals(
            BrowserDownloads.DownloadRoute.HLS_WORKER,
            BrowserDownloads.routeFor("https://example.com/hls/master.m3u8", null, true),
        )
        assertEquals(
            BrowserDownloads.DownloadRoute.DASH_WORKER,
            BrowserDownloads.routeFor("https://example.com/v/manifest.mpd", null, true),
        )
        // Finder path (media true): HLS and DASH go to their workers.
        assertEquals(
            BrowserDownloads.DownloadRoute.HLS_WORKER,
            BrowserDownloads.routeFor("https://example.com/hls/master.m3u8", true, true),
        )
        assertEquals(
            BrowserDownloads.DownloadRoute.DASH_WORKER,
            BrowserDownloads.routeFor("https://example.com/v/manifest.mpd", true, false),
        )
        // Long-press path (media false outright): playlists still plain-download.
        assertEquals(
            BrowserDownloads.DownloadRoute.PLAIN,
            BrowserDownloads.routeFor("https://example.com/hls/master.m3u8", false, true),
        )
        assertEquals(
            BrowserDownloads.DownloadRoute.PLAIN,
            BrowserDownloads.routeFor("https://example.com/v/manifest.mpd", false, true),
        )
        // Segmented: media true with the setting on segments; the
        // setting off, or media false or unknown, stays plain.
        assertEquals(
            BrowserDownloads.DownloadRoute.SEGMENTED_WORKER,
            BrowserDownloads.routeFor("https://example.com/video.mp4", true, true),
        )
        assertEquals(
            BrowserDownloads.DownloadRoute.PLAIN,
            BrowserDownloads.routeFor("https://example.com/video.mp4", true, false),
        )
        assertEquals(
            BrowserDownloads.DownloadRoute.PLAIN,
            BrowserDownloads.routeFor("https://example.com/video.mp4", false, true),
        )
        assertEquals(
            BrowserDownloads.DownloadRoute.PLAIN,
            BrowserDownloads.routeFor("https://example.com/video.mp4", null, true),
        )
        // Ordinary URLs are untouched.
        assertEquals(
            BrowserDownloads.DownloadRoute.PLAIN,
            BrowserDownloads.routeFor("https://example.com/report.pdf", null, true),
        )
        // Query strings and case do not hide a playlist or manifest URL.
        assertEquals(
            BrowserDownloads.DownloadRoute.HLS_WORKER,
            BrowserDownloads.routeFor("https://example.com/hls/master.m3u8?token=abc", null, true),
        )
        assertEquals(
            BrowserDownloads.DownloadRoute.HLS_WORKER,
            BrowserDownloads.routeFor("https://example.com/hls/MASTER.M3U8", null, true),
        )
        assertEquals(
            BrowserDownloads.DownloadRoute.DASH_WORKER,
            BrowserDownloads.routeFor("https://example.com/v/MANIFEST.MPD?token=abc", null, true),
        )
        // A playlist or manifest directory name with a non-playlist
        // file does not route.
        assertEquals(
            BrowserDownloads.DownloadRoute.PLAIN,
            BrowserDownloads.routeFor("https://example.com/x.m3u8/video.mp4", null, true),
        )
        assertEquals(
            BrowserDownloads.DownloadRoute.PLAIN,
            BrowserDownloads.routeFor("https://example.com/x.mpd/video.mp4", null, true),
        )
    }

    // ---- Library action gating ----

    @Test
    fun actionGatingMatrix() {
        val inProgress = BrowserStore.DownloadState.IN_PROGRESS
        val paused = BrowserStore.DownloadState.PAUSED
        val failed = BrowserStore.DownloadState.FAILED
        val complete = BrowserStore.DownloadState.COMPLETE
        // Worker rows: pause only while in progress, resume only
        // while paused, cancel while active, retry only when failed.
        assertTrue(BrowserDownloads.showLibraryPause(workerRecord(inProgress)))
        assertFalse(BrowserDownloads.showLibraryPause(workerRecord(paused)))
        assertFalse(BrowserDownloads.showLibraryPause(workerRecord(failed)))
        assertFalse(BrowserDownloads.showLibraryPause(workerRecord(complete)))
        assertTrue(BrowserDownloads.showLibraryResume(workerRecord(paused)))
        assertFalse(BrowserDownloads.showLibraryResume(workerRecord(inProgress)))
        assertTrue(BrowserDownloads.showLibraryCancel(workerRecord(inProgress)))
        assertTrue(BrowserDownloads.showLibraryCancel(workerRecord(paused)))
        assertFalse(BrowserDownloads.showLibraryCancel(workerRecord(failed)))
        assertFalse(BrowserDownloads.showLibraryCancel(workerRecord(complete)))
        assertTrue(BrowserDownloads.showLibraryRetry(workerRecord(failed)))
        assertFalse(BrowserDownloads.showLibraryRetry(workerRecord(inProgress)))
        assertFalse(BrowserDownloads.showLibraryRetry(workerRecord(paused)))
        assertFalse(BrowserDownloads.showLibraryRetry(workerRecord(complete)))
        // Plain rows: never a pause, resume, or cancel affordance.
        assertFalse(BrowserDownloads.showLibraryPause(plainRecord(inProgress)))
        assertFalse(BrowserDownloads.showLibraryResume(plainRecord(inProgress)))
        assertFalse(BrowserDownloads.showLibraryCancel(plainRecord(inProgress)))
        assertTrue(BrowserDownloads.showLibraryRetry(plainRecord(failed)))
        assertFalse(BrowserDownloads.showLibraryRetry(plainRecord(complete)))
    }

    // ---- Segment joining and naming ----

    @Test
    fun joinSegmentsJoinsInOrderWithInitFirst() {
        val dir = File(System.getProperty("java.io.tmpdir"), "hls-test-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val init = File(dir, "init.m4s").apply { writeBytes("INIT".toByteArray()) }
            val seg0 = File(dir, "a.ts").apply { writeBytes("aaa".toByteArray()) }
            val seg1 = File(dir, "b.ts").apply { writeBytes("bb".toByteArray()) }
            val out = File(dir, "joined.ts")
            assertTrue(joinSegments(listOf(seg0, seg1), out, init))
            assertEquals("INITaaabb", out.readText())
            val out2 = File(dir, "joined2.ts")
            assertTrue(joinSegments(listOf(seg0, seg1), out2, null))
            assertEquals("aaabb", out2.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun joinSegmentsRefusesEmptyWithoutCreatingOutput() {
        val dir = File(System.getProperty("java.io.tmpdir"), "hls-test-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val out = File(dir, "joined.ts")
            assertFalse(joinSegments(emptyList(), out, null))
            assertFalse(out.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun finalFileNameCases() {
        assertEquals("master.ts", finalFileName("master.m3u8", ".ts"))
        assertEquals("clip.mp4", finalFileName("clip", ".mp4"))
        assertEquals("clip.mp4", finalFileName("clip.mp4", ".mp4"))
        assertEquals("video.ts", finalFileName("", ".ts"))
        assertEquals("MASTER.ts", finalFileName("MASTER.M3U8", ".ts"))
    }

    @Test
    fun segmentFileNaming() {
        val dir = File(System.getProperty("java.io.tmpdir"))
        assertEquals("seg-00000.ts", segmentFile(dir, 0, false).name)
        assertEquals("seg-00012.m4s", segmentFile(dir, 12, true).name)
    }
}
