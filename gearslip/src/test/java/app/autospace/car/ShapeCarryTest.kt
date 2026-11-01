package app.autospace.car

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RectangleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import app.autospace.car.theme.ChamferShape
import app.autospace.car.theme.ControlLook
import app.autospace.car.theme.GsKit
import app.autospace.car.theme.cardShapeForLook
import app.autospace.car.theme.keyShapeForLook
import app.autospace.car.theme.rowShapeForLook
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * r167 (audit §3 R5): pins for the UI shape-carry. Rows, turn/ETA
 * fallbacks, keyboard keys, and the opaque chrome branch resolve through
 * the style rule — so a style change can never silently leave a surface
 * on a raw Material or fixed corner again. The Meridian turn/ETA branch
 * is a hardcoded 16dp surface, not the card rule, so it is pinned here
 * only by exclusion: nothing in this round touches it.
 */
class ShapeCarryTest {

    private val plain = ControlLook()
    private val verveLike = ControlLook(corner = 24.dp, cardsUseStyleCorner = true)
    private val meridianLike = ControlLook(corner = 16.dp, cardsUseStyleCorner = true)
    private val pitlaneLike = ControlLook(chamferCut = 4.dp)
    private val horizonLike = ControlLook(pillButtons = true)
    private val rectangularLike = ControlLook(rectangularButtons = true, corner = 6.dp)

    @Test
    fun rowShapeFollowsCardRule() {
        assertEquals(RoundedCornerShape(20.dp), rowShapeForLook(plain))
        assertEquals(RoundedCornerShape(24.dp), rowShapeForLook(verveLike))
        assertEquals(RoundedCornerShape(16.dp), rowShapeForLook(meridianLike))
        assertEquals(ChamferShape(4.dp), rowShapeForLook(pitlaneLike))
        assertEquals(RoundedCornerShape(6.dp), rowShapeForLook(rectangularLike))
    }

    @Test
    fun keyShapeIsPillOnlyUnderPillButtons() {
        assertEquals(RoundedCornerShape(12.dp), keyShapeForLook(plain))
        assertEquals(GsKit.PillShape, keyShapeForLook(horizonLike))
        assertEquals(ChamferShape(4.dp), keyShapeForLook(pitlaneLike))
        // Chamfer wins over pill: Pitlane keys were already chamfered
        // through gsButtonShape, and this round must not round them.
        assertEquals(ChamferShape(4.dp), keyShapeForLook(ControlLook(chamferCut = 4.dp, pillButtons = true)))
    }

    @Test
    fun opaqueChromeShapeKeepsZeroDpExactlyZero() {
        assertEquals(RoundedCornerShape(0.dp), opaqueChromeShape(plain, RoundedCornerShape(0.dp)))
        assertEquals(RoundedCornerShape(0.dp), opaqueChromeShape(verveLike, RoundedCornerShape(0.dp)))
        assertEquals(RoundedCornerShape(0.dp), opaqueChromeShape(pitlaneLike, RoundedCornerShape(0.dp)))
    }

    @Test
    fun opaqueChromeShapeRoutesNonZeroRoundedShapes() {
        assertEquals(RoundedCornerShape(16.dp), opaqueChromeShape(plain, RoundedCornerShape(16.dp)))
        assertEquals(RoundedCornerShape(24.dp), opaqueChromeShape(verveLike, RoundedCornerShape(16.dp)))
        assertEquals(ChamferShape(4.dp), opaqueChromeShape(pitlaneLike, RoundedCornerShape(16.dp)))
    }

    @Test
    fun opaqueChromeShapeLeavesNonRoundedShapesAlone() {
        // CircleShape IS a RoundedCornerShape(50) under the hood, so it
        // is excluded by value in the guard, not by type.
        assertEquals(CircleShape, opaqueChromeShape(verveLike, CircleShape))
        assertEquals(CircleShape, opaqueChromeShape(pitlaneLike, CircleShape))
        assertEquals(RectangleShape, opaqueChromeShape(verveLike, RectangleShape))
        assertEquals(RectangleShape, opaqueChromeShape(pitlaneLike, RectangleShape))
    }

    @Test
    fun turnEtaFallbackResolvesToKitCard() {
        // The three themed call sites pass GsKit.CardShape (20dp) as the
        // fallback; the rule itself is pinned here, the call-site swap
        // is code-review verified.
        assertEquals(RoundedCornerShape(20.dp), cardShapeForLook(plain, GsKit.CardShape))
        assertEquals(RoundedCornerShape(24.dp), cardShapeForLook(verveLike, GsKit.CardShape))
        assertEquals(RoundedCornerShape(16.dp), cardShapeForLook(meridianLike, GsKit.CardShape))
        assertEquals(ChamferShape(4.dp), cardShapeForLook(pitlaneLike, GsKit.CardShape))
    }
}
