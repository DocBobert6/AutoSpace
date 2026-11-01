package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for the r160 downloads slice (DASH +
 * segmented + settings + backfill; no Android framework needed):
 * the new engine kinds and the WAITING state in the codec, the
 * generalized worker action gating, backfill matching, segment
 * math, worker file naming, and the Wi-Fi gate. The WorkManager
 * scheduling, the Transformer remux, the network halves, and the
 * MediaStore query itself rest on CI compilation plus the device
 * checks in the round report.
 */
class DownloadSlice4Test {

    private fun workerRecord(kind: BrowserStore.DownloadKind, state: BrowserStore.DownloadState) =
        BrowserStore.DownloadRecord(
            id = "w-1",
            url = "https://example.com/v/manifest.mpd",
            fileName = "manifest",
            state = state,
            downloadManagerId = -1L,
            localSource = null,
            isPrivate = false,
            createdAtMs = 1_000_000L,
            isMedia = true,
            progress = -1,
            kind = kind,
        )

    // ---- Codec: new kinds and the WAITING state ----

    @Test
    fun codecRoundTripPreservesDashSegmentedAndWaiting() {
        val dash = workerRecord(BrowserStore.DownloadKind.DASH, BrowserStore.DownloadState.PAUSED)
            .copy(progress = 60)
        val segmented = workerRecord(
            BrowserStore.DownloadKind.SEGMENTED,
            BrowserStore.DownloadState.IN_PROGRESS,
        ).copy(fileName = "clip.mp4", progress = 25)
        val waiting = BrowserStore.DownloadRecord(
            id = "dl-wait",
            url = "https://example.com/report.pdf",
            fileName = "report.pdf",
            state = BrowserStore.DownloadState.WAITING,
            downloadManagerId = -1L,
            localSource = null,
            isPrivate = false,
            createdAtMs = 2_000_000L,
        )
        val decoded = BrowserStore.decodeDownloads(
            BrowserStore.encodeDownloads(listOf(dash, segmented, waiting)),
        )
        assertEquals(3, decoded.size)
        assertEquals(dash, decoded[0])
        assertEquals(segmented, decoded[1])
        assertEquals(waiting, decoded[2])
    }

    @Test
    fun isWorkerKindCoversAllThreeWorkersOnly() {
        assertTrue(BrowserDownloads.isWorkerKind(BrowserStore.DownloadKind.HLS))
        assertTrue(BrowserDownloads.isWorkerKind(BrowserStore.DownloadKind.DASH))
        assertTrue(BrowserDownloads.isWorkerKind(BrowserStore.DownloadKind.SEGMENTED))
        assertFalse(BrowserDownloads.isWorkerKind(BrowserStore.DownloadKind.PLAIN))
    }

    // ---- Generalized action gating ----

    @Test
    fun actionGatingCoversDashAndSegmented() {
        val inProgress = BrowserStore.DownloadState.IN_PROGRESS
        val paused = BrowserStore.DownloadState.PAUSED
        val complete = BrowserStore.DownloadState.COMPLETE
        for (kind in listOf(BrowserStore.DownloadKind.DASH, BrowserStore.DownloadKind.SEGMENTED)) {
            assertTrue(BrowserDownloads.showLibraryPause(workerRecord(kind, inProgress)))
            assertFalse(BrowserDownloads.showLibraryPause(workerRecord(kind, paused)))
            assertTrue(BrowserDownloads.showLibraryResume(workerRecord(kind, paused)))
            assertFalse(BrowserDownloads.showLibraryResume(workerRecord(kind, inProgress)))
            assertTrue(BrowserDownloads.showLibraryCancel(workerRecord(kind, inProgress)))
            assertTrue(BrowserDownloads.showLibraryCancel(workerRecord(kind, paused)))
            assertFalse(BrowserDownloads.showLibraryCancel(workerRecord(kind, complete)))
        }
    }

    // ---- Backfill matching ----

