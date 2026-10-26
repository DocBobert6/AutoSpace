package app.autospace.car

import app.autospace.car.theme.GsThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Edge (r20) takes Keycap's slot: new id, old id never reused, Keycap out of the picker. */
class GsThemeTest {

    @Test
    fun edgeIsBuiltInAndKeycapIsNot() {
        assertTrue(GsThemes.builtIn.any { it.id == "edge" && it.name == "Edge" })
        assertFalse(GsThemes.builtIn.any { it.id == "keycap" })
        assertEquals("edge", GsThemes.byId("edge").id)
    }

    @Test
    fun edgeParametersMatchTheApprovedSpec() {
        val look = GsThemes.Edge.control
        assertEquals(0.85f, look.hairlineAlpha, 0.001f)
        assertEquals(0.35f, look.bevelTop, 0.001f)
        assertEquals(0.45f, look.bevelBottom, 0.001f)
        assertEquals(0f, look.topLight, 0.001f)
        assertEquals(0, look.shadow.value.toInt())
        assertEquals(0.18f, look.pressedDim, 0.001f)
        assertEquals(1f, look.containerAlpha, 0.001f)
        val light = checkNotNull(look.light)
        assertEquals(0.70f, light.hairlineAlpha, 0.001f)
        assertTrue(light.bevelTop < look.bevelTop)
        assertTrue(light.bevelBottom < look.bevelBottom)
    }

    @Test
    fun frostFlatAndTactileUnchanged() {
        assertEquals("frost", GsThemes.default.id)
        assertTrue(GsThemes.builtIn.any { it.id == "flat" })
        assertTrue(GsThemes.builtIn.any { it.id == "tactile" })
    }
}
