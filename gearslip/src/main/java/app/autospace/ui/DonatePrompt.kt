package app.autospace.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import app.autospace.GearslipLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The donation ask on Home, after a drive that worked. A driver sees it first after their third
 * such drive, so Gearslip has earned it, and then at most once every three months. Tapping either
 * donate button ends it for good.
 */
object DonatePrompt {

    private const val PREFS = "donate_prompt"
    private const val KEY_DRIVES = "drives"
    private const val KEY_LAST = "last_shown"
    private const val KEY_DONATED = "donated"
    private const val KEY_WAITING = "waiting"
    private const val FIRST_AFTER = 3
    private const val GAP_MS = 90L * 24 * 60 * 60 * 1000

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var loaded = false
    private val flow = MutableStateFlow(false)

    /** True while the ask is waiting for the driver to open Home. */
    fun pending(context: Context): StateFlow<Boolean> {
        if (!loaded) {
            loaded = true
            flow.value = prefs(context).getBoolean(KEY_WAITING, false)
        }
        return flow
    }

    /** A drive that reached the car's screen ended cleanly. Asks if it's time. */
    fun onDriveWorked(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_DONATED, false)) return
        val drives = p.getInt(KEY_DRIVES, 0) + 1
        val due = drives >= FIRST_AFTER && System.currentTimeMillis() - p.getLong(KEY_LAST, 0L) > GAP_MS
        p.edit().putInt(KEY_DRIVES, drives).apply {
            if (due) putBoolean(KEY_WAITING, true)
        }.apply()
        if (due) {
            loaded = true
            flow.value = true
        }
    }

    /** The driver closed the ask; [donated] if they picked a way to give. */
    fun answer(context: Context, donated: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_WAITING, false)
            .putLong(KEY_LAST, System.currentTimeMillis())
            .apply { if (donated) putBoolean(KEY_DONATED, true) }
            .apply()
        loaded = true
        flow.value = false
        GearslipLog.i(if (donated) "donate prompt: opened a donation link" else "donate prompt: not now")
    }
}

/** Shows the donation ask over Home when one is waiting and the car isn't connected. */
// AutoSpace: no donation recipient is configured for this fork, so the upstream
// donation ask is disabled rather than sending fork users to the upstream author.

// AutoSpace: donation ask disabled — no donation recipient is configured for this fork,
// so fork users are never asked to pay the upstream author.
@Composable
internal fun DonateDialog(live: Boolean) {
}
