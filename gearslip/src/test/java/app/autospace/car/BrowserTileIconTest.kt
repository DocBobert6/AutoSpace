package app.autospace.car

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import app.autospace.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * r116: the Browser tile's Mariner circle. The circle is a drawable the tile and rail load by
 * resource id, so these tests pin the assignment — which built-in carries it — not the pixels.
 * That the drawable itself exists is proven by compilation: both call sites reference it.
 */
class BrowserTileIconTest {

    @Test
    fun browserBuiltInCarriesTheMarinerCircle() {
        assertEquals(R.drawable.browser_mariner_circle, BuiltInApps.Web.circleIconRes)
    }

    @Test
    fun noOtherBuiltInCarriesACircleBitmap() {
        BuiltInApps.all
            .filter { it !== BuiltInApps.Web }
            .forEach { assertNull("${it.id} must keep its glyph", it.circleIconRes) }
    }

    @Test
    fun browserKeepsItsGlyphAsFallback() {
        // The magnifier stays on the definition: TileIcon and the rail prefer the bitmap, so
        // it never draws while the circle loads — but removing it would leave a tile with
        // neither bitmap nor glyph if the drawable ever went missing.
        assertEquals(Icons.Filled.Search, BuiltInApps.Web.glyph)
    }
}
