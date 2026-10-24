package app.autospace

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Proves the H.264 encoder actually produces a bitstream on this device, before a drive.
 *
 * Without this, an unsupported colour format, a rejected Baseline profile, or a null
 * getInputImage() would all present in the car as "the head unit shows nothing" - the same
 * symptom as a protocol fault, and impossible to tell apart from the log. Same reasoning as
 * the TLS self-test: make our own failures loud on the bench.
 */
object EncoderSelfTest {
    private val log = GearslipLog.tagged("VIDEO")

    fun run(width: Int = 800, height: Int = 480, frameRate: Int = 30): Boolean {
        log.i("=== encoder self-test starting (${width}x$height @ ${frameRate}fps) ===")

        var codecConfig: ByteArray? = null
        var frames = 0
        var sawKeyFrame = false
        var sawResyncKeyFrame = false
        var resyncRequestedAtFrame = -1
        val done = CountDownLatch(1)

        val source = VideoSource(
            width = width,
            height = height,
            frameRate = frameRate,
            onCodecConfig = { codecConfig = it },
            onFrame = { _, _, keyFrame ->
                frames++
                if (keyFrame) sawKeyFrame = true
                // At frame 5, ask for a resync keyframe - the same call sendVideoFrame makes
                // after a drop - and confirm THIS encoder actually honours it. Trusting an
                // API we have never exercised on this device is exactly the mistake that
                // cost two drives already (the CONTROL message-type bit, the touch scaling).
                if (frames == 5) {
                    resyncRequestedAtFrame = frames
                }
                if (resyncRequestedAtFrame in 1 until frames && keyFrame) {
                    sawResyncKeyFrame = true
                }
                if (frames >= 12) done.countDown()
            },
        )

        return try {
            source.start()
            // Give it a couple of frames before requesting resync, matching how it is used
            // for real: the request always follows at least one already-encoded frame.
            Thread {
                while (resyncRequestedAtFrame == -1) Thread.sleep(20)
                source.requestSyncFrame()
            }.apply { isDaemon = true; start() }
            done.await(8, TimeUnit.SECONDS)
            source.stop()

            val csd = codecConfig
            when {
                csd == null -> {
                    log.e("encoder self-test FAILED: no CODEC_CONFIG (SPS/PPS) produced")
                    false
                }
                frames == 0 -> {
                    log.e("encoder self-test FAILED: no frames produced")
                    false
                }
                !sawKeyFrame -> {
                    log.e("encoder self-test FAILED: no keyframe - the car needs one first")
                    false
                }
                !sawResyncKeyFrame -> {
                    log.e(
                        "encoder self-test FAILED: requestSyncFrame() produced no keyframe - " +
                            "the resync-after-drop fix would silently do nothing on this device",
                    )
                    false
                }
                else -> {
                    log.i(
                        "SPS/PPS = ${csd.size} bytes, $frames frames, keyframe present, " +
                            "requestSyncFrame() confirmed working",
                    )
                    log.hex("   csd", csd, limit = 48)
                    log.i("=== encoder self-test PASSED ===")
                    true
                }
            }
        } catch (t: Throwable) {
            runCatching { source.stop() }
            log.e("encoder self-test FAILED with an exception", t)
            false
        }
    }
}
