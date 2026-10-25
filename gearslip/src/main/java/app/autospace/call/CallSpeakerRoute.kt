package app.autospace.call

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
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
 * never retried in a loop, and never a crash. Calls only: nothing here touches voice
 * commands, listening, assistant speech, or media playback, which use other streams.
 */
object CallSpeakerRoute {

    /** Speakerphone state captured when a call started, so the end of the call restores it. */
    private var previousSpeaker: Boolean? = null
    private var communicationDeviceSet = false

    /** Called when [CarCalls] observes a call go active (OFFHOOK), answered or dialled. */
    fun onCallActive(context: Context) {
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
    }

    /** Called when [CarCalls] observes the call end (IDLE), or when call watching stops. */
    fun onCallEnded(context: Context) {
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
}
