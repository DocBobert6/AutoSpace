package app.autospace.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the 2026-10-25 spoken-voice download failure:
 * both engines failed every download with "files incomplete" because
 * tokens.txt files are tiny by nature (live Hugging Face sizes
 * verified 2026-10-25: Kokoro tokens.txt 687 bytes, Piper
 * tokens.txt 921 bytes) while the old small-file floor was 1,000
 * bytes. The tokens floor must stay at or below both real sizes
 * and above an empty stub, and the model floors must not move.
 */
class VoiceModelsValidationTest {

    @Test
    fun tokensFloorPassesBothRealTokensFiles() {
        assertTrue(VoiceModels.minFileBytes(VoiceModels.Engine.KOKORO, "tokens.txt") <= 687)
        assertTrue(VoiceModels.minFileBytes(VoiceModels.Engine.PIPER, "tokens.txt") <= 921)
    }

    @Test
    fun tokensFloorStillCatchesAnEmptyStub() {
        assertTrue(VoiceModels.minFileBytes(VoiceModels.Engine.KOKORO, "tokens.txt") > 0)
        assertTrue(VoiceModels.minFileBytes(VoiceModels.Engine.PIPER, "tokens.txt") > 0)
    }

    @Test
    fun downloadPercentMovesAndCapsAt99() {
        assertEquals(0, VoiceModels.downloadPercent(0, 100))
        assertEquals(50, VoiceModels.downloadPercent(50, 100))
        // Over-estimate totals used to freeze the bar: a real total
        // past the estimate must still cap at 99, never 100, until Ready.
        assertEquals(99, VoiceModels.downloadPercent(200, 100))
        assertEquals(0, VoiceModels.downloadPercent(50, 0))
    }

    @Test
    fun downloadingStateCarriesItsDetail() {
        val s = VoiceModels.State.Downloading(96, "espeak-ng-data: 201/355 files")
        assertEquals(96, s.percent)
        assertEquals("espeak-ng-data: 201/355 files", s.detail)
        assertEquals("", VoiceModels.State.Downloading(0).detail)
    }

    @Test
    fun modelAndVoicesFloorsUnchanged() {
        assertEquals(10_000_000L, VoiceModels.minFileBytes(VoiceModels.Engine.KOKORO, "model.onnx"))
        assertEquals(1_000L, VoiceModels.minFileBytes(VoiceModels.Engine.KOKORO, "voices.bin"))
        assertEquals(1_000_000L, VoiceModels.minFileBytes(VoiceModels.Engine.PIPER, "en_US-amy-medium.onnx"))
    }
}
