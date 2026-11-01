package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for the r157 Downloads list and the r158
 * Offline library (no Android framework needed): the record codec,
 * the private-origin and media list filters, the MIME classification,
 * the library-play decision, and the retry gate + transition. The
 * Context-taking store ops (reads/writes through AppSettings) and the
 * DownloadManager queries are not unit-testable in this tree's
 * plain-JUnit setup; they rest on CI compilation plus the device
 * checks in the round report.
 */
class DownloadStoreTest {

    private fun record(
        id: String = "dl-1",
        state: BrowserStore.DownloadState = BrowserStore.DownloadState.IN_PROGRESS,
        isPrivate: Boolean = false,
        isMedia: Boolean = false,
        progress: Int = -1,
        kind: BrowserStore.DownloadKind = BrowserStore.DownloadKind.PLAIN,
    ) = BrowserStore.DownloadRecord(
        id = id,
        url = "https://example.com/report.pdf",
        fileName = "report.pdf",
        state = state,
        downloadManagerId = 42L,
        localSource = null,
        isPrivate = isPrivate,
        createdAtMs = 1_000_000L,
        isMedia = isMedia,
        progress = progress,
        kind = kind,
    )

    @Test
    fun codecRoundTripPreservesEveryField() {
        val complete = record(id = "dl-9", state = BrowserStore.DownloadState.COMPLETE)
            .copy(localSource = "content://media/external/downloads/7", isPrivate = true)
        val decoded = BrowserStore.decodeDownloads(BrowserStore.encodeDownloads(listOf(record(), complete)))
        assertEquals(2, decoded.size)
        assertEquals(record(), decoded[0])
        assertEquals(complete, decoded[1])
    }

    @Test
    fun codecSkipsMalformedRowsAndDefaultsMissingFields() {
        // Whole-store garbage decodes to empty (the codec never guesses
        // at a broken store); row-level tolerance covers blank ids,
        // short rows, and unknown states inside line input.
        assertTrue(BrowserStore.decodeDownloads("garbage").isEmpty())
        val raw = "|https://example.com/a.pdf|a.pdf|IN_PROGRESS|1||0|1\n" +
            "dl-ok|https://example.com/b.pdf|b.pdf|IN_PROGRESS|||0|1000000\n" +
            "short|row\n" +
            "dl-state|https://example.com/c.pdf|c.pdf|BOGUS|99|content://x|1|5"
        val decoded = BrowserStore.decodeDownloads(raw)
        assertEquals(listOf("dl-ok", "dl-state"), decoded.map { it.id })
        assertEquals(BrowserStore.DownloadState.IN_PROGRESS, decoded[0].state)
        assertEquals(-1L, decoded[0].downloadManagerId)
        assertEquals(BrowserStore.DownloadState.IN_PROGRESS, decoded[1].state)
        assertTrue(decoded[1].isPrivate)
    }

    @Test
    fun codecEmptyAndBlankDecodeToEmpty() {
        assertTrue(BrowserStore.decodeDownloads("").isEmpty())
        assertTrue(BrowserStore.decodeDownloads("   ").isEmpty())
    }

    @Test
    fun visibleDownloadsExcludesPrivateOriginsOnly() {
        val records = listOf(
            record(id = "dl-1"),
            record(id = "dl-2", isPrivate = true),
            record(id = "dl-3", state = BrowserStore.DownloadState.FAILED),
            record(id = "dl-4", state = BrowserStore.DownloadState.COMPLETE, isPrivate = true),
        )
        assertEquals(
            listOf("dl-1", "dl-3"),
            BrowserStore.visibleDownloads(records).map { it.id },
        )
    }

    @Test
    fun retryGateBlocksCompletedOnly() {
        assertFalse(BrowserStore.retryable(record(state = BrowserStore.DownloadState.COMPLETE)))
        assertTrue(BrowserStore.retryable(record(state = BrowserStore.DownloadState.IN_PROGRESS)))
        assertTrue(BrowserStore.retryable(record(state = BrowserStore.DownloadState.FAILED)))
    }

    @Test
    fun retriedKeepsIdentityAndResetsProgress() {
        val failed = record(state = BrowserStore.DownloadState.FAILED)
            .copy(localSource = "content://media/external/downloads/7")
        val retried = BrowserStore.retried(failed, 99L)
        assertEquals(failed.id, retried.id)
        assertEquals(failed.url, retried.url)
        assertEquals(failed.fileName, retried.fileName)
        assertEquals(failed.isPrivate, retried.isPrivate)
        assertEquals(failed.createdAtMs, retried.createdAtMs)
        assertEquals(BrowserStore.DownloadState.IN_PROGRESS, retried.state)
        assertEquals(99L, retried.downloadManagerId)
        assertEquals(null, retried.localSource)
    }

