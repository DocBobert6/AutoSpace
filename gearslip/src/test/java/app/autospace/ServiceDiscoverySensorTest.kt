package app.autospace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The 2018 Uconnect's sensor and mic services, from `archive/spike/car-run-2026-09-16-run2.log`.
 * Channel 7 offers sensors in Service field 2; channel 3 is the mic in field 5. Reading the wrong
 * field finds nothing at all, so the sensor channel never opens.
 */
class ServiceDiscoverySensorTest {

    private fun varintField(field: Int, value: Long) = Protobuf.varintField(field, value)

    private fun lenField(field: Int, body: ByteArray): ByteArray =
        Protobuf.varint(((field shl 3) or 2).toLong()) + Protobuf.varint(body.size.toLong()) + body

    private fun micSource() = lenField(
        1,
        varintField(1, 3L) + lenField(5, varintField(1, 1L) + lenField(2, varintField(1, 16_000L))),
    )

    private fun sensorSource(types: List<Int>): ByteArray {
        val sensors = types.fold(ByteArray(0)) { acc, t -> acc + lenField(1, varintField(1, t.toLong())) }
        return lenField(1, varintField(1, 7L) + lenField(2, sensors + varintField(2, 256L)))
    }

    @Test
    fun findsUconnectSensorsInFieldTwo() {
        val response = micSource() + sensorSource(listOf(1, 3, 10, 13, 20, 21))
        val sensors = ServiceDiscovery.findSensorService(response)
        assertNotNull(sensors)
        sensors!!
        assertEquals(7, sensors.serviceId)
        assertEquals(listOf(1, 3, 10, 13, 20, 21), sensors.types)
    }
}
