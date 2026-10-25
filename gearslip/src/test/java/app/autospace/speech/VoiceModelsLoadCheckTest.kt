package app.autospace.speech

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the 2026-10-25 Kokoro load crash (Robert's
 * 09:37 crash report: "native load begin kokoro" with no end, on a
 * set that had PASSED the old size floors). The native load kills
 * the whole process on a bad set instead of throwing, so validation
 * must pin every file the load opens to its exact published size
 * (Hugging Face tree API, verified live 2026-10-25) and name the
 * offending file, instead of a blanket "files incomplete".
 *
 * Sizes are faked with sparse files (setLength reports the length
 * without writing the bytes), so the 325 MB model costs nothing.
 */
class VoiceModelsLoadCheckTest {

    private val roots = mutableListOf<File>()

    @After
    fun tearDown() {
        roots.forEach { it.deleteRecursively() }
    }

    private fun sized(root: File, rel: String, size: Long) {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        RandomAccessFile(f, "rw").use { it.setLength(size) }
    }

    /** A Kokoro dir with every load-critical file at its exact published size. */
    private fun exactKokoro(): Pair<File, File> {
        val root = Files.createTempDirectory("kokoro-exact").toFile().also { roots += it }
        sized(root, "model.onnx", 325_560_556L)
        sized(root, "voices.bin", 28_200_960L)
        sized(root, "tokens.txt", 687L)
        sized(root, "espeak-ng-data/phondata", 550_424L)
        sized(root, "espeak-ng-data/phonindex", 39_074L)
        sized(root, "espeak-ng-data/phontab", 55_796L)
        sized(root, "espeak-ng-data/intonations", 2_040L)
        return root to File(root, "espeak-ng-data")
    }

    @Test
    fun exactTablesMatchPublishedArtifact() {
        val k = VoiceModels.Engine.KOKORO
        assertEquals(325_560_556L, VoiceModels.exactFileBytes(k, "model.onnx"))
        assertEquals(28_200_960L, VoiceModels.exactFileBytes(k, "voices.bin"))
        assertEquals(687L, VoiceModels.exactFileBytes(k, "tokens.txt"))
        val p = VoiceModels.Engine.PIPER
        assertEquals(63_201_425L, VoiceModels.exactFileBytes(p, "en_US-amy-medium.onnx"))
        assertEquals(921L, VoiceModels.exactFileBytes(p, "tokens.txt"))
        assertEquals(550_424L, VoiceModels.exactEspeakBytes("phondata"))
        assertEquals(39_074L, VoiceModels.exactEspeakBytes("phonindex"))
        assertEquals(55_796L, VoiceModels.exactEspeakBytes("phontab"))
        assertEquals(2_040L, VoiceModels.exactEspeakBytes("intonations"))
    }

    @Test
    fun exactTablesReturnNullForUnknownFiles() {
        assertNull(VoiceModels.exactFileBytes(VoiceModels.Engine.KOKORO, "nope.bin"))
        assertNull(VoiceModels.exactEspeakBytes("en_dict"))
    }

    @Test
    fun completeExactKokoroSetValidates() {
        val (root, espeak) = exactKokoro()
        assertNull(VoiceModels.validationFailureForDir(root, espeak, VoiceModels.Engine.KOKORO))
    }

    @Test
    fun completeExactPiperSetValidates() {
        val root = Files.createTempDirectory("piper-exact").toFile().also { roots += it }
        sized(root, "en_US-amy-medium.onnx", 63_201_425L)
        sized(root, "tokens.txt", 921L)
        sized(root, "espeak-ng-data/phondata", 550_424L)
        sized(root, "espeak-ng-data/phonindex", 39_074L)
        sized(root, "espeak-ng-data/phontab", 55_796L)
        sized(root, "espeak-ng-data/intonations", 2_040L)
        assertNull(
            VoiceModels.validationFailureForDir(
                root, File(root, "espeak-ng-data"), VoiceModels.Engine.PIPER
            )
        )
    }

    @Test
    fun voicesBinOneByteShortNamesItself() {
        val (root, espeak) = exactKokoro()
        sized(root, "voices.bin", 28_200_959L)
        val failure = VoiceModels.validationFailureForDir(root, espeak, VoiceModels.Engine.KOKORO)
        assertTrue(failure?.contains("voices.bin") == true)
        assertTrue(failure?.contains("size mismatch") == true)
        assertTrue(failure?.contains("28200960") == true)
    }

    @Test
    fun missingPhonindexNamesItself() {
        val (root, espeak) = exactKokoro()
        File(espeak, "phonindex").delete()
        val failure = VoiceModels.validationFailureForDir(root, espeak, VoiceModels.Engine.KOKORO)
        assertTrue(failure?.contains("phonindex") == true)
        assertTrue(failure?.contains("missing") == true)
    }

    @Test
    fun truncatedPhondataNamesItself() {
        val (root, espeak) = exactKokoro()
        sized(root, "espeak-ng-data/phondata", 550_423L)
        val failure = VoiceModels.validationFailureForDir(root, espeak, VoiceModels.Engine.KOKORO)
        assertTrue(failure?.contains("phondata") == true)
        assertTrue(failure?.contains("size mismatch") == true)
    }

    @Test
    fun missingModelStillReportsGenericIncomplete() {
        val (root, espeak) = exactKokoro()
        File(root, "model.onnx").delete()
        val failure = VoiceModels.validationFailureForDir(root, espeak, VoiceModels.Engine.KOKORO)
        assertTrue(failure?.contains("files incomplete") == true)
    }

    @Test
    fun loadCheckReportsActualSizesAndEspeakCount() {
        val root = Files.createTempDirectory("kokoro-check").toFile().also { roots += it }
        sized(root, "model.onnx", 123L)
        sized(root, "voices.bin", 456L)
        sized(root, "tokens.txt", 7L)
        sized(root, "espeak-ng-data/phondata", 10L)
        sized(root, "espeak-ng-data/lang/gmw/en", 3L)
        val line = VoiceModels.loadCheckForDir(
            root, File(root, "espeak-ng-data"), VoiceModels.Engine.KOKORO
        )
        assertTrue(line.contains("kokoro model.onnx=123 voices.bin=456 tokens.txt=7"))
        assertTrue(line.contains("phondata=10 phonindex=-1 phontab=-1 intonations=-1"))
        assertTrue(line.contains("espeak_files=2"))
    }
}
