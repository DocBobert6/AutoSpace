package app.autospace

import java.io.ByteArrayOutputStream

/**
 * Android Auto frame layout, transcribed from aasdk.
 *
 * FrameHeader::getData (src/Messenger/FrameHeader.cpp:47-55),
 * FrameSize::getData (src/Messenger/FrameSize.cpp:49-62) and
 * MessageOutStream::compoundFrame (src/Messenger/MessageOutStream.cpp:100-120):
 *
 *   byte 0     channel id
 *   byte 1     frameType | encryption | messageType
 *   bytes 2-3  uint16 BE  payload length of THIS frame
 *   bytes 4-7  uint32 BE  total message length - present ONLY when frameType == FIRST
 *   payload    uint16 BE message id, then the body
 */
object Frames {

    const val CHANNEL_CONTROL = 0

    const val TYPE_MIDDLE = 0
    const val TYPE_FIRST = 1
    const val TYPE_LAST = 2
    const val TYPE_BULK = 3

    const val ENCRYPTED = 1 shl 3

    /**
     * Message type bit. Channel-open messages are CONTROL even when sent on a service
     * channel - aasdk sets MessageType::CONTROL only in sendChannelOpenResponse and
     * MessageType::SPECIFIC everywhere else (e.g.
     * src/Channel/MediaSink/Video/VideoMediaSinkService.cpp:52 vs :66). Getting this wrong
     * makes the head unit drop the USB link outright rather than answer.
     */
    const val MESSAGE_SPECIFIC = 0
    const val MESSAGE_CONTROL = 1 shl 2

    /** aasdk splits at this size (MessageOutStream.hpp:62). Nothing the spike sends is close. */
    const val MAX_FRAME_PAYLOAD = 0x4000

    class Frame(
        val channel: Int,
        val frameType: Int,
        val encrypted: Boolean,
        val messageType: Int,
        val payload: ByteArray,
    )

    /**
     * Builds one frame. [totalSize] is the full plaintext message length and is written only
     * for FIRST frames, matching aasdk's MessageOutStream::setFrameSize
     * (`FrameSize(payloadSize, totalSize)` for FIRST, `FrameSize(payloadSize)` otherwise).
     */
    fun build(
        channel: Int,
        encrypted: Boolean,
        payload: ByteArray,
        messageType: Int = MESSAGE_SPECIFIC,
        frameType: Int = TYPE_BULK,
        totalSize: Int? = null,
    ): ByteArray {
        val flags = frameType or messageType or (if (encrypted) ENCRYPTED else 0)
        val header = byteArrayOf(
            channel.toByte(),
            flags.toByte(),
            ((payload.size shr 8) and 0xFF).toByte(),
            (payload.size and 0xFF).toByte(),
        )
        if (frameType != TYPE_FIRST || totalSize == null) return header + payload
        val extended = byteArrayOf(
            ((totalSize shr 24) and 0xFF).toByte(),
            ((totalSize shr 16) and 0xFF).toByte(),
            ((totalSize shr 8) and 0xFF).toByte(),
            (totalSize and 0xFF).toByte(),
        )
        return header + extended + payload
    }

    /**
     * Pulls whole frames out of an accumulating buffer.
     *
     * A read() on the accessory descriptor returns one bulk transfer, which is usually one
     * frame - but nothing guarantees it, so this never assumes alignment.
     */
    class Parser {
        private var pending = ByteArray(0)

        fun append(data: ByteArray, length: Int) {
            val grown = ByteArray(pending.size + length)
            System.arraycopy(pending, 0, grown, 0, pending.size)
            System.arraycopy(data, 0, grown, pending.size, length)
            pending = grown
        }

        fun next(): Frame? {
            if (pending.size < 4) return null
            val channel = pending[0].toInt() and 0xFF
            val flags = pending[1].toInt() and 0xFF
            val frameType = flags and TYPE_BULK
            // Extended size is written only for FIRST - BULK (3) keeps the short header.
            val headerLength = if (frameType == TYPE_FIRST) 8 else 4
            if (pending.size < headerLength) return null

            val frameSize = ((pending[2].toInt() and 0xFF) shl 8) or (pending[3].toInt() and 0xFF)
            val total = headerLength + frameSize
            if (pending.size < total) return null

            val payload = pending.copyOfRange(headerLength, total)
            pending = pending.copyOfRange(total, pending.size)
            return Frame(
                channel, frameType, (flags and ENCRYPTED) != 0, flags and MESSAGE_CONTROL, payload,
            )
        }
    }

    /** Rejoins FIRST/MIDDLE/LAST runs. Payloads arrive already decrypted. */
    class Assembler {
        private val partial = HashMap<Int, ByteArrayOutputStream>()

        fun offer(frame: Frame, payload: ByteArray): ByteArray? = when (frame.frameType) {
            TYPE_BULK -> payload
            TYPE_FIRST -> {
                partial[frame.channel] = ByteArrayOutputStream().apply { write(payload) }
                null
            }
            TYPE_MIDDLE -> {
                partial[frame.channel]?.write(payload)
                null
            }
            TYPE_LAST -> partial.remove(frame.channel)?.apply { write(payload) }?.toByteArray()
            else -> null
        }
    }
}
