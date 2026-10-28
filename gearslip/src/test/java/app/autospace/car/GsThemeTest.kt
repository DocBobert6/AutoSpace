package app.autospace.car

import androidx.compose.ui.unit.dp
import app.autospace.car.theme.GsThemes
import app.autospace.car.theme.HoldMarkerStyle
import app.autospace.car.theme.haloBrightnessFactor
import app.autospace.car.theme.foundryGlossAlpha
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
        // r115: the zoned rework cuts the corner (4dp chamfer) instead
        // of rounding it (6dp round).
        assertEquals(4, look.corner.value.toInt())
        assertEquals(4, look.chamferCut.value.toInt())
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
        assertEquals(4, light.corner.value.toInt())
        assertEquals(4, light.chamferCut.value.toInt())
        assertTrue(light.rectangularButtons)
        assertFalse(light.checkerStrip)
        assertFalse(GsThemes.builtIn.any { it.control.checkerStrip })
        assertFalse(GsThemes.builtIn.any { it.control.light?.checkerStrip == true })
        // Rectangular buttons are Pitlane-only: every other built-in
        // style keeps its existing shapes exactly.
        assertFalse(GsThemes.builtIn.filter { it.id != "pitlane" }.any { it.control.rectangularButtons })
        // The chamfer is Pitlane-only too.
        assertFalse(GsThemes.builtIn.filter { it.id != "pitlane" }.any { it.control.chamferCut > 0.dp })
        assertFalse(GsThemes.builtIn.filter { it.id != "pitlane" }.any { (it.control.light?.chamferCut ?: 0.dp) > 0.dp })
    }

    @Test
    fun pitLaneFidelityParametersArePitLaneOnly() {
        // r26: the Pit Lane material (weave, grain, rivets, LED, bat toggle,
        // engraved headers, bevel on latched) exists ONLY on Pit Lane, dark +
        // light. Every other built-in look carries none of it, so no other
        // style's appearance changes by construction.
        // r115: grain is gone (the fill zone is clean, the frame zone
        // carries weave + bolts only) and the bat toggle is superseded by
        // the rocker toggle; weave, rivets (now hex bolts), LED, engraved
        // headers and bevel-on-latched stay Pitlane-only.
        val pitlane = GsThemes.Pitlane.control
        assertEquals(0.05f, pitlane.weaveAlpha, 0.001f)
        assertEquals(0f, pitlane.grainAlpha, 0.001f)
        assertTrue(pitlane.rivets)
        assertTrue(pitlane.ledDot)
        assertFalse(pitlane.batToggle)
        assertTrue(pitlane.rockerToggle)
        assertTrue(pitlane.engravedHeader)
        assertTrue(pitlane.bevelOnLatched)
        val light = checkNotNull(pitlane.light)
        assertEquals(0.04f, light.weaveAlpha, 0.001f)
        assertEquals(0f, light.grainAlpha, 0.001f)
        assertTrue(light.rivets)
        assertTrue(light.ledDot)
        assertFalse(light.batToggle)
        assertTrue(light.rockerToggle)
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
            assertFalse(other.control.rockerToggle)
            assertEquals(0, other.control.chamferCut.value.toInt())
            assertFalse(other.control.engravedHeader)
            assertFalse(other.control.bevelOnLatched)
            val otherLight = other.control.light
            if (otherLight != null) {
                assertEquals(0f, otherLight.weaveAlpha, 0.001f)
                assertEquals(0f, otherLight.grainAlpha, 0.001f)
                assertFalse(otherLight.rivets)
                assertFalse(otherLight.ledDot)
                assertFalse(otherLight.batToggle)
                assertFalse(otherLight.rockerToggle)
                assertEquals(0, otherLight.chamferCut.value.toInt())
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
        // r142: Foundry appends after Morph, so Heirloom sits
        // sixth from last.
        // r143: Aurora appends after Foundry, so Heirloom sits
        // seventh from last.
        assertTrue(GsThemes.builtIn.any { it.id == "heirloom" && it.name == "Heirloom" })
        assertEquals(GsThemes.Heirloom, GsThemes.builtIn[GsThemes.builtIn.size - 7])
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
        // r142: Foundry appends after Morph, so Blueprint sits
        // fifth from last.
        // r143: Aurora appends after Foundry, so Blueprint sits
        // sixth from last.
        assertTrue(GsThemes.builtIn.any { it.id == "blueprint" && it.name == "Blueprint" })
        assertEquals(GsThemes.Blueprint, GsThemes.builtIn[GsThemes.builtIn.size - 6])
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
        // r142: Foundry appends after Morph, so Sector sits
        // fourth from last.
        // r143: Aurora appends after Foundry, so Sector sits
        // fifth from last.
        assertTrue(GsThemes.builtIn.any { it.id == "sector" && it.name == "Sector" })
        assertEquals(GsThemes.Sector, GsThemes.builtIn[GsThemes.builtIn.size - 5])
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
        // r142: Foundry appends after Morph, so Silhouette sits
        // third from last.
        // r143: Aurora appends after Foundry, so Silhouette sits
        // fourth from last.
        assertEquals(GsThemes.Silhouette, GsThemes.builtIn[GsThemes.builtIn.size - 4])
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
        // r142: Foundry appends after it, and r143 appends
        // Aurora last, so Morph sits third from last.
        assertEquals(GsThemes.Morph, GsThemes.builtIn[GsThemes.builtIn.size - 3])
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

    @Test
    fun foundryIsBuiltInSecondToLastWithGlossBandAndMachinedRim() {
        // r142: Foundry appends last (pinned here), and r143
        // appends Aurora last; Foundry sits second from last,
        // Morph third from last, and nothing else moved. The
        // Skeuomorphic style: 10dp corner, brushed-metal
        // gradient (topLight 0.10, bottomShade 0.08, 0.12 on
        // light fills), 6dp shadow at 0.35, white gloss band
        // 0.30 fading to 0 over the top 35% (pressed 0.12),
        // machined rim (1dp, white 0.35 top / black 0.30
        // bottom), pressed shadow 2dp, face down 1dp (sink),
        // DOT hold marker. Cards take the gradient + rim, not
        // the corner and not the gloss band
        // (cardsUseStyleCorner is false; the gloss band lives
        // only in gsSurface, which cards never call).
        assertTrue(GsThemes.builtIn.any { it.id == "foundry" && it.name == "Foundry" })
        assertEquals(GsThemes.Foundry, GsThemes.builtIn[GsThemes.builtIn.size - 2])
        assertEquals("foundry", GsThemes.byId("foundry").id)
        assertEquals("Workshop", ColorScheme.WORKSHOP.displayName)
        val look = GsThemes.Foundry.control
        assertEquals(10, look.corner.value.toInt())
        assertEquals(0.10f, look.topLight, 0.001f)
        assertEquals(0.08f, look.bottomShade, 0.001f)
        assertEquals(0.12f, look.bottomShadeOnLight, 0.001f)
        assertEquals(6, look.shadow.value.toInt())
        assertEquals(0.35f, look.shadowAlpha, 0.001f)
        assertEquals(0.30f, look.glossAlpha, 0.001f)
        assertEquals(0.12f, look.glossPressedAlpha, 0.001f)
        assertEquals(0.35f, look.glossFraction, 0.001f)
        assertTrue(look.machinedRim)
        assertEquals(0.35f, look.machinedRimTopAlpha, 0.001f)
        assertEquals(0.30f, look.machinedRimBottomAlpha, 0.001f)
        assertEquals(2, look.pressedShadow.value.toInt())
        assertEquals(1, look.sink.value.toInt())
        assertEquals(0.22f, look.pressedDim, 0.001f)
        assertEquals(0.98f, look.pressedScale, 0.001f)
        assertEquals(1f, look.containerAlpha, 0.001f)
        assertFalse(look.cardsUseStyleCorner)
        assertFalse(look.springMotion)
        assertFalse(look.latchedPrimaryTint)
        assertEquals(0f, look.ringAlpha, 0.001f)
        assertEquals(0f, look.stitchAlpha, 0.001f)
        assertEquals(0f, look.segmentAlpha, 0.001f)
        assertEquals(0f, look.weaveAlpha, 0.001f)
        assertEquals(0f, look.grainAlpha, 0.001f)
        assertFalse(look.rivets)
        val light = checkNotNull(look.light)
        assertEquals(10, light.corner.value.toInt())
        assertEquals(0.30f, light.shadowAlpha, 0.001f)
        assertEquals(0.30f, light.glossAlpha, 0.001f)
        assertEquals(0.12f, light.glossPressedAlpha, 0.001f)
        assertEquals(0.35f, light.glossFraction, 0.001f)
        assertTrue(light.machinedRim)
        assertEquals(2, light.pressedShadow.value.toInt())
        assertEquals(1, light.sink.value.toInt())
        // The gloss band, machined rim, and pressed shadow are
        // Foundry-only: no other built-in style carries them.
        for (other in GsThemes.builtIn.filter { it.id != "foundry" }) {
            assertEquals(0f, other.control.glossAlpha, 0.001f)
            assertEquals(0f, other.control.glossPressedAlpha, 0.001f)
            assertEquals(0f, other.control.glossFraction, 0.001f)
            assertFalse(other.control.machinedRim)
            assertEquals(0, other.control.pressedShadow.value.toInt())
            assertEquals(0f, other.control.light?.glossAlpha ?: 0f, 0.001f)
            assertFalse(other.control.light?.machinedRim == true)
        }
    }

    @Test
    fun auroraIsBuiltInLastWithDiagonalSheenWhiteRimAndTintedShadow() {
        // r143: Aurora appends last (pinned here); Foundry sits
        // second from last, and nothing else moved. The
        // Frutiger Aero style: 10dp corner, translucent scheme
        // fill at 0.75 alpha, diagonal sheen — a white band at
        // 45 degrees, 0.22 at rest rising to 0.30 pressed —
        // uniform 1dp white rim at 0.5 alpha, shadow 8dp at
        // 0.25 with a scheme-primary tint (strength 0.25, the
        // first tinted shadow in the tree), face dims 0.10
        // pressed, latched glow takes the scheme primary with
        // the sheen staying at rest alpha, DOT hold marker.
        // Cards take translucency + rim + sheen, not the corner
        // (cardsUseStyleCorner is false; the sheen lives in
        // auroraCardSheen, which cards call, while the control
        // band lives only in gsSurface, which cards never call).
        assertTrue(GsThemes.builtIn.any { it.id == "aurora" && it.name == "Aurora" })
        assertEquals(GsThemes.Aurora, GsThemes.builtIn.last())
        assertEquals("aurora", GsThemes.byId("aurora").id)
        assertEquals("Aurora Field", ColorScheme.AURORA_FIELD.displayName)
        val look = GsThemes.Aurora.control
        assertEquals(10, look.corner.value.toInt())
        assertEquals(0.75f, look.containerAlpha, 0.001f)
        assertEquals(8, look.shadow.value.toInt())
        assertEquals(0.25f, look.shadowAlpha, 0.001f)
        assertEquals(0.25f, look.shadowTintStrength, 0.001f)
        assertEquals(0.22f, look.sheenAlpha, 0.001f)
        assertEquals(0.30f, look.sheenPressedAlpha, 0.001f)
        assertEquals(45f, look.sheenAngleDegrees, 0.001f)
        assertEquals(0.5f, look.whiteRimAlpha, 0.001f)
        assertEquals(0.10f, look.pressedDim, 0.001f)
        assertTrue(look.latchedPrimaryTint)
        assertEquals(HoldMarkerStyle.DOT, look.holdMarker)
        assertFalse(look.cardsUseStyleCorner)
        assertFalse(look.springMotion)
        assertEquals(0f, look.glossAlpha, 0.001f)
        assertFalse(look.machinedRim)
        assertEquals(0f, look.ringAlpha, 0.001f)
        assertEquals(0f, look.stitchAlpha, 0.001f)
        assertEquals(0f, look.segmentAlpha, 0.001f)
        assertEquals(0f, look.weaveAlpha, 0.001f)
        assertEquals(0f, look.grainAlpha, 0.001f)
        assertFalse(look.rivets)
        val light = checkNotNull(look.light)
        assertEquals(10, light.corner.value.toInt())
        assertEquals(0.75f, light.containerAlpha, 0.001f)
        assertEquals(8, light.shadow.value.toInt())
        assertEquals(0.25f, light.shadowAlpha, 0.001f)
        assertEquals(0.25f, light.shadowTintStrength, 0.001f)
        assertEquals(0.22f, light.sheenAlpha, 0.001f)
        assertEquals(0.30f, light.sheenPressedAlpha, 0.001f)
        assertEquals(45f, light.sheenAngleDegrees, 0.001f)
        assertEquals(0.5f, light.whiteRimAlpha, 0.001f)
        assertEquals(0.10f, light.pressedDim, 0.001f)
        assertTrue(light.latchedPrimaryTint)
        // The sheen, white rim, and tinted shadow are
        // Aurora-only: no other built-in style carries them.
        for (other in GsThemes.builtIn.filter { it.id != "aurora" }) {
            assertEquals(0f, other.control.sheenAlpha, 0.001f)
            assertEquals(0f, other.control.sheenPressedAlpha, 0.001f)
            assertEquals(0f, other.control.sheenAngleDegrees, 0.001f)
            assertEquals(0f, other.control.whiteRimAlpha, 0.001f)
            assertEquals(0f, other.control.shadowTintStrength, 0.001f)
            assertEquals(0f, other.control.light?.sheenAlpha ?: 0f, 0.001f)
            assertEquals(0f, other.control.light?.whiteRimAlpha ?: 0f, 0.001f)
            assertEquals(0f, other.control.light?.shadowTintStrength ?: 0f, 0.001f)
        }
    }

    @Test
    fun foundryGlossAlphaScalesDownOnLightFills() {
        // r142: the gloss-lightness rule — dark fills keep the
        // full band, light fills (luminance > 0.5) scale to 60%.
        assertEquals(0.30f, foundryGlossAlpha(0.30f, 0.10f), 0.001f)
        assertEquals(0.12f, foundryGlossAlpha(0.12f, 0.10f), 0.001f)
        assertEquals(0.18f, foundryGlossAlpha(0.30f, 0.85f), 0.001f)
        assertEquals(0.072f, foundryGlossAlpha(0.12f, 0.85f), 0.001f)
        assertEquals(0.30f, foundryGlossAlpha(0.30f, 0.5f), 0.001f)
    }
}
