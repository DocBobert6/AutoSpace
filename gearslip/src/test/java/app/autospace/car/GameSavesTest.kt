package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSavesTest {
    @Test
    fun gameIdPrefersLibraryIdThenRomName() {
        assertEquals("abc123", GameSaves.gameIdFor("abc123", "/roms/game.smc"))
        assertEquals("rom-sonic", GameSaves.gameIdFor(null, "/roms/sonic.md"))
        assertEquals("rom-game", GameSaves.gameIdFor(null, "/roms/.md"))
    }

    @Test
    fun slotFileNamesMatchMarinerLayout() {
        assertEquals("slot1.state", GameSaves.stateFileName(1))
        assertEquals("slot4.state", GameSaves.stateFileName(4))
        assertEquals("slot1.png", GameSaves.thumbFileName(1))
        assertEquals("auto.state", GameSaves.stateFileName(GameSaves.AUTO_SLOT))
        assertEquals("auto.png", GameSaves.thumbFileName(GameSaves.AUTO_SLOT))
        assertEquals(4, GameSaves.SLOT_COUNT)
        assertEquals(-2, GameSaves.AUTO_SLOT)
        assertEquals(-1, GameSaves.NO_SLOT)
    }

    @Test
    fun archiveNamesRoundTrip() {
        assertEquals("auto-123456.state", GameSaves.archiveFileName(123456L))
        assertEquals("auto-123456.png", GameSaves.archiveThumbName(123456L))
        assertTrue(GameSaves.isArchiveName("auto-123456.state"))
        assertFalse(GameSaves.isArchiveName("slot1.state"))
        assertFalse(GameSaves.isArchiveName("auto.state"))
        assertEquals(123456L, GameSaves.archiveMillis("auto-123456.state"))
        assertNull(GameSaves.archiveMillis("auto-abc.state"))
    }

    @Test
    fun memoryCardIsExactly128KAndReadsAreCapped() {
        assertTrue(MemoryCard.isValidSize(128 * 1024))
        assertFalse(MemoryCard.isValidSize(128 * 1024 - 1))
        assertFalse(MemoryCard.isValidSize(0))
        val big = ByteArray(200 * 1024)
        assertEquals(128 * 1024 + 1, MemoryCard.readCapped(big).size)
        val small = ByteArray(100)
        assertEquals(100, MemoryCard.readCapped(small).size)
    }

    @Test
    fun discLabelsPreferCoreLabelsThenNumberedFallback() {
        assertEquals("My Disc", DiscSwap.displayLabel(0, "My Disc"))
        assertEquals("Disc 1", DiscSwap.displayLabel(0, null))
        assertEquals("Disc 2", DiscSwap.displayLabel(1, ""))
        assertEquals("Disc 3", DiscSwap.displayLabel(2, "   "))
    }
}
