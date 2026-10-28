package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** r80 Pairs: approved list, mapping, and derived current-pair lookup. */
class ThemePairsTest {

    @Test
    fun pairsAreExactlyTheNineApprovedInOrder() {
        assertEquals(
            listOf("Pitlane + Pitlane", "Verve + Verve", "Frost + Canvas", "Meridian + Meridian", "Heritage + Heirloom", "Canvas + Blueprint", "Circuit + Sector", "Nocturne + Silhouette", "Material You + Morph"),
            themePairs.map { it.label },
        )
    }

    @Test
    fun pitlanePairSetsPitlaneStyleAndScheme() {
        val pair = themePairs[0]
        assertEquals("pitlane", pair.styleId)
        assertEquals(ColorScheme.PITLANE, pair.scheme)
    }

    @Test
    fun vervePairSetsVerveStyleAndScheme() {
        val pair = themePairs[1]
        assertEquals("verve", pair.styleId)
        assertEquals(ColorScheme.VERVE, pair.scheme)
    }

    @Test
    fun frostCanvasPairIsTheDefault() {
        val pair = themePairs[2]
        assertEquals("frost", pair.styleId)
        assertEquals(ColorScheme.NEUTRAL, pair.scheme)
    }

    @Test
    fun meridianPairSetsMeridianStyleAndScheme() {
        val pair = themePairs[3]
        assertEquals("meridian", pair.styleId)
        assertEquals(ColorScheme.MERIDIAN, pair.scheme)
    }

    @Test
    fun heritagePairSetsHeirloomStyleAndScheme() {
        val pair = themePairs[4]
        assertEquals("heirloom", pair.styleId)
        assertEquals(ColorScheme.HERITAGE, pair.scheme)
    }

    @Test
    fun canvasPairSetsBlueprintStyleAndScheme() {
        val pair = themePairs[5]
        assertEquals("blueprint", pair.styleId)
        assertEquals(ColorScheme.NEUTRAL, pair.scheme)
    }

    @Test
    fun circuitPairSetsSectorStyleAndScheme() {
        val pair = themePairs[6]
        assertEquals("sector", pair.styleId)
        assertEquals(ColorScheme.CIRCUIT, pair.scheme)
    }

    @Test
    fun nocturnePairSetsSilhouetteStyleAndScheme() {
        val pair = themePairs[7]
        assertEquals("silhouette", pair.styleId)
        assertEquals(ColorScheme.NIGHT, pair.scheme)
    }

    @Test
    fun materialYouPairSetsMorphStyleAndScheme() {
        // r114: the pair label is "Material You + Morph" —
        // pattern-consistent with every other pair (scheme +
        // style); the blend's name, Chroma, lives in the
        // style's own documentation, not in the pair label.
        val pair = themePairs[8]
        assertEquals("morph", pair.styleId)
        assertEquals(ColorScheme.MATERIAL_YOU, pair.scheme)
    }

    @Test
    fun lookupFindsEachPair() {
        assertEquals(themePairs[0], themePairFor("pitlane", ColorScheme.PITLANE))
        assertEquals(themePairs[1], themePairFor("verve", ColorScheme.VERVE))
        assertEquals(themePairs[2], themePairFor("frost", ColorScheme.NEUTRAL))
        assertEquals(themePairs[3], themePairFor("meridian", ColorScheme.MERIDIAN))
        assertEquals(themePairs[4], themePairFor("heirloom", ColorScheme.HERITAGE))
        assertEquals(themePairs[5], themePairFor("blueprint", ColorScheme.NEUTRAL))
        assertEquals(themePairs[6], themePairFor("sector", ColorScheme.CIRCUIT))
        assertEquals(themePairs[7], themePairFor("silhouette", ColorScheme.NIGHT))
        assertEquals(themePairs[8], themePairFor("morph", ColorScheme.MATERIAL_YOU))
    }

    @Test
    fun lookupReturnsNullForNonPairCombinations() {
        assertNull(themePairFor("frost", ColorScheme.PITLANE))
        assertNull(themePairFor("verve", ColorScheme.NEUTRAL))
        assertNull(themePairFor("horizon", ColorScheme.HORIZON))
        assertNull(themePairFor("edge", ColorScheme.NEUTRAL))
        assertNull(themePairFor("blueprint", ColorScheme.HERITAGE))
        assertNull(themePairFor("sector", ColorScheme.NEUTRAL))
        assertNull(themePairFor("silhouette", ColorScheme.NEUTRAL))
        assertNull(themePairFor("morph", ColorScheme.NEUTRAL))
    }
}