    // ---- r158: Offline library ----

    @Test
    fun codecRoundTripPreservesMediaFlagAndPreFlagRowsDecodeAsOrdinary() {
        val media = record(id = "dl-media", state = BrowserStore.DownloadState.COMPLETE, isMedia = true)
            .copy(localSource = "file:///storage/emulated/0/Download/song.mp3")
        val decoded = BrowserStore.decodeDownloads(BrowserStore.encodeDownloads(listOf(record(), media)))
        assertEquals(2, decoded.size)
        assertFalse(decoded[0].isMedia)
        assertEquals(media, decoded[1])
        // A pre-flag (8-field) row decodes with media false, so slice-1
        // records stay ordinary files after the upgrade.
        val preFlag = "dl-old|https://example.com/a.mp3|a.mp3|COMPLETE|7|file:///storage/emulated/0/Download/a.mp3|0|1000000"
        val old = BrowserStore.decodeDownloads(preFlag)
        assertEquals(1, old.size)
        assertFalse(old[0].isMedia)
        // A 9-field row carries the flag both ways.
        val flagged = "dl-new|https://example.com/b.mp3|b.mp3|COMPLETE|8|file:///storage/emulated/0/Download/b.mp3|0|1000000|1"
        assertTrue(BrowserStore.decodeDownloads(flagged)[0].isMedia)
        val unflagged = flagged.dropLast(1) + "0"
        assertFalse(BrowserStore.decodeDownloads(unflagged)[0].isMedia)
    }

    @Test
    fun visibleDownloadsExcludesMediaRecords() {
        val records = listOf(
            record(id = "dl-1"),
            record(id = "dl-2", isMedia = true),
            record(id = "dl-3", isMedia = true, isPrivate = true),
            record(id = "dl-4", state = BrowserStore.DownloadState.COMPLETE, isMedia = true),
        )
        assertEquals(listOf("dl-1"), BrowserStore.visibleDownloads(records).map { it.id })
    }

    @Test
    fun visibleLibraryListsMediaNonPrivateOnly() {
        val records = listOf(
            record(id = "dl-1", isMedia = true),
            record(id = "dl-2", isMedia = true, isPrivate = true),
            record(id = "dl-3"),
            record(id = "dl-4", isPrivate = true),
            record(id = "dl-5", state = BrowserStore.DownloadState.COMPLETE, isMedia = true),
            record(id = "dl-6", state = BrowserStore.DownloadState.FAILED, isMedia = true),
        )
        // Any state qualifies: the list shows media rows, and the row
        // actions toast instead of playing when a row is not ready.
        assertEquals(
            listOf("dl-1", "dl-5", "dl-6"),
            BrowserStore.visibleLibrary(records).map { it.id },
        )
    }

    @Test
    fun isMediaMimeClassification() {
        assertTrue(BrowserDownloads.isMediaMime("video/mp4"))
        assertTrue(BrowserDownloads.isMediaMime("audio/mpeg"))
        assertTrue(BrowserDownloads.isMediaMime("Video/MP4"))
        assertTrue(BrowserDownloads.isMediaMime("audio/mpeg; charset=utf-8"))
        assertFalse(BrowserDownloads.isMediaMime("image/png"))
        assertFalse(BrowserDownloads.isMediaMime("text/html"))
        assertFalse(BrowserDownloads.isMediaMime("application/pdf"))
        assertFalse(BrowserDownloads.isMediaMime(""))
        assertFalse(BrowserDownloads.isMediaMime(null))
    }

    @Test
    fun libraryPlayDecisionMatrix() {
        val complete = BrowserStore.DownloadState.COMPLETE
        val progress = BrowserStore.DownloadState.IN_PROGRESS
        val failed = BrowserStore.DownloadState.FAILED
        assertEquals(BrowserDownloads.LibraryResult.READY, BrowserDownloads.libraryPlayDecision(complete, true, true))
        assertEquals(BrowserDownloads.LibraryResult.NO_FILE, BrowserDownloads.libraryPlayDecision(complete, true, false))
        assertEquals(BrowserDownloads.LibraryResult.NO_FILE, BrowserDownloads.libraryPlayDecision(complete, false, false))
        assertEquals(BrowserDownloads.LibraryResult.NOT_READY, BrowserDownloads.libraryPlayDecision(progress, false, false))
        assertEquals(BrowserDownloads.LibraryResult.NOT_READY, BrowserDownloads.libraryPlayDecision(progress, true, true))
        assertEquals(BrowserDownloads.LibraryResult.FAILED, BrowserDownloads.libraryPlayDecision(failed, false, false))
        assertEquals(BrowserDownloads.LibraryResult.FAILED, BrowserDownloads.libraryPlayDecision(failed, true, true))
    }

