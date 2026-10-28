package app.autospace.car

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.autospace.car.theme.FrostedChromeAlpha
import app.autospace.media.ArtColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** r34 floating Home: card visibility, pill step-aside, and card width rules; r36 restore fix; r42 narrower card + smaller dismiss + style-shaped transport; r43 map behind the nav bar in floating mode only. */
class FloatingHomeTest {

    @Test
    fun cardShowsOnlyWhenEveryGatePasses() {
        assertTrue(shouldShowFloatingCard(true, false, SidePanel.NONE, true))
    }

    @Test
    fun cardHidesWhenAnyGateFails() {
        // Toggle off (the default): classic Home, no card.
        // Nothing playing: full-bleed map, no card.
        assertFalse(shouldShowFloatingCard(false, false, SidePanel.NONE, true))
        // Dismissed this session: map only, pill comes back.
        assertFalse(shouldShowFloatingCard(true, true, SidePanel.NONE, true))
        // Lyrics owns the side: classic split instead.
        assertFalse(shouldShowFloatingCard(true, false, SidePanel.LYRICS, true))
        // Not on Home: the card lives in CarHome only.
        assertFalse(shouldShowFloatingCard(true, false, SidePanel.NONE, false))
    }





    @Test
    fun playbackStartTransitionResurfacesButLevelDoesNot() {
        // Paused/stopped -> playing: the edge that brings the card back.
        assertTrue(shouldResurfaceOnPlayTransition(false, true))
        // Dismiss-while-playing + track change: playing stays true, no edge,
        // card stays hidden.
        assertFalse(shouldResurfaceOnPlayTransition(true, true))
        // Pausing: no resurface.
        assertFalse(shouldResurfaceOnPlayTransition(true, false))
        // Staying paused: no resurface.
        assertFalse(shouldResurfaceOnPlayTransition(false, false))
    }

    @Test
    fun cardWidthIs40PercentCappedAndFloored() {
        // r42: 800dp frame: 40% = 320dp, inside the band.
        assertEquals(320.dp, floatingCardWidth(800.dp))
        // Wide frame: capped at 380dp, not 40%.
        assertEquals(380.dp, floatingCardWidth(1000.dp))
        assertEquals(380.dp, floatingCardWidth(1920.dp))
        // Narrow frame: floored at 240dp so transport still fits.
        assertEquals(240.dp, floatingCardWidth(400.dp))
        // Tiny frame: never wider than the frame minus 24dp.
        assertEquals(176.dp, floatingCardWidth(200.dp))
    }

    @Test
    fun dismissButtonShrinksVisuallyButKeeps44dpTouchTarget() {
        // r42: the touch target never drops below the 44dp floor, the
        // drawn box sits inside it, and the glyph sits inside the box.
        assertEquals(44.dp, FloatingCardDismissTouchSize)
        assertEquals(36.dp, FloatingCardDismissDrawnSize)
        assertEquals(20.dp, FloatingCardDismissGlyphSize)
        assertTrue(FloatingCardDismissDrawnSize < FloatingCardDismissTouchSize)
        assertTrue(FloatingCardDismissGlyphSize < FloatingCardDismissDrawnSize)
    }

    @Test
    fun artCardDrawsNoPerimeterButPlainCardKeepsItsEdge() {
        // r133: with art the art IS the face, so no kit edge strokes
        // over it; without art the card keeps exactly today's edge.
        assertFalse(artCardDrawsPerimeter(true))
        assertTrue(artCardDrawsPerimeter(false))
    }

    @Test
    fun artCardCastsNoShadowButPlainCardKeepsItsShadow() {
        // r136: r133's perimeter flag killed every kit edge stroke,
        // and the 4dp Surface shadow was the one perimeter effect
        // left — a soft outline around the card over the map. With
        // art the card draws nothing at its edge; without art it
        // keeps exactly today's 4dp shadow.
        assertEquals(0.dp, cardArtElevation(true))
        assertEquals(4.dp, cardArtElevation(false))
    }

    @Test
    fun artPaletteFallsBackOnMissingOrMutedTint() {
        // r136: no tint, or a tint below the 0.30 saturation bar the
        // progress accent uses, means today's scheme treatment — the
        // card never tints grey from a muted cover.
        assertNull(floatingArtPalette(null))
        assertNull(floatingArtPalette(ArtColor.Tint(0f, 0.29f)))
        assertFalse(floatingArtUsable(ArtColor.Tint(0f, 0.29f)))
        assertTrue(floatingArtUsable(ArtColor.Tint(0f, 0.30f)))
        assertNotNull(floatingArtPalette(ArtColor.Tint(0f, 0.30f)))
    }

    @Test
    fun artPaletteKeepsHueFamilyAndDarkensTheSecondary() {
        // r136: both fills come from the art's hue — a red tint gives
        // red-dominant fills — and the secondary (previous, next, X)
        // is darker than the vivid primary (play, waveform).
        val palette = floatingArtPalette(ArtColor.Tint(0f, 0.8f))!!
        fun channels(color: Color): Triple<Int, Int, Int> {
            val argb = floatingColorArgb(color)
            return Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
        }
        val (pr, pg, pb) = channels(palette.primary)
        assertTrue(pr > pg && pr > pb)
        val (sr, sg, sb) = channels(palette.secondary)
        assertTrue(sr > sg && sr > sb)
        val primarySum = pr + pg + pb
        val secondarySum = sr + sg + sb
        assertTrue(secondarySum < primarySum)
    }

