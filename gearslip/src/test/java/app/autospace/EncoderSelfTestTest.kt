package app.autospace

import android.media.MediaCodec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assume
import org.junit.Test

/**
 * The migrated EncoderSelfTest probe (audit §1 F13, r166): the production
 * object is gone, and its body lives here as a test instead of being
 * deleted outright.
 *
 * It CANNOT run on the JVM: the unit-test android.jar is stubs, so
 * MediaCodec creation throws there. The test therefore probes for a
 * real MediaCodec first and assumes away (skips, does not fail) on
 * JVM stubs. On a bench run with a real codec it exercises the full
 * probe: bitstream produced, keyframe present, and requestSyncFrame()
 * honoured after a drop — the exact failure that used to present in
 * the car as "the head unit shows nothing".
 */
class EncoderSelfTestTest {

    @Test
    fun `encoder produces a bitstream with a resync keyframe`() {
        try {
            MediaCodec.createEncoderByType("video/avc")?.release()
        } catch (t: Exception) {
            Assume.assumeTrue("MediaCodec unavailable on JVM stubs", false)
        }

        var codecConfig: ByteArray? = null
        var frames = 0
        var sawKeyFrame = false
        var sawResyncKeyFrame = false
        var resyncRequestedAtFrame = -1
        val done = CountDownLatch(1)

        val source = VideoSource(
            width = 800,
            height = 480,
            frameRate = 30,
            onCodecConfig = { codecConfig = it },
            onFrame = { _, _, keyFrame ->
                frames++
                if (keyFrame) sawKeyFrame = true
                if (frames == 5) {
                    resyncRequestedAtFrame = frames
                }
                if (resyncRequestedAtFrame in 1 until frames && keyFrame) {
                    sawResyncKeyFrame = true
                }
                if (frames >= 12) done.countDown()
            },
        )

        try {
            source.start()
            Thread {
                while (resyncRequestedAtFrame == -1) Thread.sleep(20)
                source.requestSyncFrame()
            }.apply { isDaemon = true; start() }
            done.await(8, TimeUnit.SECONDS)
            source.stop()
        } catch (t: Throwable) {
            runCatching { source.stop() }
            throw AssertionError("encoder self-test FAILED with an exception", t)
        }

        val csd = codecConfig
        checkNotNull(csd) { "encoder self-test FAILED: no CODEC_CONFIG (SPS/PPS) produced" }
        check(frames > 0) { "encoder self-test FAILED: no frames produced" }
        check(sawKeyFrame) { "encoder self-test FAILED: no keyframe - the car needs one first" }
        check(sawResyncKeyFrame) {
            "encoder self-test FAILED: requestSyncFrame() produced no keyframe - " +
                "the resync-after-drop fix would silently do nothing on this device"
        }
    }
}
