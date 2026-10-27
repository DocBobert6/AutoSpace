package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The barge-in generation guard, without any assistant machinery: a superseded
 * turn's generation must stop matching the moment a new turn begins.
 */
class TurnGateTest {

    @Test
    fun initialGenerationIsCurrent() {
        val gate = TurnGate()
        assertEquals(0, gate.current())
        assertTrue(gate.isCurrent(0))
    }

    @Test
    fun beginTurnInvalidatesPriorGenerations() {
        val gate = TurnGate()
        val first = gate.beginTurn()
        assertEquals(1, first)
        assertTrue(gate.isCurrent(first))
        assertFalse(gate.isCurrent(0))
        val second = gate.beginTurn()
        assertEquals(2, second)
        assertTrue(gate.isCurrent(second))
        assertFalse(gate.isCurrent(first))
    }

    @Test
    fun repeatedBeginsKeepOnlyTheLatestCurrent() {
        val gate = TurnGate()
        val generations = (1..5).map { gate.beginTurn() }
        assertTrue(gate.isCurrent(generations.last()))
        generations.dropLast(1).forEach { assertFalse(gate.isCurrent(it)) }
    }
}
