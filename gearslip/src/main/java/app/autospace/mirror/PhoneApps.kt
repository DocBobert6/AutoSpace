package app.autospace.mirror

import app.autospace.host.AndroidAuto
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.autospace.GearslipLog

/** An app installed on the phone, offered on the car screen as something to mirror. */
class PhoneApp(val packageName: String, val label: String, val icon: ImageBitmap?)

/**
 * The launcher's view of the phone, shown in the car so an app can be started without picking
 * the phone up. Needs the `<queries>` entry in the manifest - package visibility hides other
 * apps from us otherwise, and the list comes back empty rather than failing.
 */
object PhoneApps {

    private const val ICON_PX = 128

    @Volatile private var cache: List<PhoneApp>? = null

    /** What the last scan found, or null before the first one. */
    fun cached(): List<PhoneApp>? = cache

    /** Scans and stores the result; [Prefetch] calls this so the screen opens already filled. */
    fun warm(context: Context) { cache = installed(context) }

    fun installed(context: Context): List<PhoneApp> {
        val manager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return manager.queryIntentActivities(intent, 0)
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .filterNot { AndroidAuto.matches(it.activityInfo.packageName, it.loadLabel(manager).toString()) }
            .distinctBy { it.activityInfo.packageName }
            .map {
                PhoneApp(
                    packageName = it.activityInfo.packageName,
                    label = it.loadLabel(manager).toString(),
                    icon = runCatching { it.loadIcon(manager).toImageBitmap() }.getOrNull(),
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /**
     * Starts an app on the phone's own screen, which is what the mirror is pointed at.
     *
     * Deliberately uses the application context: a composable in the car UI sees a context
     * bound to the projected display, and launching from that would try to place the activity
     * there - the exact thing the platform refuses.
     */
    fun launch(context: Context, packageName: String) {
        val app = context.applicationContext
        val intent = app.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            GearslipLog.e("no launch intent for $packageName")
            return
        }
        runCatching { app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onSuccess { GearslipLog.i("launched $packageName on the phone") }
            .onFailure { GearslipLog.e("launching $packageName failed", it) }
    }

    /** Opens the phone's accessibility settings, where the touch relay is switched on. */
    fun openAccessibilitySettings(context: Context) {
        val app = context.applicationContext
        runCatching {
            app.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { GearslipLog.e("could not open accessibility settings", it) }
    }

    private fun Drawable.toImageBitmap(): ImageBitmap {
        if (this is BitmapDrawable && bitmap != null) {
            return bitmap.copy(Bitmap.Config.ARGB_8888, false).asImageBitmap()
        }
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return bitmap.asImageBitmap()
    }
}
