package app.autospace.car

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import app.autospace.car.theme.cardShapeForLook
import org.junit.Assert.assertEquals
import org.junit.Test

/** r29 themes round: the shape rule and the floating nav bar's pinned numbers. */
class ThemesRoundTest {

    @Test
    fun pitLaneStyleTakesItsOwnCorner() {
        // Pit Lane: rectangularButtons + 6dp corner -> 6dp, whatever the fallback was.
        assertEquals(RoundedCornerShape(6.dp), cardShapeForLook(true, 6.dp, RoundedCornerShape(20.dp)))
        assertEquals(RoundedCornerShape(6.dp), cardShapeForLook(true, 6.dp, RoundedCornerShape(50)))
        assertEquals(RoundedCornerShape(6.dp), cardShapeForLook(true, 6.dp, RoundedCornerShape(14.dp)))
    }

    @Test
    fun otherStylesKeepTheirFallbackExactly() {
        // Frost (12dp), Edge (16dp), or any non-rectangular style: the fallback
        // passes through untouched, so their look is unchanged by construction.
        assertEquals(RoundedCornerShape(20.dp), cardShapeForLook(false, 12.dp, RoundedCornerShape(20.dp)))
        assertEquals(RoundedCornerShape(50), cardShapeForLook(false, 16.dp, RoundedCornerShape(50)))
        assertEquals(RoundedCornerShape(28.dp), cardShapeForLook(false, 12.dp, RoundedCornerShape(28.dp)))
    }

    @Test
    fun floatingNavBarNumbersArePinned() {
        // r29: the bar keeps its 64dp height and targets; it only insets
        // inside its row (12dp sides, 8dp vertical) with 6dp elevation.
        assertEquals(64, NAV_BAR_HEIGHT.value.toInt())
        assertEquals(12, NAV_BAR_FLOAT_HORIZONTAL.value.toInt())
        assertEquals(8, NAV_BAR_FLOAT_VERTICAL.value.toInt())
        assertEquals(6, NAV_BAR_FLOAT_ELEVATION.value.toInt())
    }
}
