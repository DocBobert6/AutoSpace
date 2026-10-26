package app.autospace.car

import android.content.Context
import app.autospace.GearslipLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Simulated car session for couch testing (Robert, 2026-10-25):
 * a test switch that fakes a car session on the phone alone, so the
 * hotword loop, the assistant and the guidance plumbing can run
 * without a head unit. It changes NO loop logic: the hotword loop's
 * session check simply also accepts this flag (see
 * CarServices.ensureTestHotword), and everything else runs exactly
 * as it does in a real session.
 *
 * Guardrails, all load-bearing:
 * - In-memory ONLY: the flag lives in this object, is never
 *   persisted, and always starts off — a process restart, and any
 *   real accessory connection, both clear it.
 * - Auto-OFF on a real session: GearslipActivity deactivates it the
 *   moment a real accessory (USB) or socket (wireless) session
 *   starts, so a test flag can never ride into a real drive.
 * - Always visible: GearslipApp shows a TEST MODE banner on every
 *   phone screen while it is on.
 * - Attributed in logs: activation/deactivation are logged, and the
 *   hotword loop logs when it is listening under test mode only.
 * - Honest limit, stated on the toggle itself: without the car
 *   preview open there is no screen for the assistant overlay, so
 *   listening still runs (and replies still speak) but nothing is
 *   shown; and guidance turns still need a real map app's route,
 *   which test mode cannot fake — it enables the plumbing, not a
 *   route.
 */
object TestMode {

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun isActive(): Boolean = _active.value

    /**
     * Turns test mode on and makes sure the hotword loop exists to
     * run under it (configuring + starting it is guarded, so calling
     * this when a real or preview session already started it is a
     * no-op for the loop). Also inits the assistant, whose start()
     * silently no-ops without an application context.
     */
    fun activate(context: Context) {
        if (_active.value) return
        _active.value = true
        GearslipLog.i("test mode: simulated car session ON (not a real accessory)")
        CarServices.ensureTestHotword(context)
        CarAssistant.init(context)
    }

    fun deactivate() {
        if (!_active.value) return
        _active.value = false
        GearslipLog.i("test mode: simulated car session OFF")
    }
}
