package app.autospace.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import app.autospace.GearslipLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A caller as the car shows it: whatever the contacts app knows, or just the number. */
data class Caller(val number: String, val name: String?) {
    val label: String get() = name ?: number.ifBlank { "Unknown number" }
}

sealed interface CallUiState {
    data object None : CallUiState
    data class Ringing(val caller: Caller, val canDecline: Boolean) : CallUiState
    data class Active(val caller: Caller?) : CallUiState
}

/**
 * Tracks the phone's call state for the car screen, and answers or declines through the two
 * doors a sideloaded app can use: [android.telecom.TelecomManager.acceptRingingCall] to answer,
 * and [GearslipCallScreening]'s response to decline one that is still ringing.
 *
 * Ending a call already answered needs MODIFY_PHONE_STATE, a privileged permission Gearslip
 * cannot hold, so a call taken here is finished the way it would be without Gearslip at all -
 * from the phone itself, a paired headset, or the car's own Bluetooth if it has any. Call audio
 * stays on the phone's normal voice path too; nothing here sends it over the USB link, which
 * only ever carries media playback.
 */
object CarCalls {

    private val _state = MutableStateFlow<CallUiState>(CallUiState.None)
    val state: StateFlow<CallUiState> = _state.asStateFlow()

    private var telephony: TelephonyManager? = null
    private var watcher: TelephonyCallback? = null
    private var appContext: Context? = null
    private var pendingRespond: ((allow: Boolean) -> Unit)? = null
    private var ringingCaller: Caller? = null

    fun start(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        if (watcher != null) return
        if (!phoneStateGate(context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED)) return
        val manager = context.getSystemService(TelephonyManager::class.java) ?: return
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) = onPhoneState(state)
        }
        runCatching { manager.registerTelephonyCallback(context.mainExecutor, callback) }
            .onSuccess {
                telephony = manager; watcher = callback
                // r45: the call may already be active when watching starts (app
                // launched or resumed mid-call); the callback only fires on CHANGES,
                // so check the current state once instead of waiting for a transition
                // that never comes. A later OFFHOOK event is a no-op for the route
                // (previousSpeaker guard), so this cannot double-apply.
                if (CallRouteProbe.shouldApplyOnRegistration(manager.callState)) {
                    appContext?.let { CallSpeakerRoute.onCallActive(it) }
                }
            }
            .onFailure { GearslipLog.w("calls: could not watch call state: ${it.message}") }
    }

    fun stop() {
        val callback = watcher ?: return
        runCatching { telephony?.unregisterTelephonyCallback(callback) }
        telephony = null
        watcher = null
        appContext?.let { CallSpeakerRoute.onCallEnded(it) }
        pendingRespond = null
        ringingCaller = null
        _state.value = CallUiState.None
    }

    private fun onPhoneState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_IDLE -> {
                appContext?.let { CallSpeakerRoute.onCallEnded(it) }
                pendingRespond = null
                ringingCaller = null
                _state.value = CallUiState.None
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                appContext?.let { CallSpeakerRoute.onCallActive(it) }
                pendingRespond = null
                _state.value = CallUiState.Active(ringingCaller)
            }
            TelephonyManager.CALL_STATE_RINGING -> {
                // The number itself only ever arrives through the screening service; if this
                // fires first, the ringing card appears once incomingCall() catches up.
                ringingCaller?.let { _state.value = CallUiState.Ringing(it, pendingRespond != null) }
            }
        }
    }

    /** Called by [GearslipCallScreening] the moment a call comes in - the only place with a number. */
    fun incomingCall(context: Context, number: String?, respond: (allow: Boolean) -> Unit) {
        val caller = Caller(number.orEmpty(), number?.let { lookUpName(context, it) })
        ringingCaller = caller
        pendingRespond = respond
        _state.value = CallUiState.Ringing(caller, canDecline = true)
    }

    /** The screening window closed by itself - the call rings normally now, and can't be declined from here. */
    fun screeningExpired() {
        pendingRespond = null
        val caller = ringingCaller ?: return
        if (_state.value is CallUiState.Ringing) _state.value = CallUiState.Ringing(caller, canDecline = false)
    }

    fun decline() {
        val respond = pendingRespond ?: return
        pendingRespond = null
        runCatching { respond(false) }.onFailure { GearslipLog.w("calls: decline failed: ${it.message}") }
    }

    fun answer(context: Context) {
        if (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            GearslipLog.w("calls: answer needs ANSWER_PHONE_CALLS, which was never granted")
            return
        }
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return
        runCatching { telecom.acceptRingingCall() }
            .onFailure { GearslipLog.w("calls: answer failed: ${it.message}") }
    }

    private fun lookUpName(context: Context, number: String): String? {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }
}

/** Posts to the main thread after a delay - used to give up on a screening response Gearslip never sent. */
internal fun mainThreadDelay(ms: Long, action: () -> Unit) {
    Handler(Looper.getMainLooper()).postDelayed(action, ms)
}

/**
 * r45: the READ_PHONE_STATE gate, extracted so the failure is visible. The old
 * code returned silently here, which made a missing permission indistinguishable
 * from "no calls happened" in a trace. The permission check itself needs a
 * Context and cannot run on the JVM, so the gate takes the check result and the
 * test pins the branch plus this exact log text.
 */
internal const val MISSING_PHONE_STATE_LOG =
    "calls: call watching needs READ_PHONE_STATE, which was never granted"

internal fun phoneStateGate(granted: Boolean): Boolean {
    if (granted) return true
    GearslipLog.w(MISSING_PHONE_STATE_LOG)
    return false
}