    @Test
    fun backfillMatchUniqueInWindowOnly() {
        val createdAt = 10_000_000L
        val dayMs = 24L * 60L * 60L * 1000L
        val hit = BackfillCandidate("clip.mp4", createdAt + dayMs, "content://media/external/downloads/7")
        // Exactly one in-window match recovers.
        assertEquals(
            "content://media/external/downloads/7",
            backfillMatch(listOf(hit), "clip.mp4", createdAt),
        )
        // Zero matches refuse.
        assertNull(backfillMatch(emptyList(), "clip.mp4", createdAt))
        // Two in-window matches refuse — never guess.
        val second = BackfillCandidate("clip.mp4", createdAt + 2L * dayMs, "content://media/external/downloads/8")
        assertNull(backfillMatch(listOf(hit, second), "clip.mp4", createdAt))
        // A match outside the window does not count, even alone.
        val stale = BackfillCandidate("clip.mp4", createdAt + 2L * dayMs + 1L, "content://media/external/downloads/9")
        assertNull(backfillMatch(listOf(stale), "clip.mp4", createdAt))
        // A different name does not count, even in-window.
        val other = BackfillCandidate("other.mp4", createdAt, "content://media/external/downloads/10")
        assertNull(backfillMatch(listOf(other), "clip.mp4", createdAt))
        // The window bounds are inclusive: exactly two days out still matches.
        val edge = BackfillCandidate("clip.mp4", createdAt - 2L * dayMs, "content://media/external/downloads/11")
        assertEquals(
            "content://media/external/downloads/11",
            backfillMatch(listOf(edge), "clip.mp4", createdAt),
        )
        // An out-of-window row beside an in-window one does not
        // create ambiguity: only in-window rows count.
        assertEquals(
            "content://media/external/downloads/7",
            backfillMatch(listOf(hit, stale), "clip.mp4", createdAt),
        )
    }

    // ---- Segment math ----

    @Test
    fun segmentRangesCoverExactly() {
        val oneMb = 1024L * 1024L
        assertEquals(
            listOf(0L..oneMb - 1L, oneMb..2L * oneMb - 1L),
            segmentRanges(2L * oneMb, oneMb),
        )
        // A remainder makes one short final part, never a gap or overlap.
        val ranges = segmentRanges(2L * oneMb + 500L, oneMb)
        assertEquals(3, ranges.size)
        assertEquals(0L..oneMb - 1L, ranges[0])
        assertEquals(oneMb..2L * oneMb - 1L, ranges[1])
        assertEquals(2L * oneMb..2L * oneMb + 499L, ranges[2])
        // A total smaller than one part is a single range.
        assertEquals(listOf(0L..499L), segmentRanges(500L, oneMb))
        // Non-positive inputs mean "no ranges", not a crash.
        assertTrue(segmentRanges(0L, oneMb).isEmpty())
        assertTrue(segmentRanges(-5L, oneMb).isEmpty())
        assertTrue(segmentRanges(1000L, 0L).isEmpty())
    }

    @Test
    fun segmentPartFileNaming() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"))
        assertEquals("part-00000.bin", segmentPartFile(dir, 0).name)
        assertEquals("part-00012.bin", segmentPartFile(dir, 12).name)
    }

    // ---- Worker file naming ----

    @Test
    fun workerFinalFileNameCases() {
        assertEquals("manifest.mp4", workerFinalFileName("manifest.mpd", ".mpd", ".mp4"))
        assertEquals("MANIFEST.mp4", workerFinalFileName("MANIFEST.MPD", ".mpd", ".mp4"))
        assertEquals("clip.mp4", workerFinalFileName("clip.mp4", ".mpd", ".mp4"))
        assertEquals("clip.mp4", workerFinalFileName("clip", ".mpd", ".mp4"))
        assertEquals("video.mp4", workerFinalFileName("", ".mpd", ".mp4"))
        assertEquals("video.mp4", workerFinalFileName(".mpd", ".mpd", ".mp4"))
    }

    // ---- Wi-Fi gate ----

    @Test
    fun shouldWaitForWifiMatrix() {
        // Only the combination of the setting on and a metered network waits.
        assertTrue(shouldWaitForWifi(true, false))
        assertFalse(shouldWaitForWifi(true, true))
        assertFalse(shouldWaitForWifi(false, false))
        assertFalse(shouldWaitForWifi(false, true))
    }
}
