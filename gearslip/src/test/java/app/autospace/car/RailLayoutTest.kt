package app.autospace.car

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** r134 rail spacing + drawer icon size: distributed-gap math and switcher grid geometry. */
class RailLayoutTest {

    @Test
    fun minGapIsSixDp() {
        assertEquals(6.dp, RAIL_ICON_MIN_GAP)
    }

    @Test
    fun switcherGridFootprintIsButtonGlyphSize() {
        assertEquals(24.dp, SWITCHER_GRID_FOOTPRINT)
        assertEquals(SWITCHER_SQUARE * 2 + SWITCHER_GRID_GAP, SWITCHER_GRID_FOOTPRINT)
    }

    @Test
    fun distributedGapEmptyStackIsFloor() {
        assertEquals(6, railDistributedGapPx(500, intArrayOf(), 6))
    }

    @Test
    fun distributedGapOverflowingStackIsFloor() {
        // Content plus floor gaps already exceeds the area: no surplus to share.
        assertEquals(6, railDistributedGapPx(300, intArrayOf(100, 100, 100), 6))
    }

    @Test
    fun distributedGapExactFitIsFloor() {
        assertEquals(6, railDistributedGapPx(312, intArrayOf(100, 100, 100), 6))
    }

    @Test
    fun distributedGapSharesSurplusEvenly() {
        // Surplus 40 across the leading edge and the two gaps: 6 + 40 / 4 = 16.
        assertEquals(16, railDistributedGapPx(352, intArrayOf(100, 100, 100), 6))
    }

    @Test
    fun distributedArrangementCentersStackInArea() {
        val out = IntArray(3)
        with(Density(1f)) {
            RailDistributedArrangement.arrange(352, intArrayOf(100, 100, 100), out)
        }
        // Leading edge 10, gaps 16: 10, 126, 242, trailing 10.
        assertEquals(10, out[0])
        assertEquals(126, out[1])
        assertEquals(242, out[2])
    }

    @Test
    fun distributedArrangementNeverShrinksBelowFloor() {
        val out = IntArray(3)
        with(Density(1f)) {
            RailDistributedArrangement.arrange(312, intArrayOf(100, 100, 100), out)
        }
        assertEquals(0, out[0])
        assertEquals(106, out[1])
        assertEquals(212, out[2])
    }
}
