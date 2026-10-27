package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationHistoryTest {

    @Test fun recordThenSnapshotReturnsInOrder() {
        val history = ConversationHistory()
        history.record("what is the weather", "Sunny and warm.", 1000L)
        history.record("and tomorrow", "Rain in the afternoon.", 2000L)
        val turns = history.snapshot(3000L)
        assertEquals(2, turns.size)
        assertEquals("what is the weather", turns[0].user)
        assertEquals("Sunny and warm.", turns[0].assistant)
        assertEquals("and tomorrow", turns[1].user)
    }

    @Test fun turnCapDropsOldest() {
        val history = ConversationHistory()
        for (i in 1..9) {
            history.record("question $i", "answer $i", 1000L + i)
        }
        val turns = history.snapshot(2000L)
        assertEquals(ConversationHistory.MAX_TURNS, turns.size)
        assertEquals("question 2", turns.first().user)
        assertEquals("question 9", turns.last().user)
    }

    @Test fun charCapDropsOldest() {
        val history = ConversationHistory()
        val big = "x".repeat(5000)
        history.record("first", big, 1000L)
        history.record("second", big, 2000L)
        history.record("third", big, 3000L)
        val turns = history.snapshot(4000L)
        val total = turns.sumOf { it.user.length + it.assistant.length }
        assertTrue(total <= ConversationHistory.MAX_HISTORY_CHARS)
        assertEquals("third", turns.last().user)
        assertFalse(turns.any { it.user == "first" })
    }

    @Test fun timeoutClears() {
        val history = ConversationHistory()
        history.record("hello", "Hi there.", 1000L)
        val expired = history.snapshot(1000L + ConversationHistory.SESSION_TIMEOUT_MS + 1L)
        assertTrue(expired.isEmpty())
        assertTrue(history.snapshot(1000L + ConversationHistory.SESSION_TIMEOUT_MS + 2L).isEmpty())
    }

    @Test fun withinTimeoutKeeps() {
        val history = ConversationHistory()
        history.record("hello", "Hi there.", 1000L)
        val kept = history.snapshot(1000L + ConversationHistory.SESSION_TIMEOUT_MS)
        assertEquals(1, kept.size)
    }

    @Test fun clearEmpties() {
        val history = ConversationHistory()
        history.record("hello", "Hi there.", 1000L)
        history.clear()
        assertTrue(history.isEmpty())
        assertTrue(history.snapshot(2000L).isEmpty())
    }

    @Test fun blankTurnsIgnored() {
        val history = ConversationHistory()
        history.record("", "Hi there.", 1000L)
        history.record("hello", "", 2000L)
        history.record("   ", "   ", 3000L)
        assertTrue(history.isEmpty())
    }

}
