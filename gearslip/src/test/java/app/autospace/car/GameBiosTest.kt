package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameBiosTest {
    @Test
    fun expectedFilesPerConsole() {
        assertEquals(listOf("bios_MD.bin"), GameBios.expectedFor("genesis"))
        assertEquals(
            listOf("bios_CD_U.bin", "bios_CD_E.bin", "bios_CD_J.bin"),
            GameBios.expectedFor("segacd")
        )
        assertEquals(
            listOf("scph5500.bin", "scph5501.bin", "scph5502.bin"),
            GameBios.expectedFor("ps1")
        )
        for (console in listOf("nes", "snes", "sms", "sg1000", "32x")) {
            assertTrue(
                "$console should need no BIOS files",
                GameBios.expectedFor(console).isEmpty()
            )
        }
    }

    @Test
    fun notesOnlyWhereGrounded() {
        val segacd = GameBios.noteFor("segacd")
        assertTrue(segacd != null && segacd.contains("needs a BIOS"))
        val genesis = GameBios.noteFor("genesis")
        assertTrue(genesis != null && genesis.contains("System Boot ROM"))
        // PlayStation: Mariner lists the files with no boot claim,
        // so no required-ness note is made here either.
        assertNull(GameBios.noteFor("ps1"))
        assertNull(GameBios.noteFor("nes"))
    }
}
