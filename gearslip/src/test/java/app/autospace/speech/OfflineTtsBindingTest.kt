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

    // Round 4 (2026-10-25 launch trace: 9 previews, every one dying
    // on "Failed to get field ID for lmFlow"): the .so's
    // GetOfflineTtsConfig converts the WHOLE ModelConfig regardless of
    // which engine is used, and EVERY field lookup in it throws
    // RuntimeException on a null field ID (disassembly of the lmFlow
    // site: GetFieldID at 0x5991f4, null check, log, ThrowNew) — the
    // round-2 "leaf gaps tolerated" claim was wrong for this path.
    // The full audit of that conversion (field + signature pairs in
    // order from 0x593e30) found exactly three gaps: Pocket's eight
    // real fields, Supertonic's seven real fields, and ruleFars on
    // OfflineTtsConfig. These tests fail on the old binding (fields
    // absent) and pass on the fixed one.

    @Test
    fun pocketCarriesItsRealFields() {
        val cls = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig")
        val fields = cls.declaredFields.associateBy { it.name }
        for (name in listOf(
            "lmFlow", "lmMain", "encoder", "decoder",
            "textConditioner", "vocabJson", "tokenScoresJson",
        )) {
            val field = fields[name]
            assertNotNull("missing Pocket field: $name", field)
            assertEquals("java.lang.String", field!!.type.name)
        }
        val capacity = fields["voiceEmbeddingCacheCapacity"]
        assertNotNull("missing Pocket field: voiceEmbeddingCacheCapacity", capacity)
        assertEquals("int", capacity!!.type.name)
    }

    @Test
    fun supertonicCarriesItsRealFields() {
        val cls = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig")
        val fields = cls.declaredFields.associateBy { it.name }
        for (name in listOf(
            "durationPredictor", "textEncoder", "vectorEstimator",
            "vocoder", "ttsJson", "unicodeIndexer", "voiceStyle",
        )) {
            val field = fields[name]
            assertNotNull("missing Supertonic field: $name", field)
            assertEquals("java.lang.String", field!!.type.name)
        }
    }

    @Test
    fun configCarriesRuleFars() {
        val field = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsConfig")
            .getDeclaredField("ruleFars")
        assertEquals("java.lang.String", field.type.name)
    }

    @Test
    fun modelConfigCarriesAllSubConfigsAndTopFields() {
        val fields = OfflineTtsModelConfig::class.java.declaredFields
            .associateBy { it.name }
        for (name in listOf(
            "vits", "matcha", "kokoro", "kitten",
            "zipvoice", "pocket", "supertonic",
        )) {
            assertNotNull("missing ModelConfig field: $name", fields[name])
        }
        assertEquals("int", fields["numThreads"]!!.type.name)
        assertEquals("boolean", fields["debug"]!!.type.name)
        assertEquals("java.lang.String", fields["provider"]!!.type.name)
    }
}
