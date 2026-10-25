package app.autospace.car

import android.content.Context
import androidx.car.app.model.CarText
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.Step
import app.autospace.GearslipLog
import app.autospace.audio.AudioLink
import app.autospace.speech.VoiceEngine
import app.autospace.speech.VoiceModels
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Spoken turn-by-turn on the car's directions channel (the GUIDANCE audio sink).
 *
 * A map app on the phone speaks its own prompts as navigation audio, which Android can't hand to
 * Gearslip without root, and which the phone mutes while USB audio runs. So Gearslip reads the
 * same turn the instrument cluster gets ([NavTurn]) and says it itself: once when the turn comes
 * up, again a kilometre or so out on a long stretch, and once more just before it. The words are
 * rendered with AutoSpace's own voice ([VoiceEngine]: Kokoro, Piper as fallback), never played
 * on the phone, and streamed to the car, which ducks the music under them the way it does for
 * real Android Auto. With neither voice model downloaded, a turn goes unspoken and the log says
 * so — there is no system voice left to fall back to.
 *
 * Nothing spoken is logged: road names are where the driver is going.
 */
object DirectionsVoice {

    private enum class Stage { FAR, PREPARED, DONE }

    private var appContext: Context? = null

    @Volatile private var link: AudioLink? = null
    @Volatile private var running = false
    private val speaker = Executors.newSingleThreadExecutor { Thread(it, "gearslip-directions").apply { isDaemon = true } }
    @Volatile private var latest = 0

    private var turnKey: String? = null
    private var stage = Stage.DONE

    fun init(context: Context) {
        appContext = context.applicationContext
        appContext?.let(VoiceModels::init)
    }

    /** The car opened its directions sink: start following the map app's turns. */
    fun attach(link: AudioLink) {
        this.link = link
        GearslipLog.i("guidance: the car offered a directions sink at ${link.sampleRate}Hz x${link.channels}")
        if (running) return
        running = true
        turnKey = null
        Thread({
            while (running) {
                runCatching { tick() }.onFailure { GearslipLog.w("guidance: ${it.message}") }
                Thread.sleep(TICK_MS)
            }
        }, "gearslip-directions-watch").apply { isDaemon = true }.start()
    }

    fun detach(link: AudioLink) {
        if (this.link !== link) return
        this.link = null
        running = false
    }

    private fun tick() {
        if (!CarSettings.speakDirections.value) return
        val turn = NavTurn.current()
        if (turn == null) {
            turnKey = null
            return
        }
        if (turn.rerouting) return
        val step = turn.step
        val instruction = instruction(step) ?: return
        val meters = turn.distance?.let(NavTurn::toMeters)
        val close = (turn.seconds != null && turn.seconds <= NEAR_SECONDS) || (meters != null && meters <= NEAR_METERS)

        val key = "${step.road.text()}|${step.maneuver?.type}|${step.maneuver?.roundaboutExitNumber}|${step.cue.text()}"
        if (key != turnKey) {
            turnKey = key
            if (step.maneuver?.type == Maneuver.TYPE_DEPART) {
                stage = Stage.DONE
                return
            }
            if (close || turn.distance == null) {
                say(instruction)
                stage = Stage.DONE
            } else {
                say("In ${spoken(turn.distance)}, $instruction")
                stage = if (meters != null && meters > PREPARE_FROM_METERS) Stage.FAR else Stage.PREPARED
            }
            return
        }
        when {
            stage != Stage.DONE && close -> {
                say(instruction)
                stage = Stage.DONE
            }
            stage == Stage.FAR && meters != null && meters <= PREPARE_AT_METERS -> {
                say("In ${spoken(turn.distance)}, $instruction")
                stage = Stage.PREPARED
            }
        }
    }

