package app.autospace

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Who has the car's screen: Gearslip, or the car's own interface. Android Auto's "Exit" hands the
 * screen back to the car with the session still running, so music keeps playing; the car's own
 * Android Auto button brings Gearslip back. [GearslipRunner] fills in [request] while connected.
 */
object CarFocus {

    private val projectedFlow = MutableStateFlow(true)

    /** False while the car shows its own interface. */
    val projected: StateFlow<Boolean> = projectedFlow

    @Volatile internal var request: (() -> Unit)? = null

    /** Asks the car to show its own interface. Does nothing when no car is connected. */
    fun exitToCar() {
        request?.invoke()
    }

    internal fun set(projected: Boolean) {
        projectedFlow.value = projected
    }
}
