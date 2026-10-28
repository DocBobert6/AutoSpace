package app.autospace.speech

import app.autospace.CrashReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTraceTest {

    @Test
    fun traceFileNameIsTimestampedTxtInDownloadsShape() {
        assertEquals(
            "autospace-launch-trace-20261025-161400.txt",
            VoiceTrace.traceFileName("20261025-161400"),
        )
    }

    @Test
    fun traceWindowIsExactly45Seconds() {
        assertEquals(45_000L, VoiceTrace.WINDOW_MS)
    }

    @Test
    fun traceLongWindowIsExactly90Seconds() {
        // Couch-testing option (2026-10-25): the longer window the
        // phone and car trace pickers offer alongside the default.
        assertEquals(90_000L, VoiceTrace.WINDOW_MS_LONG)
    }

    @Test
    fun buildTagIsPinnedAndNonBlank() {
        // Round 7 tripwire: the tag must change on every push (see
        // VoiceTrace.BUILD_TAG). A push that forgets to bump it fails
        // here instead of shipping traces no one can attribute.
        assertEquals("r133", VoiceTrace.BUILD_TAG)
    }

    @Test
    fun traceFilterDropsContentLinesAndKeepsStageLines() {
        val kept = CrashReporter.filterContentLines(
            listOf(
                "10:15:33.614 I APP   voice: load check piper en_US-amy-medium.onnx=63201425 tokens.txt=921",
                "10:15:33.616 I APP   voice: native load begin piper",
                "10:15:33.620 W APP   voice: piper failed to load: IllegalArgumentException: Invalid OfflineTtsConfig",
                "10:15:33.621 I APP   voice: preview tap piper ready=true",
                "09:37:25.821 I APP   voice: downloaded Piper — Amy",
                "12:00:00.000 I APP   test message: hello world",
                "12:00:01.000 I APP   media status: -> track Some Song",
                "12:00:02.000 I APP   starting a session over Wi-Fi to 192.168.1.2",
            ),
        )
        assertEquals(5, kept.size)
        assertTrue(kept.all { "test message:" !in it && "media status: -> track" !in it })
        assertTrue(kept.any { "preview tap piper" in it })
        assertTrue(kept.any { "failed to load: IllegalArgumentException" in it })
    }
}

