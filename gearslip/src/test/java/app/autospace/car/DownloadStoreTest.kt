package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for the r157 Downloads list (no Android framework
 * needed): the record codec, the private-origin list filter, and the
 * retry gate + transition. The Context-taking store ops (reads/writes
 * through AppSettings) and the DownloadManager queries are not
 * unit-testable in this tree's plain-JUnit setup; they rest on CI
 * compilation plus the device checks in the round report.
 */
class DownloadStoreTest {

    private fun record(
        id: String = "dl-1",
        state: BrowserStore.DownloadState = BrowserStore.DownloadState.IN_PROGRESS,
        isPrivate: Boolean = false,
    ) = BrowserStore.DownloadRecord(
        id = id,
        url = "https://example.com/report.pdf",
        fileName = "report.pdf",
        state = state,
        downloadManagerId = 42L,
        localSource = null,
        isPrivate = isPrivate,
        createdAtMs = 1_000_000L,
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
        // Whole-array invalid JSON decodes to empty (the codec never
        // guesses at a broken store); row-level tolerance covers blank
        // ids/urls and unknown states inside valid JSON.
        assertTrue(BrowserStore.decodeDownloads("[not json").isEmpty())
        val raw = "[{\"id\":\"\",\"url\":\"https://example.com/a.pdf\"}," +
            "{\"id\":\"dl-ok\",\"url\":\"https://example.com/b.pdf\"}," +
            "{\"id\":\"dl-state\",\"url\":\"https://example.com/c.pdf\",\"state\":\"BOGUS\"}]"
        val decoded = BrowserStore.decodeDownloads(raw)
        assertEquals(listOf("dl-ok", "dl-state"), decoded.map { it.id })
        assertEquals(BrowserStore.DownloadState.IN_PROGRESS, decoded[0].state)
        assertEquals(-1L, decoded[0].downloadManagerId)
        assertEquals(BrowserStore.DownloadState.IN_PROGRESS, decoded[1].state)
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
}
