package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * r49 regression tests for the video-audit stale-base defect: inserts must land on the
 * CURRENT text, never the snapshot from when the key's pointerInput block was created.
 *
 * The handler is built ONCE (like the pointerInput block, which restarts only when
 * hint/label change) and the provider is mutated between calls (like keystrokes landing
 * without a restart). Under the old snapshot lambdas, the second append below computed
 * from the construction-time string — "qe" after "qw", "..1235" after "..1234" — exactly
 * the video's signature. [KeyInsert] reads through its provider at insert time, so the
 * same sequence accumulates; the Compose wiring feeds that provider from
 * rememberUpdatedState, which is the framework's own freshness guarantee.
 */
class KeyInsertTest {

    /** Simulates the field state a parent composable holds, mutated by each onTextChange. */
    private class Field(var text: String = "") {
        val received = mutableListOf<String>()
        fun handler() = KeyInsert({ text }) { updated -> text = updated; received.add(updated) }
    }

    @Test
    fun twoAppendsWithoutRecreationBothAccumulate() {
        val field = Field("q")
        val insert = field.handler()
        insert.append("w")
        assertEquals("qw", field.text)
        insert.append("e")
        assertEquals("qwe", field.text)
        assertEquals(listOf("qw", "qwe"), field.received)
    }

    @Test
    fun longPressHintSequenceNeverFallsBackToStaleBase() {
        // The t=72-87 signature: every hint appended to "..123" instead of the live tail.
        val field = Field("..123")
        val insert = field.handler()
        insert.append("4")
        assertEquals("..1234", field.text)
        insert.append("5")
        assertEquals("..12345", field.text)
        insert.append("6")
        assertEquals("..123456", field.text)
    }

    @Test
    fun singleTapCanNeverWipeTheQuery() {
        // The t=27.50 signature: one tap on a stale "" base wiped a 10-char query.
        val field = Field("wertyuiopas")
        val insert = field.handler()
        insert.append("q")
        assertEquals("wertyuiopasq", field.text)
    }

    @Test
    fun dropLastRemovesOneCharAndIgnoresEmpty() {
        val field = Field("abc")
        val insert = field.handler()
        insert.dropLast()
        assertEquals("ab", field.text)
        insert.dropLast()
        insert.dropLast()
        assertEquals("", field.text)
        insert.dropLast()
        assertEquals("", field.text)
        assertEquals(3, field.received.size)
    }

    @Test
    fun insertWordAppendsWhenCurrentWordEmpty() {
        // r50: gliding after a space — replaceCurrentWord alone would return
        // text unchanged, swallowing the glide, so insertWord appends instead.
        val field = Field("hello ")
        val insert = field.handler()
        insert.insertWord("world")
        assertEquals("hello world", field.text)
    }

    @Test
    fun insertWordReplacesCurrentWord() {
        val field = Field("hello wor")
        val insert = field.handler()
        insert.insertWord("world")
        assertEquals("hello world", field.text)
    }

    @Test
    fun insertWordCapitalizesWhenShifted() {
        val field = Field("hello ")
        val insert = field.handler()
        insert.insertWord("world", capitalize = true)
        assertEquals("hello World", field.text)
    }
}
