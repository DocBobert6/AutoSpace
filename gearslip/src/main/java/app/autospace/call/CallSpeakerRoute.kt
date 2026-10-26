package app.autospace.call

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import app.autospace.GearslipLog
import app.autospace.car.CarSettings

/**
 * The call-speaker option (2026-10-25, Robert): when [CarSettings.callSpeaker] is on, a call
 * that goes active is moved to the phone's speaker — which also makes the phone's microphone
 * the one the caller hears, sidestepping a broken car microphone — and the previous speaker
 * state is restored when the call ends, so speakerphone is never left stuck on.
 *
 * Why this shape, and not a route command: the call's audio route belongs to Telecom and
 * the system dialer. AutoSpace is neither, and [android.telecom.InCallService.setAudioRoute]
 * is dialer-only, so there is no clean "route to speaker" call to make. What a sideloaded
 * app CAN do is flip the global voice-call speaker flag ([AudioManager.isSpeakerphoneOn],
 * already covered by the app's MODIFY_AUDIO_SETTINGS permission) and, on API 31+, attempt
 * the communication-device route. Both are best-effort: if the phone is paired to the car,
 * Telecom may reassert the Bluetooth route and this setting simply loses — that is logged,
 * re-asserted at most twice (r45), never looped, and never a crash. Calls only: nothing
 * here touches voice commands, listening, assistant speech, or media playback, which use
 * other streams.
 *
 * r45 (2026-10-26, Robert): two bounded additions, neither of which can force
 * Telecom's route — the honest limit stands. (1) Observation: while any observed
 * call is active, the route the app can see (audio mode, speakerphone read-back,
 * SCO state, output device types) is logged every 2 s, so the next trace shows
 * the actual route instead of only the requested flag. Observation runs whether
 * or not the speaker option is on. (2) Bounded re-assert: when the option is on,
 * the flag is re-asserted exactly twice, at ~+1 s and ~+3 s after OFFHOOK, without
 * re-capturing the pre-call state — a race where Telecom finalizes the route
 * after the first set is the thing this tests. It is a bounded sequence, never a
 * loop: after the second re-assert nothing more is scheduled, and ending the call
 * cancels anything pending. The communication-device path is deliberately NOT
 * re-asserted and its set=true result is never presented as routing evidence.
 */
object CallSpeakerRoute {

    /** Speakerphone state captured when a call started, so the end of the call restores it. */
    private var previousSpeaker: Boolean? = null
    private var communicationDeviceSet = false

    /** r45: observation + re-assert session state. Both live exactly one call. */
    private var observeContext: Context? = null
    private var observing = false
    private var reassertsFired = 0

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    /** One route sample now, then every OBSERVE_INTERVAL_MS until the call ends. */
    private val observeRunnable = object : Runnable {
        override fun run() {
            val ctx = observeContext ?: return
            val audio = ctx.getSystemService(AudioManager::class.java) ?: return
            runCatching {
                val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
                @Suppress("DEPRECATION")
                val sco = audio.isBluetoothScoOn
                GearslipLog.i(CallRouteProbe.sampleLine(audio.mode, audio.isSpeakerphoneOn, sco, outputs))
            }.onFailure { GearslipLog.w("calls: route sample failed: ${it.message}") }
            if (observing) mainHandler.postDelayed(this, CallRouteProbe.OBSERVE_INTERVAL_MS)
        }
    }

    /** Bounded re-assert chain: exactly REASSERT_AT_MS.size firings, then silence. */
    private val reassertRunnable = object : Runnable {
        override fun run() {
            val ctx = observeContext ?: return
            // Session over (call ended or watching stopped) or the user turned the
            // option off mid-call: stop the chain, do not re-capture, do not loop.
            if (previousSpeaker == null || !CarSettings.callSpeaker.value) return
            val audio = ctx.getSystemService(AudioManager::class.java) ?: return
            val n = reassertsFired + 1
            runCatching {
                @Suppress("DEPRECATION")
                if (!audio.isSpeakerphoneOn) {
                    audio.isSpeakerphoneOn = true
                    GearslipLog.i("calls: speaker re-assert $n set (was off)")
                } else {
                    GearslipLog.i("calls: speaker re-assert $n already on")
                }
            }.onFailure { GearslipLog.w("calls: speaker re-assert $n failed: ${it.message}") }
            reassertsFired = n
            CallRouteProbe.nextReassertDelayMs(reassertsFired)?.let { mainHandler.postDelayed(this, it) }
        }
    }

    /** Called when [CarCalls] observes a call go active (OFFHOOK), answered or dialled. */
    fun onCallActive(context: Context) {
        // r45: observation starts on EVERY observed call, whether or not the speaker
        // option is on — the next trace needs the route either way.
        startObservation(context)
        if (!CarSettings.callSpeaker.value) return
        if (previousSpeaker != null) return // Already applied for this call; never re-capture.
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            previousSpeaker = audio.isSpeakerphoneOn
            if (previousSpeaker == false) {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = true
                GearslipLog.i("calls: speaker option on, speakerphone set (was off)")
            } else {
                GearslipLog.i("calls: speaker option on, speakerphone already on")
            }
        }.onFailure { GearslipLog.w("calls: speaker option failed to set speakerphone: ${it.message}") }
        // API 31+: the newer communication-device route, attempted only as a second,
        // best-effort mechanism. During a Telecom call the app is not the communication
        // owner, so this commonly returns false and changes nothing — that is fine, the
        // speakerphone flag above is the mechanism that can actually move a call.
        runCatching {
            val speaker = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } ?: return@runCatching
            communicationDeviceSet = audio.setCommunicationDevice(speaker)
            GearslipLog.i("calls: speaker option communication-device set=$communicationDeviceSet")
        }.onFailure { GearslipLog.w("calls: speaker option communication-device failed: ${it.message}") }
        // r45: bounded re-assert — exactly two, at ~+1 s and ~+3 s after OFFHOOK.
        // No re-capture: previousSpeaker still holds the pre-call state, so the end
        // of the call restores exactly what the call found.
        reassertsFired = 0
        CallRouteProbe.nextReassertDelayMs(0)?.let { mainHandler.postDelayed(reassertRunnable, it) }
    }

    /** Called when [CarCalls] observes the call end (IDLE), or when call watching stops. */
    fun onCallEnded(context: Context) {
        stopObservation()
        val previous = previousSpeaker ?: return
        previousSpeaker = null
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            if (communicationDeviceSet) {
                audio.clearCommunicationDevice()
                communicationDeviceSet = false
            }
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = previous
            GearslipLog.i("calls: speaker option restored speakerphone to $previous")
        }.onFailure { GearslipLog.w("calls: speaker option failed to restore speakerphone: ${it.message}") }
    }

    private fun startObservation(context: Context) {
        if (observing) return
        observing = true
        observeContext = context.applicationContext
        mainHandler.post(observeRunnable)
    }

    private fun stopObservation() {
        observing = false
        observeContext = null
        reassertsFired = 0
        mainHandler.removeCallbacks(observeRunnable)
        mainHandler.removeCallbacks(reassertRunnable)
    }
}
