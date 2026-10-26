package app.autospace.call

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.telephony.TelephonyManager

/**
 * Pure, JVM-testable helpers for the r45 call-route fix (2026-10-26, Robert).
 *
 * The problem they serve: the call-speaker mechanism can only flip the global
 * speakerphone flag — it cannot ask Telecom which device a call is actually on,
 * so a trace could never show whether the flag moved the audio or Telecom kept
 * it on Bluetooth or the earpiece. The observation sample below logs everything
 * the app CAN see (audio mode, speakerphone read-back, SCO state, output device
 * types) so the next trace discriminates the real route. Nothing here logs a
 * number, a contact, or any call content — device types only.
 *
 * Honest limit, kept in the code: nothing in this app can force Telecom's
 * route short of becoming the default dialer, so these helpers improve the
 * odds and the diagnosis, never guarantee speaker audio.
 */
object CallRouteProbe {

    /** Observation cadence: one route sample every 2 s while a call is active. */
    const val OBSERVE_INTERVAL_MS = 2000L

    /**
     * Bounded re-assert offsets from OFFHOOK, absolute: exactly two re-asserts,
     * at ~+1 s and ~+3 s. This is NOT a loop — after the second one nothing more
     * is scheduled, and a re-assert never re-captures the previous speaker state.
     */
    val REASSERT_AT_MS = listOf(1000L, 3000L)

    /**
     * Delay before the next re-assert, given how many have already fired, or null
     * when the bounded sequence is exhausted. The first delay is absolute (+1 s);
     * later delays are relative to the previous re-assert (+3 s absolute = +2 s
     * after the first).
     */
    fun nextReassertDelayMs(fired: Int): Long? {
        if (fired < 0 || fired >= REASSERT_AT_MS.size) return null
        val at = REASSERT_AT_MS[fired]
        val prev = if (fired == 0) 0L else REASSERT_AT_MS[fired - 1]
        return at - prev
    }

    /** True only when a call is already active at registration time (OFFHOOK). */
    fun shouldApplyOnRegistration(callState: Int): Boolean =
        callState == TelephonyManager.CALL_STATE_OFFHOOK

    fun modeName(mode: Int): String = when (mode) {
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        else -> "mode=$mode"
    }

    fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "BLE_BROADCAST"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        else -> "type=$type"
    }

    /**
     * One observation line. Device types only — never a number, contact, or any
     * call content. `speaker` is the read-back AFTER any flag set, so the trace
     * shows what the flag actually is, not what was requested.
     */
    fun sampleLine(mode: Int, speaker: Boolean, sco: Boolean, outputTypes: List<Int>): String {
        val outputs = outputTypes.joinToString(",") { deviceTypeName(it) }
        return "calls: route sample mode=${modeName(mode)} speaker=$speaker sco=$sco outputs=[$outputs]"
    }
}
