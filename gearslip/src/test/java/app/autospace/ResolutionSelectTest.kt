package app.autospace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolutionSelectTest {
    private val offered = listOf(
        OfferedConfig("800x480", 800, 480),
        OfferedConfig("1280x720", 1280, 720),
        OfferedConfig("1920x1080", 1920, 1080),
        OfferedConfig("2560x1440", 2560, 1440),
    )
    private val largest1080: (List<OfferedConfig>) -> Int? = { list ->
        list.withIndex().filter { (_, c) -> c.pixels <= 1920 * 1080 }.maxByOrNull { (_, c) -> c.pixels }?.index
    }

    @Test
    fun exactProfileNameWinsOverPreference() {
        val choice = ResolutionSelect.select(offered, "1280x720", "4k", false, largest1080)
        assertEquals(1, choice!!.index)
    }

    @Test
    fun preferencePicksLargestAtOrBelow() {
        val choice = ResolutionSelect.select(offered, null, "1080p", false, largest1080)
        assertEquals(2, choice!!.index)
        assertEquals("1920x1080", offered[choice.index].name)
    }

    @Test
    fun preferenceAboveAllOfferedFallsBackToFit() {
        val small = listOf(OfferedConfig("1920x1080", 1920, 1080))
        val choice = ResolutionSelect.select(small, null, "720p", false) { 0 }
        assertEquals(0, choice!!.index)
        assertTrue(choice.note.contains("not offered at or below"))
    }

    @Test
    fun wirelessCaps2kAnd4kAt1080p() {
        val choice = ResolutionSelect.select(offered, null, "4k", true, largest1080)
        assertEquals(2, choice!!.index)
        assertTrue(choice.note.contains("wireless"))
    }

    @Test
    fun autoUsesFitFallback() {
        val choice = ResolutionSelect.select(offered, null, "auto", false, largest1080)
        assertEquals(2, choice!!.index)
    }

    @Test
    fun portraitConfigsAreNotPreferencePicks() {
        val portraitOnly = listOf(OfferedConfig("720x1280", 720, 1280))
        val choice = ResolutionSelect.select(portraitOnly, null, "720p", false) { 0 }
        assertEquals(0, choice!!.index)
        assertTrue(choice.note.contains("not offered at or below"))
    }

    @Test
    fun emptyOfferedReturnsNull() {
        assertNull(ResolutionSelect.select(emptyList(), null, "1080p", false, largest1080))
    }
}
