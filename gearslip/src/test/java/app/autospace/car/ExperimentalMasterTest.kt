package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** r53 experimental master switch: derived state and the exact flag writes of each action. */
class ExperimentalMasterTest {

    @Test
    fun masterReadsOnOnlyWhenBothFloatingAndWidgetsAreOn() {
        assertTrue(ExperimentalMaster.isOn(true, true))
        assertFalse(ExperimentalMaster.isOn(true, false))
        assertFalse(ExperimentalMaster.isOn(false, true))
        assertFalse(ExperimentalMaster.isOn(false, false))
    }

    @Test
    fun masterOnSetsSuiteAndClearsDismissal() {
        val writes = ExperimentalMaster.onWrites()
        assertEquals(true, writes.experimentalFeatures)
        assertEquals(true, writes.floatingMedia)
        assertEquals(true, writes.homeWidgets)
        assertEquals(false, writes.widgetsDismissed)
        assertEquals(true, writes.frostedMapChrome)
    }

    @Test
    fun masterOffClearsOnlyFloatingAndWidgets() {
        val writes = ExperimentalMaster.offWrites()
        assertEquals(false, writes.floatingMedia)
        assertEquals(false, writes.homeWidgets)
        // Visibility flag left as-is: OFF must not hide rows the driver chose to show.
        assertNull(writes.experimentalFeatures)
        // Frosted chrome left as-is: a suite member for ON, never forced off by OFF.
        assertNull(writes.frostedMapChrome)
        // Dismissal left as-is: OFF does not re-hide or re-show the column.
        assertNull(writes.widgetsDismissed)
    }

    @Test
    fun writesCarryNoVehicleDataFieldByConstruction() {
        // Vehicle data is excluded from the master entirely: the Writes
        // class has no vehicle-data field, so neither action can touch it.
        // This test pins that shape — adding such a field must fail review.
        // Order-independent: the JVM field order is not part of the
        // contract, the exact SET is (an earlier version of this test
        // compared a sorted actual list against an unsorted literal and
        // failed on ordering alone — the production code was correct).
        val fields = ExperimentalMaster.Writes::class.java.declaredFields
            .map { it.name }
            .filter { it != "Companion" }
            .toSet()
        assertEquals(
            setOf(
                "experimentalFeatures",
                "floatingMedia",
                "homeWidgets",
                "widgetsDismissed",
                "frostedMapChrome",
            ),
            fields,
        )
    }

    @Test
    fun divergenceReadsMasterOff() {
        // Any individual toggle off after a master ON makes the master read
        // off again — derived state, no stored override to go stale.
        assertFalse(ExperimentalMaster.isOn(false, true))
        assertFalse(ExperimentalMaster.isOn(true, false))
    }
}
