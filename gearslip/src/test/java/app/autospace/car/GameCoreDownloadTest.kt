package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameCoreDownloadTest {
    private val sampleManifest = """
        {
          "cores": [
            {
              "key": "fceumm",
              "file": "fceumm_libretro_android.so",
              "size": 2878224,
              "sha256": "d6231558b5a843cce41cb1aafbe696497acf0650cbf68f14e54d452e58c24420",
              "consoles": ["nes"]
            },
            {
              "key": "picodrive",
              "file": "picodrive_libretro_android.so",
              "size": 1619096,
              "sha256": "8d9158d751cb6a5b07c10c2017759a81875d29acbc97d340a7ec75f995c7fe93",
              "consoles": ["32x"]
            },
            {
              "key": "broken",
              "file": "broken.so"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parseManifestReadsEntriesAndSkipsMalformedRows() {
        val entries = GameCoreDownload.parseManifest(sampleManifest)
        assertEquals(2, entries.size)
        val fceumm = entries.first { it.key == "fceumm" }
        assertEquals("fceumm_libretro_android.so", fceumm.file)
        assertEquals(2878224L, fceumm.size)
        assertEquals(
            "d6231558b5a843cce41cb1aafbe696497acf0650cbf68f14e54d452e58c24420",
            fceumm.sha256
        )
        assertEquals(listOf("nes"), fceumm.consoles)
        assertEquals(
            "picodrive",
            GameCores.entryForSystem(entries, "32x")!!.key
        )
    }

    @Test
    fun parseManifestRejectsGarbage() {
        assertEquals(emptyList<GameCoreEntry>(), GameCoreDownload.parseManifest("not json"))
        assertEquals(emptyList<GameCoreEntry>(), GameCoreDownload.parseManifest("{}"))
        assertEquals(
            emptyList<GameCoreEntry>(),
            GameCoreDownload.parseManifest("""{"cores": "nope"}""")
        )
    }

    @Test
    fun sha256HexMatchesKnownDigest() {
        // SHA-256 of "abc", the standard test vector.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            GameCoreDownload.sha256Hex("abc".toByteArray())
        )
        assertEquals(64, GameCoreDownload.sha256Hex(ByteArray(0)).length)
    }

    @Test
    fun isUpdateAvailableComparesCaseInsensitively() {
        assertFalse(GameCoreDownload.isUpdateAvailable(null, "abc"))
        assertFalse(GameCoreDownload.isUpdateAvailable("ABC", "abc"))
        assertTrue(GameCoreDownload.isUpdateAvailable("aaa", "abc"))
    }
}
