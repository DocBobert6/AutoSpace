package app.autospace.speech

/**
 * Pure microphone-choice logic for voice listens (r28 hotword consistency).
 *
 * The defect it fixes, proven from code: the hotword loop's silent-car-mic
 * fallback lived and died inside one hotword hold ([HotwordListener.runStream]
 * locals), while the assistant listen that a hotword triggers re-selected its
 * microphone from scratch ([SpeechEngine.openSpeechMicSource]) — so a hotword
 * heard on the phone's microphone was routinely followed by a command listen
 * on the dead car microphone. The fix is two pieces of memory in [SpeechEngine]:
 * a one-shot inheritance (the listen a hotword triggers keeps the mic the
 * hotword was heard on) and a session-sticky dead-car-mic flag (once the car
 * mic is proven dead, listens stop trusting it until the session ends).
 *
 * Everything here is pure so it runs on the plain JVM: the actual microphone
 * objects (AudioRecord, the car's channel) cannot. The wiring in [SpeechEngine]
 * is deliberately thin — it reads settings, consults this file, and opens
 * exactly the microphone this file names.
 */
internal object MicSelection {

    /** How long a hotword-heard mic inheritance stays valid for the next listen. */
    const val INHERIT_VALID_MS = 60_000L

    /**
     * Which microphone a listen uses. Order is the contract, and the toggles
     * keep winning over everything: the phone-mic-for-voice setting first,
     * then a car mic already proven dead this session, then a mic inherited
     * from the hotword that triggered this listen, then the car's mic when
     * one is actually sending, else the phone's. Nothing here can ever pick
     * the car mic over an explicit phone choice or over proven deadness.
     */
    fun selectSource(
        phoneMicSetting: Boolean,
        carMicDead: Boolean,
        inheritedPhone: Boolean,
        carAvailable: Boolean,
    ): SpeechEngine.MicSource = when {
        phoneMicSetting -> SpeechEngine.MicSource.PHONE
        carMicDead -> SpeechEngine.MicSource.PHONE
        inheritedPhone -> SpeechEngine.MicSource.PHONE
        carAvailable -> SpeechEngine.MicSource.CAR
        else -> SpeechEngine.MicSource.PHONE
    }
}

/**
 * Digital-silence accounting, shared by the hotword loop and the button
 * listen so the two can never drift apart on what "silent" means. A chunk
 * is silent when every sample in it is within [amplitude] of zero — the
 * same shape [HotwordListener] already ships (its constant is the single
 * source, not a copy). [allSilent] stays true only while every chunk so
 * far was silent; [add] returns whether just this chunk was silent, which
 * is what the hotword's consecutive-silence counter needs.
 */
internal class SilenceTracker(
    private val amplitude: Int = HotwordListener.SILENCE_AMPLITUDE,
) {
    var chunksSeen = 0
        private set
    var allSilent = true
        private set

    fun add(frame: ShortArray, n: Int): Boolean {
        chunksSeen++
        var silent = true
        for (i in 0 until n) {
            val v = frame[i].toInt()
            if (v > amplitude || v < -amplitude) {
                silent = false
                break
            }
        }
        if (!silent) allSilent = false
        return silent
    }
}

/**
 * One-shot inheritance of the microphone a hotword was heard on. Only ever
 * holds the phone's microphone: a hotword heard on the car's mic sets
 * nothing, so normal selection is untouched. The 60 s validity is a
 * staleness guard, not a window the driver is expected to hit — a
 * hotword-triggered listen starts within about two seconds of the trigger
 * (main-thread post plus the mic-release wait) — so an unconsumed
 * inheritance can never leak into an unrelated later press.
 */
internal class HotwordMicOverride {
    private var phoneSinceMs: Long? = null

    fun noteHeardOnPhone(nowMs: Long) {
        phoneSinceMs = nowMs
    }

    /** True once, and only while the inheritance is still fresh. Always consumes. */
    fun takePhone(nowMs: Long): Boolean {
        val since = phoneSinceMs ?: return false
        phoneSinceMs = null
        return nowMs - since <= MicSelection.INHERIT_VALID_MS
    }

    fun clear() {
        phoneSinceMs = null
    }
}