    @Test
    fun retriedPreservesMediaFlag() {
        val failed = record(state = BrowserStore.DownloadState.FAILED, isMedia = true)
        assertTrue(BrowserStore.retried(failed, 99L).isMedia)
    }

    // ---- r159: HLS worker records ----

    @Test
    fun codecRoundTripPreservesProgressAndKind() {
        val worker = record(
            id = "dl-hls",
            state = BrowserStore.DownloadState.IN_PROGRESS,
            isMedia = true,
            progress = 42,
            kind = BrowserStore.DownloadKind.HLS,
        ).copy(downloadManagerId = -1L)
        val decoded = BrowserStore.decodeDownloads(BrowserStore.encodeDownloads(listOf(record(), worker)))
        assertEquals(2, decoded.size)
        assertEquals(record(), decoded[0])
        assertEquals(worker, decoded[1])
        assertEquals(42, decoded[1].progress)
        assertEquals(BrowserStore.DownloadKind.HLS, decoded[1].kind)
    }

    @Test
    fun codecPre159RowsDefaultProgressUnknownAndPlain() {
        // 8-field (slice 1) and 9-field (slice 2) rows decode with
        // unknown progress and the plain engine, so old records stay
        // plain DownloadManager downloads after the upgrade.
        val eight = "dl-old|https://example.com/a.mp4|a.mp4|COMPLETE|7|file:///storage/emulated/0/Download/a.mp4|0|1000000"
        val nine = "dl-mid|https://example.com/b.mp4|b.mp4|COMPLETE|8|file:///storage/emulated/0/Download/b.mp4|0|1000000|1"
        val decoded = BrowserStore.decodeDownloads("$eight\n$nine")
        assertEquals(2, decoded.size)
        assertEquals(-1, decoded[0].progress)
        assertEquals(BrowserStore.DownloadKind.PLAIN, decoded[0].kind)
        assertEquals(-1, decoded[1].progress)
        assertEquals(BrowserStore.DownloadKind.PLAIN, decoded[1].kind)
        assertTrue(decoded[1].isMedia)
        // An 11-field row with an unknown kind name degrades to plain.
        // (DASH became a real kind in r160, so the bogus name here
        // is one no engine uses.)
        val bogusKind = "dl-bogus|https://example.com/c.mp4|c.mp4|FAILED|9||0|1000000|1|50|MERGE"
        assertEquals(BrowserStore.DownloadKind.PLAIN, BrowserStore.decodeDownloads(bogusKind)[0].kind)
        assertEquals(50, BrowserStore.decodeDownloads(bogusKind)[0].progress)
    }

    @Test
    fun pausedStateRoundTrips() {
        val paused = record(
            state = BrowserStore.DownloadState.PAUSED,
            isMedia = true,
            progress = 60,
            kind = BrowserStore.DownloadKind.HLS,
        ).copy(downloadManagerId = -1L)
        val decoded = BrowserStore.decodeDownloads(BrowserStore.encodeDownloads(listOf(paused)))
        assertEquals(1, decoded.size)
        assertEquals(paused, decoded[0])
    }

    @Test
    fun retriedWorkerKeepsIdentityKindMediaAndProgress() {
        val failed = record(
            state = BrowserStore.DownloadState.FAILED,
            isMedia = true,
            progress = 37,
            kind = BrowserStore.DownloadKind.HLS,
        ).copy(downloadManagerId = -1L, localSource = "content://media/external/downloads/9")
        val retried = BrowserStore.retriedWorker(failed)
        assertEquals(failed.id, retried.id)
        assertEquals(failed.url, retried.url)
        assertEquals(failed.fileName, retried.fileName)
        assertEquals(failed.isPrivate, retried.isPrivate)
        assertEquals(failed.createdAtMs, retried.createdAtMs)
        assertTrue(retried.isMedia)
        assertEquals(37, retried.progress)
        assertEquals(BrowserStore.DownloadKind.HLS, retried.kind)
        assertEquals(-1L, retried.downloadManagerId)
        assertEquals(BrowserStore.DownloadState.IN_PROGRESS, retried.state)
        assertEquals(null, retried.localSource)
    }
}
