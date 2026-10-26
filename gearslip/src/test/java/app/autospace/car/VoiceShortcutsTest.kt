package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic coverage for custom voice shortcuts (r19): no Android context needed. */
class VoiceShortcutsTest {

    @Test
    fun normalizeLowercasesTrimsAndCollapsesSpaces() {
        assertEquals("my home", VoiceShortcuts.normalize("  My   Home "))
    }

    @Test
    fun validateRejectsBlankKeywordAndBlankTarget() {
        assertNotNull(VoiceShortcuts.validate("  ", "somewhere", emptyList()))
        assertNotNull(VoiceShortcuts.validate("home", "   ", emptyList()))
    }

    @Test
    fun validateRejectsReservedWords() {
        assertNotNull(VoiceShortcuts.validate("stop", "somewhere", emptyList()))
        assertNotNull(VoiceShortcuts.validate("emergency", "somewhere", emptyList()))
        assertNotNull(VoiceShortcuts.validate("911", "somewhere", emptyList()))
        assertNotNull(VoiceShortcuts.validate("hang up", "somewhere", emptyList()))
        assertNotNull(VoiceShortcuts.validate("navigate", "somewhere", emptyList()))
        assertNotNull(VoiceShortcuts.validate("search", "somewhere", emptyList()))
        assertNotNull(VoiceShortcuts.validate("open", "somewhere", emptyList()))
        assertNotNull(VoiceShortcuts.validate("call", "somewhere", emptyList()))
    }

    @Test
    fun validateAcceptsOrdinaryKeyword() {
        assertNull(
            VoiceShortcuts.validate(
                "home", "1600 Main Street",
                listOf(VoiceShortcut("work", VoiceShortcutType.NAVIGATION, "1 Work Way")),
            ),
        )
    }

    @Test
    fun validateRejectsCaseInsensitiveDuplicatesButAllowsEditingItself() {
        val current = listOf(VoiceShortcut("home", VoiceShortcutType.NAVIGATION, "1 Home Way"))
        assertNotNull(VoiceShortcuts.validate("HOME", "somewhere else", current))
        assertNull(VoiceShortcuts.validate("home", "somewhere else", current, existingIndex = 0))
    }

    @Test
    fun validateEnforcesFiftyEntryCap() {
        val full = (1..50).map { VoiceShortcut("place$it", VoiceShortcutType.NAVIGATION, "address $it") }
        assertNotNull(VoiceShortcuts.validate("one more", "somewhere", full))
        assertTrue(VoiceShortcuts.validate("one more", "somewhere", full)!!.contains("Too many"))
    }

    @Test
    fun encodeDecodeRoundTripPreservesAllTypesAndSpecialCharacters() {
        val entries = listOf(
            VoiceShortcut("home", VoiceShortcutType.NAVIGATION, "123 Main St, Apt #4 | North"),
            VoiceShortcut("tunes", VoiceShortcutType.OPEN_APP, "com.example.music/.Main", "Music"),
            VoiceShortcut("news", VoiceShortcutType.BROWSER_SEARCH, "local news & weather"),
        )
        val decoded = VoiceShortcuts.decode(VoiceShortcuts.encode(entries))
        assertEquals(entries.sortedBy { it.keyword }, decoded)
    }

    @Test
    fun decodeIgnoresMalformedLines() {
        val decoded = VoiceShortcuts.decode("garbage\nNAVIGATION|home|123%20Main%20St|")
        assertEquals(1, decoded.size)
        assertEquals("home", decoded.first().keyword)
        assertFalse(decoded.any { it.keyword.isEmpty() })
    }
}
