package app.autospace.car

import app.autospace.car.theme.GsThemes
import app.autospace.car.theme.haloBrightnessFactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun r31DisplayNamesAreOneWordAndIdsUnchanged() {
        // r31 rename round: "Pit Lane" -> "Pitlane" display-only. The id and
        // stored value never move; this pins both halves of that promise.
        // "Pitch" (dark type) lives in composable picker lists, not in a
        // JVM-testable data source, so it is verified by source grep + dex
        // scan at push time, not faked as a unit test here.
        assertEquals("Pitlane", GsThemes.Pitlane.name)
        assertEquals("pitlane", GsThemes.Pitlane.id)
        assertEquals("pitlane", GsThemes.byId("pitlane").id)
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
        assertEquals(ColorScheme.PITLANE, ColorScheme.valueOf("PITLANE"))
        // Rectangular buttons still Pit Lane-only; no checker anywhere.
        assertFalse(GsThemes.builtIn.filter { it.id != "pitlane" }.any { it.control.rectangularButtons })
        assertFalse(GsThemes.builtIn.any { it.control.checkerStrip })
        assertFalse(GsThemes.builtIn.any { it.control.light?.checkerStrip == true })
    }

    @Test
    fun meridianIsBuiltInWithFlatSixteenDpStyle() {
        // r107: Meridian appended last, same rule as Verve/Horizon —
        // nobody moves to it automatically.
        assertTrue(GsThemes.builtIn.any { it.id == "meridian" && it.name == "Meridian" })
        assertEquals("meridian", GsThemes.byId("meridian").id)
        val look = GsThemes.Meridian.control
        assertEquals(16, look.corner.value.toInt())
        assertEquals(0f, look.topLight, 0.001f)
        assertEquals(0f, look.hairlineAlpha, 0.001f)
        assertEquals(0f, look.bevelTop, 0.001f)
        assertEquals(0f, look.bevelBottom, 0.001f)
        assertEquals(0, look.shadow.value.toInt())
        assertEquals(0f, look.shadowAlpha, 0.001f)
        assertEquals(0f, look.weaveAlpha, 0.001f)
        assertEquals(0f, look.grainAlpha, 0.001f)
        assertFalse(look.rivets)
        assertEquals(0.14f, look.pressedDim, 0.001f)
        assertEquals(0.98f, look.pressedScale, 0.001f)
        assertEquals(1f, look.containerAlpha, 0.001f)
        assertTrue(look.springMotion)
        assertEquals(1.0f, look.springDamping, 0.001f)
        assertTrue(look.cardsUseStyleCorner)
        assertFalse(look.latchedPrimaryTint)
        assertEquals(null, look.light)
    }

    @Test
    fun heirloomIsBuiltInWithStitchSpec() {
        // r108: Heritage's own style, appended last, same rule as
        // Verve/Horizon/Meridian — nobody moves to it automatically.
        // The working name was "Leather stitch"; the style ships
        // named Heirloom and no "Leather stitch" label exists in code.
        // r110: Blueprint appends after it, so Heirloom sits second
        // from last, not last.
        // r112: Sector appends after Blueprint, so Heirloom sits
        // third from last.
        // r113: Silhouette appends after Sector, so Heirloom sits
        // fourth from last.
        // r114: Morph appends after Silhouette, so Heirloom sits
        // fifth from last.
        assertTrue(GsThemes.builtIn.any { it.id == "heirloom" && it.name == "Heirloom" })
        assertEquals(GsThemes.Heirloom, GsThemes.builtIn[GsThemes.builtIn.size - 5])
        assertEquals("heirloom", GsThemes.byId("heirloom").id)
        val look = GsThemes.Heirloom.control
        assertEquals(22, look.corner.value.toInt())
        assertEquals(0f, look.topLight, 0.001f)
        assertEquals(0.20f, look.bevelTop, 0.001f)
        assertEquals(0.25f, look.bevelBottom, 0.001f)
        assertEquals(4, look.shadow.value.toInt())
        assertEquals(0.28f, look.shadowAlpha, 0.001f)
        assertEquals(0.9f, look.stitchAlpha, 0.001f)
        assertEquals(2, look.stitchInset.value.toInt())
        assertEquals(0f, look.weaveAlpha, 0.001f)
        assertEquals(0f, look.grainAlpha, 0.001f)
        assertFalse(look.rivets)
        assertFalse(look.rectangularButtons)
        assertFalse(look.cardsUseStyleCorner)
        assertFalse(look.springMotion)
        val light = checkNotNull(look.light)
        assertEquals(22, light.corner.value.toInt())
        assertEquals(0.24f, light.bevelTop, 0.001f)
        assertEquals(0.30f, light.bevelBottom, 0.001f)
        assertEquals(0.9f, light.stitchAlpha, 0.001f)
        assertEquals(2, light.stitchInset.value.toInt())
        assertFalse(light.rivets)
    }

    @Test
    fun newSchemeSlotsExistAndFrostIsStillDefault() {
        // Stored by these exact names in car_scheme (r35: the palette
        // axis; the mode lives in car_appearance_mode); a rename of the
        // picker label must not change the stored name.
        assertEquals(ColorScheme.PITLANE, ColorScheme.valueOf("PITLANE"))
        assertEquals(ColorScheme.CIRCUIT, ColorScheme.valueOf("CIRCUIT"))
        assertEquals(ColorScheme.HERITAGE, ColorScheme.valueOf("HERITAGE"))
        assertEquals("frost", GsThemes.default.id)
    }

    @Test
    fun blueprintIsBuiltInWithRingSpec() {
        // r110: the Neutral — now Canvas — scheme's own style,
        // appended last, same rule as Verve/Horizon/Meridian/
        // Heirloom — nobody moves to it automatically.
        // The working name was "Outline"; the style ships named
        // Blueprint and no "Outline" style label exists in code
        // (GsTone.Outline is the unrelated outlined-button tone).
        // The rename is label-only: the stored scheme name stays
        // NEUTRAL, so existing Canvas users keep their scheme.
        // r112: Sector appends after it, so Blueprint sits second
        // from last, not last.
        // r113: Silhouette appends after Sector, so Blueprint sits
        // third from last.
        // r114: Morph appends after Silhouette, so Blueprint sits
        // fourth from last.
        assertTrue(GsThemes.builtIn.any { it.id == "blueprint" && it.name == "Blueprint" })
        assertEquals(GsThemes.Blueprint, GsThemes.builtIn[GsThemes.builtIn.size - 4])
        assertEquals("blueprint", GsThemes.byId("blueprint").id)
        assertEquals("Canvas", ColorScheme.NEUTRAL.displayName)
        val look = GsThemes.Blueprint.control
        assertEquals(16, look.corner.value.toInt())
        assertEquals(0f, look.topLight, 0.001f)
        assertEquals(0f, look.hairlineAlpha, 0.001f)
        assertEquals(0f, look.bevelTop, 0.001f)
        assertEquals(0f, look.bevelBottom, 0.001f)
        assertEquals(2, look.shadow.value.toInt())
        assertEquals(0.20f, look.shadowAlpha, 0.001f)
        assertEquals(1f, look.ringAlpha, 0.001f)
        assertEquals(0f, look.weaveAlpha, 0.001f)
        assertEquals(0f, look.grainAlpha, 0.001f)
        assertFalse(look.rivets)
        assertFalse(look.rectangularButtons)
        assertFalse(look.cardsUseStyleCorner)
        assertFalse(look.springMotion)
        assertFalse(look.pillButtons)
        assertTrue(look.latchedPrimaryTint)
        assertEquals(0.14f, look.pressedDim, 0.001f)
        assertEquals(0.98f, look.pressedScale, 0.001f)
        val light = checkNotNull(look.light)
        assertEquals(16, light.corner.value.toInt())
        assertEquals(1f, light.ringAlpha, 0.001f)
        assertTrue(light.latchedPrimaryTint)
    }

    @Test
    fun sectorIsBuiltInWithTrioSegmentSpec() {
        // r112: the Circuit scheme's own style, appended last, same
        // rule as Verve/Horizon/Meridian/Heirloom/Blueprint —
        // nobody moves to it automatically. The bevel is Tactile's
        // exact values (0.16/0.22 dark, 0.24/0.30 light), not a
        // midpoint; the segment is 3dp thick at full alpha, and
        // the latched brighten lives in the draw code (20% toward
        // white), not in a field, so it is pinned by eye on device
        // rather than here. State- or role-based coloring was
        // rejected in the pick, so no field carries it.
        // r113: Silhouette appends after it, so Sector sits second
        // from last, not last.
        // r114: Morph appends after Silhouette, so Sector sits
        // third from last.
        assertTrue(GsThemes.builtIn.any { it.id == "sector" && it.name == "Sector" })
        assertEquals(GsThemes.Sector, GsThemes.builtIn[GsThemes.builtIn.size - 3])
        assertEquals("sector", GsThemes.byId("sector").id)
        val look = GsThemes.Sector.control
        assertEquals(14, look.corner.value.toInt())
        assertEquals(0f, look.topLight, 0.001f)
        assertEquals(0f, look.hairlineAlpha, 0.001f)
        assertEquals(0.16f, look.bevelTop, 0.001f)
        assertEquals(0.22f, look.bevelBottom, 0.001f)
        assertEquals(2, look.shadow.value.toInt())
        assertEquals(0.20f, look.shadowAlpha, 0.001f)
        assertEquals(1f, look.segmentAlpha, 0.001f)
        assertEquals(3, look.segmentThickness.value.toInt())
        assertEquals(0f, look.ringAlpha, 0.001f)
        assertEquals(0f, look.stitchAlpha, 0.001f)
        assertEquals(0f, look.bottomLineAlpha, 0.001f)
        assertEquals(0f, look.haloAlpha, 0.001f)
        assertEquals(0, look.haloFalloff.value.toInt())
        assertEquals(0f, look.weaveAlpha, 0.001f)
        assertEquals(0f, look.grainAlpha, 0.001f)
        assertFalse(look.rivets)
        assertFalse(look.rectangularButtons)
        assertFalse(look.cardsUseStyleCorner)
        assertFalse(look.springMotion)
        assertFalse(look.pillButtons)
        assertTrue(look.latchedPrimaryTint)
        assertEquals(0.14f, look.pressedDim, 0.001f)
        assertEquals(0.98f, look.pressedScale, 0.001f)
        val light = checkNotNull(look.light)
        assertEquals(14, light.corner.value.toInt())
        assertEquals(0.24f, light.bevelTop, 0.001f)
        assertEquals(0.30f, light.bevelBottom, 0.001f)
        assertEquals(0.16f, light.shadowAlpha, 0.001f)
        assertEquals(1f, light.segmentAlpha, 0.001f)
        assertEquals(3, light.segmentThickness.value.toInt())
        assertTrue(light.latchedPrimaryTint)
    }

    @Test
    fun silhouetteIsBuiltInWithContourLineAndHaloSpec() {
        // r113: the Night — now Nocturne — scheme's own style,
        // appended last, same rule as Verve/Horizon/Meridian/
        // Heirloom/Blueprint/Sector — nobody moves to it
        // automatically. The working name was "Contour"; the
        // style ships named Silhouette and no "Contour" style
        // label exists in code. Dark-only by design: NO light
        // variant (the pick never asked for one), so on a light
        // scheme it keeps its flat 16dp read while the white
        // line and halo do not read on light fills. The bottom
        // line is white at 0.25 (latched: scheme primary); the
        // halo is the full 0.12 on cards and half that on
        // buttons, with a tight 3dp falloff, top + sides only.
        // The latched statement is the line alone: no faint
        // primary tint, no spring motion, no style corner on
        // cards. The rename is label-only: the stored scheme
        // name stays NIGHT, so existing Night users keep
        // their scheme.
        assertTrue(GsThemes.builtIn.any { it.id == "silhouette" && it.name == "Silhouette" })
        // r114: Morph appends after it, so Silhouette sits
        // second from last, not last.
        assertEquals(GsThemes.Silhouette, GsThemes.builtIn[GsThemes.builtIn.size - 2])
        assertEquals("silhouette", GsThemes.byId("silhouette").id)
        assertEquals("Nocturne", ColorScheme.NIGHT.displayName)
        val look = GsThemes.Silhouette.control
        assertEquals(16, look.corner.value.toInt())
        assertEquals(0f, look.topLight, 0.001f)
        assertEquals(0f, look.hairlineAlpha, 0.001f)
        assertEquals(0f, look.bevelTop, 0.001f)
        assertEquals(0f, look.bevelBottom, 0.001f)
        assertEquals(0, look.shadow.value.toInt())
        assertEquals(0f, look.shadowAlpha, 0.001f)
        assertEquals(0.25f, look.bottomLineAlpha, 0.001f)
        assertEquals(0.12f, look.haloAlpha, 0.001f)
        assertEquals(3, look.haloFalloff.value.toInt())
        assertEquals(0f, look.ringAlpha, 0.001f)
        assertEquals(0f, look.stitchAlpha, 0.001f)
        assertEquals(0f, look.segmentAlpha, 0.001f)
        assertEquals(0f, look.weaveAlpha, 0.001f)
        assertEquals(0f, look.grainAlpha, 0.001f)
        assertFalse(look.rivets)
        assertFalse(look.rectangularButtons)
        assertFalse(look.cardsUseStyleCorner)
        assertFalse(look.springMotion)
        assertFalse(look.pillButtons)
        assertFalse(look.latchedPrimaryTint)
        assertEquals(0.22f, look.pressedDim, 0.001f)
        assertEquals(0.98f, look.pressedScale, 0.001f)
        assertNull(look.light)
    }

    @Test
    fun haloBrightnessFactorFadesToZeroAtMinimumBrightness() {
        // r113: the halo's brightness factor — 1 at full
        // brightness, 0 at the setting's floor (0.05), linear
        // between, clamped outside.
        assertEquals(1f, haloBrightnessFactor(1f), 0.001f)
        assertEquals(0f, haloBrightnessFactor(0.05f), 0.001f)
        assertEquals(0f, haloBrightnessFactor(0f), 0.001f)
        assertEquals(0.5f, haloBrightnessFactor(0.525f), 0.001f)
    }

    @Test
    fun morphIsBuiltInLastWithChromaWashAndMorphingCorner() {
        // r114: Morph appends last (pinned here); Silhouette
        // sits second from last, and nothing else moved. The
        // Chroma wash: rest 0.09 (the pick's 8–10%), pressed
        // 0.18, latched full strength 0.30, cards/sheets half
        // strength 0.045. The morph: rest 20dp, pressed 16dp,
        // latched 24dp, on the shared state-change spring
        // (parked) / snap (moving) — springMotion with the
        // shared 0.7 damping, deliberately not retuned.
        // Cards take the static 20dp corner
        // (cardsUseStyleCorner); latched carries no faint
        // tint of its own (the latched wash IS the tint, and
        // the tint-only fallback lives in the draw code, not
        // in a field). No other style gains the wash or the
        // morph — the loop below is the guard.
        assertTrue(GsThemes.builtIn.any { it.id == "morph" && it.name == "Morph" })
        assertEquals(GsThemes.Morph, GsThemes.builtIn.last())
        assertEquals("morph", GsThemes.byId("morph").id)
        assertEquals("Material You", ColorScheme.MATERIAL_YOU.displayName)
        val look = GsThemes.Morph.control
        assertEquals(20, look.corner.value.toInt())
        assertEquals(0f, look.topLight, 0.001f)
        assertEquals(0f, look.hairlineAlpha, 0.001f)
        assertEquals(0f, look.bevelTop, 0.001f)
        assertEquals(0f, look.bevelBottom, 0.001f)
        assertEquals(4, look.shadow.value.toInt())
        assertEquals(0.28f, look.shadowAlpha, 0.001f)
        assertEquals(0.09f, look.washAlpha, 0.001f)
        assertEquals(0.18f, look.washPressedAlpha, 0.001f)
        assertEquals(0.30f, look.washLatchedAlpha, 0.001f)
        assertEquals(0.045f, look.washCardAlpha, 0.001f)
        assertTrue(look.morphCorners)
        assertEquals(16, look.pressedCorner.value.toInt())
        assertEquals(24, look.latchedCorner.value.toInt())
        assertTrue(look.springMotion)
        assertEquals(0.7f, look.springDamping, 0.001f)
        assertTrue(look.cardsUseStyleCorner)
        assertFalse(look.latchedPrimaryTint)
        assertFalse(look.pillButtons)
        assertFalse(look.rectangularButtons)
        assertEquals(0.14f, look.pressedDim, 0.001f)
        assertEquals(0.98f, look.pressedScale, 0.001f)
        assertEquals(0f, look.ringAlpha, 0.001f)
        assertEquals(0f, look.stitchAlpha, 0.001f)
        assertEquals(0f, look.segmentAlpha, 0.001f)
        assertEquals(0f, look.weaveAlpha, 0.001f)
        assertEquals(0f, look.grainAlpha, 0.001f)
        assertFalse(look.rivets)
        val light = checkNotNull(look.light)
        assertEquals(20, light.corner.value.toInt())
        assertEquals(0.22f, light.shadowAlpha, 0.001f)
        assertEquals(0.09f, light.washAlpha, 0.001f)
        assertEquals(0.18f, light.washPressedAlpha, 0.001f)
        assertEquals(0.30f, light.washLatchedAlpha, 0.001f)
        assertEquals(0.045f, light.washCardAlpha, 0.001f)
        assertTrue(light.morphCorners)
        assertEquals(16, light.pressedCorner.value.toInt())
        assertEquals(24, light.latchedCorner.value.toInt())
        assertTrue(light.springMotion)
        assertTrue(light.cardsUseStyleCorner)
        for (other in GsThemes.builtIn.filter { it.id != "morph" }) {
            assertEquals(0f, other.control.washAlpha, 0.001f)
            assertEquals(0f, other.control.washPressedAlpha, 0.001f)
            assertEquals(0f, other.control.washLatchedAlpha, 0.001f)
            assertEquals(0f, other.control.washCardAlpha, 0.001f)
            assertFalse(other.control.morphCorners)
            assertEquals(0f, other.control.light?.washAlpha ?: 0f, 0.001f)
            assertFalse(other.control.light?.morphCorners == true)
        }
    }
}
