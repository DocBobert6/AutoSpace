package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** r81 icon packs: appfilter parsing, pack resolution, and override storage. */
class IconPackTest {

    private val xml = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <item component="ComponentInfo{com.spotify.music/com.spotify.music.MainActivity}" drawable="spotify" />
            <item drawable="youtube" component="ComponentInfo{com.google.android.youtube/com.google.android.youtube.HomeActivity}" />
            <item component="ComponentInfo{com.example.app/com.example.app.Launcher}" drawable="example" />
            <item component="ComponentInfo{com.example.app/com.example.app.SettingsActivity}" drawable="example_settings" />
            <iconback img1="back" />
            <item component="ComponentInfo{com.broken.app/com.broken.app.Main}" />
            <item drawable="nodraw" />
            <item component="not-a-component" drawable="weird" />
        </resources>
    """.trimIndent()

    private val mapping = parseAppFilter(xml)

    @Test
    fun parseAppFilterMapsComponentsToDrawables() {
        assertEquals(4, mapping.size)
        assertEquals("spotify", mapping["com.spotify.music/com.spotify.music.MainActivity"])
        assertEquals("youtube", mapping["com.google.android.youtube/com.google.android.youtube.HomeActivity"])
        assertEquals("example", mapping["com.example.app/com.example.app.Launcher"])
        assertEquals("example_settings", mapping["com.example.app/com.example.app.SettingsActivity"])
    }

    @Test
    fun parseAppFilterHandlesEitherAttributeOrder() {
        // The youtube entry lists drawable before component; it still maps.
        assertEquals("youtube", mapping["com.google.android.youtube/com.google.android.youtube.HomeActivity"])
    }

    @Test
    fun parseAppFilterSkipsMalformedItems() {
        assertNull(mapping["com.broken.app/com.broken.app.Main"])
        assertEquals(4, mapping.size)
    }

    @Test
    fun parseAppFilterKeepsFileOrder() {
        assertEquals(
            listOf(
                "com.spotify.music/com.spotify.music.MainActivity",
                "com.google.android.youtube/com.google.android.youtube.HomeActivity",
                "com.example.app/com.example.app.Launcher",
                "com.example.app/com.example.app.SettingsActivity",
            ),
            mapping.keys.toList(),
        )
    }

    @Test
    fun packDrawableForExactComponentWins() {
        assertEquals(
            "spotify",
            packDrawableFor(mapping, "com.spotify.music/com.spotify.music.MainActivity", "com.spotify.music"),
        )
    }

    @Test
    fun packDrawableForFallsBackToFirstEntryForPackage() {
        // Car components are services, not the launcher activities packs
        // map, so an unmapped activity of a mapped package takes the
        // first entry in file order for that package.
        assertEquals(
            "example",
            packDrawableFor(mapping, "com.example.app/com.example.app.OtherActivity", "com.example.app"),
        )
    }

    @Test
    fun packDrawableForReturnsNullWithoutMatch() {
        assertNull(packDrawableFor(mapping, "com.unknown.app/com.unknown.app.Main", "com.unknown.app"))
        assertNull(packDrawableFor(mapping, null, null))
    }

    @Test
    fun resolvePackDrawableOverrideWinsOverExact() {
        val overrides = mapOf("com.spotify.music/com.spotify.music.MainActivity" to "spotify_alt")
        assertEquals(
            "spotify_alt",
            resolvePackDrawable(overrides, mapping, "com.spotify.music/com.spotify.music.MainActivity", "com.spotify.music"),
        )
    }

    @Test
    fun resolvePackDrawableOverrideWinsOverPackageFallback() {
        val overrides = mapOf("com.example.app/com.example.app.OtherActivity" to "example_alt")
        assertEquals(
            "example_alt",
            resolvePackDrawable(overrides, mapping, "com.example.app/com.example.app.OtherActivity", "com.example.app"),
        )
    }

    @Test
    fun resolvePackDrawableFallsThroughToSystemWhenNothingMatches() {
        assertNull(
            resolvePackDrawable(emptyMap(), mapping, "com.unknown.app/com.unknown.app.Main", "com.unknown.app"),
        )
    }

    @Test
    fun encodeDecodeOverridesRoundTrip() {
        val overrides = mapOf(
            "com.spotify.music/com.spotify.music.MainActivity" to "spotify_alt",
            "messages:com.example.messenger" to "messenger_alt",
        )
        assertEquals(overrides, decodeOverrides(encodeOverrides(overrides)))
    }

    @Test
    fun decodeOverridesSkipsMalformedSegments() {
        assertEquals(
            mapOf("com.a.app/com.a.app.Main" to "a_icon"),
            decodeOverrides("nonsense,com.a.app/com.a.app.Main=a_icon,=empty,trailing="),
        )
    }

    @Test
    fun decodeEmptyIsEmpty() {
        assertEquals(emptyMap<String, String>(), decodeOverrides(""))
        assertEquals(emptyMap<String, String>(), decodeOverrides("   "))
    }
}
