package app.autospace.car

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import app.autospace.car.theme.cardShapeForLook
import org.junit.Assert.assertEquals
import org.junit.Test

/** r29 themes round: the shape rule and the rail's pinned numbers (r97: the bar is gone; the rail reuses its width and elevation). */
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
        // Frost (12dp), Ink (16dp), or any non-rectangular style: the fallback
        // passes through untouched, so their look is unchanged by construction.
        assertEquals(RoundedCornerShape(20.dp), cardShapeForLook(false, 12.dp, RoundedCornerShape(20.dp)))
        assertEquals(RoundedCornerShape(50), cardShapeForLook(false, 16.dp, RoundedCornerShape(50)))
        assertEquals(RoundedCornerShape(28.dp), cardShapeForLook(false, 12.dp, RoundedCornerShape(28.dp)))
    }

    @Test
    fun railNumbersArePinned() {
        // r97: the rail reuses the old bar's 64dp width and 4dp
        // elevation, floats with 12dp margins, and reserves an
        // 88dp strip (64 + 12 + 12).
        assertEquals(64, NAV_BAR_HEIGHT.value.toInt())
        assertEquals(12, NAV_BAR_SIDE_FLOAT.value.toInt())
        assertEquals(4, NAV_BAR_FLOAT_ELEVATION.value.toInt())
        assertEquals(88, NAV_BAR_SIDE_STRIP_WIDTH.value.toInt())
    }
}
