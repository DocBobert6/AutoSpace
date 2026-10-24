package app.autospace.call

import android.telecom.Call
import android.telecom.CallScreeningService
import app.autospace.GearslipLog

/**
 * Sees every incoming call before it rings, so the car screen can show who is calling and
 * decline it in the same window a spam-blocker would use. Inert until the driver sets Gearslip
 * as the phone's caller ID and spam app - the same on-device consent Truecaller-style apps ask
 * for, not a privileged permission.
 *
 * The system gives an app a few seconds to answer before it lets the call ring on its own, so a
 * response is sent either when the driver taps a button or when that window is about to close,
 * whichever comes first. Answering a call that has already passed this point still works, since
 * [CarCalls.answer] goes through the ordinary ringing-call API rather than through screening.
 */
class GearslipCallScreening : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart
        var responded = false

        val respond: (Boolean) -> Unit = { allow ->
            if (!responded) {
                responded = true
                runCatching {
                    respondToCall(
                        callDetails,
                        CallResponse.Builder()
                            .setDisallowCall(!allow)
                            .setRejectCall(!allow)
                            .setSkipNotification(false)
                            .build(),
                    )
                }.onFailure { GearslipLog.w("calls: screening response failed: ${it.message}") }
            }
        }

        CarCalls.incomingCall(applicationContext, number, respond)

        // Answer for the system if nobody has by the time its own patience runs out, so a call
        // Gearslip is slow to show never gets silently blocked.
        mainThreadDelay(SCREENING_WINDOW_MS) {
            respond(true)
            CarCalls.screeningExpired()
        }
    }

    private companion object {
        const val SCREENING_WINDOW_MS = 3_500L
    }
}
