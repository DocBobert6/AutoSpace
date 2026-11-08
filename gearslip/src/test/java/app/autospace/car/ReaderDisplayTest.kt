package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic coverage for the r187 reader display settings (Phase 5 + G4). */
class ReaderDisplayTest {

    @Test
    fun palettesMatchMarinerSafariValues() {
        assertEquals(ReaderPalette("#FFFFFF", "#1B1B1B", "#1B1B1B", false), readerPalette("white"))
        assertEquals(ReaderPalette("#F8F1E3", "#4F321C", "#4F321C", false), readerPalette("sepia"))
        assertEquals(ReaderPalette("#4A4A4D", "#D7D7D9", "#D7D7D9", true), readerPalette("gray"))
        assertEquals(ReaderPalette("#121212", "#B0B0B0", "#B0B0B0", true), readerPalette("night"))
    }

    @Test
    fun paletteFallsBackToWhiteForUnknownKeys() {
        assertEquals(readerPalette("white"), readerPalette("bogus"))
        assertEquals(readerPalette("white"), readerPalette(""))
        assertEquals(readerPalette("white"), readerPalette("light"))
        assertEquals(readerPalette("night"), readerPalette("dark"))
    }

    @Test
    fun themeKeyNormalizes() {
        assertEquals("white", readerThemeKey("white"))
        assertEquals("sepia", readerThemeKey("sepia"))
        assertEquals("gray", readerThemeKey("gray"))
        assertEquals("night", readerThemeKey("night"))
        assertEquals("white", readerThemeKey("light"))
        assertEquals("night", readerThemeKey("dark"))
        assertEquals("white", readerThemeKey(""))
        assertEquals("white", readerThemeKey("bogus"))
    }

    @Test
    fun fontKeyNormalizes() {
        assertEquals("opensans", readerFontKey("opensans"))
        assertEquals("roboto", readerFontKey("roboto"))
        assertEquals("inter", readerFontKey("inter"))
        assertEquals("opensans", readerFontKey("serif"))
        assertEquals("opensans", readerFontKey("mono"))
        assertEquals("roboto", readerFontKey("sans"))
        // No custom-font import exists in AutoSpace, so "custom" folds
        // to Open Sans instead of naming a font that can never load.
        assertEquals("opensans", readerFontKey("custom"))
        assertEquals("opensans", readerFontKey(""))
        assertEquals("opensans", readerFontKey("bogus"))
    }

    @Test
    fun fontStacksNameTheirFamily() {
        assertTrue(readerFontStack("inter").startsWith("'Inter'"))
        assertTrue(readerFontStack("roboto").startsWith("'Roboto'"))
        assertTrue(readerFontStack("opensans").startsWith("'Open Sans'"))
        assertTrue(readerFontStack("bogus").startsWith("'Open Sans'"))
    }

    @Test
    fun fontSizeClampsToFourteenTwentyEight() {
        assertEquals(14, coerceReaderFontSize(13))
        assertEquals(14, coerceReaderFontSize(14))
        assertEquals(22, coerceReaderFontSize(22))
        assertEquals(28, coerceReaderFontSize(28))
        assertEquals(28, coerceReaderFontSize(29))
    }

    @Test
    fun lineMultRoundsToOneDecimalAndClamps() {
        assertEquals(1.1f, coerceReaderLineMult(1.05f), 0.001f)
        assertEquals(1.1f, coerceReaderLineMult(0.9f), 0.001f)
        assertEquals(1.1f, coerceReaderLineMult(1.1f), 0.001f)
        assertEquals(1.6f, coerceReaderLineMult(1.6f), 0.001f)
        assertEquals(1.6f, coerceReaderLineMult(1.64f), 0.001f)
        assertEquals(1.7f, coerceReaderLineMult(1.66f), 0.001f)
        assertEquals(2.2f, coerceReaderLineMult(2.2f), 0.001f)
        assertEquals(2.2f, coerceReaderLineMult(2.25f), 0.001f)
    }

    @Test
    fun shellKeepsTodaysDefaults() {
        val article = Article(
            url = "https://example.com/a",
            title = "Title",
            byline = "By Someone",
            siteName = "Example",
            contentHtml = "<p>Hi.</p>",
        )
        val html = buildReaderShell(article, "#FFFFFF", "#1B1B1B", "#1B1B1B")
        assertTrue(html.contains("font-family: system-ui, sans-serif;"))
        assertTrue(html.contains("font-size: 22px;"))
        assertTrue(html.contains("line-height: 1.6;"))
        assertTrue(html.contains("background: #FFFFFF;"))
        assertTrue(html.contains("color: #1B1B1B;"))
    }

    @Test
    fun shellUsesDisplaySettings() {
        val article = Article(
            url = "https://example.com/a",
            title = "Title",
            byline = "",
            siteName = "",
            contentHtml = "<p>Hi.</p>",
        )
        val html = buildReaderShell(
            article, "#121212", "#B0B0B0", "#B0B0B0",
            fontSizePx = 28,
            lineHeight = 2.2f,
            fontFamily = "'Inter', 'Roboto', system-ui, sans-serif",
            fontFaceCss = "@font-face { font-family: 'Inter'; }",
        )
        assertTrue(html.contains("font-size: 28px;"))
        assertTrue(html.contains("line-height: 2.2;"))
        assertTrue(html.contains("font-family: 'Inter', 'Roboto', system-ui, sans-serif;"))
        assertTrue(html.contains("@font-face { font-family: 'Inter'; }"))
        assertTrue(html.contains("background: #121212;"))
    }
}
