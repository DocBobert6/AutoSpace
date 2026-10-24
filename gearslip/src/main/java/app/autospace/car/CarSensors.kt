package app.autospace.car

import app.autospace.Protobuf
import app.autospace.Wire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the head unit's sensor channel has offered and sent, for the "Vehicle data" screen.
 *
 * [ServiceDiscovery.findSensorService] tells us which numeric sensor types this head unit is
 * willing to stream; [SensorType] names the ones publicly documented by other AA reverse-engineering
 * projects (aasdk, openauto). That mapping is carried over from other projects, not verified
 * against this exact unit, so it's a label, not a guarantee - the raw field dump next to it is
 * the ground truth. Once [onEvent] starts decoding real traffic from the 2018 Uconnect, correct
 * any type that turns out to be mislabeled.
 */
object CarSensors {

    data class Reading(val typeId: Int, val typeName: String, val raw: String, val updatedAtMs: Long)

    private val readingsFlow = MutableStateFlow<List<Reading>>(emptyList())
    val readings: StateFlow<List<Reading>> = readingsFlow

    /** Seeds one row per sensor type the head unit advertised, so the screen shows what's on
     * offer even before the first event for it arrives. */
    fun onDiscovered(types: List<Int>) {
        readingsFlow.value = types.map { id ->
            Reading(id, SensorType.name(id), "(waiting for data)", 0L)
        }
    }

    /**
     * One SensorEventIndication. Every reverse-engineered version of this message multiplexes
     * every sensor type into one shared set of message-level field numbers (field N = "the data
     * for sensor type N", roughly) rather than tagging events with the type id from
     * SensorType.type - so field number is read here as a best-effort type id, same as the
     * other services in this file.
     */
    fun onEvent(body: ByteArray) {
        val now = System.currentTimeMillis()
        val fields = Wire.fields(body)
        val byType = fields.groupBy { it.number }
        val updated = byType.mapValues { (number, group) ->
            Reading(
                number,
                SensorType.name(number),
                group.joinToString("; ") { field ->
                    field.bytes?.let { Protobuf.describe(it, indent = "").trim().replace("\n", ", ") }
                        ?: "varint=${field.varint}"
                },
                now,
            )
        }
        val existing = readingsFlow.value.associateBy { it.typeId }
        readingsFlow.value = (existing + updated).values.sortedBy { it.typeId }
    }

    fun clear() {
        readingsFlow.value = emptyList()
    }
}

/**
 * Sensor type ids as named across public Android Auto reverse-engineering references. Treated as
 * a label, not a fact - see [CarSensors] doc.
 */
object SensorType {
    private val NAMES = mapOf(
        1 to "Location (GPS)",
        2 to "Compass",
        3 to "Speed",
        4 to "RPM",
        5 to "Odometer",
        6 to "Fuel",
        7 to "Parking brake",
        8 to "Gear",
        9 to "Diagnostics",
        10 to "Night mode",
        11 to "Environment",
        12 to "HVAC",
        13 to "Driving status",
        14 to "Dead reckoned location",
        15 to "Passenger",
        16 to "Door",
        17 to "Light",
        18 to "Tire pressure",
        19 to "Accelerometer",
        20 to "Gyroscope",
        21 to "GPS satellites",
        22 to "Toll card",
    )

    fun name(id: Int): String = NAMES[id]?.let { "$it (#$id)" } ?: "Sensor #$id"
}
