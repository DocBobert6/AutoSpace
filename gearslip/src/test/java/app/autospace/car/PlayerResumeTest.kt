package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerResumeTest {
    @Test fun meaningfulWindow() {
        assertFalse(PlayerResume.isMeaningful(0L, 600_000L))
        assertFalse(PlayerResume.isMeaningful(9_999L, 600_000L))
        assertTrue(PlayerResume.isMeaningful(10_000L, 600_000L))
        assertTrue(PlayerResume.isMeaningful(300_000L, 600_000L))
        // 95% of 600s is 570s: at it counts as finished, under it prompts.
        assertTrue(PlayerResume.isMeaningful(569_999L, 600_000L))
        assertFalse(PlayerResume.isMeaningful(570_000L, 600_000L))
        assertFalse(PlayerResume.isMeaningful(599_000L, 600_000L))
        // Unknown duration prompts on the 10s rule alone.
        assertTrue(PlayerResume.isMeaningful(10_000L, 0L))
        assertFalse(PlayerResume.isMeaningful(9_000L, 0L))
    }

    @Test fun codecRoundTripWithSpecialCharacters() {
        val entries = linkedMapOf(
            "https://example.com/v?a=1&b=2|weird" to PlayerResume.Entry(123_456L, 600_000L),
            "https://example.com/plain" to PlayerResume.Entry(10_000L, 0L),
        )
        val decoded = PlayerResume.decode(PlayerResume.encode(entries))
        assertEquals(entries, decoded)
    }

    @Test fun codecSkipsMalformedLines() {
        val raw = "not-a-line\n" +
            "https%3A%2F%2Fexample.com%2Fok|30000|600000\n" +
            "https%3A%2F%2Fexample.com%2Fbad-pos|abc|600000\n" +
            "https%3A%2F%2Fexample.com%2Fbad-dur|30000|xyz\n" +
            "https%3A%2F%2Fexample.com%2Fneg|-5|600000\n" +
            "|30000|600000\n"
        val decoded = PlayerResume.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals(PlayerResume.Entry(30_000L, 600_000L), decoded["https://example.com/ok"])
        assertNull(decoded["https://example.com/bad-pos"])
    }

    @Test fun codecKeepsInsertionOrderAndOverwritesDuplicates() {
        val raw = "a|10000|60000\nb|20000|60000\na|30000|60000\n"
        val decoded = PlayerResume.decode(raw)
        assertEquals(listOf("b", "a"), decoded.keys.toList())
        assertEquals(PlayerResume.Entry(30_000L, 60_000L), decoded["a"])
    }
}