    /** What to do, in words: built from the manoeuvre when the app names one, else the app's own text. */
    private fun instruction(step: Step): String? {
        val road = step.road.text()
        val onto = if (road.isBlank()) "" else " onto $road"
        val toward = if (road.isBlank()) "" else " toward $road"
        val maneuver = step.maneuver
        val built = when (maneuver?.type) {
            Maneuver.TYPE_TURN_SLIGHT_LEFT -> "Turn slightly left$onto"
            Maneuver.TYPE_TURN_SLIGHT_RIGHT -> "Turn slightly right$onto"
            Maneuver.TYPE_TURN_NORMAL_LEFT -> "Turn left$onto"
            Maneuver.TYPE_TURN_NORMAL_RIGHT -> "Turn right$onto"
            Maneuver.TYPE_TURN_SHARP_LEFT -> "Turn sharply left$onto"
            Maneuver.TYPE_TURN_SHARP_RIGHT -> "Turn sharply right$onto"
            Maneuver.TYPE_U_TURN_LEFT, Maneuver.TYPE_U_TURN_RIGHT -> "Make a U-turn"
            Maneuver.TYPE_KEEP_LEFT -> "Keep left$toward"
            Maneuver.TYPE_KEEP_RIGHT -> "Keep right$toward"
            Maneuver.TYPE_FORK_LEFT -> "Keep left at the fork$toward"
            Maneuver.TYPE_FORK_RIGHT -> "Keep right at the fork$toward"
            Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT, Maneuver.TYPE_ON_RAMP_NORMAL_LEFT, Maneuver.TYPE_ON_RAMP_SHARP_LEFT,
            Maneuver.TYPE_ON_RAMP_U_TURN_LEFT -> "Take the ramp on the left$onto"
            Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT, Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT, Maneuver.TYPE_ON_RAMP_SHARP_RIGHT,
            Maneuver.TYPE_ON_RAMP_U_TURN_RIGHT -> "Take the ramp on the right$onto"
            Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT, Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT -> "Take the exit on the left$toward"
            Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT, Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT -> "Take the exit on the right$toward"
            Maneuver.TYPE_MERGE_LEFT -> "Merge left$onto"
            Maneuver.TYPE_MERGE_RIGHT -> "Merge right$onto"
            Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED -> "Merge$onto"
            Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW, Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE,
            Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW, Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE ->
                maneuver.roundaboutExitNumber.takeIf { it in 1..ORDINALS.size }
                    ?.let { "At the roundabout, take the ${ORDINALS[it - 1]} exit$onto" }
                    ?: "Go through the roundabout$onto"
            Maneuver.TYPE_ROUNDABOUT_ENTER_CW, Maneuver.TYPE_ROUNDABOUT_ENTER_CCW -> "Enter the roundabout"
            Maneuver.TYPE_ROUNDABOUT_EXIT_CW, Maneuver.TYPE_ROUNDABOUT_EXIT_CCW -> "Exit the roundabout$onto"
            Maneuver.TYPE_STRAIGHT -> "Continue straight$onto"
            Maneuver.TYPE_NAME_CHANGE -> "Continue$onto"
            Maneuver.TYPE_FERRY_BOAT, Maneuver.TYPE_FERRY_BOAT_LEFT, Maneuver.TYPE_FERRY_BOAT_RIGHT -> "Take the ferry"
            Maneuver.TYPE_FERRY_TRAIN, Maneuver.TYPE_FERRY_TRAIN_LEFT, Maneuver.TYPE_FERRY_TRAIN_RIGHT -> "Take the train"
            Maneuver.TYPE_DESTINATION, Maneuver.TYPE_DESTINATION_STRAIGHT -> "Your destination is ahead"
            Maneuver.TYPE_DESTINATION_LEFT -> "Your destination is on the left"
            Maneuver.TYPE_DESTINATION_RIGHT -> "Your destination is on the right"
            else -> null
        }
        return built ?: step.cue.text().ifBlank { null }
    }

    /** The app's own rounded distance, read the way a person would say it. */
    private fun spoken(d: Distance): String {
        val n = d.displayDistance
        val number = if (n % 1.0 == 0.0) n.toInt().toString() else "%.1f".format(n)
        val one = n == 1.0
        return when (d.displayUnit) {
            Distance.UNIT_KILOMETERS, Distance.UNIT_KILOMETERS_P1 -> if (one) "1 kilometer" else "$number kilometers"
            Distance.UNIT_MILES, Distance.UNIT_MILES_P1 -> when (n) {
                0.25 -> "a quarter mile"
                0.5 -> "half a mile"
                0.75 -> "three quarters of a mile"
                1.0 -> "1 mile"
                else -> "$number miles"
            }
            Distance.UNIT_FEET -> "$number feet"
            Distance.UNIT_YARDS -> "$number yards"
            else -> "${n.roundToInt()} meters"
        }
    }

    /** Newest wins: a prompt still waiting when the next one arrives is already out of date. */
    private fun say(text: String) {
        val id = ++latest
        speaker.execute {
            if (id != latest) return@execute
            runCatching { play(text, id) }.onFailure { GearslipLog.w("guidance: could not speak a turn: ${it.message}") }
        }
    }

    private fun play(text: String, id: Int) {
        val context = appContext ?: return
        val sink = link ?: return
        val pcm = VoiceEngine.synthesizeForSink(context, text, sink.sampleRate, sink.channels) ?: run {
            GearslipLog.w("guidance: no voice downloaded, a turn went unspoken")
            return
        }
        stream(sink, pcm)
        GearslipLog.i("guidance: spoke a turn (${pcm.size / (2 * sink.channels) * 1000 / sink.sampleRate} ms)")
    }

    /** Start, the words in 20 ms chunks at about real time, a little silence, Stop. */
    private fun stream(sink: AudioLink, pcm: ByteArray) {
        val frame = 2 * sink.channels
        val chunk = sink.sampleRate / 50 * frame
        val chunkMs = 20L
        val padded = pcm + ByteArray(sink.sampleRate / 5 * frame) // 200 ms tail so the last word isn't clipped
        sink.start()
        try {
            val began = System.currentTimeMillis()
            var offset = 0
            var sent = 0
            while (offset < padded.size && link === sink) {
                val size = minOf(chunk, padded.size - offset)
                sink.write(padded.copyOfRange(offset, offset + size), size)
                offset += size
                sent++
                val ahead = sent * chunkMs - (System.currentTimeMillis() - began)
                if (ahead > LEAD_MS) Thread.sleep(ahead - LEAD_MS)
            }
            // Let the car play what it has before closing the stream on it.
            Thread.sleep(LEAD_MS)
        } finally {
            sink.stop()
        }
    }

    private fun CarText?.text() = this?.toString().orEmpty()

    private const val TICK_MS = 500L
    private const val NEAR_SECONDS = 12L
    private const val NEAR_METERS = 120
    private const val PREPARE_FROM_METERS = 2_500
    private const val PREPARE_AT_METERS = 1_000
    private const val LEAD_MS = 100L
    private val ORDINALS = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth")
}
