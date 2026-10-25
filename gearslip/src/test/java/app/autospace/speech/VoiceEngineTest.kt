package app.autospace.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure, native-free checks on the voice engine: sid map, language map, audio maths. */
class VoiceEngineTest {

    @Test
    fun kokoroVoiceTableHas53VoicesWithUniqueSids() {
        val voices = VoiceModels.KOKORO_VOICES
        assertEquals(53, voices.size)
        assertEquals(53, voices.map { it.sid }.toSet().size)
        assertEquals((0..52).toList(), voices.map { it.sid }.sorted())
    }

    @Test
    fun amAdamIsDefaultAtSid11() {
        assertEquals("am_adam", VoiceModels.DEFAULT_VOICE.id)
        assertEquals(11, VoiceModels.DEFAULT_VOICE.sid)
        assertEquals("American English", VoiceModels.DEFAULT_VOICE.language)
    }

    @Test
    fun featuredPlusMoreCoversAll53VoicesWithNothingRemoved() {
        assertEquals(
            listOf("am_michael", "bm_george", "af_heart", "bf_emma"),
            VoiceModels.FEATURED_VOICES.map { it.id },
        )
        assertEquals(49, VoiceModels.MORE_VOICES.size)
        assertTrue(VoiceModels.FEATURED_VOICES.intersect(VoiceModels.MORE_VOICES.toSet()).isEmpty())
        assertEquals(
            VoiceModels.KOKORO_VOICES.map { it.id }.toSet(),
            (VoiceModels.FEATURED_VOICES + VoiceModels.MORE_VOICES).map { it.id }.toSet(),
        )
        // The default stays am_adam, and it lives on the More voices page.
        assertTrue(VoiceModels.DEFAULT_VOICE in VoiceModels.MORE_VOICES)
        assertEquals("In 500 feet, turn left.", VoiceModels.PREVIEW_PHRASE)
    }

    @Test
    fun espeakLangFollowsVoicePrefix() {
        val byId = VoiceModels.KOKORO_VOICES.associateBy { it.id }
        assertEquals("en", VoiceModels.espeakLang(byId.getValue("am_adam")))
        assertEquals("en", VoiceModels.espeakLang(byId.getValue("bf_emma")))
        assertEquals("es", VoiceModels.espeakLang(byId.getValue("ef_dora")))
        assertEquals("fr", VoiceModels.espeakLang(byId.getValue("ff_siwis")))
        assertEquals("hi", VoiceModels.espeakLang(byId.getValue("hf_alpha")))
        assertEquals("it", VoiceModels.espeakLang(byId.getValue("if_sara")))
        assertEquals("ja", VoiceModels.espeakLang(byId.getValue("jf_alpha")))
        assertEquals("pt", VoiceModels.espeakLang(byId.getValue("pf_dora")))
        assertEquals("cmn", VoiceModels.espeakLang(byId.getValue("zf_xiaobei")))
    }

    @Test
    fun floatsToShortsClampsInsteadOfWrapping() {
        val shorts = VoiceEngine.floatsToShorts(floatArrayOf(-2f, -1f, 0f, 0.5f, 1f, 2f))
        assertEquals((-32768).toShort(), shorts[0])
        assertEquals((-32767).toShort(), shorts[1])
        assertEquals(0.toShort(), shorts[2])
        assertEquals(16383.toShort(), shorts[3])
        assertEquals(32767.toShort(), shorts[4])
        assertEquals(32767.toShort(), shorts[5])
    }

    @Test
    fun resampleDoublesFramesAndCopiesChannels() {
        val pcm = VoiceEngine.resample(floatArrayOf(0f, 1f, -1f, 0.5f), 24000, 48000, 2)
        // 4 frames at 24 kHz -> 8 frames at 48 kHz, 2 channels, 2 bytes each.
        assertEquals(8 * 2 * 2, pcm.size)
        // First frame is silence on both channels; second frame's channels agree.
        assertTrue(pcm[0] == 0.toByte() && pcm[1] == 0.toByte())
        assertTrue(pcm[4] == pcm[6] && pcm[5] == pcm[7])
    }
}
