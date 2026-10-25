// OfflineTts bindings for the sherpa-onnx native library already bundled with
// AutoSpace (libsherpa-onnx-jni.so, Apache-2.0, https://github.com/k2-fsa/sherpa-onnx).
// The config field names mirror sherpa-onnx's kotlin-api, because the JNI layer
// reads them by name; the sub-configs it never uses (matcha, kitten, zipvoice,
// pocket, supertonic) are present so a newer native library that looks one of them
// up still finds a field, and are left empty. Only the config and generate paths
// AutoSpace actually calls are used: newFromFile + generateImpl + delete.
// 2026-10-25, LOAD-BEARING NAME (Robert's 10:15 Piper crash report: death
// inside native OfflineTts load with verified-good files): the vendored
// .so's JNI looks the ZipVoice sub-config up by the EXACT class name
// com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig (capital V, proven
// from the .so's own field-type descriptor string). The binding previously
// spelled it OfflineTtsZipvoiceModelConfig (lowercase v), so the JNI's
// GetFieldID for ModelConfig.zipvoice found no field with that signature
// and the process died inside newFromFile — for BOTH engines, since both
// go through the same whole-ModelConfig conversion, while the recognizers
// (whose binding class names all match) kept working. Do NOT rename this
// class back to the lowercase spelling: the capital V is what the .so
// looks up. The encoder/decoder/scale fields below complete the class
// for the same reason.
// CORRECTION, 2026-10-25 round 4 (Robert's launch trace: every preview
// dying on "Failed to get field ID for lmFlow"): the claim above that
// "a missing leaf field is tolerated" is WRONG for this .so's
// GetOfflineTtsConfig. Disassembly of libsherpa-onnx-jni.so proves
// every field lookup in that conversion follows load -> GetFieldID ->
// null field ID -> log "Failed to get field ID for '%s'" -> throw
// RuntimeException (lmFlow site: 0x5991f4 GetFieldID, 0x59920c null
// check, 0x599260 log, then ThrowNew). A missing field is FATAL, not
// tolerated, which is why one gap killed both engines and why the fix
// had to audit the WHOLE conversion, not one field. That audit
// (field-name + signature pairs in conversion order, 0x593e30
// onwards) found exactly three gaps, all fixed here: Pocket's eight
// real fields, Supertonic's seven real fields, and ruleFars on
// OfflineTtsConfig. Every other field the .so reads (Vits: model,
// lexicon, tokens, dataDir, noiseScale, noiseScaleW, lengthScale;
// Matcha: acousticModel, vocoder, lexicon, tokens, dataDir,
// noiseScale, lengthScale; Kokoro: model, voices, tokens, lexicon,
// lang, dataDir, lengthScale; ZipVoice: tokens, encoder, decoder,
// vocoder, dataDir, lexicon, featScale, tShift, targetRms,
// guidanceScale; Kitten: model, voices, tokens, dataDir, lengthScale;
// ModelConfig: all seven sub-configs + numThreads + debug +
// provider; OfflineTtsConfig: model + ruleFsts + ruleFars +
// maxNumSentences; GenerationConfig: sid + speed + silenceScale) is
// present in this binding; the generic extra fields on the older
// sub-configs are inert (JNI reads by name) and are kept.
// GeneratedAudio's constructor shape (FloatArray, Int) is what the JNI layer
// builds, so keep it exactly.
package com.k2fsa.sherpa.onnx

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class OfflineTtsVitsModelConfig(
    var model: String = "",
    var lexicon: String = "",
    var tokens: String = "",
    var dataDir: String = "",
    var dictDir: String = "",
    var lengthScale: Float = 1.0f,
    var noiseScale: Float = 0.667f,
    var noiseScaleW: Float = 0.8f,
    var voices: String = "",
    var lang: String = "",
    var acousticModel: String = "",
    var vocoder: String = "",
)

data class OfflineTtsMatchaModelConfig(
    var acousticModel: String = "",
    var vocoder: String = "",
    var lexicon: String = "",
    var tokens: String = "",
    var dataDir: String = "",
    var dictDir: String = "",
    var lengthScale: Float = 1.0f,
    var model: String = "",
    var voices: String = "",
    var lang: String = "",
    var noiseScale: Float = 0.667f,
    var noiseScaleW: Float = 0.8f,
)

data class OfflineTtsKokoroModelConfig(
    var model: String = "",
    var voices: String = "",
    var tokens: String = "",
    var dataDir: String = "",
    var dictDir: String = "",
    var lexicon: String = "",
    var lang: String = "",
    var lengthScale: Float = 1.0f,
    var noiseScale: Float = 0.667f,
    var noiseScaleW: Float = 0.8f,
    var acousticModel: String = "",
    var vocoder: String = "",
)

data class OfflineTtsKittenModelConfig(
    var model: String = "",
    var voices: String = "",
    var tokens: String = "",
    var dataDir: String = "",
    var dictDir: String = "",
    var lexicon: String = "",
    var lang: String = "",
    var lengthScale: Float = 1.0f,
    var noiseScale: Float = 0.667f,
    var noiseScaleW: Float = 0.8f,
    var acousticModel: String = "",
    var vocoder: String = "",
)

