package app.autospace.car

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r64 A37 tests, all on pure logic (no composables): the full-player art column's share of
 * the two columns. The Compose layout itself is not JVM-observable - these pin the rule the
 * layout reads, and the layout change itself stands on CI compile plus the device list.
 */
class PlayerArtLayoutTest {

    @Test
    fun narrowSplitsKeepTodayShare() {
        assertEquals(0.4f, playerArtWeightFraction(400.dp), 0.001f)
        assertEquals(0.4f, playerArtWeightFraction(0.dp), 0.001f)
        assertEquals(0.4f, playerArtWeightFraction((-100).dp), 0.001f)
    }

    @Test
    fun typicalHeadUnitGrowsArt() {
        // 800px-wide unit: 744dp to split after padding and gap.
        assertEquals(0.409f, playerArtWeightFraction(744.dp), 0.001f)
        // 1024px-wide unit: 968dp to split.
        assertEquals(0.545f, playerArtWeightFraction(968.dp), 0.001f)
    }

    @Test
    fun wideSplitsCapAtSixtyPercent() {
        assertEquals(0.6f, playerArtWeightFraction(1224.dp), 0.001f)
        assertEquals(0.6f, playerArtWeightFraction(2000.dp), 0.001f)
    }

    @Test
    fun controlsColumnKeepsTransportRoom() {
        // Wherever the fraction is uncapped the controls column is exactly the minimum;
        // wherever it caps it is wider still.
        for (width in listOf(800, 968, 1100, 1224, 1600)) {
            val fraction = playerArtWeightFraction(width.dp)
            val controls = (1f - fraction) * width
            assertTrue(controls >= PLAYER_CONTROLS_MIN_WIDTH_DP - 0.01f)
        }
    }
}
