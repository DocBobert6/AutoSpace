package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * r154 pure tests for the Gboard decided picks: sentence-start auto-cap
 * and double-space-period, both gated off in URL fields by the caller
 * ([CarKeyboard.urlMode]) — these functions themselves are mode-free,
 * the gate lives at the call site and in [KeyInsert].
 */
class AutoFormatTest {

    @Test
    fun emptyFieldCapitalizes() {
        assertEquals(true, AutoFormat.shouldCapitalize(""))
    }

    @Test
    fun whitespaceOnlyFieldCapitalizes() {
        assertEquals(true, AutoFormat.shouldCapitalize("   "))
    }

    @Test
    fun plainWordDoesNotCapitalize() {
        assertEquals(false, AutoFormat.shouldCapitalize("hello"))
    }

    @Test
    fun periodSpaceCapitalizes() {
        assertEquals(true, AutoFormat.shouldCapitalize("hello. "))
    }

    @Test
    fun terminatorWithoutSpaceCapitalizes() {
        assertEquals(true, AutoFormat.shouldCapitalize("hello."))
        assertEquals(true, AutoFormat.shouldCapitalize("hello!"))
        assertEquals(true, AutoFormat.shouldCapitalize("hello?"))
    }

    @Test
    fun newlineCapitalizes() {
        assertEquals(true, AutoFormat.shouldCapitalize("hello\n"))
    }

    @Test
    fun decimalDoesNotCapitalize() {
        assertEquals(false, AutoFormat.shouldCapitalize("version 2.0"))
    }

    @Test
    fun abbreviationCapitalizesLikeGboard() {
        // Deliberate Gboard-faithful quirk, documented on AutoFormat:
        // "e.g. " ends in a terminator, so the next word capitalizes.
        assertEquals(true, AutoFormat.shouldCapitalize("e.g. "))
    }

    @Test
    fun spaceAfterWordAppendsPlainSpace() {
        assertEquals("hello ", AutoFormat.spaceResult("hello"))
    }

    @Test
    fun doubleSpaceBecomesPeriodSpace() {
        assertEquals("hello. ", AutoFormat.spaceResult("hello "))
    }

    @Test
    fun spaceAfterTerminatorDoesNotDoublePeriod() {
        assertEquals("hello.  ", AutoFormat.spaceResult("hello. "))
        assertEquals("hello!  ", AutoFormat.spaceResult("hello! "))
    }

    @Test
    fun spaceAfterTwoSpacesStaysPlain() {
        assertEquals("hello   ", AutoFormat.spaceResult("hello  "))
    }

    @Test
    fun spaceAtFieldStartStaysPlain() {
        assertEquals(" ", AutoFormat.spaceResult(""))
        assertEquals("  ", AutoFormat.spaceResult(" "))
    }
}
