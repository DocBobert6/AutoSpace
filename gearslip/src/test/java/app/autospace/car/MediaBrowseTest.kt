package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaBrowseTest {

    @Test
    fun browsableNodeGetsFolderEvenWhenPlayable() {
        assertEquals(BrowseFallbackGlyph.FOLDER, browseFallbackGlyph(browsable = true, playable = true))
    }

    @Test
    fun browsableOnlyNodeGetsFolder() {
        assertEquals(BrowseFallbackGlyph.FOLDER, browseFallbackGlyph(browsable = true, playable = false))
    }

    @Test
    fun playableOnlyNodeGetsMusicNote() {
        assertEquals(BrowseFallbackGlyph.MUSIC_NOTE, browseFallbackGlyph(browsable = false, playable = true))
    }

    @Test
    fun neitherBrowsableNorPlayableKeepsEmptyBox() {
        assertNull(browseFallbackGlyph(browsable = false, playable = false))
    }
}
