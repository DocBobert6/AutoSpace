package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Test

/** r140: the widget is retired — liveness, overlay row visibility, and the overlay turn display rule. */
class LiveSwapTest {

    @Test
    fun idleIsNotLive() {
        assertEquals(false, isLiveConversation(CarAssistant.Phase.IDLE))
    }

    @Test
    fun listeningIsLive() {
        assertEquals(true, isLiveConversation(CarAssistant.Phase.LISTENING))
    }

    @Test
    fun thinkingIsLive() {
        assertEquals(true, isLiveConversation(CarAssistant.Phase.THINKING))
    }

    @Test
    fun speakingIsLive() {
        assertEquals(true, isLiveConversation(CarAssistant.Phase.SPEAKING))
    }

    @Test
    fun errorIsLive() {
        assertEquals(true, isLiveConversation(CarAssistant.Phase.ERROR))
    }

    // r140: the overlay row shows whenever a conversation is live — no second surface gates it.

    @Test
    fun overlayRowShowsWhenLive() {
        assertEquals(true, overlayConversationRowVisible(CarAssistant.Phase.LISTENING))
        assertEquals(true, overlayConversationRowVisible(CarAssistant.Phase.THINKING))
        assertEquals(true, overlayConversationRowVisible(CarAssistant.Phase.SPEAKING))
        assertEquals(true, overlayConversationRowVisible(CarAssistant.Phase.ERROR))
    }

    @Test
    fun overlayRowHiddenWhenIdle() {
        assertEquals(false, overlayConversationRowVisible(CarAssistant.Phase.IDLE))
    }

    @Test
    fun movingDropsHistory() {
        val history = listOf(
            ConversationTurn("first question", "first answer"),
            ConversationTurn("second question", "second answer"),
        )
        val turns = overlayConversationTurns(history, "now asking", "now answering", moving = true)
        assertEquals(listOf(OverlayConversationTurn("now asking", "now answering")), turns)
    }

    @Test
    fun parkedShowsFullSessionPlusCurrent() {
        val history = listOf(
            ConversationTurn("first question", "first answer"),
            ConversationTurn("second question", "second answer"),
            ConversationTurn("third question", "third answer"),
        )
        val turns = overlayConversationTurns(history, "now asking", "now answering", moving = false)
        assertEquals(
            listOf(
                OverlayConversationTurn("first question", "first answer"),
                OverlayConversationTurn("second question", "second answer"),
                OverlayConversationTurn("third question", "third answer"),
                OverlayConversationTurn("now asking", "now answering"),
            ),
            turns,
        )
    }

    @Test
    fun blankCurrentParkedShowsHistoryOnly() {
        val history = listOf(ConversationTurn("earlier question", "earlier answer"))
        val turns = overlayConversationTurns(history, "", "", moving = false)
        assertEquals(listOf(OverlayConversationTurn("earlier question", "earlier answer")), turns)
    }

    @Test
    fun blankCurrentMovingShowsNothing() {
        val history = listOf(ConversationTurn("earlier question", "earlier answer"))
        val turns = overlayConversationTurns(history, "", "", moving = true)
        assertEquals(emptyList<OverlayConversationTurn>(), turns)
    }

    @Test
    fun emptyHistoryShowsCurrentOnly() {
        val turns = overlayConversationTurns(emptyList(), "now asking", "", moving = false)
        assertEquals(listOf(OverlayConversationTurn("now asking", "")), turns)
    }

    @Test
    fun historyOrderOldestFirst() {
        val history = listOf(
            ConversationTurn("old question", "old answer"),
            ConversationTurn("new question", "new answer"),
        )
        val turns = overlayConversationTurns(history, "current question", "current answer", moving = false)
        assertEquals("old question", turns[0].user)
        assertEquals("new question", turns[1].user)
        assertEquals("current question", turns[2].user)
    }

    @Test
    fun historyAiFlagCarried() {
        val history = listOf(
            ConversationTurn("ai question", "ai answer", fromAi = true),
            ConversationTurn("builtin question", "builtin answer", fromAi = false),
        )
        val turns = overlayConversationTurns(history, "now asking", "now answering", moving = false)
        assertEquals(true, turns[0].fromAi)
        assertEquals(false, turns[1].fromAi)
    }

    @Test
    fun currentAiFlagCarried() {
        val turns = overlayConversationTurns(emptyList(), "now asking", "now answering", moving = false, currentFromAi = true)
        assertEquals(true, turns[0].fromAi)
    }

    @Test
    fun currentBuiltinFlagDefaultsFalse() {
        val turns = overlayConversationTurns(emptyList(), "now asking", "now answering", moving = false)
        assertEquals(false, turns[0].fromAi)
    }

    // Audit §6 F3: a recorded turn must render once, never as history plus current.

    @Test
    fun recordedCurrentTurnRendersOnce() {
        val history = listOf(
            ConversationTurn("earlier question", "earlier answer"),
            ConversationTurn("now asking", "now answering"),
        )
        val turns = overlayConversationTurns(history, "now asking", "now answering", moving = false)
        assertEquals(
            listOf(
                OverlayConversationTurn("earlier question", "earlier answer"),
                OverlayConversationTurn("now asking", "now answering"),
            ),
            turns,
        )
    }

    @Test
    fun followUpWindowTurnRendersOnce() {
        // The follow-up window clears heard but keeps reply, so the
        // current turn reads ("", reply) against a recorded
        // (user, reply) — still the same exchange, still once.
        val history = listOf(ConversationTurn("now asking", "now answering"))
        val turns = overlayConversationTurns(history, "", "now answering", moving = false)
        assertEquals(listOf(OverlayConversationTurn("", "now answering")), turns)
    }

    @Test
    fun differentQuestionSameAnswerNotDeduped() {
        val history = listOf(ConversationTurn("first question", "same answer"))
        val turns = overlayConversationTurns(history, "second question", "same answer", moving = false)
        assertEquals(2, turns.size)
    }

    @Test
    fun sameQuestionDifferentAnswerNotDeduped() {
        val history = listOf(ConversationTurn("same question", "first answer"))
        val turns = overlayConversationTurns(history, "same question", "second answer", moving = false)
        assertEquals(2, turns.size)
    }
}
