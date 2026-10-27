package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Test

/** r76 A58 Live conversation widget swap: gate, liveness, labels, turn display rule. */
class LiveSwapTest {

    @Test
    fun gateBothOnSwaps() {
        assertEquals(true, liveSwapNow(true, true))
    }

    @Test
    fun gateExperimentalOffNoSwap() {
        assertEquals(false, liveSwapNow(false, true))
    }

    @Test
    fun gateToggleOffNoSwap() {
        assertEquals(false, liveSwapNow(true, false))
    }

    @Test
    fun gateBothOffNoSwap() {
        assertEquals(false, liveSwapNow(false, false))
    }

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

    @Test
    fun phaseLabelListening() {
        assertEquals("Listening", livePhaseLabel(CarAssistant.Phase.LISTENING, false))
    }

    @Test
    fun phaseLabelStillListening() {
        assertEquals("Still listening", livePhaseLabel(CarAssistant.Phase.LISTENING, true))
    }

    @Test
    fun phaseLabelThinking() {
        assertEquals("Thinking", livePhaseLabel(CarAssistant.Phase.THINKING, false))
    }

    @Test
    fun phaseLabelSpeaking() {
        assertEquals("Speaking", livePhaseLabel(CarAssistant.Phase.SPEAKING, false))
    }

    @Test
    fun phaseLabelError() {
        assertEquals("Error", livePhaseLabel(CarAssistant.Phase.ERROR, false))
    }

    @Test
    fun phaseLabelIdleBlank() {
        assertEquals("", livePhaseLabel(CarAssistant.Phase.IDLE, false))
    }

    @Test
    fun emptyBodyListeningReady() {
        assertEquals("Listening…", liveEmptyBody(CarAssistant.Phase.LISTENING, true))
    }

    @Test
    fun emptyBodyListeningNotReady() {
        assertEquals("One moment…", liveEmptyBody(CarAssistant.Phase.LISTENING, false))
    }

    @Test
    fun emptyBodyThinking() {
        assertEquals("…", liveEmptyBody(CarAssistant.Phase.THINKING, true))
    }

    @Test
    fun emptyBodySpeakingBlank() {
        assertEquals("", liveEmptyBody(CarAssistant.Phase.SPEAKING, true))
    }

    @Test
    fun emptyBodyIdleBlank() {
        assertEquals("", liveEmptyBody(CarAssistant.Phase.IDLE, false))
    }

    @Test
    fun movingDropsHistory() {
        val history = listOf(
            ConversationTurn("first question", "first answer"),
            ConversationTurn("second question", "second answer"),
        )
        val turns = liveWidgetTurns(history, "now asking", "now answering", moving = true)
        assertEquals(listOf(LiveWidgetTurn("now asking", "now answering")), turns)
    }

    @Test
    fun parkedShowsLastTwoPlusCurrent() {
        val history = listOf(
            ConversationTurn("first question", "first answer"),
            ConversationTurn("second question", "second answer"),
            ConversationTurn("third question", "third answer"),
        )
        val turns = liveWidgetTurns(history, "now asking", "now answering", moving = false)
        assertEquals(
            listOf(
                LiveWidgetTurn("second question", "second answer"),
                LiveWidgetTurn("third question", "third answer"),
                LiveWidgetTurn("now asking", "now answering"),
            ),
            turns,
        )
    }

    @Test
    fun blankCurrentParkedShowsHistoryOnly() {
        val history = listOf(ConversationTurn("earlier question", "earlier answer"))
        val turns = liveWidgetTurns(history, "", "", moving = false)
        assertEquals(listOf(LiveWidgetTurn("earlier question", "earlier answer")), turns)
    }

    @Test
    fun blankCurrentMovingShowsNothing() {
        val history = listOf(ConversationTurn("earlier question", "earlier answer"))
        val turns = liveWidgetTurns(history, "", "", moving = true)
        assertEquals(emptyList<LiveWidgetTurn>(), turns)
    }

    @Test
    fun emptyHistoryShowsCurrentOnly() {
        val turns = liveWidgetTurns(emptyList(), "now asking", "", moving = false)
        assertEquals(listOf(LiveWidgetTurn("now asking", "")), turns)
    }

    @Test
    fun historyOrderOldestFirst() {
        val history = listOf(
            ConversationTurn("old question", "old answer"),
            ConversationTurn("new question", "new answer"),
        )
        val turns = liveWidgetTurns(history, "current question", "current answer", moving = false)
        assertEquals("old question", turns[0].user)
        assertEquals("new question", turns[1].user)
        assertEquals("current question", turns[2].user)
    }
}
