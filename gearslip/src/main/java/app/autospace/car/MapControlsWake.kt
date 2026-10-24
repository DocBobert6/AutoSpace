package app.autospace.car

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import android.os.SystemClock

/**
 * Whether the map's buttons (settings, pan, zoom, locate) are showing.
 *
 * They fade out after [IDLE_MS] with no touch anywhere on the car screen, to leave the map clear,
 * and any touch brings them back. [touch] is called for every pointer event and is cheap: it only
 * writes state when the buttons were actually hidden.
 */
class MapControlsWake {

    var awake by mutableStateOf(true)
        private set

    private var lastTouch = SystemClock.uptimeMillis()

    fun touch() {
        lastTouch = SystemClock.uptimeMillis()
        if (!awake) awake = true
    }

    /** Called on a timer; hides the buttons once nothing has touched the screen for [IDLE_MS]. */
    fun tick() {
        if (awake && SystemClock.uptimeMillis() - lastTouch >= IDLE_MS) awake = false
    }

    companion object {
        const val IDLE_MS = 4_000L
    }
}

val LocalMapControlsWake = staticCompositionLocalOf { MapControlsWake() }
