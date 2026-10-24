package app.autospace.car

import android.content.Context
import app.autospace.CertProvider
import app.autospace.GearslipLog
import app.autospace.mirror.PhoneApps
import java.util.concurrent.Executors

/**
 * Loads what the car screens show before anyone opens them: the car and media apps with their
 * icons, the phone's launcher apps, the weather and the calendar. Also unlocks the certificate,
 * so a head unit's first message is answered at once. Each screen paints from these
 * results at once and refreshes behind them, so nothing waits on the package manager, the
 * network or the calendar provider.
 *
 * Safe to call as often as you like: calls closer together than [MIN_GAP_MS] are dropped.
 */
object Prefetch {

    private const val MIN_GAP_MS = 60_000L

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "gearslip-prefetch").apply { isDaemon = true } }
    @Volatile private var lastRun = 0L

    /** [force] skips the throttle; use it when something changed, such as a permission grant. */
    fun warm(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRun < MIN_GAP_MS) return
        lastRun = now
        val app = context.applicationContext
        worker.execute {
            val start = System.currentTimeMillis()
            step("certificate") { CertProvider.warm(app) }
            step("launcher apps") { warmLauncherApps(app) }
            step("phone apps") { PhoneApps.warm(app) }
            step("weather and calendar") { warmDashboard(app) }
            GearslipLog.i("prefetch done in ${System.currentTimeMillis() - start}ms")
        }
    }

    // One failing loader must not stop the others.
    private fun step(name: String, block: () -> Unit) {
        runCatching(block).onFailure { GearslipLog.w("prefetch: $name failed: ${it.message}") }
    }
}
