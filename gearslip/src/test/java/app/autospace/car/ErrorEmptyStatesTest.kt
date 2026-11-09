package app.autospace.car

import app.autospace.media.BrowseState
import app.autospace.media.browseNoticeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r169 (audit §5 R7): the error/empty-state seams. The Compose branches themselves
 * (AgendaCard, the tab error UI, BrowsePanel, the launcher branch) are code-review
 * verified — there is no Robolectric in this tree — but every pure mapping behind
 * them is pinned here, so a wording or arm regression fails a test, not a drive.
 */
class ErrorEmptyStatesTest {

    // 5-14: sealed agenda result ---------------------------------------------------------

    @Test
    fun deniedBeatsEvents() {
        assertEquals(
            AgendaResult.Denied,
            agendaResult(false, listOf(AgendaEvent("Standup", 1L, false))),
        )
    }

    @Test
    fun deniedBeatsEmpty() {
        assertEquals(AgendaResult.Denied, agendaResult(false, emptyList()))
    }

    @Test
    fun grantedEmptyIsEmptyNotDenied() {
        assertEquals(AgendaResult.Empty, agendaResult(true, emptyList()))
    }

    @Test
    fun grantedEventsCarryTheList() {
        val events = listOf(AgendaEvent("Standup", 1L, false), AgendaEvent("Lunch", 2L, false))
        assertEquals(AgendaResult.Events(events), agendaResult(true, events))
    }

    // 5-16: browse notice ---------------------------------------------------------------

    @Test
    fun browseLoadingNotice() {
        val notice = browseNoticeFor(BrowseState(loading = true), "Spotify")
        assertEquals("Loading…", notice?.title)
        assertNull(notice?.detail)
        assertEquals(false, notice?.showRetry)
    }

    @Test
    fun browseUnavailableKeepsItsOwnWordingAndDetail() {
        val notice = browseNoticeFor(BrowseState(loading = false, unavailable = true), "Spotify")
        assertEquals("Spotify keeps its library to itself.", notice?.title)
        assertEquals(
            "Start music in the app on your phone. Play, pause, skip and seek work from here.",
            notice?.detail,
        )
        assertEquals(false, notice?.showRetry)
    }

    @Test
    fun browseFailedOffersRetry() {
        val notice = browseNoticeFor(BrowseState(loading = false, failed = true), "Spotify")
        assertEquals("Couldn't load this library.", notice?.title)
        assertEquals(true, notice?.showRetry)
    }

    @Test
    fun browseEmptyReadsAsYetToFill() {
        val notice = browseNoticeFor(BrowseState(loading = false), "Spotify")
        assertEquals("Nothing here yet.", notice?.title)
        assertEquals(false, notice?.showRetry)
    }

    @Test
    fun browseWithEntriesShowsNoNotice() {
        val state = BrowseState(
            loading = false,
            entries = listOf(
                app.autospace.media.MediaEntry(
                    "1", "Album", "", null, null, browsable = true, playable = false,
                ),
            ),
        )
        assertNull(browseNoticeFor(state, "Spotify"))
    }

    // 5-17: launcher empty-catalog branch ------------------------------------------------

    @Test
    fun emptyCatalogShowsTheBranch() {
        assertTrue(launcherShowsEmptyCatalog(emptyList()))
    }

    @Test
    fun nonEmptyCatalogShowsTheGrid() {
        assertFalse(launcherShowsEmptyCatalog(listOf(Entry("Browser", null))))
    }
}