data class OfflineTtsZipVoiceModelConfig(
    var model: String = "",
    var voices: String = "",
    var tokens: String = "",
    var dataDir: String = "",
    var dictDir: String = "",
    var lexicon: String = "",
    var lang: String = "",
    var lengthScale: Float = 1.0f,
    var noiseScale: Float = 0.667f,
    var noiseScaleW: Float = 0.8f,
    var acousticModel: String = "",
    var vocoder: String = "",
    // ZipVoice's own fields (unused by AutoSpace — the class is never
    // populated — but present so the JNI reader finds them by name):
    var encoder: String = "",
    var decoder: String = "",
    var featScale: Float = 1.0f,
    var guidanceScale: Float = 1.0f,
    var tShift: Float = 0.0f,
    var targetRms: Float = 1.0f,
)

data class OfflineTtsPocketModelConfig(
    // Pocket's REAL fields (2026-10-25 round 4: Robert's launch trace
    // showed every preview dying on "Failed to get field ID for
    // lmFlow"). The vendored .so's GetOfflineTtsConfig reads these
    // eight fields off the Pocket sub-config — proven by disassembling
    // libsherpa-onnx-jni.so itself (Pocket descriptor load at
    // 0x5991a4, then GetFieldID for lmFlow with signature
    // Ljava/lang/String; at 0x5991f4, null field ID -> log ->
    // RuntimeException). The previous generic fields (model, voices,
    // tokens, ...) were NEVER read by the .so for Pocket, so the class
    // existed but every load died on the first real field. The same
    // disassembly audited the whole ModelConfig conversion (see the
    // header note): Pocket and Supertonic were the only sub-configs
    // with missing fields, plus ruleFars on OfflineTtsConfig.
    var lmFlow: String = "",
    var lmMain: String = "",
    var encoder: String = "",
    var decoder: String = "",
    var textConditioner: String = "",
    var vocabJson: String = "",
    var tokenScoresJson: String = "",
    var voiceEmbeddingCacheCapacity: Int = 50,
)

data class OfflineTtsSupertonicModelConfig(
    // Supertonic's REAL fields (same 2026-10-25 round-4 audit of the
    // .so's GetOfflineTtsConfig: Supertonic descriptor load at
    // 0x59a484, then GetFieldID for each of these seven String fields
    // in order). The previous generic fields were never read by the
    // .so for Supertonic — left behind Pocket in conversion order, so
    // its gaps would have surfaced as the NEXT fatal field lookup once
    // Pocket was fixed. Fixed in the same change, not left for a
    // third trace.
    var durationPredictor: String = "",
    var textEncoder: String = "",
    var vectorEstimator: String = "",
    var vocoder: String = "",
    var ttsJson: String = "",
    var unicodeIndexer: String = "",
    var voiceStyle: String = "",
)

data class OfflineTtsModelConfig(
    var vits: OfflineTtsVitsModelConfig = OfflineTtsVitsModelConfig(),
    var matcha: OfflineTtsMatchaModelConfig = OfflineTtsMatchaModelConfig(),
    var kokoro: OfflineTtsKokoroModelConfig = OfflineTtsKokoroModelConfig(),
    var kitten: OfflineTtsKittenModelConfig = OfflineTtsKittenModelConfig(),
    var zipvoice: OfflineTtsZipVoiceModelConfig = OfflineTtsZipVoiceModelConfig(),
    var pocket: OfflineTtsPocketModelConfig = OfflineTtsPocketModelConfig(),
    var supertonic: OfflineTtsSupertonicModelConfig = OfflineTtsSupertonicModelConfig(),
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
)

data class OfflineTtsConfig(
    var model: OfflineTtsModelConfig = OfflineTtsModelConfig(),
    var maxNumSentences: Int = 1,
    var ruleFsts: String = "",
    // ruleFars (round-4 audit: the .so's GetOfflineTtsConfig reads
    // ruleFsts, then ruleFars, then maxNumSentences — the binding
    // lacked ruleFars, a third fatal gap behind Pocket/Supertonic).
    var ruleFars: String = "",
)

data class GenerationConfig(
    var sid: Int = 0,
    var speed: Float = 1.0f,
    var silenceScale: Float = 0.2f,
)

data class GeneratedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
) {
    /** 16-bit mono WAV, the shape DirectionsVoice used to read back from system TTS. */
    fun save(filename: String): Boolean = runCatching {
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in samples) pcm.putShort((v * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        val bytes = pcm.array()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + bytes.size)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(bytes.size)
        FileOutputStream(File(filename)).use { out ->
            out.write(header.array())
            out.write(bytes)
        }
        true
    }.getOrDefault(false)
}

class OfflineTts(config: OfflineTtsConfig) {
    private var ptr: Long

    init {
        ptr = newFromFile(config)
        require(ptr != 0L) {
            "Invalid OfflineTtsConfig: failed to create native OfflineTts"
        }
    }

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    fun generate(text: String, sid: Int = 0, speed: Float = 1.0f): GeneratedAudio =
        generateImpl(ptr, text, sid, speed)

    fun generateWithCallback(
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f,
        callback: (FloatArray) -> Int,
    ): GeneratedAudio = generateWithCallbackImpl(ptr, text, sid, speed, callback)

    fun getSampleRate(): Int = getSampleRate(ptr)

    fun getNumSpeakers(): Int = getNumSpeakers(ptr)

    private external fun newFromFile(config: OfflineTtsConfig): Long

    private external fun delete(ptr: Long)

    private external fun generateImpl(ptr: Long, text: String, sid: Int, speed: Float): GeneratedAudio

    private external fun generateWithCallbackImpl(
        ptr: Long,
        text: String,
        sid: Int,
        speed: Float,
        callback: (FloatArray) -> Int,
    ): GeneratedAudio

    private external fun getSampleRate(ptr: Long): Int

    private external fun getNumSpeakers(ptr: Long): Int

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}
