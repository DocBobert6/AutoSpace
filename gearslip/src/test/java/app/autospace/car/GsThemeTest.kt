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
        assertTrue(GsThemes.builtIn.any { it.id == "pitlane" && it.name == "Pit Lane" })
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
    fun pitLaneFidelityParametersArePitLaneOnly() {
        // r26: the Pit Lane material (weave, grain, rivets, LED, bat toggle,
        // engraved headers, bevel on latched) exists ONLY on Pit Lane, dark +
        // light. Every other built-in look carries none of it, so no other
        // style's appearance changes by construction.
        val pitlane = GsThemes.Pitlane.control
        assertEquals(0.05f, pitlane.weaveAlpha, 0.001f)
        assertEquals(0.03f, pitlane.grainAlpha, 0.001f)
        assertTrue(pitlane.rivets)
        assertTrue(pitlane.ledDot)
        assertTrue(pitlane.batToggle)
        assertTrue(pitlane.engravedHeader)
        assertTrue(pitlane.bevelOnLatched)
        val light = checkNotNull(pitlane.light)
        assertEquals(0.04f, light.weaveAlpha, 0.001f)
        assertEquals(0.025f, light.grainAlpha, 0.001f)
        assertTrue(light.rivets)
        assertTrue(light.ledDot)
        assertTrue(light.batToggle)
        assertTrue(light.engravedHeader)
        assertTrue(light.bevelOnLatched)
        // The hard bevel values themselves are unchanged from r23/r24 —
        // r26 extends WHERE the bevel draws (latched too, plus the
        // inverted well), it does not inflate the edge.
        assertEquals(0.50f, pitlane.bevelTop, 0.001f)
        assertEquals(0.60f, pitlane.bevelBottom, 0.001f)
        assertEquals(0.45f, light.bevelTop, 0.001f)
        assertEquals(0.55f, light.bevelBottom, 0.001f)
        assertEquals(0f, pitlane.topLight, 0.001f)
        assertEquals(0, pitlane.shadow.value.toInt())
        for (other in GsThemes.builtIn.filter { it.id != "pitlane" }) {
            assertEquals(0f, other.control.weaveAlpha, 0.001f)
            assertEquals(0f, other.control.grainAlpha, 0.001f)
            assertFalse(other.control.rivets)
            assertFalse(other.control.ledDot)
            assertFalse(other.control.batToggle)
            assertFalse(other.control.engravedHeader)
            assertFalse(other.control.bevelOnLatched)
            val otherLight = other.control.light
            if (otherLight != null) {
                assertEquals(0f, otherLight.weaveAlpha, 0.001f)
                assertEquals(0f, otherLight.grainAlpha, 0.001f)
                assertFalse(otherLight.rivets)
                assertFalse(otherLight.ledDot)
                assertFalse(otherLight.batToggle)
                assertFalse(otherLight.engravedHeader)
                assertFalse(otherLight.bevelOnLatched)
            }
        }
        // The rename is display-only: the stored id never moves, and the
        // scheme enum is untouched.
        assertEquals("pitlane", GsThemes.Pitlane.id)
        assertEquals(AppTheme.PITLANE, AppTheme.valueOf("PITLANE"))
        // Rectangular buttons still Pit Lane-only; no checker anywhere.
        assertFalse(GsThemes.builtIn.filter { it.id != "pitlane" }.any { it.control.rectangularButtons })
        assertFalse(GsThemes.builtIn.any { it.control.checkerStrip })
        assertFalse(GsThemes.builtIn.any { it.control.light?.checkerStrip == true })
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
