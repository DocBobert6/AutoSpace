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

    @Test
    fun pitlaneIsBuiltInWithSquaredThickStyleAndNoChecker() {
        assertTrue(GsThemes.builtIn.any { it.id == "pitlane" && it.name == "Pitlane" })
        assertEquals("pitlane", GsThemes.byId("pitlane").id)
        val look = GsThemes.Pitlane.control
        assertEquals(6, look.corner.value.toInt())
        assertEquals(0f, look.topLight, 0.001f)
        assertEquals(1f, look.hairlineAlpha, 0.001f)
        assertEquals(0.50f, look.bevelTop, 0.001f)
        assertEquals(0.60f, look.bevelBottom, 0.001f)
        assertEquals(0, look.shadow.value.toInt())
        assertEquals(1f, look.containerAlpha, 0.001f)
        // r24: the checker strip is removed entirely — Pitlane squares
        // its capsule/round containers instead, and no built-in style
        // wears a checker strip under any color scheme.
        assertTrue(look.rectangularButtons)
        assertFalse(look.checkerStrip)
        assertEquals(0f, look.insetAlpha, 0.001f)
        val light = checkNotNull(look.light)
        assertEquals(6, light.corner.value.toInt())
        assertTrue(light.rectangularButtons)
        assertFalse(light.checkerStrip)
        assertFalse(GsThemes.builtIn.any { it.control.checkerStrip })
        assertFalse(GsThemes.builtIn.any { it.control.light?.checkerStrip == true })
        // Rectangular buttons are Pitlane-only: every other built-in
        // style keeps its existing shapes exactly.
        assertFalse(GsThemes.builtIn.filter { it.id != "pitlane" }.any { it.control.rectangularButtons })
    }

    @Test
    fun newSchemeSlotsExistAndFrostIsStillDefault() {
        // Stored by these exact names in car_app_theme; a rename of the
        // picker label must not change the stored name.
        assertEquals(AppTheme.PITLANE, AppTheme.valueOf("PITLANE"))
        assertEquals(AppTheme.CIRCUIT, AppTheme.valueOf("CIRCUIT"))
        assertEquals(AppTheme.HERITAGE, AppTheme.valueOf("HERITAGE"))
        assertEquals("frost", GsThemes.default.id)
    }
}
