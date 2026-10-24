package app.autospace

import app.autospace.audio.CarAudio
import kotlin.math.abs

/**
 * Debug-only stand-in for the head unit's media audio sink.
 *
 * The car end of the audio path can only be exercised in a car, but everything before it - the
 * setting, the consent dialog, the capture service, and whether the media app allows itself to be
 * heard at all - is phone-side and can be tested at a desk. This sink takes the place of
 * [AudioLink] so that path runs to completion, and reports what arrived.
 *
 *   adb shell am start -n app.autospace/.CarPreviewActivity \
 *       --ei w 800 --ei h 480 --ez audio true --ei snaps 10
 *
 * A healthy run logs a rising byte count and a peak well above zero. A byte count that rises with
 * a peak of zero means the app refused to be captured.
 */
class BenchAudioSink(override val sampleRate: Int = 48_000, override val channels: Int = 2) : CarAudio.Link {

    private var chunks = 0
    private var bytes = 0L
    private var peak = 0
    private var reportedAt = 0L

    override fun start() {
        chunks = 0
        bytes = 0
        peak = 0
        reportedAt = System.currentTimeMillis()
        GearslipLog.i("bench sink: started - pretending to be a ${sampleRate}Hz x$channels car sink")
    }

    override fun stop() {
        GearslipLog.i("bench sink: stopped after $chunks chunks / $bytes bytes")
    }

    override fun write(pcm: ByteArray, size: Int) {
        chunks++
        bytes += size
        for (i in 0 until size - 1 step 2) {
            val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            peak = maxOf(peak, abs(sample.toShort().toInt()))
        }
        val now = System.currentTimeMillis()
        if (now - reportedAt < REPORT_MS) return
        reportedAt = now
        val seconds = bytes / (sampleRate.toDouble() * channels * 2)
        GearslipLog.i(
            "bench sink: %d chunks / %d bytes (%.1fs of audio), peak %.3f%s".format(
                chunks, bytes, seconds, peak / 32768f,
                if (peak == 0) " - SILENT, the app is not letting itself be captured" else "",
            ),
        )
        peak = 0
    }

    companion object {
        private const val REPORT_MS = 2_000L
    }
}
