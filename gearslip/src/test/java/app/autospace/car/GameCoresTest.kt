package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameCoresTest {
    @Test
    fun coreFileNameForMapsAllEightConsoles() {
        assertEquals("fceumm_libretro_android.so", GameCores.coreFileNameFor("nes"))
        assertEquals("snes9x_libretro_android.so", GameCores.coreFileNameFor("snes"))
        assertEquals(
            "genesis_plus_gx_libretro_android.so",
            GameCores.coreFileNameFor("genesis")
        )
        assertEquals(
            "genesis_plus_gx_libretro_android.so",
            GameCores.coreFileNameFor("sms")
        )
        assertEquals(
            "genesis_plus_gx_libretro_android.so",
            GameCores.coreFileNameFor("sg1000")
        )
        assertEquals(
            "genesis_plus_gx_libretro_android.so",
            GameCores.coreFileNameFor("segacd")
        )
        assertEquals("picodrive_libretro_android.so", GameCores.coreFileNameFor("32x"))
        assertEquals(
            "swanstation_libretro_android.so",
            GameCores.coreFileNameFor("ps1")
        )
        assertNull(GameCores.coreFileNameFor("ds"))
        assertNull(GameCores.coreFileNameFor("unknown"))
    }

    @Test
    fun everyConsoleResolvesToAManifestEntry() {
        val systems = listOf(
            "nes", "sg1000", "sms", "genesis", "snes", "segacd", "32x", "ps1"
        )
        for (system in systems) {
            val entry = GameCores.entryForSystem(GameCores.MANIFEST_ENTRIES, system)
            assertTrue("no entry for $system", entry != null)
            assertEquals(GameCores.coreFileNameFor(system), entry!!.file)
        }
        assertNull(
            GameCores.entryForSystem(GameCores.MANIFEST_ENTRIES, "ds")
        )
    }

    @Test
    fun manifestEntriesCarryPublishedSizes() {
        val byKey = GameCores.MANIFEST_ENTRIES.associateBy { it.key }
        assertEquals(2878224L, byKey["fceumm"]!!.size)
        assertEquals(11116376L, byKey["genesis_plus_gx"]!!.size)
        assertEquals(2601680L, byKey["snes9x"]!!.size)
        assertEquals(26295656L, byKey["swanstation"]!!.size)
        assertEquals(1619096L, byKey["picodrive"]!!.size)
        for (entry in GameCores.MANIFEST_ENTRIES) {
            assertEquals(64, entry.sha256.length)
            assertTrue(entry.sha256.all { it in '0'..'9' || it in 'a'..'f' })
        }
    }

    @Test
    fun dedupeKeysCollapsesSharedCores() {
        assertEquals(
            listOf("genesis_plus_gx", "snes9x"),
            GameCores.dedupeKeys(listOf("genesis", "segacd", "sms", "snes"))
        )
        assertEquals(
            listOf("fceumm", "picodrive", "swanstation"),
            GameCores.dedupeKeys(listOf("nes", "32x", "ps1"))
        )
        assertEquals(emptyList<String>(), GameCores.dedupeKeys(listOf("ds")))
    }

    @Test
    fun manifestWithPinnedEntriesAddsMissingOnesOnly() {
        val fetched = listOf(GameCores.MANIFEST_ENTRIES.first { it.key == "fceumm" })
        val merged = GameCores.manifestWithPinnedEntries(fetched)
        assertEquals(5, merged.size)
        assertEquals(
            GameCores.MANIFEST_ENTRIES,
            GameCores.manifestWithPinnedEntries(GameCores.MANIFEST_ENTRIES)
        )
    }

    @Test
    fun urlForUsesTheCoresReleaseBase() {
        val entry = GameCores.MANIFEST_ENTRIES.first { it.key == "snes9x" }
        assertEquals(
            "https://github.com/DocBobert6/NotscapeMariner/releases/download/cores/" +
                "snes9x_libretro_android.so",
            GameCores.urlFor(entry)
        )
    }
}
