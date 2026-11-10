package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * r220: durable AI memory — pure parts only. Storage needs a Context
 * (SharedPreferences), so it is not JVM-testable here; the codec, the
 * fact extraction, the weather-at-home rule, and the spoken summary
 * are pure and pinned instead — the same split as BookAutoShelf's
 * shouldAutoShelf.
 */
class AiMemoryTest {

    @Test
    fun `fact codec round-trips both kinds and survives equals and backslash`() {
        val facts = listOf(
            AiMemory.Fact(AiMemory.FactKind.HOME_PLACE, "dry ridge"),
            AiMemory.Fact(AiMemory.FactKind.NAME, "robert"),
        )
        assertEquals(facts, AiMemory.decodeFacts(AiMemory.encodeFacts(facts)))
        val tricky = listOf(AiMemory.Fact(AiMemory.FactKind.NAME, "a=b\\c"))
        assertEquals(tricky, AiMemory.decodeFacts(AiMemory.encodeFacts(tricky)))
    }

    @Test
    fun `fact codec skips malformed lines and reads empty`() {
        assertEquals(emptyList<AiMemory.Fact>(), AiMemory.decodeFacts(""))
        assertEquals(emptyList<AiMemory.Fact>(), AiMemory.decodeFacts("junk\nHOME_PLACE=\nNOPE=x"))
    }

    @Test
    fun `summary codec round-trips and reads malformed as empty`() {
        val s = AiMemory.Summary(12, 1790000000000L, listOf("cincinnati", "dry ridge"))
        assertEquals(s, AiMemory.decodeSummary(AiMemory.encodeSummary(s)))
        assertEquals(AiMemory.Summary.EMPTY, AiMemory.decodeSummary(""))
        assertEquals(AiMemory.Summary.EMPTY, AiMemory.decodeSummary("junk"))
        assertEquals(AiMemory.Summary.EMPTY, AiMemory.decodeSummary("x|y|z"))
    }

    @Test
    fun `extractFact learns home place and name`() {
        assertEquals(
            AiMemory.Fact(AiMemory.FactKind.HOME_PLACE, "dry ridge"),
            AiMemory.extractFact("my home is in dry ridge"),
        )
        assertEquals(
            AiMemory.Fact(AiMemory.FactKind.HOME_PLACE, "cincinnati"),
            AiMemory.extractFact("i live in cincinnati"),
        )
        assertEquals(
            AiMemory.Fact(AiMemory.FactKind.HOME_PLACE, "florence kentucky"),
            AiMemory.extractFact("home is in florence kentucky"),
        )
        assertEquals(
            AiMemory.Fact(AiMemory.FactKind.HOME_PLACE, "dry ridge"),
            AiMemory.extractFact("my home is in dry ridge tomorrow"),
        )
        assertEquals(
            AiMemory.Fact(AiMemory.FactKind.NAME, "robert"),
            AiMemory.extractFact("my name is robert"),
        )
        assertEquals(
            AiMemory.Fact(AiMemory.FactKind.NAME, "bob"),
            AiMemory.extractFact("call me bob"),
        )
    }

    @Test
    fun `extractFact ignores commands and near-misses`() {
        assertNull(AiMemory.extractFact("navigate home"))
        assertNull(AiMemory.extractFact("weather in cincinnati"))
        assertNull(AiMemory.extractFact("call mom"))
        assertNull(AiMemory.extractFact("call me a taxi"))
        assertNull(AiMemory.extractFact("call me when you get home"))
        assertNull(AiMemory.extractFact("call me later"))
        assertNull(AiMemory.extractFact("open home"))
        assertNull(AiMemory.extractFact("what is my name"))
    }

    @Test
    fun `homeWeatherPlace fires only for a home weather question`() {
        assertEquals("dry ridge", AiMemory.homeWeatherPlace("what's the weather at home", "dry ridge"))
        assertEquals("dry ridge", AiMemory.homeWeatherPlace("how humid is it at home", "dry ridge"))
        assertEquals("dry ridge", AiMemory.homeWeatherPlace("what's the weather near home", "dry ridge"))
        assertNull(AiMemory.homeWeatherPlace("what's the weather", "dry ridge"))
        assertNull(AiMemory.homeWeatherPlace("weather in cincinnati", "dry ridge"))
        assertNull(AiMemory.homeWeatherPlace("weather at home depot", "dry ridge"))
        assertNull(AiMemory.homeWeatherPlace("what's the weather at home", null))
        assertNull(AiMemory.homeWeatherPlace("navigate home", "dry ridge"))
    }

    @Test
    fun `summarize states facts and the turn count, or nothing yet`() {
        assertEquals(
            "I don't remember anything yet.",
            AiMemory.summarize(emptyList(), AiMemory.Summary.EMPTY),
        )
        val facts = listOf(
            AiMemory.Fact(AiMemory.FactKind.HOME_PLACE, "dry ridge"),
            AiMemory.Fact(AiMemory.FactKind.NAME, "robert"),
        )
        assertEquals(
            "Home is in dry ridge. Your name is robert. From 12 turns.",
            AiMemory.summarize(facts, AiMemory.Summary(12, 1790000000000L, listOf("cincinnati"))),
        )
        assertEquals(
            "Home is in dry ridge. From 1 turn.",
            AiMemory.summarize(facts.take(1), AiMemory.Summary(1, 0L, emptyList())),
        )
    }
}
