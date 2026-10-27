package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameLibraryTest {

    @Test
    fun consoleSetIsExactlyTheEightOrderedConsoles() {
        assertEquals(
            listOf("nes", "sg1000", "sms", "genesis", "snes", "segacd", "32x", "ps1"),
            GameConsoles.ALL.map { it.key },
        )
        assertEquals(
            listOf("NES", "SG-1000", "Master System", "Genesis", "Super Nintendo", "Sega CD", "32X", "PlayStation"),
            GameConsoles.ALL.map { it.label },
        )
    }

    @Test
    fun extensionsMatchMarinersMapPlusSg1000() {
        assertEquals(listOf("nes"), GameConsoles.extensionsFor("nes"))
        assertEquals(listOf("smc", "sfc"), GameConsoles.extensionsFor("snes"))
        assertEquals(listOf("md", "gen"), GameConsoles.extensionsFor("genesis"))
        assertEquals(listOf("sg"), GameConsoles.extensionsFor("sg1000"))
        assertEquals(listOf("cue", "m3u", "chd"), GameConsoles.extensionsFor("ps1"))
        assertEquals(listOf("cue", "m3u", "chd"), GameConsoles.extensionsFor("segacd"))
        assertEquals(emptyList<String>(), GameConsoles.extensionsFor("gba"))
        assertEquals(emptyList<String>(), GameConsoles.extensionsFor("ds"))
    }

    @Test
    fun discExtensionWithoutTargetIsPlayStation() {
        assertEquals("ps1", GameConsoles.systemForExtension("cue", null))
        assertEquals("ps1", GameConsoles.systemForExtension("m3u", null))
        assertEquals("ps1", GameConsoles.systemForExtension("chd", null))
    }

    @Test
    fun discExtensionWithDiscConsoleTargetBelongsToThatConsole() {
        assertEquals("segacd", GameConsoles.systemForExtension("cue", "segacd"))
        assertEquals("segacd", GameConsoles.systemForExtension("m3u", "segacd"))
        assertEquals("ps1", GameConsoles.systemForExtension("cue", "ps1"))
    }

    @Test
    fun discExtensionWithNonDiscTargetStillFallsBackToPlayStation() {
        assertEquals("ps1", GameConsoles.systemForExtension("cue", "genesis"))
        assertEquals("ps1", GameConsoles.systemForExtension("cue", "nes"))
    }

    @Test
    fun plainExtensionsMapCaseInsensitivelyAndUnknownIsNull() {
        assertEquals("snes", GameConsoles.systemForExtension("SMC", null))
        assertEquals("genesis", GameConsoles.systemForExtension("MD", null))
        assertNull(GameConsoles.systemForExtension("xyz", null))
        assertNull(GameConsoles.systemForExtension("", null))
    }

    @Test
    fun matchFileSkipsZipsAndExtensionlessNames() {
        assertEquals("nes", GameLibrary.matchFile("game.nes", null))
        assertEquals("genesis", GameLibrary.matchFile("SONIC.MD", null))
        assertNull(GameLibrary.matchFile("game.zip", null))
        assertNull(GameLibrary.matchFile("game", null))
        assertNull(GameLibrary.matchFile("game.xyz", null))
    }

    @Test
    fun matchZipEntryUsesBareNameAndSkipsDirsAndNestedZips() {
        assertEquals("genesis", GameLibrary.matchZipEntry("roms/sonic.md", null))
        assertEquals("nes", GameLibrary.matchZipEntry("game.nes", null))
        assertNull(GameLibrary.matchZipEntry("roms/", null))
        assertNull(GameLibrary.matchZipEntry("inner.zip", null))
        assertNull(GameLibrary.matchZipEntry("", null))
        assertNull(GameLibrary.matchZipEntry("notes.txt", null))
    }

    @Test
    fun safeFileNameKeepsSpacesAndStripsTheRest() {
        assertEquals("Super Mario Bros.nes", GameLibrary.safeFileName("Super Mario Bros.nes"))
        assertEquals("a_b_c_.nes", GameLibrary.safeFileName("a/b:c?.nes"))
        assertEquals("game", GameLibrary.safeFileName(""))
        assertEquals("game", GameLibrary.safeFileName("   "))
    }

    @Test
    fun titleForFileNameDropsOnlyTheLastExtension() {
        assertEquals("Super Mario Bros", GameLibrary.titleForFileName("Super Mario Bros.nes"))
        assertEquals("game", GameLibrary.titleForFileName("game"))
        assertEquals("archive.tar", GameLibrary.titleForFileName("archive.tar.gz"))
    }

    @Test
    fun knownKeysAreSystemSlashFileName() {
        val games = listOf(
            GameEntry("1", "A", "nes", "a.nes", 1L),
            GameEntry("2", "B", "snes", "b.smc", 2L),
        )
        assertEquals(setOf("nes/a.nes", "snes/b.smc"), GameLibrary.knownKeys(games))
    }

    @Test
    fun jsonRoundTripPreservesQuotesBackslashesAndFlags() {
        val games = listOf(
            GameEntry("id-1", "He said \"hi\" \\ now", "nes", "a.nes", 123L, 456L, true),
            GameEntry("id-2", "Plain", "ps1", "b.cue", 789L),
        )
        val parsed = GameLibrary.parse(GameLibrary.toJson(games))
        assertEquals(games, parsed)
    }

    @Test
    fun parseMalformedInputIsEmptyAndBadEntriesSkip() {
        assertEquals(emptyList<GameEntry>(), GameLibrary.parse("not json"))
        assertEquals(emptyList<GameEntry>(), GameLibrary.parse(""))
        assertEquals(emptyList<GameEntry>(), GameLibrary.parse("{}"))
        val mixed = GameLibrary.toJson(listOf(GameEntry("1", "Good", "nes", "g.nes", 1L)))
            .dropLast(1) + ",{\"id\":\"2\"}]"
        val parsed = GameLibrary.parse(mixed)
        assertEquals(1, parsed.size)
        assertEquals("Good", parsed[0].title)
    }

    @Test
    fun cueFilesReadQuotedAndUnquotedFileLines() {
        val text = "FILE \"track 01.bin\" BINARY\n  TRACK 1 AUDIO\nFILE track02.bin BINARY\nREM a comment\n"
        assertEquals(listOf("track 01.bin", "track02.bin"), GameLibrary.cueFiles(text))
    }

    @Test
    fun cueFilesReducePathsToBareNames() {
        val text = "FILE \"subdir\\track.bin\" BINARY\n"
        assertEquals(listOf("track.bin"), GameLibrary.cueFiles(text))
    }

    @Test
    fun m3uFilesSkipCommentsAndBlanksAndReducePaths() {
        val text = "#EXTM3U\n\nsubdir/track1.bin\ntrack2.bin\n# a comment\n"
        assertEquals(listOf("track1.bin", "track2.bin"), GameLibrary.m3uFiles(text))
    }
}
