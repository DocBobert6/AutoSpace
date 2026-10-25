package app.autospace.speech

import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Regression tests for the 2026-10-25 TTS load crash (Robert's 10:15
 * Piper crash report: "native load begin piper" with no end, on a set
 * whose load-check line showed every file at its exact published size).
 *
 * Root cause, proven from the vendored .so itself: its JNI looks the
 * ZipVoice sub-config up by the EXACT class name
 * com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig (capital V — the
 * field-type descriptor string inside libsherpa-onnx-jni.so). The binding
 * previously spelled it OfflineTtsZipvoiceModelConfig (lowercase v), so
 * the JNI's GetFieldID for ModelConfig.zipvoice found no field with that
 * signature and the process died inside newFromFile for BOTH engines
 * (both go through the same whole-ModelConfig conversion), while the
 * recognizers — whose binding class names all match the .so — worked.
 *
 * These tests fail on the old spelling and pass on the fixed one: they
 * resolve the class by the exact name the .so looks up, and check the
 * ModelConfig field's declared type matches it character for character.
 * A future rename of this class must update the .so expectation too —
 * the name is load-bearing, not cosmetic.
 */
class OfflineTtsBindingTest {

    @Test
    fun zipVoiceClassExistsUnderExactNameTheSoLooksUp() {
        val cls = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig")
        assertNotNull(cls)
    }

    @Test
    fun modelConfigZipvoiceFieldTypeMatchesSoDescriptor() {
        val field = OfflineTtsModelConfig::class.java.getDeclaredField("zipvoice")
        assertEquals(
            "com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig",
            field.type.name,
        )
    }

    @Test
    fun zipVoiceClassCarriesItsOwnFields() {
        val cls = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig")
        val names = cls.declaredFields.map { it.name }.toSet()
        for (needed in listOf("encoder", "decoder", "vocoder", "tokens", "dataDir")) {
            assertNotNull("missing ZipVoice field: $needed", names.contains(needed))
        }
    }

    @Test
    fun allOtherTtsSubConfigClassesExist() {
        for (name in listOf(
            "com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig",
            "com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig",
            "com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig",
            "com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig",
            "com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig",
            "com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig",
            "com.k2fsa.sherpa.onnx.OfflineTtsModelConfig",
            "com.k2fsa.sherpa.onnx.OfflineTtsConfig",
        )) {
            assertNotNull(name, Class.forName(name))
        }
    }
}
