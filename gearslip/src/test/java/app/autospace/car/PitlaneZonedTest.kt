package app.autospace.car

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import app.autospace.car.theme.ChamferShape
import app.autospace.car.theme.ControlLook
import app.autospace.car.theme.GsThemes
import app.autospace.car.theme.PitlaneFrameTier
import app.autospace.car.theme.buttonShapeForLook
import app.autospace.car.theme.cardShapeForLook
import app.autospace.car.theme.pitlaneBoltCenters
import app.autospace.car.theme.pitlaneBoltHexPoints
import app.autospace.car.theme.pitlaneBoltSize
import app.autospace.car.theme.pitlaneFrameBand
import app.autospace.car.theme.pitlaneFrameTier
import app.autospace.car.theme.pitlaneWeaveCell
import app.autospace.car.theme.shouldDrawControlTexture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/** r115 Pitlane zoned rework: tier table, frame geometry, bolt geometry, chamfer shape, texture gate. */
class PitlaneZonedTest {

    @Test
    fun tierBoundariesFollowTheKitSizeFacts() {
        // Keys bottom out at a 44dp floor, standard buttons sit at 48dp
        // and up, cards/sheets at 96dp and up. Boundaries take the
        // larger tier.
        assertEquals(PitlaneFrameTier.S, pitlaneFrameTier(44.dp))
        assertEquals(PitlaneFrameTier.S, pitlaneFrameTier(47.dp))
        assertEquals(PitlaneFrameTier.M, pitlaneFrameTier(48.dp))
        assertEquals(PitlaneFrameTier.M, pitlaneFrameTier(56.dp))
        assertEquals(PitlaneFrameTier.M, pitlaneFrameTier(95.dp))
        assertEquals(PitlaneFrameTier.L, pitlaneFrameTier(96.dp))
        assertEquals(PitlaneFrameTier.L, pitlaneFrameTier(200.dp))
    }

    @Test
    fun tierGeometryMatchesThePick() {
        // L: thick frame + weave + 8dp bolts. M: medium frame + finer
        // weave + 6dp bolts. S: no band, no weave, no bolts — the
        // border thins to standard and only chamfer + corner remain.
        assertEquals(12.dp, pitlaneFrameBand(PitlaneFrameTier.L))
        assertEquals(8.dp, pitlaneWeaveCell(PitlaneFrameTier.L))
        assertEquals(8.dp, pitlaneBoltSize(PitlaneFrameTier.L))
        assertEquals(8.dp, pitlaneFrameBand(PitlaneFrameTier.M))
        assertEquals(6.dp, pitlaneWeaveCell(PitlaneFrameTier.M))
        assertEquals(6.dp, pitlaneBoltSize(PitlaneFrameTier.M))
        assertEquals(0.dp, pitlaneFrameBand(PitlaneFrameTier.S))
        assertEquals(0.dp, pitlaneWeaveCell(PitlaneFrameTier.S))
        assertEquals(0.dp, pitlaneBoltSize(PitlaneFrameTier.S))
    }

    @Test
    fun boltCentersSitInsideTheBandOnAStrictGrid() {
        // L: 12dp band, centers 6dp in — an 8dp bolt spans 2..10dp.
        val l = pitlaneBoltCenters(200f, 120f, 12f)
        assertEquals(4, l.size)
        assertEquals(Offset(6f, 6f), l[0])
        assertEquals(Offset(194f, 6f), l[1])
        assertEquals(Offset(6f, 114f), l[2])
        assertEquals(Offset(194f, 114f), l[3])
        // M: 8dp band, centers 4dp in — a 6dp bolt spans 1..7dp.
        val m = pitlaneBoltCenters(100f, 48f, 8f)
        assertEquals(Offset(4f, 4f), m[0])
        assertEquals(Offset(96f, 44f), m[3])
    }

    @Test
    fun boltHexPointsAreSixEquidistantFlatTopVertices() {
        val pts = pitlaneBoltHexPoints(Offset(50f, 50f), 10f)
        assertEquals(6, pts.size)
        for (p in pts) {
            val d = sqrt((p.x - 50f) * (p.x - 50f) + (p.y - 50f) * (p.y - 50f))
            assertEquals(10f, d, 0.001f)
        }
        // Flat top and bottom edges: two vertices share the top y,
        // two share the bottom y.
        val ys = pts.map { it.y }.sorted()
        assertEquals(ys[0], ys[1], 0.001f)
        assertEquals(ys[4], ys[5], 0.001f)
        assertTrue(abs(ys[0] - ys[5]) > 0f)
    }

    @Test
    fun pitlaneShapesAreChamferedEverywhere() {
        // Buttons, tiles, keys (gsButtonShape path) and cards/sheets
        // (look-fed card path) all take the 4dp chamfer under Pitlane.
        assertEquals(ChamferShape(4.dp), buttonShapeForLook(GsThemes.Pitlane.control))
        assertEquals(
            ChamferShape(4.dp),
            cardShapeForLook(GsThemes.Pitlane.control, androidx.compose.foundation.shape.RoundedCornerShape(20.dp)),
        )
    }

    @Test
    fun zonedControlOnPanelStillDrawsItsFrame() {
        // r115: band-confined weave cannot moiré (bands are disjoint by
        // construction), so a zoned control on a panel draws its own
        // frame — the r32 suppression stays only for non-zoned weave.
        val zoned = ControlLook(weaveAlpha = 0.05f, chamferCut = 4.dp)
        assertTrue(shouldDrawControlTexture(zoned, true))
        assertTrue(shouldDrawControlTexture(zoned, false))
        val plain = ControlLook(weaveAlpha = 0.05f, grainAlpha = 0.03f)
        assertTrue(shouldDrawControlTexture(plain, false))
        assertTrue(!shouldDrawControlTexture(plain, true))
    }
}
