package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** r117: place-phrase extraction and session place lookup (pure, so the unit test pins them). */
class AiPlaceTest {

    @Test fun placeAfterIn() {
        assertEquals("dry ridge", AiAssistant.placePhrase("what is the weather in dry ridge"))
    }

    @Test fun placeStripsTrailingTimeWords() {
        assertEquals("cincinnati", AiAssistant.placePhrase("will it rain in cincinnati tomorrow"))
    }

    @Test fun placeForTodayIsNone() {
        assertNull(AiAssistant.placePhrase("what is the weather for today"))
    }

    @Test fun placeWithoutPrepositionIsNone() {
        assertNull(AiAssistant.placePhrase("how hot is it"))
    }

    @Test fun hasPlaceMatchesPhrase() {
        assertEquals(true, AiAssistant.hasPlace("weather in dry ridge"))
        assertEquals(false, AiAssistant.hasPlace("weather for today"))
    }

    @Test fun sessionPlaceIsNewestUserUtterance() {
        val history = listOf(
            ConversationTurn("weather in dry ridge", "Sunny in Dry Ridge.", fromAi = false),
            ConversationTurn("tell me a joke", "Why did the chicken cross the road?", fromAi = true),
            ConversationTurn("will it snow", "Snow is unlikely.", fromAi = true),
        )
        assertEquals("dry ridge", AiAssistant.placeFromSession(history))
    }

    @Test fun sessionPlaceIgnoresAssistantProse() {
        val history = listOf(
            ConversationTurn("tell me a joke", "it rained in cincinnati yesterday", fromAi = true),
        )
        assertNull(AiAssistant.placeFromSession(history))
    }

    @Test fun sessionPlaceEmptyIsNone() {
        assertNull(AiAssistant.placeFromSession(emptyList()))
    }

    @Test fun sessionPlacePrefersLaterTurn() {
        val history = listOf(
            ConversationTurn("weather in dry ridge", "Sunny.", fromAi = false),
            ConversationTurn("weather in cincinnati", "Rainy.", fromAi = false),
        )
        assertEquals("cincinnati", AiAssistant.placeFromSession(history))
    }
}
