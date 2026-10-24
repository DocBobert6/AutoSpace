package app.autospace

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LIVI 2.0.1 offers a second screen alongside the main one, and lists that screen's input first:
 *   channel 3:  video sink, no display id (the main screen)
 *   channel 19: video sink, display_id = 1, display_type = 1 (a cluster)
 *   channel 20: input service with only display_id = 1
 *   channel 8:  input service with a 1280x720 touchscreen and keys (the main screen)
 * Taking the first input service left every touch on the main screen ignored.
 */
class ServiceDiscoveryDisplayTest {

    private fun varintField(field: Int, value: Long) = Protobuf.varintField(field, value)

    private fun lenField(field: Int, body: ByteArray): ByteArray =
        Protobuf.varint(((field shl 3) or 2).toLong()) + Protobuf.varint(body.size.toLong()) + body

    private fun videoSink(id: Int, displayId: Int?): ByteArray {
        val config = varintField(1, 2L) + varintField(2, 1L) + varintField(5, 180L)
        var sink = varintField(1, 3L) + lenField(4, config)
        if (displayId != null) sink += varintField(6, displayId.toLong()) + varintField(7, 1L)
        return lenField(1, varintField(1, id.toLong()) + lenField(3, sink))
    }

    private fun input(id: Int, body: ByteArray) = lenField(1, varintField(1, id.toLong()) + lenField(4, body))

    private fun liviResponse(): ByteArray =
        videoSink(3, null) +
            videoSink(19, 1) +
            input(20, varintField(5, 1L)) +
            input(8, lenField(1, Protobuf.varint(3L) + Protobuf.varint(4L)) + lenField(2, varintField(1, 1280L) + varintField(2, 720L)))

    @Test
    fun picksTheMainScreensVideo() {
        val video = ServiceDiscovery.findVideoService(liviResponse())!!
        assertEquals(3, video.serviceId)
        assertEquals(0, video.displayId)
    }

    @Test
    fun picksTheInputForTheProjectedScreen() {
        val input = ServiceDiscovery.findInputService(liviResponse(), displayId = 0)!!
        assertEquals(8, input.serviceId)
        assertEquals(1280, input.touchWidth)
        assertEquals(720, input.touchHeight)
        assertEquals(listOf(3, 4), input.keycodesSupported)
    }
}
