package app.autospace.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r28 hotword consistency: the microphone-choice logic, without a
 * microphone. The actual mic objects (AudioRecord, the car's channel)
 * cannot run on the plain JVM, so these pin the pure decision layer
 * ([MicSelection], [SilenceTracker], [HotwordMicOverride]) that the
 * wiring in [SpeechEngine] and [HotwordListener] consults.
 */
class MicSelectionTest {

    // --- MicSelection.selectSource: the order is the contract ----------------

    @Test
    fun phoneMicSettingWinsOverEverything() {
        assertEquals(
            SpeechEngine.MicSource.PHONE,
            MicSelection.selectSource(phoneMicSetting = true, carMicDead = true, inheritedPhone = true, carAvailable = true),
        )
        assertEquals(
            SpeechEngine.MicSource.PHONE,
            MicSelection.selectSource(phoneMicSetting = true, carMicDead = false, inheritedPhone = false, carAvailable = true),
        )
    }

    @Test
    fun provenDeadCarMicWinsOverAnAvailableCarMic() {
        assertEquals(
            SpeechEngine.MicSource.PHONE,
            MicSelection.selectSource(phoneMicSetting = false, carMicDead = true, inheritedPhone = false, carAvailable = true),
        )
    }

    @Test
    fun inheritedPhoneMicWinsOverAnAvailableCarMic() {
        // The r28 handoff defect: a hotword heard on the phone's mic
        // must not be followed by a listen on the dead car mic.
        assertEquals(
            SpeechEngine.MicSource.PHONE,
            MicSelection.selectSource(phoneMicSetting = false, carMicDead = false, inheritedPhone = true, carAvailable = true),
        )
    }

    @Test
    fun carMicUsedWhenNothingOverridesAndOneIsSending() {
        assertEquals(
            SpeechEngine.MicSource.CAR,
            MicSelection.selectSource(phoneMicSetting = false, carMicDead = false, inheritedPhone = false, carAvailable = true),
        )
    }

    @Test
    fun phoneMicIsTheFallbackWhenNoCarMicIsSending() {
        assertEquals(
            SpeechEngine.MicSource.PHONE,
            MicSelection.selectSource(phoneMicSetting = false, carMicDead = false, inheritedPhone = false, carAvailable = false),
        )
    }

    // --- SilenceTracker: one definition of "silent" --------------------------

    @Test
    fun allZeroChunkIsSilent() {
        val tracker = SilenceTracker()
        assertTrue(tracker.add(ShortArray(1600), 1600))
        assertTrue(tracker.allSilent)
        assertEquals(1, tracker.chunksSeen)
    }

    @Test
    fun boundarySamplesAreSilent() {
        // Exactly ±32 is within the amplitude: the hotword's shipped
        // boundary, pinned here so the two can never drift apart.
        val tracker = SilenceTracker()
        val frame = ShortArray(1600) { if (it % 2 == 0) 32 else -32 }
        assertTrue(tracker.add(frame, 1600))
        assertTrue(tracker.allSilent)
    }

    @Test
    fun oneLoudSampleBreaksSilencePermanently() {
        val tracker = SilenceTracker()
        assertTrue(tracker.add(ShortArray(1600), 1600))
        val loud = ShortArray(1600).also { it[1599] = 33 }
        assertFalse(tracker.add(loud, 1600))
        assertFalse(tracker.allSilent)
        // A later silent chunk does not repair it: allSilent is cumulative.
        assertTrue(tracker.add(ShortArray(1600), 1600))
        assertFalse(tracker.allSilent)
        assertEquals(3, tracker.chunksSeen)
    }

    @Test
    fun negativeLoudSampleBreaksSilence() {
        val tracker = SilenceTracker()
        val frame = ShortArray(1600).also { it[0] = -33 }
        assertFalse(tracker.add(frame, 1600))
        assertFalse(tracker.allSilent)
    }

    @Test
    fun onlyTheFirstNSamplesCount() {
        // A loud sample past n must not count: frames are cut to CHUNKs,
        // and a short final chunk must not read stale tail samples.
        val tracker = SilenceTracker()
        val frame = ShortArray(1600).also { it[100] = 5000 }
        assertTrue(tracker.add(frame, 100))
        assertTrue(tracker.allSilent)
    }

    @Test
    fun hotwordSilenceConstantIsSharedNotCopied() {
        assertEquals(32, HotwordListener.SILENCE_AMPLITUDE)
    }

    // --- HotwordMicOverride: one-shot, expiring ------------------------------

    @Test
    fun inheritanceIsOneShot() {
        val override = HotwordMicOverride()
        override.noteHeardOnPhone(nowMs = 1000L)
        assertTrue(override.takePhone(nowMs = 2000L))
        assertFalse(override.takePhone(nowMs = 3000L))
    }

    @Test
    fun inheritanceExpires() {
        val override = HotwordMicOverride()
        override.noteHeardOnPhone(nowMs = 1000L)
        // Past the 60 s validity: a stale inheritance must never leak
        // into an unrelated later press.
        assertFalse(override.takePhone(nowMs = 1000L + MicSelection.INHERIT_VALID_MS + 1L))
        assertFalse(override.takePhone(nowMs = 1000L + MicSelection.INHERIT_VALID_MS + 2L))
    }

    @Test
    fun inheritanceAtTheBoundaryIsStillValid() {
        val override = HotwordMicOverride()
        override.noteHeardOnPhone(nowMs = 1000L)
        assertTrue(override.takePhone(nowMs = 1000L + MicSelection.INHERIT_VALID_MS))
    }

    @Test
    fun noNoteMeansNoInheritance() {
        assertFalse(HotwordMicOverride().takePhone(nowMs = 5000L))
    }

    @Test
    fun reNotingRefreshesTheWindow() {
        val override = HotwordMicOverride()
        override.noteHeardOnPhone(nowMs = 1000L)
        override.noteHeardOnPhone(nowMs = 50000L)
        assertTrue(override.takePhone(nowMs = 50000L + MicSelection.INHERIT_VALID_MS))
    }
}