    @Test
    fun artPaletteWaveformTrackIsPrimaryAtPointFiveFiveAlpha() {
        // r144: the card's unfilled waveform bars sit at 0.55 alpha
        // (was 0.30) so the track reads over busy art; the filled
        // bars stay the opaque art primary, and bar thickness is
        // unchanged — only the track alpha moves.
        val palette = floatingArtPalette(ArtColor.Tint(0f, 0.8f))!!
        assertEquals(1f, palette.primary.alpha, 0.001f)
        assertEquals(0.55f, palette.waveformTrack.alpha, 0.001f)
        assertEquals(palette.primary.red, palette.waveformTrack.red, 0.001f)
        assertEquals(palette.primary.green, palette.waveformTrack.green, 0.001f)
        assertEquals(palette.primary.blue, palette.waveformTrack.blue, 0.001f)
    }

    @Test
    fun artGlyphAlwaysContrastsAtLeastFourPointFiveToOne() {
        // r136: the glyph is whichever of black/white contrasts more
        // against its fill, and one of the two always reaches 4.58:1
        // or better — swept across hues and lightnesses, the chosen
        // glyph never sits dark-on-dark or light-on-light.
        for (hue in listOf(0f, 60f, 120f, 200f, 280f)) {
            for (lightness in listOf(0.15f, 0.32f, 0.55f, 0.8f)) {
                val fill = Color.hsl(hue, 0.6f, lightness)
                val glyph = floatingArtGlyph(fill)
                assertTrue(glyph == Color.White || glyph == Color.Black)
                val ratio = contrastRatio(floatingColorArgb(glyph), floatingColorArgb(fill))
                assertTrue("hue $hue lightness $lightness ratio $ratio", ratio >= 4.5)
            }
        }
    }

    @Test
    fun transportRowDefaultsToCircles() {
        // r42: callers that pass no shape (full player, CompactPlayer)
        // keep circles; only the floating card passes gsShape().
        assertEquals(CircleShape, DefaultTransportShape)
    }

    @Test
    fun sharpCoverEdgeFeatherProfileIsLinearAndClamped() {
        // r139: the fade profile the sharp layer's edge gradients
        // are built from — 0 at the edge itself, 1 at (and beyond)
        // the fade width, linear in between, clamped on both sides.
        assertEquals(0f, edgeFeatherAlpha(0f, 24f), 0f)
        assertEquals(0.5f, edgeFeatherAlpha(12f, 24f), 0f)
        assertEquals(1f, edgeFeatherAlpha(24f, 24f), 0f)
        assertEquals(1f, edgeFeatherAlpha(100f, 24f), 0f)
        assertEquals(0f, edgeFeatherAlpha(-5f, 24f), 0f)
        // A non-positive fade width means no fade: fully opaque.
        assertEquals(1f, edgeFeatherAlpha(10f, 0f), 0f)
        assertEquals(1f, edgeFeatherAlpha(10f, -3f), 0f)
    }

    @Test
    fun sharpCoverEdgeFadeWidthIs10dp() {
        // r141: the judgment-call width, narrowed from r139's
        // 24dp — 24dp erased a visible band of art at the top of
        // a real cover, while 10dp softens the edge without
        // cutting into the art. One word changes it (and this pin).
        assertEquals(10.dp, SharpCoverEdgeFade)
    }

    @Test
    fun fitCoverRectCentersSquareCoverInWideCard() {
        // r141: a square cover fitted into a wide card occupies
        // the full height and is centered horizontally, leaving
        // equal blurred bands at the sides — exactly the case
        // r139's card-box strips got wrong.
        val rect = fitCoverRect(360f, 200f, 1000f, 1000f)
        assertEquals(80f, rect.left, 0.001f)
        assertEquals(0f, rect.top, 0.001f)
        assertEquals(280f, rect.right, 0.001f)
        assertEquals(200f, rect.bottom, 0.001f)
    }

    @Test
    fun fitCoverRectCentersWideCoverInTallCard() {
        // r141: a wide cover fitted into a tall card occupies the
        // full width and is centered vertically.
        val rect = fitCoverRect(200f, 300f, 1000f, 500f)
        assertEquals(0f, rect.left, 0.001f)
        assertEquals(100f, rect.top, 0.001f)
        assertEquals(200f, rect.right, 0.001f)
        assertEquals(200f, rect.bottom, 0.001f)
    }

    @Test
    fun fitCoverRectCentersTallCoverInWideCard() {
        // r141: a tall cover fitted into a wide card occupies the
        // full height and is centered horizontally.
        val rect = fitCoverRect(300f, 200f, 500f, 1000f)
        assertEquals(100f, rect.left, 0.001f)
        assertEquals(0f, rect.top, 0.001f)
        assertEquals(200f, rect.right, 0.001f)
        assertEquals(200f, rect.bottom, 0.001f)
    }

    @Test
    fun fitCoverRectFillsLayerWhenAspectMatches() {
        // r141: a cover with exactly the layer's aspect fills it.
        val rect = fitCoverRect(360f, 200f, 1800f, 1000f)
        assertEquals(0f, rect.left, 0.001f)
        assertEquals(0f, rect.top, 0.001f)
        assertEquals(360f, rect.right, 0.001f)
        assertEquals(200f, rect.bottom, 0.001f)
    }

    @Test
    fun fitCoverRectFallsBackToFullLayerForDegenerateBitmap() {
        // r141: a zero-size bitmap never divides by zero — the
        // caller falls back to the full layer.
        val rect = fitCoverRect(360f, 200f, 0f, 1000f)
        assertEquals(0f, rect.left, 0.001f)
        assertEquals(0f, rect.top, 0.001f)
        assertEquals(360f, rect.right, 0.001f)
        assertEquals(200f, rect.bottom, 0.001f)
    }




}
