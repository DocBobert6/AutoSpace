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
    fun masterWritesAreExactlyTheSuiteFlags() {
        // Vehicle data is excluded from the master entirely, pinned here
        // by full data-class equality: Writes has exactly these five
        // fields, so adding a vehicle-data field would break compilation
        // of this test (constructor arity change) and force review.
        // This replaces an earlier reflection-based shape test, which
        // failed twice on opaque JVM field-set details (first a sorted
        // vs unsorted list, then a set mismatch whose expected/actual
        // CI logs never show) — data-class equality compares only the
        // declared properties and cannot fail on synthetic fields.
        assertEquals(
            ExperimentalMaster.Writes(
                experimentalFeatures = true,
                floatingMedia = true,
                homeWidgets = true,
                widgetsDismissed = false,
                frostedMapChrome = true,
            ),
            ExperimentalMaster.onWrites(),
        )
        assertEquals(
            ExperimentalMaster.Writes(
                experimentalFeatures = null,
                floatingMedia = false,
                homeWidgets = false,
                widgetsDismissed = null,
                frostedMapChrome = null,
            ),
            ExperimentalMaster.offWrites(),
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
