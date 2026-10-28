package app.autospace.car

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertArrayEquals
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
    fun distributedPositionsCenterStackInArea() {
        // Leading edge 10, gaps 16: 10, 126, 242, trailing 10.
        assertArrayEquals(
            intArrayOf(10, 126, 242),
            railDistributedPositions(352, intArrayOf(100, 100, 100), 6),
        )
    }

    @Test
    fun distributedPositionsNeverShrinkBelowFloor() {
        assertArrayEquals(
            intArrayOf(0, 106, 212),
            railDistributedPositions(312, intArrayOf(100, 100, 100), 6),
        )
    }
}
